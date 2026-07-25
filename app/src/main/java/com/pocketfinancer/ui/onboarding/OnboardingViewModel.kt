package com.pocketfinancer.ui.onboarding

import android.content.Context
import android.app.NotificationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.app.NotificationManagerCompat
import android.content.pm.PackageManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.isPublishedModelArtifact
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.pipeline.SmsNotificationHelper
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
    private val modelStorage: SlmModelStorage,
    private val smsRepository: com.pocketfinancer.sms.SmsRepository,
    private val syncManager: OnboardingSyncManager
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        checkPermissions()
        selectStarterModel()
        checkModelDownloadStatus()

        // Sync manager state
        viewModelScope.launch {
            syncManager.syncState.collect { syncState ->
                val slm = _state.value.selectedSlm
                val file = slm?.let { getModelFile(it) }
                val isDone = file != null && isPublishedModelArtifact(file)

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
        val runtimeNotificationPermission =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
        val notifGranted =
            runtimeNotificationPermission && progressNotificationsAvailable()

        _state.value = _state.value.copy(
            hasPermissions = granted,
            hasNotificationPermission = notifGranted
        )

        // Handle auto-transition and state updates if on the permissions screen
        if (granted && _state.value.step == OnboardingStep.PERMISSIONS) {
            if (notifGranted) {
                _state.value = _state.value.copy(step = OnboardingStep.DOWNLOAD_SLM)
            } else {
                _state.value = _state.value.copy(showNotificationWarning = true)
            }
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

    private fun selectStarterModel() {
        // Onboarding deliberately optimizes time-to-first-use. Hardware-based
        // quality upgrades are offered from Home after setup completes.
        _state.value = _state.value.copy(
            selectedSlm = SlmTier.DEFAULT_ONBOARDING_SLM
        )
    }

    private fun checkModelDownloadStatus() {
        val slm = _state.value.selectedSlm ?: return
        val file = getModelFile(slm)
        if (isPublishedModelArtifact(file)) {
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
        return modelStorage.modelFile(slm.modelFile)
    }

    private fun progressNotificationsAvailable(): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            return false
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager?.getNotificationChannel(
            SmsNotificationHelper.CHANNEL_ID
        )
        return channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
    }
}
