package com.pocketfinancer.data.repository

import androidx.room.withTransaction
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.dao.SmsProcessingDao
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsPersistenceDecisionEntity
import com.pocketfinancer.data.db.entity.SmsProcessingAnalysisEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.db.entity.SmsProcessingTraceEventEntity
import com.pocketfinancer.data.db.entity.SmsReconstructedResultEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseEntity
import com.pocketfinancer.data.db.entity.SmsSelectorAttemptEntity
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SmsOperationClaim(
    val operationId: String,
    val ownerToken: String,
    val ownerGeneration: Long,
    val expiresAt: Long
)

data class SmsTraceReceipt(
    val eventId: String,
    val sequence: Long,
    val eventHash: String
)

data class SmsStopReceipt(
    val operationId: String,
    val reviewCaseId: String?,
    val state: String,
    val committed: Boolean
)

data class SmsSourceEvidence(
    val sourceId: String,
    val admissionReceiptId: String,
    val sender: String,
    val body: String,
    val sourceFingerprint: String,
    val sourceTimestamp: Long?,
    val admittedAt: Long,
    val deletionEpoch: Long
)

data class SmsReviewRetryContext(
    val reviewCaseId: String,
    val source: AdmittedSmsSourceEntity,
    val operation: SmsProcessingOperationEntity
)

class SmsProcessingStoreException(message: String) : IllegalStateException(message)

