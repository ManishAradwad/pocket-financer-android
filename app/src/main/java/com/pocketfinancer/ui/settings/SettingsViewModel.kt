package com.pocketfinancer.ui.settings

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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    val testResult: String? = null,
    val testParsed: String? = null,
    val testError: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val deviceCapabilities: DeviceCapabilities,
    private val llamaEngine: LlamaEngine,
    private val modelDownloader: ModelDownloader,
    private val promptBuilder: PromptBuilder,
    private val extractionParser: ExtractionParser
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        assessDevice()
        refreshModelStatus()

        // Mirror downloader state into UI state
        viewModelScope.launch {
            modelDownloader.state.collect { ds ->
                _state.value = _state.value.copy(downloadState = ds)
                // Auto-load after download completes
                if (ds.isComplete && !_state.value.modelLoaded) {
                    ds.outputPath?.let { path -> loadModelFromPath(path) }
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
                contextSize = 1024,
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
        _state.value = _state.value.copy(
            modelLoaded = llamaEngine.isModelLoaded(),
            modelPath = llamaEngine.getModelPath()
        )
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
                testResult = null,
                testParsed = null,
                testError = null
            )

            try {
                val grammar = llamaEngine.readAsset("sms_extraction.gbnf")
                val rawPrompt = promptBuilder.buildExtractionPrompt(testSender, testBody)
                val chatPrompt = promptBuilder.buildChatPrompt(rawPrompt, enableThinking = true)

                val startTime = System.currentTimeMillis()

                val result = withContext(Dispatchers.IO) {
                    llamaEngine.inferForExtraction(
                        prompt = chatPrompt,
                        grammar = grammar,
                        thinkingTokens = 1024,
                        answerTokens = 256
                    )
                }

                val elapsed = System.currentTimeMillis() - startTime

                when (result) {
                    is LlamaEngine.InferenceResult.Success -> {
                        val parsed = extractionParser.parse(result.json)
                        val perfLine = result.perf?.let { p ->
                            "\n\n⚡ Performance:\n" +
                            "  Prompt eval: ${p.tPromptEvalMs}ms\n" +
                            "  Generation:  ${p.tEvalMs}ms for ${p.nTokens} tokens\n" +
                            "  Speed:       ${"%.1f".format(p.tokensPerSecond)} tok/s"
                        } ?: ""
                        _state.value = _state.value.copy(
                            testRunning = false,
                            testResult = "Raw JSON: ${result.json}\n\n⏱ ${elapsed}ms$perfLine",
                            testParsed = parsed?.let {
                                "amount=${it.amount}, type=${it.type}, counterparty=${it.counterparty ?: "-"}, account=${it.account ?: "-"}"
                            } ?: "Parsed: null (non-financial)"
                        )
                    }
                    is LlamaEngine.InferenceResult.Null -> {
                        _state.value = _state.value.copy(
                            testRunning = false,
                            testResult = "Model returned null (not a financial transaction)\n⏱ ${elapsed}ms",
                            testParsed = "N/A"
                        )
                    }
                    is LlamaEngine.InferenceResult.Error -> {
                        _state.value = _state.value.copy(
                            testRunning = false,
                            testError = result.message
                        )
                    }
                    is LlamaEngine.InferenceResult.Stopped -> {
                        _state.value = _state.value.copy(
                            testRunning = false,
                            testError = "Inference was stopped"
                        )
                    }
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    testRunning = false,
                    testError = "Test failed: ${e.message}"
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
