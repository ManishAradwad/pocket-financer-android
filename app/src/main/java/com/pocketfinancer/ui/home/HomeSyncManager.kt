package com.pocketfinancer.ui.home

import android.content.Context
import android.util.Log
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.model.SmsSourceIdentity
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
import com.pocketfinancer.pipeline.IncomingSmsQueueResult
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.setup.AdaptiveHistoryScanPolicy
import com.pocketfinancer.setup.SetupActionableError
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.toModelSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.TimeUnit
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
    val messageType: Int = 1,
    val sourceIdentity: SmsSourceIdentity = SmsSourceIdentity.androidSms(
        providerMessageId = null,
        sender = sender,
        body = body,
        sourceTimestamp = date,
        messageType = messageType
    ),
    var status: String,
    var parsedAmount: Double? = null,
    var parsedMerchant: String? = null
)

internal val SOURCE_EVIDENCE_DISCARDED_STATUSES = setOf(
    "synced",
    "already_saved",
    "filtered_out"
)

/**
 * Applies a queue-state transition without leaving source evidence in the
 * process-wide Home singleton after that evidence has a durable owner (or has
 * been rejected). Retryable states deliberately keep the source so the same
 * candidate can be attempted again.
 */
internal fun SyncSmsItem.withPrivacySafeStatus(status: String): SyncSmsItem =
    when (status) {
        "synced" -> copy(
            sender = "Saved transaction",
            body = "",
            status = status
        )
        "already_saved" -> copy(
            sender = "Already in ledger",
            body = "",
            status = status,
            parsedAmount = null,
            parsedMerchant = null
        )
        "filtered_out" -> copy(
            sender = "Rejected alert",
            body = "",
            status = status,
            parsedAmount = null,
            parsedMerchant = null
        )
        else -> copy(status = status)
    }

internal fun SyncSmsItem.hasDiagnosticSourceEvidence(): Boolean =
    status !in SOURCE_EVIDENCE_DISCARDED_STATUSES &&
        sender.isNotBlank() &&
        body.isNotBlank()

