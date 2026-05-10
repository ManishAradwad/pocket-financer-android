package com.pocketfinancer.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.explainTierSelection
import com.pocketfinancer.hardware.selectSlmForDevice
import com.pocketfinancer.inference.LlamaEngine
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
    private val promptBuilder: PromptBuilder,
    private val extractionParser: ExtractionParser
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        assessDevice()
        refreshModelStatus()
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

    // ── Model Loading ────────────────────────────────────────────────────

    fun loadSelectedModel() {
        val slm = _state.value.selectedSlm ?: return
        val modelDir = llamaEngine.getModelStorageDir()
        val modelFile = File(modelDir, slm.modelFile)

        if (!modelFile.exists()) {
            _state.value = _state.value.copy(
                modelLoadError = "Model file not found: ${modelFile.absolutePath}\n\n" +
                    "Push it with: adb push <file> ${modelFile.absolutePath}"
            )
            return
        }

        viewModelScope.launch {
            _state.value = _state.value.copy(loadingModel = true, modelLoadError = null)

            val gpuLayers = if (_state.value.deviceInfo?.isGpuAccelerationSupported == true) 99 else 0
            val result = llamaEngine.loadModel(
                path = modelFile.absolutePath,
                contextSize = 1024,
                gpuLayers = gpuLayers
            )

            result.fold(
                onSuccess = {
                    _state.value = _state.value.copy(
                        loadingModel = false,
                        modelLoaded = true,
                        modelPath = modelFile.absolutePath
                    )
                },
                onFailure = { e ->
                    _state.value = _state.value.copy(
                        loadingModel = false,
                        modelLoadError = e.message ?: "Unknown error loading model"
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

    /**
     * Feed a known SMS through the full pipeline and show the result.
     * Uses the first financial SMS from few_shot_examples.json.
     */
    fun runTestSms() {
        if (!llamaEngine.isModelLoaded()) {
            _state.value = _state.value.copy(
                testError = "Model not loaded. Load it first."
            )
            return
        }

        // Known financial SMS from the few-shot examples
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
                        _state.value = _state.value.copy(
                            testRunning = false,
                            testResult = "Raw JSON: ${result.json}\n\n⏱ ${elapsed}ms",
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

    fun getModelFilePath(): String {
        val slm = _state.value.selectedSlm ?: return "No SLM selected"
        return File(llamaEngine.getModelStorageDir(), slm.modelFile).absolutePath
    }
}
