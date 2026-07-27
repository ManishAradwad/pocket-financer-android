package com.pocketfinancer.setup

import org.junit.Assert.assertEquals
import org.junit.Test

class AdaptiveHistoryScanPolicyTest {
    private val policy = AdaptiveHistoryScanPolicy()

    @Test
    fun `empty discovery widens seven to thirty to ninety`() {
        assertEquals(
            HistoryScanDecision.Widen(30),
            policy.decide(7, providerMessageCount = 0, eligibleCandidateCount = 0)
        )
        assertEquals(
            HistoryScanDecision.Widen(90),
            policy.decide(30, providerMessageCount = 12, eligibleCandidateCount = 0)
        )
        assertEquals(
            HistoryScanDecision.NoEligibleHistory(
                windowDays = 90,
                reason = SetupEmptyReason.EMPTY_INBOX
            ),
            policy.decide(
                90,
                providerMessageCount = 0,
                eligibleCandidateCount = 0,
                inboxHasAnyMessage = false
            )
        )
    }

    @Test
    fun `messages only outside ninety days are not called an empty inbox`() {
        assertEquals(
            HistoryScanDecision.NoEligibleHistory(
                windowDays = 90,
                reason = SetupEmptyReason.NO_ELIGIBLE_WITHIN_90_DAYS
            ),
            policy.decide(
                90,
                providerMessageCount = 0,
                eligibleCandidateCount = 0,
                inboxHasAnyMessage = true
            )
        )
    }

    @Test
    fun `non transaction only inbox remains distinct from empty inbox`() {
        assertEquals(
            HistoryScanDecision.NoEligibleHistory(
                windowDays = 90,
                reason = SetupEmptyReason.FILTERED_OUT
            ),
            policy.decide(
                90,
                providerMessageCount = 42,
                eligibleCandidateCount = 0
            )
        )
    }

    @Test
    fun `older eligible candidate stops widening and enters processing`() {
        assertEquals(
            HistoryScanDecision.Process(windowDays = 30),
            policy.decide(
                30,
                providerMessageCount = 20,
                eligibleCandidateCount = 1
            )
        )
    }

    @Test
    fun `explicit older scan starts beyond verified coverage`() {
        assertEquals(7, policy.firstWindowAfter(null))
        assertEquals(30, policy.firstWindowAfter(7))
        assertEquals(90, policy.firstWindowAfter(30))
        assertEquals(
            policy.allAvailableHistoryDays,
            policy.firstWindowAfter(90)
        )
    }
}
