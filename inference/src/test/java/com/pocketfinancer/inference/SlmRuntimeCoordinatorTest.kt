package com.pocketfinancer.inference

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class SlmRuntimeCoordinatorTest {
    @Test
    fun `concurrent same-model acquisitions perform one native load`() = runTest {
        val backend = ControllableBackend().apply { blockLoads = true }
        val runtime = runtime(backend)

        val first = async { runtime.acquire(SlmRuntimeOwner.HOME_SYNC, MODEL) }
        val second = async { runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL) }
        runCurrent()

        assertEquals(1, backend.loadCalls)
        assertEquals(SlmRuntimePhase.LOADING, runtime.state.value.phase)

        backend.loadPermits.send(Unit)
        runCurrent()

        val firstLease = first.await()
        val secondLease = second.await()
        assertEquals(1, backend.loadCalls)
        assertEquals(2, runtime.state.value.leaseCount)

        firstLease.release()
        secondLease.release()
        runCurrent()
    }

    @Test
    fun `native inference calls never overlap`() = runTest {
        val backend = ControllableBackend().apply { blockExtractions = true }
        val runtime = runtime(backend)
        val lease = runtime.acquire(SlmRuntimeOwner.HOME_SYNC, MODEL)

        val first = async { lease.extract(request("first")) }
        val second = async { lease.extract(request("second")) }
        runCurrent()

        assertEquals(listOf("first"), backend.startedPrompts)
        assertEquals(1, backend.maxConcurrentNative.get())

        backend.extractionPermits.send(Unit)
        runCurrent()
        assertEquals(listOf("first", "second"), backend.startedPrompts)

        backend.extractionPermits.send(Unit)
        runCurrent()
        assertIs<SlmExtractionResult.Success>(first.await())
        assertIs<SlmExtractionResult.Success>(second.await())
        assertEquals(1, backend.maxConcurrentNative.get())
        lease.release()
    }

    @Test
    fun `unload requested during inference waits for inference to return`() = runTest {
        val backend = ControllableBackend().apply { blockExtractions = true }
        val runtime = runtime(backend)
        val lease = runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL)
        val inference = async { lease.extract(request("active")) }
        runCurrent()

        lease.release()
        runCurrent()
        assertEquals(0, backend.unloadCalls)
        assertEquals(SlmRuntimePhase.RUNNING, runtime.state.value.phase)

        backend.extractionPermits.send(Unit)
        runCurrent()
        inference.await()
        assertEquals(1, backend.unloadCalls)
        assertNull(runtime.state.value.loadedModel)
    }

    @Test
    fun `temporary worker cannot unload selected pinned model`() = runTest {
        val backend = ControllableBackend()
        val runtime = runtime(backend)
        runtime.pin(SlmRuntimeOwner.SELECTED_MODEL, MODEL)
        val worker = runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL)

        worker.release()
        runCurrent()

        assertEquals(0, backend.unloadCalls)
        assertEquals(MODEL, runtime.state.value.loadedModel)
        assertEquals(MODEL, runtime.state.value.pinnedModels[SlmRuntimeOwner.SELECTED_MODEL])

        runtime.unpin(SlmRuntimeOwner.SELECTED_MODEL)
        runCurrent()
        assertEquals(1, backend.unloadCalls)
        assertNull(runtime.state.value.loadedModel)
    }

    @Test
    fun `already queued same-model demand cancels low-priority eviction`() = runTest {
        val backend = ControllableBackend()
        val runtime = runtime(backend)
        val original = runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL)

        val releasing = launch { original.release() }
        val next = async { runtime.acquire(SlmRuntimeOwner.HOME_SYNC, MODEL) }
        runCurrent()

        releasing.join()
        val nextLease = next.await()
        assertEquals(1, backend.loadCalls)
        assertEquals(0, backend.unloadCalls)
        assertEquals(MODEL, runtime.state.value.loadedModel)

        nextLease.release()
        runCurrent()
        assertEquals(1, backend.unloadCalls)
    }

    @Test
    fun `queued cancelled request never reaches backend`() = runTest {
        val backend = ControllableBackend().apply { blockExtractions = true }
        val runtime = runtime(backend)
        val lease = runtime.acquire(SlmRuntimeOwner.HOME_SYNC, MODEL)

        val active = async { lease.extract(request("active")) }
        runCurrent()
        val queued = launch { lease.extract(request("cancelled")) }
        runCurrent()

        queued.cancelAndJoin()
        runCurrent()
        assertEquals(listOf("active"), backend.startedPrompts)

        backend.extractionPermits.send(Unit)
        runCurrent()
        active.await()
        assertEquals(listOf("active"), backend.startedPrompts)
        lease.release()
    }

    @Test
    fun `active cancellation stops and drains before unload`() = runTest {
        val backend = ControllableBackend().apply { blockExtractions = true }
        val runtime = runtime(backend)
        val lease = runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL)

        val active = launch { lease.extract(request("active")) }
        runCurrent()
        active.cancel()
        runCurrent()

        assertEquals(1, backend.stopCalls.size)
        assertFalse(active.isCompleted)
        lease.release()
        runCurrent()
        assertEquals(0, backend.unloadCalls)

        backend.extractionPermits.send(Unit)
        runCurrent()
        active.join()
        assertEquals(1, backend.unloadCalls)
        assertEquals(0, backend.unloadWhileActive)

        val nextLease = runtime.acquire(SlmRuntimeOwner.SETTINGS_TEST, MODEL)
        backend.extractionPermits.send(Unit)
        val next = nextLease.extract(request("after-cancel"))
        assertIs<SlmExtractionResult.Success>(next)
        nextLease.release()
    }

    @Test
    fun `exceptions release leases and allow the next request`() = runTest {
        val backend = ControllableBackend().apply {
            throwingPrompts += "backend-failure"
        }
        val runtime = runtime(backend)

        runtime.withLease(SlmRuntimeOwner.HOME_SYNC, MODEL) { lease ->
            val failed = lease.extract(request("backend-failure"))
            assertIs<SlmExtractionResult.Error>(failed)
            assertEquals(SlmRuntimePhase.ERROR, runtime.state.value.phase)
            assertTrue(runtime.state.value.lastError?.contains("controlled") == true)
        }
        runCurrent()

        assertEquals(0, runtime.state.value.leaseCount)
        val next = runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL)
        val result = next.extract(request("next"))
        assertIs<SlmExtractionResult.Success>(result)
        next.release()
        assertEquals(1, backend.maxConcurrentNative.get())
    }

    @Test
    fun `foreground lease releases native slot between SMS items`() = runTest {
        val backend = ControllableBackend().apply { blockExtractions = true }
        val runtime = runtime(backend)
        val foreground = runtime.acquire(SlmRuntimeOwner.HOME_SYNC, MODEL)
        val external = runtime.acquire(SlmRuntimeOwner.SETTINGS_TEST, MODEL)

        val itemOne = async { foreground.extract(request("item-1")) }
        runCurrent()
        val externalRequest = async { external.extract(request("external")) }
        runCurrent()
        assertEquals(1, runtime.state.value.queueDepth)

        backend.extractionPermits.send(Unit)
        runCurrent()
        itemOne.await()
        val itemTwo = async { foreground.extract(request("item-2")) }
        runCurrent()
        assertEquals(listOf("item-1", "external"), backend.startedPrompts)

        backend.extractionPermits.send(Unit)
        runCurrent()
        externalRequest.await()
        assertEquals(listOf("item-1", "external", "item-2"), backend.startedPrompts)

        backend.extractionPermits.send(Unit)
        runCurrent()
        itemTwo.await()
        foreground.release()
        external.release()
    }

    @Test
    fun `state flow reports queued running stopping ready and unloaded states`() = runTest {
        val backend = ControllableBackend().apply {
            blockLoads = true
            blockExtractions = true
        }
        val runtime = runtime(backend)

        val acquiring = async { runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL) }
        runCurrent()
        assertEquals(SlmRuntimePhase.LOADING, runtime.state.value.phase)
        assertEquals(SlmOperationKind.LOAD, runtime.state.value.activeOperation?.kind)

        backend.loadPermits.send(Unit)
        runCurrent()
        val lease = acquiring.await()
        assertEquals(SlmRuntimePhase.READY, runtime.state.value.phase)

        val inference = launch { lease.extract(request("state")) }
        runCurrent()
        assertEquals(SlmRuntimePhase.RUNNING, runtime.state.value.phase)

        val queued = async { lease.extract(request("queued")) }
        runCurrent()
        assertEquals(1, runtime.state.value.queueDepth)

        queued.cancelAndJoin()
        runCurrent()
        assertEquals(0, runtime.state.value.queueDepth)

        inference.cancel()
        runCurrent()
        assertEquals(SlmRuntimePhase.STOPPING, runtime.state.value.phase)

        backend.extractionPermits.send(Unit)
        runCurrent()
        inference.join()
        assertEquals(SlmRuntimePhase.READY, runtime.state.value.phase)

        lease.release()
        runCurrent()
        assertEquals(SlmRuntimePhase.UNLOADED, runtime.state.value.phase)
        assertNull(runtime.state.value.loadedModel)
    }

    @Test
    fun `incompatible temporary request is rejected by persistent pin`() = runTest {
        val backend = ControllableBackend()
        val runtime = runtime(backend)
        runtime.pin(SlmRuntimeOwner.SELECTED_MODEL, MODEL)

        try {
            runtime.acquire(SlmRuntimeOwner.SMS_WORKER, OTHER_MODEL)
            fail("Expected an incompatible residency request to fail")
        } catch (_: SlmModelResidencyException) {
            // expected
        }

        assertEquals(1, backend.loadCalls)
        assertEquals(MODEL, runtime.state.value.loadedModel)
    }

    @Test
    fun `cancelled maintenance drains unload restores pin and releases gate`() = runTest {
        val backend = ControllableBackend().apply { blockUnloads = true }
        val runtime = runtime(backend)
        runtime.pin(SlmRuntimeOwner.SELECTED_MODEL, MODEL)

        val maintenance = async {
            runtime.tryAcquireMaintenance(
                owner = SlmRuntimeOwner.SETTINGS_MANUAL,
                pinsToRelease = setOf(SlmRuntimeOwner.SELECTED_MODEL)
            )
        }
        runCurrent()
        assertEquals(SlmRuntimePhase.UNLOADING, runtime.state.value.phase)

        maintenance.cancel()
        runCurrent()
        assertFalse(maintenance.isCompleted)

        backend.unloadPermits.send(Unit)
        runCurrent()
        maintenance.join()
        runCurrent()

        assertEquals(MODEL, runtime.state.value.loadedModel)
        assertEquals(MODEL, runtime.state.value.pinnedModels[SlmRuntimeOwner.SELECTED_MODEL])
        assertTrue(runtime.state.value.phase == SlmRuntimePhase.READY)

        val lease = runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL)
        lease.release()
    }

    @Test
    fun `release from cancelled coroutine still removes actor lease`() = runTest {
        val backend = ControllableBackend()
        val runtime = runtime(backend)
        val lease = runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL)

        val releasing = launch {
            coroutineContext.cancel()
            lease.release()
        }
        runCurrent()
        releasing.join()
        runCurrent()

        assertTrue(lease.isReleased)
        assertEquals(0, runtime.state.value.leaseCount)
        assertEquals(1, backend.unloadCalls)
    }

    @Test
    fun `unpin invoked by cancelled caller still commits owner removal`() = runTest {
        val backend = ControllableBackend()
        val runtime = runtime(backend)
        runtime.pin(SlmRuntimeOwner.SELECTED_MODEL, MODEL)

        val unpinning = launch {
            coroutineContext.cancel()
            runtime.unpin(SlmRuntimeOwner.SELECTED_MODEL)
        }
        runCurrent()
        unpinning.join()
        runCurrent()

        assertFalse(
            runtime.state.value.pinnedModels.containsKey(
                SlmRuntimeOwner.SELECTED_MODEL
            )
        )
        assertNull(runtime.state.value.loadedModel)
        assertEquals(1, backend.unloadCalls)
    }

    @Test
    fun `successful retry clears previous runtime error`() = runTest {
        val backend = ControllableBackend().apply { loadFailuresRemaining = 1 }
        val runtime = runtime(backend)

        runCatching {
            runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL)
        }.onSuccess {
            fail("First load should fail")
        }
        runCurrent()
        assertEquals(SlmRuntimePhase.ERROR, runtime.state.value.phase)
        assertTrue(runtime.state.value.lastError != null)

        val recovered = runtime.acquire(SlmRuntimeOwner.SMS_WORKER, MODEL)
        runCurrent()
        assertEquals(SlmRuntimePhase.READY, runtime.state.value.phase)
        assertNull(runtime.state.value.lastError)
        recovered.release()
    }

    @Test
    fun `cancelled older same-spec pin handoff cannot clobber newer generation`() = runTest {
        val backend = ControllableBackend()
        var blockNextObservation = false
        val firstObservationEntered = CompletableDeferred<Unit>()
        val releaseFirstObservation = CompletableDeferred<Unit>()
        val runtime = runtime(backend) {
            if (blockNextObservation) {
                blockNextObservation = false
                firstObservationEntered.complete(Unit)
                releaseFirstObservation.await()
            }
        }
        runtime.pin(SlmRuntimeOwner.SELECTED_MODEL, MODEL)

        blockNextObservation = true
        val older = launch {
            runtime.pin(SlmRuntimeOwner.SELECTED_MODEL, OTHER_MODEL)
        }
        runCurrent()
        firstObservationEntered.await()

        runtime.pin(SlmRuntimeOwner.SELECTED_MODEL, OTHER_MODEL)
        runCurrent()
        assertEquals(
            OTHER_MODEL,
            runtime.state.value.pinnedModels[SlmRuntimeOwner.SELECTED_MODEL]
        )

        older.cancel()
        runCurrent()
        older.join()

        assertEquals(
            OTHER_MODEL,
            runtime.state.value.pinnedModels[SlmRuntimeOwner.SELECTED_MODEL]
        )
        assertEquals(OTHER_MODEL, runtime.state.value.loadedModel)
        runtime.unpin(SlmRuntimeOwner.SELECTED_MODEL)
    }

    @Test
    fun `cancellation before backend begin is consumed only by that operation`() = runTest {
        val backend = ControllableBackend().apply {
            blockBeforeExtractions = true
        }
        val runtime = runtime(backend)
        val lease = runtime.acquire(SlmRuntimeOwner.SETTINGS_TEST, MODEL)

        val cancelled = launch { lease.extract(request("pre-begin")) }
        runCurrent()
        assertTrue(backend.startedPrompts.isEmpty())

        cancelled.cancel()
        runCurrent()
        assertEquals(1, backend.stopCalls.size)
        assertFalse(cancelled.isCompleted)

        backend.beforeExtractionPermits.send(Unit)
        runCurrent()
        cancelled.join()
        assertTrue(backend.startedPrompts.isEmpty())

        backend.blockBeforeExtractions = false
        val next = lease.extract(request("next-operation"))
        assertIs<SlmExtractionResult.Success>(next)
        assertEquals(listOf("next-operation"), backend.startedPrompts)
        lease.release()
    }

    @Test
    fun `throwing stop control call cannot kill actor and queued work continues`() = runTest {
        val backend = ControllableBackend().apply {
            blockExtractions = true
            stopThrows = true
        }
        val runtime = runtime(backend)
        val lease = runtime.acquire(SlmRuntimeOwner.SETTINGS_TEST, MODEL)

        val active = launch { lease.extract(request("stop-throws")) }
        runCurrent()
        val next = async { lease.extract(request("after-stop-failure")) }
        runCurrent()

        active.cancel()
        runCurrent()
        assertFalse(active.isCompleted)
        assertEquals(SlmRuntimePhase.STOPPING, runtime.state.value.phase)
        assertTrue(runtime.state.value.lastError?.contains("native stop") == true)

        backend.extractionPermits.send(Unit)
        runCurrent()
        active.join()
        assertEquals(
            listOf("stop-throws", "after-stop-failure"),
            backend.startedPrompts
        )

        backend.extractionPermits.send(Unit)
        runCurrent()
        assertIs<SlmExtractionResult.Success>(next.await())
        assertEquals(SlmRuntimePhase.READY, runtime.state.value.phase)
        assertNull(runtime.state.value.lastError)
        lease.release()
    }

    private fun kotlinx.coroutines.test.TestScope.runtime(
        backend: ControllableBackend,
        beforePinObserved: suspend (Long) -> Unit = {}
    ): SlmRuntimeCoordinator {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return SlmRuntimeCoordinator(
            backend,
            backgroundScope,
            dispatcher,
            beforePinObserved
        )
    }

    private fun request(label: String) = SlmExtractionRequest(
        messages = listOf(SlmChatMessage("user", label)),
        fallbackPrompt = label
    )

    private class ControllableBackend : LlamaBackend {
        var blockLoads = false
        var blockExtractions = false
        var blockBeforeExtractions = false
        var blockUnloads = false
        var stopThrows = false
        var loadFailuresRemaining = 0
        val loadPermits = Channel<Unit>(Channel.UNLIMITED)
        val extractionPermits = Channel<Unit>(Channel.UNLIMITED)
        val beforeExtractionPermits = Channel<Unit>(Channel.UNLIMITED)
        val unloadPermits = Channel<Unit>(Channel.UNLIMITED)
        val startedPrompts = mutableListOf<String>()
        val throwingPrompts = mutableSetOf<String>()
        val stopCalls = mutableListOf<Long>()
        val stoppedOperations = mutableSetOf<Long>()
        val maxConcurrentNative = AtomicInteger(0)

        var loadCalls = 0
        var unloadCalls = 0
        var unloadWhileActive = 0
        private var nativeCalls = 0
        private var loaded: SlmModelSpec? = null

        override suspend fun load(spec: SlmModelSpec) = nativeCall {
            loadCalls++
            if (blockLoads) {
                loadPermits.receive()
            }
            if (loadFailuresRemaining > 0) {
                loadFailuresRemaining--
                error("controlled load failure")
            }
            loaded = spec
        }

        override suspend fun unload(): Boolean = nativeCall {
            unloadCalls++
            if (nativeCalls > 1) {
                unloadWhileActive++
            }
            if (blockUnloads) {
                unloadPermits.receive()
            }
            loaded = null
            true
        }

        override suspend fun extract(
            operationId: Long,
            spec: SlmModelSpec,
            request: SlmExtractionRequest
        ): SlmExtractionResult = nativeCall {
            check(loaded == spec)
            if (blockBeforeExtractions) {
                beforeExtractionPermits.receive()
            }
            if (operationId in stoppedOperations) {
                return@nativeCall SlmExtractionResult.Stopped(spec)
            }
            startedPrompts += request.fallbackPrompt
            if (request.fallbackPrompt in throwingPrompts) {
                error("controlled extraction failure")
            }
            if (blockExtractions) {
                extractionPermits.receive()
            }
            if (operationId in stoppedOperations) {
                SlmExtractionResult.Stopped(spec)
            } else {
                SlmExtractionResult.Success(
                    json = """{"label":"${request.fallbackPrompt}"}""",
                    model = spec
                )
            }
        }

        override suspend fun countTokens(
            operationId: Long,
            text: String,
            addSpecial: Boolean
        ): Int? = nativeCall { text.length + if (addSpecial) 1 else 0 }

        override fun stop(operationId: Long): Boolean {
            if (stopThrows) {
                stopThrows = false
                throw LinkageError("controlled stop failure")
            }
            stopCalls += operationId
            stoppedOperations += operationId
            return true
        }

        private suspend fun <T> nativeCall(block: suspend () -> T): T {
            nativeCalls++
            maxConcurrentNative.updateAndGet { max -> maxOf(max, nativeCalls) }
            return try {
                block()
            } finally {
                nativeCalls--
            }
        }
    }

    private companion object {
        val MODEL = SlmModelSpec(
            modelId = "primary",
            modelPath = "build/models/primary.gguf",
            artifactRevision = "r1"
        )
        val OTHER_MODEL = SlmModelSpec(
            modelId = "other",
            modelPath = "build/models/other.gguf",
            artifactRevision = "r2"
        )
    }
}
