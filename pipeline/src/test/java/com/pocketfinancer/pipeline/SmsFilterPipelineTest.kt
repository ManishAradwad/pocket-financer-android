package com.pocketfinancer.pipeline

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SmsFilterPipelineTest {

    private val filterPipeline = SmsFilterPipeline()

    @Test
    fun `should accept valid credit transactions`() {
        val sender = "AX-HDFCBK"
        val bodies = listOf(
            "HDFC Bank: Rs.500.00 credited to a/c XXXXXX0000 on 01-01-20",
            "Dear Customer, A/c No. XX1234 has credit for Rs 1,500.00 by transfer",
            "₹500.00 received in A/c **4321",
            "Amt received Rs 200.00 in a/c XX1234",
            "INR 1000.00 deposited into A/c XXXXXX9999"
        )
        for (body in bodies) {
            assertTrue(
                filterPipeline.isTransactional(sender, body),
                "Failed to accept valid credit transaction: $body"
            )
        }
    }

    @Test
    fun `should accept valid debit transactions`() {
        val sender = "VM-ICICIBK"
        val bodies = listOf(
            "ICICI Bank: Rs.2,500.00 debited from a/c XXXXXX1111 on 05-05-21",
            "Spent Rs.120 on Card ending XX6789",
            "Txn of Rs.350.00 paid on Card 0816",
            "A/C XX123 has a debit by transfer of Rs.400",
            "Auto-debit of Rs 299.00 from a/c **7890",
            "Debit in a/c **5678 of Rs.1200",
            "Rs.99.00 spent on Card x3456"
        )
        for (body in bodies) {
            assertTrue(
                filterPipeline.isTransactional(sender, body),
                "Failed to accept valid debit transaction: $body"
            )
        }
    }

    @Test
    fun `should reject personal mobile numbers`() {
        val senders = listOf(
            "+919999999999",
            "9876543210",
            "+12345678901",
            "09999999999"
        )
        val body = "HDFC Bank: Rs.500.00 credited to a/c XXXXXX0000"
        for (sender in senders) {
            assertFalse(
                filterPipeline.isTransactional(sender, body),
                "Failed to reject personal sender: $sender"
            )
        }
    }

    @Test
    fun `should accept numeric shortcodes and non-mobile alphanumeric senders`() {
        val senders = listOf(
            "567676",      // shortcode
            "AD-HDFCBK",   // typical brand
            "ICICIBK",     // typical brand
            "AX-123456"    // shortcode-brand mix
        )
        val body = "Rs.500.00 credited to a/c XXXXXX0000"
        for (sender in senders) {
            assertTrue(
                filterPipeline.isTransactional(sender, body),
                "Failed to accept valid sender: $sender"
            )
        }
    }

    @Test
    fun `should reject messages without amount`() {
        val sender = "AX-HDFCBK"
        val body = "Your transaction on a/c XXXXXX1111 was processed successfully."
        assertFalse(filterPipeline.isTransactional(sender, body))
    }

    @Test
    fun `should reject messages without masked account or card number`() {
        val sender = "AX-HDFCBK"
        val body = "Dear customer, Rs.500.00 has been debited from your online wallet."
        assertFalse(filterPipeline.isTransactional(sender, body))
    }

    @Test
    fun `should reject messages without transaction verbs`() {
        val sender = "AX-HDFCBK"
        val body = "Check out our offers on your Card XX1234. Get Rs.100 cash back."
        // "cash back" or "Get Rs.100" or "offers" does not contain a verb like debited, credited, spent, etc.
        assertFalse(filterPipeline.isTransactional(sender, body))
    }

    @Test
    fun `should reject OTP and verification codes`() {
        val sender = "AX-HDFCBK"
        val bodies = listOf(
            "OTP of Rs.5000 on Card XX1111 is 876123. Do not share.",
            "Verification code for your transaction of Rs 500 on card x1234 is 5621",
            "123456 is one-time password for Rs.120.00 spent on Card x5678"
        )
        for (body in bodies) {
            assertFalse(
                filterPipeline.isTransactional(sender, body),
                "Failed to reject OTP message: $body"
            )
        }
    }

    @Test
    fun `should reject collect or mandate requests`() {
        val sender = "AX-HDFCBK"
        val bodies = listOf(
            "UPI collect request of Rs.200 received from user@bank on a/c XX1234",
            "Payment request from you of Rs.500 on card x1234",
            "mandate request created for Rs.1200 on a/c XXXXXX5678",
            "user has requested money Rs 500 on a/c XX0000"
        )
        for (body in bodies) {
            assertFalse(
                filterPipeline.isTransactional(sender, body),
                "Failed to reject collect/mandate request: $body"
            )
        }
    }

    @Test
    fun `should correctly populate FilterResult logs on success`() {
        val sender = "AX-HDFCBK"
        val body = "Rs.500.00 credited to a/c XXXXXX0000"
        val result = filterPipeline.filterWithDetails(sender, body)
        assertTrue(result.isTransactional)
        assertEquals(6, result.logs.size)
        assertTrue(result.logs[0].contains("Stage 0 (Sender Check)"))
        assertTrue(result.logs[1].contains("Stage 1 (Amount Check)"))
        assertTrue(result.logs[2].contains("Stage 2 (Account/Card Check)"))
        assertTrue(result.logs[3].contains("Stage 3 (Verb Check)"))
        assertTrue(result.logs[4].contains("Stage 4 (OTP Exclusion)"))
        assertTrue(result.logs[5].contains("Stage 5 (Collect Request Exclusion)"))
        assertTrue(result.logs.all { it.contains("PASSED") })
    }

    @Test
    fun `should halt logging and fail early at Stage 0`() {
        val result = filterPipeline.filterWithDetails("9876543210", "Rs.500.00 credited to a/c XXXXXX0000")
        assertFalse(result.isTransactional)
        assertEquals(1, result.logs.size)
        assertTrue(result.logs[0].contains("Stage 0 (Sender Check): sender='9876543210', isMobile=true -> REJECTED"))
    }

    @Test
    fun `should halt logging and fail early at Stage 1`() {
        val result = filterPipeline.filterWithDetails("AX-HDFCBK", "No money mention in account XXXXXX0000 credited")
        assertFalse(result.isTransactional)
        assertEquals(2, result.logs.size)
        assertTrue(result.logs[0].contains("PASSED"))
        assertTrue(result.logs[1].contains("Stage 1 (Amount Check): hasAmount=false -> REJECTED"))
    }

    @Test
    fun `should handle empty, blank, or extremely long senders and bodies`() {
        // empty sender (does not match mobile number regex, so it passes Stage 0 and is processed normally)
        assertTrue(filterPipeline.isTransactional("", "Rs.500.00 credited to a/c XXXXXX0000"))
        // blank sender (passes Stage 0 and is processed normally)
        assertTrue(filterPipeline.isTransactional("   ", "Rs.500.00 credited to a/c XXXXXX0000"))
        // empty body
        assertFalse(filterPipeline.isTransactional("AX-HDFCBK", ""))
        // blank body
        assertFalse(filterPipeline.isTransactional("AX-HDFCBK", "    "))
        // extremely long body
        val longBody = "Rs.500.00 credited to a/c XXXXXX0000 " + "a".repeat(10000)
        assertTrue(filterPipeline.isTransactional("AX-HDFCBK", longBody))
    }

    @Test
    fun `should accept UPI and other specific action verbs`() {
        val bodies = listOf(
            "Txn of Rs.100.00 via UPI in a/c XX1234",
            "Redemption payout of Rs 5000 to a/c XXXXXX9876",
            "money transfer of Rs.1000 from a/c XXXXXX1111",
            "Amt sent Rs 250 from a/c XX1234",
            "Amt received Rs 350 to a/c XX5678"
        )
        for (body in bodies) {
            assertTrue(filterPipeline.isTransactional("AX-HDFCBK", body), "Failed on: $body")
        }
    }

    @Test
    fun `should accept different card formats`() {
        val bodies = listOf(
            "Rs.500 debited on card ending XX1234",
            "Rs.500 debited on card 4567",
            "Rs.500 debited on card no X1234",
            "Rs.500 debited on card ending 9999"
        )
        for (body in bodies) {
            assertTrue(filterPipeline.isTransactional("AX-HDFCBK", body), "Failed on card: $body")
        }
    }
}
