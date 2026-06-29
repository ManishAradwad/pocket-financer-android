package com.pocketfinancer.ui.insights

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketfinancer.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InsightsScreen(
    viewModel: InsightsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val selectedTimeframe by viewModel.selectedTimeframe.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(M3_Surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ── Header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(M3_Tertiary.copy(alpha = 0.3f), M3_Tertiary)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.PieChart,
                            contentDescription = "Insights Icon",
                            tint = M3_OnTertiary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = "financialInsights",
                        color = M3_OnSurface,
                        style = MaterialTheme.typography.titleLarge
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // ── Timeframe Capsule Selector ──
                item {
                    TimeframeSelector(
                        selected = selectedTimeframe,
                        onSelected = { viewModel.selectTimeframe(it) }
                    )
                }

                // ── Cash Flow Card ──
                item {
                    CashFlowCard(
                        totalDebit = state.totalDebit,
                        totalCredit = state.totalCredit,
                        netSavings = state.netSavings,
                        savingsRate = state.savingsRate
                    )
                }

                // ── Chai & Snack Index (Micro-spends) ──
                item {
                    MicroSpendsCard(
                        count = state.microSpendsCount,
                        sum = state.microSpendsSum,
                        totalDebit = state.totalDebit
                    )
                }

                // ── Top Merchants Card ──
                item {
                    TopMerchantsCard(
                        merchants = state.topMerchants
                    )
                }

                // ── Account Split Card ──
                item {
                    AccountSplitCard(
                        accounts = state.accountSplits
                    )
                }

                // ── Spending Patterns (Weekdays & Time of day) ──
                item {
                    SpendingPatternsCard(
                        dayOfWeek = state.dayOfWeekVelocity,
                        hourly = state.hourlyVelocity
                    )
                }
            }
        }
    }
}

@Composable
private fun TimeframeSelector(
    selected: TimeframePreset,
    onSelected: (TimeframePreset) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(M3_SurfaceContainerLow)
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        TimeframePreset.values().forEach { preset ->
            val isSelected = selected == preset
            val label = when (preset) {
                TimeframePreset.THIS_MONTH -> "This Month"
                TimeframePreset.LAST_MONTH -> "Last Month"
                TimeframePreset.ALL_TIME -> "All Time"
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) M3_SecondaryContainer else Color.Transparent)
                    .clickable { onSelected(preset) }
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = if (isSelected) M3_OnSecondaryContainer else M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.labelLarge.copy(
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                )
            }
        }
    }
}

