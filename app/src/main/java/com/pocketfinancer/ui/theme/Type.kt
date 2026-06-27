package com.pocketfinancer.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * PocketFinancer Typography — Full M3 type scale (all 15 tokens).
 *
 * Sizes, weights, line heights, and letter spacing follow the M3 baseline spec.
 * Reference: https://m3.material.io/styles/typography/type-scale-tokens
 *
 * Brand deviations:
 * - labelMedium / labelSmall use Monospace (financial data: account codes, timestamps)
 * - All other tokens use system SansSerif (Roboto on Android)
 *
 * For domain-specific styles (hero amounts, monospaced values, screen headers),
 * prefer [AppTypography] semantic aliases over raw M3 tokens.
 */
val PocketFinancerTypography = Typography(

    // ── DISPLAY ─────────────────────────────────────────────────────────
    // Short, impactful hero text. Rarely used directly on mobile.
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,     // M3: 400
        fontSize = 57.sp,
        lineHeight = 64.sp,
        letterSpacing = (-0.25).sp
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 45.sp,
        lineHeight = 52.sp,
        letterSpacing = 0.sp
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 36.sp,
        lineHeight = 44.sp,
        letterSpacing = 0.sp
    ),

    // ── HEADLINE ────────────────────────────────────────────────────────
    // High-emphasis text marking primary content sections.
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,     // M3: 400
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = 0.sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = 0.sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        letterSpacing = 0.sp
    ),

    // ── TITLE ───────────────────────────────────────────────────────────
    // Medium-emphasis text, shorter than headlines.
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,     // M3: 400
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = 0.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,     // M3: 500
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.15.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),

    // ── BODY ────────────────────────────────────────────────────────────
    // Long-form readable content.
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,     // M3: 400
        fontSize = 16.sp,
        lineHeight = 24.sp,
        letterSpacing = 0.5.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,     // M3: 400
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.25.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,     // M3: 400
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.4.sp
    ),

    // ── LABEL ───────────────────────────────────────────────────────────
    // Small UI elements: buttons, chips, navigation, metadata.
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,     // M3: 500
        fontSize = 14.sp,
        lineHeight = 20.sp,
        letterSpacing = 0.1.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Monospace,  // Brand: Monospace for account codes / summary amounts
        fontWeight = FontWeight.Medium,     // M3: 500
        fontSize = 12.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.5.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Monospace,  // Brand: Monospace for timestamps / metadata tags
        fontWeight = FontWeight.Medium,     // M3: 500
        fontSize = 11.sp,                   // M3 minimum (was 10sp)
        lineHeight = 16.sp,                 // M3: 16sp (was 12sp)
        letterSpacing = 0.5.sp
    )
)
