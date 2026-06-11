package com.pocketfinancer.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.model.Account
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.data.repository.AccountRepository
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
    val accounts: List<Account> = emptyList()
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository
) : ViewModel() {

    private val _activeSegment = MutableStateFlow("All")
    val activeSegment: StateFlow<String> = _activeSegment.asStateFlow()

    private val _selectedTransaction = MutableStateFlow<Transaction?>(null)
    val selectedTransaction: StateFlow<Transaction?> = _selectedTransaction.asStateFlow()

    val uiState: StateFlow<TransactionsUiState> = combine(
        transactionRepository.getAllByDateDesc(),
        _activeSegment,
        _selectedTransaction,
        accountRepository.getAll()
    ) { txs, segment, selected, accountsList ->
        val filtered = when (segment) {
            "Debits" -> txs.filter { it.type == TransactionType.DEBIT }
            "Credits" -> txs.filter { it.type == TransactionType.CREDIT }
            else -> txs
        }
        TransactionsUiState(
            transactions = filtered,
            activeSegment = segment,
            selectedTransaction = selected,
            accounts = accountsList
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
                bank = "Unknown Bank",
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
