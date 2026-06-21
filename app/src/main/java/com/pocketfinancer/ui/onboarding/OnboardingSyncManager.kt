package com.pocketfinancer.ui.onboarding

import android.content.Context
import android.content.Intent
import android.os.Build
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.ModelDownloader
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingSyncManager @Inject constructor() {

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
        _syncState.value = _syncState.value.copy(
            isRunning = true,
            selectedSlm = slm,
            step = OnboardingStep.DOWNLOAD_SLM,
            modelLoadError = null
        )
        val intent = Intent(context, OnboardingService::class.java).apply {
            putExtra("EXTRA_SLM_ID", slm.id)
        }
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

    fun updateState(transform: (OnboardingSyncState) -> OnboardingSyncState) {
        _syncState.value = transform(_syncState.value)
    }

    fun reset() {
        _syncState.value = OnboardingSyncState()
    }
}
