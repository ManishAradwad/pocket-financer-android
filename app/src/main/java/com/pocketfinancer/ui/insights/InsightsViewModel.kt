package com.pocketfinancer.ui.insights

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import java.util.Calendar
import java.util.Locale
import javax.inject.Inject

enum class TimeframePreset {
    THIS_MONTH, LAST_MONTH, ALL_TIME
}

data class MerchantSpend(
    val merchant: String,
    val totalAmount: Double,
    val percentage: Float
)

data class AccountSpend(
    val accountName: String,
    val totalAmount: Double,
    val percentage: Float
)

data class InsightsUiState(
    val selectedTimeframe: TimeframePreset = TimeframePreset.THIS_MONTH,
    val totalDebit: Double = 0.0,
    val totalCredit: Double = 0.0,
    val netSavings: Double = 0.0,
    val savingsRate: Double = 0.0,
    val microSpendsCount: Int = 0,
    val microSpendsSum: Double = 0.0,
    val topMerchants: List<MerchantSpend> = emptyList(),
    val accountSplits: List<AccountSpend> = emptyList(),
    val dayOfWeekVelocity: Map<String, Double> = emptyMap(),
    val hourlyVelocity: Map<String, Double> = emptyMap()
)

@HiltViewModel
class InsightsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _selectedTimeframe = MutableStateFlow(TimeframePreset.THIS_MONTH)
    val selectedTimeframe: StateFlow<TimeframePreset> = _selectedTimeframe.asStateFlow()

    val uiState: StateFlow<InsightsUiState> = combine(
        transactionRepository.getAllByDateDesc(),
        _selectedTimeframe
    ) { txs, timeframe ->
        calculateInsights(txs, timeframe)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = InsightsUiState()
    )

    fun selectTimeframe(timeframe: TimeframePreset) {
        _selectedTimeframe.value = timeframe
    }

    private fun calculateInsights(txs: List<Transaction>, timeframe: TimeframePreset): InsightsUiState {
        val range = getTimeframeRange(timeframe)
        val filteredTxs = txs.filter { it.date in range.first..range.second }

        // 1. Debits / Credits / Savings
        val debits = filteredTxs.filter { it.type == TransactionType.DEBIT }
        val credits = filteredTxs.filter { it.type == TransactionType.CREDIT }

        val totalDebit = debits.sumOf { it.amount }
        val totalCredit = credits.sumOf { it.amount }
        val netSavings = totalCredit - totalDebit
        val savingsRate = if (totalCredit > 0) {
            ((totalCredit - totalDebit) / totalCredit * 100.0).coerceIn(-100.0, 100.0)
        } else {
            0.0
        }

        // 2. Microtransactions Index (Amount <= 100.0)
        val microSpends = debits.filter { it.amount <= 100.0 }
        val microSpendsCount = microSpends.size
        val microSpendsSum = microSpends.sumOf { it.amount }

        // 3. Top Merchants
        val totalDebitSum = if (totalDebit > 0) totalDebit else 1.0
        val topMerchants = debits
            .groupBy { it.merchant.trim().ifBlank { "Unknown Merchant" } }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
            .entries
            .sortedByDescending { it.value }
            .take(5)
            .map { entry ->
                MerchantSpend(
                    merchant = entry.key,
                    totalAmount = entry.value,
                    percentage = ((entry.value / totalDebitSum) * 100.0).toFloat()
                )
            }

        // 4. Spend Split by Account
        val accountSplits = debits
            .groupBy { it.accountLabel?.trim()?.ifBlank { "Unknown Account" } ?: "Unknown Account" }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
            .entries
            .sortedByDescending { it.value }
            .map { entry ->
                AccountSpend(
                    accountName = entry.key,
                    totalAmount = entry.value,
                    percentage = ((entry.value / totalDebitSum) * 100.0).toFloat()
                )
            }

        // 5. Day of Week Velocity
        val dayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val weekdayMap = debits.groupBy { tx ->
            val calendar = Calendar.getInstance().apply { timeInMillis = tx.date }
            val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
            when (dayOfWeek) {
                Calendar.MONDAY -> "Mon"
                Calendar.TUESDAY -> "Tue"
                Calendar.WEDNESDAY -> "Wed"
                Calendar.THURSDAY -> "Thu"
                Calendar.FRIDAY -> "Fri"
                Calendar.SATURDAY -> "Sat"
                Calendar.SUNDAY -> "Sun"
                else -> "Mon"
            }
        }.mapValues { it.value.sumOf { tx -> tx.amount } }

        val dayOfWeekVelocity = dayNames.associateWith { weekdayMap[it] ?: 0.0 }

        // 6. Hourly Velocity
        val hourlyCategories = listOf(
            "Morning (6 AM - 12 PM)",
            "Afternoon (12 PM - 5 PM)",
            "Evening (5 PM - 9 PM)",
            "Night (9 PM - 6 AM)"
        )
        val hourMap = debits.groupBy { tx ->
            val calendar = Calendar.getInstance().apply { timeInMillis = tx.date }
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            when (hour) {
                in 6..11 -> "Morning (6 AM - 12 PM)"
                in 12..16 -> "Afternoon (12 PM - 5 PM)"
                in 17..20 -> "Evening (5 PM - 9 PM)"
                else -> "Night (9 PM - 6 AM)"
            }
        }.mapValues { it.value.sumOf { tx -> tx.amount } }

        val hourlyVelocity = hourlyCategories.associateWith { hourMap[it] ?: 0.0 }

        return InsightsUiState(
            selectedTimeframe = timeframe,
            totalDebit = totalDebit,
            totalCredit = totalCredit,
            netSavings = netSavings,
            savingsRate = savingsRate,
            microSpendsCount = microSpendsCount,
            microSpendsSum = microSpendsSum,
            topMerchants = topMerchants,
            accountSplits = accountSplits,
            dayOfWeekVelocity = dayOfWeekVelocity,
            hourlyVelocity = hourlyVelocity
        )
    }

    private fun getTimeframeRange(timeframe: TimeframePreset): Pair<Long, Long> {
        val calendar = Calendar.getInstance()
        return when (timeframe) {
            TimeframePreset.THIS_MONTH -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                Pair(calendar.timeInMillis, Long.MAX_VALUE)
            }
            TimeframePreset.LAST_MONTH -> {
                calendar.set(Calendar.DAY_OF_MONTH, 1)
                calendar.set(Calendar.HOUR_OF_DAY, 0)
                calendar.set(Calendar.MINUTE, 0)
                calendar.set(Calendar.SECOND, 0)
                calendar.set(Calendar.MILLISECOND, 0)
                val thisMonthStart = calendar.timeInMillis
                val lastMonthEnd = thisMonthStart - 1
                calendar.add(Calendar.MONTH, -1)
                val lastMonthStart = calendar.timeInMillis
                Pair(lastMonthStart, lastMonthEnd)
            }
            TimeframePreset.ALL_TIME -> {
                Pair(0L, Long.MAX_VALUE)
            }
        }
    }
}
