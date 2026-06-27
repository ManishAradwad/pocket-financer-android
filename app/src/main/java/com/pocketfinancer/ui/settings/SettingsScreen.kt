package com.pocketfinancer.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.ui.theme.*

@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ── Section 1: Device Hardware ───────────────────────────────────
        HardwareCard(state.deviceInfo, state.hardwareError, viewModel)

        // ── Section 2: Active Model ──────────────────────────────────────
        ActiveModelCard(state.selectedSlm, state.selectedSlm?.let { state.tierExplanations[it.id] })

        // ── Section: Background SMS Processing ──────────────────────────
        BackgroundParsingCard(state.processIncomingSms, viewModel)

        // ── Section 3: Engine Status + Test ──────────────────────────────
        EngineCard(state, viewModel)

        // ── Section 4: Developer Options ─────────────────────────────────
        DeveloperToolsCard(viewModel)

        Spacer(modifier = Modifier.height(8.dp))
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Section 1: Device Hardware
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun HardwareCard(
    deviceInfo: DeviceCapabilities.DeviceInfo?,
    hardwareError: String?,
    viewModel: SettingsViewModel
) {
    SectionCard(title = "DEVICE HARDWARE") {
        if (hardwareError != null) {
            Text(hardwareError, color = M3_Error, style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }

        val device = deviceInfo ?: run {
            Text("Reading hardware...", color = M3_OnSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            return@SectionCard
        }

        // RAM row
        InfoRow(
            label = "RAM",
            value = "${"%.1f".format(device.ramGb)} GB",
            badge = ramBadge(device.ramTier)
        )

        // GPU row
        device.gpu?.let { gpu ->
            InfoRow(label = "GPU", value = gpu.gpuType)
            if (gpu.renderer.isNotEmpty()) {
                LabelValue("", gpu.renderer)
                LabelValue("", "OpenGL ${gpu.version}")
            }
        } ?: InfoRow(label = "GPU", value = "Could not detect")

        // CPU row
        device.cpu?.let { cpu ->
            InfoRow(label = "CPU", value = "${cpu.cores} cores")
            val features = buildString {
                append("i8mm ")
                append(if (cpu.hasI8mm) "✓" else "✗")
                append("  dotprod ")
                append(if (cpu.hasDotProd) "✓" else "✗")
                append("  fp16 ")
                append(if (cpu.hasFp16) "✓" else "✗")
            }
            LabelValue("", features)
            cpu.socModel?.let { LabelValue("SoC", it) }
        } ?: InfoRow(label = "CPU", value = "Could not detect")

        // Storage row
        InfoRow(
            label = "Storage",
            value = "${"%.1f".format(device.storage.availableGb)} GB free / ${"%.1f".format(device.storage.totalGb)} GB"
        )
        LabelValue(
            "",
            if (device.storage.canFit(DeviceCapabilities.MODEL_SIZE_BYTES)) "Can fit model? YES" else "Can fit model? NO"
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Section 2: Active Model
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ActiveModelCard(
    selectedSlm: SlmTier?,
    explanation: String?
) {
    SectionCard(title = "ACTIVE MODEL") {
        if (selectedSlm == null) {
            Text(
                "⚠ No SLM is viable for this device (RAM below minimum)",
                color = M3_Error,
                style = MaterialTheme.typography.bodyMedium
            )
            return@SectionCard
        }

        Column {
            Text(
                text = selectedSlm.name,
                color = M3_Primary,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = selectedSlm.description,
                color = M3_OnSurface,
                style = MaterialTheme.typography.bodyMedium
            )
            explanation?.let {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = it,
                    color = M3_Pos,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Section 3: Engine Status + Download + Test
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun EngineCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    val ds = state.downloadState

    SectionCard(title = "LLAMA ENGINE") {
        // Status
        InfoRow(
            label = "Status",
            value = when {
                state.loadingModel -> "LOADING..."
                state.modelLoaded -> "LOADED"
                else -> "NOT LOADED"
            },
            valueColor = if (state.modelLoaded) M3_Pos else M3_OnSurfaceVariant
        )

        // Selected model info
        state.selectedSlm?.let { slm ->
            LabelValue("Selected", "${slm.name} — ${slm.description}")
            LabelValue("File", slm.modelFile)
            LabelValue("Size", "${"%.0f".format(slm.sizeMb.toFloat())} MB")
            LabelValue("Path", viewModel.getModelFilePath())
        }

        // Model path when loaded
        if (state.modelPath != null) {
            LabelValue("Loaded from", state.modelPath)
        }

        // Error
        state.modelLoadError?.let { error ->
            Text(
                text = error,
                color = M3_Error,
                style = AppTypography.monoBody,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Download Section ──

        if (ds.isDownloading) {
            // Progress bar during download
            LinearProgressIndicator(
                progress = { ds.progress },
                modifier = Modifier.fillMaxWidth(),
                color = M3_Primary,
                trackColor = M3_SurfaceContainerLow,
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "${"%.0f".format(ds.progress * 100)}% · ${"%.1f".format(ds.downloadedMb)} / ${"%.1f".format(ds.totalMb)} MB",
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = if (ds.speedMbps > 0.01f) "${"%.1f".format(ds.speedMbps)} MB/s" else "",
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (ds.etaSeconds > 0) {
                Text(
                    text = "ETA: ${formatEta(ds.etaSeconds)}",
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedButton(
                onClick = { viewModel.cancelDownload() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = M3_Error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("CANCEL DOWNLOAD", style = MaterialTheme.typography.labelMedium)
            }
        } else {
            // Buttons row when not downloading
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Download button (if not loaded and not already complete)
                if (!state.modelLoaded && !ds.isComplete) {
                    Button(
                        onClick = { viewModel.downloadSelectedModel() },
                        enabled = state.selectedSlm != null,
                        colors = ButtonDefaults.buttonColors(containerColor = M3_PrimaryContainer)
                    ) {
                        val label = state.selectedSlm?.let {
                            "DOWNLOAD (${it.sizeMb}MB)"
                        } ?: "DOWNLOAD MODEL"
                        Text(label, color = M3_OnPrimaryContainer, style = MaterialTheme.typography.labelMedium)
                    }
                }

                // Load button
                Button(
                    onClick = { viewModel.loadSelectedModel() },
                    enabled = !state.loadingModel && !state.modelLoaded && ds.isComplete,
                    colors = ButtonDefaults.buttonColors(containerColor = M3_PrimaryContainer)
                ) {
                    Text(
                        if (state.loadingModel) "LOADING..." else "LOAD MODEL",
                        color = M3_OnPrimaryContainer,
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (state.modelLoaded) {
                    OutlinedButton(
                        onClick = { viewModel.unloadModel() },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = M3_Error)
                    ) {
                        Text("UNLOAD", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Run test button
        Button(
            onClick = { viewModel.runTestSms() },
            enabled = state.modelLoaded && !state.testRunning,
            colors = ButtonDefaults.buttonColors(containerColor = M3_SecondaryContainer),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (state.testRunning) "RUNNING..." else "RUN TEST SMS",
                color = M3_OnSecondaryContainer,
                style = MaterialTheme.typography.labelMedium
            )
        }

        // Test output
        state.testProgress?.let { progress ->
            Text(
                text = progress,
                color = M3_OnSurfaceVariant,
                style = AppTypography.monoBodyBold,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
        state.filterLogs?.let { logs ->
            OutputBox(title = "SMS Filter Pipeline Logs", content = logs.joinToString("\n"))
        }
        state.sessionCacheLogs?.let { logs ->
            OutputBox(title = "KV Cache Session Logs", content = logs.joinToString("\n"))
        }
        state.slmPrompt?.let { prompt ->
            OutputBox(title = "Complete SLM Input Prompt", content = prompt)
        }
        state.thinkingOutput?.let { thinking ->
            OutputBox(title = "Thinking (<think> block)", content = thinking.take(2000))
        }
        state.testResult?.let { result ->
            OutputBox(title = "Raw Output", content = result)
        }
        state.testParsed?.let { parsed ->
            OutputBox(title = "Parsed", content = parsed)
        }
        state.testError?.let { error ->
            Text(
                text = error,
                color = M3_Error,
                style = AppTypography.monoBody,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

private fun formatEta(seconds: Long): String {
    if (seconds < 60) return "${seconds}s"
    val m = seconds / 60
    val s = seconds % 60
    return "${m}m ${s}s"
}

// ═══════════════════════════════════════════════════════════════════════════════
// Reusable Components
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainer),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.labelSmall
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    badge: Pair<String, Color>? = null,
    valueColor: Color = M3_OnSurface
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = value,
                color = valueColor,
                style = MaterialTheme.typography.bodyMedium
            )
            badge?.let { (text, color) ->
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = text,
                    color = color,
                    style = AppTypography.eyebrowBold,
                    modifier = Modifier
                        .background(color.copy(alpha = 0.15f), MaterialTheme.shapes.extraSmall)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        if (label.isNotEmpty()) {
            Text(
                text = label + "  ",
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Text(
            text = value,
            color = M3_OnSurfaceVariant,
            style = AppTypography.monoBody
        )
    }
}

@Composable
private fun OutputBox(title: String, content: String) {
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        text = title,
        color = M3_OnSurfaceVariant,
        style = MaterialTheme.typography.labelSmall
    )
    Surface(
        color = M3_SurfaceContainerLow,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
    ) {
        Text(
            text = content,
            color = M3_OnSurface,
            style = AppTypography.monoBody,
            modifier = Modifier.padding(10.dp)
        )
    }
}

@Composable
private fun ramBadge(tier: DeviceCapabilities.RamTier): Pair<String, Color>? {
    return when (tier) {
        DeviceCapabilities.RamTier.OK -> "OK" to M3_Pos
        DeviceCapabilities.RamTier.WARNING -> "WARNING" to Color(0xFFF2C94C)
        DeviceCapabilities.RamTier.BLOCKED -> "BLOCKED" to M3_Error
    }
}

@Composable
private fun DeveloperToolsCard(viewModel: SettingsViewModel) {
    val context = androidx.compose.ui.platform.LocalContext.current
    
    SectionCard(title = "DEVELOPER OPTIONS") {
        Text(
            text = "Testing utilities for app developers. Resetting onboarding will clear transaction history, but will keep the downloaded local AI model file intact.",
            color = M3_OnSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
        
        Spacer(modifier = Modifier.height(12.dp))
        
        Button(
            onClick = {
                viewModel.resetOnboarding {
                    val activity = context as? android.app.Activity
                    activity?.recreate()
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = M3_Error),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("RESET ONBOARDING & CLEAR DB", color = Color.White, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun BackgroundParsingCard(
    enabled: Boolean,
    viewModel: SettingsViewModel
) {
    SectionCard(title = "BACKGROUND SMS PROCESSING") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Process Incoming SMS",
                    color = M3_OnSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Automatically parse transaction alerts in the background and update transactions.",
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = enabled,
                onCheckedChange = { viewModel.toggleProcessIncomingSms() },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = M3_Primary
                )
            )
        }
    }
}
