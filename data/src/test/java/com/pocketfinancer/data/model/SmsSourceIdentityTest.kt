package com.pocketfinancer.data.model

import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.Test

class SmsSourceIdentityTest {
    @Test
    fun `provider sent timestamp and broadcast identity share a canonical fallback`() {
        val broadcast = identity(
            providerId = null,
            body = "Rs 500 debited",
            sourceTimestamp = SENT_AT,
            receivedTimestamp = SENT_AT
        )
        val provider = identity(
            providerId = "123",
            body = "Rs 500 debited",
            sourceTimestamp = SENT_AT,
            receivedTimestamp = RECEIVED_AT
        )
        val legacyReceivedIdentity = identity(
            providerId = null,
            body = "Rs 500 debited",
            sourceTimestamp = RECEIVED_AT,
            receivedTimestamp = RECEIVED_AT
        )

        assertNotEquals(broadcast.messageId, provider.messageId)
        assertEquals(
            broadcast.fallbackFingerprint,
            provider.fallbackFingerprint
        )
        assertEquals(broadcast.opaqueCandidateKey, provider.opaqueCandidateKey)
        assertEquals(
            legacyReceivedIdentity.fallbackFingerprint,
            provider.alternateFingerprint
        )
        assertEquals("123", provider.providerMessageId)
        assertTrue(provider.messageId.startsWith("provider:"))
    }

    @Test
    fun `same sender and timestamp do not collapse different alerts`() {
        val first = identity(providerId = null, body = "Rs 100 debited")
        val second = identity(providerId = null, body = "Rs 200 debited")

        assertNotEquals(
            first.fallbackFingerprint,
            second.fallbackFingerprint
        )
        assertNotEquals(first.opaqueCandidateKey, second.opaqueCandidateKey)
    }

    private fun identity(
        providerId: String?,
        body: String,
        sourceTimestamp: Long = SENT_AT,
        receivedTimestamp: Long = sourceTimestamp
    ) =
        SmsSourceIdentity.androidSms(
            providerMessageId = providerId,
            sender = "AX-HDFCBK",
            body = body,
            sourceTimestamp = sourceTimestamp,
            messageType = 1,
            receivedTimestamp = receivedTimestamp
        )

    private companion object {
        const val SENT_AT = 1_000L
        const val RECEIVED_AT = 1_500L
    }
}
