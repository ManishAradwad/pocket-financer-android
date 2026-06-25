package com.pocketfinancer.data.model

import org.junit.Test
import kotlin.test.assertEquals

class TransactionTypeTest {

    @Test
    fun testFromString_credit() {
        assertEquals(TransactionType.CREDIT, TransactionType.fromString("credit"))
        assertEquals(TransactionType.CREDIT, TransactionType.fromString("CREDIT"))
        assertEquals(TransactionType.CREDIT, TransactionType.fromString("CrEdIt"))
    }

    @Test
    fun testFromString_debit() {
        assertEquals(TransactionType.DEBIT, TransactionType.fromString("debit"))
        assertEquals(TransactionType.DEBIT, TransactionType.fromString("DEBIT"))
        assertEquals(TransactionType.DEBIT, TransactionType.fromString("DeBiT"))
    }

    @Test
    fun testFromString_fallbackToDebit() {
        assertEquals(TransactionType.DEBIT, TransactionType.fromString("unknown"))
        assertEquals(TransactionType.DEBIT, TransactionType.fromString(""))
        assertEquals(TransactionType.DEBIT, TransactionType.fromString(" "))
        assertEquals(TransactionType.DEBIT, TransactionType.fromString("cred"))
    }

    @Test
    fun testTransactionDefaultIsEdited() {
        val transaction = Transaction(
            id = "tx123",
            amount = 100.0,
            merchant = "Test Merchant",
            date = 1624620000000L,
            type = TransactionType.DEBIT,
            accountId = "ac123",
            accountLabel = "HDFC",
            rawMessage = "Rs 100 spent",
            sender = "HDFCBK"
        )
        assertEquals(false, transaction.isEdited)
    }
}
