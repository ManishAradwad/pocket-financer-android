package com.pocketfinancer.ui.onboarding

import android.content.Intent
import android.content.SharedPreferences
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmRuntimeOwner
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingRunGenerationStoreTest {

    @Test
    fun `start delivered after reset generation advances is rejected`() = runTest {
        val persistedGeneration = AtomicLong(7L)
        val store = storeBackedBy(persistedGeneration)
        val stampedBeforeReset = store.currentGeneration()
        val allowDelayedDelivery = CompletableDeferred<Unit>()

        val delayedAdmission = async {
            allowDelayedDelivery.await()
            store.isCurrentGeneration(stampedBeforeReset)
        }
        runCurrent()

        // Represents reset's durable commit occurring while Android is still
        // holding the previously issued service start.
        persistedGeneration.set(store.nextGeneration())
        allowDelayedDelivery.complete(Unit)

        assertFalse(delayedAdmission.await())
    }

    @Test
    fun `start stamped after reset is accepted and unstamped start is rejected`() {
        val persistedGeneration = AtomicLong(3L)
        val store = storeBackedBy(persistedGeneration)

        assertTrue(store.isCurrentGeneration(store.currentGeneration()))
        assertFalse(store.isCurrentGeneration(null))
        assertFalse(store.isCurrentGeneration(2L))
    }

    @Test
    fun `intent stamp and delivery check use the durable generation`() {
        val persistedGeneration = AtomicLong(11L)
        val store = storeBackedBy(persistedGeneration)
        val intent = mockk<Intent>()
        every {
            intent.putExtra(
                OnboardingRunGenerationStore.EXTRA_RUN_GENERATION,
                11L
            )
        } returns intent
        every {
            intent.hasExtra(OnboardingRunGenerationStore.EXTRA_RUN_GENERATION)
        } returns true
        every {
            intent.getLongExtra(
                OnboardingRunGenerationStore.EXTRA_RUN_GENERATION,
                OnboardingRunGenerationStore.INITIAL_GENERATION
            )
        } returns 11L

        store.stamp(intent)
        assertTrue(store.isCurrent(intent))
        verify(exactly = 1) {
            intent.putExtra(
                OnboardingRunGenerationStore.EXTRA_RUN_GENERATION,
                11L
            )
        }

        persistedGeneration.set(12L)
        assertFalse(store.isCurrent(intent))
    }

    @Test
    fun `captured pre-pause generation is not refreshed after reset commits`() {
        val persistedGeneration = AtomicLong(21L)
        val store = storeBackedBy(persistedGeneration)
        val capturedBeforeAdmissionCheck = store.currentGeneration()
        val intent = mockk<Intent>()
        every {
            intent.putExtra(
                OnboardingRunGenerationStore.EXTRA_RUN_GENERATION,
                capturedBeforeAdmissionCheck
            )
        } returns intent
        every {
            intent.hasExtra(OnboardingRunGenerationStore.EXTRA_RUN_GENERATION)
        } returns true
        every {
            intent.getLongExtra(
                OnboardingRunGenerationStore.EXTRA_RUN_GENERATION,
                OnboardingRunGenerationStore.INITIAL_GENERATION
            )
        } returns capturedBeforeAdmissionCheck

        // Reset can pause and durably advance the generation after the manager's
        // admission check. The intent must retain the pre-check value rather
        // than re-read and accidentally acquire reset's new generation.
        persistedGeneration.set(22L)
        store.stamp(intent, capturedBeforeAdmissionCheck)

        assertFalse(store.isCurrent(intent))
        verify(exactly = 1) {
            intent.putExtra(
                OnboardingRunGenerationStore.EXTRA_RUN_GENERATION,
                21L
            )
        }
    }

    @Test
    fun `onboarding start issued while reset pause is held is rejected before scheduling`() =
        runTest {
            val store = storeBackedBy(AtomicLong(5L))
            val coordinator = SlmAppFlowCoordinator()
            val pause = checkNotNull(
                coordinator.tryPauseAndDrain(SlmRuntimeOwner.SETTINGS_MANUAL)
            )
            val manager = OnboardingSyncManager(store, coordinator)

            manager.startOnboarding(
                context = mockk(relaxed = true),
                slm = SlmTier.DEFAULT_ONBOARDING_SLM
            )

            assertFalse(manager.syncState.value.isRunning)
            assertTrue(manager.syncState.value.modelLoadError!!.contains("paused"))
            pause.release()
        }

    @Test
    fun `completed model preparation leaves download page and enters syncing`() {
        val manager = managerBackedBy(5L)
        val model = File.createTempFile("onboarding-model", ".gguf")
        try {
            model.writeBytes(byteArrayOf(1, 2, 3, 4))
            manager.updateState {
                it.copy(
                    isRunning = true,
                    step = OnboardingStep.DOWNLOAD_SLM,
                    isDownloading = true,
                    modelLoadError = "old error"
                )
            }

            manager.modelPreparationCompleted(model)

            val state = manager.syncState.value
            assertTrue(state.isRunning)
            assertEquals(OnboardingStep.SYNCING, state.step)
            assertFalse(state.isDownloading)
            assertTrue(state.downloadState.isComplete)
            assertEquals(1f, state.downloadState.progress)
            assertEquals(model.absolutePath, state.downloadState.outputPath)
            assertEquals(null, state.modelLoadError)
        } finally {
            model.delete()
        }
    }

    @Test
    fun `model preparation failure remains visible on download page`() {
        val manager = managerBackedBy(5L)
        manager.updateState {
            it.copy(
                isRunning = true,
                step = OnboardingStep.DOWNLOAD_SLM,
                isDownloading = true
            )
        }
        val terminalState = ModelDownloader.DownloadState(
            progress = 1f,
            error = "Download failed: publication denied"
        )

        manager.modelPreparationFailed(
            errorMessage = "publication denied",
            terminalDownloadState = terminalState
        )

        val state = manager.syncState.value
        assertFalse(state.isRunning)
        assertEquals(OnboardingStep.DOWNLOAD_SLM, state.step)
        assertFalse(state.isDownloading)
        assertFalse(state.downloadState.isComplete)
        assertEquals("Download failed: publication denied", state.downloadState.error)
        assertEquals(state.downloadState.error, state.modelLoadError)
    }

    private fun managerBackedBy(generation: Long): OnboardingSyncManager =
        OnboardingSyncManager(
            runGenerationStore = storeBackedBy(AtomicLong(generation)),
            appFlowCoordinator = SlmAppFlowCoordinator()
        )

    private fun storeBackedBy(
        persistedGeneration: AtomicLong
    ): OnboardingRunGenerationStore {
        val preferences = mockk<SharedPreferences>()
        every {
            preferences.getLong(
                OnboardingRunGenerationStore.PREFERENCE_KEY,
                OnboardingRunGenerationStore.INITIAL_GENERATION
            )
        } answers {
            persistedGeneration.get()
        }
        return OnboardingRunGenerationStore(preferences)
    }
}
