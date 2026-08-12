package com.pocketfinancer.pipeline

import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.inference.SlmChatMessage
import com.pocketfinancer.inference.SlmCacheDiagnostics
import com.pocketfinancer.inference.SlmExtractionRequest
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmPerformanceData
import com.pocketfinancer.inference.SlmTokenCallback
import com.pocketfinancer.sms.SmsReader
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds one extraction request and persists its result.
 *
 * Native access is owned exclusively by [SlmLease]. The caller chooses the
 * residency scope: workers normally hold a temporary lease for one SMS while
 * foreground batches keep a lease across the batch and call [processSingle]
 * once per item. A lease prevents eviction but does not reserve the native
 * execution lane, so independent FIFO requests can run between batch items.
 */
@Singleton
class PipelineService @Inject constructor(
    private val promptBuilder: PromptBuilder,
    private val extractionParser: ExtractionParser,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val slmProcessingPreferences: SlmProcessingPreferences,
    private val modelStorage: SlmModelStorage
) {
    private val _pipelineState = MutableStateFlow<PipelineStep?>(null)
    val pipelineState: StateFlow<PipelineStep?> = _pipelineState.asStateFlow()

    private val extractionGrammar: String by lazy {
        modelStorage.readTextAsset(GRAMMAR_ASSET)
    }

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
        EXTRACTION_REJECTED
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
        data object DeterministicFilterStarted : ProcessingEvent

        data object DeterministicFilterRejected : ProcessingEvent

        data object DeterministicFilterPassed : ProcessingEvent

        data class InferenceStarted(
            val model: SlmModelSpec,
            val thinkingEnabled: Boolean,
            val grammarEnabled: Boolean,
            val thinkingTokenBudget: Int,
            val answerTokenBudget: Int
        ) : ProcessingEvent

        data class ThinkingTokenDelta(val delta: String) : ProcessingEvent

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
        data class Saved(
            val transaction: ExtractionParser.ExtractedTransaction,
            val newlyInserted: Boolean = true
        ) : ProcessingResult

        data class Skipped(val reason: SkipReason) : ProcessingResult

        data object Stopped : ProcessingResult

        data class Failure(
            val message: String,
            val retryable: Boolean
        ) : ProcessingResult
    }

    /**
     * The ledger already owns [committedResult]. This must never be flattened
     * into [ProcessingResult.Failure], which is reserved for work that did not
     * produce a committed transaction.
     */
    class PostPersistenceCommitException internal constructor(
        val committedResult: ProcessingResult.Saved,
        cause: Exception
    ) : RuntimeException(
        "Ledger committed, but post-commit settlement failed",
        cause
    )

    /**
     * Process exactly one SMS using an already-owned residency lease.
     *
     * GBNF is snapshotted before any suspension. Prompt construction and
     * database work stay outside the coordinator's native slot; chat-template
     * rendering, token/session work, and inference are performed atomically by
     * [SlmLease.extract]. The caller must keep [lease] alive until this method
     * returns so maintenance/reset cannot interleave with persistence.
     * [onPersistenceCommitted] runs inside the same non-cancellable boundary
     * as a successful ledger insert, allowing batch owners to durably settle
     * their per-item progress before a racing cancellation can unwind them.
     * [observer] receives only this invocation's progress. Non-fatal observer
     * failures are isolated; fatal VM, thread, and linkage failures propagate.
     */
    suspend fun processSingle(
        sms: SmsReader.SmsMessage,
        lease: SlmLease,
        onPersistenceCommitted: (ProcessingResult.Saved) -> Unit = {},
        observer: ProcessingObserver? = null
    ): ProcessingResult {
        val gbnfEnabledForSms = slmProcessingPreferences.gbnfGrammarEnabled.value
        val fatalObserverFailure = AtomicReference<Throwable?>(null)

        notifyObserver(observer, ProcessingEvent.DeterministicFilterStarted)
        if (!smsFilterPipeline.isTransactional(sms.address, sms.body)) {
            notifyObserver(observer, ProcessingEvent.DeterministicFilterRejected)
            emit(Stage.SKIPPED, "Not a transactional SMS")
            return ProcessingResult.Skipped(SkipReason.NOT_TRANSACTION)
        }
        notifyObserver(observer, ProcessingEvent.DeterministicFilterPassed)

        // The shared legacy state is not invocation-keyed, so never retain
        // source evidence in it. Detailed consumers use the scoped observer.
        emit(Stage.EXTRACTING, "Processing transactional SMS")

        return try {
            val rawPrompt = promptBuilder.buildExtractionPrompt(sms.address, sms.body)
            val fallbackPrompt = promptBuilder.buildChatPrompt(
                rawPrompt = rawPrompt,
                enableThinking = lease.model.hasThinkingMode
            )
            val request = SlmExtractionRequest(
                messages = listOf(
                    SlmChatMessage(
                        role = "system",
                        content = EXTRACTION_SYSTEM_MESSAGE
                    ),
                    SlmChatMessage(role = "user", content = rawPrompt)
                ),
                fallbackPrompt = fallbackPrompt,
                staticPrefix = promptBuilder.getStaticPrefix(),
                grammar = if (gbnfEnabledForSms) extractionGrammar else null,
                thinkingTokens = 1024,
                answerTokens = 256,
                thinkingCallback = observer?.let { activeObserver ->
                    SlmTokenCallback { token ->
                        notifyObserver(
                            activeObserver,
                            ProcessingEvent.ThinkingTokenDelta(token),
                            fatalObserverFailure
                        )
                    }
                },
                jsonCallback = observer?.let { activeObserver ->
                    SlmTokenCallback { token ->
                        notifyObserver(
                            activeObserver,
                            ProcessingEvent.JsonTokenDelta(token),
                            fatalObserverFailure
                        )
                    }
                }
            )

            notifyObserver(
                observer,
                ProcessingEvent.InferenceStarted(
                    model = lease.model,
                    thinkingEnabled = lease.model.hasThinkingMode,
                    grammarEnabled = gbnfEnabledForSms,
                    thinkingTokenBudget = request.thinkingTokens,
                    answerTokenBudget = request.answerTokens
                )
            )
            val result = try {
                lease.extract(request)
            } catch (failure: Throwable) {
                fatalObserverFailure.get()?.let { throw it }
                throw failure
            }
            fatalObserverFailure.get()?.let { throw it }
            notifyObserver(observer, ProcessingEvent.InferenceCompleted(result))

            when (result) {
                is SlmExtractionResult.Null -> {
                    emit(Stage.SKIPPED, "Not a financial transaction", result.perf)
                    ProcessingResult.Skipped(SkipReason.NOT_TRANSACTION)
                }

                is SlmExtractionResult.Error -> {
                    emit(Stage.ERROR, result.message)
                    ProcessingResult.Failure(result.message, retryable = true)
                }

                is SlmExtractionResult.Stopped -> {
                    emit(Stage.ERROR, "Inference stopped")
                    ProcessingResult.Stopped
                }

                is SlmExtractionResult.Success -> persistSuccess(
                    sms = sms,
                    result = result,
                    onPersistenceCommitted = onPersistenceCommitted,
                    observer = observer
                )
            }
        } catch (postCommit: PostPersistenceCommitException) {
            throw postCommit
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val message = error.message ?: "Pipeline processing failed"
            emit(Stage.ERROR, message)
            ProcessingResult.Failure(message = message, retryable = true)
        }
    }

    private suspend fun persistSuccess(
        sms: SmsReader.SmsMessage,
        result: SlmExtractionResult.Success,
        onPersistenceCommitted: (ProcessingResult.Saved) -> Unit,
        observer: ProcessingObserver?
    ): ProcessingResult {
        val perfInfo = result.perf?.let { perf ->
            " | prompt=${perf.tPromptEvalMs}ms gen=${perf.tEvalMs}ms " +
                "${perf.tokensPerSecond.toInt()}tok/s"
        }.orEmpty()
        emit(
            Stage.EXTRACTED,
            "Extracted transaction data$perfInfo",
            perf = result.perf
        )

        val parsed = extractionParser.parse(result.json)
        if (parsed == null) {
            emit(Stage.SKIPPED, "Nonnull filter rejected extraction")
            return ProcessingResult.Skipped(SkipReason.EXTRACTION_REJECTED)
        }

        // This is the per-SMS cancellation/commit boundary. Cancellation that
        // wins before this check leaves the candidate available for a later
        // run. Once persistence starts, a user stop no longer interrupts the
        // short account + ledger handoff; repository failures still follow the
        // normal error path and are not described as an atomic DB transaction.
        currentCoroutineContext().ensureActive()
        notifyObserver(observer, ProcessingEvent.PersistenceStarted)
        val savedResult = withContext(NonCancellable) {
            val inferredBank = inferBankFromSender(sms.address)
            val account = if (parsed.account != null) {
                accountRepository.getOrCreate(
                    parsed.account,
                    inferredBank,
                    "auto-extracted"
                )
            } else {
                accountRepository.ensureDefault()
            }

            val merchantName = parsed.counterparty
                ?.takeIf {
                    it.isNotBlank() && !it.equals("null", ignoreCase = true)
                }
                ?: if (inferredBank != "Unknown Account") {
                    "Transaction ($inferredBank)"
                } else {
                    "Unknown Merchant"
                }

            val insertion = transactionRepository.insertIfAbsent(
                TransactionRepository.NewTransaction(
                    amount = parsed.amount,
                    merchant = merchantName,
                    date = sms.date,
                    type = parsed.type,
                    accountId = account.id,
                    rawMessage = sms.body,
                    sender = sms.address,
                    slmPromptEvalMs = result.perf?.tPromptEvalMs,
                    slmEvalMs = result.perf?.tEvalMs,
                    slmNumTokens = result.perf?.nTokens,
                    slmModelName = File(result.model.modelPath).name,
                    sourceIdentity = sms.sourceIdentity
                )
            )
            val committed = ProcessingResult.Saved(
                transaction = parsed,
                newlyInserted = insertion.inserted
            )
            try {
                onPersistenceCommitted(committed)
            } catch (error: Exception) {
                // The callback is deliberately non-suspending and runs under
                // NonCancellable, so a CancellationException thrown here is a
                // callback failure rather than surrounding job cancellation.
                // Preserve the committed receipt for every callback failure;
                // genuine coroutine cancellation is still rethrown by
                // processSingle outside this boundary.
                throw PostPersistenceCommitException(committed, error)
            }
            committed
        }

        if (savedResult.newlyInserted) {
            emit(Stage.SAVED, "Transaction saved")
        } else {
            emit(Stage.SAVED, "Transaction already saved")
        }
        return savedResult
    }

    private fun inferBankFromSender(sender: String): String {
        val upper = sender.uppercase()
        return when {
            upper.contains("HDFC") -> "HDFC Bank"
            upper.contains("AXIS") -> "Axis Bank"
            upper.contains("ICICI") -> "ICICI Bank"
            upper.contains("SBI") -> "State Bank of India"
            upper.contains("KOTAK") -> "Kotak Bank"
            else -> "Unknown Account"
        }
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

    private fun notifyObserver(
        observer: ProcessingObserver?,
        event: ProcessingEvent,
        fatalFailure: AtomicReference<Throwable?>? = null
    ) {
        try {
            observer?.onEvent(event)
        } catch (error: Throwable) {
            when (error) {
                is VirtualMachineError,
                is ThreadDeath,
                is LinkageError -> {
                    fatalFailure?.compareAndSet(null, error)
                    throw error
                }
            }
            // Progress reporting is strictly observational. In particular, a
            // callback-thrown CancellationException or AssertionError cannot change
            // the owning pipeline operation's outcome. Fatal VM and runtime
            // integrity failures are deliberately not swallowed.
        }
    }

    companion object {
        const val EXTRACTION_SYSTEM_MESSAGE =
            "You are a helpful financial SMS extraction assistant."

        private const val GRAMMAR_ASSET = "sms_extraction.gbnf"
    }
}
