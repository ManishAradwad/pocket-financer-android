package com.pocketfinancer.inference

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Kotlin wrapper around the llama.cpp JNI bridge.
 *
 * Handles:
 * - Model loading/unloading
 * - Two-phase generation for Qwen3 thinking models:
 *   Phase 1: think pass (no grammar, <think>...</think>)
 *   Phase 2: GBNF-constrained JSON decode
 * - Stop/cancel support
 * - GPU layer configuration
 */
@Singleton
class LlamaEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * Inference parameters for a single completion call.
     */
    data class InferenceParams(
        val temperature: Float = 0.0f,
        val maxTokens: Int = 200,
        val grammar: String? = null,     // null = no grammar constraint
        val stopToken: String? = null,   // e.g. "</think>" for thinking pass
    )

    /**
     * The result of a two-phase extraction call.
     */
    sealed class InferenceResult {
        data class Success(val json: String) : InferenceResult()
        data object Null : InferenceResult()          // non-financial SMS
        data class Error(val message: String) : InferenceResult()
        data object Stopped : InferenceResult()
    }

    private var modelHandle: Long = 0
    private var isLoaded: Boolean = false
    private var modelPath: String? = null

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Load a GGUF model from the given absolute file path.
     * Context size defaults to 1024 (the Qwen3-1.7B production config).
     */
    suspend fun loadModel(path: String, contextSize: Int = 1024, gpuLayers: Int = 0): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (isLoaded) unloadModel()
                modelHandle = nativeLoadModel(path, contextSize, gpuLayers)
                if (modelHandle == 0L) {
                    return@withContext Result.failure(Exception("Failed to load model: $path"))
                }
                isLoaded = true
                modelPath = path
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /**
     * Unload the currently loaded model and free memory.
     */
    fun unloadModel() {
        if (modelHandle != 0L) {
            nativeUnloadModel(modelHandle)
            modelHandle = 0
        }
        isLoaded = false
        modelPath = null
    }

    fun isModelLoaded(): Boolean = isLoaded

    fun getModelPath(): String? = modelPath

    // ── Two-Phase Inference ─────────────────────────────────────────────────
    //
    // Qwen3 models use a "thinking" mode where the model first reasons inside
    // <think>...</think> tags and then outputs the final answer. Without this
    // split, GBNF grammar suppresses the <think> token (grammar disallows `<`)
    // and the model collapses into copying few-shot demos.
    //
    // Phase 1: generate thinking tokens with stop=["</think>"], no grammar
    // Phase 2: append the think block, apply GBNF grammar, decode JSON

    /**
     * Run the full two-phase extraction pipeline.
     *
     * @param prompt         The full chat-template-rendered prompt
     * @param grammar        GBNF grammar string (from sms_extraction.gbnf)
     * @param thinkingTokens Max tokens for the thinking phase (default 1024)
     * @param answerTokens   Max tokens for the JSON answer phase (default 256)
     * @return InferenceResult.Success(jsonString) or InferenceResult.Null
     */
    suspend fun inferForExtraction(
        prompt: String,
        grammar: String,
        thinkingTokens: Int = 1024,
        answerTokens: Int = 256
    ): InferenceResult = withContext(Dispatchers.IO) {
        if (!isLoaded) {
            return@withContext InferenceResult.Error("Model not loaded")
        }

        try {
            // Phase 1: Thinking pass — generate <think>... block
            val thinkPrompt = prompt + "<think>\n"
            val thinkResult = nativeCompletion(
                modelHandle,
                thinkPrompt,
                null,              // no grammar during thinking
                thinkingTokens,
                0.0f,             // greedy sampling
                "</think>"         // stop token
            )

            // Check for stop signal
            if (thinkResult.isEmpty() && modelHandle != 0L) {
                return@withContext InferenceResult.Error("Thinking phase produced empty output")
            }

            // Phase 2: Grammar-constrained JSON decode
            val fullPrompt = thinkPrompt + thinkResult + "</think>\n"
            val answer = nativeCompletion(
                modelHandle,
                fullPrompt,
                grammar,           // GBNF grammar applied here
                answerTokens,
                0.0f,              // greedy sampling for deterministic output
                null               // no stop token — grammar controls completion
            )

            if (answer.isEmpty()) {
                return@withContext InferenceResult.Error("JSON decode produced empty output")
            }

            // The answer should be either "null" or a JSON object
            val trimmed = answer.trim()
            return@withContext if (trimmed == "null") {
                InferenceResult.Null
            } else {
                InferenceResult.Success(trimmed)
            }

        } catch (e: Exception) {
            InferenceResult.Error(e.message ?: "Unknown inference error")
        }
    }

    /**
     * Single-pass completion (no grammar, no thinking split).
     * Used for simple prompts or testing.
     */
    suspend fun complete(
        prompt: String,
        params: InferenceParams = InferenceParams()
    ): String = withContext(Dispatchers.IO) {
        if (!isLoaded) return@withContext ""
        nativeCompletion(
            modelHandle,
            prompt,
            params.grammar,
            params.maxTokens,
            params.temperature,
            params.stopToken
        )
    }

    // ── Control ────────────────────────────────────────────────────────────

    /**
     * Signal the running completion to stop at the next token boundary.
     * Non-blocking — returns immediately.
     */
    fun stop() {
        if (modelHandle != 0L) {
            nativeStop(modelHandle)
        }
    }

    /**
     * Get the estimated size of a GGUF model file without loading it.
     * Used to show download size before fetching.
     */
    fun getModelSize(path: String): Long {
        return nativeGetModelSize(path)
    }

    // ── Assets ─────────────────────────────────────────────────────────────

    /**
     * Read a text asset bundled with the app.
     */
    fun readAsset(filename: String): String {
        return context.assets.open(filename).bufferedReader().use { it.readText() }
    }

    fun readAssetBytes(filename: String): ByteArray {
        return context.assets.open(filename).use { it.readBytes() }
    }

    // ── Model Download Helpers ─────────────────────────────────────────────

    /**
     * Returns the path where the GGUF model should be stored on first download.
     * Uses the app's internal files directory so it's private and auto-cleaned
     * on uninstall.
     */
    fun getModelStorageDir(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    companion object {
        /** Name of the bundled Qwen3-1.7B GGUF file (if pre-bundled). */
        const val MODEL_FILENAME = "qwen3-1.7b-Q8_0.gguf"

        init {
            System.loadLibrary("pocketfinancer_llm")
        }
    }

    // ── JNI declarations ──────────────────────────────────────────────────

    private external fun nativeLoadModel(path: String, nCtx: Int, nGpuLayers: Int): Long
    private external fun nativeCompletion(
        handle: Long,
        prompt: String,
        grammar: String?,
        nPredict: Int,
        temperature: Float,
        stop: String?
    ): String
    private external fun nativeStop(handle: Long)
    private external fun nativeUnloadModel(handle: Long)
    private external fun nativeGetModelSize(path: String): Long
}
