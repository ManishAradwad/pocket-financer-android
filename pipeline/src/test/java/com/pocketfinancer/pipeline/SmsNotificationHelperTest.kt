package com.pocketfinancer.pipeline

import com.pocketfinancer.data.model.SmsSourceIdentity
import kotlin.test.assertNotEquals
import org.junit.Test

class SmsNotificationHelperTest {

    @Test
    fun `distinct provider candidates sharing sender and timestamp keep distinct notifications`() {
        val firstProviderCandidate = SmsSourceIdentity.androidSms(
            providerMessageId = "101",
            sender = "AX-BANK",
            body = "Rs 100 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )
        val secondProviderCandidate = SmsSourceIdentity.androidSms(
            providerMessageId = "102",
            sender = "AX-BANK",
            body = "Rs 100 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )

        assertNotEquals(
            SmsNotificationHelper.getNotificationId(
                firstProviderCandidate.opaqueCandidateKey
            ),
            SmsNotificationHelper.getNotificationId(
                secondProviderCandidate.opaqueCandidateKey
            )
        )
    }
}
