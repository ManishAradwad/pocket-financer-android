package com.pocketfinancer.pipeline

import com.pocketfinancer.data.model.TransactionType
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ExtractionParserTest {

    private val parser = ExtractionParser()

    // ── Valid JSON outputs ────────────────────────────────────────────────

    @Test
    fun `parse should return ExtractedTransaction for valid debit JSON`() {
        val output = """{"amount": 1500.0, "type": "debit", "account": "A/c XX6254", "counterparty": "Amazon Pay"}"""
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(1500.0, result.amount)
        assertEquals(TransactionType.DEBIT, result.type)
        assertEquals("A/c XX6254", result.account)
        assertEquals("Amazon Pay", result.counterparty)
    }

    @Test
    fun `parse should return ExtractedTransaction for valid credit JSON`() {
        val output = """{"amount": 500.0, "type": "credit", "account": "A/c XX0000", "counterparty": "UPI Ref 12345"}"""
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(500.0, result.amount)
        assertEquals(TransactionType.CREDIT, result.type)
        assertEquals("A/c XX0000", result.account)
        assertEquals("UPI Ref 12345", result.counterparty)
    }

    @Test
    fun `parse should accept output with no counterparty`() {
        val output = """{"amount": 250.0, "type": "debit", "account": "Card XX1234"}"""
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(250.0, result.amount)
        assertNull(result.counterparty)
    }

    @Test
    fun `parse should treat literal string null counterparty as null`() {
        val output = """{"amount": 250.0, "type": "debit", "account": "Card XX1234", "counterparty": "null"}"""
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(250.0, result.amount)
        assertNull(result.counterparty)
    }

    // ── Null (non-financial) ──────────────────────────────────────────────

    @Test
    fun `parse should return null for literal null output`() {
        val result = parser.parse("null")
        assertNull(result)
    }

    @Test
    fun `parse should return null for uppercase null`() {
        val result = parser.parse("NULL")
        assertNull(result)
    }

    // ── Nonnull filter (missing required fields) ──────────────────────────

    @Test
    fun `parse should return null when amount field is null`() {
        val output = """{"amount": null, "type": "debit", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNull(result)
    }

    @Test
    fun `parse should return null when type field is null`() {
        val output = """{"amount": 1500.0, "type": null, "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNull(result)
    }

    @Test
    fun `parse should return null when account field is null`() {
        val output = """{"amount": 1500.0, "type": "debit", "account": null}"""
        val result = parser.parse(output)
        assertNull(result)
    }

    @Test
    fun `parse should return null when amount is string null`() {
        val output = """{"amount": "null", "type": "debit", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNull(result)
    }

    // ── Type coercion ─────────────────────────────────────────────────────

    @Test
    fun `parse should return null for unknown type`() {
        val output = """{"amount": 100.0, "type": "unknown", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNull(result)
    }

    @Test
    fun `parse should accept type with different casing`() {
        val output = """{"amount": 100.0, "type": "DEBIT", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(TransactionType.DEBIT, result.type)
    }

    @Test
    fun `parse should accept type with mixed casing`() {
        val output = """{"amount": 100.0, "type": "Credit", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(TransactionType.CREDIT, result.type)
    }

    // ── Amount coercion ───────────────────────────────────────────────────

    @Test
    fun `parse should coerce amount from integer`() {
        val output = """{"amount": 500, "type": "debit", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(500.0, result.amount)
    }

    @Test
    fun `parse should coerce amount from string with rupees symbol`() {
        val output = """{"amount": "Rs.500", "type": "credit", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(500.0, result.amount)
    }

    @Test
    fun `parse should coerce amount from comma-formatted string`() {
        val output = """{"amount": "1,500", "type": "debit", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(1500.0, result.amount)
    }

    @Test
    fun `parse should coerce amount from string with extra whitespace`() {
        val output = """{"amount": " 500.50 ", "type": "credit", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(500.50, result.amount)
    }

    @Test
    fun `parse should return null for zero amount`() {
        val output = """{"amount": 0, "type": "debit", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNull(result)
    }

    @Test
    fun `parse should return null for negative amount`() {
        val output = """{"amount": -50.0, "type": "debit", "account": "A/c XX6254"}"""
        val result = parser.parse(output)
        assertNull(result)
    }

    // ── Malformed output ──────────────────────────────────────────────────

    @Test
    fun `parse should return null for empty string`() {
        val result = parser.parse("")
        assertNull(result)
    }

    @Test
    fun `parse should return null for unparseable text`() {
        val result = parser.parse("The model produced this output without JSON")
        assertNull(result)
    }

    @Test
    fun `parse should return null for broken JSON`() {
        val result = parser.parse("""{"amount": 100, "type": "debit", """)
        assertNull(result)
    }

    // ── Thinking block stripping ──────────────────────────────────────────

    @Test
    fun `parse should strip thinking blocks before parsing`() {
        val output = """
            <think>
            The user received a transaction of 1500 rupees
            This is a debit to their account
            </think>
            {"amount": 1500.0, "type": "debit", "account": "A/c XX6254", "counterparty": "Amazon"}
        """.trimIndent()
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(1500.0, result.amount)
        assertEquals(TransactionType.DEBIT, result.type)
    }

    @Test
    fun `parse should handle multiple thinking blocks`() {
        val output = """
            <think>
            First reasoning pass
            </think>
            <think>
            Second reasoning pass
            </think>
            {"amount": 500.0, "type": "credit", "account": "A/c XX0000"}
        """.trimIndent()
        val result = parser.parse(output)
        assertNotNull(result)
        assertEquals(500.0, result.amount)
    }

    // ── normalizeAccount utility ──────────────────────────────────────────

    @Test
    fun `normalizeAccount should extract last 4 digits of card`() {
        val result = ExtractionParser.normalizeAccount("ICICI Bank Card XX5678")
        assertNotNull(result)
        assertEquals("card", result.first)
        assertEquals("5678", result.second)
    }

    @Test
    fun `normalizeAccount should extract last 4 digits of account`() {
        val result = ExtractionParser.normalizeAccount("A/c XX6254")
        assertNotNull(result)
        assertEquals("account", result.first)
        assertEquals("6254", result.second)
    }

    @Test
    fun `normalizeAccount should return null for blank`() {
        assertNull(ExtractionParser.normalizeAccount(""))
        assertNull(ExtractionParser.normalizeAccount("  "))
        assertNull(ExtractionParser.normalizeAccount(null))
    }

    @Test
    fun `normalizeAccount should return null for text without digits`() {
        val result = ExtractionParser.normalizeAccount("INVALID")
        assertNull(result)
    }
}
