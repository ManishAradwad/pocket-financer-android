package com.pocketfinancer.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * App-specific semantic typography aliases for Pocket Financer.
 *
 * These extend the base M3 type scale with domain-specific styles for financial
 * data display. Prefer these over raw M3 tokens when the use-case is
 * domain-specific (amounts, account codes, timestamps, screen headers).
 *
 * Each alias is backed by a real M3 token with targeted overrides (e.g. Monospace
 * font family for numerical values). This keeps the design system grounded in M3
 * while serving the app's financial identity.
 *
 * Usage:
 * ```
 *   Text("₹22,500", style = AppTypography.amountHero)
 *   Text("HDFC A/c XX1234", style = AppTypography.accountCode)
 *   Text("20:59", style = AppTypography.timestamp)
 *   Text("pocketFinancer", style = AppTypography.screenHeader)
 * ```
 */
object AppTypography {

    // ── FINANCIAL AMOUNTS ───────────────────────────────────────────────

    /**
     * Hero financial amount — large monospaced number for primary balances.
     * Backed by M3 headlineLarge (32sp / 40sp) + Monospace + Bold.
     *
     * Use for: Home card balance, transaction detail receipt amount.
     */
    val amountHero: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

    /**
     * Medium financial amount — for secondary totals and summaries.
     * Backed by M3 titleLarge (22sp / 28sp) + Monospace + Medium.
     *
     * Use for: Insights totals, category-level amounts, inflow/outflow totals.
     */
    val amountMedium: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium
        )

    /**
     * Small financial amount — for inline amounts in lists and cards.
     * Backed by M3 titleMedium (16sp / 24sp) + Monospace.
     *
     * Use for: Transaction list row amounts, card summary values.
     */
    val amountSmall: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleMedium.copy(
            fontFamily = FontFamily.Monospace
        )

    /**
     * Compact financial amount — for amounts in dense layouts.
     * Backed by M3 bodyMedium (14sp / 20sp) + Monospace + SemiBold.
     *
     * Use for: Amounts inside chips, filter badges, compact list items.
     */
    val amountCompact: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyMedium.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.SemiBold
        )

    // ── FINANCIAL METADATA ──────────────────────────────────────────────

    /**
     * Account code / identifier — monospaced label for account info.
     * Backed by labelMedium (already Monospace, 12sp / 16sp).
     *
     * Use for: "HDFC A/c XX1234", filter chip account labels.
     */
    val accountCode: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.labelMedium

    /**
     * Timestamp / metadata tag — smallest monospaced text for dates and tags.
     * Backed by labelSmall (already Monospace, 11sp / 16sp).
     *
     * Use for: "20:59", "MESSAGE STREAM SYNCED", date stamps.
     */
    val timestamp: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.labelSmall

    /**
     * Monospaced body — for inline monospaced values within body content.
     * Backed by M3 bodySmall (12sp / 16sp) + Monospace.
     *
     * Use for: Monospaced reference codes, SMS parsing excerpts.
     */
    val monoBody: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace
        )

    /**
     * Monospaced body bold — for emphasized monospaced content.
     * Backed by M3 bodySmall (12sp / 16sp) + Monospace + Bold.
     *
     * Use for: Performance metrics, emphasized code values, bold log lines.
     */
    val monoBodyBold: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodySmall.copy(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )

    // ── LABELS & EYEBROWS ────────────────────────────────────────────────

    /**
     * Eyebrow label — smallest text for uppercase section labels and status badges.
     * Backed by labelSmall (already Monospace, 11sp / 16sp / Medium).
     *
     * Use for: "SOURCE SMS", "LIVE EXTRACTIONS", "EDIT MODE", "COMPLETE",
     *          "RUNNING LOCAL QWEN SLM", pipeline stage indicators.
     */
    val eyebrow: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.labelSmall

    /**
     * Eyebrow bold — for emphasized section labels and prominent badges.
     * Backed by labelSmall (11sp / 16sp / Monospace) + Bold.
     *
     * Use for: Active pipeline labels, emphasized badge text, bold metadata.
     */
    val eyebrowBold: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.labelSmall.copy(
            fontWeight = FontWeight.Bold
        )

    // ── BODY EMPHASIS ───────────────────────────────────────────────────

    /**
     * Body small bold — emphasized supporting text.
     * Backed by M3 bodySmall (12sp / 16sp) + Bold.
     *
     * Use for: Sender names, emphasized captions, bold status text.
     */
    val bodySmallBold: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodySmall.copy(
            fontWeight = FontWeight.Bold
        )

    /**
     * Body medium bold — emphasized body text for medium-sized displays.
     * Backed by M3 bodyMedium (14sp / 20sp) + Bold.
     */
    val bodyMediumBold: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.bodyMedium.copy(
            fontWeight = FontWeight.Bold
        )

    // ── NAVIGATION & STRUCTURE ──────────────────────────────────────────

    /**
     * Screen header — bold title for top-level screen names.
     * Backed by M3 titleLarge (22sp / 28sp) + Bold emphasis.
     *
     * Use for: "pocketFinancer", "transactionLedger", "financialInsights".
     */
    val screenHeader: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleLarge.copy(
            fontWeight = FontWeight.Bold
        )

    /**
     * Section heading — for card / section titles within a screen.
     * Backed by M3 titleMedium (16sp / 24sp / Medium weight).
     *
     * Use for: "Recent synced transactions", "Cash Flow Summary".
     */
    val sectionHeading: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleMedium

    /**
     * Section heading bold — for emphasized section titles.
     * Backed by M3 titleMedium (16sp / 24sp) + Bold.
     *
     * Use for: Stat card values ("₹5,000"), bold section titles.
     */
    val sectionHeadingBold: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleMedium.copy(
            fontWeight = FontWeight.Bold
        )

    /**
     * Title small bold — for emphasized small titles.
     * Backed by M3 titleSmall (14sp / 20sp) + Bold.
     */
    val titleSmallBold: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.titleSmall.copy(
            fontWeight = FontWeight.Bold
        )
    /**
     * Headline large bold — largest screen titles.
     * Backed by M3 headlineLarge (32sp / 40sp) + Bold.
     */
    val headlineLargeBold: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineLarge.copy(
            fontWeight = FontWeight.Bold
        )

    /**
     * Headline medium bold — medium screen titles.
     * Backed by M3 headlineMedium (28sp / 36sp) + Bold.
     */
    val headlineMediumBold: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineMedium.copy(
            fontWeight = FontWeight.Bold
        )

    /**
     * Headline small bold — small screen titles.
     * Backed by M3 headlineSmall (24sp / 32sp) + Bold.
     */
    val headlineSmallBold: TextStyle
        @Composable @ReadOnlyComposable
        get() = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Bold
        )
}
