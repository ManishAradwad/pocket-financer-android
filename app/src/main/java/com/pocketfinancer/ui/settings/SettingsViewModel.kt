package com.pocketfinancer.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.explainTierSelection
import com.pocketfinancer.hardware.selectSlmForDevice
import com.pocketfinancer.inference.LlamaEngine
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.model.TransactionType
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

data class SettingsUiState(
    // ── Device Hardware ──
    val deviceInfo: DeviceCapabilities.DeviceInfo? = null,
    val hardwareError: String? = null,

    // ── SLM Selection ──
    val selectedSlm: SlmTier? = null,
    val allTiers: List<SlmTier> = SlmTier.ALL_TIERS,
    val tierExplanations: Map<String, String> = emptyMap(),

    // ── Model Download ──
    val downloadState: ModelDownloader.DownloadState = ModelDownloader.DownloadState(),

    // ── Engine Status ──
    val modelLoaded: Boolean = false,
    val modelPath: String? = null,
    val loadingModel: Boolean = false,
    val modelLoadError: String? = null,

    // ── Test Run ──
    val testRunning: Boolean = false,
    val testProgress: String? = null,          // live status: "Phase 1: Thinking...", etc.
    val thinkingOutput: String? = null,        // raw <think> block from Phase 1
    val testResult: String? = null,
    val testParsed: String? = null,
    val testError: String? = null,
    val filterLogs: List<String>? = null,
    val sessionCacheLogs: List<String>? = null,
    val slmPrompt: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceCapabilities: DeviceCapabilities,
    private val llamaEngine: LlamaEngine,
    private val modelDownloader: ModelDownloader,
    private val promptBuilder: PromptBuilder,
    private val extractionParser: ExtractionParser,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        assessDevice()
        refreshModelStatus()

        // Mirror downloader state into UI state
        viewModelScope.launch {
            modelDownloader.state.collect { ds ->
                val finalDs = if (!ds.isDownloading && !ds.isComplete) {
                    val slm = _state.value.selectedSlm
                    val file = slm?.let { getModelFile(it) }
                    if (file != null && file.exists() && file.length() > 0) {
                        ds.copy(
                            isComplete = true,
                            progress = 1f,
                            downloadedMb = file.length() / 1_048_576f,
                            totalMb = file.length() / 1_048_576f,
                            outputPath = file.absolutePath
                        )
                    } else {
                        ds
                    }
                } else {
                    ds
                }
                _state.value = _state.value.copy(downloadState = finalDs)
                // Auto-load after download completes
                if (finalDs.isComplete && !_state.value.modelLoaded) {
                    finalDs.outputPath?.let { path -> loadModelFromPath(path) }
                }
            }
        }
    }

    // ── Hardware Assessment ──────────────────────────────────────────────

    private fun assessDevice() {
        try {
            val device = deviceCapabilities.assessDevice()
            val slm = selectSlmForDevice(device)
            val explanations = SlmTier.ALL_TIERS.associate { tier ->
                tier.id to explainTierSelection(tier, device, tier == slm)
            }
            _state.value = _state.value.copy(
                deviceInfo = device,
                selectedSlm = slm,
                tierExplanations = explanations
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                hardwareError = "Failed to read hardware: ${e.message}"
            )
        }
    }

    // ── Model Download ───────────────────────────────────────────────────

    fun downloadSelectedModel() {
        val slm = _state.value.selectedSlm ?: return
        val destFile = getModelFile(slm)

        viewModelScope.launch {
            _state.value = _state.value.copy(
                modelLoadError = null
            )
            modelDownloader.download(slm.downloadUrl, destFile)
        }
    }

    fun cancelDownload() {
        modelDownloader.cancel()
    }

    // ── Model Loading ────────────────────────────────────────────────────

    fun loadSelectedModel() {
        val slm = _state.value.selectedSlm ?: return
        val modelFile = getModelFile(slm)

        if (!modelFile.exists()) {
            _state.value = _state.value.copy(
                modelLoadError = "Model not downloaded yet.\nTap \"DOWNLOAD MODEL\" first."
            )
            return
        }

        loadModelFromPath(modelFile.absolutePath)
    }

    private fun loadModelFromPath(path: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loadingModel = true, modelLoadError = null)

            // ── Pre-load diagnostics ──
            val file = java.io.File(path)
            val diagnostics = buildString {
                append("File: ${file.name}\n")
                append("Exists: ${file.exists()}\n")
                if (file.exists()) {
                    append("Size: ${file.length() / 1_048_576} MB (${file.length()} bytes)\n")
                    append("Readable: ${file.canRead()}\n")
                }
                val device = _state.value.deviceInfo
                if (device != null) {
                    append("RAM: ${"%.1f".format(device.ramGb)} GB (tier: ${device.ramTier})\n")
                    append("High-perf CPU: ${device.isHighPerformanceDevice}\n")
                    append("Free storage: ${"%.1f".format(device.storage.availableGb)} GB\n")
                }
            }

            // Fail fast if file is missing or empty
            if (!file.exists() || file.length() == 0L) {
                _state.value = _state.value.copy(
                    loadingModel = false,
                    modelLoadError = "Model file missing or empty.\n\n$diagnostics"
                )
                return@launch
            }

            val slm = _state.value.selectedSlm
            val hasThinking = slm?.hasThinkingMode ?: true
            val hasFp16 = _state.value.deviceInfo?.cpu?.hasFp16 ?: false
            val result = llamaEngine.loadModel(
                path = path,
                contextSize = 3072,
                gpuLayers = 0,
                numThreads = 0, // 0 = let C++ default (std::min(cores, 4)) decide
                hasFp16 = hasFp16,
                hasThinkingMode = hasThinking
            )

            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        loadingModel = false,
                        modelLoaded = true,
                        modelPath = path
                    )
                },
                onFailure = { e ->
                    val nativeHint = if (e.message?.contains("Failed to load model") == true) {
                        "\n\nCheck logcat for native error: adb logcat -s pocketfinancer_llm:*"
                    } else ""
                    _state.value = _state.value.copy(
                        loadingModel = false,
                        modelLoadError = "${e.message}$nativeHint\n\n$diagnostics"
                    )
                }
            )
        }
    }

    fun unloadModel() {
        llamaEngine.unloadModel()
        _state.value = _state.value.copy(modelLoaded = false, modelPath = null)
    }

    fun refreshModelStatus() {
        val loaded = llamaEngine.isModelLoaded()
        val path = llamaEngine.getModelPath()
        _state.value = _state.value.copy(
            modelLoaded = loaded,
            modelPath = path
        )

        val slm = _state.value.selectedSlm
        if (slm != null) {
            val file = getModelFile(slm)
            if (file.exists() && file.length() > 0) {
                val currentDs = _state.value.downloadState
                if (!currentDs.isDownloading && !currentDs.isComplete) {
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
        }
    }

    // ── Test SMS ─────────────────────────────────────────────────────────

    fun runTestSms() {
        if (!llamaEngine.isModelLoaded()) {
            _state.value = _state.value.copy(
                testError = "Model not loaded. Load it first."
            )
            return
        }

        val testSender = "AX-HDFCBK"
        val testBody = "HDFC Bank: Rs.500.00 credited to a/c XXXXXX0000 on 01-01-20 by a/c linked to VPA demouser000@examplebank (UPI Ref No 000000000000)."

        viewModelScope.launch {
            _state.value = _state.value.copy(
                testRunning = true,
                testProgress = "Phase 0: SMS Filtering...",
                testResult = null,
                testParsed = null,
                thinkingOutput = null,
                testError = null,
                filterLogs = null,
                sessionCacheLogs = null,
                slmPrompt = null
            )

            // Perform SMS filtering with a short delay for UI state transition feedback
            delay(800)
            val filterResult = smsFilterPipeline.filterWithDetails(testSender, testBody)
            _state.value = _state.value.copy(
                filterLogs = filterResult.logs
            )
            // Let the user see the resulting logs before continuing
            delay(1000)

            if (!filterResult.isTransactional) {
                _state.value = _state.value.copy(
                    testRunning = false,
                    testProgress = "Filtered Out (Non-transactional)",
                    testResult = "Skipping LLM inference: SMS is classified as non-transactional."
                )
                return@launch
            }

            try {
                val hasThinking = llamaEngine.hasThinkingMode
                val grammar = llamaEngine.readAsset("sms_extraction.gbnf")
                val staticPrefix = promptBuilder.getStaticPrefix()
                val rawPrompt = promptBuilder.buildExtractionPrompt(testSender, testBody)
                val chatPrompt = promptBuilder.buildChatPrompt(rawPrompt, enableThinking = hasThinking)
                Log.i("PocketFinancer", "Initial raw prompt length: ${rawPrompt.length} chars")
                Log.i("PocketFinancer", "Chat prompt length: ${chatPrompt.length} chars")

                _state.value = _state.value.copy(
                    testProgress = "Checking/preparing KV Cache session...",
                    slmPrompt = chatPrompt
                )

                // Split prompt to calculate and show mock session logs
                val splitIndex = chatPrompt.indexOf(staticPrefix)
                if (splitIndex != -1) {
                    val prefixString = chatPrompt.substring(0, splitIndex + staticPrefix.length)
                    val prefixHash = llamaEngine.computeSha256(prefixString)
                    val sessionFile = llamaEngine.getSessionFile(prefixHash)
                    val prefixTokens = llamaEngine.tokenize(prefixString, addSpecial = true)
                    val cacheLogs = mutableListOf<String>()
                    if (prefixTokens != null) {
                        cacheLogs.add("Prefix Size: ${prefixTokens.size} tokens")
                        cacheLogs.add("Prefix Hash: ${prefixHash.take(12)}...")
                        if (sessionFile.exists()) {
                            cacheLogs.add("Session cache file found: ${sessionFile.name}")
                            cacheLogs.add("Reusing existing KV Cache (Skipped heavy prefill phase!).")
                        } else {
                            cacheLogs.add("Session cache file not found. Generating new session cache...")
                        }
                    }
                    _state.value = _state.value.copy(sessionCacheLogs = cacheLogs)
                }

                val startTime = System.currentTimeMillis()
                _state.value = _state.value.copy(
                    testProgress = if (hasThinking) "Phase 1: Thinking (<think> block)..." else "Generating JSON..."
                )

                val result = withContext(Dispatchers.IO) {
                    llamaEngine.inferForExtraction(
                        prompt = chatPrompt,
                        grammar = grammar,
                        staticPrefix = staticPrefix,
                        thinkingTokens = 1024,
                        answerTokens = 256,
                        thinkingCallback = { token ->
                            _state.value = _state.value.copy(
                                thinkingOutput = (_state.value.thinkingOutput ?: "") + token
                            )
                        },
                        jsonCallback = { token ->
                            _state.value = _state.value.copy(
                                testResult = (_state.value.testResult ?: "") + token
                            )
                        }
                    )
                }

                val totalElapsed = System.currentTimeMillis() - startTime
                _state.value = _state.value.copy(testProgress = "Processing result...")

                when (result) {
                    is LlamaEngine.InferenceResult.Success -> {
                        val trimmed = result.json.trim()
                        val parsed = extractionParser.parse(trimmed)
                        val perfLine = result.perf?.let { p ->
                            "\n\n⚡ Performance:\n" +
                            "  Generation: ${p.tEvalMs}ms for ${p.nTokens} tokens\n" +
                            "  Speed: ${"%.1f".format(p.tokensPerSecond)} tok/s"
                        } ?: ""

                        // Save mock transaction to database on successful test run
                        parsed?.let { p ->
                            val accountName = p.account
                            val account = if (accountName != null) {
                                accountRepository.getOrCreate(accountName, "Unknown Bank", "auto-extracted")
                            } else {
                                accountRepository.ensureDefault()
                            }
                            transactionRepository.insert(
                                TransactionRepository.NewTransaction(
                                    amount = p.amount,
                                    merchant = p.counterparty ?: "Unknown Merchant",
                                    date = System.currentTimeMillis(),
                                    type = p.type,
                                    accountId = account.id,
                                    rawMessage = testBody,
                                    sender = testSender
                                )
                            )
                        }

                        _state.value = _state.value.copy(
                            testRunning = false,
                            testProgress = null,
                            testResult = "Raw JSON: $trimmed\n⏱ ${totalElapsed}ms$perfLine",
                            testParsed = parsed?.let {
                                "amount=${it.amount}, type=${it.type.name.lowercase()}, counterparty=${it.counterparty ?: "-"}, account=${it.account ?: "-"}"
                            } ?: "Parsed: null (non-financial)"
                        )
                    }
                    is LlamaEngine.InferenceResult.Null -> {
                        _state.value = _state.value.copy(
                            testRunning = false,
                            testProgress = null,
                            testResult = "Model returned null (not a financial transaction)\n⏱ ${totalElapsed}ms",
                            testParsed = "N/A"
                        )
                    }
                    is LlamaEngine.InferenceResult.Error -> {
                        _state.value = _state.value.copy(
                            testRunning = false,
                            testProgress = null,
                            testError = result.message
                        )
                    }
                    is LlamaEngine.InferenceResult.Stopped -> {
                        _state.value = _state.value.copy(
                            testRunning = false,
                            testProgress = null,
                            testError = "Inference stopped"
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("PocketFinancer", "Test failed with exception", e)
                _state.value = _state.value.copy(
                    testRunning = false,
                    testProgress = null,
                    testError = "Test failed: ${e.message}\n${e.stackTraceToString().take(500)}"
                )
            }
        }
    }

    fun resetOnboarding(onSuccess: () -> Unit) {
        viewModelScope.launch {
            llamaEngine.unloadModel()
            withContext(Dispatchers.IO) {
                transactionRepository.clearDatabase()
            }
            val prefs = context.getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
            prefs.edit().putBoolean("onboarding_completed", false).apply()
            withContext(Dispatchers.Main) {
                onSuccess()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    fun getModelFilePath(): String {
        val slm = _state.value.selectedSlm ?: return "No SLM selected"
        return getModelFile(slm).absolutePath
    }

    private fun getModelFile(slm: SlmTier): File {
        val dir = llamaEngine.getModelStorageDir()
        return File(dir, slm.modelFile)
    }
}