@Composable
private fun CashFlowCard(
    totalDebit: Double,
    totalCredit: Double,
    netSavings: Double,
    savingsRate: Double
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "CASH FLOW SUMMARY",
                style = MaterialTheme.typography.labelSmall,
                color = M3_OnSurfaceVariant,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Inflow (Credits)",
                        style = MaterialTheme.typography.bodySmall,
                        color = M3_OnSurfaceVariant
                    )
                    Text(
                        text = formatRupees(totalCredit),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = M3_Pos
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Outflow (Debits)",
                        style = MaterialTheme.typography.bodySmall,
                        color = M3_OnSurfaceVariant
                    )
                    Text(
                        text = formatRupees(totalDebit),
                        style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                        color = M3_Error
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.2f))

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Net Balance",
                        style = MaterialTheme.typography.bodyMedium,
                        color = M3_OnSurface
                    )
                    Text(
                        text = formatRupees(netSavings),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        color = if (netSavings >= 0) M3_OnSurface else M3_Error
                    )
                }

                if (totalCredit > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (savingsRate >= 0) M3_PosContainer.copy(alpha = 0.5f)
                                else M3_ErrorContainer.copy(alpha = 0.5f)
                            )
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (savingsRate >= 0) {
                                "+${String.format("%.1f", savingsRate)}% saved"
                            } else {
                                "${String.format("%.1f", Math.abs(savingsRate))}% overspent"
                            },
                            color = if (savingsRate >= 0) M3_OnPosContainer else M3_OnErrorContainer,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MicroSpendsCard(
    count: Int,
    sum: Double,
    totalDebit: Double
) {
    val percentage = if (totalDebit > 0) (sum / totalDebit * 100.0) else 0.0

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Coffee,
                    contentDescription = "Coffee Icon",
                    tint = M3_Tertiary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = "CHAI & SNACK INDEX (MICRO-SPENDS)",
                    style = MaterialTheme.typography.labelSmall,
                    color = M3_OnSurfaceVariant,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "$count Payments",
                        style = MaterialTheme.typography.titleLarge,
                        color = M3_OnSurface,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Transactions under ₹100",
                        style = MaterialTheme.typography.bodySmall,
                        color = M3_OnSurfaceVariant
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = formatRupees(sum),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = FontFamily.Monospace),
                        color = M3_Tertiary
                    )
                    val percentText = when {
                        percentage == 0.0 -> "0.0%"
                        percentage < 0.1 -> "< 0.1%"
                        else -> "${String.format("%.1f", percentage)}%"
                    }
                    Text(
                        text = "$percentText of debits",
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = M3_OnSurfaceVariant
                    )
                }
            }

            if (count > 0) {
                Spacer(modifier = Modifier.height(16.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(M3_SurfaceContainerHighest)
                        .padding(12.dp)
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Info Icon",
                            tint = M3_OnSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "You made $count quick scans this period. Small UPI payments add up fast!",
                            style = MaterialTheme.typography.bodySmall,
                            color = M3_OnSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TopMerchantsCard(
    merchants: List<MerchantSpend>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "TOP EXPENSES BY MERCHANT",
                style = MaterialTheme.typography.labelSmall,
                color = M3_OnSurfaceVariant,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (merchants.isEmpty()) {
                Text(
                    text = "No debit transactions recorded.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = M3_OnSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    merchants.forEach { merchantSpend ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = merchantSpend.merchant,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = M3_OnSurface,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatRupees(merchantSpend.totalAmount),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = M3_OnSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            // Progress bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(M3_SurfaceContainerHighest)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(merchantSpend.percentage / 100f)
                                        .clip(CircleShape)
                                        .background(M3_Primary)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountSplitCard(
    accounts: List<AccountSpend>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "SPENDING BY ACCOUNT / BANK",
                style = MaterialTheme.typography.labelSmall,
                color = M3_OnSurfaceVariant,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (accounts.isEmpty()) {
                Text(
                    text = "No accounts activity found.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = M3_OnSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    accounts.forEach { accountSpend ->
                        Column {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = accountSpend.accountName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = M3_OnSurface,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatRupees(accountSpend.totalAmount),
                                    style = MaterialTheme.typography.bodyMedium.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold
                                    ),
                                    color = M3_OnSurface
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            // Account split progress bar
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(CircleShape)
                                    .background(M3_SurfaceContainerHighest)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(accountSpend.percentage / 100f)
                                        .clip(CircleShape)
                                        .background(M3_Secondary)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SpendingPatternsCard(
    dayOfWeek: Map<String, Double>,
    hourly: Map<String, Double>
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "WEEKDAY & TIME DISTRIBUTION",
                style = MaterialTheme.typography.labelSmall,
                color = M3_OnSurfaceVariant,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Spends by Day",
                style = MaterialTheme.typography.titleMedium,
                color = M3_OnSurface
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Weekday bars
            val maxDaySpend = dayOfWeek.values.maxOrNull() ?: 1.0
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(100.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                dayOfWeek.forEach { (day, amount) ->
                    val ratio = (amount / maxDaySpend).toFloat().coerceIn(0.02f, 1f)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (amount > 0) "₹${String.format("%.0f", amount)}" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = M3_OnSurfaceVariant,
                            maxLines = 1
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .height(56.dp)
                                .width(16.dp),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(16.dp)
                                    .fillMaxHeight(ratio)
                                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                    .background(M3_Tertiary)
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = day,
                            style = MaterialTheme.typography.labelSmall,
                            color = M3_OnSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Spends by Time of Day",
                style = MaterialTheme.typography.titleMedium,
                color = M3_OnSurface
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Hourly progress split
            val maxHourSpend = hourly.values.maxOrNull() ?: 1.0
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                hourly.forEach { (timeCategory, amount) ->
                    val ratio = (amount / maxHourSpend).toFloat().coerceIn(0f, 1f)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = timeCategory,
                            style = MaterialTheme.typography.bodySmall,
                            color = M3_OnSurfaceVariant,
                            modifier = Modifier.width(140.dp)
                        )
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(8.dp)
                                .clip(CircleShape)
                                .background(M3_SurfaceContainerHighest)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(ratio)
                                    .clip(CircleShape)
                                    .background(M3_Primary)
                            )
                        }
                        Text(
                            text = formatRupees(amount),
                            style = MaterialTheme.typography.labelSmall,
                            color = M3_OnSurface,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.width(60.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatRupees(amount: Double): String {
    return "₹" + String.format("%,.0f", amount)
}
