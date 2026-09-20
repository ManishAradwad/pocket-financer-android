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
import com.pocketfinancer.data.db.entity.AccountAliasEntity
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
    val accounts: List<AccountEntity>,
    val groundedProposal: SmsReviewProposal?,
    val draftProposal: SmsReviewProposal?,
    val draftCorrections: List<SmsFieldCorrection>
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

    suspend fun activeOperations(): List<SmsProcessingOperationEntity> = dao.getActiveOperations()

    suspend fun details(reviewCaseId: String): SmsReviewDetails {
        val review = dao.getReviewCase(reviewCaseId)
            ?: throw SmsProcessingStoreException("Review case not found")
        val source = dao.getSource(review.sourceId)
            ?: throw SmsProcessingStoreException("Admitted SMS source not found")
        val operation = dao.getOperation(review.currentOperationId)
            ?: throw SmsProcessingStoreException("Processing operation not found")
        val reconstructed = dao.getReconstructedResult(operation.id)
        val proposal = SmsReviewGrounding.proposal(
            reconstructed?.semanticResultJson,
            source.rawMessage
        )
        val draftCorrections = runCatching {
            SmsReviewGrounding.correctionsFromJson(review.draftJson)
        }.getOrDefault(emptyList())
        val draftProposal = proposal?.let { base ->
            runCatching {
                SmsReviewGrounding.applyCorrections(
                    base,
                    source.rawMessage,
                    draftCorrections.filter {
                        it.evidenceJson != null || it.field == "counterparty"
                    }
                )
            }.getOrNull()
        }
        return SmsReviewDetails(
            review,
            source,
            operation,
            dao.getTrace(operation.id),
            reconstructed,
            dao.getPersistenceDecision(operation.id),
            dao.getFeedbackHistory(review.id),
            accountDao.getAllOnce(),
            proposal,
            draftProposal,
            draftCorrections
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
        if (operation.contractReleaseId == "native-integration-v4") {
            return projectV4Review(operation, source, command, now)
        }
        val result = dao.getReconstructedResult(operationId)?.semanticResultJson
            ?.let(::JSONObject)
            ?: run {
                check(command.action == SmsReviewAction.CORRECT) { "Grounded proposal is unavailable" }
                val requiredFields = setOf("amount_minor_units", "currency", "direction",
                    "counterparty", "account_id", "occurred_at_epoch_ms")
                require(command.corrections.map { it.field }.toSet() == requiredFields) {
                    "A complete correction is required without a grounded proposal"
                }
                // This is a user projection only; never fabricate a reconstructed model result.
                JSONObject()
            }
        var minorUnits = result.optLong("minor_units", 0)
        var currency = result.optString("currency", "").uppercase(java.util.Locale.ROOT)
        var direction = result.optString("direction", "")
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
                "amount_minor_units" -> minorUnits = value.exactLongOrNull()
                    ?: throw IllegalArgumentException("Corrected amount must be an integer")
                "currency" -> currency = (value as? String)?.uppercase()
                    ?: throw IllegalArgumentException("Corrected currency must be text")
                "direction" -> direction = value as? String
                    ?: throw IllegalArgumentException("Corrected direction must be text")
                "counterparty" -> merchant = (value as? String)?.trim().orEmpty()
                "account_id" -> accountId = value as? String
                "occurred_at_epoch_ms" -> occurredAt = value.exactLongOrNull()
                else -> throw IllegalArgumentException("Unsupported correction field")
            }
        }
        val scale = CurrencyScaleRegistry.scale(currency)
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
            currencyProvenance = if (command.corrections.any { it.field == "currency" }) {
                "user_supplied"
            } else result.optString("currency_provenance", "unknown"),
            timestampProvenance = if (command.corrections.any { it.field == "occurred_at_epoch_ms" }) {
                "user_corrected_time"
            } else result.optString("timestamp_provenance", "unknown"),
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
                revision = revisionNumber,
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

    private suspend fun projectV4Review(
        operation: SmsProcessingOperationEntity,
        source: AdmittedSmsSourceEntity,
        command: SmsReviewCommand,
        now: Long
    ): String {
        val configuration = JSONObject(operation.configurationJson)
        check(configuration.optString("config_hash") == operation.configurationHash) {
            "Stored configuration hash changed"
        }
        val payload = JSONObject(operation.configurationJson).apply { remove("config_hash") }
        check(SmsProcessingStore.sha256(canonicalJson(payload)) == operation.configurationHash) {
            "Stored configuration payload changed"
        }
        check(configuration.optString("contract") == "pocketfinancer.processing-config/4")
        check(configuration.getJSONObject("contract_release").optString("release_id") == "native-integration-v4")
        check(configuration.getJSONObject("persistence_policy").optString("rollout_mode") == "review_only")
        val extractor = configuration.getJSONObject("extractor")
        check(extractor.optString("model_identity_kind") == "file_sha256")
        check(extractor.optString("model_file_sha256").matches(Regex("[0-9a-f]{64}")))
        check(configuration.optString("source_ref_hash") == SmsProcessingStore.sha256(source.id)) {
            "Stored source reference changed"
        }
        val analysis = requireNotNull(dao.getAnalysis(operation.id)) {
            "Stored analysis is unavailable"
        }
        check(analysis.configurationHash == operation.configurationHash) {
            "Analysis configuration changed after extraction"
        }
        check(analysis.sourceHash == SmsProcessingStore.sha256(source.rawMessage)) {
            "SMS source changed after extraction"
        }
        val resultEntity = dao.getReconstructedResult(operation.id)
            ?: throw SmsProcessingStoreException("Grounded proposal is unavailable")
        val base = SmsReviewGrounding.proposal(resultEntity.semanticResultJson, source.rawMessage)
            ?: throw SmsProcessingStoreException("Grounded proposal is invalid")
        check(base.duplicateIdempotencyKey == source.id) {
            "Duplicate assessment source identity changed"
        }
        check(base.duplicateSourceEventKey == operation.stableEventId) {
            "Duplicate assessment event identity changed"
        }
        check(base.duplicateStatus != "already_persisted") { "This source event was already saved" }
        val allowed = setOf("amount", "direction", "account", "counterparty")
        require(command.corrections.all { it.field in allowed })
        require(command.corrections.map { it.field }.toSet().size == command.corrections.size)
        val projection = SmsReviewGrounding.applyCorrections(
            base,
            source.rawMessage,
            command.corrections
        )

        val normalizedAlias = if ('@' in projection.accountReference) {
            "vpa:${projection.accountReference}"
        } else {
            "suffix:${projection.accountReference}"
        }
        val aliasHash = SmsProcessingStore.sha256(normalizedAlias)
        val matchingAccounts = revisionDao.findConfirmedAliases(
            aliasHash,
            GroundedAccountResolver.MATCHING_SCOPE
        ).mapNotNull { accountDao.getById(it.accountId) }.distinctBy { it.id }
        check(matchingAccounts.size <= 1) { "Account reference is ambiguous" }
        val account = matchingAccounts.singleOrNull() ?: run {
            val display = if ('@' in projection.accountReference) {
                "UPI account ${projection.accountReference}"
            } else {
                "Account ••${projection.accountReference.takeLast(4)}"
            }
            AccountEntity(
                id = UUID.randomUUID().toString(),
                name = display,
                bank = source.sender.ifBlank { "Unknown Account" },
                type = "sms-review",
                createdAt = now,
                updatedAt = now
            ).also { created ->
                accountDao.insert(created)
                revisionDao.insertAccountAlias(
                    AccountAliasEntity(
                        id = UUID.randomUUID().toString(),
                        accountId = created.id,
                        normalizedAliasHash = aliasHash,
                        aliasKind = if ('@' in projection.accountReference) "vpa" else "suffix",
                        matchingScope = GroundedAccountResolver.MATCHING_SCOPE,
                        confirmedByUser = true,
                        createdAt = now
                    )
                )
            }
        }

        check(transactionDao.getBySourceEvent(source.id, operation.stableEventId) == null) {
            "This SMS event already has a transaction"
        }
        val fingerprint = SmsProcessingStore.sha256(
            "${projection.amountMinorUnits}\u0000${projection.currency}\u0000" +
                "${projection.direction}\u0000${account.id}\u0000${projection.receiptTimestampEpochMs}"
        )
        // Exact source/event duplicates are blocked above. A semantic fingerprint
        // match remains visible in review and is resolved by this explicit action.
        @Suppress("UNUSED_VARIABLE")
        val matchingFingerprints = dao.countMatchingTransactionFingerprints(operation.id, fingerprint)

        val transactionId = UUID.nameUUIDFromBytes(
            "${source.id}|${operation.stableEventId}".toByteArray(Charsets.UTF_8)
        ).toString()
        val revisionId = UUID.randomUUID().toString()
        val merchant = projection.counterparty ?: "Unspecified counterparty"
        val scale = requireNotNull(CurrencyScaleRegistry.scale(projection.currency))
        val transaction = TransactionEntity(
            id = transactionId,
            amount = BigDecimal.valueOf(projection.amountMinorUnits)
                .movePointLeft(scale).toDouble(),
            merchant = merchant,
            date = projection.receiptTimestampEpochMs,
            type = projection.direction,
            accountId = account.id,
            rawMessage = source.rawMessage,
            sender = source.sender,
            isEdited = command.corrections.isNotEmpty(),
            createdAt = now,
            updatedAt = now,
            slmModelName = "direct-sms-extractor-v4",
            sourceConnector = source.sourceConnector,
            sourceProviderMessageId = source.sourceProviderMessageId,
            sourceMessageId = source.sourceMessageId,
            sourceFingerprint = source.sourceFingerprint,
            sourceAlternateFingerprint = source.sourceAlternateFingerprint,
            sourceId = source.id,
            sourceEventId = operation.stableEventId,
            exactMinorUnits = projection.amountMinorUnits,
            currencyCode = projection.currency,
            currencyScale = scale,
            currencyProvenance = "review_source_span",
            timestampProvenance = "${projection.receiptProvenance}_read_only",
            currentRevisionId = revisionId,
            projectionState = "current",
            legacyPrecisionStatus = "exact_minor_units"
        )
        check(transactionDao.insert(transaction) != -1L) { "Transaction projection conflict" }
        revisionDao.insertRevision(
            TransactionRevisionEntity(
                id = revisionId,
                transactionId = transactionId,
                sourceId = source.id,
                stableEventId = operation.stableEventId,
                revision = 0,
                previousRevisionId = null,
                operationId = operation.id,
                feedbackActionId = command.actionId,
                exactMinorUnits = projection.amountMinorUnits,
                currencyCode = projection.currency,
                currencyScale = scale,
                direction = projection.direction,
                merchant = merchant,
                accountId = account.id,
                occurredAt = projection.receiptTimestampEpochMs,
                provenance = if (command.corrections.isEmpty()) {
                    "user_confirmed_grounded_proposal"
                } else {
                    "user_confirmed_source_span_revision"
                },
                isCurrentProjection = true,
                createdAt = now
            )
        )
        return revisionId
    }

    private fun Any.exactLongOrNull(): Long? = when (this) {
        is Long -> this
        is Int -> toLong()
        else -> null
    }

    private fun decodeScalar(json: String): Any {
        val value = JSONTokener(json).nextValue()
        require(value !is JSONObject && value !is org.json.JSONArray && value != JSONObject.NULL)
        return value
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(
            prefix = "{", postfix = "}", separator = ","
        ) { key -> "${JSONObject.quote(key)}:${canonicalJson(value.get(key))}" }
        is org.json.JSONArray -> (0 until value.length()).joinToString(
            prefix = "[", postfix = "]", separator = ","
        ) { canonicalJson(value.get(it)) }
        is String -> JSONObject.quote(value)
        is Boolean, is Int, is Long -> value.toString()
        else -> error("Unsupported canonical JSON value")
    }

    private fun validate(command: SmsReviewCommand) {
        require(command.expectedRevision >= 0)
        require(command.corrections.map { it.field }.distinct().size == command.corrections.size) {
            "Duplicate correction fields are not permitted"
        }
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
