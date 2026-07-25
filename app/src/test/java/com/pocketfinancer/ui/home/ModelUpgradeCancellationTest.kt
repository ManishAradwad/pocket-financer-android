package com.pocketfinancer.ui.home

import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.ui.onboarding.OnboardingStep
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUpgradeCancellationTest {

    @Test
    fun `active model upgrade can be cancelled`() {
        val state = OnboardingSyncManager.OnboardingSyncState(
            isRunning = true,
            isCancellationAllowed = true,
            runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
        )

        assertTrue(canCancelModelUpgrade(state))
    }

    @Test
    fun `initial onboarding cannot be cancelled from home upgrade action`() {
        val state = OnboardingSyncManager.OnboardingSyncState(
            isRunning = true,
            isCancellationAllowed = true,
            runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP
        )

        assertFalse(canCancelModelUpgrade(state))
    }

    @Test
    fun `completed model upgrade cannot be cancelled`() {
        val state = OnboardingSyncManager.OnboardingSyncState(
            isRunning = false,
            runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
            step = OnboardingStep.COMPLETED
        )

        assertFalse(canCancelModelUpgrade(state))
        assertNull(unfinishedModelUpgradeTarget(state))
    }

    @Test
    fun `upgrade cannot be cancelled again while cleanup is draining`() {
        val state = OnboardingSyncManager.OnboardingSyncState(
            isRunning = true,
            isCancelling = true,
            isCancellationAllowed = false,
            runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
            syncMessage = "Cancelling model upgrade..."
        )

        assertFalse(canCancelModelUpgrade(state))
    }

    @Test
    fun `cancelled upgrade keeps its model target available for resume`() {
        val target = SlmTier.QWEN3_1_7B_Q4_K_M
        val state = OnboardingSyncManager.OnboardingSyncState(
            isRunning = false,
            runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
            selectedSlm = target,
            step = OnboardingStep.DOWNLOAD_SLM,
            syncMessage = "Cancelled"
        )

        assertEquals(target, unfinishedModelUpgradeTarget(state))
    }

    @Test
    fun `initial onboarding target is not reused by home recommendation`() {
        val state = OnboardingSyncManager.OnboardingSyncState(
            isRunning = false,
            runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
            selectedSlm = SlmTier.DEFAULT_ONBOARDING_SLM,
            step = OnboardingStep.DOWNLOAD_SLM
        )

        assertNull(unfinishedModelUpgradeTarget(state))
    }
}
