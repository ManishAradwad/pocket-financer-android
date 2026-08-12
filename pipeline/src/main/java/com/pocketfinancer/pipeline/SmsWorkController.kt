package com.pocketfinancer.pipeline

import android.content.Context
import androidx.work.Operation
import androidx.work.WorkManager
import androidx.work.await
import com.pocketfinancer.data.repository.SmsIngestionRepository
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

    /**
     * Applies the automatic-processing consistency boundary without cancelling
     * the one running worker. Pending candidates and their candidate-scoped
     * WorkManager requests are discarded atomically; the single claimed
     * candidate has already snapshotted ON and finishes normally.
     *
     * @return number of encrypted pending candidates removed.
     */
    suspend fun discardPendingAutomaticWork(): Int
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
    private val admissionGate: SmsWorkAdmissionGate,
    private val ingestionRepository: SmsIngestionRepository
) : SmsWorkController {
    override fun pauseAdmissions(): SmsWorkAdmissionPause =
        WorkManagerAdmissionPause(
            gatePause = admissionGate.pause(),
            cancelAction = ::cancelUniqueWork
        )

    override suspend fun cancelPending() {
        cancelUniqueWork().await()
    }

    override suspend fun discardPendingAutomaticWork(): Int =
        discardPendingAutomaticCandidatesAndNotifications(
            context = context,
            admissionGate = admissionGate,
            ingestionRepository = ingestionRepository
        )

    private fun cancelUniqueWork(): Operation =
        WorkManager.getInstance(context)
            .cancelAllWorkByTag(SmsParserWorker.WORK_TAG)

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

internal suspend fun discardPendingAutomaticCandidatesAndNotifications(
    context: Context,
    admissionGate: SmsWorkAdmissionGate,
    ingestionRepository: SmsIngestionRepository
): Int = withContext(NonCancellable) {
    val gatePause = admissionGate.pause()
    try {
        // An admitted enqueue can still be committing after enqueueUniqueWork
        // returns. Wait for those submissions before cancelling by unique name
        // so no late KEEP shell survives the OFF boundary.
        gatePause.admittedEnqueues.forEach { operation -> operation.await() }

        val pending = ingestionRepository.pendingAutomaticCandidates()
        val workManager = WorkManager.getInstance(context)

        // Finish cancellation before deleting the durable rows. This prevents
        // a stale unfinished KEEP chain from suppressing a later opt-in
        // admission of the same source identity. Claimed rows are excluded by
        // the repository query, so the operation that already snapshotted ON
        // is never cancelled.
        pending.forEach { candidate ->
            workManager.cancelUniqueWork(
                SmsParserWorker.uniqueWorkName(candidate.candidateKey)
            ).await()
        }

        // Cancel notifications while their durable candidate identities still
        // exist. If the process dies here, startup can discover the retained
        // rows and retry cleanup instead of leaving an orphaned notification.
        pending.forEach { candidate ->
            SmsNotificationHelper.cancelCandidateNotification(
                context = context,
                candidateKey = candidate.candidateKey
            )
        }
        val removed = ingestionRepository.discardPendingAutomatic()
        removed
    } finally {
        gatePause.release()
    }
}
