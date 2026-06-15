package com.pocketfinancer.data.repository

import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.entity.TransactionEntity
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val appDatabase: AppDatabase,
    private val transactionDao: TransactionDao,
    private val accountRepository: AccountRepository
) {

    suspend fun clearDatabase() {
        appDatabase.clearAllTables()
    }
    fun getAllByDateDesc(): Flow<List<Transaction>> =
        transactionDao.getAllByDateDesc().map { list -> list.map { it.toDomain() } }

    fun getByDateRange(startMs: Long, endMs: Long): Flow<List<Transaction>> =
        transactionDao.getByDateRange(startMs, endMs).map { list -> list.map { it.toDomain() } }

    fun getByType(type: TransactionType): Flow<List<Transaction>> =
        transactionDao.getByType(type.name.lowercase()).map { list -> list.map { it.toDomain() } }

    fun getRecent(limit: Int = 20): Flow<List<Transaction>> =
        transactionDao.getRecent(limit).map { list -> list.map { it.toDomain() } }

    suspend fun insert(data: NewTransaction): Transaction {
        val entity = TransactionEntity(
            id = UUID.randomUUID().toString(),
            amount = data.amount,
            merchant = data.merchant,
            date = data.date,
            type = data.type.name.lowercase(),
            accountId = data.accountId,
            rawMessage = data.rawMessage,
            sender = data.sender
        )
        transactionDao.insert(entity)
        return entity.toDomain()
    }

    suspend fun updateTransaction(
        id: String,
        amount: Double,
        merchant: String,
        type: TransactionType,
        accountId: String
    ): Transaction? {
        val existing = transactionDao.getById(id) ?: return null
        val updatedEntity = existing.copy(
            amount = amount,
            merchant = merchant,
            type = type.name.lowercase(),
            accountId = accountId,
            isEdited = true,
            updatedAt = System.currentTimeMillis()
        )
        transactionDao.insert(updatedEntity)
        return updatedEntity.toDomain()
    }

    suspend fun sumDebitsSince(sinceMs: Long): Double =
        transactionDao.sumByTypeSince("debit", sinceMs) ?: 0.0

    suspend fun sumCreditsSince(sinceMs: Long): Double =
        transactionDao.sumByTypeSince("credit", sinceMs) ?: 0.0

    suspend fun count(): Int = transactionDao.count()

    private suspend fun TransactionEntity.toDomain(): Transaction {
        val account = accountRepository.getById(accountId)
        return Transaction(
            id = id,
            amount = amount,
            merchant = merchant,
            date = date,
            type = TransactionType.fromString(type),
            accountId = accountId,
            accountLabel = account?.name,
            rawMessage = rawMessage,
            sender = sender,
            isEdited = isEdited
        )
    }

    data class NewTransaction(
        val amount: Double,
        val merchant: String,
        val date: Long,
        val type: TransactionType,
        val accountId: String,
        val rawMessage: String,
        val sender: String
    )
}
