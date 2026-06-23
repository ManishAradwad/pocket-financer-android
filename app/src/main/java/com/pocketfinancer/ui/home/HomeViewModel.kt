package com.pocketfinancer.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.inference.LlamaEngine
import java.util.Calendar
import javax.inject.Inject

data class PeriodData(
    val amount: Double = 0.0,
    val txnCount: Int = 0,
    val deltaDir: String = "less", // "less" | "more"
    val deltaLabel: String = "Same as previous",
    val recent: List<Transaction> = emptyList()
)

data class HomeUiState(
    val selectedPeriod: String = "Day", // "Day" | "Week" | "Month"
    val periodData: Map<String, PeriodData> = emptyMap(),
    val syncState: HomeSyncState = HomeSyncState()
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionRepository: TransactionRepository,
    private val syncManager: HomeSyncManager,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val promptBuilder: PromptBuilder,
    private val llamaEngine: LlamaEngine,
    private val extractionParser: ExtractionParser
) : ViewModel() {

    private val _selectedPeriod = MutableStateFlow("Day")
    val selectedPeriod: StateFlow<String> = _selectedPeriod.asStateFlow()

    val uiState: StateFlow<HomeUiState> = combine(
        transactionRepository.getAllByDateDesc(),
        _selectedPeriod,
        syncManager.syncState
    ) { txs, period, syncState ->
        val periodDataMap = calculatePeriodData(txs)
        HomeUiState(
            selectedPeriod = period,
            periodData = periodDataMap,
            syncState = syncState
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    init {
        checkForUnsynced()
    }

    fun selectPeriod(period: String) {
        _selectedPeriod.value = period
    }

    fun checkForUnsynced() {
        viewModelScope.launch {
            syncManager.checkForUnsyncedSms()
            val pendingCount = syncManager.syncState.value.queue.count { it.status == "pending" }
            val toastMsg = if (pendingCount > 0) {
                "Scan complete: Found $pendingCount unsynced transactional messages."
            } else {
                "Scan complete: Up to date. No new transactional messages found."
            }
            android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun startSync() {
        viewModelScope.launch {
            SyncService.start(context)
        }
    }

    fun resetSyncState() {
        syncManager.resetState()
    }

    private fun calculatePeriodData(txs: List<Transaction>): Map<String, PeriodData> {
        // Date timestamps
        val now = System.currentTimeMillis()

        // 1. Today calculations
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = calendar.timeInMillis
        val yesterdayStart = todayStart - 24 * 60 * 60 * 1000L
        val yesterdayEnd = todayStart - 1

        val todayDebits = txs.filter { it.date >= todayStart && it.type == TransactionType.DEBIT }
        val todayAmount = todayDebits.sumOf { it.amount }
        val todayCount = todayDebits.size
        val yesterdayAmount = txs.filter { it.date in yesterdayStart..yesterdayEnd && it.type == TransactionType.DEBIT }.sumOf { it.amount }
        
        val todayDeltaVal = Math.abs(todayAmount - yesterdayAmount)
        val todayDeltaDir = if (todayAmount >= yesterdayAmount) "more" else "less"
        val todayDeltaLabel = "₹${String.format("%,.0f", todayDeltaVal)} $todayDeltaDir than yesterday"

        // 2. Week calculations (Start of current week, e.g. Monday)
        val weekCal = Calendar.getInstance()
        weekCal.set(Calendar.HOUR_OF_DAY, 0)
        weekCal.set(Calendar.MINUTE, 0)
        weekCal.set(Calendar.SECOND, 0)
        weekCal.set(Calendar.MILLISECOND, 0)
        // Set to Monday of this week
        val currentDayOfWeek = weekCal.get(Calendar.DAY_OF_WEEK)
        val daysDiff = if (currentDayOfWeek == Calendar.SUNDAY) -6 else Calendar.MONDAY - currentDayOfWeek
        weekCal.add(Calendar.DAY_OF_YEAR, daysDiff)
        val thisWeekStart = weekCal.timeInMillis
        val lastWeekStart = thisWeekStart - 7 * 24 * 60 * 60 * 1000L
        val lastWeekEnd = thisWeekStart - 1

        val thisWeekDebits = txs.filter { it.date >= thisWeekStart && it.type == TransactionType.DEBIT }
        val thisWeekAmount = thisWeekDebits.sumOf { it.amount }
        val thisWeekCount = thisWeekDebits.size
        val lastWeekAmount = txs.filter { it.date in lastWeekStart..lastWeekEnd && it.type == TransactionType.DEBIT }.sumOf { it.amount }

        val weekDeltaVal = Math.abs(thisWeekAmount - lastWeekAmount)
        val weekDeltaDir = if (thisWeekAmount >= lastWeekAmount) "more" else "less"
        val weekDeltaLabel = "₹${String.format("%,.0f", weekDeltaVal)} $weekDeltaDir than last week"

        // 3. Month calculations
        val monthCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val thisMonthStart = monthCal.timeInMillis
        monthCal.add(Calendar.MONTH, -1)
        val lastMonthStart = monthCal.timeInMillis
        val lastMonthEnd = thisMonthStart - 1

        val thisMonthDebits = txs.filter { it.date >= thisMonthStart && it.type == TransactionType.DEBIT }
        val thisMonthAmount = thisMonthDebits.sumOf { it.amount }
        val thisMonthCount = thisMonthDebits.size
        val lastMonthAmount = txs.filter { it.date in lastMonthStart..lastMonthEnd && it.type == TransactionType.DEBIT }.sumOf { it.amount }

        val monthDeltaVal = Math.abs(thisMonthAmount - lastMonthAmount)
        val monthDeltaDir = if (thisMonthAmount >= lastMonthAmount) "more" else "less"
        val monthDeltaLabel = "₹${String.format("%,.0f", monthDeltaVal)} $monthDeltaDir than last month"

        return mapOf(
            "Day" to PeriodData(
                amount = todayAmount,
                txnCount = todayCount,
                deltaDir = todayDeltaDir,
                deltaLabel = todayDeltaLabel,
                recent = todayDebits.take(5)
            ),
            "Week" to PeriodData(
                amount = thisWeekAmount,
                txnCount = thisWeekCount,
                deltaDir = weekDeltaDir,
                deltaLabel = weekDeltaLabel,
                recent = thisWeekDebits.take(5)
            ),
            "Month" to PeriodData(
                amount = thisMonthAmount,
                txnCount = thisMonthCount,
                deltaDir = monthDeltaDir,
                deltaLabel = monthDeltaLabel,
                recent = thisMonthDebits.take(5)
            )
        )
    }

    fun getFilterLogs(sender: String, body: String): List<String> {
        return smsFilterPipeline.filterWithDetails(sender, body).logs
    }

    fun getKvCacheLogs(sender: String, body: String): List<String> {
        val staticPrefix = promptBuilder.getStaticPrefix()
        val rawPrompt = promptBuilder.buildExtractionPrompt(sender, body)
        val hasThinking = llamaEngine.hasThinkingMode
        val chatPrompt = promptBuilder.buildChatPrompt(rawPrompt, enableThinking = hasThinking)
        val splitIndex = chatPrompt.indexOf(staticPrefix)
        val cacheLogs = mutableListOf<String>()
        if (splitIndex != -1) {
            val prefixString = chatPrompt.substring(0, splitIndex + staticPrefix.length)
            val prefixHash = llamaEngine.computeSha256(prefixString)
            val sessionFile = llamaEngine.getSessionFile(prefixHash)
            val prefixTokens = llamaEngine.tokenize(prefixString, addSpecial = true)
            if (prefixTokens != null) {
                cacheLogs.add("Prefix Size: ${prefixTokens.size} tokens")
                cacheLogs.add("Prefix Hash: ${prefixHash.take(12)}...")
                if (sessionFile.exists()) {
                    cacheLogs.add("Session cache file found: ${sessionFile.name}")
                    cacheLogs.add("Reusing existing KV Cache (Skipped heavy prefill phase!).")
                } else {
                    cacheLogs.add("Session cache file not found. Generating new session cache...")
                }
            } else {
                cacheLogs.add("Prefix Hash: ${prefixHash.take(12)}...")
                if (sessionFile.exists()) {
                    cacheLogs.add("Session cache file found: ${sessionFile.name}")
                    cacheLogs.add("Reusing existing KV Cache.")
                } else {
                    cacheLogs.add("Session cache file not found. Prefix tokenization bypassed.")
                }
            }
        } else {
            cacheLogs.add("No static prefix matched in chat prompt.")
        }
        return cacheLogs
    }

    fun getSlmPrompt(sender: String, body: String): String {
        val rawPrompt = promptBuilder.buildExtractionPrompt(sender, body)
        val hasThinking = llamaEngine.hasThinkingMode
        return promptBuilder.buildChatPrompt(rawPrompt, enableThinking = hasThinking)
    }

    fun getParsedOutput(jsonStr: String): String {
        val parsed = extractionParser.parse(jsonStr)
        return parsed?.let {
            "amount=${it.amount}, type=${it.type.name.lowercase()}, counterparty=${it.counterparty ?: "-"}, account=${it.account ?: "-"}"
        } ?: "Parsed: null (non-financial)"
    }
}
