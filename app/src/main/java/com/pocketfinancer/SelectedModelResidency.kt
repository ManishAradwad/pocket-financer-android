package com.pocketfinancer

import android.util.Log
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns every mutation of the process-wide SELECTED_MODEL runtime pin.
 *
 * A short-held monitor assigns FIFO mutation reservations. Native pin changes
 * happen after the monitor is released, so waiting for SlmRuntime can never
 * deadlock a provisional owner's durable commit. A provisional handoff retains
 * its reservation until commit or rollback, preventing a newer selection from
 * overtaking it.
 */
@Singleton
class SelectedModelResidency @Inject constructor(
    private val slmRuntime: SlmRuntime
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val jobLock = Any()
    private val nextMutationId = AtomicLong(1L)
    private val mutationWaiters = ArrayDeque<MutationWaiter>()

    private var restoreJob: Job? = null

    // All fields below are protected by stateLock.
    private var activeMutationId: Long? = null
    private var mutationPauseId: Long? = null
    private var pinGeneration: Long = 0L
    private var selectedSpec: SlmModelSpec? = null
    private var activeProvisionalGeneration: Long? = null

    fun restore(spec: SlmModelSpec) {
        synchronized(jobLock) {
            if (restoreJob?.isActive == true) return
            restoreJob = scope.launch {
                try {
                    mutatePin(spec)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    // SlmRuntime.state carries native load failures.
                    Log.e(TAG, "Selected-model residency restore failed", error)
                }
            }
        }
    }

    suspend fun pin(spec: SlmModelSpec) {
        cancelPendingRestore()
        mutatePin(spec)
    }

    /**
     * Installs [spec] provisionally and retains the selected-mutation
     * reservation until the returned handle commits or rolls back.
     *
     * [persistedFallback] covers process startup where the persisted selection
     * exists but its asynchronous restore has not installed the local pin yet.
     */
    suspend fun beginProvisionalPin(
        spec: SlmModelSpec,
        persistedFallback: SlmModelSpec?
    ): ProvisionalSelectedModelPin {
        cancelPendingRestore()
        val mutationId = acquireMutation()
        val previous = synchronized(stateLock) {
            selectedSpec ?: persistedFallback
        }

        try {
            slmRuntime.pin(SlmRuntimeOwner.SELECTED_MODEL, spec)
        } catch (failure: Throwable) {
            // If cancellation drained startup restore before it installed A,
            // and loading B fails, restore exact persisted A before returning.
            withContext(NonCancellable) {
                val localSelection = synchronized(stateLock) { selectedSpec }
                if (previous != null && localSelection == null) {
                    try {
                        slmRuntime.pin(SlmRuntimeOwner.SELECTED_MODEL, previous)
                        synchronized(stateLock) {
                            selectedSpec = previous
                            pinGeneration++
                        }
                    } catch (restoreFailure: Throwable) {
                        failure.addSuppressed(restoreFailure)
                    }
                }
            }
            releaseMutation(mutationId)
            throw failure
        }

        val generation = synchronized(stateLock) {
            selectedSpec = spec
            (++pinGeneration).also { activeProvisionalGeneration = it }
        }
        return ProvisionalSelectedModelPin(
            target = spec,
            previous = previous,
            commitAction = { persist ->
                commitProvisional(mutationId, spec, generation, persist)
            },
            rollbackAction = {
                rollbackProvisional(mutationId, spec, previous, generation)
            }
        )
    }

    suspend fun unpin() {
        cancelPendingRestore()
        val mutationId = acquireMutation()
        try {
            // SlmRuntime.unpin is an actor handoff: cancellation after the
            // command is sent cannot retract the native/runtime mutation.
            // Commit the matching local ownership update in the same
            // non-cancellable section so the two state owners cannot diverge.
            withContext(NonCancellable) {
                slmRuntime.unpin(SlmRuntimeOwner.SELECTED_MODEL)
                synchronized(stateLock) {
                    selectedSpec = null
                    pinGeneration++
                }
            }
        } finally {
            releaseMutation(mutationId)
        }
    }

    fun currentSelectedSpec(): SlmModelSpec? = synchronized(stateLock) {
        selectedSpec
    }

    /**
     * Atomically rejects later selection changes for destructive reset.
     * Existing/queued mutations or a provisional handoff make this a failed
     * try-acquire; reset should first drain their owning app-flow Jobs.
     */
    suspend fun tryPauseMutations(): SelectedModelMutationPause? {
        cancelPendingRestore()
        val pauseId = nextMutationId.getAndIncrement()
        val acquired = synchronized(stateLock) {
            if (mutationPauseId != null ||
                activeMutationId != null ||
                mutationWaiters.isNotEmpty() ||
                activeProvisionalGeneration != null
            ) {
                false
            } else {
                mutationPauseId = pauseId
                true
            }
        }
        if (!acquired) return null
        return SelectedModelMutationPause(
            restoreAction = { spec -> restoreDuringMutationPause(pauseId, spec) },
            externalMutationAction = { recordExternalMutationDuringPause(pauseId) },
            releaseAction = { releaseMutationPause(pauseId) }
        )
    }

    suspend fun cancelPendingRestore() {
        val pending = synchronized(jobLock) {
            restoreJob.also { restoreJob = null }
        }
        pending?.cancelAndJoin()
    }

    private suspend fun mutatePin(spec: SlmModelSpec) {
        val mutationId = acquireMutation()
        try {
            slmRuntime.pin(SlmRuntimeOwner.SELECTED_MODEL, spec)
            synchronized(stateLock) {
                selectedSpec = spec
                pinGeneration++
            }
        } finally {
            releaseMutation(mutationId)
        }
    }

    private suspend fun acquireMutation(): Long {
        val mutationId = nextMutationId.getAndIncrement()
        val response = CompletableDeferred<Unit>()
        val waiter = MutationWaiter(mutationId, response)
        val grantedImmediately = synchronized(stateLock) {
            check(mutationPauseId == null) {
                "Selected-model mutations are paused for reset"
            }
            if (activeMutationId == null && mutationWaiters.isEmpty()) {
                activeMutationId = mutationId
                true
            } else {
                mutationWaiters.addLast(waiter)
                false
            }
        }
        if (grantedImmediately) return mutationId

        try {
            response.await()
            return mutationId
        } catch (cancelled: CancellationException) {
            synchronized(stateLock) {
                val removedWhileQueued = mutationWaiters.remove(waiter)
                if (!removedWhileQueued && activeMutationId == mutationId) {
                    activeMutationId = null
                    grantNextMutationLocked()
                }
            }
            throw cancelled
        }
    }

    private fun releaseMutation(mutationId: Long) {
        synchronized(stateLock) {
            if (activeMutationId != mutationId) return
            activeMutationId = null
            grantNextMutationLocked()
        }
    }

    private fun grantNextMutationLocked() {
        while (mutationWaiters.isNotEmpty()) {
            val next = mutationWaiters.removeFirst()
            activeMutationId = next.id
            if (next.response.complete(Unit)) return
            activeMutationId = null
        }
    }

    private fun commitProvisional(
        mutationId: Long,
        target: SlmModelSpec,
        generation: Long,
        persist: () -> Boolean
    ): Boolean {
        val committed = synchronized(stateLock) {
            if (activeMutationId != mutationId ||
                activeProvisionalGeneration != generation ||
                pinGeneration != generation ||
                selectedSpec != target
            ) {
                false
            } else {
                persist().also { durable ->
                    if (durable) activeProvisionalGeneration = null
                }
            }
        }
        if (committed) releaseMutation(mutationId)
        return committed
    }

    private suspend fun rollbackProvisional(
        mutationId: Long,
        target: SlmModelSpec,
        previous: SlmModelSpec?,
        generation: Long
    ) {
        try {
            val shouldRollback = synchronized(stateLock) {
                activeMutationId == mutationId &&
                    activeProvisionalGeneration == generation &&
                    pinGeneration == generation &&
                    selectedSpec == target &&
                    previous != target
            }
            if (shouldRollback) {
                if (previous == null) {
                    slmRuntime.unpin(SlmRuntimeOwner.SELECTED_MODEL)
                } else {
                    slmRuntime.pin(SlmRuntimeOwner.SELECTED_MODEL, previous)
                }
                synchronized(stateLock) {
                    selectedSpec = previous
                    pinGeneration++
                }
            }
        } finally {
            synchronized(stateLock) {
                if (activeProvisionalGeneration == generation) {
                    activeProvisionalGeneration = null
                }
            }
            releaseMutation(mutationId)
        }
    }

    private suspend fun restoreDuringMutationPause(
        pauseId: Long,
        spec: SlmModelSpec?
    ) {
        // Reset currently invokes this from non-cancellable cleanup, but keep
        // the pause API atomic on its own: a future caller must not cancel
        // between the runtime handoff and the matching local bookkeeping.
        withContext(NonCancellable) {
            checkPauseOwner(pauseId)
            if (spec == null) {
                slmRuntime.unpin(SlmRuntimeOwner.SELECTED_MODEL)
            } else {
                slmRuntime.pin(SlmRuntimeOwner.SELECTED_MODEL, spec)
            }
            synchronized(stateLock) {
                check(mutationPauseId == pauseId) {
                    "Selected-model mutation pause ownership changed"
                }
                selectedSpec = spec
                pinGeneration++
            }
        }
    }

    private fun recordExternalMutationDuringPause(pauseId: Long) {
        synchronized(stateLock) {
            check(mutationPauseId == pauseId) {
                "Selected-model mutation pause ownership changed"
            }
            selectedSpec = null
            pinGeneration++
        }
    }

    private fun releaseMutationPause(pauseId: Long) {
        synchronized(stateLock) {
            if (mutationPauseId == pauseId) mutationPauseId = null
        }
    }

    private fun checkPauseOwner(pauseId: Long) {
        synchronized(stateLock) {
            check(mutationPauseId == pauseId) {
                "Selected-model mutation pause ownership changed"
            }
        }
    }

    private data class MutationWaiter(
        val id: Long,
        val response: CompletableDeferred<Unit>
    )

    private companion object {
        const val TAG = "SelectedModelResidency"
    }
}

