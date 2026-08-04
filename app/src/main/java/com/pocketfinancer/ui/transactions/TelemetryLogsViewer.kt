package com.pocketfinancer.ui.transactions

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pocketfinancer.ui.theme.*

data class TelemetryRuntimeFacts(
    val grammarEnabled: Boolean,
    val thinkingTokenBudget: Int,
    val answerTokenBudget: Int,
    val promptEvalMs: Long? = null,
    val evalMs: Long? = null,
    val generatedTokens: Int? = null,
    val cacheAttempted: Boolean? = null,
    val cacheHit: Boolean? = null,
    val cachePrefixTokens: Int? = null
)

internal fun telemetrySettledPersistenceLabel(status: String): String? =
    when (status) {
        "synced" -> "Saved to encrypted ledger"
        "already_saved" -> "Verified in encrypted ledger"
        else -> null
    }

internal data class TelemetrySettledFacts(
    val upstreamCompleted: Boolean,
    val upstreamUnavailable: Boolean,
    val ledgerVerified: Boolean
)

/**
 * `already_saved` can be assigned by the source-identity preflight before the
 * filter or model runs. Without provenance, only the ledger result is known;
 * claiming completed upstream stages would be misleading.
 */
internal fun telemetrySettledFacts(status: String): TelemetrySettledFacts =
    TelemetrySettledFacts(
        upstreamCompleted = status == "synced",
        upstreamUnavailable = status == "already_saved",
        ledgerVerified = status.isLedgerVerifiedSuccess()
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelemetryLogsViewer(
    sender: String,
    body: String,
    status: String, // "pending" | "syncing" | "synced" | "filtered_out" | "error"
    hasThinkingMode: Boolean,
    isActive: Boolean,
    activeStageIndex: Int,
    thinkingOutput: String,
    jsonOutput: String,
    filterLogs: List<String>,
    kvLogs: List<String>,
    slmPrompt: String,
    parsedOutput: String,
    performanceText: String?,
    activeModelName: String? = null,
    runtimeFacts: TelemetryRuntimeFacts? = null,
    thinkingOutputTruncated: Boolean = false,
    jsonOutputTruncated: Boolean = false,
    isStopping: Boolean = false,
    canStop: Boolean = true,
    isFinishing: Boolean = false,
    onStop: (() -> Unit)? = null,
    onClose: () -> Unit
) {
    var expandedStage by remember { mutableStateOf<Int?>(null) }

    // Auto-expand active stage, or first stage if idle
    LaunchedEffect(activeStageIndex, isActive) {
        if (isActive) {
            expandedStage = when {
                activeStageIndex == 0 -> 0 // Pre-filter
                activeStageIndex == 1 && hasThinkingMode -> 2 // Thinking Pass (mapped to stage 2: Inference)
                activeStageIndex == 1 && !hasThinkingMode -> 2 // Structured JSON
                activeStageIndex == 2 -> 2 // Structured JSON
                activeStageIndex == 3 -> 3 // Persistence
                else -> expandedStage
            }
        } else if (expandedStage == null) {
            expandedStage = if (status == "filtered_out") 0 else 2
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Title Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Memory,
                    contentDescription = null,
                    tint = Color(0xFFF2C94C),
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "ON-DEVICE EXTRACTION LOGS",
                    color = M3_OnSurface,
                    style = AppTypography.titleSmallBold
                )
            }
            Text(
                text = "Close Logs",
                color = M3_OnSurfaceVariant,
                style = AppTypography.eyebrowBold,
                modifier = Modifier
                    .background(M3_SurfaceContainerHigh, RoundedCornerShape(100))
                    .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(100))
                    .clickable { onClose() }
                    .defaultMinSize(minHeight = 48.dp)
                    .padding(horizontal = 12.dp)
                    .wrapContentHeight(Alignment.CenterVertically)
            )
        }


        if (isActive && onStop != null) {
            OutlinedButton(
                onClick = onStop,
                enabled = canStop && !isStopping && !isFinishing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = M3_Error,
                    disabledContentColor = M3_OnSurfaceVariant
                ),
                border = BorderStroke(
                    1.dp,
                    if (isStopping || isFinishing || !canStop) {
                        M3_OutlineVariant
                    } else {
                        M3_Error.copy(alpha = 0.55f)
                    }
                )
            ) {
                Icon(
                    imageVector = if (isStopping || isFinishing || !canStop) {
                        Icons.Rounded.HourglassTop
                    } else {
                        Icons.Rounded.StopCircle
                    },
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    when {
                        isStopping -> "Stopping safely..."
                        isFinishing -> "Finishing current save..."
                        !canStop -> "Stop unavailable during commit"
                        else -> "Stop SMS processing"
                    }
                )
            }
        }

        // Hardware Runtime Banner
        Card(
            colors = CardDefaults.cardColors(containerColor = M3_Surface),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.15f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.DeveloperMode,
                        contentDescription = null,
                        tint = M3_Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Column {
                        Text(
                            text = "Local Device CPU Runtime",
                            color = M3_OnSurface,
                            style = AppTypography.eyebrowBold
                        )
                        Text(
                            text = activeModelName ?: "Model details unavailable",
                            color = M3_OnSurfaceVariant,
                            style = AppTypography.timestamp
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = performanceText ?: if (isActive) "Evaluating..." else "Unavailable",
                        color = M3_Primary,
                        style = AppTypography.monoBodyBold
                    )
                    Text(
                        text = "llama.cpp JNI",
                        color = M3_OnSurfaceVariant,
                        style = AppTypography.timestamp
                    )
                }
            }
        }

        runtimeFacts?.let { facts ->
            RuntimeFactsCard(facts)
        }

        // Timeline Flow Section
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val settledFacts = telemetrySettledFacts(status)
            val isSettledFiltered = status == "filtered_out"
            val isSettledError = status == "error"

            // Stage 0: SMS Pre-Filter Check
            val isStage0Done = if (isActive) {
                activeStageIndex > 0
            } else {
                settledFacts.upstreamCompleted ||
                    isSettledFiltered ||
                    isSettledError
            }
            val isStage0Active = isActive && activeStageIndex == 0
            val stage0Status = when {
                isStage0Done -> "Checked"
                isStage0Active -> "Checking..."
                settledFacts.upstreamUnavailable -> "Details unavailable"
                else -> "Pending"
            }
            val stage0Color = if (isStage0Done) M3_Pos else if (isStage0Active) Color(0xFFF2C94C) else M3_OnSurfaceVariant.copy(alpha = 0.4f)
            
            TimelineStage(
                index = 0,
                title = "Stage 1: SMS Pre-Filter Check",
                statusLabel = stage0Status,
                statusColor = stage0Color,
                icon = if (isStage0Done) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
                isExpanded = expandedStage == 0,
                onToggle = { expandedStage = if (expandedStage == 0) null else 0 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ORIGINAL RAW MESSAGE BODY",
                        color = M3_OnSurfaceVariant,
                        style = AppTypography.eyebrow
                    )
                    Text(
                        text = "\"$body\"",
                        color = M3_OnSurfaceVariant,
                        style = AppTypography.monoBody,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(M3_Surface, RoundedCornerShape(8.dp))
                            .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    OutputBox(title = "SMS Filter Pipeline Logs", content = filterLogs.joinToString("\n"))
                }
            }

            // Stage 1: KV Cache & Prompt Prep
            val isStage1Done = if (isActive) {
                activeStageIndex >= (if (hasThinkingMode) 1 else 2)
            } else {
                settledFacts.upstreamCompleted
            }
            val isStage1Active = isActive && activeStageIndex == 1 && !isStage1Done
            val stage1Status = when {
                isStage1Done -> "Prompt Compiled"
                isStage1Active -> "Compiling..."
                settledFacts.upstreamUnavailable -> "Details unavailable"
                isSettledError -> "Status unavailable"
                isSettledFiltered -> "Not retained"
                else -> "Pending"
            }
            val stage1Color = when {
                isStage1Done -> M3_Pos
                isStage1Active -> Color(0xFFF2C94C)
                else -> M3_OnSurfaceVariant.copy(alpha = 0.4f)
            }
            
            TimelineStage(
                index = 1,
                title = "Stage 2: KV Cache & Prompt Prep",
                statusLabel = stage1Status,
                statusColor = stage1Color,
                icon = if (isStage1Done) Icons.Rounded.CheckCircle else Icons.Rounded.Layers,
                isExpanded = expandedStage == 1,
                onToggle = { expandedStage = if (expandedStage == 1) null else 1 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutputBox(title = "KV Cache Session Logs", content = kvLogs.joinToString("\n"))
                    OutputBox(
                        title = "Prompt content supplied to runtime",
                        content = slmPrompt
                    )
                    Text(
                        text = "The model-specific chat template is rendered inside the local runtime and is not claimed as an exact rendered template here.",
                        color = M3_OnSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            // Stage 2: Local SLM Inference Execution
            val isStage2Done = if (isActive) {
                activeStageIndex > 2
            } else {
                settledFacts.upstreamCompleted
            }
            val isStage2Active = isActive && (activeStageIndex == 1 || activeStageIndex == 2)
            val stage2Status = when {
                isStage2Done -> "Inference Complete"
                isStage2Active -> {
                    if (activeStageIndex == 1 && hasThinkingMode) "Phase 1: Thinking Pass" else "Phase 2: Structured JSON"
                }
                settledFacts.upstreamUnavailable -> "Details unavailable"
                isSettledError -> "Status unavailable"
                isSettledFiltered -> "No transaction"
                else -> "Pending"
            }
            val stage2Color = when {
                isStage2Done -> M3_Pos
                isStage2Active -> Color(0xFFF2C94C)
                else -> M3_OnSurfaceVariant.copy(alpha = 0.4f)
            }
            
            TimelineStage(
                index = 2,
                title = "Stage 3: Local SLM Inference Execution",
                statusLabel = stage2Status,
                statusColor = stage2Color,
                icon = when {
                    isStage2Done -> Icons.Rounded.CheckCircle
                    isSettledError -> Icons.AutoMirrored.Rounded.HelpOutline
                    isSettledFiltered -> Icons.Rounded.Block
                    else -> Icons.Rounded.Memory
                },
                isExpanded = expandedStage == 2,
                onToggle = { expandedStage = if (expandedStage == 2) null else 2 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Show Thinking Output block ONLY if the model supports thinking mode AND we have thinking content (or are currently running thinking pass)
                    if (hasThinkingMode && (thinkingOutput.isNotEmpty() || (isStage2Active && activeStageIndex == 1))) {
                        val displayThinking = thinkingOutput
                            .ifEmpty { "Waiting for thinking tokens..." }
                            .withLiveOutputTruncationNotice(thinkingOutputTruncated)
                        OutputBox(title = "Thinking Output (<think> block)", content = displayThinking)
                    }

                    val displayJson = jsonOutput
                        .ifEmpty {
                            if (isStage2Active && activeStageIndex == 2) {
                                "Streaming JSON output..."
                            } else {
                                "Waiting for JSON output..."
                            }
                        }
                        .withLiveOutputTruncationNotice(jsonOutputTruncated)
                    OutputBox(title = "Raw JSON Output", content = displayJson)
                }
            }

            // Stage 3: Database Persistence
            val isStage3Done =
                settledFacts.ledgerVerified || (isActive && activeStageIndex > 3)
            val isStage3Active = isActive && activeStageIndex == 3
            val stage3Status = when {
                isStage3Done ->
                    telemetrySettledPersistenceLabel(status) ?: "Saved to encrypted ledger"
                isStage3Active -> "Writing..."
                isSettledFiltered || isSettledError -> "Not saved"
                else -> "Pending"
            }
            val stage3Color = when {
                isStage3Done -> M3_Pos
                isStage3Active -> Color(0xFFF2C94C)
                isSettledError -> M3_Error
                else -> M3_OnSurfaceVariant.copy(alpha = 0.4f)
            }
            
            TimelineStage(
                index = 3,
                title = "Stage 4: Encrypted Persistence",
                statusLabel = stage3Status,
                statusColor = stage3Color,
                icon = if (isStage3Done) Icons.Rounded.SaveAlt else Icons.Rounded.Storage,
                isExpanded = expandedStage == 3,
                onToggle = { expandedStage = if (expandedStage == 3) null else 3 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val displayParsed = parsedOutput.ifEmpty { "Waiting for parsed data..." }
                    OutputBox(title = "Parsed Transaction Output", content = displayParsed)
                }
            }
        }

        // Local processing boundary
        Card(
            colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerHigh),
            shape = RoundedCornerShape(12.dp),
            border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    tint = M3_Pos,
                    modifier = Modifier.size(18.dp)
                )
                Column {
                    Text(
                        text = "Local SMS Processing",
                        color = M3_OnSurface,
                        style = AppTypography.eyebrowBold
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "SMS extraction and model inference run locally on this device. This processing pipeline does not transmit SMS content or model output.",
                        color = M3_OnSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
    }
}

internal fun String.withLiveOutputTruncationNotice(truncated: Boolean): String =
    if (truncated) {
        "$this\n\n[Live output truncated for display.]"
    } else {
        this
    }

@Composable
private fun RuntimeFactsCard(facts: TelemetryRuntimeFacts) {
    Card(
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainer),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "RUNTIME REQUEST SNAPSHOT",
                color = M3_OnSurfaceVariant,
                style = AppTypography.eyebrowBold
            )
            RuntimeFactRow(
                label = "Structured grammar",
                value = if (facts.grammarEnabled) "Enabled" else "Disabled"
            )
            RuntimeFactRow(
                label = "Token budgets",
                value = "${facts.thinkingTokenBudget} thinking • ${facts.answerTokenBudget} answer"
            )
            RuntimeFactRow(
                label = "Prompt evaluation",
                value = facts.promptEvalMs?.let { "$it ms" }
                    ?: "Awaiting inference result"
            )
            RuntimeFactRow(
                label = "Generation",
                value = if (
                    facts.evalMs != null && facts.generatedTokens != null
                ) {
                    "${facts.evalMs} ms • ${facts.generatedTokens} tokens"
                } else {
                    "Awaiting inference result"
                }
            )
            RuntimeFactRow(
                label = "Prefix cache",
                value = when (facts.cacheAttempted) {
                    null -> "Awaiting inference result"
                    false ->
                        "Not attempted • ${facts.cachePrefixTokens ?: 0} prefix tokens"
                    true -> {
                        val outcome = if (facts.cacheHit == true) "Hit" else "Miss"
                        "$outcome • ${facts.cachePrefixTokens ?: 0} prefix tokens"
                    }
                }
            )
        }
    }
}

