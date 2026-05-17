package com.pocketfinancer.hardware

/**
 * Defines available SLM tiers and auto-selects the best one for the
 * device's hardware capabilities.
 *
 * Priority: Qwen3 > Gemma4 (Qwen3 has thinking-mode which is critical
 * for SMS extraction accuracy). Within each family, higher quant = better
 * quality but requires more RAM.
 *
 * HuggingFace GGUF repos:
 *   Qwen3:  unsloth/Qwen3-1.7B-GGUF
 *   Gemma4: unsloth/gemma-4-E2B-it-GGUF
 */
data class SlmTier(
    val id: String,
    val name: String,
    val modelFile: String,
    val description: String,
    val sizeMb: Int,           // approximate GGUF size in MB
    val minRamGb: Float,       // minimum RAM to run
    val downloadUrl: String,
    val family: String = "",   // "qwen3" or "gemma4"
    val hasThinkingMode: Boolean = false
) {
    val sizeGb: Float get() = sizeMb / 1024f

    companion object {
        // ═══════════════════════════════════════════════════════════════
        // Qwen3-1.7B — best extraction quality (thinking mode)
        // ═══════════════════════════════════════════════════════════════

        /** Qwen3-1.7B Q8_0: ~1950 MB, best quality. Needs 4GB+ RAM, GPU recommended. */
        val QWEN3_1_7B_Q8_0 = SlmTier(
            id = "qwen3-1.7b-q8_0",
            name = "Qwen3-1.7B Q8_0",
            modelFile = "Qwen3-1.7B-Q8_0.gguf",
            description = "~1.9 GB · 8-bit quant · Best extraction quality",
            sizeMb = 1950,
            minRamGb = 4.0f,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q8_0.gguf",
            family = "qwen3",
            hasThinkingMode = true
        )

        /** Qwen3-1.7B Q4_K_M: ~1100 MB, good quality. 3.5GB+ RAM. */
        val QWEN3_1_7B_Q4_K_M = SlmTier(
            id = "qwen3-1.7b-q4_k_m",
            name = "Qwen3-1.7B Q4_K_M",
            modelFile = "Qwen3-1.7B-Q4_K_M.gguf",
            description = "~1.1 GB · 4-bit quant · Balanced size/quality",
            sizeMb = 1136,
            minRamGb = 3.5f,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            family = "qwen3",
            hasThinkingMode = true
        )

        // ═══════════════════════════════════════════════════════════════
        // Gemma 4 E2B — alternative, no thinking mode but newer arch
        // ═══════════════════════════════════════════════════════════════

        /** Gemma 4 E2B Q8_0: ~5170 MB. 6GB+ RAM, GPU recommended. */
        val GEMMA4_E2B_Q8_0 = SlmTier(
            id = "gemma4-e2b-q8_0",
            name = "Gemma 4 E2B Q8_0",
            modelFile = "gemma-4-E2B-it-Q8_0.gguf",
            description = "~5.0 GB · 8-bit quant · Highest overall quality",
            sizeMb = 5170,
            minRamGb = 6.0f,
            downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q8_0.gguf",
            family = "gemma4",
            hasThinkingMode = false
        )

        /** Gemma 4 E2B Q4_K_M: ~3180 MB. 4GB+ RAM. */
        val GEMMA4_E2B_Q4_K_M = SlmTier(
            id = "gemma4-e2b-q4_k_m",
            name = "Gemma 4 E2B Q4_K_M",
            modelFile = "gemma-4-E2B-it-Q4_K_M.gguf",
            description = "~3.1 GB · 4-bit quant · Alternative model family",
            sizeMb = 3180,
            minRamGb = 4.0f,
            downloadUrl = "https://huggingface.co/unsloth/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_K_M.gguf",
            family = "gemma4",
            hasThinkingMode = false
        )

        // ═══════════════════════════════════════════════════════════════
        // Fallback
        // ═══════════════════════════════════════════════════════════════

        /** Qwen3-0.6B Q8_0: ~700 MB. 2.5GB+ RAM. Lightweight fallback. */
        val QWEN3_0_6B_Q8_0 = SlmTier(
            id = "qwen3-0.6b-q8_0",
            name = "Qwen3-0.6B Q8_0",
            modelFile = "Qwen3-0.6B-Q8_0.gguf",
            description = "~0.7 GB · 8-bit quant · Lightweight fallback",
            sizeMb = 700,
            minRamGb = 2.5f,
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-0.6B-Q8_0.gguf",
            family = "qwen3",
            hasThinkingMode = true
        )

        /** All tiers in priority order (best extraction first). */
        val ALL_TIERS = listOf(
            QWEN3_1_7B_Q8_0,
            QWEN3_1_7B_Q4_K_M,
            GEMMA4_E2B_Q8_0,
            GEMMA4_E2B_Q4_K_M,
            QWEN3_0_6B_Q8_0
        )
    }
}

