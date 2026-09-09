package com.pocketfinancer.data.repository

import androidx.room.withTransaction
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.dao.SmsProcessingDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.dao.TransactionRevisionDao
import com.pocketfinancer.data.db.entity.TransactionEntity
import com.pocketfinancer.data.db.entity.TransactionRevisionEntity
import com.pocketfinancer.data.db.entity.SmsUserFeedbackEventEntity
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsPersistenceDecisionEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import com.pocketfinancer.data.db.entity.SmsProcessingTraceEventEntity
import com.pocketfinancer.data.db.entity.SmsReconstructedResultEntity
import com.pocketfinancer.data.db.entity.SmsReviewCaseEntity
import org.json.JSONObject
import org.json.JSONTokener
import java.math.BigDecimal
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class SmsReviewAction(val wireValue: String) {
    CONFIRM("confirm"),
    CORRECT("correct"),
    REJECT("reject"),
    RESOLVE_MULTIPLE_EVENTS("resolve_multiple_events"),
    SAVE_DRAFT("save_draft"),
    RETRY("retry")
}

enum class SmsFieldGroundingClassification(val wireValue: String) {
    SELECTED_EXISTING_CANDIDATE("selected_existing_candidate"),
    CHANGED_INTERPRETATION_AMONG_CANDIDATES("changed_interpretation_among_candidates"),
    SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS("supplied_source_supported_candidate_miss"),
    SUPPLIED_MANUAL_UNGROUNDED_VALUE("supplied_manual_ungrounded_value")
}

data class SmsFieldCorrection(
    val field: String,
    val classification: SmsFieldGroundingClassification,
    val previousRevisionId: String?,
    val candidateId: String?,
    val evidenceJson: String?,
    val newValueJson: String
)

data class SmsReviewCommand(
    val actionId: String,
    val reviewCaseId: String,
    val expectedRevision: Long,
    val action: SmsReviewAction,
    val corrections: List<SmsFieldCorrection> = emptyList(),
    val retryConfiguration: String? = null
)

data class SmsReviewReceipt(
    val actionId: String,
    val reviewCaseId: String,
    val resultingRevision: Long,
    val replayed: Boolean
)

data class SmsReviewDetails(
    val reviewCase: SmsReviewCaseEntity,
    val source: AdmittedSmsSourceEntity,
    val operation: SmsProcessingOperationEntity,
    val trace: List<SmsProcessingTraceEventEntity>,
    val reconstructedResult: SmsReconstructedResultEntity?,
    val persistenceDecision: SmsPersistenceDecisionEntity?,
    val feedback: List<SmsUserFeedbackEventEntity>,
    val accounts: List<AccountEntity>
)

