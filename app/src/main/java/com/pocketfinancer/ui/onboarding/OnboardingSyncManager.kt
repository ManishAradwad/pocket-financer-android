package com.pocketfinancer.ui.onboarding

import android.content.Context
import android.content.Intent
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.ModelDownloader
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingSyncManager @Inject constructor(
    private val runGenerationStore: OnboardingRunGenerationStore,
    private val appFlowCoordinator: SlmAppFlowCoordinator
) {

    enum class RunPurpose {
        INITIAL_SETUP,
        MODEL_UPGRADE
    }

    data class OnboardingSyncState(
        val isRunning: Boolean = false,
        val isCancelling: Boolean = false,
        val isCancellationAllowed: Boolean = false,
        val runPurpose: RunPurpose = RunPurpose.INITIAL_SETUP,
        val step: OnboardingStep = OnboardingStep.WELCOME,
        val selectedSlm: SlmTier? = null,
        val downloadState: ModelDownloader.DownloadState = ModelDownloader.DownloadState(),
        val isDownloading: Boolean = false,
        val isModelLoaded: Boolean = false,
        val modelLoadError: String? = null,
        val syncProgress: Float = 0f,
        val syncMessage: String = "",
        val syncLogs: List<String> = emptyList(),
        val syncEtaSeconds: Int = 0,
        val hasPermissions: Boolean = false,
        val hasNotificationPermission: Boolean = false,
        val showNotificationWarning: Boolean = false,
        val deniedCount: Int = 0,
        val syncTotalMessages: Int = 0,
        val syncTransactionalCount: Int = 0,
        val syncParsedCount: Int = 0,
        val syncSpendsTotal: Double = 0.0,
        val syncRecentTransactions: List<ExtractedTxPreview> = emptyList()
    )

    private val _syncState = MutableStateFlow(OnboardingSyncState())
    val syncState: StateFlow<OnboardingSyncState> = _syncState.asStateFlow()

    fun startOnboarding(context: Context, slm: SlmTier) {
        startRun(context, slm, RunPurpose.INITIAL_SETUP)
    }

    fun startModelUpgrade(context: Context, slm: SlmTier) {
        startRun(context, slm, RunPurpose.MODEL_UPGRADE)
    }

    private fun startRun(
        context: Context,
        slm: SlmTier,
        purpose: RunPurpose
    ) {
        if (_syncState.value.isRunning) return

        // Capture before checking the in-memory gate. If reset pauses after the
        // check, this old captured value becomes stale when reset commits. If
        // reset pauses before the check, admissionPaused rejects scheduling.
        // Re-reading the generation after the check would create a TOCTOU where
        // a racing pre-reset start could accidentally receive the new value.
        val requestedGeneration = runGenerationStore.currentGeneration()
        if (appFlowCoordinator.state.value.admissionPaused) {
            _syncState.value = _syncState.value.copy(
                isRunning = false,
                isDownloading = false,
                modelLoadError = if (purpose == RunPurpose.MODEL_UPGRADE) {
                    "Model upgrade is temporarily paused while other model maintenance completes."
                } else {
                    "Onboarding start is paused while reset completes."
                }
            )
            return
        }
        _syncState.value = OnboardingSyncState(
            isRunning = true,
            isCancellationAllowed = purpose == RunPurpose.MODEL_UPGRADE,
            runPurpose = purpose,
            selectedSlm = slm,
            step = OnboardingStep.DOWNLOAD_SLM,
            syncMessage = if (purpose == RunPurpose.MODEL_UPGRADE) {
                "Starting the background model download..."
            } else {
                ""
            }
        )
        val intent = Intent(context, OnboardingService::class.java).apply {
            putExtra("EXTRA_SLM_ID", slm.id)
            putExtra(EXTRA_RUN_PURPOSE, purpose.name)
        }
        runGenerationStore.stamp(intent, requestedGeneration)
        try {
            context.startForegroundService(intent)
        } catch (error: Exception) {
            _syncState.value = _syncState.value.copy(
                isRunning = false,
                isDownloading = false,
                isCancellationAllowed = false,
                modelLoadError =
                    error.message ?: "Could not start background model work."
            )
        }
    }

    /**
     * Requests cancellation without claiming that cleanup has already
     * completed. The service publishes the terminal Cancelled state only after
     * downloader cancellation, selected-model rollback, and app-flow admission
     * release have all drained.
     */
    fun requestModelUpgradeCancellation(context: Context): Boolean {
        while (true) {
            val current = _syncState.value
            if (!current.isRunning ||
                current.isCancelling ||
                !current.isCancellationAllowed ||
                current.runPurpose != RunPurpose.MODEL_UPGRADE
            ) {
                return false
            }
            val cancelling = current.copy(
                isCancelling = true,
                isCancellationAllowed = false,
                syncMessage = "Cancelling model upgrade...",
                modelLoadError = null
            )
            if (_syncState.compareAndSet(current, cancelling)) break
        }

        val serviceWasRunning = context.stopService(
            Intent(context, OnboardingService::class.java)
        )
        if (!serviceWasRunning) {
            completeCancellationIfRequested()
        }
        return true
    }

    /**
     * Linearization point between a cancellable activation and its short,
     * non-cancellable durable commit. Exactly one of cancellation or commit
     * can win the state compare-and-set.
     */
    internal fun tryBeginModelUpgradeCommit(): Boolean {
        while (true) {
            val current = _syncState.value
            if (!current.isRunning ||
                current.isCancelling ||
                !current.isCancellationAllowed ||
                current.runPurpose != RunPurpose.MODEL_UPGRADE
            ) {
                return false
            }
            val committing = current.copy(
                isCancellationAllowed = false,
                syncMessage = "Finishing model activation..."
            )
            if (_syncState.compareAndSet(current, committing)) return true
        }
    }

    internal fun completeCancellationIfRequested() {
        _syncState.update { current ->
            if (!current.isCancelling) {
                current
            } else {
                current.copy(
                    isRunning = false,
                    isCancelling = false,
                    isCancellationAllowed = false,
                    isDownloading = false,
                    downloadState = current.downloadState.copy(
                        isDownloading = false,
                        error = null
                    ),
                    syncMessage = "Cancelled"
                )
            }
        }
    }

    internal fun modelPreparationCompleted(modelFile: File) {
        val sizeMb = modelFile.length() / BYTES_PER_MEBIBYTE
        _syncState.update {
            it.copy(
                step = OnboardingStep.SYNCING,
                isDownloading = false,
                modelLoadError = null,
                downloadState = ModelDownloader.DownloadState(
                    isComplete = true,
                    progress = 1f,
                    downloadedMb = sizeMb,
                    totalMb = sizeMb,
                    outputPath = modelFile.absolutePath
                )
            )
        }
    }

    internal fun modelPreparationFailed(
        errorMessage: String,
        terminalDownloadState: ModelDownloader.DownloadState
    ) {
        val visibleError = terminalDownloadState.error
            ?: "Download failed: $errorMessage"
        _syncState.update {
            it.copy(
                step = OnboardingStep.DOWNLOAD_SLM,
                modelLoadError = visibleError,
                isRunning = false,
                isCancelling = false,
                isCancellationAllowed = false,
                isDownloading = false,
                downloadState = terminalDownloadState.copy(
                    isDownloading = false,
                    isComplete = false,
                    error = visibleError
                )
            )
        }
    }

    fun updateState(transform: (OnboardingSyncState) -> OnboardingSyncState) {
        _syncState.update(transform)
    }

    fun reset() {
        _syncState.value = OnboardingSyncState()
    }

    companion object {
        private const val BYTES_PER_MEBIBYTE = 1_048_576f
        internal const val EXTRA_RUN_PURPOSE =
            "com.pocketfinancer.ui.onboarding.EXTRA_RUN_PURPOSE"
    }
}