@Singleton
class SmsProcessingStore @Inject constructor(
    private val database: AppDatabase,
    private val dao: SmsProcessingDao
) {
    suspend fun admitSource(source: AdmittedSmsSourceEntity): Boolean {
        require(source.id.isNotBlank())
        require(source.rawMessage.toByteArray(Charsets.UTF_8).size <= 16_384)
        require(source.sender.toByteArray(Charsets.UTF_8).size <= 512)
        val inserted = dao.insertSource(source) != -1L
        val stored = dao.getSource(source.id)
            ?: throw SmsProcessingStoreException("Admitted SMS source disappeared")
        check(
            stored.sourceConnector == source.sourceConnector &&
                stored.sourceMessageId == source.sourceMessageId &&
                stored.sourceFingerprint == source.sourceFingerprint
        ) { "Admitted SMS source identity conflict" }
        return inserted
    }

    suspend fun sourceEvidence(sourceId: String): SmsSourceEvidence {
        val source = dao.getSource(sourceId)
            ?: throw SmsProcessingStoreException("Admitted SMS source not found")
        return SmsSourceEvidence(
            source.id,
            source.admissionReceiptId,
            source.sender,
            source.rawMessage,
            source.sourceFingerprint,
            source.sourceTimestamp,
            source.admittedAt,
            source.deletionEpoch
        )
    }

    suspend fun settledReceipt(operationId: String): Pair<String, String?>? {
        val operation = dao.getOperation(operationId) ?: return null
        return operation.settledAt?.let { operation.state to operation.settlementReceiptJson }
    }

    suspend fun reviewCaseIdForOperation(operationId: String): String? =
        dao.getReviewCaseForOperation(operationId)?.id

    suspend fun retryContext(reviewCaseId: String): SmsReviewRetryContext {
        val review = dao.getReviewCase(reviewCaseId)
            ?: throw SmsProcessingStoreException("Review case not found")
        val source = dao.getSource(review.sourceId)
            ?: throw SmsProcessingStoreException("Admitted SMS source not found")
        val operation = dao.getOperation(review.currentOperationId)
            ?: throw SmsProcessingStoreException("Processing operation not found")
        return SmsReviewRetryContext(review.id, source, operation)
    }

    /**
     * Converts abandoned operation snapshots into visible review cases. The
     * generation-aware fence cannot steal a refreshed or newly claimed owner.
     */
    suspend fun recoverExpiredOperations(now: Long = System.currentTimeMillis()): Int =
        database.withTransaction {
            val staleBefore = now - CLAIM_LEASE_MS
            dao.getRecoverableOperations(staleBefore, now).count { operation ->
                if (
                    dao.fenceRecoverable(
                        operation.id,
                        operation.ownerGeneration,
                        staleBefore,
                        now
                    ) != 1
                ) {
                    false
                } else {
                    retainInterruptedOperation(
                        operation,
                        "operation_interrupted",
                        now
                    )
                    true
                }
            }
        }

    suspend fun createOperation(operation: SmsProcessingOperationEntity) {
        require(UUID.fromString(operation.id).toString() == operation.id.lowercase())
        require(UUID.fromString(operation.stableEventId).toString() == operation.stableEventId.lowercase())
        require(operation.configurationHash.matches(SHA256_REGEX))
        database.withTransaction {
            check(dao.getSource(operation.sourceId) != null) { "Admitted SMS source is missing" }
            dao.getOperation(operation.id)?.let { existing ->
                check(
                    existing.sourceId == operation.sourceId &&
                        existing.configurationHash == operation.configurationHash
                ) { "Operation identity was reused with different immutable input" }
                return@withTransaction
            }
            dao.insertOperation(operation)
        }
    }

    suspend fun claim(operationId: String, now: Long): SmsOperationClaim {
        val ownerToken = UUID.randomUUID().toString()
        val expiresAt = now + CLAIM_LEASE_MS
        check(dao.claim(operationId, ownerToken, now, expiresAt) == 1) {
            "SMS operation is already owned"
        }
        val operation = dao.getOperation(operationId)
            ?: throw SmsProcessingStoreException("Claimed operation disappeared")
        return SmsOperationClaim(
            operationId = operationId,
            ownerToken = ownerToken,
            ownerGeneration = operation.ownerGeneration,
            expiresAt = expiresAt
        )
    }

    suspend fun heartbeat(claim: SmsOperationClaim, now: Long): SmsOperationClaim {
        val expiresAt = now + CLAIM_LEASE_MS
        check(
            dao.heartbeat(
                claim.operationId,
                claim.ownerToken,
                claim.ownerGeneration,
                now,
                expiresAt
            ) == 1
        ) { "SMS operation ownership was lost" }
        return claim.copy(expiresAt = expiresAt)
    }

    suspend fun transition(
        claim: SmsOperationClaim,
        expectedState: String,
        nextState: String,
        now: Long
    ) {
        check(
            dao.transition(
                operationId = claim.operationId,
                ownerToken = claim.ownerToken,
                ownerGeneration = claim.ownerGeneration,
                expectedState = expectedState,
                nextState = nextState,
                now = now
            ) == 1
        ) { "SMS operation transition lost ownership or state" }
    }

    suspend fun appendTrace(
        claim: SmsOperationClaim,
        stage: String,
        status: String,
        reasonCodes: List<String> = emptyList(),
        detailJson: String? = null,
        now: Long
    ): SmsTraceReceipt = database.withTransaction {
        require(stage in TRACE_STAGES)
        require(status in TRACE_STATUSES)
        require(reasonCodes.size == reasonCodes.distinct().size)
        requireOwned(claim, now)
        val existing = dao.getTrace(claim.operationId)
        val sequence = existing.size.toLong()
        check(existing.withIndex().all { (index, event) -> event.sequence == index.toLong() }) {
            "SMS trace sequence is corrupt"
        }
        val previousHash = existing.lastOrNull()?.eventHash
        val eventId = UUID.randomUUID().toString()
        val payload = canonicalTracePayload(
            sequence = sequence,
            eventId = eventId,
            occurredAt = now,
            stage = stage,
            status = status,
            reasonCodes = reasonCodes,
            detailJson = detailJson,
            previousHash = previousHash
        )
        val eventHash = sha256(payload)
        dao.insertTraceEvent(
            SmsProcessingTraceEventEntity(
                id = eventId,
                operationId = claim.operationId,
                sequence = sequence,
                occurredAt = now,
                stage = stage,
                status = status,
                reasonCodesJson = jsonArray(reasonCodes),
                detailJson = detailJson,
                previousEventHash = previousHash,
                eventHash = eventHash
            )
        )
        SmsTraceReceipt(eventId, sequence, eventHash)
    }

    suspend fun recordAnalysis(
        claim: SmsOperationClaim,
        analysisId: String,
        contractVersion: String,
        sourceHash: String,
        configurationHash: String,
        canonicalJson: String,
        now: Long
    ) = database.withTransaction {
        requireOwned(claim, now)
        if (dao.getAnalysis(claim.operationId) == null) {
            dao.insertAnalysis(
                SmsProcessingAnalysisEntity(
                    id = UUID.randomUUID().toString(),
                    operationId = claim.operationId,
                    analysisId = analysisId,
                    contractVersion = contractVersion,
                    sourceHash = sourceHash,
                    configurationHash = configurationHash,
                    canonicalJson = canonicalJson,
                    createdAt = now
                )
            )
        }
    }

    suspend fun recordSelectorAttempt(
        claim: SmsOperationClaim,
        runtimeProfileJson: String,
        requestJson: String,
        rawOutput: String?,
        completion: String,
        validatedSelectionJson: String?,
        safeErrorCode: String?,
        startedAt: Long,
        completedAt: Long
    ) = database.withTransaction {
        requireOwned(claim, completedAt)
        val existing = dao.getLatestSelectorAttempt(claim.operationId)
        check(existing == null) { "Only one selector attempt is permitted per operation" }
        dao.insertSelectorAttempt(
            SmsSelectorAttemptEntity(
                id = UUID.randomUUID().toString(),
                operationId = claim.operationId,
                attemptIndex = 0,
                runtimeProfileJson = runtimeProfileJson,
                requestJson = requestJson,
                rawOutput = rawOutput,
                outputByteCount = rawOutput?.toByteArray(Charsets.UTF_8)?.size,
                completion = completion,
                validatedSelectionJson = validatedSelectionJson,
                safeErrorCode = safeErrorCode,
                startedAt = startedAt,
                completedAt = completedAt
            )
        )
    }

    suspend fun recordReconstruction(
        claim: SmsOperationClaim,
        semanticResultJson: String,
        now: Long
    ) = database.withTransaction {
        requireOwned(claim, now)
        if (dao.getReconstructedResult(claim.operationId) == null) {
            dao.insertReconstructedResult(
                SmsReconstructedResultEntity(
                    id = UUID.randomUUID().toString(),
                    operationId = claim.operationId,
                    contractVersion = "pocketfinancer.processing-result/2",
                    recognitionDecision = "posted",
                    semanticResultJson = semanticResultJson,
                    createdAt = now
                )
            )
        }
    }

    suspend fun recordGateDecision(
        claim: SmsOperationClaim,
        result: String,
        primaryReason: String,
        checksJson: String,
        accountResolutionJson: String,
        rolloutMode: String,
        now: Long
    ) = database.withTransaction {
        requireOwned(claim, now)
        if (dao.getPersistenceDecision(claim.operationId) == null) {
            dao.insertPersistenceDecision(
                SmsPersistenceDecisionEntity(
                    id = UUID.randomUUID().toString(),
                    operationId = claim.operationId,
                    result = result,
                    primaryReason = primaryReason,
                    checksJson = checksJson,
                    accountResolutionJson = accountResolutionJson,
                    rolloutMode = rolloutMode,
                    createdAt = now
                )
            )
        }
    }

    suspend fun retainForReview(
        claim: SmsOperationClaim,
        reasons: List<String>,
        now: Long
    ): String = database.withTransaction {
        val operation = requireOwned(claim, now)
        val reviewCase = dao.getReviewCaseForOperation(operation.id)
            ?: operation.parentOperationId
                ?.let { dao.getReviewCaseForOperation(it) }
                ?.let { existing ->
                    val stableEventIds = runCatching {
                        val values = JSONObject("{\"values\":${existing.stableEventIdsJson}}")
                            .getJSONArray("values")
                        (0 until values.length()).map { values.getString(it) }.toMutableSet()
                    }.getOrElse { mutableSetOf() }
                    stableEventIds += operation.stableEventId
                    existing.copy(
                        currentOperationId = operation.id,
                        state = "open",
                        reasonCodesJson = jsonArray(reasons),
                        stableEventIdsJson = jsonArray(stableEventIds.sorted()),
                        updatedAt = now
                    ).also { check(dao.updateReviewCase(it) == 1) }
                }
            ?: SmsReviewCaseEntity(
                id = UUID.randomUUID().toString(),
                sourceId = operation.sourceId,
                currentOperationId = operation.id,
                state = "open",
                revision = 0,
                reasonCodesJson = jsonArray(reasons),
                draftJson = null,
                stableEventIdsJson = jsonArray(listOf(operation.stableEventId)),
                createdAt = now,
                updatedAt = now
            ).also { dao.insertReviewCase(it) }
        val receipt = "{" +
            "\"reasons\":${jsonArray(reasons)}," +
            "\"review_case_id\":${JSONObject.quote(reviewCase.id)}}"
        check(
            dao.settleToReview(
                operationId = operation.id,
                ownerToken = claim.ownerToken,
                ownerGeneration = claim.ownerGeneration,
                receiptJson = receipt,
                now = now
            ) == 1
        ) { "SMS operation lost ownership before review settlement" }
        reviewCase.id
    }

    suspend fun settleDiscarded(
        claim: SmsOperationClaim,
        reason: String,
        now: Long
    ) = database.withTransaction {
        val operation = requireOwned(claim, now)
        check(dao.eraseTerminalSource(operation.sourceId, operation.deletionEpoch) == 1) {
            "SMS source deletion epoch changed before terminal discard"
        }
        val receipt = "{\"reason\":${JSONObject.quote(reason)}}"
        check(
            dao.settleDiscarded(
                operation.id,
                claim.ownerToken,
                claim.ownerGeneration,
                receipt,
                now
            ) == 1
        ) { "SMS operation lost ownership before terminal discard" }
    }

    suspend fun requestStop(operationId: String, now: Long): SmsStopReceipt =
        database.withTransaction {
            val operation = dao.getOperation(operationId)
                ?: throw SmsProcessingStoreException("SMS operation not found")
            if (operation.settledAt != null) {
                return@withTransaction SmsStopReceipt(
                    operationId,
                    dao.getReviewCaseForOperation(operationId)?.id,
                    operation.state,
                    operation.state == "persisted"
                )
            }
            check(dao.fenceAndInterrupt(operationId, now) == 1)
            val reviewCaseId = retainInterruptedOperation(operation, "operation_interrupted", now)
            SmsStopReceipt(operationId, reviewCaseId, "interrupted", false)
        }

    /**
     * Makes an unowned invalid/interrupted operation visible without stealing
     * a still-live owner. The operation remains recoverable rather than being
     * represented by a fabricated review identifier.
     */
    suspend fun retainUnownedForReview(
        operationId: String,
        reason: String,
        now: Long
    ): String? = database.withTransaction {
        val operation = dao.getOperation(operationId) ?: return@withTransaction null
        dao.getReviewCaseForOperation(operationId)?.let { return@withTransaction it.id }
        if (operation.settledAt != null) return@withTransaction null
        if (operation.ownerToken != null && (operation.claimExpiresAt ?: Long.MIN_VALUE) > now) {
            return@withTransaction null
        }
        check(dao.fenceAndInterrupt(operationId, now) == 1)
        retainInterruptedOperation(operation, reason, now)
    }

    private suspend fun retainInterruptedOperation(
        operation: SmsProcessingOperationEntity,
        reason: String,
        now: Long
    ): String {
        val existing = dao.getReviewCaseForOperation(operation.id)
            ?: operation.parentOperationId?.let { dao.getReviewCaseForOperation(it) }
        if (existing != null) {
            val updated = existing.copy(
                currentOperationId = operation.id,
                state = "open",
                reasonCodesJson = jsonArray(listOf(reason)),
                updatedAt = now
            )
            check(dao.updateReviewCase(updated) == 1)
            return updated.id
        }
        val review = SmsReviewCaseEntity(
            id = UUID.randomUUID().toString(),
            sourceId = operation.sourceId,
            currentOperationId = operation.id,
            state = "open",
            revision = 0,
            reasonCodesJson = jsonArray(listOf(reason)),
            draftJson = null,
            stableEventIdsJson = jsonArray(listOf(operation.stableEventId)),
            createdAt = now,
            updatedAt = now
        )
        check(dao.insertReviewCase(review) != -1L)
        return review.id
    }

    private suspend fun requireOwned(
        claim: SmsOperationClaim,
        now: Long
    ): SmsProcessingOperationEntity {
        val operation = dao.getOperation(claim.operationId)
            ?: throw SmsProcessingStoreException("SMS operation not found")
        check(
            operation.ownerToken == claim.ownerToken &&
                operation.ownerGeneration == claim.ownerGeneration &&
                (operation.claimExpiresAt ?: Long.MIN_VALUE) >= now &&
                operation.settledAt == null
        ) { "SMS operation ownership was lost" }
        return operation
    }

    companion object {
        const val CLAIM_LEASE_MS = 120_000L
        const val CLAIM_HEARTBEAT_MS = 15_000L
        private val SHA256_REGEX = Regex("[0-9a-f]{64}")
        private val TRACE_STAGES = setOf(
            "admission",
            "configuration",
            "claim",
            "analysis",
            "triage",
            "selector_execution",
            "selector_validation",
            "reconstruction",
            "account_resolution",
            "persistence_gate",
            "settlement",
            "review",
            "recovery",
            "erase"
        )
        private val TRACE_STATUSES = setOf(
            "pending",
            "running",
            "completed",
            "skipped",
            "failed",
            "interrupted",
            "retained"
        )

        private fun canonicalTracePayload(
            sequence: Long,
            eventId: String,
            occurredAt: Long,
            stage: String,
            status: String,
            reasonCodes: List<String>,
            detailJson: String?,
            previousHash: String?
        ): String = "{" +
            "\"detail\":${detailJson ?: "null"}," +
            "\"event_id\":${JSONObject.quote(eventId)}," +
            "\"occurred_at_epoch_ms\":$occurredAt," +
            "\"previous_event_hash\":${previousHash?.let(JSONObject::quote) ?: "null"}," +
            "\"reason_codes\":${jsonArray(reasonCodes)}," +
            "\"sequence\":$sequence," +
            "\"stage\":${JSONObject.quote(stage)}," +
            "\"status\":${JSONObject.quote(status)}}"

        internal fun jsonArray(values: List<String>): String =
            values.joinToString(prefix = "[", postfix = "]", separator = ",") {
                JSONObject.quote(it)
            }

        internal fun sha256(value: String): String = MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