data class HomeSyncState(
    val status: Status = Status.IDLE,
    val queue: List<SyncSmsItem> = emptyList(),
    val currentIndex: Int? = null,
    val currentStageIndex: Int? = null,
    val thinkingOutput: String = "",
    val jsonOutput: String = "",
    val activeSmsPerformance: String? = null,
    val hasThinkingMode: Boolean = false,
    val activeModelName: String? = null,
    val recentScanOutcome: RecentScanOutcome = RecentScanOutcome.NOT_RUN,
    val recentScanWindowDays: Int? = null,
    val lastSuccessfulScanMillis: Long? = null,
    val scanError: String? = null,
    val syncError: String? = null
) {
    enum class Status {
        IDLE, SYNCING, DONE
    }

    enum class RecentScanOutcome {
        NOT_RUN, SUCCESS, PERMISSION_NEEDED, FAILED
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
    private val slmProcessingPreferences: SlmProcessingPreferences,
    private val setupImportStore: SetupImportStore
) {
    private val historyScanPolicy = AdaptiveHistoryScanPolicy()
    private val executionMutex = Mutex()
    private val _syncState = MutableStateFlow(HomeSyncState())
    val syncState: StateFlow<HomeSyncState> = _syncState.asStateFlow()

    suspend fun checkForUnsyncedSms() = withContext(Dispatchers.IO) {
        if (_syncState.value.status == HomeSyncState.Status.SYNCING) return@withContext
        val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC)
            ?: run {
                recordManualOperationError(
                    code = "RECENT_SCAN_NOT_STARTED",
                    message =
                        "The recent scan is waiting for another local setup or maintenance operation.",
                    actionLabel = "Try recent scan again"
                )
                _syncState.value = _syncState.value.copy(
                    recentScanOutcome =
                        HomeSyncState.RecentScanOutcome.NOT_RUN,
                    scanError =
                        "The recent scan is waiting for another local setup or maintenance operation."
                )
                return@withContext
            }

        try {
            val onboardingComplete = context
                .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(ONBOARDING_COMPLETED, false)
            if (!onboardingComplete) return@withContext
            if (
                !manualRecentSyncAvailable(
                    setupImportStore.state.value.status
                )
            ) {
                _syncState.value = _syncState.value.copy(
                    recentScanOutcome =
                        HomeSyncState.RecentScanOutcome.FAILED,
                    scanError =
                        "Resume the first-run import before scanning recent alerts."
                )
                return@withContext
            }
            if (!smsRepository.hasPermissions()) {
                setupImportStore.reconcilePermission(granted = false)
                _syncState.value = _syncState.value.copy(
                    recentScanOutcome =
                        HomeSyncState.RecentScanOutcome.PERMISSION_NEEDED,
                    scanError = "SMS access is required to scan recent alerts."
                )
                return@withContext
            }
            val scanWindowDays = historyScanPolicy.firstWindowDays
            val rawMessages = smsRepository.fetchHistory(
                daysBack = scanWindowDays,
                // Persisted coverage must describe a complete provider read,
                // not the first page of a potentially larger inbox window.
                limit = Int.MAX_VALUE
            )
            val transactional = rawMessages.filter { message ->
                smsFilterPipeline.isTransactional(message.address, message.body)
            }
            val currentQueue = _syncState.value.queue.filter {
                it.status == "pending" || it.status == "syncing"
            }
            val unsynced = transactional
                .filter { message ->
                    !transactionRepository.exists(message.sourceIdentity)
                }
                .map { message ->
                    currentQueue.find {
                        it.sourceIdentity.connector ==
                            message.sourceIdentity.connector &&
                            (
                                it.sourceIdentity.messageId ==
                                    message.sourceIdentity.messageId ||
                                    it.sourceIdentity.fallbackFingerprint ==
                                        message.sourceIdentity.fallbackFingerprint
                            )
                    } ?: SyncSmsItem(
                        id = message.sourceIdentity.opaqueCandidateKey,
                        sender = message.address,
                        body = message.body,
                        date = message.date,
                        messageType = message.type,
                        sourceIdentity = message.sourceIdentity,
                        status = "pending"
                    )
                }
            val loadedModel = slmRuntime.state.value.loadedModel
            val completedAt = System.currentTimeMillis()
            _syncState.value = HomeSyncState(
                status = HomeSyncState.Status.IDLE,
                queue = (currentQueue + unsynced).distinctBy {
                    it.sourceIdentity.connector to
                        it.sourceIdentity.fallbackFingerprint
                },
                hasThinkingMode = loadedModel?.hasThinkingMode ?: false,
                activeModelName = loadedModel?.modelPath?.let { File(it).name },
                recentScanOutcome = HomeSyncState.RecentScanOutcome.SUCCESS,
                recentScanWindowDays = scanWindowDays,
                lastSuccessfulScanMillis = completedAt
            )
            recordManualScanSuccess(
                scanWindowDays = scanWindowDays,
                completedAt = completedAt,
                providerMessageCount = rawMessages.size,
                eligibleCandidateCount = transactional.size
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed checking for unsynced SMS", e)
            recordManualOperationError(
                code = "RECENT_SMS_SCAN_FAILED",
                message = e.message ?: "The recent SMS scan failed.",
                actionLabel = "Try recent scan again"
            )
            _syncState.value = _syncState.value.copy(
                status = HomeSyncState.Status.IDLE,
                recentScanOutcome = HomeSyncState.RecentScanOutcome.FAILED,
                scanError = e.message ?: "The recent SMS scan failed."
            )
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
            // SyncService is already a foreground service. Wait through the
            // short selected-model handoff instead of returning and posting a
            // false completion while queued incoming SMS remain unprocessed.
            val flowLease =
                appFlowCoordinator.enterWhenAvailable(SlmRuntimeOwner.HOME_SYNC)
            try {
                val onboardingComplete = context
                    .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                    .getBoolean(ONBOARDING_COMPLETED, false)
                if (!onboardingComplete) return@withLock
                if (
                    !manualRecentSyncAvailable(
                        setupImportStore.state.value.status
                    )
                ) {
                    _syncState.value = _syncState.value.copy(
                        status = HomeSyncState.Status.IDLE,
                        syncError =
                            "Resume the first-run import before processing recent alerts."
                    )
                    return@withLock
                }
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
            _syncState.value = _syncState.value.copy(
                status = HomeSyncState.Status.IDLE
            )
            return
        }

        _syncState.value = _syncState.value.copy(
            status = HomeSyncState.Status.SYNCING,
            currentIndex = 0,
            currentStageIndex = 0,
            syncError = null
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

                if (transactionRepository.exists(item.sourceIdentity)) {
                    updateItemStatus(index, "already_saved")
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
                    Log.e(TAG, "Failed syncing an SMS candidate", e)
                    updateItemStatus(index, "error")
                }
                index++
            }

            _syncState.value = _syncState.value.copy(
                status = HomeSyncState.Status.DONE,
                currentIndex = null,
                currentStageIndex = null,
                syncError = null
            )
            val failedCount = _syncState.value.queue.count {
                it.status == "error"
            }
            if (failedCount > 0) {
                manualProcessingFailureError(failedCount)?.let { error ->
                    recordManualOperationError(
                        code = error.code,
                        message = error.message,
                        actionLabel = error.actionLabel
                    )
                }
            } else {
                clearManualOperationError()
            }
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
            recordManualOperationError(
                code = "MANUAL_PROCESSING_INTERRUPTED",
                message =
                    "Manual processing stopped. Completed saves remain available.",
                actionLabel = "Scan recent messages"
            )
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Error in manual sync process", e)
            recordManualOperationError(
                code = "MANUAL_SMS_PROCESSING_FAILED",
                message =
                    e.message ?: "Manual SMS processing could not finish.",
                actionLabel = "Scan recent messages"
            )
            _syncState.value = _syncState.value.copy(
                status = HomeSyncState.Status.DONE,
                currentIndex = null,
                currentStageIndex = null,
                syncError =
                    e.message ?: "Manual SMS processing could not finish."
            )
        } finally {
            withContext(NonCancellable) {
                lease?.release()
            }
        }
    }

    private fun updateItemStatus(index: Int, status: String) {
        val queue = _syncState.value.queue.toMutableList()
        queue[index] = queue[index].withPrivacySafeStatus(status)
        val shouldDiscardTransientOutput =
            status in SOURCE_EVIDENCE_DISCARDED_STATUSES
        _syncState.value = _syncState.value.copy(
            queue = queue,
            thinkingOutput = if (shouldDiscardTransientOutput) {
                ""
            } else {
                _syncState.value.thinkingOutput
            },
            jsonOutput = if (shouldDiscardTransientOutput) {
                ""
            } else {
                _syncState.value.jsonOutput
            }
        )
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
            queue[index] = item.withPrivacySafeStatus("filtered_out")
        } else {
            val bank = inferBankFromSender(item.sender)
            val merchant = parsed.counterparty
                ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                ?: if (bank != "Unknown Account") "Transaction ($bank)" else "Unknown Merchant"
            val account = parsed.account?.let {
                accountRepository.getOrCreate(it, bank, "auto-extracted")
            } ?: accountRepository.ensureDefault()

            val insertion = transactionRepository.insertIfAbsent(
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
                    slmModelName = File(result.model.modelPath).name,
                    sourceIdentity = item.sourceIdentity
                )
            )
            val terminalStatus = if (insertion.inserted) {
                "synced"
            } else {
                "already_saved"
            }
            queue[index] = item.copy(
                parsedAmount = parsed.amount.takeIf { insertion.inserted },
                parsedMerchant = merchant.takeIf { insertion.inserted }
            ).withPrivacySafeStatus(terminalStatus)
        }

        val perf = result.perf?.let {
            "${"%.1f".format(it.tokensPerSecond)} tok/s • ${it.tEvalMs}ms"
        } ?: "Done"
        _syncState.value = _syncState.value.copy(
            queue = queue,
            activeSmsPerformance = perf,
            // Model output can echo source evidence. The parsed display
            // summary above is all Home needs once processing is terminal.
            thinkingOutput = "",
            jsonOutput = ""
        )
    }

    suspend fun queueIncomingSms(
        address: String,
        body: String,
        date: Long
    ): IncomingSmsQueueResult = withContext(Dispatchers.IO) {
        val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC)
            ?: return@withContext IncomingSmsQueueResult.ADMISSION_PAUSED
        try {
            val onboardingComplete = context
                .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(ONBOARDING_COMPLETED, false)
            if (!onboardingComplete) {
                return@withContext IncomingSmsQueueResult.IGNORED
            }
            val isTransaction = smsFilterPipeline.isTransactional(address, body)
            if (!isTransaction) {
                return@withContext IncomingSmsQueueResult.IGNORED
            }
            val sourceIdentity = SmsSourceIdentity.androidSms(
                providerMessageId = null,
                sender = address,
                body = body,
                sourceTimestamp = date,
                messageType = 1
            )
            if (transactionRepository.exists(sourceIdentity)) {
                Log.i(
                    TAG,
                    "Transaction for incoming SMS source already exists. Skipping."
                )
                return@withContext IncomingSmsQueueResult.IGNORED
            }

            val queue = _syncState.value.queue.toMutableList()
            if (queue.any {
                    it.sourceIdentity.connector == sourceIdentity.connector &&
                        (
                            it.sourceIdentity.messageId ==
                                sourceIdentity.messageId ||
                                it.sourceIdentity.fallbackFingerprint ==
                                    sourceIdentity.fallbackFingerprint
                            )
                }
            ) {
                Log.i(TAG, "Incoming SMS source is already queued. Skipping.")
                return@withContext IncomingSmsQueueResult.IGNORED
            }
            queue += SyncSmsItem(
                id = sourceIdentity.opaqueCandidateKey,
                sender = address,
                body = body,
                date = date,
                sourceIdentity = sourceIdentity,
                status = "pending"
            )
            val currentState = _syncState.value
            _syncState.value = if (currentState.status == HomeSyncState.Status.DONE) {
                currentState.copy(
                    status = HomeSyncState.Status.IDLE,
                    queue = queue,
                    currentIndex = null,
                    currentStageIndex = null,
                    thinkingOutput = "",
                    jsonOutput = "",
                    activeSmsPerformance = null
                )
            } else {
                currentState.copy(queue = queue)
            }
            IncomingSmsQueueResult.QUEUED_TRANSACTION
        } finally {
            withContext(NonCancellable) {
                flowLease.release()
            }
        }
    }

    private fun recordManualOperationError(
        code: String,
        message: String,
        actionLabel: String
    ) {
        if (
            !manualRecentSyncAvailable(
                setupImportStore.state.value.status
            )
        ) {
            return
        }
        setupImportStore.update {
            it.copy(
                actionableError = SetupActionableError(
                    code = code,
                    message = message,
                    actionLabel = actionLabel
                )
            )
        }
    }

    private fun recordManualScanSuccess(
        scanWindowDays: Int,
        completedAt: Long,
        providerMessageCount: Int,
        eligibleCandidateCount: Int
    ) {
        setupImportStore.update {
            if (!manualRecentSyncAvailable(it.status)) {
                it
            } else {
                it.withSuccessfulRecentScan(
                    scanWindowDays = scanWindowDays,
                    completedAt = completedAt,
                    providerMessageCount = providerMessageCount,
                    eligibleCandidateCount = eligibleCandidateCount
                )
            }
        }
    }

    private fun clearManualOperationError() {
        val code = setupImportStore.state.value.actionableError?.code
        if (
            code?.startsWith("RECENT_") != true &&
            code?.startsWith("MANUAL_") != true
        ) {
            return
        }
        setupImportStore.update {
            it.copy(actionableError = null)
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
        val TERMINAL_ITEM_STATUSES = setOf(
            "synced",
            "already_saved",
            "filtered_out",
            "error"
        )
    }
}

internal fun SetupImportState.withSuccessfulRecentScan(
    scanWindowDays: Int,
    completedAt: Long,
    providerMessageCount: Int,
    eligibleCandidateCount: Int
): SetupImportState {
    require(scanWindowDays > 0) {
        "Recent scan window must be positive"
    }
    return copy(
        recentCoverageStartMillis =
            completedAt - TimeUnit.DAYS.toMillis(scanWindowDays.toLong()),
        recentCoverageEndMillis = completedAt,
        recentScanWindowDays = scanWindowDays,
        recentProviderMessageCount = providerMessageCount.coerceAtLeast(0),
        recentEligibleCandidateCount = eligibleCandidateCount.coerceAtLeast(0),
        lastSuccessfulRecentScanMillis = completedAt,
        actionableError = null
    )
}

internal fun manualProcessingFailureError(
    failedCount: Int
): SetupActionableError? {
    if (failedCount <= 0) return null
    return SetupActionableError(
        code = "MANUAL_SMS_PROCESSING_FAILED",
        message =
            "$failedCount eligible alert" +
                if (failedCount == 1) {
                    " could not be processed."
                } else {
                    "s could not be processed."
                },
        actionLabel = "Scan recent messages"
    )
}
