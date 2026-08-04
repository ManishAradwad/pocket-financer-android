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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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
    fun getAllByDateDesc(): Flow<List<Transaction>> = ledgerFlow {
        transactionDao.getAllByDateDesc()
    }

    fun getByDateRange(startMs: Long, endMs: Long): Flow<List<Transaction>> =
        ledgerFlow { transactionDao.getByDateRange(startMs, endMs) }

    fun getByType(type: TransactionType): Flow<List<Transaction>> =
        ledgerFlow { transactionDao.getByType(type.name.lowercase()) }

    fun getRecent(limit: Int = 20): Flow<List<Transaction>> =
        ledgerFlow { transactionDao.getRecent(limit) }

    private fun ledgerFlow(
        source: () -> Flow<List<TransactionEntity>>
    ): Flow<List<Transaction>> = flow {
        accountRepository.ensureInitialConsolidation()
        emitAll(
            source().map { list -> list.map { it.toDomain() } }
        )
    }

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
        return appDatabase.withTransaction {
            val existing = transactionDao.findBySource(
                connector = source.connector,
                messageId = source.messageId,
                providerMessageId = source.providerMessageId,
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
                        providerMessageId = source.providerMessageId,
                        fingerprint = source.fallbackFingerprint,
                        alternateFingerprint = source.alternateFingerprint
                    ) ?: error("Transaction source conflict could not be resolved")
                }
            }
            transactionDao.preserveSourceMetadata(
                transactionId = owned.id,
                messageId = source.messageId,
                providerMessageId = source.providerMessageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint,
                receivedDate = data.date
            )
            candidateDao.findBySource(
                connector = source.connector,
                messageId = source.messageId,
                providerMessageId = source.providerMessageId,
                fingerprint = source.fallbackFingerprint,
                alternateFingerprint = source.alternateFingerprint
            )?.let { matchingCandidate ->
                candidateDao.deleteByKey(matchingCandidate.candidateKey)
            }
            val persisted = transactionDao.getById(owned.id)
                ?: error("Persisted transaction disappeared")
            // Convert before Room commits. If a corrupt row cannot be mapped,
            // the source handoff rolls back instead of throwing after an insert.
            InsertResult(
                transaction = persisted.toDomain(),
                inserted = inserted
            )
        }
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
            providerMessageId = sourceIdentity.providerMessageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint
        )

    /**
     * Atomically resolves an existing source and promotes fallback-only
     * provenance when a later provider row supplies an authoritative id.
     *
     * Callers that skip parsing because a transaction already exists should
     * use this method instead of a read-only [exists] check. It also removes at
     * most one matching queued copy after the ledger owns the raw evidence.
     */
    suspend fun preserveSourceMetadataIfExists(
        sourceIdentity: SmsSourceIdentity,
        receivedDate: Long? = null
    ): Boolean = appDatabase.withTransaction {
        val existing = transactionDao.findBySource(
            connector = sourceIdentity.connector,
            messageId = sourceIdentity.messageId,
            providerMessageId = sourceIdentity.providerMessageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint
        ) ?: return@withTransaction false
        transactionDao.preserveSourceMetadata(
            transactionId = existing.id,
            messageId = sourceIdentity.messageId,
            providerMessageId = sourceIdentity.providerMessageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint,
            receivedDate = receivedDate
        )
        candidateDao.findBySource(
            connector = sourceIdentity.connector,
            messageId = sourceIdentity.messageId,
            providerMessageId = sourceIdentity.providerMessageId,
            fingerprint = sourceIdentity.fallbackFingerprint,
            alternateFingerprint = sourceIdentity.alternateFingerprint
        )?.let { matchingCandidate ->
            candidateDao.deleteByKey(matchingCandidate.candidateKey)
        }
        true
    }

    suspend fun findBySource(sourceIdentity: SmsSourceIdentity): Transaction? =
        transactionDao.findBySource(
            connector = sourceIdentity.connector,
            messageId = sourceIdentity.messageId,
            providerMessageId = sourceIdentity.providerMessageId,
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