@Composable
private fun RuntimeFactRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = label,
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            color = M3_OnSurface,
            style = AppTypography.monoBody,
            modifier = Modifier.weight(1.35f)
        )
    }
}

@Composable
private fun TimelineStage(
    index: Int,
    title: String,
    statusLabel: String,
    statusColor: Color,
    icon: ImageVector,
    isExpanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainer),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = if (isExpanded) 0.35f else 0.15f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onToggle() }
                    .padding(12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(24.dp)
                            .background(statusColor.copy(alpha = 0.1f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Column {
                        Text(
                            text = title,
                            color = M3_OnSurface,
                            style = AppTypography.eyebrowBold
                        )
                        Text(
                            text = statusLabel,
                            color = statusColor,
                            style = AppTypography.eyebrow
                        )
                    }
                }
                Icon(
                    imageVector = if (isExpanded) Icons.Rounded.KeyboardArrowUp else Icons.Rounded.KeyboardArrowDown,
                    contentDescription = null,
                    tint = M3_OnSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }

            // Expanded content
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_SurfaceContainerLow.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun OutputBox(title: String, content: String) {
    Text(
        text = title,
        color = M3_OnSurfaceVariant,
        style = AppTypography.eyebrow
    )
    Surface(
        color = M3_SurfaceContainerLowest,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp, bottom = 8.dp)
    ) {
        Text(
            text = content,
            color = M3_OnSurface,
            style = AppTypography.monoBody,
            modifier = Modifier.padding(10.dp)
        )
    }
}
