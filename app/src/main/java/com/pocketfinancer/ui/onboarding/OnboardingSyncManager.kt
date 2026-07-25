package com.pocketfinancer.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Build
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

    data class OnboardingSyncState(
        val isRunning: Boolean = false,
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
                modelLoadError = "Onboarding start is paused while reset completes."
            )
            return
        }
        _syncState.value = _syncState.value.copy(
            isRunning = true,
            selectedSlm = slm,
            step = OnboardingStep.DOWNLOAD_SLM,
            modelLoadError = null
        )
        val intent = Intent(context, OnboardingService::class.java).apply {
            putExtra("EXTRA_SLM_ID", slm.id)
        }
        runGenerationStore.stamp(intent, requestedGeneration)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun cancelOnboarding(context: Context) {
        val intent = Intent(context, OnboardingService::class.java)
        context.stopService(intent)
        _syncState.value = _syncState.value.copy(
            isRunning = false,
            isDownloading = false,
            downloadState = ModelDownloader.DownloadState(),
            syncProgress = 0f,
            syncMessage = "Cancelled"
        )
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

    private companion object {
        const val BYTES_PER_MEBIBYTE = 1_048_576f
    }
}
