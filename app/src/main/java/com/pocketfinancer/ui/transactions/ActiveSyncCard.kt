package com.pocketfinancer.ui.transactions

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.SyncSmsItem
import com.pocketfinancer.ui.theme.AppTypography
import com.pocketfinancer.ui.theme.M3_Error
import com.pocketfinancer.ui.theme.M3_ErrorContainer
import com.pocketfinancer.ui.theme.M3_OnSurface
import com.pocketfinancer.ui.theme.M3_OnSurfaceVariant
import com.pocketfinancer.ui.theme.M3_OutlineVariant
import com.pocketfinancer.ui.theme.M3_Pos
import com.pocketfinancer.ui.theme.M3_PosContainer
import com.pocketfinancer.ui.theme.M3_Primary
import com.pocketfinancer.ui.theme.M3_Surface
import com.pocketfinancer.ui.theme.M3_SurfaceContainerLow
import com.pocketfinancer.ui.theme.M3_SurfaceContainerLowest
import com.pocketfinancer.ui.theme.PocketFinancerTheme

internal enum class ActiveSyncCardTone {
    PROCESSING,
    SUCCESS,
    ISSUE
}

internal data class ActiveSyncCardUiModel(
    val tone: ActiveSyncCardTone,
    val title: String,
    val detail: String,
    val badge: String,
    val stepLabel: String,
    val stepValue: String,
    val actionLabel: String,
    val stateDescription: String
)

/**
 * Selects the item represented by the compact transaction-screen monitor.
 *
 * During a run this follows the active queue index, with a status-based fallback
 * for a briefly inconsistent index. Once the run ends, failures and interrupted
 * items take priority so the card never hides a result that needs attention.
 */
internal fun HomeSyncState.syncCardItem(): SyncSmsItem? = when (status) {
    HomeSyncState.Status.IDLE,
    HomeSyncState.Status.SCANNING -> null
    HomeSyncState.Status.SYNCING,
    HomeSyncState.Status.CANCELLING -> {
        currentIndex?.let { queue.getOrNull(it) }
            ?: queue.firstOrNull { it.status == "syncing" }
    }
    HomeSyncState.Status.DONE -> {
        queue.lastOrNull { it.status == "error" }
            ?: queue.lastOrNull {
                it.status != "synced" &&
                    it.status != "filtered_out" &&
                    it.status != "error"
            }
            ?: queue.lastOrNull()
    }
}

internal fun HomeSyncState.toActiveSyncCardUiModel(
    activeSms: SyncSmsItem
): ActiveSyncCardUiModel? {
    if (
        status == HomeSyncState.Status.IDLE ||
        status == HomeSyncState.Status.SCANNING
    ) {
        return null
    }

    if (
        status == HomeSyncState.Status.SYNCING ||
        status == HomeSyncState.Status.CANCELLING
    ) {
        val isStopping = status == HomeSyncState.Status.CANCELLING
        val total = queue.size.coerceAtLeast(1)
        val position = queue
            .indexOfFirst { it.id == activeSms.id }
            .takeIf { it >= 0 }
            ?.plus(1)
            ?: currentIndex
                ?.plus(1)
                ?.coerceIn(1, total)
            ?: 1
        val step = if (isStopping) {
            if (currentStageIndex == 3) {
                "Finishing current save"
            } else {
                "Stopping safely"
            }
        } else {
            when (currentStageIndex) {
                0, null -> "Checking message"
                1 -> if (hasThinkingMode) {
                    "Reasoning on device"
                } else {
                    "Extracting transaction"
                }
                2 -> "Extracting transaction"
                3 -> "Saving transaction"
                else -> "Finishing"
            }
        }
        val title = if (isStopping) {
            "Stopping SMS processing"
        } else if (total == 1) {
            "Processing message"
        } else {
            "Processing message $position of $total"
        }
        val detail = "From ${activeSms.sender}"

        return ActiveSyncCardUiModel(
            tone = ActiveSyncCardTone.PROCESSING,
            title = title,
            detail = detail,
            badge = if (isStopping) "STOPPING" else "LIVE",
            stepLabel = "CURRENT STEP",
            stepValue = step,
            actionLabel = if (isStopping) {
                "View stopping details"
            } else {
                "View live log"
            },
            stateDescription = "$title. $detail. Current step: $step."
        )
    }

    val saved = queue.count { it.status == "synced" }
    val skipped = queue.count { it.status == "filtered_out" }
    val failed = queue.count { it.status == "error" }
    val incomplete = queue.count {
        it.status != "synced" && it.status != "filtered_out" && it.status != "error"
    }
    val hasIssues = failed > 0 || incomplete > 0
    val resultCounts = buildList {
        if (saved > 0) add("$saved saved")
        if (skipped > 0) add("$skipped skipped")
        if (failed > 0) add("$failed failed")
        if (incomplete > 0) add("$incomplete incomplete")
    }.joinToString(" • ").ifEmpty { "No messages processed" }
    val summary = "Queue: $resultCounts"
    val result = when (activeSms.status) {
        "synced" -> "Saved to transaction ledger"
        "filtered_out" -> "Skipped — no transaction found"
        "error" -> "Processing needs attention"
        "pending", "syncing" -> "Sync ended before processing"
        else -> "Result unavailable"
    }
    val title = if (hasIssues) "Sync finished with issues" else "Sync complete"
    val action = if (hasIssues) "Review log" else "View latest log"

    return ActiveSyncCardUiModel(
        tone = if (hasIssues) ActiveSyncCardTone.ISSUE else ActiveSyncCardTone.SUCCESS,
        title = title,
        detail = summary,
        badge = if (hasIssues) "REVIEW" else "DONE",
        stepLabel = "LATEST RESULT",
        stepValue = result,
        actionLabel = action,
        stateDescription = "$title. $summary. $result."
    )
}