@Singleton
class SmsReviewRepository @Inject constructor(
    private val database: AppDatabase,
    private val dao: SmsProcessingDao,
    private val transactionDao: TransactionDao,
    private val revisionDao: TransactionRevisionDao,
    private val accountDao: AccountDao
) {
    suspend fun openCases(): List<SmsReviewCaseEntity> = dao.getOpenReviewCases()

    suspend fun details(reviewCaseId: String): SmsReviewDetails {
        val review = dao.getReviewCase(reviewCaseId)
            ?: throw SmsProcessingStoreException("Review case not found")
        val source = dao.getSource(review.sourceId)
            ?: throw SmsProcessingStoreException("Admitted SMS source not found")
        val operation = dao.getOperation(review.currentOperationId)
            ?: throw SmsProcessingStoreException("Processing operation not found")
        return SmsReviewDetails(
            review,
            source,
            operation,
            dao.getTrace(operation.id),
            dao.getReconstructedResult(operation.id),
            dao.getPersistenceDecision(operation.id),
            dao.getFeedbackHistory(review.id),
            accountDao.getAllOnce()
        )
    }

    suspend fun resolve(command: SmsReviewCommand, now: Long): SmsReviewReceipt =
        database.withTransaction {
        require(UUID.fromString(command.actionId).toString() == command.actionId.lowercase())
        dao.getFeedbackByAction(command.actionId)?.let { event ->
            check(event.reviewCaseId == command.reviewCaseId) {
                "Feedback action belongs to another review"
            }
            return@withTransaction SmsReviewReceipt(
                event.actionId,
                requireNotNull(event.reviewCaseId),
                event.resultingReviewRevision,
                replayed = true
            )
        }
        val review = dao.getReviewCase(command.reviewCaseId)
            ?: throw SmsProcessingStoreException("Review case not found")
        check(review.revision == command.expectedRevision) { "Review revision conflict" }
        validate(command)
        val resultingRevision = command.expectedRevision + 1
        val previousHash = dao.getLatestFeedback(command.reviewCaseId)?.eventHash
        val correctionsJson = correctionsJson(command.corrections)
        val transactionRevisionId = if (
            command.action == SmsReviewAction.CONFIRM || command.action == SmsReviewAction.CORRECT
        ) {
            projectReview(review.currentOperationId, command, now)
        } else {
            null
        }
        val eventHash = SmsProcessingStore.sha256(
            canonicalFeedback(
                command = command,
                operationId = review.currentOperationId,
                resultingRevision = resultingRevision,
                correctionsJson = correctionsJson,
                previousHash = previousHash
            )
        )
        val event = SmsUserFeedbackEventEntity(
            actionId = command.actionId,
            reviewCaseId = command.reviewCaseId,
            operationId = review.currentOperationId,
            transactionId = transactionRevisionId?.let { revisionId ->
                revisionDao.getById(revisionId)?.transactionId
            },
            transactionRevisionId = transactionRevisionId,
            expectedReviewRevision = command.expectedRevision,
            resultingReviewRevision = resultingRevision,
            action = command.action.wireValue,
            actorClass = "user",
            actorIdHash = SmsProcessingStore.sha256("local-owner"),
            correctionsJson = correctionsJson,
            retryConfiguration = command.retryConfiguration,
            canonicalLabelId = null,
            canonicalLabelRevision = null,
            previousEventHash = previousHash,
            eventHash = eventHash,
            createdAt = now
        )
        val state = when (command.action) {
            SmsReviewAction.CONFIRM,
            SmsReviewAction.RESOLVE_MULTIPLE_EVENTS -> "confirmed"
            SmsReviewAction.CORRECT -> "corrected"
            SmsReviewAction.REJECT -> "rejected"
            SmsReviewAction.SAVE_DRAFT -> "draft"
            SmsReviewAction.RETRY -> "waiting_retry"
        }
        val draft = if (
            command.action == SmsReviewAction.SAVE_DRAFT ||
            command.action == SmsReviewAction.CORRECT
        ) {
            correctionsJson
        } else {
            null
        }
        check(dao.insertFeedbackAndAdvanceReview(event, state, draft, now))
        SmsReviewReceipt(
            command.actionId,
            command.reviewCaseId,
            resultingRevision,
            replayed = false
        )
    }

    private suspend fun projectReview(
        operationId: String,
        command: SmsReviewCommand,
        now: Long
    ): String {
        val operation = dao.getOperation(operationId)
            ?: throw SmsProcessingStoreException("Processing operation not found")
        val source = dao.getSource(operation.sourceId)
            ?: throw SmsProcessingStoreException("Admitted SMS source not found")
        val result = dao.getReconstructedResult(operationId)?.semanticResultJson
            ?.let(::JSONObject)
            ?: throw SmsProcessingStoreException("Grounded proposal is unavailable")
        var minorUnits = result.getLong("minor_units")
        var currency = result.getString("currency").uppercase()
        var scale = result.getInt("currency_scale")
        var direction = result.getString("direction")
        var merchant = if (result.isNull("counterparty_evidence")) {
            "Unspecified counterparty"
        } else {
            result.optString("counterparty_evidence").ifBlank { "Unspecified counterparty" }
        }
        var occurredAt = result.optLong("occurred_at_epoch_ms", Long.MIN_VALUE)
            .takeUnless { it == Long.MIN_VALUE }
        var accountId = dao.getPersistenceDecision(operationId)?.accountResolutionJson
            ?.let(::JSONObject)
            ?.takeIf { it.optString("result") == "unique" }
            ?.optString("account_id")
            ?.takeIf(String::isNotBlank)
        command.corrections.forEach { correction ->
            val value = decodeScalar(correction.newValueJson)
            when (correction.field) {
                "amount_minor_units" -> minorUnits = (value as? Number)?.toLong()
                    ?: throw IllegalArgumentException("Corrected amount must be an integer")
                "currency" -> currency = (value as? String)?.uppercase()
                    ?: throw IllegalArgumentException("Corrected currency must be text")
                "direction" -> direction = value as? String
                    ?: throw IllegalArgumentException("Corrected direction must be text")
                "counterparty" -> merchant = (value as? String)?.trim().orEmpty()
                "account_id" -> accountId = value as? String
                "occurred_at_epoch_ms" -> occurredAt = (value as? Number)?.toLong()
                else -> throw IllegalArgumentException("Unsupported correction field")
            }
        }
        scale = CurrencyScaleRegistry.scale(currency)
            ?: throw IllegalArgumentException("Unsupported corrected currency")
        require(minorUnits > 0 && direction in setOf("debit", "credit"))
        require(merchant.isNotBlank())
        val resolvedOccurredAt = occurredAt
            ?: throw IllegalArgumentException("A transaction time must be selected")
        val resolvedAccountId = accountId?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("An owned account must be selected")
        check(accountDao.getById(resolvedAccountId) != null) { "Selected account does not exist" }

        val transactionId = transactionDao.getBySourceEvent(source.id, operation.stableEventId)?.id
            ?: UUID.nameUUIDFromBytes(
                "${source.id}|${operation.stableEventId}".toByteArray(Charsets.UTF_8)
            ).toString()
        val history = revisionDao.getHistory(transactionId)
        val previous = history.firstOrNull { it.isCurrentProjection }
        val revisionId = UUID.randomUUID().toString()
        val revisionNumber = (history.maxOfOrNull { it.revision } ?: -1) + 1
        val compatibleAmount = BigDecimal.valueOf(minorUnits)
            .movePointLeft(scale)
            .toDouble()
        val existing = transactionDao.getById(transactionId)
        val projection = TransactionEntity(
            id = transactionId,
            amount = compatibleAmount,
            merchant = merchant,
            date = resolvedOccurredAt,
            type = direction,
            accountId = resolvedAccountId,
            rawMessage = source.rawMessage,
            sender = source.sender,
            isEdited = command.action == SmsReviewAction.CORRECT,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now,
            slmModelName = "grounded-candidate-selector",
            sourceConnector = source.sourceConnector,
            sourceProviderMessageId = source.sourceProviderMessageId,
            sourceMessageId = source.sourceMessageId,
            sourceFingerprint = source.sourceFingerprint,
            sourceAlternateFingerprint = source.sourceAlternateFingerprint,
            sourceId = source.id,
            sourceEventId = operation.stableEventId,
            exactMinorUnits = minorUnits,
            currencyCode = currency,
            currencyScale = scale,
            currencyProvenance = result.optString("currency_provenance", "unknown"),
            timestampProvenance = result.optString("timestamp_provenance", "user_corrected_time"),
            currentRevisionId = revisionId,
            projectionState = "current",
            legacyPrecisionStatus = "exact_minor_units"
        )
        if (existing == null) {
            check(transactionDao.insert(projection) != -1L) { "Transaction projection conflict" }
        } else {
            check(transactionDao.update(projection) == 1) { "Transaction projection disappeared" }
        }
        revisionDao.clearCurrentProjection(transactionId)
        revisionDao.insertRevision(
            TransactionRevisionEntity(
                id = revisionId,
                transactionId = transactionId,
                sourceId = source.id,
                stableEventId = operation.stableEventId,
                revision = revisionNumber.toLong(),
                previousRevisionId = previous?.id,
                operationId = operation.id,
                feedbackActionId = command.actionId,
                exactMinorUnits = minorUnits,
                currencyCode = currency,
                currencyScale = scale,
                direction = direction,
                merchant = merchant,
                accountId = resolvedAccountId,
                occurredAt = resolvedOccurredAt,
                provenance = if (command.action == SmsReviewAction.CONFIRM) {
                    "user_confirmed_grounded_proposal"
                } else {
                    "user_corrected_projection"
                },
                isCurrentProjection = true,
                createdAt = now
            )
        )
        return revisionId
    }

    private fun decodeScalar(json: String): Any {
        val value = JSONTokener(json).nextValue()
        require(value !is JSONObject && value !is org.json.JSONArray && value != JSONObject.NULL)
        return value
    }

    private fun validate(command: SmsReviewCommand) {
        require(command.expectedRevision >= 0)
        when (command.action) {
            SmsReviewAction.CORRECT -> {
                require(command.corrections.isNotEmpty())
                require(command.retryConfiguration == null)
            }
            SmsReviewAction.SAVE_DRAFT,
            SmsReviewAction.RESOLVE_MULTIPLE_EVENTS -> require(command.retryConfiguration == null)
            SmsReviewAction.RETRY -> {
                require(command.corrections.isEmpty())
                require(command.retryConfiguration in setOf("original", "current"))
            }
            SmsReviewAction.CONFIRM,
            SmsReviewAction.REJECT -> {
                require(command.corrections.isEmpty())
                require(command.retryConfiguration == null)
            }
        }
    }

    private fun correctionsJson(corrections: List<SmsFieldCorrection>): String =
        corrections.joinToString(prefix = "[", postfix = "]", separator = ",") { correction ->
            "{" +
                "\"candidate_id\":${correction.candidateId?.let(JSONObject::quote) ?: "null"}," +
                "\"classification\":${JSONObject.quote(correction.classification.wireValue)}," +
                "\"evidence\":${correction.evidenceJson ?: "null"}," +
                "\"field\":${JSONObject.quote(correction.field)}," +
                "\"new_value\":${correction.newValueJson}," +
                "\"previous_revision_id\":" +
                "${correction.previousRevisionId?.let(JSONObject::quote) ?: "null"}}"
        }

    private fun canonicalFeedback(
        command: SmsReviewCommand,
        operationId: String,
        resultingRevision: Long,
        correctionsJson: String,
        previousHash: String?
    ): String = "{" +
        "\"action\":${JSONObject.quote(command.action.wireValue)}," +
        "\"action_id\":${JSONObject.quote(command.actionId)}," +
        "\"corrections\":$correctionsJson," +
        "\"expected_revision\":${command.expectedRevision}," +
        "\"operation_id\":${JSONObject.quote(operationId)}," +
        "\"previous_event_hash\":${previousHash?.let(JSONObject::quote) ?: "null"}," +
        "\"resulting_revision\":$resultingRevision," +
        "\"retry_configuration\":" +
        "${command.retryConfiguration?.let(JSONObject::quote) ?: "null"}," +
        "\"review_case_id\":${JSONObject.quote(command.reviewCaseId)}}"
}
