package com.pocketfinancer.hardware

import org.junit.Test
import kotlin.test.*

class SlmSelectorUnitTest {

    // ── Helper: build a DeviceInfo with given specs ───────────────────

    private fun deviceWith(
        ramGb: Float,
        highPerf: Boolean = false,
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
            features = if (highPerf) setOf("i8mm", "dotprod", "asimddp", "fphp") else emptySet(),
            hasI8mm = highPerf,
            hasDotProd = highPerf,
            hasFp16 = highPerf,
            socModel = "Exynos 9611"
        )
        val gpu = if (highPerf) {
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
            ramTier = when {
                ramGb >= 3.5f -> DeviceCapabilities.RamTier.OK
                ramGb >= 2.5f -> DeviceCapabilities.RamTier.WARNING
                else -> DeviceCapabilities.RamTier.BLOCKED
            },
            gpu = gpu,
            cpu = cpu,
            storage = storage,
            isHighPerformanceDevice = highPerf
        )
    }

    // ── Gemma preferred over Qwen3 ──────────────────────────────────

    @Test
    fun `selectSlmForDevice should prefer Gemma over Qwen3 with high RAM`() {
        val device = deviceWith(ramGb = 8.0f, highPerf = true)
        val result = selectSlmForDevice(device)
        assertNotNull(result)
        assertEquals(SlmTier.GEMMA4_E2B_Q8_0, result)
    }

    // ── Tier 1: Gemma 4 E2B Q8_0 with 8GB+ and high-perf CPU ────────

    @Test
    fun `selectSlmForDevice should pick Gemma Q8_0 with 8GB and high-perf CPU`() {
        val device = deviceWith(ramGb = 8.0f, highPerf = true)
        assertEquals(SlmTier.GEMMA4_E2B_Q8_0, selectSlmForDevice(device))
    }

    // ── Mali GPU devices now get premium tiers (fix for Qualcomm-only gate) ──

    @Test
    fun `selectSlmForDevice should pick Gemma Q8_0 with Mali GPU and i8mm`() {
        // Galaxy S24 Exynos, Pixel 9 Pro Tensor: Mali GPU + i8mm+dotprod
        // Before fix: excluded by Adreno-only gate. After fix: correctly selected
        // based on CPU features (i8mm+dotprod) regardless of GPU vendor.
        val device = deviceWith(ramGb = 8.0f, highPerf = true)
        assertEquals(SlmTier.GEMMA4_E2B_Q8_0, selectSlmForDevice(device))
    }

    // ── Device without CPU features can't access premium tiers ──

    @Test
    fun `selectSlmForDevice should NOT pick Q8_0 with 4GB but no CPU features`() {
        // Older or budget phones: enough RAM but missing i8mm+dotprod
        // The test helper sets all CPU features to false when highPerf=false.
        val device = deviceWith(ramGb = 4.0f, highPerf = false)
        assertNotEquals(SlmTier.QWEN3_1_7B_Q8_0, selectSlmForDevice(device))
        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, selectSlmForDevice(device))
    }

    // ── Tier 2: Gemma 4 E2B Q4_K_M with 6GB RAM ────────────────────

    @Test
    fun `selectSlmForDevice should pick Gemma Q4_K_M with 6GB`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = false)
        assertEquals(SlmTier.GEMMA4_E2B_Q4_K_M, selectSlmForDevice(device))
    }

    @Test
    fun `selectSlmForDevice should pick Gemma Q4_K_M with 6GB even with CPU features`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = true)
        assertEquals(SlmTier.GEMMA4_E2B_Q4_K_M, selectSlmForDevice(device))
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
        val device = deviceWith(ramGb = 4.0f, highPerf = true)
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
    fun `explainTierSelection should mention CPU requirement for Qwen3 Q8_0`() {
        val device = deviceWith(ramGb = 4.0f, highPerf = false)
        val explanation = explainTierSelection(SlmTier.QWEN3_1_7B_Q8_0, device, isSelected = false)
        assertTrue(explanation.contains("i8mm") || explanation.contains("CPU") || explanation.contains("dotprod"))
    }

    @Test
    fun `explainTierSelection should say higher-priority for available but not selected`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = true)
        val explanation = explainTierSelection(SlmTier.QWEN3_1_7B_Q8_0, device, isSelected = false)
        assertTrue(explanation.contains("higher-priority") || explanation.contains("Available"))
    }

    // ── ALL_TIERS ordering ────────────────────────────────────────────

    @Test
    fun `ALL_TIERS should be ordered by priority`() {
        val tiers = SlmTier.ALL_TIERS
        assertEquals(5, tiers.size)
        assertEquals(SlmTier.GEMMA4_E2B_Q8_0, tiers[0])
        assertEquals(SlmTier.GEMMA4_E2B_Q4_K_M, tiers[1])
        assertEquals(SlmTier.QWEN3_1_7B_Q8_0, tiers[2])
        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, tiers[3])
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

    @Test
    fun `selectSlmForDevice should pick Qwen3-1_7B Q8_0 with 4GB RAM and high performance`() {
        val device = deviceWith(ramGb = 4.0f, highPerf = true)
        assertEquals(SlmTier.QWEN3_1_7B_Q8_0, selectSlmForDevice(device))
    }

    @Test
    fun `selectSlmForDevice should pick Qwen3-1_7B Q4_K_M at exactly 3_5GB RAM boundary`() {
        val device = deviceWith(ramGb = 3.5f, highPerf = false)
        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, selectSlmForDevice(device))
    }

    @Test
    fun `selectSlmForDevice should pick Qwen3-1_7B Q4_K_M at exactly 4_0GB RAM boundary without high performance`() {
        val device = deviceWith(ramGb = 4.0f, highPerf = false)
        assertEquals(SlmTier.QWEN3_1_7B_Q4_K_M, selectSlmForDevice(device))
    }

    @Test
    fun `explainTierSelection should say Selected for Gemma Q4_K_M`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = false)
        val explanation = explainTierSelection(SlmTier.GEMMA4_E2B_Q4_K_M, device, isSelected = true)
        assertTrue(explanation.contains("Selected") && explanation.contains("no i8mm+dotprod"))
    }

    @Test
    fun `explainTierSelection should say Selected with CPU info for Gemma Q4_K_M with CPU features`() {
        val device = deviceWith(ramGb = 6.0f, highPerf = true)
        val explanation = explainTierSelection(SlmTier.GEMMA4_E2B_Q4_K_M, device, isSelected = true)
        assertTrue(explanation.contains("Selected") && explanation.contains("CPU features (i8mm+dotprod) detected"))
    }

    @Test
    fun `SlmTier properties should match configuration specifications`() {
        val tier = SlmTier.GEMMA4_E2B_Q8_0
        assertEquals("gemma4-e2b-q8_0", tier.id)
        assertEquals("gemma-4-E2B-it-Q8_0.gguf", tier.modelFile)
        assertEquals(5170, tier.sizeMb)
        assertEquals(8.0f, tier.minRamGb)
        assertEquals("https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q8_0.gguf", tier.downloadUrl)
        assertEquals("gemma4", tier.family)
    }
}
