package com.pocketfinancer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val DarkColorScheme = darkColorScheme(
    primary = M3_Primary,
    onPrimary = M3_OnPrimary,
    primaryContainer = M3_PrimaryContainer,
    onPrimaryContainer = M3_OnPrimaryContainer,
    secondary = M3_Secondary,
    onSecondary = M3_OnSecondary,
    secondaryContainer = M3_SecondaryContainer,
    onSecondaryContainer = M3_OnSecondaryContainer,
    tertiary = M3_Tertiary,
    onTertiary = M3_OnTertiary,
    tertiaryContainer = M3_TertiaryContainer,
    onTertiaryContainer = M3_OnTertiaryContainer,
    error = M3_Error,
    onError = M3_OnError,
    errorContainer = M3_ErrorContainer,
    onErrorContainer = M3_OnErrorContainer,
    background = M3_Background,
    onBackground = M3_OnBackground,
    surface = M3_Surface,
    onSurface = M3_OnSurface,
    surfaceVariant = M3_SurfaceVariant,
    onSurfaceVariant = M3_OnSurfaceVariant,
    outline = M3_Outline,
    outlineVariant = M3_OutlineVariant,
    surfaceContainerLowest = M3_SurfaceContainerLowest,
    surfaceContainerLow = M3_SurfaceContainerLow,
    surfaceContainer = M3_SurfaceContainer,
    surfaceContainerHigh = M3_SurfaceContainerHigh,
    surfaceContainerHighest = M3_SurfaceContainerHighest
)

@Composable
fun PocketFinancerTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content
    )
}
