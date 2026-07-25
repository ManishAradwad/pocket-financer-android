package com.pocketfinancer.ui.home

import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.selectSlmForDevice

/**
 * The model offered after onboarding, together with whether it came from the
 * debug-emulator test seam rather than the production hardware policy.
 */
internal data class ModelUpgradeTarget(
    val tier: SlmTier?,
    val isDebugEmulatorOverride: Boolean = false
)

/**
 * Keeps release recommendations tied to native backends that are actually
 * accelerated, while allowing the upgrade UI and activation path to be
 * exercised on a roomy debug emulator.
 *
 * The emulator override is deliberately capped at the 1.7B Q4 model. Host CPU
 * flags do not prove that equivalent optimized kernels were compiled into the
 * Android x86_64 binary, so they must not alter production eligibility.
 */
internal fun selectModelUpgradeTarget(
    device: DeviceCapabilities.DeviceInfo,
    allowDebugEmulatorOverride: Boolean
): ModelUpgradeTarget {
    val productionTarget = selectSlmForDevice(device)
    if (!allowDebugEmulatorOverride ||
        productionTarget != SlmTier.DEFAULT_ONBOARDING_SLM
    ) {
        return ModelUpgradeTarget(productionTarget)
    }

    val debugTier = SlmTier.QWEN3_1_7B_Q4_K_M
    val downloadBytes = debugTier.sizeMb.toLong() * BYTES_PER_MEBIBYTE
    val canExerciseUpgrade =
        device.ramGb >= debugTier.minRamGb &&
            device.storage.canFit(downloadBytes)

    return if (canExerciseUpgrade) {
        ModelUpgradeTarget(
            tier = debugTier,
            isDebugEmulatorOverride = true
        )
    } else {
        ModelUpgradeTarget(productionTarget)
    }
}

/**
 * Upgrade visibility follows the durably selected tier, not whether a target
 * file happens to exist. A downloaded-but-unapplied or failed target therefore
 * remains discoverable and retryable.
 */
internal fun isHigherQualityModel(
    current: SlmTier?,
    target: SlmTier?
): Boolean {
    target ?: return false
    if (current == null) return true

    val currentRank = SlmTier.ALL_TIERS.indexOf(current)
    val targetRank = SlmTier.ALL_TIERS.indexOf(target)
    return targetRank >= 0 && (currentRank < 0 || targetRank < currentRank)
}

private const val BYTES_PER_MEBIBYTE = 1_048_576L
