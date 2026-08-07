package com.pocketfinancer.ui.smsprocessing

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Sms
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.pocketfinancer.ui.theme.AppTypography
import com.pocketfinancer.ui.theme.M3_Error
import com.pocketfinancer.ui.theme.M3_ErrorContainer
import com.pocketfinancer.ui.theme.M3_OnSurface
import com.pocketfinancer.ui.theme.M3_OnSurfaceVariant
import com.pocketfinancer.ui.theme.M3_OutlineVariant
import com.pocketfinancer.ui.theme.M3_Pos
import com.pocketfinancer.ui.theme.M3_PosContainer
import com.pocketfinancer.ui.theme.M3_Primary
import com.pocketfinancer.ui.theme.M3_SurfaceContainerLow
import com.pocketfinancer.ui.theme.M3_SurfaceContainerLowest
import com.pocketfinancer.ui.theme.PocketFinancerTheme

/**
 * Shared lightweight monitor for every user-initiated SMS processing path.
 * The card is intentionally not clickable: Inspect and Stop are independent,
 * accessible controls and both return the exact target rendered by this card.
 */
@Composable
fun SmsPipelineActivityCard(
    model: SmsPipelineCardUiModel,
    onInspect: (SmsProcessingTarget) -> Unit,
    onStop: (SmsProcessingTarget) -> Unit,
    modifier: Modifier = Modifier
) {
    val accentColor = when (model.tone) {
        SmsPipelineTone.PROCESSING -> Color(0xFFF2C94C)
        SmsPipelineTone.SUCCESS -> M3_Pos
        SmsPipelineTone.ISSUE -> M3_Error
    }
    val containerColor = when (model.tone) {
        SmsPipelineTone.PROCESSING -> M3_SurfaceContainerLow
        SmsPipelineTone.SUCCESS -> M3_PosContainer.copy(alpha = 0.20f)
        SmsPipelineTone.ISSUE -> M3_ErrorContainer.copy(alpha = 0.14f)
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                stateDescription = model.accessibilityText
                liveRegion = LiveRegionMode.Polite
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        border = BorderStroke(1.dp, accentColor.copy(alpha = 0.30f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val useStackedLayout =
                maxWidth < 340.dp || LocalDensity.current.fontScale >= 1.3f

            Column(modifier = Modifier.padding(14.dp)) {
                PipelineHeader(
                    model = model,
                    accentColor = accentColor,
                    stacked = useStackedLayout
                )

                if (model.source is SmsSourcePreview.Message) {
                    Spacer(modifier = Modifier.height(12.dp))
                    SourcePreview(
                        source = model.source,
                        accentColor = accentColor
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(
                    color = M3_OutlineVariant.copy(alpha = 0.20f)
                )
                Spacer(modifier = Modifier.height(10.dp))

                if (useStackedLayout) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        PipelineStep(model = model, accentColor = accentColor)
                        PipelineControls(
                            model = model,
                            onInspect = onInspect,
                            onStop = onStop,
                            fillWidth = true
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(modifier = Modifier.weight(1f)) {
                            PipelineStep(model = model, accentColor = accentColor)
                        }
                        PipelineControls(
                            model = model,
                            onInspect = onInspect,
                            onStop = onStop,
                            fillWidth = false
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PipelineHeader(
    model: SmsPipelineCardUiModel,
    accentColor: Color,
    stacked: Boolean
) {
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
                SmsPipelineTone.PROCESSING -> CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = accentColor,
                    strokeWidth = 2.dp
                )

                SmsPipelineTone.SUCCESS -> Icon(
                    imageVector = Icons.Rounded.Check,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )

                SmsPipelineTone.ISSUE -> Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "ON-DEVICE PIPELINE",
                color = accentColor,
                style = AppTypography.eyebrowBold
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = model.title,
                color = M3_OnSurface,
                style = AppTypography.titleSmallBold,
                maxLines = if (stacked) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = model.detail,
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (stacked) 3 else 2,
                overflow = TextOverflow.Ellipsis
            )
            if (stacked) {
                Spacer(modifier = Modifier.height(6.dp))
                PipelineBadge(model.badge, accentColor)
            }
        }

        if (!stacked) {
            PipelineBadge(model.badge, accentColor)
        }
    }
}

@Composable
private fun PipelineBadge(label: String, accentColor: Color) {
    Text(
        text = label,
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

@Composable
private fun SourcePreview(
    source: SmsSourcePreview.Message,
    accentColor: Color
) {
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
                text = source.sender.ifBlank { "Unknown sender" },
                color = M3_OnSurface,
                style = AppTypography.eyebrowBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(1.dp))
            Text(
                text = source.body,
                color = M3_OnSurfaceVariant,
                style = AppTypography.monoBody,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun PipelineStep(model: SmsPipelineCardUiModel, accentColor: Color) {
    Column {
        Text(
            text = model.stepLabel,
            color = M3_OnSurfaceVariant.copy(alpha = 0.78f),
            style = AppTypography.eyebrow
        )
        Text(
            text = model.stepValue,
            color = accentColor,
            style = AppTypography.bodySmallBold,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PipelineControls(
    model: SmsPipelineCardUiModel,
    onInspect: (SmsProcessingTarget) -> Unit,
    onStop: (SmsProcessingTarget) -> Unit,
    fillWidth: Boolean
) {
    val target = model.target
    val modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        horizontalAlignment = Alignment.End
    ) {
        if (model.inspectState == SmsInspectUiState.AVAILABLE && target != null) {
            OutlinedButton(
                onClick = { onInspect(target) },
                modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                    .defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = M3_Primary
                ),
                border = BorderStroke(1.dp, M3_Primary.copy(alpha = 0.28f))
            ) {
                Text(model.inspectLabel, maxLines = 2)
                Spacer(modifier = Modifier.width(2.dp))
                Icon(
                    imageVector = Icons.Rounded.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        if (model.stopState != SmsStopUiState.HIDDEN && target != null) {
            val stopAvailable = model.stopState == SmsStopUiState.AVAILABLE
            OutlinedButton(
                onClick = { onStop(target) },
                enabled = stopAvailable,
                modifier = (if (fillWidth) Modifier.fillMaxWidth() else Modifier)
                    .defaultMinSize(minHeight = 48.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = M3_Error,
                    disabledContentColor = M3_OnSurfaceVariant
                ),
                border = BorderStroke(
                    1.dp,
                    if (stopAvailable) {
                        M3_Error.copy(alpha = 0.55f)
                    } else {
                        M3_OutlineVariant
                    }
                )
            ) {
                Icon(
                    imageVector = if (stopAvailable) {
                        Icons.Rounded.StopCircle
                    } else {
                        Icons.Rounded.HourglassTop
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    when (model.stopState) {
                        SmsStopUiState.AVAILABLE -> "Stop"
                        SmsStopUiState.STOPPING -> "Stopping safely…"
                        SmsStopUiState.COMMIT_UNAVAILABLE -> "Finishing save…"
                        SmsStopUiState.HIDDEN -> ""
                    },
                    maxLines = 2
                )
            }
        }
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun SmsPipelineActivityCardPreview() {
    PocketFinancerTheme {
        SmsPipelineActivityCard(
            model = SmsPipelineCardUiModel(
                target = SmsProcessingTarget.ManualRecent("preview-run", "preview-sms"),
                phase = SmsPipelinePhase.PROCESSING,
                tone = SmsPipelineTone.PROCESSING,
                title = "Processing message 2 of 4",
                detail = "From VK-AMEXIN",
                badge = "LIVE",
                source = SmsSourcePreview.Message(
                    sender = "VK-AMEXIN",
                    body = "You have spent INR 89,450.75 on your card ending 9876."
                ),
                stepLabel = "CURRENT STEP",
                stepValue = "Checking message",
                inspectState = SmsInspectUiState.AVAILABLE,
                inspectLabel = "Inspect",
                stopState = SmsStopUiState.AVAILABLE,
                accessibilityText =
                    "Processing message 2 of 4. Current step: Checking message."
            ),
            onInspect = {},
            onStop = {}
        )
    }
}

@Preview(widthDp = 320, fontScale = 2f, showBackground = true)
@Composable
private fun SmsPipelineActivityCardLargeTextPreview() {
    PocketFinancerTheme {
        SmsPipelineActivityCard(
            model = historicalSmsPipelineGapUiModel(
                runId = "preview-history",
                isCancelling = true,
                isFinishing = false
            ),
            onInspect = {},
            onStop = {}
        )
    }
}

@Preview(widthDp = 360, showBackground = true)
@Composable
private fun SmsPipelineActivityCardCompletePreview() {
    PocketFinancerTheme {
        SmsPipelineActivityCard(
            model = SmsPipelineCardUiModel(
                target = SmsProcessingTarget.ManualResult("preview-result"),
                phase = SmsPipelinePhase.COMPLETE,
                tone = SmsPipelineTone.SUCCESS,
                title = "Sync complete",
                detail = "Queue: 1 saved • 1 skipped",
                badge = "DONE",
                source = SmsSourcePreview.Hidden,
                stepLabel = "LATEST RESULT",
                stepValue = "Saved to transaction ledger",
                inspectState = SmsInspectUiState.AVAILABLE,
                inspectLabel = "Inspect",
                stopState = SmsStopUiState.HIDDEN,
                accessibilityText =
                    "Sync complete. One saved and one skipped."
            ),
            onInspect = {},
            onStop = {}
        )
    }
}
