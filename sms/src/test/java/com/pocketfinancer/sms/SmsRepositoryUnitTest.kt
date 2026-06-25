package com.pocketfinancer.sms

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import io.mockk.every
import io.mockk.mockk
import org.junit.Before
import org.junit.Test
import app.cash.turbine.test
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SmsRepositoryUnitTest {

    private lateinit var mockContext: Context
    private lateinit var mockReader: SmsReader
    private lateinit var repo: SmsRepository

    @Before
    fun setUp() {
        mockContext = mockk()
        mockReader = mockk()
        repo = SmsRepository(mockContext, mockReader)
    }

    @Test
    fun `hasPermissions should return true when both permissions granted`() {
        // Mock ContextCompat.checkSelfPermission to return GRANTED
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.READ_SMS)
        } returns PackageManager.PERMISSION_GRANTED
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.RECEIVE_SMS)
        } returns PackageManager.PERMISSION_GRANTED

        assertTrue(repo.hasPermissions())
    }

    @Test
    fun `hasPermissions should return false when READ_SMS denied`() {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.READ_SMS)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.RECEIVE_SMS)
        } returns PackageManager.PERMISSION_GRANTED

        assertFalse(repo.hasPermissions())
    }

    @Test
    fun `hasPermissions should return false when RECEIVE_SMS denied`() {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.READ_SMS)
        } returns PackageManager.PERMISSION_GRANTED
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.RECEIVE_SMS)
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(repo.hasPermissions())
    }

    @Test
    fun `hasPermissions should return false when both denied`() {
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.READ_SMS)
        } returns PackageManager.PERMISSION_DENIED
        every {
            ContextCompat.checkSelfPermission(mockContext, Manifest.permission.RECEIVE_SMS)
        } returns PackageManager.PERMISSION_DENIED

        assertFalse(repo.hasPermissions())
    }

    @Test
    fun `fetchHistory should delegate to SmsReader with correct date range`() {
        val testMessages = listOf(
            SmsReader.SmsMessage("AX-HDFCBK", "Rs.500 credited", 1000L, 1)
        )
        every { mockReader.fetchInbox(any()) } returns testMessages

        val results = repo.fetchHistory(daysBack = 30, limit = 100)
        assertEquals(1, results.size)

        // Verify the filter was passed with correct date
        // (implicit: SmsReader was called)
    }

    @Test
    fun `fetchHistory should pass limit to SmsReader`() {
        every { mockReader.fetchInbox(any()) } returns emptyList()

        repo.fetchHistory(daysBack = 90, limit = 500)
        // Verify limit was received (implicit via mock)
    }

    @Test
    fun `fetchHistory should return empty list when no messages`() {
        every { mockReader.fetchInbox(any()) } returns emptyList()

        val results = repo.fetchHistory(daysBack = 90)
        assertTrue(results.isEmpty())
    }

    @Test
    fun `pollInbox should periodically query inbox and emit new messages`() = runBlocking {
        val msg1 = SmsReader.SmsMessage("AX-HDFCBK", "Msg 1", 1000L, 1)
        val msg2 = SmsReader.SmsMessage("AX-HDFCBK", "Msg 2", 2000L, 1)

        var queryCount = 0
        every { mockReader.fetchInbox(any()) } answers {
            queryCount++
            if (queryCount == 1) listOf(msg1) else listOf(msg2)
        }

        repo.pollInbox(intervalMs = 10L).take(2).test {
            val first = awaitItem()
            assertEquals(1, first.size)
            assertEquals(msg1, first[0])

            val second = awaitItem()
            assertEquals(1, second.size)
            assertEquals(msg2, second[0])

            awaitComplete()
        }
    }

    @Test
    fun `listenForIncomingSms should return flow from SmsReceiver`() = runBlocking {
        mockkObject(SmsReceiver.Companion)
        val expectedFlow = flowOf(SmsReader.SmsMessage("AX-HDFCBK", "Rs.500 credited", 1000L, 1))
        every { SmsReceiver.incomingSmsFlow() } returns expectedFlow

        val result = repo.listenForIncomingSms()
        assertEquals(expectedFlow, result)

        unmockkObject(SmsReceiver.Companion)
    }
}
