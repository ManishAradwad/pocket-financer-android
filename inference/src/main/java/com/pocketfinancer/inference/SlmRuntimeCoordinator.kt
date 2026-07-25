package com.pocketfinancer.inference

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

internal interface LlamaBackend {
    suspend fun load(spec: SlmModelSpec)

    /** Returns false rather than freeing a handle while a native operation is active. */
    suspend fun unload(): Boolean

    suspend fun extract(
        operationId: Long,
        spec: SlmModelSpec,
        request: SlmExtractionRequest
    ): SlmExtractionResult

    suspend fun countTokens(
        operationId: Long,
        text: String,
        addSpecial: Boolean
    ): Int?

    /**
     * The sole method allowed to run concurrently with another backend method.
     * Implementations must only set an atomic stop bit for matching [operationId].
     */
    fun stop(operationId: Long): Boolean
}

internal class JniLlamaBackend(
    context: Context,
    storage: SlmModelStorage
) : LlamaBackend {
    private val engine = LlamaEngine(context, storage)

    override suspend fun load(spec: SlmModelSpec) {
        engine.loadModel(spec).getOrThrow()
    }

    override suspend fun unload(): Boolean = engine.unloadModel()

    override suspend fun extract(
        operationId: Long,
        spec: SlmModelSpec,
        request: SlmExtractionRequest
    ): SlmExtractionResult = engine.inferForExtraction(operationId, spec, request)

    override suspend fun countTokens(
        operationId: Long,
        text: String,
        addSpecial: Boolean
    ): Int? = engine.countTokens(operationId, text, addSpecial)

    override fun stop(operationId: Long): Boolean = engine.stop(operationId)
}

/**
 * Actor-backed implementation. The actor owns all lifecycle state while a
 * single native dispatcher executes at most one load/use/unload operation.
 */
