package com.pocketfinancer.pipeline

import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.inference.SlmCacheDiagnostics
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmPerformanceData
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.pipeline.sms.AdmittedMessageRef
import com.pocketfinancer.pipeline.sms.DefaultSmsProcessingCoordinator
import com.pocketfinancer.pipeline.sms.SmsOperationSnapshotFactory
import com.pocketfinancer.pipeline.sms.SmsProcessingOutcome
import com.pocketfinancer.pipeline.sms.SmsProcessingObserver
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONObject

/** Admits one SMS and routes it through the grounded processing coordinator. */
@Singleton
class PipelineService @Inject constructor(
    private val smsProcessingStore: SmsProcessingStore,
    private val snapshotFactory: SmsOperationSnapshotFactory,
    private val processingConfiguration: ProcessingConfigurationRepository,
    private val smsProcessingCoordinator: DefaultSmsProcessingCoordinator
) {
    private val _pipelineState = MutableStateFlow<PipelineStep?>(null)
    val pipelineState: StateFlow<PipelineStep?> = _pipelineState.asStateFlow()

    data class PipelineStep(
        val stage: Stage,
        val message: String,
        val progress: Int = 0,
        val total: Int = 0,
        val perf: SlmPerformanceData? = null
    )

    enum class Stage {
        EXTRACTING, EXTRACTED, SKIPPED, SAVED, ERROR
    }

    enum class SkipReason {
        NOT_TRANSACTION,
        EXTRACTION_REJECTED,
        RETAINED_FOR_REVIEW
    }

    /**
     * Receives detailed progress for one [processSingle] invocation.
     *
     * This is deliberately run-local rather than another shared state holder:
     * concurrent callers can render their own SMS without observing events
     * from unrelated background work. Observer failures are always isolated
     * from filtering, inference, and persistence.
     */
    fun interface ProcessingObserver {
        fun onEvent(event: ProcessingEvent)
    }

    sealed interface ProcessingEvent {
        data class GroundedStage(
            val stage: String,
            val status: String,
            val reasonCodes: List<String>
        ) : ProcessingEvent

        data object DeterministicFilterStarted : ProcessingEvent

        data object DeterministicFilterRejected : ProcessingEvent

        data object DeterministicFilterPassed : ProcessingEvent

        data class InferenceStarted(
            val model: SlmModelSpec,
            val grammarEnabled: Boolean,
            val answerTokenBudget: Int
        ) : ProcessingEvent

        data class JsonTokenDelta(val delta: String) : ProcessingEvent

        /**
         * Keeps the exact terminal runtime result while exposing the metadata
         * needed by progress UIs without requiring every consumer to remap it.
         */
        data class InferenceCompleted(
            val result: SlmExtractionResult
        ) : ProcessingEvent {
            val json: String?
                get() = (result as? SlmExtractionResult.Success)?.json

            val perf: SlmPerformanceData?
                get() = when (result) {
                    is SlmExtractionResult.Success -> result.perf
                    is SlmExtractionResult.Null -> result.perf
                    is SlmExtractionResult.Error,
                    is SlmExtractionResult.Stopped -> null
                }

            val cache: SlmCacheDiagnostics?
                get() = when (result) {
                    is SlmExtractionResult.Success -> result.cache
                    is SlmExtractionResult.Null -> result.cache
                    is SlmExtractionResult.Error,
                    is SlmExtractionResult.Stopped -> null
                }

            val model: SlmModelSpec
                get() = result.model
        }

        /** Emitted after cancellation is checked, at the durable handoff. */
        data object PersistenceStarted : ProcessingEvent
    }

    /**
     * A typed pipeline outcome keeps "not a transaction", cancellation, and
     * operational failure distinct for WorkManager retry and UI reporting.
     */
    sealed interface ProcessingResult {
        data class Skipped(val reason: SkipReason) : ProcessingResult

        data object AwaitingConfiguration : ProcessingResult

        data object Stopped : ProcessingResult

        data class Failure(
            val message: String,
            val retryable: Boolean
        ) : ProcessingResult
    }

    /**
     * Process exactly one SMS using an already-owned residency lease. The
     * lease is passed into the coordinator so foreground batches never attempt
     * a nested runtime acquisition.
     */
    suspend fun processSingle(
        sms: SmsReader.SmsMessage,
        lease: SlmLease,
        observer: ProcessingObserver? = null,
        trigger: String = "manual"
    ): ProcessingResult {
        return processThroughGroundedCoordinator(
            sms = sms,
            lease = lease,
            trigger = trigger,
            store = smsProcessingStore,
            snapshotFactory = snapshotFactory,
            configurationRepository = processingConfiguration,
            coordinator = smsProcessingCoordinator,
            observer = observer
        )
    }

    /**
     * Process one durable source without requiring the caller to own model
     * residency. The coordinator resolves the configured model and retains a
     * visible review case when the selector cannot safely run.
     */
    suspend fun processSingle(
        sms: SmsReader.SmsMessage,
        observer: ProcessingObserver? = null,
        trigger: String = "manual"
    ): ProcessingResult {
        return processThroughGroundedCoordinator(
            sms = sms,
            lease = null,
            trigger = trigger,
            store = smsProcessingStore,
            snapshotFactory = snapshotFactory,
            configurationRepository = processingConfiguration,
            coordinator = smsProcessingCoordinator,
            observer = observer
        )
    }

    suspend fun retryReview(reviewCaseId: String, configurationMode: String): ProcessingResult {
        require(configurationMode in setOf("original", "current"))
        val retry = smsProcessingStore.retryContext(reviewCaseId)
        val previousConfiguration = JSONObject(retry.operation.configurationJson)
        val previousCurrencyContext = previousConfiguration.getJSONObject("currency_context")
        val previousSelector = previousConfiguration.getJSONObject("selector")
        val currency = if (configurationMode == "original") {
            previousCurrencyContext.getString("primary_currency")
        } else {
            processingConfiguration.confirmedPrimaryCurrency()
                ?: return ProcessingResult.AwaitingConfiguration
        }
        val profiles = if (configurationMode == "original") {
            previousCurrencyContext.getJSONArray("enabled_profile_ids").let { values ->
                (0 until values.length()).map { values.getString(it) }
            }
        } else {
            processingConfiguration.enabledProfiles(currency)
        }
        val selectorModelId = if (configurationMode == "original") {
            previousSelector.optString("model_identifier")
                .takeIf { it.isNotBlank() && it != "null" }
        } else {
            smsProcessingCoordinator.currentSelectorModelId()
        }
        val source = retry.source
        val reference = AdmittedMessageRef(
            source.id,
            source.admissionReceiptId,
            SmsProcessingStore.sha256(source.rawMessage)
        )
        val snapshot = snapshotFactory.create(
            source = reference,
            trigger = "retry",
            primaryCurrency = currency,
            enabledProfiles = profiles,
            sourceTimestampEpochMs = source.sourceTimestamp,
            sourceTimestampProvenance = if (source.sourceTimestamp == null) {
                "unknown"
            } else {
                "acquisition_supplied_message_time"
            },
            admissionTimestampEpochMs = source.admittedAt,
            selectorModelId = selectorModelId,
            selectorModelHash = null,
            selectorRuntimeVersion = previousSelector.optString(
                "runtime_version", "llama.cpp-jni"
            ),
            deviceCohort = android.os.Build.MODEL
                ?.takeIf { it.isNotBlank() }
                ?: "android-device",
            stableEventId = retry.operation.stableEventId,
            parentOperationId = retry.operation.id
        )
        return mapOutcome(smsProcessingCoordinator.process(reference, snapshot))
    }

    private suspend fun processThroughGroundedCoordinator(
        sms: SmsReader.SmsMessage,
        lease: SlmLease?,
        trigger: String,
        store: SmsProcessingStore,
        snapshotFactory: SmsOperationSnapshotFactory,
        configurationRepository: ProcessingConfigurationRepository,
        coordinator: DefaultSmsProcessingCoordinator,
        observer: ProcessingObserver?
    ): ProcessingResult {
        val sourceIdentity = sms.sourceIdentity
        val sourceId = sourceIdentity.opaqueCandidateKey
        val receiptId = UUID.nameUUIDFromBytes(sourceId.toByteArray(Charsets.UTF_8)).toString()
        val now = System.currentTimeMillis()
        store.admitSource(
            AdmittedSmsSourceEntity(
                id = sourceId,
                sourceConnector = sourceIdentity.connector,
                sourceMessageId = sourceIdentity.messageId,
                sourceProviderMessageId = sourceIdentity.providerMessageId,
                sourceFingerprint = sourceIdentity.fallbackFingerprint,
                sourceAlternateFingerprint = sourceIdentity.alternateFingerprint,
                sender = sms.address,
                rawMessage = sms.body,
                sourceTimestamp = sms.sourceTimestamp,
                messageType = sms.type,
                origin = trigger,
                admissionReceiptId = receiptId,
                admittedAt = now,
                retentionState = "admitted"
            )
        )
        val currency = configurationRepository.confirmedPrimaryCurrency()
            ?: return ProcessingResult.AwaitingConfiguration
        val reference = AdmittedMessageRef(
            sourceId,
            receiptId,
            SmsProcessingStore.sha256(sms.body)
        )
        val snapshot = snapshotFactory.create(
            source = reference,
            trigger = trigger,
            primaryCurrency = currency,
            enabledProfiles = configurationRepository.enabledProfiles(currency),
            sourceTimestampEpochMs = sms.sourceTimestamp,
            sourceTimestampProvenance = "acquisition_supplied_message_time",
            admissionTimestampEpochMs = now,
            selectorModelId = lease?.model?.modelId ?: coordinator.currentSelectorModelId(),
            selectorModelHash = null,
            selectorRuntimeVersion = "llama.cpp-jni",
            deviceCohort = android.os.Build.MODEL
                ?.takeIf { it.isNotBlank() }
                ?: "android-device",
            now = now
        )
        val groundedObserver = SmsProcessingObserver { event ->
            when (event.stage) {
                "analysis", "triage" -> emit(Stage.EXTRACTING, "Analyzing grounded evidence")
                "selector_execution" -> emit(Stage.EXTRACTING, "Selecting grounded candidates")
                "settlement" -> emit(Stage.SKIPPED, "Saved for review")
            }
            runCatching {
                observer?.onEvent(
                    ProcessingEvent.GroundedStage(
                        event.stage,
                        event.status,
                        event.reasonCodes.toList()
                    )
                )
            }
        }
        val outcome = if (lease != null) {
            coordinator.processUsingLease(reference, snapshot, lease, groundedObserver)
        } else {
            coordinator.process(reference, snapshot, groundedObserver)
        }
        return mapOutcome(outcome)
    }

    private fun mapOutcome(outcome: SmsProcessingOutcome): ProcessingResult = when (outcome) {
        is SmsProcessingOutcome.TerminallyDiscarded ->
            ProcessingResult.Skipped(SkipReason.NOT_TRANSACTION)
        is SmsProcessingOutcome.RetainedForReview ->
            ProcessingResult.Skipped(SkipReason.RETAINED_FOR_REVIEW)
        is SmsProcessingOutcome.Persisted -> ProcessingResult.Failure(
            "Automatic persistence is disabled in this build.", retryable = false
        )
        is SmsProcessingOutcome.RetryableFailure -> ProcessingResult.Failure(
            "Saved for retry: ${outcome.reason}", retryable = true
        )
        is SmsProcessingOutcome.Stopped -> ProcessingResult.Stopped
    }

    private fun emit(
        stage: Stage,
        message: String,
        perf: SlmPerformanceData? = null
    ) {
        _pipelineState.value = PipelineStep(
            stage = stage,
            message = message,
            perf = perf
        )
    }

}
