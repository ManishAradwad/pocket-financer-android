package com.pocketfinancer.ui.settings

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
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val filterLogs: List<String>? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val deviceCapabilities: DeviceCapabilities,
    private val llamaEngine: LlamaEngine,
    private val modelDownloader: ModelDownloader,
    private val promptBuilder: PromptBuilder,
    private val extractionParser: ExtractionParser,
    private val smsFilterPipeline: SmsFilterPipeline
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

            val result = llamaEngine.loadModel(
                path = path,
                contextSize = 3072,
                gpuLayers = 0
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
                filterLogs = null
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
                // ── Phase 1: Thinking ─────────────────────────────────────
                _state.value = _state.value.copy(
                    testProgress = "Phase 1: Thinking (<think> block, max 1024 tokens)..."
                )
                val grammar = llamaEngine.readAsset("sms_extraction.gbnf")
                val rawPrompt = promptBuilder.buildExtractionPrompt(testSender, testBody)
                val chatPrompt = promptBuilder.buildChatPrompt(rawPrompt, enableThinking = true)
                Log.i("PocketFinancer", "Initial raw prompt length: ${rawPrompt.length} chars")
                Log.i("PocketFinancer", "Chat prompt length: ${chatPrompt.length} chars")

                val startTime = System.currentTimeMillis()
                Log.i("PocketFinancer", "=== Phase 1: Thinking ===")

                val thinkPrompt = chatPrompt + "<think>\n"
                val thinkResult = withContext(Dispatchers.IO) {
                    llamaEngine.complete(
                        prompt = thinkPrompt,
                        params = LlamaEngine.InferenceParams(
                            maxTokens = 1024,
                            temperature = 0.0f,
                            stopToken = "</think>"
                        ),
                        keepCache = false
                    ) { token ->
                        _state.value = _state.value.copy(
                            thinkingOutput = (_state.value.thinkingOutput ?: "") + token
                        )
                    }
                }

                val thinkElapsed = System.currentTimeMillis() - startTime
                Log.i("PocketFinancer", "Phase 1 completed in ${thinkElapsed}ms")
                Log.i("PocketFinancer", "Phase 1 output (${thinkResult.length} chars): <<<${thinkResult}>>>")

                // Ensure final thinking output is exactly the returned block
                _state.value = _state.value.copy(thinkingOutput = thinkResult)

                if (thinkResult.isEmpty()) {
                    Log.w("PocketFinancer", "Phase 1 produced empty output!")
                    _state.value = _state.value.copy(
                        testRunning = false,
                        testProgress = "Phase 1 failed: empty thinking output",
                        testError = "Phase 1 (thinking) returned empty. The model may not be responding. Check logcat."
                    )
                    return@launch
                }

                // ── Phase 2: Grammar-constrained JSON ─────────────────────
                _state.value = _state.value.copy(
                    testProgress = "Phase 2: Generating JSON with GBNF grammar..."
                )
                Log.i("PocketFinancer", "=== Phase 2: JSON generation ===")

                val fullPrompt = thinkPrompt + thinkResult + "</think>\n"
                Log.i("PocketFinancer", "Phase 2 conceptual prompt length: ${fullPrompt.length} chars (KV cache reused, decoding suffix only)")

                val answer = withContext(Dispatchers.IO) {
                    llamaEngine.complete(
                        prompt = "</think>\n",
                        params = LlamaEngine.InferenceParams(
                            maxTokens = 256,
                            temperature = 0.0f,
                            grammar = grammar
                        ),
                        keepCache = true
                    ) { token ->
                        _state.value = _state.value.copy(
                            testResult = (_state.value.testResult ?: "") + token
                        )
                    }
                }

                val totalElapsed = System.currentTimeMillis() - startTime
                Log.i("PocketFinancer", "Phase 2 completed in ${totalElapsed}ms (total)")
                Log.i("PocketFinancer", "Phase 2 output (${answer.length} chars): <<<${answer}>>>")

                // ── Process result ────────────────────────────────────────
                _state.value = _state.value.copy(
                    testProgress = "Processing result..."
                )

                val trimmed = answer.trim()
                if (trimmed.isEmpty()) {
                    Log.w("PocketFinancer", "Phase 2 produced empty output!")
                    _state.value = _state.value.copy(
                        testRunning = false,
                        testProgress = null,
                        testError = "Phase 2 (JSON) returned empty. Grammar may have blocked all tokens."
                    )
                    return@launch
                }

                if (trimmed == "null") {
                    Log.i("PocketFinancer", "Model returned null (non-financial)")
                    _state.value = _state.value.copy(
                        testRunning = false,
                        testProgress = null,
                        testResult = "Model returned null (not a financial transaction)\n⏱ ${totalElapsed}ms",
                        testParsed = "N/A"
                    )
                    return@launch
                }

                // Parse the JSON output
                val parsed = extractionParser.parse(trimmed)
                val perfData = llamaEngine.getPerformanceData()
                val perfLine = perfData?.let { p ->
                    "\n\n⚡ Performance:\n" +
                    "  Generation: ${p.tEvalMs}ms for ${p.nTokens} tokens\n" +
                    "  Speed: ${"%.1f".format(p.tokensPerSecond)} tok/s"
                } ?: ""

                Log.i("PocketFinancer", "Parsed result: $parsed")
                Log.i("PocketFinancer", "Performance: ${perfData}")

                _state.value = _state.value.copy(
                    testRunning = false,
                    testProgress = null,
                    testResult = "Raw JSON: $trimmed\n⏱ ${totalElapsed}ms$perfLine",
                    testParsed = parsed?.let {
                        "amount=${it.amount}, type=${it.type}, counterparty=${it.counterparty ?: "-"}, account=${it.account ?: "-"}"
                    } ?: "Parsed: null (non-financial)"
                )

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
