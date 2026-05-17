package com.pocketfinancer.hardware

import org.junit.Test
import kotlin.test.*

class SlmSelectorUnitTest {

    // ── Helper: build a DeviceInfo with given specs ───────────────────

    private fun deviceWith(
        ramGb: Float,
        gpuAccel: Boolean = false,
        storageFreeGb: Float = 10f
    ): DeviceCapabilities.DeviceInfo {
        val oneGb = 1_073_741_824L
        val storage = DeviceCapabilities.StorageInfo(
            totalBytes = (storageFreeGb * oneGb).toLong() * 10,
            availableBytes = (storageFreeGb * oneGb).toLong(),
            usedBytes = (storageFreeGb * oneGb).toLong() * 9
        )
        val cpu = DeviceCapabilities.CpuInfo(
            cores = 8,
            features = setOf("i8mm", "dotprod", "asimddp", "fphp"),
            hasI8mm = true,
            hasDotProd = true,
            hasFp16 = true,
            socModel = "Exynos 9611"
        )
        val gpu = if (gpuAccel) {
            DeviceCapabilities.GpuInfo(
                renderer = "Mali-G72 MP3",
                vendor = "ARM",
                version = "OpenGL ES 3.2",
                hasAdreno = false,
                hasMali = true,
                hasPowerVR = false,
                gpuType = "Mali (ARM)"
            )
        } else null
        return DeviceCapabilities.DeviceInfo(
            ramGb = ramGb,
            totalRamGb = ramGb,
            ramTier = when {
                ramGb >= 4.0f -> DeviceCapabilities.RamTier.OK
                ramGb >= 3.5f -> DeviceCapabilities.RamTier.WARNING
                else -> DeviceCapabilities.RamTier.BLOCKED
            },
            gpu = gpu,
            cpu = cpu,
            storage = storage,
            isGpuAccelerationSupported = gpuAccel
        )
    }

    // ── Qwen3 preferred over Gemma4 ──────────────────────────────────

    @Test
    fun `selectSlmForDevice should prefer Qwen3 over Gemma with same RAM`() {
        val device = deviceWith(ramGb = 4.0f, gpuAccel = true)
        val result = selectSlmForDevice(device)
        assertNotNull(result)
        assertEquals(SlmTier.QWEN3_1_7B_Q8_0, result)
    }

    // ── Tier 1: Qwen3-1.7B Q8_0 with 4GB+ and GPU ───────────────────

    @Test
    fun `selectSlmForDevice should pick Qwen3 Q8_0 with 4GB and GPU`() {
        val device = deviceWith(ramGb = 4.0f, gpuAccel = true)
        assertEquals(SlmTier.QWEN3_1_7B_Q8_0, selectSlmForDevice(device))
    }

    // ── Tier 2: Qwen3-1.7B Q4_K_M with 3.5GB RAM ────────────────────

    @Test
    fun `selectSlmForDevice should pick Qwen3 Q4_K_M with 3_5GB`() {
        val device = deviceWith(ramGb = 3.5f, gpuAccel = false)
        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, selectSlmForDevice(device))
    }

    @Test
    fun `selectSlmForDevice should pick Qwen3 Q4_K_M with 3_5GB even with GPU`() {
        val device = deviceWith(ramGb = 3.5f, gpuAccel = true)
        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, selectSlmForDevice(device))
    }

    // ── Tier 5: Fallback to Qwen3-0.6B with 2.5GB ────────────────────

    @Test
    fun `selectSlmForDevice should pick Qwen3 0_6B with 2_5GB`() {
        val device = deviceWith(ramGb = 2.5f)
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, selectSlmForDevice(device))
    }

    // ── Null when too little RAM ─────────────────────────────────────

    @Test
    fun `selectSlmForDevice should return null with less than 2_5GB`() {
        val device = deviceWith(ramGb = 2.0f)
        assertNull(selectSlmForDevice(device))
    }

    @Test
    fun `selectSlmForDevice should return null with 1GB`() {
        val device = deviceWith(ramGb = 1.0f)
        assertNull(selectSlmForDevice(device))
    }

    // ── explainTierSelection ──────────────────────────────────────────

    @Test
    fun `explainTierSelection should say Selected for picked tier`() {
        val device = deviceWith(ramGb = 4.0f, gpuAccel = true)
        val tier = SlmTier.QWEN3_1_7B_Q8_0
        val explanation = explainTierSelection(tier, device, isSelected = true)
        assertTrue(explanation.startsWith("Selected"))
    }

    @Test
    fun `explainTierSelection should mention blockers for unavailable tier`() {
        val device = deviceWith(ramGb = 2.0f)
        val explanation = explainTierSelection(SlmTier.QWEN3_1_7B_Q8_0, device, isSelected = false)
        assertTrue(explanation.contains("Not available"))
        assertTrue(explanation.contains("needs"))
    }

    @Test
    fun `explainTierSelection should mention GPU requirement for Qwen3 Q8_0`() {
        val device = deviceWith(ramGb = 4.0f, gpuAccel = false)
        val explanation = explainTierSelection(SlmTier.QWEN3_1_7B_Q8_0, device, isSelected = false)
        assertTrue(explanation.contains("GPU"))
    }

    @Test
    fun `explainTierSelection should say higher-priority for available but not selected`() {
        val device = deviceWith(ramGb = 6.0f, gpuAccel = true)
        val explanation = explainTierSelection(SlmTier.GEMMA4_E2B_Q8_0, device, isSelected = false)
        assertTrue(explanation.contains("higher-priority") || explanation.contains("Available"))
    }

    // ── ALL_TIERS ordering ────────────────────────────────────────────

    @Test
    fun `ALL_TIERS should be ordered by priority`() {
        val tiers = SlmTier.ALL_TIERS
        assertEquals(5, tiers.size)
        assertEquals(SlmTier.QWEN3_1_7B_Q8_0, tiers[0])
        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, tiers[1])
        assertEquals(SlmTier.GEMMA4_E2B_Q8_0, tiers[2])
        assertEquals(SlmTier.GEMMA4_E2B_Q4_K_M, tiers[3])
        assertEquals(SlmTier.QWEN3_0_6B_Q8_0, tiers[4])
    }

    // ── SlmTier properties ────────────────────────────────────────────

    @Test
    fun `Qwen3 tiers should have thinking mode enabled`() {
        assertTrue(SlmTier.QWEN3_1_7B_Q8_0.hasThinkingMode)
        assertTrue(SlmTier.QWEN3_1_7B_Q4_K_M.hasThinkingMode)
        assertTrue(SlmTier.QWEN3_0_6B_Q8_0.hasThinkingMode)
    }

    @Test
    fun `Gemma4 tiers should not have thinking mode`() {
        assertFalse(SlmTier.GEMMA4_E2B_Q8_0.hasThinkingMode)
        assertFalse(SlmTier.GEMMA4_E2B_Q4_K_M.hasThinkingMode)
    }

    @Test
    fun `sizeGb should convert MB to GB`() {
        assertEquals(700.0f / 1024.0f, SlmTier.QWEN3_0_6B_Q8_0.sizeGb, 0.001f)
    }
}