@Composable
fun ActiveSyncCard(
    activeSms: SyncSmsItem,
    syncState: HomeSyncState,
    onClick: () -> Unit
) {
    val model = syncState.toActiveSyncCardUiModel(activeSms) ?: return
    val accentColor = when (model.tone) {
        ActiveSyncCardTone.PROCESSING -> Color(0xFFF2C94C)
        ActiveSyncCardTone.SUCCESS -> M3_Pos
        ActiveSyncCardTone.ISSUE -> M3_Error
    }
    val containerColor = when (model.tone) {
        ActiveSyncCardTone.PROCESSING -> M3_SurfaceContainerLow
        ActiveSyncCardTone.SUCCESS -> M3_PosContainer.copy(alpha = 0.20f)
        ActiveSyncCardTone.ISSUE -> M3_ErrorContainer.copy(alpha = 0.14f)
    }
    val cardShape = RoundedCornerShape(20.dp)

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) {
                stateDescription = model.stateDescription
                liveRegion = LiveRegionMode.Polite
            },
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.30f)),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 0.dp,
            pressedElevation = 1.dp
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(accentColor.copy(alpha = 0.11f), RoundedCornerShape(12.dp))
                        .border(
                            BorderStroke(1.dp, accentColor.copy(alpha = 0.16f)),
                            RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    when (model.tone) {
                        ActiveSyncCardTone.PROCESSING -> {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = accentColor,
                                strokeWidth = 2.dp
                            )
                        }
                        ActiveSyncCardTone.SUCCESS -> {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        ActiveSyncCardTone.ISSUE -> {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ON-DEVICE PIPELINE",
                        color = accentColor,
                        style = AppTypography.eyebrowBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = model.title,
                        color = M3_OnSurface,
                        style = AppTypography.titleSmallBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = model.detail,
                        color = M3_OnSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = model.badge,
                    color = accentColor,
                    style = AppTypography.eyebrowBold,
                    maxLines = 1,
                    modifier = Modifier
                        .background(accentColor.copy(alpha = 0.12f), RoundedCornerShape(100))
                        .border(
                            BorderStroke(1.dp, accentColor.copy(alpha = 0.16f)),
                            RoundedCornerShape(100)
                        )
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        M3_SurfaceContainerLowest.copy(alpha = 0.72f),
                        RoundedCornerShape(12.dp)
                    )
                    .border(
                        BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.18f)),
                        RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(accentColor.copy(alpha = 0.09f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Sms,
                        contentDescription = null,
                        tint = accentColor.copy(alpha = 0.90f),
                        modifier = Modifier.size(14.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = activeSms.sender,
                        color = M3_OnSurface,
                        style = AppTypography.eyebrowBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(1.dp))
                    Text(
                        text = activeSms.body,
                        color = M3_OnSurfaceVariant,
                        style = AppTypography.monoBody,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.20f))
            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = model.stepLabel,
                        color = M3_OnSurfaceVariant.copy(alpha = 0.78f),
                        style = AppTypography.eyebrow
                    )
                    Text(
                        text = model.stepValue,
                        color = accentColor,
                        style = AppTypography.bodySmallBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    modifier = Modifier
                        .heightIn(min = 40.dp)
                        .background(M3_Primary.copy(alpha = 0.10f), RoundedCornerShape(100))
                        .border(
                            BorderStroke(1.dp, M3_Primary.copy(alpha = 0.16f)),
                            RoundedCornerShape(100)
                        )
                        .padding(horizontal = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = model.actionLabel,
                        color = M3_Primary,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1
                    )
                    Icon(
                        imageVector = Icons.Rounded.ChevronRight,
                        contentDescription = null,
                        tint = M3_Primary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

private val previewSms = SyncSmsItem(
    id = "preview",
    sender = "HDFC Bank",
    body = "Dear Customer, Your a/c no. XXXXXXXX6254 was debited by ₹1,249 at LOCAL MERCHANT.",
    date = 0L,
    status = "synced",
    parsedAmount = 1249.0,
    parsedMerchant = "Local Merchant"
)

@Preview(
    name = "Sync complete",
    widthDp = 360,
    showBackground = true,
    backgroundColor = 0xFF111318
)
@Composable
private fun ActiveSyncCardCompletePreview() {
    val state = HomeSyncState(
        status = HomeSyncState.Status.DONE,
        queue = listOf(previewSms)
    )
    PocketFinancerTheme {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(M3_Surface)
                .padding(vertical = 8.dp)
        ) {
            ActiveSyncCard(
                activeSms = previewSms,
                syncState = state,
                onClick = {}
            )
        }
    }
}

@Preview(
    name = "Sync running — narrow",
    widthDp = 320,
    fontScale = 1.3f,
    showBackground = true,
    backgroundColor = 0xFF111318
)
@Composable
private fun ActiveSyncCardRunningPreview() {
    val runningSms = previewSms.copy(status = "syncing")
    val state = HomeSyncState(
        status = HomeSyncState.Status.SYNCING,
        queue = listOf(runningSms, previewSms.copy(id = "queued", status = "pending")),
        currentIndex = 0,
        currentStageIndex = 2
    )
    PocketFinancerTheme {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(M3_Surface)
                .padding(vertical = 8.dp)
        ) {
            ActiveSyncCard(
                activeSms = runningSms,
                syncState = state,
                onClick = {}
            )
        }
    }
}
