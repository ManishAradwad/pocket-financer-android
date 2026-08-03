package com.pocketfinancer.ui.onboarding

import android.content.Intent
import android.content.SharedPreferences
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.setup.SetupImportStatus
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
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `cancellation is terminal only after service cleanup completes`() {
        val manager = managerBackedBy(5L)
        manager.updateState {
            it.copy(
                isRunning = true,
                isCancelling = true,
                isCancellationAllowed = false,
                runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
                step = OnboardingStep.DOWNLOAD_SLM,
                isDownloading = true,
                syncMessage = "Cancelling model upgrade...",
                downloadState = ModelDownloader.DownloadState(
                    isDownloading = true,
                    progress = 0.4f,
                    downloadedMb = 400f,
                    totalMb = 1_000f
                )
            )
        }

        assertTrue(manager.syncState.value.isRunning)
        manager.completeCancellationIfRequested()

        val state = manager.syncState.value
        assertFalse(state.isRunning)
        assertFalse(state.isCancelling)
        assertFalse(state.isDownloading)
        assertFalse(state.downloadState.isDownloading)
        assertEquals(0.4f, state.downloadState.progress)
        assertEquals("Cancelled", state.syncMessage)
    }

    @Test
    fun `durable model commit closes cancellation before preferences change`() {
        val manager = managerBackedBy(5L)
        manager.updateState {
            it.copy(
                isRunning = true,
                isCancellationAllowed = true,
                runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
            )
        }

        assertTrue(manager.tryBeginModelUpgradeCommit())
        assertFalse(manager.syncState.value.isCancellationAllowed)
        assertEquals(
            "Finishing model activation...",
            manager.syncState.value.syncMessage
        )
        assertFalse(manager.tryBeginModelUpgradeCommit())
    }

    @Test
    fun `historical stop is enabled only after model preparation boundary`() {
        val manager = managerBackedBy(5L)
        manager.updateState {
            it.copy(
                runId = "history-run",
                isRunning = true,
                isPreparingHistoricalModel = true,
                runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP
            )
        }

        assertFalse(
            manager.allowHistoricalImportCancellation(
                runId = "history-run",
                durableStatus = SetupImportStatus.DOWNLOADING
            )
        )
        assertTrue(manager.syncState.value.isPreparingHistoricalModel)

        assertTrue(
            manager.allowHistoricalImportCancellation(
                runId = "history-run",
                durableStatus = SetupImportStatus.SCANNING
            )
        )
        assertTrue(manager.syncState.value.isCancellationAllowed)
        assertFalse(manager.syncState.value.isPreparingHistoricalModel)
    }

    @Test
    fun `onboarding run IDs are nonempty and unique`() {
        val first = newOnboardingRunId()
        val second = newOnboardingRunId()

        assertTrue(first.isNotBlank())
        assertTrue(second.isNotBlank())
        assertNotEquals(first, second)
    }

    @Test
    fun `cancelled service work remains unfinished until cleanup completes`() {
        assertTrue(
            onboardingServiceWorkIsUnfinished(
                workJobIsPresent = true,
                workJobIsCompleted = false,
                cleanupJobIsPresent = false,
                cleanupJobIsCompleted = false
            )
        )
        assertTrue(
            onboardingServiceWorkIsUnfinished(
                workJobIsPresent = true,
                workJobIsCompleted = true,
                cleanupJobIsPresent = true,
                cleanupJobIsCompleted = false
            )
        )
        assertFalse(
            onboardingServiceWorkIsUnfinished(
                workJobIsPresent = true,
                workJobIsCompleted = true,
                cleanupJobIsPresent = true,
                cleanupJobIsCompleted = true
            )
        )
    }

    @Test
    fun `cleanup-only stale commands retire after the drain`() {
        assertEquals(
            12,
            nextCleanupOnlyRetirementStartId(
                currentRetirementStartId = 10,
                deliveredStartId = 12,
                workJobIsPresent = true,
                workJobIsCompleted = true,
                cleanupJobIsPresent = true,
                cleanupJobIsCompleted = false
            )
        )
        assertEquals(
            null,
            nextCleanupOnlyRetirementStartId(
                currentRetirementStartId = null,
                deliveredStartId = 12,
                workJobIsPresent = true,
                workJobIsCompleted = false,
                cleanupJobIsPresent = true,
                cleanupJobIsCompleted = false
            )
        )
        assertEquals(
            13,
            nextCleanupOnlyRetirementStartId(
                currentRetirementStartId = 12,
                deliveredStartId = 13,
                workJobIsPresent = true,
                workJobIsCompleted = true,
                cleanupJobIsPresent = false,
                cleanupJobIsCompleted = false,
                cancellationSettlementPending = true
            )
        )
        assertTrue(
            onboardingServiceWorkIsUnfinished(
                workJobIsPresent = true,
                workJobIsCompleted = true,
                cleanupJobIsPresent = false,
                cleanupJobIsCompleted = false,
                cancellationSettlementPending = true
            )
        )
    }

    @Test
    fun `historical stop matcher rejects stale tokens and download phase`() {
        val active = OnboardingSyncManager.OnboardingSyncState(
            runId = "current-run",
            isRunning = true,
            isCancellationAllowed = true,
            runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP
        )

        assertTrue(
            historicalCancellationMatches(
                state = active,
                requestedRunId = "current-run",
                durableStatus = SetupImportStatus.SCANNING
            )
        )
        assertFalse(
            historicalCancellationMatches(
                state = active,
                requestedRunId = "stale-run",
                durableStatus = SetupImportStatus.SCANNING
            )
        )
        assertFalse(
            historicalCancellationMatches(
                state = active,
                requestedRunId = "current-run",
                durableStatus = SetupImportStatus.DOWNLOADING
            )
        )
        assertTrue(
            historicalCancellationMatches(
                state = active,
                requestedRunId = "current-run",
                durableStatus = SetupImportStatus.PERMISSION_NEEDED
            )
        )
        assertTrue(
            historicalCancellationMatches(
                state = active,
                requestedRunId = "current-run",
                durableStatus = SetupImportStatus.PAUSED
            )
        )
        assertFalse(
            historicalCancellationMatches(
                state = active.copy(isCancellationAllowed = false),
                requestedRunId = "current-run",
                durableStatus = SetupImportStatus.PERMISSION_NEEDED
            )
        )
        assertFalse(
            historicalCancellationMatches(
                state = active.copy(isCancellationAllowed = false),
                requestedRunId = "current-run",
                durableStatus = SetupImportStatus.PAUSED
            )
        )
        assertFalse(
            historicalCancellationMatches(
                state = active.copy(
                    runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
                ),
                requestedRunId = "current-run",
                durableStatus = SetupImportStatus.PROCESSING
            )
        )
        assertTrue(
            onboardingStartMatches(
                state = active,
                requestedRunId = "current-run",
                requestedPurpose =
                    OnboardingSyncManager.RunPurpose.INITIAL_SETUP
            )
        )
        assertFalse(
            onboardingStartMatches(
                state = active,
                requestedRunId = "stale-run",
                requestedPurpose =
                    OnboardingSyncManager.RunPurpose.INITIAL_SETUP
            )
        )
    }

    @Test
    fun `historical cancellation CAS disables repeat action until cleanup`() {
        val manager = managerBackedBy(5L)
        manager.updateState {
            it.copy(
                runId = "history-run",
                isRunning = true,
                isCancellationAllowed = true,
                runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP
            )
        }

        assertTrue(
            manager.tryRequestHistoricalImportCancellation(
                runId = "history-run",
                durableStatus = SetupImportStatus.PROCESSING
            )
        )
        assertTrue(manager.syncState.value.isRunning)
        assertTrue(manager.syncState.value.isCancelling)
        assertFalse(manager.syncState.value.isCancellationAllowed)
        assertEquals(
            "Stopping SMS processing...",
            manager.syncState.value.syncMessage
        )
        assertFalse(
            manager.tryRequestHistoricalImportCancellation(
                runId = "history-run",
                durableStatus = SetupImportStatus.PROCESSING
            )
        )
        assertFalse(manager.tryBeginHistoricalImportCommit("history-run"))

        assertTrue(manager.completeHistoricalImportCancellation("history-run"))
        assertFalse(manager.syncState.value.isRunning)
        assertFalse(manager.syncState.value.isCancelling)
        assertEquals(null, manager.syncState.value.runId)
    }

    @Test
    fun `historical terminal commit CAS prevents a later stop`() {
        val manager = managerBackedBy(5L)
        manager.updateState {
            it.copy(
                runId = "history-run",
                isRunning = true,
                isCancellationAllowed = true,
                runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP
            )
        }

        assertTrue(manager.tryBeginHistoricalImportCommit("history-run"))
        assertFalse(manager.syncState.value.isCancellationAllowed)
        assertFalse(
            manager.tryRequestHistoricalImportCancellation(
                runId = "history-run",
                durableStatus = SetupImportStatus.SCANNING
            )
        )
    }

    @Test
    fun `permission loss can settle an accepted stop without claiming user pause`() {
        val manager = managerBackedBy(5L)
        manager.updateState {
            it.copy(
                runId = "history-run",
                isRunning = true,
                isCancelling = true,
                isCancellationAllowed = false,
                runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
                syncMessage = "Stopping SMS processing..."
            )
        }

        assertTrue(
            manager.acceptHistoricalImportCancellation(
                runId = "history-run",
                durableStatus = SetupImportStatus.PERMISSION_NEEDED
            )
        )
        assertTrue(
            manager.completeHistoricalCancellationWithoutUserPause(
                runId = "history-run",
                syncMessage = "SMS access must be restored to continue setup."
            )
        )
        assertFalse(manager.syncState.value.isRunning)
        assertFalse(manager.syncState.value.isCancelling)
        assertEquals(null, manager.syncState.value.runId)
        assertEquals(
            "SMS access must be restored to continue setup.",
            manager.syncState.value.syncMessage
        )
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
