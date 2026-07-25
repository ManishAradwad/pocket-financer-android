package com.pocketfinancer

import com.pocketfinancer.inference.SlmRuntimeOwner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.withContext

data class SlmAppFlowState(
    val admissionPaused: Boolean = false,
    val activeByOwner: Map<SlmRuntimeOwner, Int> = emptyMap()
) {
    val activeCount: Int
        get() = activeByOwner.values.sum()
}

interface SlmAppFlowLease {
    val owner: SlmRuntimeOwner
    suspend fun release()
}

interface SlmAppFlowPause {
    val owner: SlmRuntimeOwner
    suspend fun release()
}

/**
 * Process-wide admission and drain boundary for foreground SLM workflows.
 *
 * Native operations are still serialized by [com.pocketfinancer.inference.SlmRuntime].
 * This coordinator covers the larger app workflow (scanning, inference, and
 * persistence). It lets destructive reset atomically cancel/drain all work,
 * and lets selected-model upgrades pause admission while older snapshots
 * finish naturally before the durable model handoff.
 */
@Singleton
class SlmAppFlowCoordinator @Inject constructor() {
    private val lock = Any()
    private val nextId = AtomicLong(1L)
    private val active = linkedMapOf<Long, ActiveFlow>()
    private var pause: ActivePause? = null

    private val _state = MutableStateFlow(SlmAppFlowState())
    val state: StateFlow<SlmAppFlowState> = _state.asStateFlow()

    /**
     * Returns null when destructive maintenance has paused new admission.
     * Completion cleanup is registered before this method returns, closing the
     * cancellation race where a caller never gets a chance to release its lease.
     */
    suspend fun tryEnter(owner: SlmRuntimeOwner): SlmAppFlowLease? {
        val job = currentCoroutineContext()[Job]
            ?: error("An app SLM flow must run in a coroutine Job")
        val id = nextId.getAndIncrement()
        val lease = FlowLease(id, owner, ::releaseFlow)

        synchronized(lock) {
            if (pause != null || !job.isActive) return null
            active[id] = ActiveFlow(owner, job)
            publishLocked()
            job.invokeOnCompletion {
                releaseFlow(id)
            }
        }
        return lease
    }

    /**
     * Waits through a temporary maintenance pause, then atomically admits the
     * workflow. Intended for already-running foreground services that must not
     * falsely report completion merely because a short model handoff owns the
     * gate.
     */
    suspend fun enterWhenAvailable(owner: SlmRuntimeOwner): SlmAppFlowLease {
        while (true) {
            currentCoroutineContext().ensureActive()
            tryEnter(owner)?.let { return it }
            state.first { appState -> !appState.admissionPaused }
        }
    }

    /**
     * Atomically pauses admission, then cancels and joins every flow that was
     * admitted before the pause. Returns null if another pause already owns the
     * gate. If the acquiring coroutine is cancelled while draining, cleanup is
     * completed and admission is reopened before cancellation escapes.
     */
    suspend fun tryPauseAndDrain(owner: SlmRuntimeOwner): SlmAppFlowPause? {
        val caller = currentCoroutineContext()[Job]
            ?: error("An app SLM flow pause must run in a coroutine Job")
        val token = nextId.getAndIncrement()
        val jobs = synchronized(lock) {
            if (pause != null) return null
            check(active.values.none { it.job === caller }) {
                "A registered app SLM flow cannot pause and join itself"
            }
            pause = ActivePause(token, owner)
            publishLocked()
            active.values.map { it.job }.distinct()
        }

        var handedOff = false
        try {
            withContext(NonCancellable) {
                jobs.forEach { job ->
                    job.cancel(CancellationException("SLM app flows paused by ${owner.value}"))
                }
                jobs.joinAll()
            }
            currentCoroutineContext().ensureActive()
            val lease = PauseLease(token, owner, ::releasePause)
            handedOff = true
            return lease
        } finally {
            if (!handedOff) {
                withContext(NonCancellable) {
                    releasePause(token)
                }
            }
        }
    }

    /**
     * Blocks new workflow admission and waits for every already-admitted flow
     * except the caller to finish naturally.
     *
     * This is the selected-model handoff boundary: old-model work admitted
     * before the boundary finishes with its immutable snapshot, no new work
     * can resolve the old persisted selection, and the caller can then load and
     * durably commit the new selection without producing conflicting leases.
     */
    suspend fun tryPauseAdmissionAndDrainOthers(
        owner: SlmRuntimeOwner
    ): SlmAppFlowPause? {
        val caller = currentCoroutineContext()[Job]
            ?: error("An app SLM flow pause must run in a coroutine Job")
        val token = nextId.getAndIncrement()
        val jobs = synchronized(lock) {
            if (pause != null) return null
            check(active.values.any { it.job === caller }) {
                "Only an admitted app SLM flow can drain other workflows"
            }
            pause = ActivePause(token, owner)
            publishLocked()
            active.values
                .map { it.job }
                .filter { it !== caller }
                .distinct()
        }

        var handedOff = false
        try {
            jobs.joinAll()
            currentCoroutineContext().ensureActive()
            val lease = PauseLease(token, owner, ::releasePause)
            handedOff = true
            return lease
        } finally {
            if (!handedOff) {
                withContext(NonCancellable) {
                    releasePause(token)
                }
            }
        }
    }

    private fun releaseFlow(id: Long) {
        synchronized(lock) {
            if (active.remove(id) != null) publishLocked()
        }
    }

    private fun releasePause(token: Long) {
        synchronized(lock) {
            if (pause?.token == token) {
                pause = null
                publishLocked()
            }
        }
    }

    private fun publishLocked() {
        _state.value = SlmAppFlowState(
            admissionPaused = pause != null,
            activeByOwner = active.values
                .groupingBy { it.owner }
                .eachCount()
        )
    }

    private data class ActiveFlow(
        val owner: SlmRuntimeOwner,
        val job: Job
    )

    private data class ActivePause(
        val token: Long,
        val owner: SlmRuntimeOwner
    )

    private class FlowLease(
        private val id: Long,
        override val owner: SlmRuntimeOwner,
        private val releaseAction: (Long) -> Unit
    ) : SlmAppFlowLease {
        private val released = AtomicBoolean(false)

        override suspend fun release() {
            if (released.compareAndSet(false, true)) releaseAction(id)
        }
    }

    private class PauseLease(
        private val token: Long,
        override val owner: SlmRuntimeOwner,
        private val releaseAction: (Long) -> Unit
    ) : SlmAppFlowPause {
        private val released = AtomicBoolean(false)

        override suspend fun release() {
            if (released.compareAndSet(false, true)) releaseAction(token)
        }
    }
}
