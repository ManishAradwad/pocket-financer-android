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
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.inference.LlamaEngine
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val activeSegment: String = "All",
    val selectedTransaction: Transaction? = null,
    val accounts: List<Account> = emptyList(),
    val selectedAccountId: String = "All",
    val syncState: HomeSyncState = HomeSyncState()
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val syncManager: HomeSyncManager,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val promptBuilder: PromptBuilder,
    private val llamaEngine: LlamaEngine,
    private val extractionParser: ExtractionParser
) : ViewModel() {

    private val _activeSegment = MutableStateFlow("All")
    val activeSegment: StateFlow<String> = _activeSegment.asStateFlow()

    private val _selectedTransaction = MutableStateFlow<Transaction?>(null)
    val selectedTransaction: StateFlow<Transaction?> = _selectedTransaction.asStateFlow()

    private val _selectedAccountId = MutableStateFlow("All")
    val selectedAccountId: StateFlow<String> = _selectedAccountId.asStateFlow()

    val uiState: StateFlow<TransactionsUiState> = combine(
        transactionRepository.getAllByDateDesc(),
        _activeSegment,
        _selectedTransaction,
        accountRepository.getAll(),
        _selectedAccountId,
        syncManager.syncState
    ) { flowsArray ->
        val txs = flowsArray[0] as List<Transaction>
        val segment = flowsArray[1] as String
        val selected = flowsArray[2] as Transaction?
        val accountsList = flowsArray[3] as List<Account>
        val selectedAccId = flowsArray[4] as String
        val syncState = flowsArray[5] as HomeSyncState

        val filteredBySegment = when (segment) {
            "Debits" -> txs.filter { it.type == TransactionType.DEBIT }
            "Credits" -> txs.filter { it.type == TransactionType.CREDIT }
            else -> txs
        }
        val filtered = if (selectedAccId == "All") {
            filteredBySegment
        } else {
            filteredBySegment.filter { it.accountId == selectedAccId }
        }

        TransactionsUiState(
            transactions = filtered,
            activeSegment = segment,
            selectedTransaction = selected,
            accounts = accountsList,
            selectedAccountId = selectedAccId,
            syncState = syncState
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
