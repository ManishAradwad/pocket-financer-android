package com.pocketfinancer.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.model.Account
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.ui.home.HomeSyncManager
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.SyncSmsItem
import com.pocketfinancer.ui.home.hasDiagnosticSourceEvidence
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.SlmAppFlowCoordinator
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class SortOption {
    DATE_DESC,   // Newest First (Default)
    DATE_ASC,    // Oldest First
    AMOUNT_DESC, // Highest Amount First
    AMOUNT_ASC   // Lowest Amount First
}

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val activeSegment: String = "All",
    val selectedTransaction: Transaction? = null,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String = "All",
    val syncState: HomeSyncState = HomeSyncState(),
    val searchQuery: String = "",
    val sortOption: SortOption = SortOption.DATE_DESC
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val syncManager: HomeSyncManager,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val promptBuilder: PromptBuilder,
    private val slmRuntime: SlmRuntime,
    private val extractionParser: ExtractionParser,
    private val appFlowCoordinator: SlmAppFlowCoordinator
) : ViewModel() {

    private val _activeSegment = MutableStateFlow("All")
    val activeSegment: StateFlow<String> = _activeSegment.asStateFlow()

    private val _selectedTransaction = MutableStateFlow<Transaction?>(null)
    val selectedTransaction: StateFlow<Transaction?> = _selectedTransaction.asStateFlow()

    private val _selectedAccountId = MutableStateFlow("All")
    val selectedAccountId: StateFlow<String> = _selectedAccountId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sortOption = MutableStateFlow(SortOption.DATE_DESC)
    val sortOption: StateFlow<SortOption> = _sortOption.asStateFlow()

    val uiState: StateFlow<TransactionsUiState> = combine(
        transactionRepository.getAllByDateDesc(),
        _activeSegment,
        _selectedTransaction,
        accountRepository.getAll(),
        _selectedAccountId,
        syncManager.syncState,
        _searchQuery,
        _sortOption
    ) { flowsArray ->
        val txs = flowsArray[0] as List<Transaction>
        val segment = flowsArray[1] as String
        val selected = flowsArray[2] as Transaction?
        val accountsList = flowsArray[3] as List<Account>
        val selectedAccId = flowsArray[4] as String
        val syncState = flowsArray[5] as HomeSyncState
        val query = flowsArray[6] as String
        val sort = flowsArray[7] as SortOption

        val filteredBySegment = when (segment) {
            "Debits" -> txs.filter { it.type == TransactionType.DEBIT }
            "Credits" -> txs.filter { it.type == TransactionType.CREDIT }
            else -> txs
        }
        val filteredByAccount = if (selectedAccId == "All") {
            filteredBySegment
        } else {
            filteredBySegment.filter { it.accountId == selectedAccId }
        }

        val filteredBySearch = if (query.isBlank()) {
            filteredByAccount
        } else {
            filteredByAccount.filter { tx ->
                tx.merchant.contains(query, ignoreCase = true) ||
                tx.amount.toString().contains(query) ||
                (tx.accountLabel?.contains(query, ignoreCase = true) == true)
            }
        }

        val sortedTransactions = when (sort) {
            SortOption.DATE_DESC -> filteredBySearch.sortedByDescending { it.date }
            SortOption.DATE_ASC -> filteredBySearch.sortedBy { it.date }
            SortOption.AMOUNT_DESC -> filteredBySearch.sortedByDescending { it.amount }
            SortOption.AMOUNT_ASC -> filteredBySearch.sortedBy { it.amount }
        }

        TransactionsUiState(
            transactions = sortedTransactions,
            activeSegment = segment,
            selectedTransaction = selected,
            accounts = accountsList,
            selectedAccountId = selectedAccId,
            syncState = syncState,
            searchQuery = query,
            sortOption = sort
        )
    }
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = TransactionsUiState()
    )

    fun updateSegment(segment: String) {
        _activeSegment.value = segment
    }

    fun selectTransaction(transaction: Transaction?) {
        _selectedTransaction.value = transaction
    }

    fun selectAccount(accountId: String) {
        _selectedAccountId.value = accountId
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun updateSortOption(option: SortOption) {
        _sortOption.value = option
    }

    fun resetSyncState() {
        syncManager.resetState()
    }

    fun updateTransaction(
        id: String,
        amount: Double,
        merchant: String,
        type: TransactionType,
        accountName: String
    ) {
        viewModelScope.launch {
            withLedgerEditAdmission(appFlowCoordinator) {
                val account = accountRepository.getOrCreate(
                    name = accountName.trim(),
                    bank = "Unknown Account",
                    type = "auto-extracted"
                )
                val updated = transactionRepository.updateTransaction(
                    id = id,
                    amount = amount,
                    merchant = merchant,
                    type = type,
                    accountId = account.id
                )
                if (_selectedTransaction.value?.id == id) {
                    _selectedTransaction.value = updated
                }
            }
        }
    }

    fun getFilterLogs(item: SyncSmsItem): List<String> {
        if (!item.hasDiagnosticSourceEvidence()) {
            return listOf(SOURCE_EVIDENCE_UNAVAILABLE)
        }
        return smsFilterPipeline.filterWithDetails(item.sender, item.body).logs
    }

    fun getKvCacheLogs(item: SyncSmsItem): List<String> {
        if (!item.hasDiagnosticSourceEvidence()) {
            return listOf(SOURCE_EVIDENCE_UNAVAILABLE)
        }
        return listOf(
            "KV cache telemetry is captured from the exact runtime request.",
            "Historical transactions do not currently persist cache-hit diagnostics."
        )
    }

    fun getSlmPrompt(item: SyncSmsItem): String {
        if (!item.hasDiagnosticSourceEvidence()) {
            return SOURCE_EVIDENCE_UNAVAILABLE
        }
        val rawPrompt = promptBuilder.buildExtractionPrompt(
            item.sender,
            item.body
        )
        val hasThinking = slmRuntime.state.value.loadedModel?.hasThinkingMode ?: true
        return promptBuilder.buildChatPrompt(rawPrompt, enableThinking = hasThinking)
    }

    fun getParsedOutput(jsonStr: String): String {
        val parsed = extractionParser.parse(jsonStr)
        return parsed?.let {
            "amount=${it.amount}, type=${it.type.name.lowercase()}, counterparty=${it.counterparty ?: "-"}, account=${it.account ?: "-"}"
        } ?: "Parsed: null (non-financial)"
    }

    private companion object {
        const val SOURCE_EVIDENCE_UNAVAILABLE =
            "Source evidence is unavailable after terminal processing."
    }
}

/**
 * Admits the complete financial write before it can create an account or
 * mutate a ledger row. Erase-all pauses this coordinator, cancels and joins
 * already-admitted edits, then rejects new edits until encrypted tables are
 * empty and the shell has been durably reset.
 */
internal suspend fun <T> withLedgerEditAdmission(
    coordinator: SlmAppFlowCoordinator,
    block: suspend () -> T
): T? {
    val lease = coordinator.tryEnter(SlmRuntimeOwner.LEDGER_EDIT) ?: return null
    return try {
        block()
    } finally {
        withContext(NonCancellable) {
            lease.release()
        }
    }
}
