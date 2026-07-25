package com.pocketfinancer.pipeline

import android.content.Context
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.await
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Idempotent process-local admission pause for incoming parser work.
 *
 * Releasing is internally non-cancellable. Callers should still release from a
 * `finally` block after their destructive operation completes.
 */
interface SmsWorkAdmissionPause {
    val isReleased: Boolean

    /**
     * Waits for enqueues admitted before this pause, then cancels the persisted
     * unique chain. Admission remains closed after this method returns.
     */
    suspend fun cancelPending()

    suspend fun release()
}

/**
 * Owns lifecycle control for the parser's unique WorkManager chain.
 *
 * Destructive reset must call [pauseAdmissions], invoke
 * [SmsWorkAdmissionPause.cancelPending] before entering its maintenance
 * window, retain the handle through the reset, and release it in `finally`.
 */
interface SmsWorkController {
    /**
     * Atomically closes scheduler admission and returns a pause token.
     *
     * This method is intentionally non-suspending, so cancellation cannot lose
     * a pause token between gate acquisition and delivery to the caller.
     */
    fun pauseAdmissions(): SmsWorkAdmissionPause

    /**
     * Cancels the persisted chain without changing admission state.
     *
     * This is useful for a second cancellation while an admission pause is
     * already held. It must not be used alone to establish a reset boundary.
     */
    suspend fun cancelPending()
}

/**
 * Shared gate used by the scheduler and controller.
 *
 * The short synchronized section covers check -> enqueue -> operation
 * registration. No WorkManager future is awaited while holding [monitor].
 */
@Singleton
internal class SmsWorkAdmissionGate @Inject constructor() {
    private val monitor = Any()
    private var pauseCount = 0
    private val admittedEnqueues = linkedSetOf<Operation>()

    fun enqueueIfOpen(enqueue: () -> Operation): Boolean = synchronized(monitor) {
        if (pauseCount > 0) {
            return@synchronized false
        }

        val operation = enqueue()
        admittedEnqueues += operation
        operation.result.addListener(
            {
                synchronized(monitor) {
                    admittedEnqueues.remove(operation)
                }
            },
            DIRECT_EXECUTOR
        )
        true
    }

    fun pause(): GatePause = synchronized(monitor) {
        pauseCount += 1
        GatePause(
            gate = this,
            admittedEnqueues = admittedEnqueues.toList()
        )
    }

    private fun resume() {
        synchronized(monitor) {
            check(pauseCount > 0) { "SMS work admission pause underflow" }
            pauseCount -= 1
        }
    }

    class GatePause internal constructor(
        private val gate: SmsWorkAdmissionGate,
        val admittedEnqueues: List<Operation>
    ) {
        private val released = AtomicBoolean(false)

        val isReleased: Boolean
            get() = released.get()

        fun release() {
            if (released.compareAndSet(false, true)) {
                gate.resume()
            }
        }
    }

    private companion object {
        val DIRECT_EXECUTOR = java.util.concurrent.Executor { command ->
            command.run()
        }
    }
}

@Singleton
class WorkManagerSmsWorkController @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val admissionGate: SmsWorkAdmissionGate
) : SmsWorkController {
    override fun pauseAdmissions(): SmsWorkAdmissionPause =
        WorkManagerAdmissionPause(
            gatePause = admissionGate.pause(),
            cancelAction = ::cancelUniqueWork
        )

    override suspend fun cancelPending() {
        cancelUniqueWork().await()
    }

    private fun cancelUniqueWork(): Operation =
        WorkManager.getInstance(context)
            .cancelUniqueWork(SmsWorkSchedulerImpl.UNIQUE_SMS_PARSER_WORK)

    private class WorkManagerAdmissionPause(
        private val gatePause: SmsWorkAdmissionGate.GatePause,
        private val cancelAction: () -> Operation
    ) : SmsWorkAdmissionPause {
        override val isReleased: Boolean
            get() = gatePause.isReleased

        override suspend fun cancelPending() {
            check(!isReleased) { "SMS work admission pause has been released" }
            withContext(NonCancellable) {
                var enqueueFailure: Throwable? = null
                gatePause.admittedEnqueues.forEach { operation ->
                    try {
                        operation.await()
                    } catch (failure: Throwable) {
                        if (enqueueFailure == null) {
                            enqueueFailure = failure
                        } else {
                            enqueueFailure!!.addSuppressed(failure)
                        }
                    }
                }

                var cancellationFailure: Throwable? = null
                try {
                    cancelAction().await()
                } catch (failure: Throwable) {
                    cancellationFailure = failure
                }

                val failure = enqueueFailure ?: cancellationFailure
                if (failure != null) {
                    cancellationFailure
                        ?.takeIf { it !== failure }
                        ?.let(failure::addSuppressed)
                    throw failure
                }
            }
        }

        override suspend fun release() {
            withContext(NonCancellable) {
                gatePause.release()
            }
        }
    }
}
