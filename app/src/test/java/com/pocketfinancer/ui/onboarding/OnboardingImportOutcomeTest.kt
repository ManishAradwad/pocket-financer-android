package com.pocketfinancer.ui.onboarding

import com.pocketfinancer.setup.AdaptiveHistoryScanPolicy
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupPauseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingImportOutcomeTest {

    @Test
    fun `concurrent duplicate is ready but is not counted as newly saved`() {
        assertEquals(
            SetupImportStatus.READY,
            setupTerminalStatus(
                savedCount = 0,
                failedCount = 0,
                concurrentDuplicateCount = 1
            )
        )
    }

    @Test
    fun `resume with a transaction already saved terminates ready`() {
        assertEquals(
            SetupImportStatus.READY,
            setupTerminalStatus(
                savedCount = 1,
                failedCount = 0,
                concurrentDuplicateCount = 0
            )
        )
    }

    @Test
    fun `rejections without saves remain ready for next alert`() {
        assertEquals(
            SetupImportStatus.READY_NO_HISTORY,
            setupTerminalStatus(
                savedCount = 0,
                failedCount = 0,
                concurrentDuplicateCount = 0
            )
        )
    }

    @Test
    fun `failure remains actionable even when another item saved`() {
        assertEquals(
            SetupImportStatus.FAILED,
            setupTerminalStatus(
                savedCount = 1,
                failedCount = 1,
                concurrentDuplicateCount = 0
            )
        )
    }

    @Test
    fun `restart rechecks the verified window so in-memory candidates are not skipped`() {
        assertEquals(
            30,
            initialHistoryWindowDays(
                policy = AdaptiveHistoryScanPolicy(),
                coveredWindowDays = 30,
                resumeWindowDays = 30
            )
        )
    }

    @Test
    fun `restart reuses the exact provider upper bound for partial import`() {
        val state = SetupImportState(
            coverageStartMillis = 1_000L,
            coverageEndMillis = 2_000L,
            coverageWindowDays = 30,
            lastSuccessfulScanMillis = 2_100L
        )

        assertEquals(
            2_000L,
            historicalScanProviderMaxDate(
                state = state,
                resumeWindowDays = 30,
                nowMillis = 9_000L
            )
        )
        assertEquals(
            9_000L,
            historicalScanProviderMaxDate(
                state = state,
                resumeWindowDays = null,
                nowMillis = 9_000L
            )
        )
    }

    @Test
    fun `restart during adaptive widening reuses the in-flight provider bound`() {
        val state = SetupImportState(
            coverageStartMillis = 1_000L,
            coverageEndMillis = 2_000L,
            coverageWindowDays = 7,
            activeScanWindowDays = 30,
            activeScanProviderMaxDateMillis = 2_500L,
            lastSuccessfulScanMillis = 2_100L
        )

        assertEquals(
            2_500L,
            historicalScanProviderMaxDate(
                state = state,
                resumeWindowDays = 30,
                nowMillis = 9_000L
            )
        )
        assertEquals(
            9_000L,
            historicalScanProviderMaxDate(
                state = state,
                resumeWindowDays = null,
                nowMillis = 9_000L
            )
        )
    }

    @Test
    fun `deliberate older scan advances beyond verified coverage`() {
        assertEquals(
            90,
            initialHistoryWindowDays(
                policy = AdaptiveHistoryScanPolicy(),
                coveredWindowDays = 30,
                resumeWindowDays = null
            )
        )
        assertEquals(
            36_500,
            initialHistoryWindowDays(
                policy = AdaptiveHistoryScanPolicy(),
                coveredWindowDays = 90,
                resumeWindowDays = null
            )
        )
    }

    @Test
    fun `terminal initial import drops transient candidate diagnostics`() {
        val terminal = scrubCompletedOnboardingState(
            OnboardingSyncManager.OnboardingSyncState(
                runId = "finished-run",
                isRunning = true,
                isCancelling = true,
                isCancellationAllowed = true,
                isPreparingHistoricalModel = true,
                syncMessage = "Analyzing a sender",
                syncLogs = listOf("candidate detail")
            )
        )

        assertEquals(OnboardingStep.COMPLETED, terminal.step)
        assertEquals("Setup finished", terminal.syncMessage)
        assertEquals(emptyList<String>(), terminal.syncLogs)
        assertEquals(null, terminal.runId)
        assertEquals(false, terminal.isCancelling)
        assertEquals(false, terminal.isCancellationAllowed)
        assertEquals(false, terminal.isPreparingHistoricalModel)
    }

    @Test
    fun `user pause preserves committed counts coverage and active resume bound`() {
        val active = SetupImportState(
            status = SetupImportStatus.PROCESSING,
            coverageStartMillis = 1_000L,
            coverageEndMillis = 2_000L,
            coverageWindowDays = 30,
            activeScanWindowDays = 90,
            activeScanProviderMaxDateMillis = 2_500L,
            providerMessageCount = 48,
            eligibleCandidateCount = 6,
            processedCount = 3,
            savedCount = 2,
            rejectedCount = 1,
            failedCount = 0,
            lastSuccessfulScanMillis = 2_100L,
            modelDownloadConfirmed = true,
            modelPrepared = true
        )

        val paused = pauseHistoricalImportForUser(active)
        val repeated = pauseHistoricalImportForUser(paused)

        assertEquals(SetupImportStatus.PAUSED, paused.status)
        assertEquals(SetupPauseReason.USER_REQUESTED, paused.pauseReason)
        assertEquals(1_000L, paused.coverageStartMillis)
        assertEquals(2_000L, paused.coverageEndMillis)
        assertEquals(30, paused.coverageWindowDays)
        assertEquals(90, paused.activeScanWindowDays)
        assertEquals(2_500L, paused.activeScanProviderMaxDateMillis)
        assertEquals(48, paused.providerMessageCount)
        assertEquals(6, paused.eligibleCandidateCount)
        assertEquals(3, paused.processedCount)
        assertEquals(2, paused.savedCount)
        assertEquals(1, paused.rejectedCount)
        assertTrue(paused.actionableError!!.message.contains("Completed saves"))
        assertTrue(paused.actionableError!!.message.contains("rediscover"))
        assertSame(paused, repeated)
    }

    @Test
    fun `user pause never overwrites permission loss`() {
        val permissionNeeded = SetupImportState(
            status = SetupImportStatus.PERMISSION_NEEDED,
            pauseReason = SetupPauseReason.PERMISSION_REVOKED,
            activeScanWindowDays = 90,
            savedCount = 2
        )

        assertSame(
            permissionNeeded,
            pauseHistoricalImportForUser(permissionNeeded)
        )
    }

    @Test
    fun `persistence failure fallback is paused and truthfully actionable`() {
        val fallback = historicalStopPersistenceFallback(
            SetupImportState(
                status = SetupImportStatus.PROCESSING,
                processedCount = 3,
                savedCount = 2
            )
        )

        assertEquals(SetupImportStatus.PAUSED, fallback.status)
        assertEquals(SetupPauseReason.USER_REQUESTED, fallback.pauseReason)
        assertEquals(3, fallback.processedCount)
        assertEquals(2, fallback.savedCount)
        assertEquals(
            "SMS_STOP_STATE_NOT_SAVED",
            fallback.actionableError?.code
        )
    }
}
