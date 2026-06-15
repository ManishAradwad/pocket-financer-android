package com.pocketfinancer.pipeline

import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.inference.LlamaEngine
import com.pocketfinancer.sms.SmsReader
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the SMS → SLM → Database pipeline.
 *
 * Processes SMS messages sequentially (one at a time) to avoid concurrent
 * LLM access crashes. Each SMS goes through:
 *   1. Build prompt (system + few-shot + sender + body)
 *   2. Apply chat template (Qwen3 Jinja via model, or manual fallback)
 *   3. Two-phase inference (thinking → GBNF grammar)
 *   4. Parse output (null = skip, JSON = save)
 *   5. Save transaction + find-or-create account
 */
@Singleton
class PipelineService @Inject constructor(
    private val llamaEngine: LlamaEngine,
    private val promptBuilder: PromptBuilder,
    private val extractionParser: ExtractionParser,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val smsFilterPipeline: SmsFilterPipeline
) {
    /** Max SMS in queue before dropping. */
    private val maxQueueLen = 200

    /** Whether the pipeline is currently processing an SMS. */
    private var isProcessing = false

    private val smsQueue = ArrayDeque<SmsReader.SmsMessage>()

    /** Pipeline step events for UI sync strip. */
    private val _pipelineState = MutableStateFlow<PipelineStep?>(null)
    val pipelineState: StateFlow<PipelineStep?> = _pipelineState.asStateFlow()

    /** Loaded GBNF grammar from assets. */
    private val grammar: String by lazy {
        llamaEngine.readAsset("sms_extraction.gbnf")
    }

    data class PipelineStep(
        val stage: Stage,
        val message: String,
        val progress: Int = 0,     // 0-100
        val total: Int = 0,
        val perf: LlamaEngine.PerformanceData? = null   // timing data when available
    )

    enum class Stage {
        ENQUEUED, EXTRACTING, EXTRACTED, SKIPPED, SAVED, ERROR
    }

    /**
     * Enqueue an SMS for processing. Drops if queue is full.
     */
    fun enqueue(sms: SmsReader.SmsMessage) {
        if (!smsFilterPipeline.isTransactional(sms.address, sms.body)) {
            return
        }
        if (smsQueue.size >= maxQueueLen) {
            return
        }
        smsQueue.addLast(sms)
        processQueue()
    }

    /**
     * Enqueue multiple SMS messages (e.g., from history sync).
     */
    fun enqueueBatch(messages: List<SmsReader.SmsMessage>) {
        for (sms in messages) {
            if (!smsFilterPipeline.isTransactional(sms.address, sms.body)) {
                continue
            }
            if (smsQueue.size >= maxQueueLen) break
            smsQueue.addLast(sms)
        }
        processQueue()
    }

    /**
     * Block until all queued SMS are processed. Used during initial sync.
     */
    suspend fun drain(timeoutMs: Long = 30_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (smsQueue.isNotEmpty() && System.currentTimeMillis() < deadline) {
            delay(100)
        }
    }

    val queueSize: Int get() = smsQueue.size

    // ── Internal ─────────────────────────────────────────────────────────

    private fun processQueue() {
        if (isProcessing || smsQueue.isEmpty()) return
        isProcessing = true

        CoroutineScope(Dispatchers.IO).launch {
            while (smsQueue.isNotEmpty()) {
                val sms = smsQueue.removeFirst()
                try {
                    processSingle(sms)
                } catch (e: Exception) {
                    emit(Stage.ERROR, "Pipeline error: ${e.message}")
                }
            }
            isProcessing = false
        }
    }

    /**
     * Apply the chat template to the raw extraction prompt.
     *
     * Primary path: uses the model's built-in Jinja template via
     * LlamaEngine.applyChatTemplate(). Falls back to the manual
     * Qwen3 template (PromptBuilder.buildChatPrompt()) if the
     * model doesn't have a built-in template.
     */
    private fun applyChatTemplate(rawPrompt: String): String {
        // Try model's Jinja template first
        val messages = listOf(
            LlamaEngine.ChatMessage("system", "You are a helpful financial SMS extraction assistant."),
            LlamaEngine.ChatMessage("user", rawPrompt)
        )
        val rendered = llamaEngine.applyChatTemplate(messages, addAssistantPrefix = true)
        if (rendered != null) {
            return rendered
        }
        // Fallback: manual Qwen3 template
        return promptBuilder.buildChatPrompt(rawPrompt, enableThinking = llamaEngine.hasThinkingMode)
    }

    internal suspend fun processSingle(sms: SmsReader.SmsMessage): ExtractionParser.ExtractedTransaction? {
        if (!llamaEngine.isModelLoaded()) {
            emit(Stage.ERROR, "Model not loaded, skipping SMS")
            return null
        }

        emit(Stage.EXTRACTING, "Processing SMS from ${sms.address}")

        // 1. Build raw prompt (system + few-shot + sender + body)
        val rawPrompt = promptBuilder.buildExtractionPrompt(sms.address, sms.body)

        // 2. Apply chat template (model's Jinja or manual fallback)
        val chatPrompt = applyChatTemplate(rawPrompt)

        // 3. Run two-phase inference
        val result = llamaEngine.inferForExtraction(
            prompt = chatPrompt,
            grammar = grammar,
            staticPrefix = promptBuilder.getStaticPrefix(),
            thinkingTokens = 1024,
            answerTokens = 256
        )

        return when (result) {
            is LlamaEngine.InferenceResult.Null -> {
                emit(Stage.SKIPPED, "Not a financial transaction")
                null
            }
            is LlamaEngine.InferenceResult.Error -> {
                emit(Stage.ERROR, result.message)
                null
            }
            is LlamaEngine.InferenceResult.Stopped -> {
                emit(Stage.ERROR, "Inference stopped")
                null
            }
            is LlamaEngine.InferenceResult.Success -> {
                val perfInfo = result.perf?.let { p ->
                    " | prompt=${p.tPromptEvalMs}ms gen=${p.tEvalMs}ms ${p.tokensPerSecond.toInt()}tok/s"
                } ?: ""
                emit(Stage.EXTRACTED, "Extracted transaction data$perfInfo", perf = result.perf)
                // Continue to parsing
                val parsed = extractionParser.parse(result.json)
                if (parsed == null) {
                    emit(Stage.SKIPPED, "Nonnull filter rejected extraction")
                    return null
                }

                // 4. Resolve account
                val account = if (parsed.account != null) {
                    accountRepository.getOrCreate(parsed.account, "Unknown Bank", "auto-extracted")
                } else {
                    accountRepository.ensureDefault()
                }

                // 5. Save transaction
                transactionRepository.insert(
                    TransactionRepository.NewTransaction(
                        amount = parsed.amount,
                        merchant = parsed.counterparty ?: "Unknown Merchant",
                        date = sms.date,
                        type = parsed.type,
                        accountId = account.id,
                        rawMessage = sms.body,
                        sender = sms.address
                    )
                )

                emit(Stage.SAVED, "Transaction saved: ₹${parsed.amount} ${parsed.type.name}")
                parsed
            }
        }
    }

    private fun emit(stage: Stage, message: String, perf: LlamaEngine.PerformanceData? = null) {
        _pipelineState.value = PipelineStep(
            stage = stage,
            message = message,
            total = smsQueue.size,
            perf = perf
        )
    }
}
