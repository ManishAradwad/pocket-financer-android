package com.pocketfinancer.ui.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class TransactionsUiState(
    val transactions: List<Transaction> = emptyList(),
    val activeSegment: String = "All",
    val selectedTransaction: Transaction? = null
)

@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository
) : ViewModel() {

    private val _activeSegment = MutableStateFlow("All")
    val activeSegment: StateFlow<String> = _activeSegment.asStateFlow()

    private val _selectedTransaction = MutableStateFlow<Transaction?>(null)
    val selectedTransaction: StateFlow<Transaction?> = _selectedTransaction.asStateFlow()

    val uiState: StateFlow<TransactionsUiState> = combine(
        transactionRepository.getAllByDateDesc(),
        _activeSegment,
        _selectedTransaction
    ) { txs, segment, selected ->
        val filtered = when (segment) {
            "Debits" -> txs.filter { it.type == TransactionType.DEBIT }
            "Credits" -> txs.filter { it.type == TransactionType.CREDIT }
            else -> txs
        }
        TransactionsUiState(
            transactions = filtered,
            activeSegment = segment,
            selectedTransaction = selected
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
}
