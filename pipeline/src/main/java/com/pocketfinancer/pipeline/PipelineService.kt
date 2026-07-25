package com.pocketfinancer.pipeline

import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.inference.SlmChatMessage
import com.pocketfinancer.inference.SlmExtractionRequest
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmPerformanceData
import com.pocketfinancer.sms.SmsReader
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     * A typed pipeline outcome keeps "not a transaction", cancellation, and
     * operational failure distinct for WorkManager retry and UI reporting.
     */
    sealed interface ProcessingResult {
        data class Saved(
            val transaction: ExtractionParser.ExtractedTransaction
        ) : ProcessingResult

        data class Skipped(val reason: SkipReason) : ProcessingResult

        data object Stopped : ProcessingResult

        data class Failure(
            val message: String,
            val retryable: Boolean
        ) : ProcessingResult
    }

    /**
     * Process exactly one SMS using an already-owned residency lease.
     *
     * GBNF is snapshotted before any suspension. Prompt construction and
     * database work stay outside the coordinator's native slot; chat-template
     * rendering, token/session work, and inference are performed atomically by
     * [SlmLease.extract]. The caller must keep [lease] alive until this method
     * returns so maintenance/reset cannot interleave with persistence.
     */
    suspend fun processSingle(
        sms: SmsReader.SmsMessage,
        lease: SlmLease
    ): ProcessingResult {
        val gbnfEnabledForSms = slmProcessingPreferences.gbnfGrammarEnabled.value

        if (!smsFilterPipeline.isTransactional(sms.address, sms.body)) {
            emit(Stage.SKIPPED, "Not a transactional SMS")
            return ProcessingResult.Skipped(SkipReason.NOT_TRANSACTION)
        }

        emit(Stage.EXTRACTING, "Processing SMS from ${sms.address}")

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
                        content = "You are a helpful financial SMS extraction assistant."
                    ),
                    SlmChatMessage(role = "user", content = rawPrompt)
                ),
                fallbackPrompt = fallbackPrompt,
                staticPrefix = promptBuilder.getStaticPrefix(),
                grammar = if (gbnfEnabledForSms) extractionGrammar else null,
                thinkingTokens = 1024,
                answerTokens = 256
            )

            when (val result = lease.extract(request)) {
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

                is SlmExtractionResult.Success -> persistSuccess(sms, result)
            }
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
        result: SlmExtractionResult.Success
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

        val inferredBank = inferBankFromSender(sms.address)
        val account = if (parsed.account != null) {
            accountRepository.getOrCreate(parsed.account, inferredBank, "auto-extracted")
        } else {
            accountRepository.ensureDefault()
        }

        val merchantName = parsed.counterparty
            ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
            ?: if (inferredBank != "Unknown Account") {
                "Transaction ($inferredBank)"
            } else {
                "Unknown Merchant"
            }

        transactionRepository.insert(
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
                slmModelName = File(result.model.modelPath).name
            )
        )

        emit(Stage.SAVED, "Transaction saved: ₹${parsed.amount} ${parsed.type.name}")
        return ProcessingResult.Saved(parsed)
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

    private companion object {
        const val GRAMMAR_ASSET = "sms_extraction.gbnf"
    }
}
