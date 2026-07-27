package com.pocketfinancer.ui.home

import com.pocketfinancer.setup.SetupEmptyReason
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupPauseReason
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class SetupImportCardModel(
    val eyebrow: String,
    val title: String,
    val body: String,
    val evidence: String? = null,
    val primaryAction: SetupCardAction? = null,
    val primaryLabel: String? = null,
    val showProgress: Boolean = false
)

internal enum class SetupCardAction {
    PREPARE_MODEL,
    RESUME,
    RESTORE_PERMISSION,
    SCAN_OLDER,
    SCAN_RECENT,
    RETRY_RECENT_SYNC
}

internal enum class SetupCardActionTarget {
    START_SETUP,
    SCAN_OLDER,
    RETRY_RECENT_SYNC,
    RESTORE_PERMISSION
}

internal fun SetupCardAction.target(): SetupCardActionTarget = when (this) {
    SetupCardAction.PREPARE_MODEL,
    SetupCardAction.RESUME,
    SetupCardAction.SCAN_RECENT -> SetupCardActionTarget.START_SETUP
    SetupCardAction.SCAN_OLDER -> SetupCardActionTarget.SCAN_OLDER
    SetupCardAction.RETRY_RECENT_SYNC ->
        SetupCardActionTarget.RETRY_RECENT_SYNC
    SetupCardAction.RESTORE_PERMISSION ->
        SetupCardActionTarget.RESTORE_PERMISSION
}

internal fun manualRecentSyncAvailable(
    status: SetupImportStatus
): Boolean = status == SetupImportStatus.READY ||
    status == SetupImportStatus.READY_NO_HISTORY

internal fun setupImportCardModel(
    state: SetupImportState,
    nowMillis: Long = System.currentTimeMillis()
): SetupImportCardModel {
    val needsExplicitModelPreparation =
        !state.modelPrepared &&
            !state.modelDownloadConfirmed &&
            !state.isActive &&
            state.status != SetupImportStatus.PERMISSION_NEEDED
    if (needsExplicitModelPreparation) {
        return SetupImportCardModel(
            eyebrow = "PRIVATE, ON-DEVICE SETUP",
            title = "Prepare the on-device model",
            body = buildString {
                append(
                    "No usable local model is available. Preparing it uses " +
                        "about 700 MB and starts only after you confirm."
                )
                if (state.hasVerifiedCoverage) {
                    append(" Your previously verified SMS coverage is preserved.")
                }
            },
            evidence = coverageDescription(state)
                .takeIf { state.hasVerifiedCoverage },
            primaryAction = SetupCardAction.PREPARE_MODEL,
            primaryLabel = "Prepare local AI"
        )
    }

    val manualError = state.actionableError?.takeIf { error ->
        manualRecentSyncAvailable(state.status) &&
            (
                error.code.startsWith("RECENT_") ||
                    error.code.startsWith("MANUAL_")
                )
    }
    if (manualError != null) {
        return SetupImportCardModel(
            eyebrow = "RECENT SCAN NEEDS ATTENTION",
            title = "The recent SMS scan did not finish",
            body =
                "${manualError.message} Your previously verified coverage is unchanged.",
            evidence = coverageDescription(state),
            primaryAction = SetupCardAction.RETRY_RECENT_SYNC,
            primaryLabel = manualError.actionLabel
                .takeIf { it.isNotBlank() }
                ?: "Try recent scan again"
        )
    }

    return when (state.status) {
    SetupImportStatus.NOT_STARTED -> SetupImportCardModel(
        eyebrow = "PRIVATE, ON-DEVICE SETUP",
        title = "Prepare Pocket Financer when you are ready",
        body = if (state.modelPrepared) {
            "The local model is ready. Start a resumable scan to look for eligible transaction alerts."
        } else {
            "No model has been downloaded. Preparing the local AI uses about 700 MB and starts only after you confirm."
        },
        primaryAction = if (state.modelPrepared) {
            SetupCardAction.RESUME
        } else {
            SetupCardAction.PREPARE_MODEL
        },
        primaryLabel = if (state.modelPrepared) {
            "Start history scan"
        } else {
            "Prepare local AI"
        }
    )

    SetupImportStatus.PERMISSION_NEEDED -> SetupImportCardModel(
        eyebrow = "SMS ACCESS NEEDED",
        title = "Restore SMS access",
        body = "Pocket Financer cannot scan or capture alerts while SMS access is off. Existing encrypted transactions stay available.",
        primaryAction = SetupCardAction.RESTORE_PERMISSION,
        primaryLabel = "Restore access"
    )

    SetupImportStatus.DOWNLOADING -> SetupImportCardModel(
        eyebrow = "MODEL PREPARATION",
        title = "Downloading the on-device model",
        body = "The confirmed download is running in the background. You can keep using the app.",
        showProgress = true
    )

    SetupImportStatus.SCANNING -> SetupImportCardModel(
        eyebrow = "HISTORY DISCOVERY",
        title = when (
            val days =
                state.activeScanWindowDays ?: state.coverageWindowDays
        ) {
            null -> "Checking SMS history"
            in 36_500..Int.MAX_VALUE ->
                "Checking all available SMS history"
            else -> "Checking the last $days days"
        },
        body = "Messages are filtered locally before any model parsing. This state is saved so an interruption is visible.",
        // Counts are reset before provider I/O, while coverage still describes
        // the previous successful window. Showing either as current evidence
        // here would be misleading.
        evidence = null,
        showProgress = true
    )

    SetupImportStatus.PROCESSING -> SetupImportCardModel(
        eyebrow = "LOCAL PROCESSING",
        title = "Checking eligible alerts",
        body = "Each candidate is processed on this device. Saved transactions keep their encrypted source evidence; rejected messages are not retained long-term.",
        evidence =
            "${state.processedCount} of ${state.eligibleCandidateCount} checked · " +
                "${state.savedCount} saved · ${state.rejectedCount} rejected" +
                if (state.failedCount > 0) " · ${state.failedCount} failed" else "",
        showProgress = true
    )

    SetupImportStatus.READY -> {
        if (state.hasVerifiedCoverage) {
            SetupImportCardModel(
                eyebrow = "READY",
                title = if (coverageIsFresh(state, nowMillis)) {
                    "Pocket Financer is up to date"
                } else {
                    "Pocket Financer is ready"
                },
                body = coverageDescription(state),
                evidence = scanEvidence(state),
                primaryAction = SetupCardAction.SCAN_OLDER,
                primaryLabel = "Scan older messages"
            )
        } else {
            SetupImportCardModel(
                eyebrow = "READY · COVERAGE UNKNOWN",
                title = "Your previous setup is preserved",
                body = "This app version cannot verify when the legacy import last succeeded. Run a scan to establish truthful coverage.",
                primaryAction = SetupCardAction.SCAN_RECENT,
                primaryLabel = "Scan recent messages"
            )
        }
    }

    SetupImportStatus.READY_NO_HISTORY -> SetupImportCardModel(
        eyebrow = "READY FOR THE NEXT ALERT",
        title = when (state.emptyReason) {
            SetupEmptyReason.EMPTY_INBOX ->
                "No SMS were found in the checked history"
            SetupEmptyReason.FILTERED_OUT ->
                "Messages were found, but none looked transactional"
            SetupEmptyReason.CANDIDATES_REJECTED ->
                "Potential alerts were checked, but none were saved"
            SetupEmptyReason.NO_ADDITIONAL_MESSAGES ->
                "No additional eligible alerts were found"
            else -> "No eligible transaction history was found"
        },
        body = buildString {
            append(coverageDescription(state))
            append(" Pocket Financer will capture the next eligible alert.")
        },
        evidence = scanEvidence(state),
        primaryAction = SetupCardAction.SCAN_OLDER,
        primaryLabel = "Scan older messages"
    )

    SetupImportStatus.PAUSED -> SetupImportCardModel(
        eyebrow = "SETUP PAUSED",
        title = when (state.pauseReason) {
            SetupPauseReason.INTERRUPTED ->
                "Setup stopped before it finished"
            SetupPauseReason.AUTOMATIC_UPDATES_DISABLED ->
                "Automatic setup work is paused"
            else -> "Setup is paused"
        },
        body = state.actionableError?.message
            ?: "Completed work is still saved. Resume when this device is ready.",
        evidence = scanEvidence(state),
        primaryAction = SetupCardAction.RESUME,
        primaryLabel = state.actionableError?.actionLabel
            ?.takeIf { it.isNotBlank() }
            ?: "Resume setup"
    )

    SetupImportStatus.FAILED -> SetupImportCardModel(
        eyebrow = "SETUP NEEDS ATTENTION",
        title = "Pocket Financer could not finish setup",
        body = state.actionableError?.message
            ?: "The last setup attempt failed before completion.",
        evidence = scanEvidence(state),
        primaryAction = SetupCardAction.RESUME,
        primaryLabel = state.actionableError?.actionLabel
            ?.takeIf { it.isNotBlank() }
            ?: "Try again"
    )
    }
}

