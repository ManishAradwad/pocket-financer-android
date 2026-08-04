package com.pocketfinancer.ui.home

import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager

class ModelUpgradePolicyTest {

    @Test
    fun `release policy keeps an unaccelerated device on the starter model`() {
        val target = selectModelUpgradeTarget(
            device = device(
                ramGb = 16f,
                highPerformance = false,
                availableStorageBytes = 10L * GIBIBYTE
            ),
            allowDebugEmulatorOverride = false
        )

        assertEquals(SlmTier.DEFAULT_ONBOARDING_SLM, target.tier)
        assertFalse(target.isDebugEmulatorOverride)
    }

    @Test
    fun `debug emulator override is capped at the 1_7B Q4 model`() {
        val target = selectModelUpgradeTarget(
            device = device(
                ramGb = 16f,
                highPerformance = false,
                availableStorageBytes = 10L * GIBIBYTE
            ),
            allowDebugEmulatorOverride = true
        )

        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, target.tier)
        assertTrue(target.isDebugEmulatorOverride)
    }

    @Test
    fun `debug emulator override respects target RAM and storage`() {
        val lowRam = selectModelUpgradeTarget(
            device = device(
                ramGb = 3f,
                highPerformance = false,
                availableStorageBytes = 10L * GIBIBYTE
            ),
            allowDebugEmulatorOverride = true
        )
        val lowStorage = selectModelUpgradeTarget(
            device = device(
                ramGb = 16f,
                highPerformance = false,
                availableStorageBytes = 1L * GIBIBYTE
            ),
            allowDebugEmulatorOverride = true
        )

        assertEquals(SlmTier.DEFAULT_ONBOARDING_SLM, lowRam.tier)
        assertEquals(SlmTier.DEFAULT_ONBOARDING_SLM, lowStorage.tier)
        assertFalse(lowRam.isDebugEmulatorOverride)
        assertFalse(lowStorage.isDebugEmulatorOverride)
    }

    @Test
    fun `downloaded target remains an upgrade until selection changes`() {
        // Artifact presence is intentionally not an input to this policy.
        assertTrue(
            isHigherQualityModel(
                current = SlmTier.DEFAULT_ONBOARDING_SLM,
                target = SlmTier.QWEN3_1_7B_Q4_K_M
            )
        )
        assertFalse(
            isHigherQualityModel(
                current = SlmTier.QWEN3_1_7B_Q4_K_M,
                target = SlmTier.QWEN3_1_7B_Q4_K_M
            )
        )
    }

    @Test
    fun `other model work blocks a new upgrade on every surface`() {
        val message = modelUpgradeStartBlockedMessage(
            onboarding = OnboardingSyncManager.OnboardingSyncState(),
            otherFlowBusy = true
        )

        assertEquals(
            "Finish the current model task before starting a model upgrade.",
            message
        )
    }

    private fun device(
        ramGb: Float,
        highPerformance: Boolean,
        availableStorageBytes: Long
    ) = DeviceCapabilities.DeviceInfo(
        ramGb = ramGb,
        ramTier = when {
            ramGb < 2.5f -> DeviceCapabilities.RamTier.BLOCKED
            ramGb < 3.5f -> DeviceCapabilities.RamTier.WARNING
            else -> DeviceCapabilities.RamTier.OK
        },
        gpu = null,
        cpu = DeviceCapabilities.CpuInfo(
            cores = 4,
            features = emptySet(),
            hasI8mm = highPerformance,
            hasDotProd = highPerformance,
            hasFp16 = highPerformance,
            socModel = null
        ),
        storage = DeviceCapabilities.StorageInfo(
            totalBytes = 20L * GIBIBYTE,
            availableBytes = availableStorageBytes,
            usedBytes = 20L * GIBIBYTE - availableStorageBytes
        ),
        isHighPerformanceDevice = highPerformance
    )

    private companion object {
        const val GIBIBYTE = 1_073_741_824L
    }
}