@Singleton
class SlmRuntimeCoordinator internal constructor(
    private val backend: LlamaBackend,
    private val actorScope: CoroutineScope,
    private val nativeDispatcher: CoroutineDispatcher,
    private val beforePinObserved: suspend (Long) -> Unit = {}
) : SlmRuntime {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        storage: SlmModelStorage
    ) : this(
        backend = JniLlamaBackend(context, storage),
        actorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        nativeDispatcher = Dispatchers.IO.limitedParallelism(1)
    )

    private val nextId = AtomicLong(1)
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val _state = MutableStateFlow(SlmRuntimeState())
    override val state: StateFlow<SlmRuntimeState> = _state.asStateFlow()

    init {
        actorScope.launch {
            actorLoop()
        }
    }

    override suspend fun acquire(owner: SlmRuntimeOwner, spec: SlmModelSpec): SlmLease {
        val requestId = nextId.getAndIncrement()
        val response = CompletableDeferred<SlmLease>()
        commands.send(Command.Acquire(requestId, owner, spec, response))
        val lease = awaitRequest(requestId, response)
        check(commands.trySend(Command.AcquisitionObserved(requestId)).isSuccess)
        return lease
    }

    override suspend fun pin(owner: SlmRuntimeOwner, spec: SlmModelSpec) {
        val requestId = nextId.getAndIncrement()
        val response = CompletableDeferred<Unit>()
        commands.send(Command.Pin(requestId, owner, spec, response))
        awaitRequest(requestId, response)
        try {
            beforePinObserved(requestId)
            check(commands.trySend(Command.PinObserved(requestId)).isSuccess)
        } catch (cancelled: CancellationException) {
            cancelAndDrain(requestId)
            throw cancelled
        }
    }

    override suspend fun unpin(owner: SlmRuntimeOwner) {
        withContext(NonCancellable) {
            val response = CompletableDeferred<Unit>()
            commands.send(Command.Unpin(owner, response))
            response.await()
        }
    }

    override suspend fun tryAcquireMaintenance(
        owner: SlmRuntimeOwner,
        pinsToRelease: Set<SlmRuntimeOwner>
    ): SlmMaintenanceLease? {
        val requestId = nextId.getAndIncrement()
        val response = CompletableDeferred<SlmMaintenanceLease?>()
        commands.send(
            Command.TryMaintenance(
                id = requestId,
                owner = owner,
                pinsToRelease = pinsToRelease,
                response = response
            )
        )
        val lease = awaitRequest(requestId, response)
        if (lease != null) {
            check(commands.trySend(Command.MaintenanceObserved(requestId)).isSuccess)
        }
        return lease
    }

    private suspend fun submitExtraction(
        leaseId: Long,
        request: SlmExtractionRequest
    ): SlmExtractionResult {
        val requestId = nextId.getAndIncrement()
        val response = CompletableDeferred<SlmExtractionResult>()
        commands.send(Command.Extract(requestId, leaseId, request, response))
        return awaitRequest(requestId, response)
    }

    private suspend fun submitTokenCount(
        leaseId: Long,
        text: String,
        addSpecial: Boolean
    ): Int? {
        val requestId = nextId.getAndIncrement()
        val response = CompletableDeferred<Int?>()
        commands.send(
            Command.Tokenize(
                id = requestId,
                leaseId = leaseId,
                text = text,
                addSpecial = addSpecial,
                response = response
            )
        )
        return awaitRequest(requestId, response)
    }

    private suspend fun releaseLease(leaseId: Long) {
        val response = CompletableDeferred<Unit>()
        commands.send(Command.ReleaseLease(leaseId, response))
        response.await()
    }

    private suspend fun releaseMaintenance(leaseId: Long) {
        val response = CompletableDeferred<Unit>()
        commands.send(Command.ReleaseMaintenance(leaseId, response))
        response.await()
    }

    private suspend fun <T> awaitRequest(
        requestId: Long,
        response: CompletableDeferred<T>
    ): T {
        try {
            return response.await()
        } catch (cancelled: CancellationException) {
            cancelAndDrain(requestId)
            throw cancelled
        }
    }

    private suspend fun cancelAndDrain(requestId: Long) {
        withContext(NonCancellable) {
            val drained = CompletableDeferred<Unit>()
            commands.send(Command.Cancel(requestId, drained))
            drained.await()
        }
    }

    private suspend fun actorLoop() {
        val runtime = ActorState()
        for (command in commands) {
            when (command) {
                is Command.Acquire -> {
                    runtime.autoUnloadBlocked = false
                    runtime.pending.addLast(
                        Pending.Acquire(
                            command.id,
                            command.owner,
                            command.spec,
                            command.response
                        )
                    )
                }

                is Command.Pin -> {
                    runtime.pinRestoreBlockedFor = null
                    runtime.autoUnloadBlocked = false
                    runtime.pending
                        .filterIsInstance<Pending.Pin>()
                        .filter { it.owner == command.owner }
                        .forEach { old ->
                            runtime.pending.remove(old)
                            old.response.completeExceptionally(
                                CancellationException("Superseded by a newer pin request")
                            )
                        }
                    runtime.pending.addLast(
                        Pending.Pin(
                            command.id,
                            command.owner,
                            command.spec,
                            command.response
                        )
                    )
                }

                is Command.Unpin -> handleUnpin(runtime, command)
                is Command.Extract -> runtime.pending.addLast(
                    Pending.Extract(
                        command.id,
                        command.leaseId,
                        command.request,
                        command.response
                    )
                )

                is Command.Tokenize -> runtime.pending.addLast(
                    Pending.Tokenize(
                        command.id,
                        command.leaseId,
                        command.text,
                        command.addSpecial,
                        command.response
                    )
                )

                is Command.ReleaseLease -> {
                    val lease = runtime.leases.remove(command.leaseId)
                    lease?.markReleased()
                    command.response.complete(Unit)
                }

                is Command.AcquisitionObserved ->
                    runtime.unobservedAcquisitions.remove(command.requestId)
                is Command.PinObserved ->
                    runtime.unobservedPins.remove(command.requestId)

                is Command.TryMaintenance -> handleTryMaintenance(runtime, command)
                is Command.MaintenanceObserved -> {
                    val leaseId = runtime.unobservedMaintenance.remove(command.requestId)
                    val lease = runtime.maintenance
                    if (leaseId != null && lease?.id == leaseId) {
                        lease.pinsToRelease.forEach(runtime.pins::remove)
                    }
                }
                is Command.ReleaseMaintenance -> {
                    if (runtime.maintenance?.id == command.leaseId) {
                        runtime.maintenance = null
                    }
                    command.response.complete(Unit)
                }

                Command.EvictIfIdle -> {
                    runtime.evictionScheduled = false
                    if (runtime.active == null &&
                        runtime.maintenance == null &&
                        runtime.pending.isEmpty() &&
                        runtime.loadedModel != null &&
                        runtime.leases.isEmpty() &&
                        runtime.pins.isEmpty() &&
                        !runtime.autoUnloadBlocked
                    ) {
                        startNative(
                            runtime,
                            source = Pending.AutoUnload(nextId.getAndIncrement()),
                            kind = SlmOperationKind.UNLOAD,
                            owner = null,
                            model = runtime.loadedModel!!
                        ) {
                            NativeOutcome.Unloaded(backend.unload())
                        }
                    }
                }

                is Command.Cancel -> handleCancel(runtime, command)
                is Command.NativeFinished -> handleNativeFinished(runtime, command)
            }
            pump(runtime)
            publishState(runtime)
        }
    }

    private fun handleUnpin(runtime: ActorState, command: Command.Unpin) {
        val removedPin = runtime.pins.remove(command.owner)
        runtime.unobservedPins.entries.removeAll { it.value.owner == command.owner }
        runtime.pinRestoreBlockedFor = null
        runtime.autoUnloadBlocked = false
        if (removedPin != null && runtime.pins.isEmpty()) {
            runtime.unloadRequested = true
        }

        runtime.pending
            .filterIsInstance<Pending.Pin>()
            .filter { it.owner == command.owner }
            .forEach { pending ->
                runtime.pending.remove(pending)
                pending.response.completeExceptionally(
                    CancellationException("Pin was released before it became active")
                )
            }

        val active = runtime.active
        if (active?.source is Pending.Pin && active.source.owner == command.owner) {
            active.cancelled = true
            active.drainWaiters += command.response
        } else {
            command.response.complete(Unit)
        }
    }

    private fun handleTryMaintenance(
        runtime: ActorState,
        command: Command.TryMaintenance
    ) {
        val remainingPins = runtime.pins.keys - command.pinsToRelease
        val isQuiescent = runtime.active == null &&
            runtime.pending.isEmpty() &&
            runtime.leases.isEmpty() &&
            runtime.maintenance == null &&
            remainingPins.isEmpty()

        if (!isQuiescent) {
            command.response.complete(null)
            return
        }

        val lease = MaintenanceLeaseImpl(
            id = command.id,
            owner = command.owner,
            pinsToRelease = command.pinsToRelease,
            releaseAction = ::releaseMaintenance
        )
        runtime.maintenance = lease

        if (runtime.loadedModel == null) {
            runtime.unobservedMaintenance[command.id] = lease.id
            command.response.complete(lease)
        } else {
            startNative(
                runtime = runtime,
                source = Pending.Maintenance(
                    id = command.id,
                    lease = lease,
                    response = command.response
                ),
                kind = SlmOperationKind.UNLOAD,
                owner = command.owner,
                model = runtime.loadedModel!!
            ) {
                NativeOutcome.Unloaded(backend.unload())
            }
        }
    }

    private fun handleCancel(runtime: ActorState, command: Command.Cancel) {
        val queued = runtime.pending.firstOrNull { it.id == command.requestId }
        if (queued != null) {
            runtime.pending.remove(queued)
            queued.cancel(CancellationException("Runtime request cancelled while queued"))
            command.drained.complete(Unit)
            return
        }

        runtime.unobservedAcquisitions.remove(command.requestId)?.let { leaseId ->
            runtime.leases.remove(leaseId)?.markReleased()
            command.drained.complete(Unit)
            return
        }

        runtime.unobservedMaintenance.remove(command.requestId)?.let { leaseId ->
            if (runtime.maintenance?.id == leaseId) {
                runtime.maintenance = null
            }
            command.drained.complete(Unit)
            return
        }

        runtime.unobservedPins.remove(command.requestId)?.let { handoff ->
            if (runtime.pins[handoff.owner]?.generation ==
                handoff.installedGeneration
            ) {
                runtime.pins.remove(handoff.owner)
                handoff.previous?.let { runtime.pins[handoff.owner] = it }
                runtime.pinRestoreBlockedFor = null
                if (runtime.pins.isEmpty()) {
                    runtime.unloadRequested = true
                }
            }
            command.drained.complete(Unit)
            return
        }

        val active = runtime.active
        if (active?.source?.id == command.requestId) {
            active.cancelled = true
            active.drainWaiters += command.drained
            if (active.kind == SlmOperationKind.EXTRACT ||
                active.kind == SlmOperationKind.TOKENIZE
            ) {
                active.stopping = true
                try {
                    backend.stop(active.operationId)
                } catch (failure: Throwable) {
                    runtime.lastError = buildString {
                        append("Failed to signal native stop")
                        failure.message?.let {
                            append(": ")
                            append(it)
                        }
                    }
                }
            }
            return
        }

        command.drained.complete(Unit)
    }

    private fun handleNativeFinished(
        runtime: ActorState,
        command: Command.NativeFinished
    ) {
        val active = runtime.active
        if (active == null || active.operationId != command.operationId) {
            return
        }
        runtime.active = null

        when (val source = active.source) {
            is Pending.Acquire -> finishAcquire(runtime, active, source, command.outcome)
            is Pending.Pin -> finishPin(runtime, active, source, command.outcome)
            is Pending.Extract -> {
                val outcome = command.outcome
                if (active.cancelled) {
                    source.response.completeExceptionally(
                        CancellationException("Active inference cancelled and drained")
                    )
                } else if (outcome is NativeOutcome.Extracted) {
                    runtime.lastError =
                        (outcome.result as? SlmExtractionResult.Error)?.message
                    source.response.complete(outcome.result)
                } else {
                    runtime.lastError = outcome.failureMessage()
                    source.response.complete(
                        SlmExtractionResult.Error(
                            message = outcome.failureMessage(),
                            model = active.model
                        )
                    )
                }
            }

            is Pending.Tokenize -> {
                val outcome = command.outcome
                if (active.cancelled) {
                    source.response.completeExceptionally(
                        CancellationException("Active tokenization cancelled and drained")
                    )
                } else if (outcome is NativeOutcome.TokenCounted) {
                    runtime.lastError = null
                    source.response.complete(outcome.count)
                } else {
                    runtime.lastError = outcome.failureMessage()
                    source.response.completeExceptionally(
                        IllegalStateException(outcome.failureMessage())
                    )
                }
            }

            is Pending.AutoUnload -> {
                if (command.outcome !is NativeOutcome.Unloaded ||
                    !command.outcome.unloaded
                ) {
                    runtime.lastError = command.outcome.failureMessage()
                    runtime.autoUnloadBlocked = true
                } else {
                    runtime.loadedModel = null
                    runtime.lastError = null
                    runtime.unloadRequested = false
                    runtime.autoUnloadBlocked = false
                }
            }

            is Pending.Maintenance ->
                finishMaintenance(runtime, active, source, command.outcome)
            is Pending.RestorePinnedModel -> {
                val outcome = command.outcome
                if (outcome is NativeOutcome.ModelChanged &&
                    outcome.loaded == source.spec
                ) {
                    runtime.loadedModel = source.spec
                    runtime.lastError = null
                    runtime.pinRestoreBlockedFor = null
                } else {
                    runtime.loadedModel =
                        (outcome as? NativeOutcome.ModelChanged)?.loaded
                    runtime.lastError = outcome.failureMessage()
                    runtime.pinRestoreBlockedFor = source.spec
                }
            }
        }

        active.drainWaiters.forEach { it.complete(Unit) }
    }

    private fun finishAcquire(
        runtime: ActorState,
        active: ActiveNative,
        source: Pending.Acquire,
        outcome: NativeOutcome
    ) {
        if (outcome is NativeOutcome.ModelChanged && outcome.loaded == source.spec) {
            runtime.loadedModel = source.spec
            runtime.lastError = null
            if (active.cancelled) {
                source.response.completeExceptionally(
                    CancellationException("Model acquisition cancelled while loading")
                )
                return
            }
            grantLease(runtime, source)
        } else {
            runtime.loadedModel = (outcome as? NativeOutcome.ModelChanged)?.loaded
            runtime.lastError = outcome.failureMessage()
            source.response.completeExceptionally(
                IllegalStateException(outcome.failureMessage())
            )
        }
    }

    private fun finishPin(
        runtime: ActorState,
        active: ActiveNative,
        source: Pending.Pin,
        outcome: NativeOutcome
    ) {
        if (outcome is NativeOutcome.ModelChanged && outcome.loaded == source.spec) {
            runtime.loadedModel = source.spec
            runtime.lastError = null
            if (!active.cancelled) {
                runtime.pinRestoreBlockedFor = null
                completePinSuccess(runtime, source)
            } else {
                source.response.completeExceptionally(
                    CancellationException("Pin cancelled while loading")
                )
            }
        } else {
            runtime.loadedModel = (outcome as? NativeOutcome.ModelChanged)?.loaded
            runtime.lastError = outcome.failureMessage()
            source.response.completeExceptionally(
                IllegalStateException(outcome.failureMessage())
            )
        }
    }

    private fun finishMaintenance(
        runtime: ActorState,
        active: ActiveNative,
        source: Pending.Maintenance,
        outcome: NativeOutcome
    ) {
        if (outcome is NativeOutcome.Unloaded && outcome.unloaded) {
            runtime.loadedModel = null
            runtime.lastError = null
            if (active.cancelled) {
                runtime.maintenance = null
                source.response.completeExceptionally(
                    CancellationException("Maintenance acquisition cancelled while draining")
                )
            } else {
                runtime.unobservedMaintenance[source.id] = source.lease.id
                source.response.complete(source.lease)
            }
        } else {
            runtime.maintenance = null
            runtime.lastError = outcome.failureMessage()
            source.response.complete(null)
        }
    }

    private fun pump(runtime: ActorState) {
        if (runtime.active != null || runtime.maintenance != null) {
            return
        }

        while (runtime.pending.isNotEmpty()) {
            val head = runtime.pending.first()
            when (head) {
                is Pending.Acquire -> {
                    if (runtime.pinRestoreBlockedFor == head.spec &&
                        runtime.loadedModel != head.spec
                    ) {
                        runtime.pending.removeFirst()
                        head.response.completeExceptionally(
                            SlmModelResidencyException(
                                runtime.lastError
                                    ?: "Pinned model restoration previously failed"
                            )
                        )
                        continue
                    }
                    if (runtime.pins.values.any { it.spec != head.spec }) {
                        runtime.pending.removeFirst()
                        head.response.completeExceptionally(
                            SlmModelResidencyException(
                                "Model ${head.spec.modelId} conflicts with a pinned model"
                            )
                        )
                        continue
                    }
                    if (runtime.loadedModel == head.spec) {
                        runtime.pending.removeFirst()
                        grantLease(runtime, head)
                        continue
                    }
                    if (canChangeModel(runtime, replacingPinOwner = null)) {
                        runtime.pending.removeFirst()
                        startModelChange(runtime, head, head.owner, head.spec)
                    } else {
                        startExistingLeaseWorkPastBlockedChange(runtime)
                    }
                    return
                }

                is Pending.Pin -> {
                    if (runtime.pins.any { (owner, record) ->
                            owner != head.owner && record.spec != head.spec
                        }
                    ) {
                        runtime.pending.removeFirst()
                        head.response.completeExceptionally(
                            SlmModelResidencyException(
                                "Model ${head.spec.modelId} conflicts with another owner's pin"
                            )
                        )
                        continue
                    }
                    if (runtime.loadedModel == head.spec) {
                        runtime.pending.removeFirst()
                        runtime.lastError = null
                        runtime.pinRestoreBlockedFor = null
                        completePinSuccess(runtime, head)
                        continue
                    }
                    if (canChangeModel(runtime, replacingPinOwner = head.owner)) {
                        runtime.pending.removeFirst()
                        startModelChange(runtime, head, head.owner, head.spec)
                    } else {
                        startExistingLeaseWorkPastBlockedChange(runtime)
                    }
                    return
                }

                is Pending.Extract -> {
                    runtime.pending.removeFirst()
                    val lease = runtime.leases[head.leaseId]
                    if (lease == null || lease.isReleased) {
                        head.response.completeExceptionally(
                            IllegalStateException("SLM lease has been released")
                        )
                        continue
                    }
                    if (runtime.loadedModel != lease.model) {
                        head.response.completeExceptionally(
                            IllegalStateException("Lease model is not resident")
                        )
                        continue
                    }
                    startNative(
                        runtime,
                        source = head,
                        kind = SlmOperationKind.EXTRACT,
                        owner = lease.owner,
                        model = lease.model
                    ) {
                        NativeOutcome.Extracted(
                            backend.extract(head.id, lease.model, head.request)
                        )
                    }
                    return
                }

                is Pending.Tokenize -> {
                    runtime.pending.removeFirst()
                    val lease = runtime.leases[head.leaseId]
                    if (lease == null || lease.isReleased) {
                        head.response.completeExceptionally(
                            IllegalStateException("SLM lease has been released")
                        )
                        continue
                    }
                    if (runtime.loadedModel != lease.model) {
                        head.response.completeExceptionally(
                            IllegalStateException("Lease model is not resident")
                        )
                        continue
                    }
                    startNative(
                        runtime,
                        source = head,
                        kind = SlmOperationKind.TOKENIZE,
                        owner = lease.owner,
                        model = lease.model
                    ) {
                        NativeOutcome.TokenCounted(
                            backend.countTokens(
                                operationId = head.id,
                                text = head.text,
                                addSpecial = head.addSpecial
                            )
                        )
                    }
                    return
                }

                is Pending.AutoUnload,
                is Pending.Maintenance,
                is Pending.RestorePinnedModel ->
                    error("Internal pending item leaked into FIFO")
            }
        }

        val loaded = runtime.loadedModel
        if (runtime.pins.isNotEmpty()) {
            val pinnedSpecs = runtime.pins.values.map { it.spec }.distinct()
            if (pinnedSpecs.size == 1 &&
                loaded != pinnedSpecs.single() &&
                runtime.pinRestoreBlockedFor != pinnedSpecs.single()
            ) {
                val spec = pinnedSpecs.single()
                startNative(
                    runtime,
                    source = Pending.RestorePinnedModel(
                        id = nextId.getAndIncrement(),
                        spec = spec
                    ),
                    kind = SlmOperationKind.LOAD,
                    owner = null,
                    model = spec
                ) {
                    var resident = loaded
                    try {
                        if (loaded != null) {
                            check(backend.unload()) {
                                "Native backend refused model unload while restoring a pin"
                            }
                            resident = null
                        }
                        backend.load(spec)
                        NativeOutcome.ModelChanged(spec)
                    } catch (failure: Throwable) {
                        NativeOutcome.ModelChanged(resident, failure)
                    }
                }
            } else if (pinnedSpecs.size != 1) {
                runtime.lastError = "Pinned owners requested incompatible models"
            }
            if (pinnedSpecs.size != 1 || loaded != pinnedSpecs.single()) {
                return
            }
        }
        if (loaded != null &&
            runtime.leases.isEmpty() &&
            runtime.pins.isEmpty() &&
            !runtime.autoUnloadBlocked
        ) {
            if (!runtime.evictionScheduled) {
                runtime.evictionScheduled = true
                check(commands.trySend(Command.EvictIfIdle).isSuccess)
            }
        }
    }

    /**
     * A model switch at the head cannot wait in front of work needed by an
     * already-issued old-model lease, otherwise a scoped batch could deadlock
     * while trying to finish and release that lease.
     */
    private fun startExistingLeaseWorkPastBlockedChange(runtime: ActorState) {
        val loaded = runtime.loadedModel ?: return
        val bypass = runtime.pending.drop(1).firstOrNull { pending ->
            val leaseId = when (pending) {
                is Pending.Extract -> pending.leaseId
                is Pending.Tokenize -> pending.leaseId
                else -> null
            }
            leaseId != null && runtime.leases[leaseId]?.model == loaded
        } ?: return
        runtime.pending.remove(bypass)
        runtime.pending.addFirst(bypass)
        pump(runtime)
    }

    private fun canChangeModel(
        runtime: ActorState,
        replacingPinOwner: SlmRuntimeOwner?
    ): Boolean {
        if (runtime.leases.isNotEmpty()) {
            return false
        }
        return runtime.pins.keys.none { it != replacingPinOwner }
    }

    private fun grantLease(runtime: ActorState, source: Pending.Acquire) {
        val leaseId = nextId.getAndIncrement()
        val lease = LeaseImpl(
            id = leaseId,
            owner = source.owner,
            model = source.spec,
            extractAction = ::submitExtraction,
            tokenCountAction = ::submitTokenCount,
            releaseAction = ::releaseLease
        )
        runtime.leases[leaseId] = lease
        runtime.lastError = null
        runtime.unobservedAcquisitions[source.id] = leaseId
        source.response.complete(lease)
    }

    private fun completePinSuccess(runtime: ActorState, source: Pending.Pin) {
        val installed = PinRecord(source.spec, source.id)
        val previous = runtime.pins.put(source.owner, installed)
        runtime.unloadRequested = false
        runtime.unobservedPins[source.id] =
            PinHandoff(source.owner, installed.generation, previous)
        source.response.complete(Unit)
    }

    private fun startModelChange(
        runtime: ActorState,
        source: Pending,
        owner: SlmRuntimeOwner,
        target: SlmModelSpec
    ) {
        val previous = runtime.loadedModel
        startNative(
            runtime = runtime,
            source = source,
            kind = SlmOperationKind.LOAD,
            owner = owner,
            model = target
        ) {
            var resident = previous
            try {
                if (previous != null && previous != target) {
                    check(backend.unload()) {
                        "Native backend refused model unload while idle"
                    }
                    resident = null
                }
                backend.load(target)
                NativeOutcome.ModelChanged(loaded = target)
            } catch (failure: Throwable) {
                var rollbackFailure: Throwable? = null
                if (previous != null && resident == null) {
                    try {
                        backend.load(previous)
                        resident = previous
                    } catch (rollback: Throwable) {
                        resident = null
                        rollbackFailure = rollback
                    }
                }
                NativeOutcome.ModelChanged(
                    loaded = resident,
                    failure = ModelChangeException(failure, rollbackFailure)
                )
            }
        }
    }

    private fun startNative(
        runtime: ActorState,
        source: Pending,
        kind: SlmOperationKind,
        owner: SlmRuntimeOwner?,
        model: SlmModelSpec,
        block: suspend () -> NativeOutcome
    ) {
        check(runtime.active == null)
        val operationId = source.id
        val active = ActiveNative(
            operationId = operationId,
            source = source,
            kind = kind,
            owner = owner,
            model = model
        )
        runtime.active = active
        active.job = actorScope.launch(nativeDispatcher) {
            val outcome = try {
                block()
            } catch (failure: Throwable) {
                NativeOutcome.Failed(failure)
            }
            commands.send(Command.NativeFinished(operationId, outcome))
        }
    }

    private fun publishState(runtime: ActorState) {
        val active = runtime.active
        val phase = when {
            runtime.maintenance != null && active == null -> SlmRuntimePhase.MAINTENANCE
            active?.stopping == true -> SlmRuntimePhase.STOPPING
            active?.kind == SlmOperationKind.LOAD -> SlmRuntimePhase.LOADING
            active?.kind == SlmOperationKind.UNLOAD -> SlmRuntimePhase.UNLOADING
            active != null -> SlmRuntimePhase.RUNNING
            runtime.lastError != null -> SlmRuntimePhase.ERROR
            runtime.loadedModel != null -> SlmRuntimePhase.READY
            else -> SlmRuntimePhase.UNLOADED
        }
        val pendingAction = when {
            runtime.maintenance != null ->
                SlmPendingAction.Maintenance(runtime.maintenance!!.owner)
            active?.kind == SlmOperationKind.UNLOAD -> SlmPendingAction.Unload
            active?.kind == SlmOperationKind.LOAD ->
                SlmPendingAction.ModelChange(active.model)
            runtime.pending.firstOrNull() is Pending.Pin ->
                SlmPendingAction.ModelChange((runtime.pending.first() as Pending.Pin).spec)
            runtime.pending.firstOrNull() is Pending.Acquire -> {
                val spec = (runtime.pending.first() as Pending.Acquire).spec
                if (spec != runtime.loadedModel) SlmPendingAction.ModelChange(spec) else null
            }
            runtime.unloadRequested && runtime.loadedModel != null ->
                SlmPendingAction.Unload
            runtime.evictionScheduled && runtime.loadedModel != null ->
                SlmPendingAction.Unload
            else -> null
        }

        val leasesByOwner = runtime.leases.values
            .groupingBy { it.owner }
            .eachCount()
        _state.value = SlmRuntimeState(
            phase = phase,
            loadedModel = runtime.loadedModel,
            activeOperation = active?.let {
                SlmActiveOperation(
                    requestId = it.operationId,
                    kind = it.kind,
                    owner = it.owner,
                    model = it.model
                )
            },
            queueDepth = runtime.pending.size,
            leaseCount = runtime.leases.size,
            leasesByOwner = leasesByOwner,
            pinnedModels = runtime.pins.mapValues { it.value.spec },
            pendingAction = pendingAction,
            lastError = runtime.lastError
        )
    }

    private inner class ActorState {
        var loadedModel: SlmModelSpec? = null
        val leases = linkedMapOf<Long, LeaseImpl>()
        val pins = linkedMapOf<SlmRuntimeOwner, PinRecord>()
        val pending = ArrayDeque<Pending>()
        val unobservedAcquisitions = mutableMapOf<Long, Long>()
        val unobservedPins = mutableMapOf<Long, PinHandoff>()
        val unobservedMaintenance = mutableMapOf<Long, Long>()
        var active: ActiveNative? = null
        var maintenance: MaintenanceLeaseImpl? = null
        var lastError: String? = null
        var pinRestoreBlockedFor: SlmModelSpec? = null
        var unloadRequested: Boolean = false
        var autoUnloadBlocked: Boolean = false
        var evictionScheduled: Boolean = false
    }

    private sealed interface Command {
        data class Acquire(
            val id: Long,
            val owner: SlmRuntimeOwner,
            val spec: SlmModelSpec,
            val response: CompletableDeferred<SlmLease>
        ) : Command

        data class Pin(
            val id: Long,
            val owner: SlmRuntimeOwner,
            val spec: SlmModelSpec,
            val response: CompletableDeferred<Unit>
        ) : Command

        data class Unpin(
            val owner: SlmRuntimeOwner,
            val response: CompletableDeferred<Unit>
        ) : Command

        data class Extract(
            val id: Long,
            val leaseId: Long,
            val request: SlmExtractionRequest,
            val response: CompletableDeferred<SlmExtractionResult>
        ) : Command

        data class Tokenize(
            val id: Long,
            val leaseId: Long,
            val text: String,
            val addSpecial: Boolean,
            val response: CompletableDeferred<Int?>
        ) : Command

        data class ReleaseLease(
            val leaseId: Long,
            val response: CompletableDeferred<Unit>
        ) : Command

        data class AcquisitionObserved(val requestId: Long) : Command

        data class PinObserved(val requestId: Long) : Command

        data class MaintenanceObserved(val requestId: Long) : Command

        data class TryMaintenance(
            val id: Long,
            val owner: SlmRuntimeOwner,
            val pinsToRelease: Set<SlmRuntimeOwner>,
            val response: CompletableDeferred<SlmMaintenanceLease?>
        ) : Command

        data class ReleaseMaintenance(
            val leaseId: Long,
            val response: CompletableDeferred<Unit>
        ) : Command

        data object EvictIfIdle : Command

        data class Cancel(
            val requestId: Long,
            val drained: CompletableDeferred<Unit>
        ) : Command

        data class NativeFinished(
            val operationId: Long,
            val outcome: NativeOutcome
        ) : Command
    }

    private sealed class Pending(open val id: Long) {
        abstract fun cancel(cause: CancellationException)

        data class Acquire(
            override val id: Long,
            val owner: SlmRuntimeOwner,
            val spec: SlmModelSpec,
            val response: CompletableDeferred<SlmLease>
        ) : Pending(id) {
            override fun cancel(cause: CancellationException) {
                response.completeExceptionally(cause)
            }
        }

        data class Pin(
            override val id: Long,
            val owner: SlmRuntimeOwner,
            val spec: SlmModelSpec,
            val response: CompletableDeferred<Unit>
        ) : Pending(id) {
            override fun cancel(cause: CancellationException) {
                response.completeExceptionally(cause)
            }
        }

        data class Extract(
            override val id: Long,
            val leaseId: Long,
            val request: SlmExtractionRequest,
            val response: CompletableDeferred<SlmExtractionResult>
        ) : Pending(id) {
            override fun cancel(cause: CancellationException) {
                response.completeExceptionally(cause)
            }
        }

        data class Tokenize(
            override val id: Long,
            val leaseId: Long,
            val text: String,
            val addSpecial: Boolean,
            val response: CompletableDeferred<Int?>
        ) : Pending(id) {
            override fun cancel(cause: CancellationException) {
                response.completeExceptionally(cause)
            }
        }

        data class AutoUnload(override val id: Long) : Pending(id) {
            override fun cancel(cause: CancellationException) = Unit
        }

        data class Maintenance(
            override val id: Long,
            val lease: MaintenanceLeaseImpl,
            val response: CompletableDeferred<SlmMaintenanceLease?>
        ) : Pending(id) {
            override fun cancel(cause: CancellationException) {
                response.completeExceptionally(cause)
            }
        }

        data class RestorePinnedModel(
            override val id: Long,
            val spec: SlmModelSpec
        ) : Pending(id) {
            override fun cancel(cause: CancellationException) = Unit
        }
    }

    private class ActiveNative(
        val operationId: Long,
        val source: Pending,
        val kind: SlmOperationKind,
        val owner: SlmRuntimeOwner?,
        val model: SlmModelSpec
    ) {
        lateinit var job: Job
        var cancelled: Boolean = false
        var stopping: Boolean = false
        val drainWaiters = mutableListOf<CompletableDeferred<Unit>>()
    }

    private sealed interface NativeOutcome {
        data class ModelChanged(
            val loaded: SlmModelSpec?,
            val failure: Throwable? = null
        ) : NativeOutcome

        data class Extracted(val result: SlmExtractionResult) : NativeOutcome
        data class TokenCounted(val count: Int?) : NativeOutcome
        data class Unloaded(val unloaded: Boolean) : NativeOutcome
        data class Failed(val failure: Throwable) : NativeOutcome
    }

    private data class PinHandoff(
        val owner: SlmRuntimeOwner,
        val installedGeneration: Long,
        val previous: PinRecord?
    )

    private data class PinRecord(
        val spec: SlmModelSpec,
        val generation: Long
    )

    private class ModelChangeException(
        changeFailure: Throwable,
        rollbackFailure: Throwable?
    ) : IllegalStateException(
        buildString {
            append(changeFailure.message ?: "Model change failed")
            if (rollbackFailure != null) {
                append("; restoring the previous model also failed: ")
                append(rollbackFailure.message ?: rollbackFailure::class.java.simpleName)
            }
        },
        changeFailure
    )

    private fun NativeOutcome.failureMessage(): String = when (this) {
        is NativeOutcome.ModelChanged ->
            failure?.message ?: "Model change did not make the requested model resident"
        is NativeOutcome.Unloaded -> "Native backend refused model unload"
        is NativeOutcome.Failed -> failure.message ?: failure::class.java.simpleName
        else -> "Unexpected native runtime result"
    }

    private class LeaseImpl(
        val id: Long,
        override val owner: SlmRuntimeOwner,
        override val model: SlmModelSpec,
        private val extractAction:
            suspend (Long, SlmExtractionRequest) -> SlmExtractionResult,
        private val tokenCountAction:
            suspend (Long, String, Boolean) -> Int?,
        private val releaseAction: suspend (Long) -> Unit
    ) : SlmLease {
        private val released = AtomicBoolean(false)
        override val isReleased: Boolean
            get() = released.get()

        override suspend fun extract(request: SlmExtractionRequest): SlmExtractionResult {
            check(!released.get()) { "SLM lease has been released" }
            return extractAction(id, request)
        }

        override suspend fun countTokens(text: String, addSpecial: Boolean): Int? {
            check(!released.get()) { "SLM lease has been released" }
            return tokenCountAction(id, text, addSpecial)
        }

        override suspend fun release() {
            if (released.compareAndSet(false, true)) {
                withContext(NonCancellable) {
                    releaseAction(id)
                }
            }
        }

        fun markReleased() {
            released.set(true)
        }
    }

    private class MaintenanceLeaseImpl(
        val id: Long,
        override val owner: SlmRuntimeOwner,
        val pinsToRelease: Set<SlmRuntimeOwner>,
        private val releaseAction: suspend (Long) -> Unit
    ) : SlmMaintenanceLease {
        private val released = AtomicBoolean(false)

        override suspend fun release() {
            if (released.compareAndSet(false, true)) {
                withContext(NonCancellable) {
                    releaseAction(id)
                }
            }
        }
    }
}
