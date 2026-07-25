package com.pocketfinancer.ui.home

import android.content.Context
import android.util.Log
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.resolveActiveSlmTier
import com.pocketfinancer.inference.SlmChatMessage
import com.pocketfinancer.inference.SlmExtractionRequest
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.toModelSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class SyncSmsItem(
    val id: String,
    val sender: String,
    val body: String,
    val date: Long,
    var status: String,
    var parsedAmount: Double? = null,
    var parsedMerchant: String? = null
)

data class HomeSyncState(
    val status: Status = Status.IDLE,
    val queue: List<SyncSmsItem> = emptyList(),
    val currentIndex: Int? = null,
    val currentStageIndex: Int? = null,
    val thinkingOutput: String = "",
    val jsonOutput: String = "",
    val activeSmsPerformance: String? = null,
    val hasThinkingMode: Boolean = false,
    val activeModelName: String? = null
) {
    enum class Status {
        IDLE, SYNCING, DONE
    }
}

@Singleton
class HomeSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsRepository: SmsRepository,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val slmRuntime: SlmRuntime,
    private val appFlowCoordinator: SlmAppFlowCoordinator,
    private val modelStorage: SlmModelStorage,
    private val deviceCapabilities: DeviceCapabilities,
    private val promptBuilder: PromptBuilder,
    private val extractionParser: ExtractionParser,
    private val slmProcessingPreferences: SlmProcessingPreferences
) {
    private val executionMutex = Mutex()
    private val _syncState = MutableStateFlow(HomeSyncState())
    val syncState: StateFlow<HomeSyncState> = _syncState.asStateFlow()

    suspend fun checkForUnsyncedSms() = withContext(Dispatchers.IO) {
        if (_syncState.value.status == HomeSyncState.Status.SYNCING) return@withContext
        val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC)
            ?: return@withContext

        try {
            val onboardingComplete = context
                .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(ONBOARDING_COMPLETED, false)
            if (!onboardingComplete) return@withContext
            val rawMessages = smsRepository.fetchHistory(daysBack = 7, limit = 250)
            val transactional = rawMessages.filter { message ->
                smsFilterPipeline.isTransactional(message.address, message.body)
            }
            val currentQueue = _syncState.value.queue
            val unsynced = transactional
                .filter { message ->
                    !transactionRepository.exists(message.address, message.date)
                }
                .map { message ->
                    currentQueue.find {
                        it.sender == message.address && it.date == message.date
                    } ?: SyncSmsItem(
                        id = UUID.randomUUID().toString(),
                        sender = message.address,
                        body = message.body,
                        date = message.date,
                        status = "pending"
                    )
                }
            val loadedModel = slmRuntime.state.value.loadedModel
            _syncState.value = HomeSyncState(
                status = HomeSyncState.Status.IDLE,
                queue = (currentQueue + unsynced).distinctBy { it.sender to it.date },
                hasThinkingMode = loadedModel?.hasThinkingMode ?: false,
                activeModelName = loadedModel?.modelPath?.let { File(it).name }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed checking for unsynced SMS", e)
        } finally {
            withContext(NonCancellable) {
                flowLease.release()
            }
        }
    }

    /**
     * Holds a residency lease for the batch, while each SMS is submitted as an
     * independent FIFO native request so other runtime callers can interleave.
     */
    @Suppress("UNUSED_PARAMETER")
    suspend fun executeSync(serviceContext: Context) = withContext(Dispatchers.IO) {
        executionMutex.withLock {
            val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC)
                ?: return@withLock
            try {
                val onboardingComplete = context
                    .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                    .getBoolean(ONBOARDING_COMPLETED, false)
                if (!onboardingComplete) return@withLock
                executeSyncLocked()
            } finally {
                withContext(NonCancellable) {
                    flowLease.release()
                }
            }
        }
    }

    private suspend fun executeSyncLocked() {
        if (_syncState.value.queue.isEmpty()) {
            _syncState.value = _syncState.value.copy(status = HomeSyncState.Status.DONE)
            return
        }

        _syncState.value = _syncState.value.copy(
            status = HomeSyncState.Status.SYNCING,
            currentIndex = 0,
            currentStageIndex = 0
        )
        var lease: SlmLease? = null
        try {
            val device = deviceCapabilities.assessDevice()
            val tier = resolveActiveSlmTier(context, modelStorage.modelDirectory, device)
                ?: error("No viable SLM for this device (RAM below minimum).")
            val modelFile = modelStorage.modelFile(tier.modelFile)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                error("Model file ${tier.modelFile} is not downloaded yet.")
            }
            val spec = tier.toModelSpec(modelStorage, device)

            _syncState.value = _syncState.value.copy(
                status = HomeSyncState.Status.SYNCING,
                currentIndex = 0,
                currentStageIndex = 0,
                thinkingOutput = "",
                jsonOutput = "",
                activeSmsPerformance = null,
                hasThinkingMode = spec.hasThinkingMode,
                activeModelName = modelFile.name
            )

            val batchLease = slmRuntime.acquire(SlmRuntimeOwner.HOME_SYNC, spec)
            lease = batchLease
            val grammar: String by lazy {
                modelStorage.readTextAsset("sms_extraction.gbnf")
            }

            var index = 0
            while (index < _syncState.value.queue.size) {
                val queue = _syncState.value.queue.toMutableList()
                queue[index].status = "syncing"
                _syncState.value = _syncState.value.copy(
                    queue = queue,
                    currentIndex = index,
                    currentStageIndex = 0,
                    thinkingOutput = "",
                    jsonOutput = "",
                    activeSmsPerformance = null
                )

                val item = _syncState.value.queue[index]

                // Immutable per-SMS preference snapshot. A toggle made while
                // this request is queued/running applies to the next item.
                val useGrammar = slmProcessingPreferences.gbnfGrammarEnabled.value

                if (transactionRepository.exists(item.sender, item.date)) {
                    updateItemStatus(index, "synced")
                    index++
                    continue
                }
                if (!smsFilterPipeline.isTransactional(item.sender, item.body)) {
                    updateItemStatus(index, "filtered_out")
                    index++
                    continue
                }

                try {
                    val hasThinking = batchLease.model.hasThinkingMode
                    _syncState.value = _syncState.value.copy(
                        currentStageIndex = if (hasThinking) 1 else 2
                    )
                    val rawPrompt = promptBuilder.buildExtractionPrompt(item.sender, item.body)
                    val fallbackPrompt =
                        promptBuilder.buildChatPrompt(rawPrompt, enableThinking = hasThinking)

                    val result = batchLease.extract(
                        SlmExtractionRequest(
                            messages = listOf(
                                SlmChatMessage(
                                    role = "system",
                                    content = "You are a helpful financial SMS extraction assistant."
                                ),
                                SlmChatMessage(role = "user", content = rawPrompt)
                            ),
                            fallbackPrompt = fallbackPrompt,
                            staticPrefix = promptBuilder.getStaticPrefix(),
                            grammar = if (useGrammar) grammar else null,
                            thinkingTokens = 1024,
                            answerTokens = 256,
                            thinkingCallback = { token ->
                                _syncState.value = _syncState.value.copy(
                                    thinkingOutput = _syncState.value.thinkingOutput + token
                                )
                            },
                            jsonCallback = { token ->
                                _syncState.value = _syncState.value.copy(
                                    currentStageIndex = 2,
                                    jsonOutput = _syncState.value.jsonOutput + token
                                )
                            }
                        )
                    )

                    when (result) {
                        is SlmExtractionResult.Success ->
                            persistSuccessfulExtraction(index, result)
                        is SlmExtractionResult.Null ->
                            updateItemStatus(index, "filtered_out")
                        is SlmExtractionResult.Error -> {
                            Log.e(TAG, "SLM extraction failed: ${result.message}")
                            updateItemStatus(index, "error")
                        }
                        is SlmExtractionResult.Stopped ->
                            updateItemStatus(index, "error")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed syncing SMS: ${item.body}", e)
                    updateItemStatus(index, "error")
                }
                index++
            }

            _syncState.value = _syncState.value.copy(
                status = HomeSyncState.Status.DONE,
                currentIndex = null,
                currentStageIndex = null
            )
        } catch (e: CancellationException) {
            val queue = _syncState.value.queue.toMutableList()
            _syncState.value.currentIndex?.let { index ->
                if (index in queue.indices && queue[index].status == "syncing") {
                    queue[index].status = "pending"
                }
            }
            _syncState.value = _syncState.value.copy(
                status = HomeSyncState.Status.IDLE,
                queue = queue,
                currentIndex = null,
                currentStageIndex = null
            )
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in manual sync process", e)
            _syncState.value = _syncState.value.copy(
                status = HomeSyncState.Status.DONE,
                currentIndex = null,
                currentStageIndex = null
            )
        } finally {
            withContext(NonCancellable) {
                lease?.release()
            }
        }
    }

    private fun updateItemStatus(index: Int, status: String) {
        val queue = _syncState.value.queue.toMutableList()
        queue[index].status = status
        _syncState.value = _syncState.value.copy(queue = queue)
    }

    private suspend fun persistSuccessfulExtraction(
        index: Int,
        result: SlmExtractionResult.Success
    ) {
        // Parsing and database work happen after the native request completes.
        _syncState.value = _syncState.value.copy(currentStageIndex = 3)
        val parsed = extractionParser.parse(result.json)
        val queue = _syncState.value.queue.toMutableList()
        val item = queue[index]

        if (parsed == null) {
            item.status = "filtered_out"
        } else {
            val bank = inferBankFromSender(item.sender)
            val merchant = parsed.counterparty
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?: if (bank != "Unknown Account") "Transaction ($bank)" else "Unknown Merchant"
            val account = parsed.account?.let {
                accountRepository.getOrCreate(it, bank, "auto-extracted")
            } ?: accountRepository.ensureDefault()

            transactionRepository.insert(
                TransactionRepository.NewTransaction(
                    amount = parsed.amount,
                    merchant = merchant,
                    date = item.date,
                    type = parsed.type,
                    accountId = account.id,
                    rawMessage = item.body,
                    sender = item.sender,
                    slmPromptEvalMs = result.perf?.tPromptEvalMs,
                    slmEvalMs = result.perf?.tEvalMs,
                    slmNumTokens = result.perf?.nTokens,
                    slmModelName = File(result.model.modelPath).name
                )
            )
            item.status = "synced"
            item.parsedAmount = parsed.amount
            item.parsedMerchant = merchant
        }

        val perf = result.perf?.let {
            "${"%.1f".format(it.tokensPerSecond)} tok/s • ${it.tEvalMs}ms"
        } ?: "Done"
        _syncState.value = _syncState.value.copy(
            queue = queue,
            activeSmsPerformance = perf
        )
    }

    suspend fun queueIncomingSms(
        address: String,
        body: String,
        date: Long
    ): Boolean = withContext(Dispatchers.IO) {
        val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC)
            ?: return@withContext false
        try {
            val onboardingComplete = context
                .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(ONBOARDING_COMPLETED, false)
            if (!onboardingComplete) return@withContext false
            val isTransaction = smsFilterPipeline.isTransactional(address, body)
            if (transactionRepository.exists(address, date)) {
                Log.i(
                    TAG,
                    "Transaction for incoming SMS from $address at $date already exists. Skipping."
                )
                return@withContext false
            }

            val queue = _syncState.value.queue.toMutableList()
            if (queue.any { it.sender == address && it.date == date }) {
                Log.i(TAG, "Incoming SMS from $address at $date already queued. Skipping.")
                return@withContext false
            }
            queue += SyncSmsItem(
                id = UUID.randomUUID().toString(),
                sender = address,
                body = body,
                date = date,
                status = if (isTransaction) "pending" else "filtered_out"
            )
            _syncState.value = _syncState.value.copy(queue = queue)
            isTransaction
        } finally {
            withContext(NonCancellable) {
                flowLease.release()
            }
        }
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

    fun resetState() {
        _syncState.value = HomeSyncState()
    }

    private companion object {
        const val TAG = "HomeSyncManager"
        const val APP_SETTINGS = ".app_settings"
        const val ONBOARDING_COMPLETED = "onboarding_completed"
    }
}
