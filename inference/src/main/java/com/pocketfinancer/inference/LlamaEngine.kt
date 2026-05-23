package com.pocketfinancer.inference

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
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
 * - Chat template rendering via model's built-in Jinja template
 * - Performance data capture (prompt eval time, token generation speed)
 * - Stop/cancel support
 * - GPU layer configuration
 */
@Singleton
class LlamaEngine @Inject constructor(
    @ApplicationContext private val context: Context
) {
    /**
     * A single chat message for chat template rendering.
     */
    data class ChatMessage(
        val role: String,    // "system", "user", "assistant"
        val content: String
    )

    fun interface TokenCallback {
        fun onToken(token: String)
    }

    /**
     * Performance data from the most recent inference run.
     */
    data class PerformanceData(
        val tLoadMs: Long,
        val tPromptEvalMs: Long,
        val tEvalMs: Long,
        val nTokens: Int
    ) {
        /** Total tokens per second during generation phase. */
        val tokensPerSecond: Double
            get() = if (tEvalMs > 0) (nTokens.toDouble() / (tEvalMs / 1000.0)) else 0.0
    }

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
        data class Success(
            val json: String,
            val perf: PerformanceData? = null   // timing data from this inference
        ) : InferenceResult()
        data object Null : InferenceResult()          // non-financial SMS
        data class Error(val message: String) : InferenceResult()
        data object Stopped : InferenceResult()
    }

    private var modelHandle: Long = 0
    private var isLoaded: Boolean = false
    private var modelPath: String? = null
    var hasThinkingMode: Boolean = true
        private set

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /**
     * Load a GGUF model from the given absolute file path.
     * Context size defaults to 3072 (the Qwen3-1.7B production config).
     */
    suspend fun loadModel(
        path: String,
        contextSize: Int = 3072,
        gpuLayers: Int = 0,
        numThreads: Int = 0,
        hasThinkingMode: Boolean = true
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                if (isLoaded) unloadModel()
                modelHandle = nativeLoadModel(path, contextSize, gpuLayers, numThreads)
                if (modelHandle == 0L) {
                    return@withContext Result.failure(Exception("Failed to load model: $path"))
                }
                isLoaded = true
                modelPath = path
                this@LlamaEngine.hasThinkingMode = hasThinkingMode
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
        hasThinkingMode = true
    }

    fun isModelLoaded(): Boolean = isLoaded

    fun getModelPath(): String? = modelPath

    // ── Chat Template ──────────────────────────────────────────────────────

    /**
     * Render a list of chat messages using the model's built-in Jinja template.
     * Returns null if the model doesn't have a template or if rendering fails.
     */
    fun applyChatTemplate(
        messages: List<ChatMessage>,
        addAssistantPrefix: Boolean = true
    ): String? {
        if (modelHandle == 0L) return null
        val jsonArray = JSONArray().apply {
            messages.forEach { msg ->
                put(JSONObject().apply {
                    put("role", msg.role)
                    put("content", msg.content)
                })
            }
        }
        val result = nativeApplyChatTemplate(modelHandle, jsonArray.toString(), addAssistantPrefix)
        return result.ifEmpty { null }
    }

    // ── Performance Data ───────────────────────────────────────────────────

    /**
     * Get performance timing data from the context since model load.
     * Returns null if no inference has been performed yet.
     */
    fun getPerformanceData(): PerformanceData? {
        if (modelHandle == 0L) return null
        val json = nativeGetPerfData(modelHandle) ?: return null
        return try {
            val obj = JSONObject(json)
            if (obj.keys().asSequence().none()) return null  // empty object
            PerformanceData(
                tLoadMs = obj.optLong("t_load_ms", 0),
                tPromptEvalMs = obj.optLong("t_p_eval_ms", 0),
                tEvalMs = obj.optLong("t_eval_ms", 0),
                nTokens = obj.optInt("n_tokens", 0)
            )
        } catch (_: Exception) {
            null
        }
    }

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
        staticPrefix: String? = null,
        thinkingTokens: Int = 1024,
        answerTokens: Int = 256,
        callback: TokenCallback? = null
    ): InferenceResult = withContext(Dispatchers.IO) {
        if (!isLoaded) {
            return@withContext InferenceResult.Error("Model not loaded")
        }

        try {
            if (!hasThinkingMode) {
                // Direct single-pass grammar-constrained JSON generation (Gemma, etc.)
                var executionPrompt = prompt
                var keepCache = false

                if (staticPrefix != null) {
                    val splitIndex = prompt.indexOf(staticPrefix)
                    if (splitIndex != -1) {
                        val prefixString = prompt.substring(0, splitIndex + staticPrefix.length)
                        val suffixString = prompt.substring(splitIndex + staticPrefix.length)

                        val prefixHash = computeSha256(prefixString)
                        val sessionFile = getSessionFile(prefixHash)

                        val prefixTokens = tokenize(prefixString, addSpecial = true)
                        if (prefixTokens != null) {
                            var sessionLoaded = false
                            if (sessionFile.exists()) {
                                android.util.Log.i("pocketfinancer_llm", "Session cache file found: ${sessionFile.name}. Loading...")
                                val loaded = loadSession(sessionFile.absolutePath, prefixTokens.size)
                                if (loaded == prefixTokens.size) {
                                    sessionLoaded = true
                                    android.util.Log.i("pocketfinancer_llm", "Session cache loaded successfully ($loaded tokens).")
                                } else {
                                    android.util.Log.w("pocketfinancer_llm", "Loaded tokens ($loaded) does not match expected size (${prefixTokens.size}). Regenerating...")
                                }
                            }

                            if (!sessionLoaded) {
                                android.util.Log.i("pocketfinancer_llm", "Generating new session cache...")
                                // Prefill-only run
                                nativeCompletion(
                                    modelHandle,
                                    prefixString,
                                    null,
                                    0,
                                    0.0f,
                                    null,
                                    false,
                                    null
                                )
                                deleteStaleSessions(prefixHash)
                                val saved = saveSession(sessionFile.absolutePath, prefixTokens)
                                android.util.Log.i("pocketfinancer_llm", "Session cache saved: $saved")
                            }

                            executionPrompt = suffixString
                            keepCache = true
                        }
                    }
                }

                // Run direct JSON generation with grammar applied
                val answer = nativeCompletion(
                    modelHandle,
                    executionPrompt,
                    grammar,
                    answerTokens,
                    0.0f,
                    null,
                    keepCache,
                    callback
                )

                if (answer.isEmpty()) {
                    return@withContext InferenceResult.Error("JSON decode produced empty output")
                }

                val perf = getPerformanceData()
                val trimmed = answer.trim()
                return@withContext if (trimmed == "null") {
                    InferenceResult.Null
                } else {
                    InferenceResult.Success(trimmed, perf = perf)
                }
            }

            // Two-phase thinking inference (Qwen3, etc.)
            var thinkPrompt = prompt + "<think>\n"
            var keepCacheFirstPhase = false

            if (staticPrefix != null) {
                val splitIndex = prompt.indexOf(staticPrefix)
                if (splitIndex != -1) {
                    val prefixString = prompt.substring(0, splitIndex + staticPrefix.length)
                    val suffixString = prompt.substring(splitIndex + staticPrefix.length)

                    val prefixHash = computeSha256(prefixString)
                    val sessionFile = getSessionFile(prefixHash)

                    val prefixTokens = tokenize(prefixString, addSpecial = true)
                    if (prefixTokens != null) {
                        var sessionLoaded = false
                        if (sessionFile.exists()) {
                            android.util.Log.i("pocketfinancer_llm", "Session cache file found: ${sessionFile.name}. Loading...")
                            val loaded = loadSession(sessionFile.absolutePath, prefixTokens.size)
                            if (loaded == prefixTokens.size) {
                                sessionLoaded = true
                                android.util.Log.i("pocketfinancer_llm", "Session cache loaded successfully ($loaded tokens).")
                            } else {
                                android.util.Log.w("pocketfinancer_llm", "Loaded tokens ($loaded) does not match expected size (${prefixTokens.size}). Regenerating...")
                            }
                        }

                        if (!sessionLoaded) {
                            android.util.Log.i("pocketfinancer_llm", "Generating new session cache...")
                            // Prefill-only run
                            nativeCompletion(
                                modelHandle,
                                prefixString,
                                null,
                                0,
                                0.0f,
                                null,
                                false,
                                null
                            )
                            deleteStaleSessions(prefixHash)
                            val saved = saveSession(sessionFile.absolutePath, prefixTokens)
                            android.util.Log.i("pocketfinancer_llm", "Session cache saved: $saved")
                        }

                        thinkPrompt = suffixString + "<think>\n"
                        keepCacheFirstPhase = true
                    }
                }
            }

            // Phase 1: Thinking pass — generate <think>... block
            val thinkResult = nativeCompletion(
                modelHandle,
                thinkPrompt,
                null,              // no grammar during thinking
                thinkingTokens,
                0.0f,             // greedy sampling
                "</think>",        // stop token
                keepCacheFirstPhase,
                callback
            )

            // Check for stop signal
            if (thinkResult.isEmpty() && modelHandle != 0L) {
                return@withContext InferenceResult.Error("Thinking phase produced empty output")
            }

            // Phase 2: Grammar-constrained JSON decode
            // We reuse the existing KV cache that contains the prompt and the generated think block.
            // We only need to append and decode the stop suffix "</think>\n".
            val thinkSuffix = "</think>\n"
            val answer = nativeCompletion(
                modelHandle,
                thinkSuffix,
                grammar,           // GBNF grammar applied here
                answerTokens,
                0.0f,              // greedy sampling for deterministic output
                null,              // no stop token — grammar controls completion
                true,              // keep cache!
                callback
            )

            if (answer.isEmpty()) {
                return@withContext InferenceResult.Error("JSON decode produced empty output")
            }

            // Capture performance data after inference
            val perf = getPerformanceData()

            // The answer should be either "null" or a JSON object
            val trimmed = answer.trim()
            return@withContext if (trimmed == "null") {
                InferenceResult.Null
            } else {
                InferenceResult.Success(trimmed, perf = perf)
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
        params: InferenceParams = InferenceParams(),
        keepCache: Boolean = false,
        callback: TokenCallback? = null
    ): String = withContext(Dispatchers.IO) {
        if (!isLoaded) return@withContext ""
        nativeCompletion(
            modelHandle,
            prompt,
            params.grammar,
            params.maxTokens,
            params.temperature,
            params.stopToken,
            keepCache,
            callback
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
            try {
                System.loadLibrary("pocketfinancer_llm")
            } catch (_: UnsatisfiedLinkError) {
                // Native library not available (e.g., unit tests). The engine
                // will fail at loadModel() time with a descriptive error.
            }
        }
    }

    /**
     * Tokenize text into token IDs.
     */
    fun tokenize(text: String, addSpecial: Boolean = true): IntArray? {
        if (modelHandle == 0L) return null
        return nativeTokenize(modelHandle, text, addSpecial)
    }

    /**
     * Save the current KV cache session state and associated tokens to a file.
     */
    fun saveSession(path: String, tokens: IntArray): Boolean {
        if (modelHandle == 0L) return false
        return nativeSaveSession(modelHandle, path, tokens)
    }

    /**
     * Load/restore the KV cache session state from a file.
     * Returns the number of loaded tokens, or -1 on error.
     */
    fun loadSession(path: String, maxTokens: Int): Int {
        if (modelHandle == 0L) return -1
        val tokensOut = IntArray(maxTokens)
        return nativeLoadSession(modelHandle, path, tokensOut)
    }

    /**
     * Compute SHA-256 hash of a string.
     */
    fun computeSha256(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Get the session file for a given prefix hash.
     */
    fun getSessionFile(hash: String): File {
        val dir = getModelStorageDir()
        return File(dir, "session_$hash.bin")
    }

    /**
     * Delete any stale session files, keeping only the one with the specified hash (if any).
     */
    fun deleteStaleSessions(activeHash: String?) {
        val dir = getModelStorageDir()
        val files = dir.listFiles() ?: return
        for (file in files) {
            if (file.name.startsWith("session_") && file.name.endsWith(".bin")) {
                if (activeHash == null || file.name != "session_$activeHash.bin") {
                    try {
                        val deleted = file.delete()
                        android.util.Log.i("pocketfinancer_llm", "Deleted stale session file: ${file.name} (success=$deleted)")
                    } catch (e: Exception) {
                        android.util.Log.e("pocketfinancer_llm", "Failed to delete stale session: ${file.name}", e)
                    }
                }
            }
        }
    }

    // ── JNI declarations ──────────────────────────────────────────────────

    private external fun nativeLoadModel(path: String, nCtx: Int, nGpuLayers: Int, nThreads: Int): Long
    private external fun nativeCompletion(
        handle: Long,
        prompt: String,
        grammar: String?,
        nPredict: Int,
        temperature: Float,
        stop: String?,
        keepCache: Boolean,
        callback: TokenCallback?
    ): String
    private external fun nativeApplyChatTemplate(
        handle: Long,
        messages: String,
        addAssistantPrefix: Boolean
    ): String
    private external fun nativeGetPerfData(handle: Long): String?
    private external fun nativeStop(handle: Long)
    private external fun nativeUnloadModel(handle: Long)
    private external fun nativeGetModelSize(path: String): Long
    private external fun nativeTokenize(handle: Long, text: String, addSpecial: Boolean): IntArray?
    private external fun nativeSaveSession(handle: Long, path: String, tokens: IntArray): Boolean
    private external fun nativeLoadSession(handle: Long, path: String, tokensOut: IntArray): Int
}
