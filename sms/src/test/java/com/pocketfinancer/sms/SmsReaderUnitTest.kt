package com.pocketfinancer.sms

import android.content.ContentResolver
import android.content.Context
import android.database.MatrixCursor
import android.net.Uri
import android.provider.Telephony
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SmsReaderUnitTest {

    private lateinit var mockContext: Context
    private lateinit var mockContentResolver: ContentResolver
    private lateinit var reader: SmsReader

    @Before
    fun setUp() {
        mockContext = mockk()
        mockContentResolver = mockk()
        every { mockContext.contentResolver } returns mockContentResolver
        reader = SmsReader(mockContext)
    }

    @Test
    fun `fetchInbox should query correct URI`() {
        val cursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))
        cursor.addRow(arrayOf(1, "AX-HDFCBK", "Rs.500 credited", 1000L, 1))

        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                any(),
                any(),
                any(),
                any()
            )
        } returns cursor

        val results = reader.fetchInbox()
        assertEquals(1, results.size)
    }

    @Test
    fun `fetchInbox should parse cursor correctly`() {
        val cursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))
        cursor.addRow(arrayOf(1, "AX-HDFCBK", "Rs.500 credited to a/c XX0000", 2000L, 1))

        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                any(), any(), any(), any()
            )
        } returns cursor

        val results = reader.fetchInbox()
        assertEquals(1, results.size)
        assertEquals("AX-HDFCBK", results[0].address)
        assertEquals("Rs.500 credited to a/c XX0000", results[0].body)
        assertEquals(2000L, results[0].date)
        assertEquals(1, results[0].type)
    }

    @Test
    fun `fetchInbox should handle empty cursor`() {
        val cursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))

        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                any(), any(), any(), any()
            )
        } returns cursor

        val results = reader.fetchInbox()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `fetchInbox should handle null cursor`() {
        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                any(), any(), any(), any()
            )
        } returns null

        val results = reader.fetchInbox()
        assertTrue(results.isEmpty())
    }

    @Test
    fun `fetchInbox should respect limit`() {
        val cursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))
        for (i in 1..10) {
            cursor.addRow(arrayOf(i, "AX-BANK", "SMS $i", i * 1000L, 1))
        }

        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                any(), any(), any(), any()
            )
        } returns cursor

        val results = reader.fetchInbox(SmsReader.SmsFilter(limit = 3))
        assertEquals(3, results.size)
    }

    @Test
    fun `fetchInbox should filter by address pattern`() {
        val cursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))
        cursor.addRow(arrayOf(1, "AX-HDFCBK", "HDFC SMS", 1000L, 1))
        cursor.addRow(arrayOf(2, "AX-SBINB", "SBI SMS", 2000L, 1))
        cursor.addRow(arrayOf(3, "AX-ICICIB", "ICICI SMS", 3000L, 1))

        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                any(), any(), any(), any()
            )
        } returns cursor

        val results = reader.fetchInbox(SmsReader.SmsFilter(addressPattern = "HDFC", limit = 10))
        assertEquals(1, results.size)
        assertEquals("AX-HDFCBK", results[0].address)
    }

    @Test
    fun `fetchInbox should use correct projection`() {
        val cursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))

        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                arrayOf("_id", "address", "body", "date", "type"),
                any(), any(), any()
            )
        } returns cursor

        reader.fetchInbox()
        // Verify projection was passed correctly
        // (the matcher is implicit via the every {} setup)
    }

    @Test
    fun `fetchInbox should use date-based selection args`() {
        val cursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))

        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                any(),
                "date >= ? AND date <= ?",
                any(),
                any()
            )
        } returns cursor

        reader.fetchInbox(SmsReader.SmsFilter(minDate = 1000L, maxDate = 5000L))
    }

    @Test
    fun `fetchInbox should sort by date descending`() {
        val cursor = MatrixCursor(arrayOf("_id", "address", "body", "date", "type"))

        every {
            mockContentResolver.query(
                Uri.parse("content://sms/inbox"),
                any(), any(), any(),
                "date DESC"
            )
        } returns cursor

        reader.fetchInbox()
    }
}
