package com.pocketfinancer.data.repository

import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.dao.QueuedSmsCandidateDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.entity.TransactionEntity
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TransactionRepository @Inject constructor(
    private val appDatabase: AppDatabase,
    private val transactionDao: TransactionDao,
    private val accountRepository: AccountRepository,
    private val candidateDao: QueuedSmsCandidateDao
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

    /**
     * Compatibility adapter for existing UI callers. Source uniqueness still
     * uses atomic insert-ignore semantics; an existing row is returned without
     * replacing its raw evidence.
     */
    suspend fun insert(data: NewTransaction): Transaction =
        insertIfAbsent(data).transaction

    suspend fun insertIfAbsent(data: NewTransaction): InsertResult {
        val source = data.sourceIdentity ?: SmsSourceIdentity.androidSms(
            providerMessageId = null,
            sender = data.sender,
            body = data.rawMessage,
            sourceTimestamp = data.date,
            messageType = 1
        )
        val entity = TransactionEntity(
            id = UUID.randomUUID().toString(),
            amount = data.amount,
            merchant = data.merchant,
            date = data.date,
            type = data.type.name.lowercase(),
            accountId = data.accountId,
            rawMessage = data.rawMessage,
            sender = data.sender,
            slmPromptEvalMs = data.slmPromptEvalMs,
            slmEvalMs = data.slmEvalMs,
            slmNumTokens = data.slmNumTokens,
            slmModelName = data.slmModelName,
            sourceConnector = source.connector,
            sourceProviderMessageId = source.providerMessageId,
            sourceMessageId = source.messageId,
            sourceFingerprint = source.fallbackFingerprint,
            sourceAlternateFingerprint = source.alternateFingerprint
        )
        val persisted = appDatabase.withTransaction {
            val existing = transactionDao.findBySource(
                connector = source.connector,
                messageId = source.messageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint
            )
            val inserted: Boolean
            val owned = if (existing != null) {
                inserted = false
                existing
            } else {
                inserted = transactionDao.insert(entity) != -1L
                if (inserted) {
                    entity
                } else {
                    transactionDao.findBySource(
                        connector = source.connector,
                        messageId = source.messageId,
                        fingerprint = source.fallbackFingerprint,
                        alternateFingerprint = source.alternateFingerprint
                    ) ?: error("Transaction source conflict could not be resolved")
                }
            }
            transactionDao.preserveSourceMetadata(
                transactionId = owned.id,
                providerMessageId = source.providerMessageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint,
                receivedDate = data.date
            )
            candidateDao.deleteBySource(
                connector = source.connector,
                messageId = source.messageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint
            )
            val preservedAlternate = owned.sourceAlternateFingerprint
                ?: when {
                    owned.sourceFingerprint != source.fallbackFingerprint ->
                        source.fallbackFingerprint
                    source.alternateFingerprint != null &&
                        owned.sourceFingerprint != source.alternateFingerprint ->
                        source.alternateFingerprint
                    else -> null
                }
            owned.copy(
                date = if (
                    owned.sourceProviderMessageId == null &&
                    source.providerMessageId != null
                ) {
                    data.date
                } else {
                    owned.date
                },
                sourceProviderMessageId =
                    owned.sourceProviderMessageId ?: source.providerMessageId,
                sourceAlternateFingerprint = preservedAlternate
            ) to inserted
        }
        return InsertResult(
            transaction = persisted.first.toDomain(),
            inserted = persisted.second
        )
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
        transactionDao.update(updatedEntity)
        return updatedEntity.toDomain()
    }

    suspend fun sumDebitsSince(sinceMs: Long): Double =
        transactionDao.sumByTypeSince("debit", sinceMs) ?: 0.0

    suspend fun sumCreditsSince(sinceMs: Long): Double =
        transactionDao.sumByTypeSince("credit", sinceMs) ?: 0.0

    suspend fun count(): Int = transactionDao.count()

    suspend fun exists(sender: String, date: Long): Boolean =
        transactionDao.exists(sender, date)

    suspend fun exists(sourceIdentity: SmsSourceIdentity): Boolean =
        transactionDao.existsBySource(
            connector = sourceIdentity.connector,
            messageId = sourceIdentity.messageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint
        )

    suspend fun findBySource(sourceIdentity: SmsSourceIdentity): Transaction? =
        transactionDao.findBySource(
            connector = sourceIdentity.connector,
            messageId = sourceIdentity.messageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint
        )?.toDomain()

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
            isEdited = isEdited,
            slmPromptEvalMs = slmPromptEvalMs,
            slmEvalMs = slmEvalMs,
            slmNumTokens = slmNumTokens,
            slmModelName = slmModelName,
            sourceIdentity = SmsSourceIdentity(
                connector = sourceConnector,
                messageId = sourceMessageId,
                fallbackFingerprint = sourceFingerprint,
                providerMessageId = sourceProviderMessageId,
                alternateFingerprint = sourceAlternateFingerprint
            )
        )
    }

    data class InsertResult(
        val transaction: Transaction,
        val inserted: Boolean
    )

    data class NewTransaction(
        val amount: Double,
        val merchant: String,
        val date: Long,
        val type: TransactionType,
        val accountId: String,
        val rawMessage: String,
        val sender: String,
        val slmPromptEvalMs: Long? = null,
        val slmEvalMs: Long? = null,
        val slmNumTokens: Int? = null,
        val slmModelName: String? = null,
        val sourceIdentity: SmsSourceIdentity? = null
    )
}
