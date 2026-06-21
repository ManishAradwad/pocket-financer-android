package com.pocketfinancer.pipeline

import org.junit.Test
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
}
