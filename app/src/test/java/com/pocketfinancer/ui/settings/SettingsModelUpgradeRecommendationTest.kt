package com.pocketfinancer.ui.settings

import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.ui.onboarding.OnboardingStep
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsModelUpgradeRecommendationTest {
    private val current = SlmTier.DEFAULT_ONBOARDING_SLM
    private val target = SlmTier.QWEN3_1_7B_Q4_K_M

    @Test
    fun `available recommendation remains actionable in settings`() {
        val recommendation = settingsModelUpgradeRecommendation(
            currentSlm = current,
            recommendedSlm = target,
            onboarding = OnboardingSyncManager.OnboardingSyncState(),
            fallbackDownloadState = ModelDownloader.DownloadState()
        )

        assertTrue(recommendation.isUpgradeAvailable)
        assertEquals(target, recommendation.recommendedSlm)
        assertFalse(recommendation.isRunning)
    }

    @Test
    fun `initial setup keeps upgrade visible but blocked with a truthful reason`() {
        val recommendation = settingsModelUpgradeRecommendation(
            currentSlm = current,
            recommendedSlm = target,
            onboarding = OnboardingSyncManager.OnboardingSyncState(
                isRunning = true,
                runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
                step = OnboardingStep.SYNCING,
                selectedSlm = current
            ),
            fallbackDownloadState = ModelDownloader.DownloadState()
        )

        assertTrue(recommendation.isUpgradeAvailable)
        assertFalse(recommendation.isRunning)
        assertEquals(
            "Finish the current setup or history import before starting a model upgrade.",
            recommendation.startBlockedMessage
        )
    }

    @Test
    fun `settings uses the same roomy debug emulator upgrade target as home`() {
        val recommended = settingsRecommendedSlm(
            device = DeviceCapabilities.DeviceInfo(
                ramGb = 16f,
                ramTier = DeviceCapabilities.RamTier.OK,
                gpu = null,
                cpu = DeviceCapabilities.CpuInfo(
                    cores = 4,
                    features = emptySet(),
                    hasI8mm = false,
                    hasDotProd = false,
                    hasFp16 = false,
                    socModel = null
                ),
                storage = DeviceCapabilities.StorageInfo(
                    totalBytes = 20L * GIBIBYTE,
                    availableBytes = 10L * GIBIBYTE,
                    usedBytes = 10L * GIBIBYTE
                ),
                isHighPerformanceDevice = false
            ),
            allowDebugEmulatorOverride = true
        )

        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, recommended)
    }

    @Test
    fun `active managed upgrade owns settings progress and cancellation`() {
        val progress = ModelDownloader.DownloadState(
            isDownloading = true,
            progress = 0.4f,
            speedMbps = 8f,
            etaSeconds = 60L
        )
        val recommendation = settingsModelUpgradeRecommendation(
            currentSlm = current,
            recommendedSlm = target,
            onboarding = OnboardingSyncManager.OnboardingSyncState(
                isRunning = true,
                isCancellationAllowed = true,
                runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
                step = OnboardingStep.DOWNLOAD_SLM,
                selectedSlm = target,
                downloadState = progress,
                isDownloading = true
            ),
            fallbackDownloadState = ModelDownloader.DownloadState(progress = 0.1f)
        )

        assertTrue(recommendation.isRunning)
        assertTrue(recommendation.isDownloading)
        assertTrue(recommendation.canCancel)
        assertEquals(progress, recommendation.downloadState)
    }

    @Test
    fun `activated recommendation is no longer offered`() {
        val recommendation = settingsModelUpgradeRecommendation(
            currentSlm = target,
            recommendedSlm = target,
            onboarding = OnboardingSyncManager.OnboardingSyncState(
                runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
                step = OnboardingStep.COMPLETED,
                selectedSlm = target
            ),
            fallbackDownloadState = ModelDownloader.DownloadState(isComplete = true)
        )

        assertFalse(recommendation.isUpgradeAvailable)
        assertFalse(recommendation.isRunning)
    }

    @Test
    fun `in flight activation keeps the cached active model`() {
        val onboarding = OnboardingSyncManager.OnboardingSyncState(
            isRunning = true,
            runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
            step = OnboardingStep.SYNCING,
            selectedSlm = target,
            isModelLoaded = true
        )

        assertEquals(current, settingsActiveSlm(current, onboarding))
    }

    @Test
    fun `durably completed activation adopts the selected model`() {
        val onboarding = OnboardingSyncManager.OnboardingSyncState(
            isRunning = false,
            runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
            step = OnboardingStep.COMPLETED,
            selectedSlm = target,
            isModelLoaded = true
        )

        assertEquals(target, settingsActiveSlm(current, onboarding))
    }

    private companion object {
        const val GIBIBYTE = 1_073_741_824L
    }
}
