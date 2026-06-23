package com.pocketfinancer.ui.home

import android.content.Context
import android.util.Log
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.selectSlmForDevice
import com.pocketfinancer.inference.LlamaEngine
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.sms.SmsRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class SyncSmsItem(
    val id: String,
    val sender: String,
    val body: String,
    val date: Long,
    var status: String, // "pending" | "syncing" | "synced" | "filtered_out" | "error"
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
    val hasThinkingMode: Boolean = false
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
    private val llamaEngine: LlamaEngine,
    private val deviceCapabilities: DeviceCapabilities,
    private val promptBuilder: PromptBuilder,
    private val extractionParser: ExtractionParser
) {
    private val TAG = "HomeSyncManager"

    private val _syncState = MutableStateFlow(HomeSyncState())
    val syncState: StateFlow<HomeSyncState> = _syncState.asStateFlow()

    /**
     * Scans the SMS history for the last 7 days and populates the list of unsynced transactional messages.
     */
    suspend fun checkForUnsyncedSms() = withContext(Dispatchers.IO) {
        if (_syncState.value.status == HomeSyncState.Status.SYNCING) return@withContext

        try {
            val rawMessages = smsRepository.fetchHistory(daysBack = 7, limit = 250)
            val transactional = rawMessages.filter { msg ->
                smsFilterPipeline.isTransactional(msg.address, msg.body)
            }

            val currentQueue = _syncState.value.queue
            val unsynced = transactional.filter { msg ->
                !transactionRepository.exists(msg.address, msg.date)
            }.map { msg ->
                val existing = currentQueue.find { it.sender == msg.address && it.date == msg.date }
                existing ?: SyncSmsItem(
                    id = UUID.randomUUID().toString(),
                    sender = msg.address,
                    body = msg.body,
                    date = msg.date,
                    status = "pending"
                )
            }

            val mergedQueue = (currentQueue + unsynced).distinctBy { it.sender to it.date }

            _syncState.value = HomeSyncState(
                status = HomeSyncState.Status.IDLE,
                queue = mergedQueue,
                currentIndex = null,
                currentStageIndex = null,
                hasThinkingMode = llamaEngine.hasThinkingMode
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed checking for unsynced SMS", e)
        }
    }

    /**
     * Executes the sync sequentially. This should be run on a background thread
     * (e.g. within a Foreground Service).
     */
    suspend fun executeSync(serviceContext: Context) = withContext(Dispatchers.IO) {
        if (_syncState.value.status == HomeSyncState.Status.SYNCING) return@withContext

        val items = _syncState.value.queue.toMutableList()
        if (items.isEmpty()) {
            _syncState.value = _syncState.value.copy(status = HomeSyncState.Status.DONE)
            return@withContext
        }

        _syncState.value = _syncState.value.copy(
            status = HomeSyncState.Status.SYNCING,
            currentIndex = 0,
            currentStageIndex = 0,
            thinkingOutput = "",
            jsonOutput = "",
            activeSmsPerformance = null,
            hasThinkingMode = llamaEngine.hasThinkingMode
        )

        var loadedModelHere = false
        try {
            // 1. Ensure Model is Loaded
            if (!llamaEngine.isModelLoaded()) {
                val device = deviceCapabilities.assessDevice()
                val slm = selectSlmForDevice(device)
                if (slm == null) {
                    throw Exception("No viable SLM for this device (RAM below minimum).")
                }

                val modelFile = File(llamaEngine.getModelStorageDir(), slm.modelFile)
                if (!modelFile.exists() || modelFile.length() == 0L) {
                    throw Exception("Model file ${slm.modelFile} is not downloaded yet.")
                }

                val hasFp16 = device.cpu?.hasFp16 ?: false
                val loadResult = llamaEngine.loadModel(
                    path = modelFile.absolutePath,
                    contextSize = 3072,
                    gpuLayers = 0,
                    numThreads = 0,
                    hasFp16 = hasFp16,
                    hasThinkingMode = slm.hasThinkingMode
                )
                if (loadResult.isFailure) {
                    throw Exception("Failed to load model: ${loadResult.exceptionOrNull()?.message}")
                }
                loadedModelHere = true
                _syncState.value = _syncState.value.copy(
                    hasThinkingMode = llamaEngine.hasThinkingMode
                )
            }

            val grammar = llamaEngine.readAsset("sms_extraction.gbnf")

            // 2. Loop and Sync
            var index = 0
            while (true) {
                val currentQueue = _syncState.value.queue
                if (index >= currentQueue.size) break

                val loopQueue = _syncState.value.queue.toMutableList()
                loopQueue[index].status = "syncing"
                _syncState.value = _syncState.value.copy(
                    queue = loopQueue,
                    currentIndex = index,
                    currentStageIndex = 0, // Pre-filter Check
                    thinkingOutput = "",
                    jsonOutput = "",
                    activeSmsPerformance = null
                )

                val activeItem = _syncState.value.queue[index]

                // Double check if transaction already exists in DB to prevent duplicates
                if (transactionRepository.exists(activeItem.sender, activeItem.date)) {
                    val updatedQueue = _syncState.value.queue.toMutableList()
                    updatedQueue[index].status = "synced"
                    _syncState.value = _syncState.value.copy(queue = updatedQueue)
                    index++
                    continue
                }

                // Double check transactional criteria
                if (!smsFilterPipeline.isTransactional(activeItem.sender, activeItem.body)) {
                    val updatedQueue = _syncState.value.queue.toMutableList()
                    updatedQueue[index].status = "filtered_out"
                    _syncState.value = _syncState.value.copy(queue = updatedQueue)
                    index++
                    continue
                }

                try {
                    // Update stage:
                    // If model has thinking mode: update to stage 1 (Thinking Pass)
                    // If model has NO thinking mode: update to stage 2 (Grammar Constraint)
                    val hasThinking = _syncState.value.hasThinkingMode
                    _syncState.value = _syncState.value.copy(
                        currentStageIndex = if (hasThinking) 1 else 2
                    )

                    val rawPrompt = promptBuilder.buildExtractionPrompt(activeItem.sender, activeItem.body)
                    
                    // Render template
                    val messages = listOf(
                        LlamaEngine.ChatMessage("system", "You are a helpful financial SMS extraction assistant."),
                        LlamaEngine.ChatMessage("user", rawPrompt)
                    )
                    val chatPrompt = llamaEngine.applyChatTemplate(messages, addAssistantPrefix = true)
                        ?: promptBuilder.buildChatPrompt(rawPrompt, enableThinking = llamaEngine.hasThinkingMode)

                    val staticPrefix = promptBuilder.getStaticPrefix()

                    // Run dynamic extraction with real-time logging
                    val result = llamaEngine.inferForExtraction(
                        prompt = chatPrompt,
                        grammar = grammar,
                        staticPrefix = staticPrefix,
                        thinkingTokens = 1024,
                        answerTokens = 256,
                        thinkingCallback = { token ->
                            _syncState.value = _syncState.value.copy(
                                thinkingOutput = _syncState.value.thinkingOutput + token
                            )
                        },
                        jsonCallback = { token ->
                            // Update stage to Phase 2: Grammar Constraint on first json token
                            if (_syncState.value.currentStageIndex != 2) {
                                _syncState.value = _syncState.value.copy(currentStageIndex = 2)
                            }
                            _syncState.value = _syncState.value.copy(
                                jsonOutput = _syncState.value.jsonOutput + token
                            )
                        }
                    )

                    when (result) {
                        is LlamaEngine.InferenceResult.Success -> {
                            // Update stage to DB Persistence
                            _syncState.value = _syncState.value.copy(currentStageIndex = 3)

                            val parsed = extractionParser.parse(result.json)
                            val updatedQueue = _syncState.value.queue.toMutableList()
                            val processedItem = updatedQueue[index]

                            if (parsed != null) {
                                val inferredBank = inferBankFromSender(processedItem.sender)
                                val merchantName = parsed.counterparty?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                                    ?: if (inferredBank != "Unknown Bank") "Transaction ($inferredBank)" else "Unknown Merchant"

                                val parsedAcc = parsed.account
                                val account = if (parsedAcc != null) {
                                    accountRepository.getOrCreate(parsedAcc, inferredBank, "auto-extracted")
                                } else {
                                    accountRepository.ensureDefault()
                                }

                                transactionRepository.insert(
                                    TransactionRepository.NewTransaction(
                                        amount = parsed.amount,
                                        merchant = merchantName,
                                        date = processedItem.date,
                                        type = parsed.type,
                                        accountId = account.id,
                                        rawMessage = processedItem.body,
                                        sender = processedItem.sender
                                    )
                                )

                                processedItem.status = "synced"
                                processedItem.parsedAmount = parsed.amount
                                processedItem.parsedMerchant = merchantName
                            } else {
                                processedItem.status = "filtered_out" // Skipped by non-null filter/post-processor
                            }

                            val perfData = result.perf?.let { p ->
                                "${"%.1f".format(p.tokensPerSecond)} tok/s • ${p.tEvalMs}ms"
                            } ?: "Done"

                            _syncState.value = _syncState.value.copy(
                                queue = updatedQueue,
                                activeSmsPerformance = perfData
                            )
                        }
                        is LlamaEngine.InferenceResult.Null -> {
                            val updatedQueue = _syncState.value.queue.toMutableList()
                            updatedQueue[index].status = "filtered_out"
                            _syncState.value = _syncState.value.copy(queue = updatedQueue)
                        }
                        else -> {
                            val updatedQueue = _syncState.value.queue.toMutableList()
                            updatedQueue[index].status = "error"
                            _syncState.value = _syncState.value.copy(queue = updatedQueue)
                        }
                    }

                } catch (e: Exception) {
                    Log.e(TAG, "Failed syncing SMS: ${activeItem.body}", e)
                    val updatedQueue = _syncState.value.queue.toMutableList()
                    updatedQueue[index].status = "error"
                    _syncState.value = _syncState.value.copy(queue = updatedQueue)
                }

                index++
            }

            _syncState.value = _syncState.value.copy(
                status = HomeSyncState.Status.DONE,
                currentIndex = null,
                currentStageIndex = null
            )

        } catch (e: Exception) {
            Log.e(TAG, "Error in manual sync process", e)
            _syncState.value = _syncState.value.copy(status = HomeSyncState.Status.DONE)
        } finally {
            if (loadedModelHere) {
                llamaEngine.unloadModel()
            }
        }
    }

    /**
     * Appends an incoming SMS to the queue if it's transactional and not already present.
     */
    suspend fun queueIncomingSms(address: String, body: String, date: Long): Boolean = withContext(Dispatchers.IO) {
        val isTxn = smsFilterPipeline.isTransactional(address, body)

        if (transactionRepository.exists(address, date)) {
            Log.i(TAG, "Transaction for incoming SMS from $address at $date already exists in DB. Skipping.")
            return@withContext false
        }

        val currentQueue = _syncState.value.queue.toMutableList()
        if (currentQueue.any { it.sender == address && it.date == date }) {
            Log.i(TAG, "Incoming SMS from $address at $date already in sync queue. Skipping.")
            return@withContext false
        }

        val newItem = SyncSmsItem(
            id = UUID.randomUUID().toString(),
            sender = address,
            body = body,
            date = date,
            status = if (isTxn) "pending" else "filtered_out"
        )
        currentQueue.add(newItem)
        _syncState.value = _syncState.value.copy(queue = currentQueue)
        return@withContext isTxn
    }

    private fun inferBankFromSender(sender: String): String {
        val upper = sender.uppercase()
        return when {
            upper.contains("HDFC") -> "HDFC Bank"
            upper.contains("AXIS") -> "Axis Bank"
            upper.contains("ICICI") -> "ICICI Bank"
            upper.contains("SBI") -> "State Bank of India"
            upper.contains("KOTAK") -> "Kotak Bank"
            else -> "Unknown Bank"
        }
    }

    fun resetState() {
        _syncState.value = HomeSyncState()
    }
}
