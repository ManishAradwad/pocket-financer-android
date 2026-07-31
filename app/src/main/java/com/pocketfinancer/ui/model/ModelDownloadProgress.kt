package com.pocketfinancer.ui.model

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.ui.theme.AppTypography
import com.pocketfinancer.ui.theme.M3_OutlineVariant
import com.pocketfinancer.ui.theme.M3_OnSurface
import com.pocketfinancer.ui.theme.M3_OnSurfaceVariant
import com.pocketfinancer.ui.theme.M3_Primary
import com.pocketfinancer.ui.theme.M3_SurfaceContainer

internal data class ModelDownloadProgressText(
    val percentage: String,
    val transferred: String,
    val speed: String?,
    val eta: String?
)

internal fun ModelDownloader.DownloadState.toProgressText(): ModelDownloadProgressText =
    ModelDownloadProgressText(
        percentage = "${"%.0f".format(progress.coerceIn(0f, 1f) * 100)}%",
        transferred = "${"%.1f".format(downloadedMb)} / ${"%.1f".format(totalMb)} MB",
        speed = speedMbps
            .takeIf { it > 0.01f }
            ?.let { "${"%.1f".format(it)} MB/s" },
        eta = etaSeconds
            .takeIf { it > 0L }
            ?.let { "ETA: ${formatDownloadEta(it)}" }
    )

internal fun formatDownloadEta(seconds: Long): String {
    val safeSeconds = seconds.coerceAtLeast(0L)
    val hours = safeSeconds / 3_600L
    val minutes = (safeSeconds % 3_600L) / 60L
    val remainingSeconds = safeSeconds % 60L
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${remainingSeconds}s"
        else -> "${remainingSeconds}s"
    }
}

@Composable
internal fun ModelDownloadProgressPanel(
    downloadState: ModelDownloader.DownloadState,
    modifier: Modifier = Modifier,
    label: String = "Downloading model in background..."
) {
    val text = downloadState.toProgressText()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(M3_SurfaceContainer, RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                color = M3_OnSurface,
                style = MaterialTheme.typography.labelSmall
            )
            Text(
                text = text.percentage,
                color = M3_Primary,
                style = AppTypography.bodySmallBold
            )
        }
        LinearProgressIndicator(
            progress = { downloadState.progress.coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = M3_Primary,
            trackColor = M3_OutlineVariant.copy(alpha = 0.3f)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = text.transferred,
                color = M3_OnSurfaceVariant,
                style = AppTypography.timestamp
            )
            text.speed?.let { speed ->
                Text(
                    text = speed,
                    color = M3_OnSurfaceVariant,
                    style = AppTypography.timestamp
                )
            }
        }
        text.eta?.let { eta ->
            Text(
                text = eta,
                color = M3_OnSurfaceVariant,
                style = AppTypography.timestamp
            )
        }
    }
}
