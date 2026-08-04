package com.pocketfinancer.ui.transactions

import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.SyncSmsItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveSyncCardUiModelTest {

    @Test
    fun `idle state does not select or present a sync card`() {
        val sms = sms(status = "pending")
        val state = HomeSyncState(queue = listOf(sms))

        assertNull(state.syncCardItem())
        assertNull(state.toActiveSyncCardUiModel(sms))
    }

    @Test
    fun `running state reports queue position and a plain-language step`() {
        val first = sms(id = "first", status = "synced")
        val active = sms(id = "active", sender = "HDFC Bank", status = "syncing")
        val state = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            queue = listOf(first, active),
            currentIndex = 1,
            currentStageIndex = 2,
            hasThinkingMode = true
        )

        assertSame(active, state.syncCardItem())
        assertEquals(
            ActiveSyncCardUiModel(
                tone = ActiveSyncCardTone.PROCESSING,
                title = "Processing message 2 of 2",
                detail = "From HDFC Bank",
                badge = "LIVE",
                stepLabel = "CURRENT STEP",
                stepValue = "Extracting transaction",
                actionLabel = "View live log",
                stateDescription = "Processing message 2 of 2. From HDFC Bank. Current step: Extracting transaction."
            ),
            state.toActiveSyncCardUiModel(active)
        )
    }

    @Test
    fun `completed state summarizes saved and skipped messages without calling skips failures`() {
        val saved = sms(id = "saved", status = "synced")
        val skipped = sms(id = "skipped", status = "filtered_out")
        val state = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            queue = listOf(saved, skipped)
        )

        assertSame(skipped, state.syncCardItem())
        assertEquals(
            ActiveSyncCardUiModel(
                tone = ActiveSyncCardTone.SUCCESS,
                title = "Sync complete",
                detail = "Queue: 1 saved • 1 skipped",
                badge = "DONE",
                stepLabel = "LATEST RESULT",
                stepValue = "Skipped — no transaction found",
                actionLabel = "View latest log",
                stateDescription = "Sync complete. Queue: 1 saved • 1 skipped. Skipped — no transaction found."
            ),
            state.toActiveSyncCardUiModel(skipped)
        )
    }

    @Test
    fun `completed state prioritizes an error and asks the user to review it`() {
        val failed = sms(id = "failed", status = "error")
        val laterSuccess = sms(id = "later", status = "synced")
        val state = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            queue = listOf(failed, laterSuccess)
        )

        assertSame(failed, state.syncCardItem())
        assertEquals(ActiveSyncCardTone.ISSUE, state.toActiveSyncCardUiModel(failed)?.tone)
        assertEquals("Queue: 1 saved • 1 failed", state.toActiveSyncCardUiModel(failed)?.detail)
        assertEquals(
            "Processing needs attention",
            state.toActiveSyncCardUiModel(failed)?.stepValue
        )
        assertEquals("Review log", state.toActiveSyncCardUiModel(failed)?.actionLabel)
    }

    @Test
    fun `interrupted state is not presented as a successful completion`() {
        val interrupted = sms(status = "syncing")
        val state = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            queue = listOf(interrupted)
        )

        assertSame(interrupted, state.syncCardItem())
        assertEquals(ActiveSyncCardTone.ISSUE, state.toActiveSyncCardUiModel(interrupted)?.tone)
        assertEquals("Queue: 1 incomplete", state.toActiveSyncCardUiModel(interrupted)?.detail)
        assertEquals(
            "Sync ended before processing",
            state.toActiveSyncCardUiModel(interrupted)?.stepValue
        )
    }

    @Test
    fun `running state recovers the syncing item when the current index is invalid`() {
        val pending = sms(id = "pending", status = "pending")
        val active = sms(id = "active", status = "syncing")
        val state = HomeSyncState(
            status = HomeSyncState.Status.SYNCING,
            queue = listOf(pending, active),
            currentIndex = 99
        )

        assertSame(active, state.syncCardItem())
        assertEquals(
            "Processing message 2 of 2",
            state.toActiveSyncCardUiModel(active)?.title
        )
    }

    @Test
    fun `cancelling state remains visible and explains commit drain`() {
        val active = sms(status = "syncing")
        val state = HomeSyncState(
            status = HomeSyncState.Status.CANCELLING,
            cancellationRequested = true,
            queue = listOf(active),
            currentIndex = 0,
            currentStageIndex = 3
        )

        assertSame(active, state.syncCardItem())
        val model = state.toActiveSyncCardUiModel(active)
        assertEquals("Stopping SMS processing", model?.title)
        assertEquals("STOPPING", model?.badge)
        assertEquals("Finishing current save", model?.stepValue)
        assertEquals("View stopping details", model?.actionLabel)
    }

    @Test
    fun `empty ledger still shows active scan and stopping controls`() {
        assertFalse(
            shouldShowTransactionsEmptyState(
                hasTransactions = false,
                hasSyncCard = false,
                syncStatus = HomeSyncState.Status.SCANNING
            )
        )
        assertFalse(
            shouldShowTransactionsEmptyState(
                hasTransactions = false,
                hasSyncCard = false,
                syncStatus = HomeSyncState.Status.CANCELLING
            )
        )
        assertTrue(
            shouldShowTransactionsEmptyState(
                hasTransactions = false,
                hasSyncCard = false,
                syncStatus = HomeSyncState.Status.IDLE
            )
        )
    }

    private fun sms(
        id: String = "sms",
        sender: String = "Bank",
        status: String
    ) = SyncSmsItem(
        id = id,
        sender = sender,
        body = "Account ending 6254 was debited.",
        date = 0L,
        status = status
    )
}
