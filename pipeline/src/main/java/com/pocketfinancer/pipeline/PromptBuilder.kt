package com.pocketfinancer.pipeline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the full extraction prompt using the system prompt, few-shot examples,
 * and the ### EXAMPLES / ### YOUR TASK delimiter structure validated in the
 * SLM evaluation pipeline.
 *
 * Format:
 *   SYSTEM_PROMPT
 *   \n\n### EXAMPLES (already labeled — for reference only, do NOT answer these)\n\n
 *   Sender: <sender>\nSMS: <sms>\nOutput: <answer>\n
 *   ...
 *   \n### YOUR TASK (answer this one SMS only — output JSON or null, nothing else)\n\n
 *   Sender: <sender>\nSMS: <sms>\nOutput:
 */
@Singleton
class PromptBuilder @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val systemPrompt: String by lazy {
        context.assets.open("system_prompt.txt").bufferedReader().use { it.readText() }
    }

    private val fewShotExamples: JSONArray by lazy {
        val text = context.assets.open("few_shot_examples.json").bufferedReader().use { it.readText() }
        JSONArray(text)
    }

    /**
     * Build the full extraction prompt for a given SMS message.
     *
     * @param sender The SMS sender address (e.g. "AX-HDFCBK")
     * @param smsBody The raw SMS body text
     * @return Complete prompt string ready for Qwen3 chat template rendering
     */
    fun buildExtractionPrompt(sender: String, smsBody: String): String {
        val sb = StringBuilder()

        // ── System prompt ──
        sb.append(systemPrompt)

        // ── Few-shot examples ──
        sb.append("\n\n### EXAMPLES (already labeled — for reference only, do NOT answer these)\n\n")
        for (i in 0 until fewShotExamples.length()) {
            val ex = fewShotExamples.getJSONObject(i)
            sb.append("Sender: ${ex.getString("sender")}\n")
            sb.append("SMS: ${ex.getString("sms")}\n")
            sb.append("Output: ${ex.getString("answer")}\n\n")
        }

        // ── Query ──
        sb.append("### YOUR TASK (answer this one SMS only — output JSON or null, nothing else)\n\n")
        sb.append("Sender: $sender\n")
        sb.append("SMS: $smsBody\n")
        sb.append("Output: ")

        return sb.toString()
    }

    /**
     * Build a prompt with the Qwen3 chat template applied.
     *
     * Qwen3 requires chat template rendering with enable_thinking=True for the
     * thinking pass. The Kotlin side wraps the raw prompt in the Qwen3 template
     * format. Since llama.cpp handles chat templates internally, we pass the
     * prompt as a user message and let the template engine render it.
     */
    fun buildChatPrompt(rawPrompt: String, enableThinking: Boolean = true): String {
        // Qwen3 chat template format:
        // <|im_start|>system\n{system}<|im_end|>\n<|im_start|>user\n{prompt}<|im_end|>\n<|im_start|>assistant\n
        // When enable_thinking=True, the template prepends a thinking directive.
        val systemMsg = "You are a helpful financial SMS extraction assistant."
        return buildString {
            append("<|im_start|>system\n")
            append(systemMsg)
            if (enableThinking) {
                append("\n\nPlease think through this step by step inside <think> tags before giving your final answer.")
            }
            append("<|im_end|>\n")
            append("<|im_start|>user\n")
            append(rawPrompt)
            append("<|im_end|>\n")
            append("<|im_start|>assistant\n")
            // The engine appends "<think>\n" in phase 1 and "</think>\n" between phases
        }
    }
}
