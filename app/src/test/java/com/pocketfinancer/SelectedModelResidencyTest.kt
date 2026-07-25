package com.pocketfinancer

import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmMaintenanceLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.inference.SlmRuntimePhase
import com.pocketfinancer.inference.SlmRuntimeState
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SelectedModelResidencyTest {

    @Test
    fun `provisional replacement commits exact target and releases next mutation`() = runTest {
        val runtime = FakeRuntime()
        val residency = SelectedModelResidency(runtime)
        residency.pin(MODEL_A)
        val handoff = residency.beginProvisionalPin(MODEL_B, persistedFallback = MODEL_A)

        val nextPin = async {
            residency.pin(MODEL_C)
        }
        runCurrent()
        assertFalse(nextPin.isCompleted)
        assertEquals(listOf(MODEL_A, MODEL_B), runtime.pinCalls)

        var persisted = false
        assertTrue(
            handoff.commit {
                persisted = true
                true
            }
        )
        assertTrue(persisted)
        runCurrent()
        nextPin.await()

        assertEquals(listOf(MODEL_A, MODEL_B, MODEL_C), runtime.pinCalls)
        assertEquals(MODEL_C, residency.currentSelectedSpec())
    }

    @Test
    fun `failed workflow rolls provisional target back to previous exact pin`() = runTest {
        val runtime = FakeRuntime()
        val residency = SelectedModelResidency(runtime)
        residency.pin(MODEL_A)

        val handoff = residency.beginProvisionalPin(MODEL_B, persistedFallback = MODEL_A)
        handoff.rollbackUnlessCommitted()

        assertEquals(listOf(MODEL_A, MODEL_B, MODEL_A), runtime.pinCalls)
        assertEquals(MODEL_A, residency.currentSelectedSpec())
        assertEquals(MODEL_A, runtime.state.value.pinnedModels[SlmRuntimeOwner.SELECTED_MODEL])
    }

    @Test
    fun `older rollback completes before a newer same-spec selection takes ownership`() =
        runTest {
            val runtime = FakeRuntime()
            val residency = SelectedModelResidency(runtime)
            residency.pin(MODEL_A)
            val older = residency.beginProvisionalPin(MODEL_B, persistedFallback = MODEL_A)

            val newer = async {
                residency.pin(MODEL_B)
            }
            runCurrent()
            assertFalse(newer.isCompleted)

            older.rollbackUnlessCommitted()
            runCurrent()
            newer.await()

            assertEquals(
                listOf(MODEL_A, MODEL_B, MODEL_A, MODEL_B),
                runtime.pinCalls
            )
            assertEquals(MODEL_B, residency.currentSelectedSpec())
        }

    @Test
    fun `target load failure restores persisted fallback when startup pin was absent`() =
        runTest {
            val runtime = FakeRuntime().apply {
                failNextPinFor = MODEL_B
            }
            val residency = SelectedModelResidency(runtime)

            try {
                residency.beginProvisionalPin(MODEL_B, persistedFallback = MODEL_A)
                fail("Expected target pin failure")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("synthetic"))
            }

            assertEquals(listOf(MODEL_B, MODEL_A), runtime.pinCalls)
            assertEquals(MODEL_A, residency.currentSelectedSpec())
            assertEquals(MODEL_A, runtime.state.value.pinnedModels[SlmRuntimeOwner.SELECTED_MODEL])
        }

    @Test
    fun `mutation pause rejects new pin and can restore pin after external maintenance`() =
        runTest {
            val runtime = FakeRuntime()
            val residency = SelectedModelResidency(runtime)
            residency.pin(MODEL_A)

            val pause = residency.tryPauseMutations()
            assertNotNull(pause)
            pause!!.recordExternalPinMutation()

            try {
                residency.pin(MODEL_B)
                fail("Expected mutation pause rejection")
            } catch (expected: IllegalStateException) {
                assertTrue(expected.message!!.contains("paused"))
            }

            pause.restorePin(MODEL_A)
            pause.release()
            assertEquals(MODEL_A, residency.currentSelectedSpec())
        }

    @Test
    fun `cancellation during unpin commits runtime and local ownership together`() =
        runTest {
            val runtime = FakeRuntime()
            val residency = SelectedModelResidency(runtime)
            residency.pin(MODEL_A)
            runtime.blockNextUnpin = true

            val unpinJob = launch {
                residency.unpin()
            }
            runtime.unpinEntered.await()
            unpinJob.cancel()
            runCurrent()

            assertFalse(unpinJob.isCompleted)
            assertEquals(MODEL_A, residency.currentSelectedSpec())

            runtime.allowUnpin.complete(Unit)
            unpinJob.join()

            assertEquals(null, residency.currentSelectedSpec())
            assertFalse(
                runtime.state.value.pinnedModels.containsKey(
                    SlmRuntimeOwner.SELECTED_MODEL
                )
            )
        }

    private class FakeRuntime : SlmRuntime {
        private val mutableState = MutableStateFlow(SlmRuntimeState())
        override val state: StateFlow<SlmRuntimeState> = mutableState

        val pinCalls = mutableListOf<SlmModelSpec>()
        var failNextPinFor: SlmModelSpec? = null
        var blockNextUnpin = false
        val unpinEntered = CompletableDeferred<Unit>()
        val allowUnpin = CompletableDeferred<Unit>()

        override suspend fun acquire(
            owner: SlmRuntimeOwner,
            spec: SlmModelSpec
        ): SlmLease = error("Not used")

        override suspend fun pin(owner: SlmRuntimeOwner, spec: SlmModelSpec) {
            check(owner == SlmRuntimeOwner.SELECTED_MODEL)
            pinCalls += spec
            if (failNextPinFor == spec) {
                failNextPinFor = null
                error("synthetic target pin failure")
            }
            mutableState.value = mutableState.value.copy(
                phase = SlmRuntimePhase.READY,
                loadedModel = spec,
                pinnedModels = mapOf(owner to spec)
            )
        }

        override suspend fun unpin(owner: SlmRuntimeOwner) {
            check(owner == SlmRuntimeOwner.SELECTED_MODEL)
            if (blockNextUnpin) {
                blockNextUnpin = false
                unpinEntered.complete(Unit)
                allowUnpin.await()
            }
            mutableState.value = mutableState.value.copy(
                pinnedModels = mutableState.value.pinnedModels - owner
            )
        }

        override suspend fun tryAcquireMaintenance(
            owner: SlmRuntimeOwner,
            pinsToRelease: Set<SlmRuntimeOwner>
        ): SlmMaintenanceLease? = error("Not used")
    }

    private companion object {
        val MODEL_A = model("a")
        val MODEL_B = model("b")
        val MODEL_C = model("c")

        fun model(id: String) = SlmModelSpec(
            modelId = id,
            modelPath = File("build/models/$id.gguf").absolutePath,
            artifactRevision = id
        )
    }
}
