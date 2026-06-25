package com.pocketfinancer.ui.onboarding

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.selectSlmForDevice
import com.pocketfinancer.inference.LlamaEngine
import com.pocketfinancer.inference.ModelDownloader
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

enum class OnboardingStep {
    WELCOME,
    PERMISSIONS,
    DOWNLOAD_SLM,
    SYNCING,
    COMPLETED
}

data class ExtractedTxPreview(
    val amount: Double,
    val merchant: String,
    val type: String
)

data class OnboardingUiState(
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

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceCapabilities: DeviceCapabilities,
    private val llamaEngine: LlamaEngine,
    private val smsRepository: com.pocketfinancer.sms.SmsRepository,
    private val syncManager: OnboardingSyncManager
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        checkPermissions()
        assessDeviceHardware()
        checkModelDownloadStatus()

        // Sync manager state
        viewModelScope.launch {
            syncManager.syncState.collect { syncState ->
                val slm = _state.value.selectedSlm
                val file = slm?.let { getModelFile(it) }
                val isDone = file != null && file.exists() && slm != null && file.length() >= (slm.sizeMb.toLong() * 1024L * 1024L * 95L / 100L)

                val finalDs = if (!syncState.isDownloading && !syncState.downloadState.isComplete && isDone) {
                    syncState.downloadState.copy(
                        isComplete = true,
                        progress = 1f,
                        downloadedMb = file!!.length() / 1_048_576f,
                        totalMb = file.length() / 1_048_576f,
                        outputPath = file.absolutePath
                    )
                } else {
                    syncState.downloadState
                }

                _state.value = _state.value.copy(
                    step = if (syncState.isRunning || syncState.step == OnboardingStep.COMPLETED) syncState.step else _state.value.step,
                    selectedSlm = syncState.selectedSlm ?: _state.value.selectedSlm,
                    downloadState = finalDs,
                    isDownloading = syncState.isDownloading,
                    isModelLoaded = syncState.isModelLoaded,
                    modelLoadError = syncState.modelLoadError,
                    syncProgress = syncState.syncProgress,
                    syncMessage = syncState.syncMessage,
                    syncLogs = syncState.syncLogs,
                    syncEtaSeconds = syncState.syncEtaSeconds,
                    syncTotalMessages = syncState.syncTotalMessages,
                    syncTransactionalCount = syncState.syncTransactionalCount,
                    syncParsedCount = syncState.syncParsedCount,
                    syncSpendsTotal = syncState.syncSpendsTotal,
                    syncRecentTransactions = syncState.syncRecentTransactions
                )
            }
        }
    }

    fun setStep(step: OnboardingStep) {
        _state.value = _state.value.copy(step = step)
    }

    fun checkPermissions() {
        val granted = smsRepository.hasPermissions()
        val notifGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        _state.value = _state.value.copy(
            hasPermissions = granted,
            hasNotificationPermission = notifGranted
        )

        // Automatically transition if both are granted and we are on the permissions screen
        if (granted && notifGranted && _state.value.step == OnboardingStep.PERMISSIONS) {
            _state.value = _state.value.copy(step = OnboardingStep.DOWNLOAD_SLM)
        }
    }

    fun incrementPermissionDeny() {
        _state.value = _state.value.copy(deniedCount = _state.value.deniedCount + 1)
    }

    fun showNotificationWarning() {
        _state.value = _state.value.copy(showNotificationWarning = true)
    }

    fun proceedAnyway() {
        _state.value = _state.value.copy(
            step = OnboardingStep.DOWNLOAD_SLM,
            showNotificationWarning = false
        )
    }

    private fun assessDeviceHardware() {
        try {
            val device = deviceCapabilities.assessDevice()
            val slm = selectSlmForDevice(device)
            _state.value = _state.value.copy(selectedSlm = slm)
        } catch (e: Exception) {
            Log.e("OnboardingViewModel", "Hardware assessment failed", e)
        }
    }

    private fun checkModelDownloadStatus() {
        val slm = _state.value.selectedSlm ?: return
        val file = getModelFile(slm)
        if (file.exists() && file.length() >= (slm.sizeMb.toLong() * 1024L * 1024L * 95L / 100L)) {
            _state.value = _state.value.copy(
                downloadState = ModelDownloader.DownloadState(
                    isDownloading = false,
                    isComplete = true,
                    progress = 1f,
                    downloadedMb = file.length() / 1_048_576f,
                    totalMb = file.length() / 1_048_576f,
                    outputPath = file.absolutePath
                )
            )
        }
    }

    fun downloadModel() {
        val slm = _state.value.selectedSlm ?: return
        syncManager.startOnboarding(context, slm)
    }

    private fun getModelFile(slm: SlmTier): File {
        val dir = llamaEngine.getModelStorageDir()
        return File(dir, slm.modelFile)
    }
}
