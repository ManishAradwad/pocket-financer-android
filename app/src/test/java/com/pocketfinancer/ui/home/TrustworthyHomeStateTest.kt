package com.pocketfinancer.ui.home

import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.setup.SetupActionableError
import com.pocketfinancer.setup.SetupEmptyReason
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.reconcileSetupModelAvailability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrustworthyHomeStateTest {

    @Test
    fun `manual sync rejects delayed starts after erase or before shell unlock`() {
        assertTrue(
            manualSyncStartAllowed(
                shellUnlocked = true,
                generationIsCurrent = true
            )
        )
        assertFalse(
            manualSyncStartAllowed(
                shellUnlocked = false,
                generationIsCurrent = true
            )
        )
        assertFalse(
            manualSyncStartAllowed(
                shellUnlocked = true,
                generationIsCurrent = false
            )
        )
    }

    @Test
    fun `manual recent scan cannot replace resumable import state`() {
        assertFalse(
            manualRecentSyncAvailable(SetupImportStatus.PAUSED)
        )
        assertFalse(
            manualRecentSyncAvailable(SetupImportStatus.FAILED)
        )
        assertFalse(
            manualRecentSyncAvailable(SetupImportStatus.NOT_STARTED)
        )
        assertTrue(
            manualRecentSyncAvailable(SetupImportStatus.READY)
        )
        assertTrue(
            manualRecentSyncAvailable(SetupImportStatus.READY_NO_HISTORY)
        )
    }

    @Test
    fun `first run keeps the confirmed onboarding artifact on high end devices`() {
        val largerHardwareRecommendation = SlmTier.ALL_TIERS.last {
            it != SlmTier.DEFAULT_ONBOARDING_SLM
        }

        assertEquals(
            SlmTier.DEFAULT_ONBOARDING_SLM,
            setupTierForPreparation(
                modelPrepared = false,
                resolvedActiveTier = largerHardwareRecommendation
            )
        )
        assertEquals(
            largerHardwareRecommendation,
            setupTierForPreparation(
                modelPrepared = true,
                resolvedActiveTier = largerHardwareRecommendation
            )
        )
    }

    @Test
    fun `legacy prepared flag is revoked when its model artifact is missing`() {
        val legacy = SetupImportState(
            status = SetupImportStatus.READY,
            coverageStartMillis = 100,
            coverageEndMillis = 200,
            coverageWindowDays = 90,
            lastSuccessfulScanMillis = 200,
            modelDownloadConfirmed = true,
            modelPrepared = true
        )

        val reconciled = reconcileSetupModelAvailability(
            state = legacy,
            hasPublishedModel = false
        )
        val card = setupImportCardModel(reconciled)

        assertFalse(reconciled.modelPrepared)
        assertFalse(reconciled.modelDownloadConfirmed)
        assertEquals(90, reconciled.coverageWindowDays)
        assertEquals(SetupCardAction.PREPARE_MODEL, card.primaryAction)
        assertTrue(card.body.contains("starts only after you confirm"))
        assertTrue(card.body.contains("verified SMS coverage is preserved"))
    }

    @Test
    fun `published prepared model keeps explicit setup state`() {
        val prepared = SetupImportState(
            status = SetupImportStatus.READY,
            modelDownloadConfirmed = true,
            modelPrepared = true
        )

        assertEquals(
            prepared,
            reconcileSetupModelAvailability(
                state = prepared,
                hasPublishedModel = true
            )
        )
    }

    @Test
    fun `successful recent scan is durable evidence separate from history`() {
        val historical = SetupImportState(
            status = SetupImportStatus.READY,
            coverageStartMillis = 100,
            coverageEndMillis = 200,
            coverageWindowDays = 90,
            lastSuccessfulScanMillis = 200,
            actionableError = SetupActionableError(
                code = "RECENT_SMS_SCAN_FAILED",
                message = "Old failure",
                actionLabel = "Retry"
            )
        )

        val recent = historical.withSuccessfulRecentScan(
            scanWindowDays = 7,
            providerMaxDate = java.util.concurrent.TimeUnit.DAYS.toMillis(9),
            completedAt = java.util.concurrent.TimeUnit.DAYS.toMillis(10),
            providerMessageCount = 12,
            eligibleCandidateCount = 3
        )

        assertEquals(100L, recent.coverageStartMillis)
        assertEquals(200L, recent.coverageEndMillis)
        assertEquals(90, recent.coverageWindowDays)
        assertEquals(
            java.util.concurrent.TimeUnit.DAYS.toMillis(2),
            recent.recentCoverageStartMillis
        )
        assertEquals(
            java.util.concurrent.TimeUnit.DAYS.toMillis(9),
            recent.recentCoverageEndMillis
        )
        assertEquals(7, recent.recentScanWindowDays)
        assertEquals(12, recent.recentProviderMessageCount)
        assertEquals(3, recent.recentEligibleCandidateCount)
        assertEquals(0, recent.recentProcessedCount)
        assertTrue(recent.hasIncompleteRecentProcessing)
        assertEquals(null, recent.actionableError)
        assertEquals(
            recent.recentCoverageEndMillis,
            recentScanProviderMaxDate(
                state = recent,
                scanWindowDays = 7,
                nowMillis = java.util.concurrent.TimeUnit.DAYS.toMillis(12)
            )
        )

        val incompleteCard = setupImportCardModel(
            recent.copy(modelPrepared = true),
            nowMillis = recent.recentCoverageEndMillis!!
        )
        assertEquals(
            "Some recent alerts still need attention",
            incompleteCard.title
        )

        val card = setupImportCardModel(
            recent.copy(
                modelPrepared = true,
                recentProcessedCount = 3,
                recentSavedCount = 2,
                recentRejectedCount = 1
            ),
            nowMillis = recent.recentCoverageEndMillis!!
        )
        assertEquals(
            java.util.concurrent.TimeUnit.DAYS.toMillis(12),
            recentScanProviderMaxDate(
                state = recent.copy(recentProcessedCount = 3),
                scanWindowDays = 7,
                nowMillis = java.util.concurrent.TimeUnit.DAYS.toMillis(12)
            )
        )
        assertEquals("Pocket Financer is up to date", card.title)
        assertTrue(card.body.contains("recent 7-day scan"))
        assertEquals(
            "Recent scan: 12 messages checked · 3 eligible · 2 saved · 1 rejected",
            card.evidence
        )
    }

    @Test
    fun `manual processing failure produces durable actionable copy`() {
        assertEquals(null, manualProcessingFailureError(0))
        assertEquals(
            "1 eligible alert could not be processed.",
            manualProcessingFailureError(1)?.message
        )
        assertEquals(
            "2 eligible alerts could not be processed.",
            manualProcessingFailureError(2)?.message
        )
    }

    @Test
    fun `active wider scan copy does not claim stale coverage evidence`() {
        val card = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.SCANNING,
                coverageWindowDays = 90,
                coverageStartMillis = 1L,
                coverageEndMillis = 2L,
                lastSuccessfulScanMillis = 2L,
                activeScanWindowDays = 36_500,
                providerMessageCount = 0,
                eligibleCandidateCount = 0
            )
        )

        assertEquals("Checking all available SMS history", card.title)
        assertEquals(null, card.evidence)
    }

    @Test
    fun `stale verified range is dated and is not called up to date`() {
        val scanEnded = 10_000L
        val card = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.READY,
                coverageWindowDays = 90,
                coverageStartMillis = 1_000L,
                coverageEndMillis = scanEnded,
                lastSuccessfulScanMillis = scanEnded,
                modelPrepared = true
            ),
            nowMillis = scanEnded + java.util.concurrent.TimeUnit.DAYS
                .toMillis(30)
        )

        assertEquals("Pocket Financer is ready", card.title)
        assertTrue(card.body.contains("through"))
        assertFalse(card.body.contains("The last 90 days"))
    }

    @Test
    fun `durable recent scan failure overlays but preserves verified coverage`() {
        val card = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.READY,
                coverageWindowDays = 90,
                coverageStartMillis = 1_000L,
                coverageEndMillis = 2_000L,
                lastSuccessfulScanMillis = 2_000L,
                modelPrepared = true,
                actionableError = SetupActionableError(
                    code = "RECENT_SMS_SCAN_FAILED",
                    message = "The SMS provider could not be read.",
                    actionLabel = "Try recent scan again"
                )
            )
        )

        assertEquals("The recent SMS scan did not finish", card.title)
        assertEquals(
            SetupCardAction.RETRY_RECENT_SYNC,
            card.primaryAction
        )
        assertEquals(
            SetupCardActionTarget.RETRY_RECENT_SYNC,
            card.primaryAction!!.target()
        )
        assertTrue(card.body.contains("previously verified coverage"))
    }

    @Test
    fun `zero versus zero spending is neutral`() {
        val comparison = spendingComparison(
            currentAmount = 0.0,
            previousAmount = 0.0,
            previousPeriodLabel = "yesterday"
        )

        assertEquals("same", comparison.direction)
        assertEquals("No spending in either period", comparison.label)
    }

    @Test
    fun `first use and selected period empty states are distinct`() {
        assertEquals(
            "No transactions yet. The setup card shows what remains.",
            selectedPeriodEmptyMessage(
                selectedPeriod = "Day",
                totalTransactionCount = 0,
                setupStatus = SetupImportStatus.NOT_STARTED
            )
        )
        assertEquals(
            "No spending transactions in the selected month period",
            selectedPeriodEmptyMessage(
                selectedPeriod = "Month",
                totalTransactionCount = 5,
                setupStatus = SetupImportStatus.READY
            )
        )
    }

    @Test
    fun `legacy ready state never claims verified freshness`() {
        val state = SetupImportState(
            status = SetupImportStatus.READY,
            modelPrepared = true
        )

        val model = setupImportCardModel(state)

        assertFalse(state.hasVerifiedCoverage)
        assertEquals("READY · COVERAGE UNKNOWN", model.eyebrow)
        assertEquals(SetupCardAction.SCAN_RECENT, model.primaryAction)
    }

    @Test
    fun `empty inbox filtered inbox permission and scan failure have distinct copy`() {
        val emptyInbox = setupImportCardModel(
            readyNoHistory(SetupEmptyReason.EMPTY_INBOX)
        )
        val filteredInbox = setupImportCardModel(
            readyNoHistory(SetupEmptyReason.FILTERED_OUT)
        )
        val permission = setupImportCardModel(
            SetupImportState(status = SetupImportStatus.PERMISSION_NEEDED)
        )
        val failure = setupImportCardModel(
            SetupImportState(
                status = SetupImportStatus.FAILED,
                modelPrepared = true,
                actionableError = SetupActionableError(
                    code = "SMS_SCAN_FAILED",
                    message = "The SMS provider did not complete the scan.",
                    actionLabel = "Try again"
                )
            )
        )

        assertEquals(
            "No SMS were found in the checked history",
            emptyInbox.title
        )
        assertEquals(
            "Messages were found, but none looked transactional",
            filteredInbox.title
        )
        assertEquals("Restore SMS access", permission.title)
        assertEquals(
            "The SMS provider did not complete the scan.",
            failure.body
        )
    }

    @Test
    fun `ready without history does not promise automatic capture when updates are off`() {
        val card = setupImportCardModel(
            state = readyNoHistory(SetupEmptyReason.EMPTY_INBOX),
            automaticProcessingEnabled = false
        )

        assertTrue(
            card.body.contains(
                "will not process new alerts automatically"
            )
        )
        assertFalse(card.body.contains("will capture"))
        assertTrue(
            selectedPeriodEmptyMessage(
                selectedPeriod = "Day",
                totalTransactionCount = 0,
                setupStatus = SetupImportStatus.READY_NO_HISTORY,
                automaticProcessingEnabled = false
            ).contains("will not process new alerts automatically")
        )
    }

    @Test
    fun `fatal manual processing failure is never presented as success`() {
        val state = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            syncError = "The on-device model is not prepared."
        )

        assertEquals(true, homeSyncHasFailures(state))
    }

    @Test
    fun `concurrent winner is not counted as a newly saved transaction`() {
        val state = HomeSyncState(
            status = HomeSyncState.Status.DONE,
            queue = listOf(
                SyncSmsItem(
                    id = "new",
                    sender = "AX-BANK",
                    body = "Rs 100 debited",
                    date = 1L,
                    status = "synced"
                ),
                SyncSmsItem(
                    id = "existing",
                    sender = "AX-BANK",
                    body = "Rs 200 debited",
                    date = 2L,
                    status = "already_saved"
                )
            )
        )

        assertEquals(
            "1 saved · 1 already present",
            homeSyncCompletionSummary(state)
        )
    }

    @Test
    fun `distinct provider ids keep identical evidence as separate sources`() {
        val first = com.pocketfinancer.data.model.SmsSourceIdentity.androidSms(
            providerMessageId = "provider-1",
            sender = "AX-BANK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )
        val second = com.pocketfinancer.data.model.SmsSourceIdentity.androidSms(
            providerMessageId = "provider-2",
            sender = "AX-BANK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )
        val broadcast = com.pocketfinancer.data.model.SmsSourceIdentity.androidSms(
            providerMessageId = null,
            sender = "AX-BANK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1
        )

        assertFalse(sameQueuedSmsSource(first, second))
        assertTrue(sameQueuedSmsSource(first, broadcast))
        assertTrue(sameQueuedSmsSource(second, broadcast))

        val merged = mergeRecentScanQueue(
            currentQueue = listOf(
                SyncSmsItem(
                    id = broadcast.opaqueCandidateKey,
                    sender = "AX-BANK",
                    body = "Rs 500 debited",
                    date = 1_000L,
                    sourceIdentity = broadcast,
                    status = "pending"
                )
            ),
            providerMessages = listOf(
                com.pocketfinancer.sms.SmsReader.SmsMessage(
                    address = "AX-BANK",
                    body = "Rs 500 debited",
                    date = 1_000L,
                    type = 1,
                    providerMessageId = "provider-1"
                ),
                com.pocketfinancer.sms.SmsReader.SmsMessage(
                    address = "AX-BANK",
                    body = "Rs 500 debited",
                    date = 1_000L,
                    type = 1,
                    providerMessageId = "provider-2"
                )
            )
        )
        assertEquals(2, merged.size)
        assertEquals(
            setOf("provider-1", "provider-2"),
            merged.mapNotNull {
                it.sourceIdentity.providerMessageId
            }.toSet()
        )
    }

    @Test
    fun `terminal notification never calls a failed sync completed`() {
        val needsAttention = manualSyncTerminalNotificationCopy(
            saved = 2,
            rejected = 1,
            failed = 1
        )
        val complete = manualSyncTerminalNotificationCopy(
            saved = 2,
            rejected = 1,
            failed = 0
        )

        assertEquals("SMS Sync Needs Attention", needsAttention.title)
        assertFalse(needsAttention.title.contains("Complete"))
        assertFalse(needsAttention.text.contains("successfully"))
        assertTrue(needsAttention.text.contains("needs another attempt"))
        assertEquals("SMS Sync Complete", complete.title)
    }

    @Test
    fun `saved and already-saved queue items discard source evidence`() {
        val source = SyncSmsItem(
            id = "candidate",
            sender = "AX-HDFCBK",
            body = "Rs 500 debited at Example Merchant",
            date = 1L,
            status = "syncing",
            parsedAmount = 500.0,
            parsedMerchant = "Example Merchant"
        )

        val saved = source.withPrivacySafeStatus("synced")
        val alreadySaved = source.withPrivacySafeStatus("already_saved")

        assertEquals("Saved transaction", saved.sender)
        assertEquals("", saved.body)
        assertEquals(500.0, saved.parsedAmount)
        assertEquals("Example Merchant", saved.parsedMerchant)
        assertFalse(saved.hasDiagnosticSourceEvidence())

        assertEquals("Already in ledger", alreadySaved.sender)
        assertEquals("", alreadySaved.body)
        assertEquals(null, alreadySaved.parsedAmount)
        assertEquals(null, alreadySaved.parsedMerchant)
        assertFalse(alreadySaved.hasDiagnosticSourceEvidence())
    }

    @Test
    fun `pending in-flight and retryable queue items retain source evidence`() {
        val source = SyncSmsItem(
            id = "candidate",
            sender = "AX-HDFCBK",
            body = "Rs 500 debited at Example Merchant",
            date = 1L,
            status = "pending"
        )

        listOf("pending", "syncing", "error").forEach { status ->
            val transitioned = source.withPrivacySafeStatus(status)

            assertEquals(source.sender, transitioned.sender)
            assertEquals(source.body, transitioned.body)
            assertEquals(status, transitioned.status)
            assertTrue(transitioned.hasDiagnosticSourceEvidence())
        }
    }

    private fun readyNoHistory(reason: SetupEmptyReason) =
        SetupImportState(
            status = SetupImportStatus.READY_NO_HISTORY,
            coverageStartMillis = 100,
            coverageEndMillis = 200,
            coverageWindowDays = 90,
            lastSuccessfulScanMillis = 200,
            emptyReason = reason,
            modelPrepared = true
        )
}
