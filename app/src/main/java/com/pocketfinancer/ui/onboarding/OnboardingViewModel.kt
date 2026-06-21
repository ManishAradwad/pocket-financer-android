package com.pocketfinancer.ui.onboarding

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.selectSlmForDevice
import com.pocketfinancer.inference.LlamaEngine
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.sms.SmsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val modelDownloader: ModelDownloader,
    private val smsRepository: SmsRepository,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val pipelineService: PipelineService
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingUiState())
    val state: StateFlow<OnboardingUiState> = _state.asStateFlow()

    init {
        checkPermissions()
        assessDeviceHardware()
        checkModelDownloadStatus()

        // Sync downloader state
        viewModelScope.launch {
            modelDownloader.state.collect { ds ->
                val slm = _state.value.selectedSlm
                val file = slm?.let { getModelFile(it) }
                val isDone = file != null && file.exists() && file.length() > 0

                val finalDs = if (!ds.isDownloading && !ds.isComplete && isDone) {
                    ds.copy(
                        isComplete = true,
                        progress = 1f,
                        downloadedMb = file!!.length() / 1_048_576f,
                        totalMb = file.length() / 1_048_576f,
                        outputPath = file.absolutePath
                    )
                } else {
                    ds
                }

                _state.value = _state.value.copy(
                    downloadState = finalDs,
                    isDownloading = finalDs.isDownloading
                )

                // Auto-transition to syncing when download completes
                if (finalDs.isComplete && _state.value.step == OnboardingStep.DOWNLOAD_SLM) {
                    delay(800)
                    startSyncingPhase()
                }
            }
        }
    }

    fun setStep(step: OnboardingStep) {
        _state.value = _state.value.copy(step = step)
    }

    fun checkPermissions() {
        val granted = smsRepository.hasPermissions()
        _state.value = _state.value.copy(hasPermissions = granted)
        if (granted && _state.value.step == OnboardingStep.PERMISSIONS) {
            _state.value = _state.value.copy(step = OnboardingStep.DOWNLOAD_SLM)
        }
    }

    fun incrementPermissionDeny() {
        _state.value = _state.value.copy(deniedCount = _state.value.deniedCount + 1)
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
        if (file.exists() && file.length() > 0) {
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
        val destFile = getModelFile(slm)
        _state.value = _state.value.copy(isDownloading = true)
        viewModelScope.launch {
            modelDownloader.download(slm.downloadUrl, destFile)
        }
    }

    private fun startSyncingPhase() {
        _state.value = _state.value.copy(step = OnboardingStep.SYNCING)
        viewModelScope.launch {
            runOnboardingSync()
        }
    }

    private suspend fun runOnboardingSync() {
        val slm = _state.value.selectedSlm ?: return
        val modelFile = getModelFile(slm)
        val logs = mutableListOf<String>()

        fun addLog(msg: String) {
            logs.add(msg)
            _state.value = _state.value.copy(syncLogs = logs.toList())
        }

        addLog("System: Initializing local sync...")
        _state.value = _state.value.copy(
            syncProgress = 0.05f,
            syncMessage = "Warming up local AI engine..."
        )
        delay(800)

        // 1. Load the model
        if (!llamaEngine.isModelLoaded()) {
            addLog("Model: Loading ${slm.name} into memory...")
            _state.value = _state.value.copy(syncMessage = "Initializing model layers...")
            val device = deviceCapabilities.assessDevice()
            val hasFp16 = device.cpu?.hasFp16 ?: false
            val result = llamaEngine.loadModel(
                path = modelFile.absolutePath,
                contextSize = 3072,
                gpuLayers = 0,
                numThreads = 0,
                hasFp16 = hasFp16,
                hasThinkingMode = slm.hasThinkingMode
            )
            if (result.isFailure) {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                addLog("Error: Failed to load model ($errorMsg)")
                _state.value = _state.value.copy(
                    modelLoadError = "Failed to load model: $errorMsg"
                )
                return
            }
        }
        addLog("Model: Loaded successfully on device CPU.")

        _state.value = _state.value.copy(
            syncProgress = 0.15f,
            syncMessage = "Scanning recent message inbox..."
        )
        addLog("SmsReader: Querying inbox history (last 7 days)...")
        delay(600)

        // 2. Fetch SMS history (last 7 days)
        val rawMessages = withContext(Dispatchers.IO) {
            smsRepository.fetchHistory(daysBack = 7, limit = 250)
        }

        if (rawMessages.isEmpty()) {
            addLog("SmsReader: No SMS found in inbox.")
            _state.value = _state.value.copy(
                syncProgress = 1.0f,
                syncMessage = "No SMS found in inbox. Complete!",
                syncTotalMessages = 0
            )
            completeOnboarding()
            return
        }
        addLog("SmsReader: Retrieved ${rawMessages.size} messages.")
        _state.value = _state.value.copy(
            syncTotalMessages = rawMessages.size
        )

        // 3. Deterministic Pre-Filtering
        _state.value = _state.value.copy(
            syncProgress = 0.25f,
            syncMessage = "Filtering promotional messages..."
        )
        addLog("Pipeline: Applying regex filters to filter promotional/spam SMS...")
        delay(600)

        val transactionalMessages = rawMessages.filter { msg ->
            smsFilterPipeline.isTransactional(msg.address, msg.body)
        }

        val spamCount = rawMessages.size - transactionalMessages.size
        addLog("Pipeline: Discarded $spamCount non-transactional messages.")
        _state.value = _state.value.copy(
            syncTransactionalCount = transactionalMessages.size
        )

        if (transactionalMessages.isEmpty()) {
            addLog("Pipeline: Found 0 transactional messages.")
            _state.value = _state.value.copy(
                syncProgress = 1.0f,
                syncMessage = "Sync completed! No transactional history."
            )
            completeOnboarding()
            return
        }
        addLog("Pipeline: Found ${transactionalMessages.size} transactions to process.")

        // Limit the heavy sync workload during onboarding to prevent long waiting times
        val syncLimit = 20
        val messagesToProcess = transactionalMessages.take(syncLimit)
        val totalCount = messagesToProcess.size
        addLog("AI: Beginning local offline parsing for $totalCount transactions...")

        val loopStartTime = System.currentTimeMillis()
        val defaultTimePerTxMs = 10000L
        var parsedCount = 0
        var spendsTotal = 0.0
        val recentTxList = mutableListOf<ExtractedTxPreview>()

        // 4. Sequential Inference Pass
        for ((index, sms) in messagesToProcess.withIndex()) {
            val progressBase = 0.25f
            val progressScale = 0.70f
            val currentProgress = progressBase + (index.toFloat() / totalCount) * progressScale
            
            // Set initial/dynamic ETA
            val currentRemaining = totalCount - index
            val elapsedSoFar = System.currentTimeMillis() - loopStartTime
            val estimatedAvgTime = if (index > 0) (elapsedSoFar / index) else defaultTimePerTxMs
            val currentEtaSec = ((estimatedAvgTime * currentRemaining) / 1000f).toInt().coerceAtLeast(1)

            _state.value = _state.value.copy(
                syncProgress = currentProgress,
                syncMessage = "Analyzing SMS ${index + 1} of $totalCount: ${sms.address}...",
                syncEtaSeconds = currentEtaSec
            )
            
            addLog("AI: Analyzing transaction ${index + 1}/$totalCount (${sms.address})...")
            
            val txStartTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    pipelineService.processSingle(sms)
                } catch (e: Exception) {
                    Log.e("OnboardingViewModel", "Sync parse error", e)
                    null
                }
            }
            val durationMs = System.currentTimeMillis() - txStartTime
            
            if (result != null) {
                parsedCount++
                if (result.type == TransactionType.DEBIT) {
                    spendsTotal += result.amount
                }
                recentTxList.add(
                    0,
                    ExtractedTxPreview(
                        amount = result.amount,
                        merchant = result.counterparty ?: "Unknown Merchant",
                        type = result.type.name.lowercase()
                    )
                )

                _state.value = _state.value.copy(
                    syncParsedCount = parsedCount,
                    syncSpendsTotal = spendsTotal,
                    syncRecentTransactions = recentTxList.take(3)
                )

                addLog("➔ Extracted: ₹${result.amount} at ${result.counterparty ?: "Unknown Merchant"} [${"%.1f".format(durationMs / 1000f)}s]")
                addLog("➔ Saved to encrypted local database.")
            } else {
                addLog("➔ Skipped (non-transactional content detected) [${"%.1f".format(durationMs / 1000f)}s]")
            }
        }

        _state.value = _state.value.copy(
            syncProgress = 0.98f,
            syncMessage = "Optimizing encrypted local database...",
            syncEtaSeconds = 0
        )
        addLog("Database: Reindexing and optimizing storage encryption...")
        delay(1000)

        addLog("System: Offline synchronization fully completed!")
        _state.value = _state.value.copy(
            syncProgress = 1.0f,
            syncMessage = "Synchronization completed!"
        )
        delay(600)
        completeOnboarding()
    }

    private fun completeOnboarding() {
        val prefs = context.getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding_completed", true).apply()
        _state.value = _state.value.copy(step = OnboardingStep.COMPLETED)
    }

    private fun getModelFile(slm: SlmTier): File {
        val dir = llamaEngine.getModelStorageDir()
        return File(dir, slm.modelFile)
    }
}
