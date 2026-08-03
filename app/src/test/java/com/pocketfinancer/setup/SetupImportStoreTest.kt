package com.pocketfinancer.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupImportStoreTest {

    @Test
    fun `legacy completed onboarding upgrades without invented freshness`() {
        val fake = FakeSharedPreferences(
            mapOf(SetupImportStore.KEY_ONBOARDING_COMPLETED to true)
        )

        val state = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertEquals(SetupImportStatus.READY, state.status)
        assertTrue(state.modelPrepared)
        assertTrue(state.modelDownloadConfirmed)
        assertNull(state.coverageStartMillis)
        assertNull(state.coverageEndMillis)
        assertNull(state.coverageWindowDays)
        assertNull(state.lastSuccessfulScanMillis)
        assertFalse(state.hasVerifiedCoverage)
    }

    @Test
    fun `missing state reflects required permission health`() {
        val missingPermission = SetupImportStore(
            FakeSharedPreferences().preferences,
            hasSmsPermissions = false
        )
        val grantedPermission = SetupImportStore(
            FakeSharedPreferences().preferences,
            hasSmsPermissions = true
        )

        assertEquals(
            SetupImportStatus.PERMISSION_NEEDED,
            missingPermission.state.value.status
        )
        assertEquals(
            SetupImportStatus.NOT_STARTED,
            grantedPermission.state.value.status
        )
    }

    @Test
    fun `shell unlock and setup initialization share one durable commit`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )

        val state = store.completeRequiredPermissions()

        assertEquals(SetupImportStatus.NOT_STARTED, state.status)
        assertEquals(
            true,
            fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED]
        )
    }

    @Test
    fun `process recreation reconciles active import to interrupted pause`() {
        val fake = FakeSharedPreferences()
        val firstProcess = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        firstProcess.update {
            it.copy(
                status = SetupImportStatus.PROCESSING,
                coverageWindowDays = 30,
                activeScanWindowDays = 30,
                activeScanProviderMaxDateMillis = 250,
                coverageStartMillis = 100,
                coverageEndMillis = 200,
                providerMessageCount = 15,
                eligibleCandidateCount = 3,
                processedCount = 1,
                savedCount = 1,
                modelDownloadConfirmed = true,
                modelPrepared = true
            )
        }

        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertEquals(SetupImportStatus.PAUSED, restarted.status)
        assertEquals(SetupPauseReason.INTERRUPTED, restarted.pauseReason)
        assertEquals(SetupImportStore.ERROR_INTERRUPTED, restarted.actionableError?.code)
        assertEquals(30, restarted.coverageWindowDays)
        assertEquals(30, restarted.activeScanWindowDays)
        assertEquals(250L, restarted.activeScanProviderMaxDateMillis)
        assertEquals(1, restarted.savedCount)
        assertTrue(restarted.modelPrepared)
    }

    @Test
    fun `permission revocation and recovery restore truthful terminal state`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.update {
            it.copy(
                status = SetupImportStatus.READY_NO_HISTORY,
                coverageWindowDays = 90,
                coverageStartMillis = 100,
                coverageEndMillis = 200,
                lastSuccessfulScanMillis = 200,
                emptyReason = SetupEmptyReason.EMPTY_INBOX
            )
        }

        val revoked = store.reconcilePermission(granted = false)
        val recovered = store.reconcilePermission(granted = true)

        assertEquals(SetupImportStatus.PERMISSION_NEEDED, revoked.status)
        assertEquals(
            SetupImportStore.ERROR_SMS_PERMISSION_REQUIRED,
            revoked.actionableError?.code
        )
        assertEquals(SetupImportStatus.READY_NO_HISTORY, recovered.status)
        assertEquals(90, recovered.coverageWindowDays)
        assertEquals(SetupEmptyReason.EMPTY_INBOX, recovered.emptyReason)
        assertNull(recovered.actionableError)
    }

    @Test
    fun `permission recovery never revives interrupted active work`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.update {
            it.copy(
                status = SetupImportStatus.DOWNLOADING,
                modelDownloadConfirmed = true,
                modelPrepared = true
            )
        }

        store.reconcilePermission(granted = false)
        val recovered = store.reconcilePermission(granted = true)

        assertEquals(SetupImportStatus.PAUSED, recovered.status)
        assertEquals(SetupPauseReason.INTERRUPTED, recovered.pauseReason)
        assertEquals(
            SetupImportStore.ERROR_INTERRUPTED,
            recovered.actionableError?.code
        )
        assertTrue(recovered.modelDownloadConfirmed)
        assertTrue(recovered.modelPrepared)
    }

    @Test
    fun `permission recovery preserves user requested pause across restart`() {
        val fake = FakeSharedPreferences()
        val originalError = SetupActionableError(
            code = "HISTORICAL_IMPORT_STOPPED_BY_USER",
            message = "Completed saves remain on this device.",
            actionLabel = "Resume SMS import"
        )
        val firstProcess = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        firstProcess.update {
            it.copy(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.USER_REQUESTED,
                actionableError = originalError
            )
        }

        firstProcess.reconcilePermission(granted = false)
        val restartedWhileRevoked = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = false
        )
        val recovered = restartedWhileRevoked.reconcilePermission(granted = true)

        assertEquals(SetupImportStatus.PAUSED, recovered.status)
        assertEquals(SetupPauseReason.USER_REQUESTED, recovered.pauseReason)
        assertEquals(originalError, recovered.actionableError)
    }

    @Test
    fun `user requested historical pause and resume metadata survive restart`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.update {
            it.copy(
                status = SetupImportStatus.PAUSED,
                pauseReason = SetupPauseReason.USER_REQUESTED,
                activeScanWindowDays = 90,
                activeScanProviderMaxDateMillis = 9_000L,
                coverageWindowDays = 30,
                coverageStartMillis = 100L,
                coverageEndMillis = 200L,
                lastSuccessfulScanMillis = 200L,
                eligibleCandidateCount = 5,
                processedCount = 3,
                savedCount = 2,
                rejectedCount = 1,
                actionableError = SetupActionableError(
                    code = "HISTORICAL_IMPORT_STOPPED_BY_USER",
                    message = "Completed saves remain on this device.",
                    actionLabel = "Resume SMS import"
                ),
                modelDownloadConfirmed = true,
                modelPrepared = true
            )
        }

        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertEquals(SetupImportStatus.PAUSED, restarted.status)
        assertEquals(SetupPauseReason.USER_REQUESTED, restarted.pauseReason)
        assertEquals(90, restarted.activeScanWindowDays)
        assertEquals(9_000L, restarted.activeScanProviderMaxDateMillis)
        assertEquals(30, restarted.coverageWindowDays)
        assertEquals(3, restarted.processedCount)
        assertEquals(2, restarted.savedCount)
        assertEquals(1, restarted.rejectedCount)
        assertTrue(restarted.actionableError!!.message.contains("Completed saves"))
    }

    @Test
    fun `failed wider attempt preserves the last verified coverage`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.update {
            it.copy(
                status = SetupImportStatus.READY,
                coverageWindowDays = 30,
                coverageStartMillis = 100,
                coverageEndMillis = 200,
                lastSuccessfulScanMillis = 200
            )
        }

        // A coordinator records only the active status before the provider
        // read; wider coverage is committed only after that read succeeds.
        store.update {
            it.copy(
                status = SetupImportStatus.SCANNING,
                activeScanWindowDays = 36_500,
                activeScanProviderMaxDateMillis = 9_000,
                providerMessageCount = 0,
                eligibleCandidateCount = 0
            )
        }
        store.update {
            it.copy(
                status = SetupImportStatus.FAILED,
                actionableError = SetupActionableError(
                    code = "SMS_SCAN_FAILED",
                    message = "The wider scan failed.",
                    actionLabel = "Try again"
                )
            )
        }
        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertEquals(SetupImportStatus.FAILED, restarted.status)
        assertEquals(30, restarted.coverageWindowDays)
        assertEquals(36_500, restarted.activeScanWindowDays)
        assertEquals(9_000L, restarted.activeScanProviderMaxDateMillis)
        assertEquals(100L, restarted.coverageStartMillis)
        assertEquals(200L, restarted.coverageEndMillis)
        assertEquals(200L, restarted.lastSuccessfulScanMillis)
        assertTrue(restarted.hasVerifiedCoverage)
    }

    @Test
    fun `recent scan failure survives restart without replacing historical coverage`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.update {
            it.copy(
                status = SetupImportStatus.READY,
                coverageWindowDays = 90,
                coverageStartMillis = 100,
                coverageEndMillis = 200,
                lastSuccessfulScanMillis = 200,
                actionableError = SetupActionableError(
                    code = "RECENT_SMS_SCAN_FAILED",
                    message = "Provider read failed.",
                    actionLabel = "Try recent scan again"
                )
            )
        }

        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertEquals(SetupImportStatus.READY, restarted.status)
        assertEquals(90, restarted.coverageWindowDays)
        assertEquals(
            "RECENT_SMS_SCAN_FAILED",
            restarted.actionableError?.code
        )
    }

    @Test
    fun `explicit model confirmation survives recreation`() {
        val fake = FakeSharedPreferences()
        SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).setModelDownloadConfirmed(true)

        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )

        assertTrue(restarted.state.value.modelDownloadConfirmed)
        assertEquals(SetupImportStatus.NOT_STARTED, restarted.state.value.status)
    }

    @Test
    fun `prepared model and selected id share one durable commit`() {
        val fake = FakeSharedPreferences()
        val prepared = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).markModelPrepared("qwen3-0.6b-q8")

        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertTrue(prepared.modelDownloadConfirmed)
        assertTrue(prepared.modelPrepared)
        assertTrue(restarted.modelDownloadConfirmed)
        assertTrue(restarted.modelPrepared)
        assertEquals("qwen3-0.6b-q8", fake.values["selected_slm_id"])
    }

    @Test
    fun `missing prepared artifact requires confirmation again after restart`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.update {
            it.copy(
                status = SetupImportStatus.READY,
                coverageStartMillis = 100,
                coverageEndMillis = 200,
                coverageWindowDays = 90,
                lastSuccessfulScanMillis = 200,
                modelDownloadConfirmed = true,
                modelPrepared = true
            )
        }

        store.reconcileModelAvailability(hasPublishedModel = false)
        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertFalse(restarted.modelPrepared)
        assertFalse(restarted.modelDownloadConfirmed)
        assertEquals(SetupImportStatus.READY, restarted.status)
        assertEquals(90, restarted.coverageWindowDays)
    }

    @Test
    fun `recent scan success survives restart without replacing history coverage`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.update {
            it.copy(
                status = SetupImportStatus.READY,
                coverageStartMillis = 100,
                coverageEndMillis = 200,
                coverageWindowDays = 90,
                lastSuccessfulScanMillis = 200,
                recentCoverageStartMillis = 300,
                recentCoverageEndMillis = 400,
                recentScanWindowDays = 7,
                recentProviderMessageCount = 18,
                recentEligibleCandidateCount = 2,
                recentProcessedCount = 2,
                recentSavedCount = 1,
                recentRejectedCount = 1,
                recentFailedCount = 0,
                lastSuccessfulRecentScanMillis = 400
            )
        }

        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertEquals(100L, restarted.coverageStartMillis)
        assertEquals(200L, restarted.coverageEndMillis)
        assertEquals(90, restarted.coverageWindowDays)
        assertEquals(200L, restarted.lastSuccessfulScanMillis)
        assertEquals(300L, restarted.recentCoverageStartMillis)
        assertEquals(400L, restarted.recentCoverageEndMillis)
        assertEquals(7, restarted.recentScanWindowDays)
        assertEquals(18, restarted.recentProviderMessageCount)
        assertEquals(2, restarted.recentEligibleCandidateCount)
        assertEquals(2, restarted.recentProcessedCount)
        assertEquals(1, restarted.recentSavedCount)
        assertEquals(1, restarted.recentRejectedCount)
        assertEquals(0, restarted.recentFailedCount)
        assertEquals(400L, restarted.lastSuccessfulRecentScanMillis)
    }

    @Test
    fun `unfinished recent candidate remains visible after process restart`() {
        val fake = FakeSharedPreferences()
        val firstProcess = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        firstProcess.update {
            it.copy(
                status = SetupImportStatus.READY,
                recentCoverageStartMillis = 100,
                recentCoverageEndMillis = 200,
                recentScanWindowDays = 7,
                recentProviderMessageCount = 8,
                recentEligibleCandidateCount = 2,
                recentProcessedCount = 1,
                recentSavedCount = 1,
                lastSuccessfulRecentScanMillis = 210,
                modelPrepared = true
            )
        }

        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertEquals(SetupImportStatus.READY, restarted.status)
        assertTrue(restarted.hasVerifiedRecentCoverage)
        assertTrue(restarted.hasIncompleteRecentProcessing)
        assertTrue(restarted.recentProcessingNeedsAttention)
        assertEquals(1, restarted.recentProcessedCount)
        assertEquals(1, restarted.recentSavedCount)
    }

    @Test
    fun `confirmed erase resets shell and all setup import metadata`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.completeRequiredPermissions()
        store.update {
            it.copy(
                status = SetupImportStatus.READY,
                coverageWindowDays = 90,
                coverageStartMillis = 100,
                coverageEndMillis = 200,
                providerMessageCount = 40,
                eligibleCandidateCount = 3,
                processedCount = 3,
                savedCount = 2,
                rejectedCount = 1,
                lastSuccessfulScanMillis = 200,
                modelDownloadConfirmed = true,
                modelPrepared = true
            )
        }

        val erased = store.resetForFirstRun()
        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertEquals(false, fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED])
        assertEquals(SetupImportStatus.NOT_STARTED, erased.status)
        assertEquals(SetupImportStatus.NOT_STARTED, restarted.status)
        assertNull(restarted.coverageWindowDays)
        assertEquals(0, restarted.providerMessageCount)
        assertEquals(0, restarted.savedCount)
        assertFalse(restarted.modelDownloadConfirmed)
        assertFalse(restarted.modelPrepared)
    }

    @Test
    fun `erase can preserve truthful preparation for a retained model artifact`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )

        val erased = store.resetForFirstRun(retainedModelPrepared = true)
        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).state.value

        assertTrue(erased.modelPrepared)
        assertTrue(erased.modelDownloadConfirmed)
        assertTrue(restarted.modelPrepared)
        assertTrue(restarted.modelDownloadConfirmed)
        assertEquals(SetupImportStatus.NOT_STARTED, restarted.status)
    }

    @Test
    fun `erase resets shell selected model and run generation atomically`() {
        val fake = FakeSharedPreferences(
            initialValues = mapOf(
                "onboarding_completed" to true,
                "selected_slm_id" to "old-model",
                "onboarding_run_generation" to 4L
            )
        )
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )

        store.resetForFirstRun(
            retainedModelPrepared = true,
            nextRunGeneration = 5L,
            clearSelectedModel = true
        )

        assertEquals(false, fake.values["onboarding_completed"])
        assertEquals(null, fake.values["selected_slm_id"])
        assertEquals(5L, fake.values["onboarding_run_generation"])
        assertTrue(store.state.value.modelPrepared)
    }

    @Test
    fun `durable erase begin locks shell advances generation and survives restart`() {
        val fake = FakeSharedPreferences(
            initialValues = mapOf(
                SetupImportStore.KEY_ONBOARDING_COMPLETED to true,
                "selected_slm_id" to "old-model",
                "onboarding_run_generation" to 4L
            )
        )
        val firstProcess = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )

        val generation = firstProcess.beginLocalFinancialErase(
            nextRunGeneration = 5L
        )
        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )

        assertEquals(5L, generation)
        assertEquals(
            true,
            fake.values[SetupImportStore.KEY_LOCAL_FINANCIAL_ERASE_PENDING]
        )
        assertEquals(
            false,
            fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED]
        )
        assertNull(fake.values["selected_slm_id"])
        assertEquals(5L, fake.values["onboarding_run_generation"])
        assertTrue(restarted.isLocalFinancialErasePending())
    }

    @Test
    fun `restarted durable erase begin keeps the generation already committed`() {
        val fake = FakeSharedPreferences(
            initialValues = mapOf("onboarding_run_generation" to 4L)
        )
        SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        ).beginLocalFinancialErase(nextRunGeneration = 5L)
        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )

        val generation = restarted.beginLocalFinancialErase(
            nextRunGeneration = 6L
        )

        assertEquals(5L, generation)
        assertEquals(5L, fake.values["onboarding_run_generation"])
        assertTrue(restarted.isLocalFinancialErasePending())
    }

    @Test
    fun `durable erase finish clears all setup history and marker together`() {
        val fake = FakeSharedPreferences(
            initialValues = mapOf(
                SetupImportStore.KEY_ONBOARDING_COMPLETED to true,
                "selected_slm_id" to "old-model",
                "onboarding_run_generation" to 4L
            )
        )
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.update {
            it.copy(
                status = SetupImportStatus.READY,
                coverageStartMillis = 100,
                coverageEndMillis = 200,
                coverageWindowDays = 90,
                activeScanWindowDays = 90,
                activeScanProviderMaxDateMillis = 500,
                providerMessageCount = 40,
                eligibleCandidateCount = 4,
                processedCount = 4,
                savedCount = 2,
                rejectedCount = 1,
                failedCount = 1,
                lastSuccessfulScanMillis = 200,
                recentCoverageStartMillis = 300,
                recentCoverageEndMillis = 400,
                recentScanWindowDays = 7,
                recentProviderMessageCount = 8,
                recentEligibleCandidateCount = 2,
                recentProcessedCount = 2,
                recentSavedCount = 1,
                recentRejectedCount = 1,
                lastSuccessfulRecentScanMillis = 400,
                modelDownloadConfirmed = true,
                modelPrepared = true
            )
        }
        store.beginLocalFinancialErase(nextRunGeneration = 5L)

        val finished = store.finishLocalFinancialErase(
            retainedModelPrepared = true
        )
        val restarted = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )

        assertFalse(store.isLocalFinancialErasePending())
        assertFalse(restarted.isLocalFinancialErasePending())
        assertFalse(
            fake.values.containsKey(
                SetupImportStore.KEY_LOCAL_FINANCIAL_ERASE_PENDING
            )
        )
        assertEquals(
            false,
            fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED]
        )
        assertNull(fake.values["selected_slm_id"])
        assertEquals(5L, fake.values["onboarding_run_generation"])
        assertEquals(SetupImportStatus.NOT_STARTED, finished.status)
        assertEquals(SetupImportStatus.NOT_STARTED, restarted.state.value.status)
        assertNull(restarted.state.value.coverageStartMillis)
        assertNull(restarted.state.value.coverageWindowDays)
        assertNull(restarted.state.value.activeScanProviderMaxDateMillis)
        assertNull(restarted.state.value.recentCoverageStartMillis)
        assertNull(restarted.state.value.recentScanWindowDays)
        assertEquals(0, restarted.state.value.providerMessageCount)
        assertEquals(0, restarted.state.value.processedCount)
        assertEquals(0, restarted.state.value.recentProviderMessageCount)
        assertEquals(0, restarted.state.value.recentEligibleCandidateCount)
        assertEquals(0, restarted.state.value.recentProcessedCount)
        assertEquals(0, restarted.state.value.recentSavedCount)
        assertEquals(0, restarted.state.value.recentRejectedCount)
        assertEquals(0, restarted.state.value.recentFailedCount)
        assertTrue(restarted.state.value.modelDownloadConfirmed)
        assertTrue(restarted.state.value.modelPrepared)
    }

    @Test
    fun `pending durable erase cannot unlock shell`() {
        val fake = FakeSharedPreferences()
        val store = SetupImportStore(
            fake.preferences,
            hasSmsPermissions = true
        )
        store.beginLocalFinancialErase(nextRunGeneration = 1L)

        val completion = store.tryCompleteRequiredPermissions()

        assertNull(completion)
        assertEquals(
            false,
            fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED]
        )
        assertTrue(store.isLocalFinancialErasePending())
    }
}