private fun coverageDescription(state: SetupImportState): String {
    val historical = when {
        !state.hasVerifiedCoverage ->
            "No verified scan range is available."
        (state.coverageWindowDays ?: 0) >= 36_500 ->
            "All available SMS history through " +
                "${formatCoverageDate(state.coverageEndMillis!!)} was checked."
        else ->
            "SMS history from ${formatCoverageDate(state.coverageStartMillis!!)} " +
                "through ${formatCoverageDate(state.coverageEndMillis!!)} was checked."
    }
    val recentEnd = state.recentCoverageEndMillis
    return if (
        state.hasVerifiedRecentCoverage &&
        recentEnd != null &&
        recentEnd > (state.coverageEndMillis ?: Long.MIN_VALUE)
    ) {
        "$historical A recent ${state.recentScanWindowDays}-day scan through " +
            "${formatCoverageDate(recentEnd)} also succeeded."
    } else {
        historical
    }
}

private fun coverageIsFresh(
    state: SetupImportState,
    nowMillis: Long
): Boolean {
    val coverageEnd = listOfNotNull(
        state.coverageEndMillis,
        state.recentCoverageEndMillis
            .takeIf { state.hasVerifiedRecentCoverage }
    ).maxOrNull() ?: return false
    return coverageEnd >= nowMillis - TimeUnit.HOURS.toMillis(24) &&
        coverageEnd <= nowMillis + TimeUnit.MINUTES.toMillis(5)
}

private fun formatCoverageDate(timestampMillis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault())
        .format(Date(timestampMillis))

private fun scanEvidence(state: SetupImportState): String? {
    if (
        state.hasVerifiedRecentCoverage &&
        (state.recentCoverageEndMillis ?: Long.MIN_VALUE) >=
        (state.coverageEndMillis ?: Long.MIN_VALUE)
    ) {
        return buildString {
            append("Recent scan: ${state.recentProviderMessageCount} messages checked")
            append(" Â· ${state.recentEligibleCandidateCount} eligible")
        }
    }
    if (!state.hasVerifiedCoverage && state.providerMessageCount == 0) return null
    return buildString {
        append("${state.providerMessageCount} messages checked")
        append(" · ${state.eligibleCandidateCount} eligible")
        if (state.savedCount > 0) append(" · ${state.savedCount} saved")
    }
}
