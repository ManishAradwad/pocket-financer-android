package com.pocketfinancer.pipeline

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PromptBuilderTest {

    private lateinit var mockContext: Context
    private lateinit var promptBuilder: PromptBuilder

    @Before
    fun setUp() {
        mockContext = mockk()

        // Mock system_prompt.txt
        every { mockContext.assets.open("system_prompt.txt") } returns """
            You are a financial SMS extraction assistant.
            Extract transaction details from Indian bank SMS messages.
            Return JSON or null.
        """.trimIndent().byteInputStream()

        // Mock few_shot_examples.json
        every { mockContext.assets.open("few_shot_examples.json") } returns """
            [
                {
                    "sender": "AX-HDFCBK",
                    "sms": "HDFC Bank: Rs.500.00 credited to a/c XX0000",
                    "answer": "{\"amount\": 500, \"type\": \"credit\", \"account\": \"A/c XX0000\"}"
                },
                {
                    "sender": "AX-SBINB",
                    "sms": "SBI Alert: Rs.1500 debited from a/c XX6254",
                    "answer": "{\"amount\": 1500, \"type\": \"debit\", \"account\": \"A/c XX6254\"}"
                }
            ]
        """.trimIndent().byteInputStream()

        promptBuilder = PromptBuilder(mockContext)
    }

    // ── buildExtractionPrompt ─────────────────────────────────────────

    @Test
    fun `buildExtractionPrompt should include system prompt`() {
        val prompt = promptBuilder.buildExtractionPrompt("AX-HDFCBK", "Test SMS body")
        assertTrue(prompt.startsWith("You are a financial SMS extraction assistant."))
    }

    @Test
    fun `buildExtractionPrompt should include EXAMPLES section`() {
        val prompt = promptBuilder.buildExtractionPrompt("AX-HDFCBK", "Test SMS body")
        assertTrue(prompt.contains("### EXAMPLES"))
    }

    @Test
    fun `buildExtractionPrompt should include all few-shot examples`() {
        val prompt = promptBuilder.buildExtractionPrompt("AX-HDFCBK", "Test SMS body")
        // Both examples should be present
        assertTrue(prompt.contains("AX-HDFCBK"))
        assertTrue(prompt.contains("AX-SBINB"))
        assertTrue(prompt.contains("credited"))
        assertTrue(prompt.contains("debited"))
    }

    @Test
    fun `buildExtractionPrompt should include YOUR TASK section`() {
        val prompt = promptBuilder.buildExtractionPrompt("AX-HDFCBK", "Test SMS body")
        assertTrue(prompt.contains("### YOUR TASK"))
    }

    @Test
    fun `buildExtractionPrompt should include sender and body`() {
        val prompt = promptBuilder.buildExtractionPrompt("AX-ICICIB", "Rs.2000 credited to a/c XX1234")
        assertTrue(prompt.contains("AX-ICICIB"))
        assertTrue(prompt.contains("Rs.2000 credited to a/c XX1234"))
    }

    @Test
    fun `buildExtractionPrompt should end with Output prefix`() {
        val prompt = promptBuilder.buildExtractionPrompt("AX-HDFCBK", "Test body")
        assertTrue(prompt.endsWith("Output: "))
    }

    @Test
    fun `buildExtractionPrompt should maintain correct section order`() {
        val prompt = promptBuilder.buildExtractionPrompt("AX-HDFCBK", "Test body")
        val systemIdx = prompt.indexOf("You are a financial")
        val examplesIdx = prompt.indexOf("### EXAMPLES")
        val taskIdx = prompt.indexOf("### YOUR TASK")
        val outputIdx = prompt.lastIndexOf("Output: ")

        assertTrue(systemIdx < examplesIdx, "System prompt should come before examples")
        assertTrue(examplesIdx < taskIdx, "Examples should come before YOUR TASK")
        assertTrue(taskIdx < outputIdx, "YOUR TASK should come before Output:")
    }

    // ── buildChatPrompt (fallback manual template) ────────────────────

    @Test
    fun `buildChatPrompt should wrap in Qwen3 format`() {
        val result = promptBuilder.buildChatPrompt("Sender: AX-TEST\nSMS: Test\nOutput:", enableThinking = true)
        assertTrue(result.startsWith("<|im_start|>system\n"))
        assertTrue(result.contains("<|im_end|>"))
        assertTrue(result.contains("<|im_start|>user\n"))
        assertTrue(result.contains("<|im_start|>assistant\n"))
    }

    @Test
    fun `buildChatPrompt should add thinking directive when enabled`() {
        val result = promptBuilder.buildChatPrompt("Test prompt", enableThinking = true)
        assertTrue(result.contains("<think>"))
    }

    @Test
    fun `buildChatPrompt should not add thinking directive when disabled`() {
        val result = promptBuilder.buildChatPrompt("Test prompt", enableThinking = false)
        assertTrue(!result.contains("<think>"))
    }

    @Test
    fun `buildChatPrompt should include the raw prompt`() {
        val result = promptBuilder.buildChatPrompt("Sender: AX-TEST\nSMS: Rs.500\nOutput:", enableThinking = false)
        assertTrue(result.contains("Sender: AX-TEST\nSMS: Rs.500\nOutput:"))
    }

    @Test
    fun `getStaticPrefix should return non-empty prompt containing system prompt and examples`() {
        val prefix = promptBuilder.getStaticPrefix()
        assertNotNull(prefix)
        assertTrue(prefix.isNotEmpty())
        assertTrue(prefix.contains("You are a financial SMS extraction assistant."))
        assertTrue(prefix.contains("### EXAMPLES"))
        assertTrue(prefix.contains("AX-HDFCBK"))
    }

    @Test
    fun `getStaticPrefix should be stable across multiple calls`() {
        val prefix1 = promptBuilder.getStaticPrefix()
        val prefix2 = promptBuilder.getStaticPrefix()
        assertEquals(prefix1, prefix2)
    }
}
