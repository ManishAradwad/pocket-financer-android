package com.pocketfinancer.ui.onboarding

import com.pocketfinancer.setup.AdaptiveHistoryScanPolicy
import com.pocketfinancer.setup.SetupImportStatus
import org.junit.Assert.assertEquals
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
                isRunning = true,
                syncMessage = "Analyzing a sender",
                syncLogs = listOf("candidate detail")
            )
        )

        assertEquals(OnboardingStep.COMPLETED, terminal.step)
        assertEquals("Setup finished", terminal.syncMessage)
        assertEquals(emptyList<String>(), terminal.syncLogs)
    }
}
