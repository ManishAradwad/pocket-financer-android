package com.pocketfinancer.hardware

/**
 * Defines available SLM tiers and auto-selects the best one for the
 * device's hardware capabilities.
 *
 * Selection priority: quality > speed > reliability
 * - GPU-accelerated devices get the largest quant
 * - Lower-RAM devices fall back to smaller models
 */
data class SlmTier(
    val id: String,
    val name: String,
    val modelFile: String,
    val description: String,
    val sizeGb: Float,
    val minRamGb: Float,
    val requiresGpu: Boolean,
    val downloadUrl: String
) {
    companion object {
        /** 1.7B Q8_0 — best quality, needs 4GB+ RAM + GPU accel recommended */
        val QWEN3_1_7B_Q8_0 = SlmTier(
            id = "qwen3-1.7b-q8_0",
            name = "Qwen3-1.7B Q8_0",
            modelFile = "qwen3-1.7b-q8_0.gguf",
            description = "~1.9 GB · 8-bit quant · Best quality",
            sizeGb = 1.9f,
            minRamGb = 4.0f,
            requiresGpu = false,  // can run CPU-only but slow
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf"
        )

        /** 1.7B Q4_K_M — good quality, lighter, 3.5GB+ RAM */
        val QWEN3_1_7B_Q4_K_M = SlmTier(
            id = "qwen3-1.7b-q4_k_m",
            name = "Qwen3-1.7B Q4_K_M",
            modelFile = "qwen3-1.7b-q4_k_m.gguf",
            description = "~1.1 GB · 4-bit quant · Balanced",
            sizeGb = 1.1f,
            minRamGb = 3.5f,
            requiresGpu = false,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf"
        )

        /** 0.6B Q8_0 — lightweight fallback, 2.5GB+ RAM */
        val QWEN3_0_6B_Q8_0 = SlmTier(
            id = "qwen3-0.6b-q8_0",
            name = "Qwen3-0.6B Q8_0",
            modelFile = "qwen3-0.6b-q8_0.gguf",
            description = "~0.7 GB · 8-bit quant · Lightweight fallback",
            sizeGb = 0.7f,
            minRamGb = 2.5f,
            requiresGpu = false,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf"
        )

        /** All tiers in priority order (highest quality first). */
        val ALL_TIERS = listOf(QWEN3_1_7B_Q8_0, QWEN3_1_7B_Q4_K_M, QWEN3_0_6B_Q8_0)
    }
}

/**
 * Auto-select the best SLM for the given hardware capabilities.
 *
 * Logic (priority order):
 * 1. 4GB+ RAM + GPU acceleration → Q8_0 (best quality, GPU handles it)
 * 2. 4GB+ RAM without GPU accel → Q4_K_M (conservative: no GPU help)
 * 3. 3.5GB+ RAM → Q4_K_M
 * 4. 2.5GB+ RAM → 0.6B Q8_0 (fallback)
 * 5. Below 2.5GB → null (BLOCKED)
 *
 * @return The selected [SlmTier] or null if no tier is viable.
 */
fun selectSlmForDevice(device: DeviceCapabilities.DeviceInfo): SlmTier? {
    val ram = device.ramGb
    val gpuAccel = device.isGpuAccelerationSupported

    return when {
        ram >= 4.0f && gpuAccel -> SlmTier.QWEN3_1_7B_Q8_0
        ram >= 4.0f               -> SlmTier.QWEN3_1_7B_Q4_K_M
        ram >= 3.5f               -> SlmTier.QWEN3_1_7B_Q4_K_M
        ram >= 2.5f               -> SlmTier.QWEN3_0_6B_Q8_0
        else                      -> null
    }
}

/**
 * Explain why a specific tier was or wasn't selected.
 */
fun explainTierSelection(
    tier: SlmTier,
    device: DeviceCapabilities.DeviceInfo,
    isSelected: Boolean
): String {
    val ram = device.ramGb
    val gpuAccel = device.isGpuAccelerationSupported

    if (isSelected) {
        return buildString {
            append("Selected — ")
            when (tier) {
                SlmTier.QWEN3_1_7B_Q8_0 -> append("best quality (8-bit), GPU acceleration available")
                SlmTier.QWEN3_1_7B_Q4_K_M -> {
                    if (gpuAccel) append("balanced quality (4-bit) with GPU support")
                    else append("balanced quality (4-bit) — no GPU acceleration detected")
                }
                SlmTier.QWEN3_0_6B_Q8_0 -> append("lightweight fallback for low-RAM devices")
                else -> append("auto-selected based on hardware")
            }
        }
    } else {
        val blockers = mutableListOf<String>()
        if (ram < tier.minRamGb) {
            blockers.add("RAM below minimum (${"%.1f".format(ram)}GB < ${"%.1f".format(tier.minRamGb)}GB)")
        }
        if (tier.requiresGpu && !gpuAccel) {
            blockers.add("GPU acceleration required but not available")
        }
        return if (blockers.isNotEmpty()) {
            "Not available: ${blockers.joinToString("; ")}"
        } else {
            "Available but a higher-quality tier was selected"
        }
    }
}