/**
 * Auto-select the best SLM for the given hardware capabilities.
 *
 * Priority logic:
 * 1. Qwen3-1.7B Q8_0  — if 4GB+ RAM AND high-performance CPU (i8mm+dotprod)
 * 2. Qwen3-1.7B Q4_K_M — if 3.5GB+ RAM (thinking mode, smaller download)
 * 3. Gemma 4 E2B Q8_0  — if 6GB+ RAM AND high-performance CPU (highest quality)
 * 4. Gemma 4 E2B Q4_K_M — if 4GB+ RAM (alternative model family)
 * 5. Qwen3-0.6B Q8_0   — if 2.5GB+ RAM (lightweight fallback)
 * 6. null               — if < 2.5GB RAM (BLOCKED)
 *
 * The "high-performance CPU" flag checks for ARM i8mm and dotprod
 * instructions. These CPU features meaningfully accelerate LLM inference
 * on CPU. GPU acceleration is NOT used: the llama.cpp Vulkan/OpenCL
 * backends are 10-15× slower than CPU on Android across all GPU vendors,
 * so model tier selection is based on CPU capability instead.
 *
 * Qwen3 tiers are preferred over Gemma because Qwen3's thinking mode
 * significantly improves SMS extraction accuracy over standard decode.
 */
fun selectSlmForDevice(device: DeviceCapabilities.DeviceInfo): SlmTier? {
    val ram = device.ramGb
    val gpuAccel = device.isGpuAccelerationSupported

    return when {
        // Tier 1: Best Qwen3 quality with GPU
        ram >= 4.0f && gpuAccel -> SlmTier.QWEN3_1_7B_Q8_0

        // Tier 2: Balanced Qwen3 (thinking mode, reasonable size)
        ram >= 3.5f -> SlmTier.QWEN3_1_7B_Q4_K_M

        // Tier 3: Gemma high quality — only if 6GB+
        ram >= 6.0f && gpuAccel -> SlmTier.GEMMA4_E2B_Q8_0

        // Tier 4: Gemma balanced
        ram >= 4.0f -> SlmTier.GEMMA4_E2B_Q4_K_M

        // Tier 5: Lightweight fallback
        ram >= 2.5f -> SlmTier.QWEN3_0_6B_Q8_0

        else -> null
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
    val ramStr = "%.1f".format(ram)

    if (isSelected) {
        return when {
            tier == SlmTier.QWEN3_1_7B_Q8_0 ->
                "Selected — best extraction quality (8-bit thinking mode, high-perf CPU)"
            tier == SlmTier.QWEN3_1_7B_Q4_K_M -> {
                val reason = if (gpuAccel) "CPU features (i8mm+dotprod) detected but RAM prefers lighter quant"
                             else "balanced size/quality — no i8mm+dotprod CPU features detected"
                "Selected — $reason"
            }
            tier == SlmTier.GEMMA4_E2B_Q8_0 ->
                "Selected — highest overall model quality, high-perf CPU"
            tier == SlmTier.GEMMA4_E2B_Q4_K_M ->
                "Selected — alternative model family, Qwen3 tiers not viable"
            tier == SlmTier.QWEN3_0_6B_Q8_0 ->
                "Selected — lightweight fallback for low-RAM devices"
            else -> "Selected — auto-picked based on hardware"
        }
    }

    val blockers = mutableListOf<String>()
    if (ram < tier.minRamGb) {
        blockers.add("needs ≥${"%.1f".format(tier.minRamGb)}GB RAM (you have ${ramStr}GB)")
    }

    when (tier) {
        SlmTier.QWEN3_1_7B_Q8_0 -> {
            if (!gpuAccel) blockers.add("needs i8mm+dotprod CPU instructions (not detected)")
        }
        SlmTier.GEMMA4_E2B_Q8_0 -> {
            if (!gpuAccel) blockers.add("needs i8mm+dotprod CPU instructions (not detected)")
        }
        else -> {}
    }

    if (blockers.isNotEmpty()) {
        return "Not available: ${blockers.joinToString("; ")}"
    }

    // No blockers but still not selected → a higher-priority tier was picked
    return "Available but a higher-priority tier was selected"
}