class ProvisionalSelectedModelPin internal constructor(
    val target: SlmModelSpec,
    val previous: SlmModelSpec?,
    private val commitAction: suspend (() -> Boolean) -> Boolean,
    private val rollbackAction: suspend () -> Unit
) {
    private val completionMutex = Mutex()
    private var finished = false

    suspend fun commit(persist: () -> Boolean): Boolean =
        completionMutex.withLock {
            if (finished) return@withLock false
            val committed = withContext(NonCancellable) {
                commitAction(persist)
            }
            if (committed) finished = true
            committed
        }

    suspend fun rollbackUnlessCommitted() {
        completionMutex.withLock {
            if (finished) return@withLock
            try {
                withContext(NonCancellable) {
                    rollbackAction()
                }
            } finally {
                finished = true
            }
        }
    }
}

class SelectedModelMutationPause internal constructor(
    private val restoreAction: suspend (SlmModelSpec?) -> Unit,
    private val externalMutationAction: suspend () -> Unit,
    private val releaseAction: suspend () -> Unit
) {
    private val released = AtomicBoolean(false)

    suspend fun restorePin(spec: SlmModelSpec?) {
        check(!released.get()) { "Selected-model mutation pause has been released" }
        restoreAction(spec)
    }

    suspend fun recordExternalPinMutation() {
        check(!released.get()) { "Selected-model mutation pause has been released" }
        externalMutationAction()
    }

    suspend fun release() {
        if (released.compareAndSet(false, true)) {
            withContext(NonCancellable) {
                releaseAction()
            }
        }
    }
}
