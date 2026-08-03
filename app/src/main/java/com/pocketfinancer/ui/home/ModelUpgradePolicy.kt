package com.pocketfinancer.ui.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.selectSlmForDevice
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager

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
 * Keeps the debug-emulator test seam identical anywhere an upgrade can start.
 */
internal fun allowDebugEmulatorModelUpgrade(context: Context): Boolean =
    (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
        (
            Build.FINGERPRINT.startsWith("generic") ||
                Build.FINGERPRINT.startsWith("unknown") ||
                Build.MODEL.contains("Emulator", ignoreCase = true) ||
                Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
                Build.PRODUCT.contains("sdk", ignoreCase = true)
            )

/**
 * Explains why a new upgrade cannot start. Running upgrades normally own their
 * own UI state, but reporting them here also closes click-time races.
 */
internal fun modelUpgradeStartBlockedMessage(
    onboarding: OnboardingSyncManager.OnboardingSyncState,
    otherFlowBusy: Boolean = false
): String? = when {
    onboarding.isRunning &&
        onboarding.runPurpose == OnboardingSyncManager.RunPurpose.INITIAL_SETUP ->
        "Finish the current setup or history import before starting a model upgrade."
    onboarding.isRunning -> "A model upgrade is already running."
    otherFlowBusy ->
        "Finish the current model task before starting a model upgrade."
    else -> null
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
