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
    onClose: () -> Unit
) {
    var expandedStage by remember { mutableStateOf<Int?>(null) }

    // Auto-expand active stage, or first stage if idle
    LaunchedEffect(activeStageIndex, isActive) {
        if (isActive) {
            expandedStage = when {
                activeStageIndex == 0 -> 0 // Pre-filter
                activeStageIndex == 1 && hasThinkingMode -> 2 // Thinking Pass (mapped to stage 2: Inference)
                activeStageIndex == 1 && !hasThinkingMode -> 2 // Grammar JSON
                activeStageIndex == 2 -> 2 // Grammar JSON
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
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                )
            }
            Text(
                text = "Close Logs",
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier
                    .background(M3_SurfaceContainerHigh, RoundedCornerShape(100))
                    .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(100))
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
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
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "Qwen-1.7B-Chat-Int4.gguf",
                            color = M3_OnSurfaceVariant,
                            style = AppTypography.timestamp
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = performanceText ?: "Evaluating...",
                        color = M3_Primary,
                        style = AppTypography.monoBody.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "llama.cpp JNI",
                        color = M3_OnSurfaceVariant,
                        style = AppTypography.timestamp
                    )
                }
            }
        }

        // Timeline Flow Section
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Stage 0: SMS Pre-Filter Check
            val isStage0Done = !isActive || activeStageIndex > 0
            val isStage0Active = isActive && activeStageIndex == 0
            val stage0Status = if (status == "filtered_out") "Filtered Out" else if (isStage0Done) "Passed" else "Pending"
            val stage0Color = if (status == "filtered_out") M3_Error else if (isStage0Done) M3_Pos else if (isStage0Active) Color(0xFFF2C94C) else M3_OnSurfaceVariant.copy(alpha = 0.4f)
            
            TimelineStage(
                index = 0,
                title = "Stage 1: SMS Pre-Filter Check",
                statusLabel = stage0Status,
                statusColor = stage0Color,
                icon = if (status == "filtered_out") Icons.Rounded.Block else if (isStage0Done) Icons.Rounded.CheckCircle else Icons.Rounded.Info,
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
            val isStage1Done = !isActive || activeStageIndex >= (if (hasThinkingMode) 1 else 2)
            val isStage1Active = isActive && activeStageIndex == 1 && !isStage1Done
            val stage1Status = if (isStage1Done) "Prompt Compiled" else if (isStage1Active) "Compiling..." else "Pending"
            val stage1Color = if (isStage1Done) M3_Pos else if (isStage1Active) Color(0xFFF2C94C) else M3_OnSurfaceVariant.copy(alpha = 0.4f)
            
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
                    OutputBox(title = "Complete SLM Input Prompt", content = slmPrompt)
                }
            }

            // Stage 2: Local SLM Inference Execution
            val isStage2Done = !isActive || activeStageIndex > 2
            val isStage2Active = isActive && (activeStageIndex == 1 || activeStageIndex == 2)
            val stage2Status = if (isStage2Done) "Inference Complete" else if (isStage2Active) {
                if (activeStageIndex == 1 && hasThinkingMode) "Phase 1: Thinking Pass" else "Phase 2: Grammar JSON"
            } else "Pending"
            val stage2Color = if (isStage2Done) M3_Pos else if (isStage2Active) Color(0xFFF2C94C) else M3_OnSurfaceVariant.copy(alpha = 0.4f)
            
            TimelineStage(
                index = 2,
                title = "Stage 3: Local SLM Inference Execution",
                statusLabel = stage2Status,
                statusColor = stage2Color,
                icon = if (isStage2Done) Icons.Rounded.CheckCircle else Icons.Rounded.Memory,
                isExpanded = expandedStage == 2,
                onToggle = { expandedStage = if (expandedStage == 2) null else 2 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Show Thinking Output block ONLY if the model supports thinking mode AND we have thinking content (or are currently running thinking pass)
                    if (hasThinkingMode && (thinkingOutput.isNotEmpty() || (isStage2Active && activeStageIndex == 1))) {
                        val displayThinking = thinkingOutput.ifEmpty { "Waiting for thinking tokens..." }
                        OutputBox(title = "Thinking Output (<think> block)", content = displayThinking)
                    }

                    val displayJson = jsonOutput.ifEmpty { if (isStage2Active && activeStageIndex == 2) "Streaming JSON output..." else "Waiting for JSON output..." }
                    OutputBox(title = "Raw JSON Output", content = displayJson)
                }
            }

            // Stage 3: Database Persistence
            val isStage3Done = !isActive || activeStageIndex > 3
            val isStage3Active = isActive && activeStageIndex == 3
            val stage3Status = if (status == "synced" || isStage3Done) "Saved to DB" else if (isStage3Active) "Writing..." else "Pending"
            val stage3Color = if (status == "synced" || isStage3Done) M3_Pos else if (isStage3Active) Color(0xFFF2C94C) else M3_OnSurfaceVariant.copy(alpha = 0.4f)
            
            TimelineStage(
                index = 3,
                title = "Stage 4: Encrypted Persistence",
                statusLabel = stage3Status,
                statusColor = stage3Color,
                icon = if (status == "synced" || isStage3Done) Icons.Rounded.SaveAlt else Icons.Rounded.Storage,
                isExpanded = expandedStage == 3,
                onToggle = { expandedStage = if (expandedStage == 3) null else 3 }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val displayParsed = parsedOutput.ifEmpty { "Waiting for parsed data..." }
                    OutputBox(title = "Parsed Transaction Output", content = displayParsed)
                }
            }
        }

        // Zero Data Security Card
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
                        text = "Zero Data Left Your Screen",
                        color = M3_OnSurface,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "Parameters run natively using llama.cpp within local native boundaries (JNI/NDK). Internet permission was not requested nor required.",
                        color = M3_OnSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        }
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
                            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = statusLabel,
                            color = statusColor,
                            style = AppTypography.eyebrow.copy(fontWeight = FontWeight.SemiBold)
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
