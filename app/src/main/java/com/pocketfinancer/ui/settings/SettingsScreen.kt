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

        // ── Section 2: SLM Selection ─────────────────────────────────────
        SlmSelectionCard(state.selectedSlm, state.allTiers, state.tierExplanations)

        // ── Section 3: Engine Status + Test ──────────────────────────────
        EngineCard(state, viewModel)

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
// Section 2: SLM Selection
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun SlmSelectionCard(
    selectedSlm: SlmTier?,
    allTiers: List<SlmTier>,
    tierExplanations: Map<String, String>
) {
    SectionCard(title = "SLM SELECTION") {
        if (selectedSlm == null) {
            Text(
                "⚠ No SLM is viable for this device (RAM below minimum)",
                color = M3_Error,
                style = MaterialTheme.typography.bodyMedium
            )
            return@SectionCard
        }

        allTiers.forEach { tier ->
            val isSelected = tier == selectedSlm
            SlmTierRow(tier, isSelected, tierExplanations[tier.id] ?: "")
            if (tier != allTiers.last()) {
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun SlmTierRow(tier: SlmTier, isSelected: Boolean, explanation: String) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Selection indicator
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(
                        if (isSelected) M3_Primary else M3_OutlineVariant,
                        shape = MaterialTheme.shapes.small
                    )
            )

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tier.name + if (isSelected) "  ← SELECTED" else "",
                    color = if (isSelected) M3_Primary else M3_OnSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = tier.description,
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Text(
            text = explanation,
            color = if (isSelected) M3_Pos else M3_OnSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.padding(start = 20.dp, top = 2.dp)
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// Section 3: Engine Status + Test
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun EngineCard(state: SettingsUiState, viewModel: SettingsViewModel) {
    SectionCard(title = "LLAMA ENGINE") {
        // Status
        val statusColor = if (state.modelLoaded) M3_Pos else M3_OnSurfaceVariant
        val statusText = when {
            state.loadingModel -> "LOADING..."
            state.modelLoaded -> "LOADED"
            else -> "NOT LOADED"
        }

        InfoRow(
            label = "Status",
            value = statusText,
            valueColor = if (state.modelLoaded) M3_Pos else M3_OnSurfaceVariant
        )

        if (state.modelPath != null) {
            LabelValue("Path", state.modelPath)
        }

        if (state.selectedSlm != null) {
            LabelValue("Expected", state.selectedSlm.modelFile)
            LabelValue("Full path", viewModel.getModelFilePath())
        }

        // Error
        state.modelLoadError?.let { error ->
            Text(
                text = error,
                color = M3_Error,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Buttons
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { viewModel.loadSelectedModel() },
                enabled = !state.loadingModel && !state.modelLoaded,
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

        Spacer(modifier = Modifier.height(8.dp))

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
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
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
                style = MaterialTheme.typography.labelSmall.copy(
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Medium
                )
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
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
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
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace
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
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
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
