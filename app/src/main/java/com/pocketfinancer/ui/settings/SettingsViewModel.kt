package com.pocketfinancer.ui.settings

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.explainTierSelection
import com.pocketfinancer.hardware.isPublishedModelArtifact
import com.pocketfinancer.hardware.resolveActiveSlmTier
import com.pocketfinancer.hardware.selectSlmForDevice
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmChatMessage
import com.pocketfinancer.inference.SlmExtractionRequest
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmPendingAction
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.inference.SlmRuntimePhase
import com.pocketfinancer.inference.SlmRuntimeState
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.SmsWorkAdmissionPause
import com.pocketfinancer.pipeline.SmsWorkController
import com.pocketfinancer.SelectedModelResidency
import com.pocketfinancer.SelectedModelMutationPause
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.SlmAppFlowPause
import com.pocketfinancer.toModelSpec
import com.pocketfinancer.ui.home.HomeSyncManager
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.SyncService
import com.pocketfinancer.ui.onboarding.OnboardingRunGenerationStore
import com.pocketfinancer.ui.onboarding.OnboardingService
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SettingsUiState(
    val deviceInfo: DeviceCapabilities.DeviceInfo? = null,
    val hardwareError: String? = null,
    val selectedSlm: SlmTier? = null,
    val recommendedSlm: SlmTier? = null,
    val allTiers: List<SlmTier> = SlmTier.ALL_TIERS,
    val tierExplanations: Map<String, String> = emptyMap(),
    val downloadState: ModelDownloader.DownloadState = ModelDownloader.DownloadState(),
    val modelLoaded: Boolean = false,
    val modelPath: String? = null,
    val modelPinnedByUser: Boolean = false,
    val loadingModel: Boolean = false,
    val modelLoadError: String? = null,
    val runtimeError: String? = null,
    val runtimePhase: SlmRuntimePhase = SlmRuntimePhase.UNLOADED,
    val runtimeBusy: Boolean = false,
    val runtimeQueueDepth: Int = 0,
    val runtimeLeaseCount: Int = 0,
    val runtimeActiveOwner: String? = null,
    val pendingRuntimeAction: String? = null,
    val flowBusy: Boolean = false,
    val resetRunning: Boolean = false,
    val testRunning: Boolean = false,
    val testProgress: String? = null,
    val thinkingOutput: String? = null,
    val testResult: String? = null,
    val testParsed: String? = null,
    val testError: String? = null,
    val filterLogs: List<String>? = null,
    val sessionCacheLogs: List<String>? = null,
    val slmPrompt: String? = null,
    val processIncomingSms: Boolean = true,
    val gbnfGrammarEnabled: Boolean = true
) {
    val canLoadModel: Boolean
        get() = selectedSlm != null &&
            downloadState.isComplete &&
            !loadingModel &&
            !testRunning &&
            !resetRunning &&
            !flowBusy &&
            runtimePhase != SlmRuntimePhase.MAINTENANCE

    val canUnloadModel: Boolean
        get() = modelPinnedByUser &&
            !loadingModel &&
            !testRunning &&
            !resetRunning &&
            !flowBusy

    val canRunTest: Boolean
        get() = modelLoaded &&
            !testRunning &&
            !loadingModel &&
            !resetRunning &&
            !flowBusy &&
            pendingRuntimeAction == null

    val canResetOnboarding: Boolean
        get() = !runtimeBusy &&
            !flowBusy &&
            !downloadState.isDownloading &&
            !loadingModel &&
            !testRunning &&
            !resetRunning
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val deviceCapabilities: DeviceCapabilities,
    private val slmRuntime: SlmRuntime,
    private val modelStorage: SlmModelStorage,
    private val modelDownloader: ModelDownloader,
    private val promptBuilder: PromptBuilder,
    private val extractionParser: ExtractionParser,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val transactionRepository: TransactionRepository,
    private val accountRepository: AccountRepository,
    private val slmProcessingPreferences: SlmProcessingPreferences,
    private val smsWorkController: SmsWorkController,
    private val selectedModelResidency: SelectedModelResidency,
    private val appFlowCoordinator: SlmAppFlowCoordinator,
    private val homeSyncManager: HomeSyncManager,
    private val onboardingSyncManager: OnboardingSyncManager,
    private val onboardingRunGenerationStore: OnboardingRunGenerationStore
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()
    private var testJob: Job? = null
    private var modelActionJob: Job? = null
    private var downloadJob: Job? = null
    private var resetJob: Job? = null

    init {
        assessDevice()
        val prefs = context.getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
        _state.value = _state.value.copy(
            processIncomingSms = prefs.getBoolean(PROCESS_INCOMING_SMS, true),
            gbnfGrammarEnabled = slmProcessingPreferences.gbnfGrammarEnabled.value
        )
        applyRuntimeState(slmRuntime.state.value)

        viewModelScope.launch {
            slmRuntime.state.collect { runtimeState ->
                applyRuntimeState(runtimeState)
            }
        }
        viewModelScope.launch {
            slmProcessingPreferences.gbnfGrammarEnabled.collect { enabled ->
                _state.value = _state.value.copy(gbnfGrammarEnabled = enabled)
            }
        }
        viewModelScope.launch {
            combine(
                homeSyncManager.syncState,
                onboardingSyncManager.syncState,
                appFlowCoordinator.state,
                modelDownloader.state
            ) { home, onboarding, appFlows, downloaderState ->
                val normalized = normalizedDownloadState(downloaderState)
                val busy = home.status == HomeSyncState.Status.SYNCING ||
                    onboarding.isRunning ||
                    appFlows.activeCount > 0 ||
                    appFlows.admissionPaused
                normalized to busy
            }.collect { (normalized, busy) ->
                _state.value = _state.value.copy(
                    downloadState = normalized,
                    flowBusy = busy
                )
            }
        }
    }

    private fun assessDevice() {
        try {
            val device = deviceCapabilities.assessDevice()
            val recommended = selectSlmForDevice(device)
            val active = resolveActiveSlmTier(context, modelStorage.modelDirectory, device)
            _state.value = _state.value.copy(
                deviceInfo = device,
                selectedSlm = active,
                recommendedSlm = recommended,
                tierExplanations = SlmTier.ALL_TIERS.associate { tier ->
                    tier.id to explainTierSelection(tier, device, tier == recommended)
                }
            )
        } catch (error: Exception) {
            _state.value = _state.value.copy(
                hardwareError = "Failed to read hardware: ${error.message}"
            )
        }
    }

    fun downloadSelectedModel() {
        val tier = _state.value.selectedSlm ?: return
        if (downloadJob?.isActive == true) return
        if (_state.value.runtimeBusy ||
            _state.value.modelLoaded ||
            _state.value.flowBusy ||
            _state.value.testRunning ||
            _state.value.resetRunning
        ) {
            _state.value = _state.value.copy(
                modelLoadError =
                    "Unload the resident model and wait for current model work before downloading a replacement."
            )
            return
        }
        val destination = modelStorage.modelFile(tier.modelFile)
        if (destination.exists()) {
            _state.value = _state.value.copy(
                modelLoadError =
                    "A model file already exists at ${destination.absolutePath}. " +
                        "Settings will not replace an existing model artifact; " +
                        "the file was left unchanged."
            )
            return
        }
        downloadJob = viewModelScope.launch {
            val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.SETTINGS_MANUAL)
            if (flowLease == null) {
                _state.value = _state.value.copy(
                    modelLoadError = "Downloads are paused while onboarding reset completes."
                )
                return@launch
            }
            _state.value = _state.value.copy(modelLoadError = null)
            var downloadedPath: String? = null
            try {
                val result = modelDownloader.download(tier.downloadUrl, destination)
                result.fold(
                    onSuccess = { downloadedPath = it },
                    onFailure = { error ->
                        _state.value = _state.value.copy(
                            modelLoadError = error.message ?: "Model download failed."
                        )
                    }
                )
            } finally {
                withContext(NonCancellable) {
                    flowLease.release()
                }
            }

            // Auto-load belongs only to this successful Settings action. A
            // passive downloader/app-flow StateFlow emission must never undo a
            // manual unload or repin a model after onboarding reset.
            val completedPath = downloadedPath ?: return@launch
            ensureActive()
            val onboardingCompleted = context
                .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                .getBoolean(ONBOARDING_COMPLETED, false)
            if (onboardingCompleted) {
                loadModelFromPath(completedPath)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.let(modelDownloader::cancel)
    }

    fun loadSelectedModel() {
        val tier = _state.value.selectedSlm ?: return
        val file = modelStorage.modelFile(tier.modelFile)
        if (!file.exists() || file.length() == 0L) {
            _state.value = _state.value.copy(
                modelLoadError = "Model not downloaded yet.\nTap \"DOWNLOAD MODEL\" first."
            )
            return
        }
        loadModelFromPath(file.absolutePath)
    }

    private fun loadModelFromPath(path: String) {
        if (modelActionJob?.isActive == true) return
        modelActionJob = viewModelScope.launch {
            val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.SETTINGS_MANUAL)
            if (flowLease == null) {
                _state.value = _state.value.copy(
                    loadingModel = false,
                    modelLoadError = "Model changes are paused while onboarding reset completes."
                )
                return@launch
            }
            var provisionalPin: com.pocketfinancer.ProvisionalSelectedModelPin? = null
            try {
                val tier = _state.value.selectedSlm ?: return@launch
                val file = File(path)
                val diagnostics = buildDiagnostics(file)
                if (!file.exists() || file.length() == 0L) {
                    _state.value = _state.value.copy(
                        loadingModel = false,
                        modelLoadError = "Model file missing or empty.\n\n$diagnostics"
                    )
                    return@launch
                }
                if (file.canonicalFile != modelStorage.modelFile(tier.modelFile).canonicalFile) {
                    _state.value = _state.value.copy(
                        modelLoadError = "Downloaded artifact does not match the selected model."
                    )
                    return@launch
                }

                _state.value = _state.value.copy(
                    loadingModel = true,
                    modelLoadError = null,
                    pendingRuntimeAction = "Loading ${tier.name}"
                )
                val device = _state.value.deviceInfo ?: deviceCapabilities.assessDevice()
                val spec = tier.toModelSpec(modelStorage, device)
                val handoff = selectedModelResidency.beginProvisionalPin(
                    spec = spec,
                    persistedFallback = resolvePersistedSelectedModelSpec()
                )
                provisionalPin = handoff

                // Commit selection only after the exact model successfully loads
                // and owns its persistent residency pin.
                val committed = withContext(NonCancellable) {
                    handoff.commit {
                        context.getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                            .edit()
                            .putString(SELECTED_SLM_ID, tier.id)
                            .commit()
                    }
                }
                check(committed) {
                    "Could not durably persist the selected model."
                }
                _state.value = _state.value.copy(
                    loadingModel = false,
                    modelLoadError = null
                )
            } catch (cancelled: CancellationException) {
                _state.value = _state.value.copy(loadingModel = false)
                throw cancelled
            } catch (error: Exception) {
                val nativeHint = if (error.message?.contains("Failed to load model") == true) {
                    "\n\nCheck logcat for native error: adb logcat -s pocketfinancer_llm:*"
                } else {
                    ""
                }
                val diagnostics = buildDiagnostics(File(path))
                _state.value = _state.value.copy(
                    loadingModel = false,
                    modelLoadError = "${error.message}$nativeHint\n\n$diagnostics"
                )
            } finally {
                withContext(NonCancellable) {
                    provisionalPin?.rollbackUnlessCommitted()
                    flowLease.release()
                }
            }
        }
    }

    fun unloadModel() {
        if (!_state.value.canUnloadModel) return
        if (modelActionJob?.isActive == true) return
        modelActionJob = viewModelScope.launch {
            val flowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.SETTINGS_MANUAL)
            if (flowLease == null) {
                _state.value = _state.value.copy(
                    modelLoadError = "Model changes are paused while onboarding reset completes."
                )
                return@launch
            }
            _state.value = _state.value.copy(
                pendingRuntimeAction =
                    "Releasing selected-model residency; active work will drain safely"
            )
            try {
                // Releases only the Settings/user pin. Other leases or pins keep
                // the model resident and physical unload remains deferred.
                selectedModelResidency.unpin()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    modelLoadError = "Could not release model: ${error.message}"
                )
            } finally {
                withContext(NonCancellable) {
                    flowLease.release()
                }
            }
        }
    }

    fun refreshModelStatus() {
        applyRuntimeState(slmRuntime.state.value)
        _state.value = _state.value.copy(
            downloadState = normalizedDownloadState(_state.value.downloadState)
        )
    }

    fun runTestSms() {
        if (testJob?.isActive == true) return
        val spec = slmRuntime.state.value.loadedModel
        if (spec == null || !_state.value.canRunTest) {
            _state.value = _state.value.copy(testError = "Model not loaded or runtime is busy.")
            return
        }

        // Click-time snapshot: delays, queueing, and the native request all use
        // this one value even if the switch changes in the meantime.
        val useGrammar = slmProcessingPreferences.gbnfGrammarEnabled.value
        testJob = viewModelScope.launch {
            runTestSms(spec, useGrammar)
        }
    }

    fun cancelTestSms() {
        testJob?.cancel()
    }

    private suspend fun runTestSms(spec: SlmModelSpec, useGrammar: Boolean) {
        val sender = "AX-HDFCBK"
        val body =
            "HDFC Bank: Rs.500.00 credited to a/c XXXXXX0000 on 01-01-20 by " +
                "a/c linked to VPA demouser000@examplebank (UPI Ref No 000000000000)."
        val appFlowLease = appFlowCoordinator.tryEnter(SlmRuntimeOwner.SETTINGS_TEST)
        if (appFlowLease == null) {
            _state.value = _state.value.copy(
                testRunning = false,
                testProgress = null,
                testError = "Test is paused while onboarding reset completes."
            )
            return
        }
        var lease: SlmLease? = null

        _state.value = _state.value.copy(
            testRunning = true,
            testProgress = "Phase 0: SMS Filtering...",
            testResult = null,
            testParsed = null,
            thinkingOutput = null,
            testError = null,
            filterLogs = null,
            sessionCacheLogs = null,
            slmPrompt = null
        )

        try {
            delay(800)
            val filter = smsFilterPipeline.filterWithDetails(sender, body)
            _state.value = _state.value.copy(filterLogs = filter.logs)
            delay(1_000)
            if (!filter.isTransactional) {
                _state.value = _state.value.copy(
                    testRunning = false,
                    testProgress = "Filtered Out (Non-transactional)",
                    testResult = "Skipping SLM inference: SMS is non-transactional."
                )
                return
            }

            _state.value = _state.value.copy(testProgress = "Queued for local SLM runtime...")
            val testLease = slmRuntime.acquire(SlmRuntimeOwner.SETTINGS_TEST, spec)
            lease = testLease
            val rawPrompt = promptBuilder.buildExtractionPrompt(sender, body)
            val fallbackPrompt = promptBuilder.buildChatPrompt(
                rawPrompt,
                enableThinking = testLease.model.hasThinkingMode
            )
            val staticPrefix = promptBuilder.getStaticPrefix()
            _state.value = _state.value.copy(
                testProgress = "Checking/preparing KV cache session...",
                slmPrompt = fallbackPrompt,
                sessionCacheLogs = listOf(
                    "Cache lookup is part of the queued extraction request.",
                    "Exact cache diagnostics will be shown when it completes."
                )
            )

            val startedAt = System.currentTimeMillis()
            _state.value = _state.value.copy(
                testProgress = if (testLease.model.hasThinkingMode) {
                    "Phase 1: Thinking (<think> block)..."
                } else {
                    "Generating JSON..."
                }
            )
            val result = testLease.extract(
                SlmExtractionRequest(
                    messages = listOf(
                        SlmChatMessage(
                            "system",
                            "You are a helpful financial SMS extraction assistant."
                        ),
                        SlmChatMessage("user", rawPrompt)
                    ),
                    fallbackPrompt = fallbackPrompt,
                    staticPrefix = staticPrefix,
                    grammar = if (useGrammar) {
                        modelStorage.readTextAsset(GRAMMAR_ASSET)
                    } else {
                        null
                    },
                    thinkingTokens = 1024,
                    answerTokens = 256,
                    thinkingCallback = { token ->
                        _state.value = _state.value.copy(
                            thinkingOutput = (_state.value.thinkingOutput ?: "") + token
                        )
                    },
                    jsonCallback = { token ->
                        _state.value = _state.value.copy(
                            testProgress = "Phase 2: Structured JSON...",
                            testResult = (_state.value.testResult ?: "") + token
                        )
                    }
                )
            )
            renderTestResult(result, body, sender, System.currentTimeMillis() - startedAt)
        } catch (cancelled: CancellationException) {
            _state.value = _state.value.copy(
                testRunning = false,
                testProgress = null,
                testError = "Test cancelled"
            )
        } catch (error: Exception) {
            Log.e(TAG, "Test failed with exception", error)
            _state.value = _state.value.copy(
                testRunning = false,
                testProgress = null,
                testError = "Test failed: ${error.message}"
            )
        } finally {
            withContext(NonCancellable) {
                lease?.release()
                appFlowLease.release()
            }
        }
    }

    private suspend fun renderTestResult(
        result: SlmExtractionResult,
        body: String,
        sender: String,
        elapsedMs: Long
    ) {
        when (result) {
            is SlmExtractionResult.Success -> {
                val trimmed = result.json.trim()
                val parsed = extractionParser.parse(trimmed)
                parsed?.let { transaction ->
                    val account = transaction.account?.let {
                        accountRepository.getOrCreate(it, "Unknown Account", "auto-extracted")
                    } ?: accountRepository.ensureDefault()
                    val merchant = transaction.counterparty
                        ?.takeIf { it.isNotBlank() && !it.equals("null", ignoreCase = true) }
                        ?: "Transaction (Test)"
                    transactionRepository.insert(
                        TransactionRepository.NewTransaction(
                            amount = transaction.amount,
                            merchant = merchant,
                            date = System.currentTimeMillis(),
                            type = transaction.type,
                            accountId = account.id,
                            rawMessage = body,
                            sender = sender,
                            slmPromptEvalMs = result.perf?.tPromptEvalMs,
                            slmEvalMs = result.perf?.tEvalMs,
                            slmNumTokens = result.perf?.nTokens,
                            slmModelName = File(result.model.modelPath).name
                        )
                    )
                }
                val performance = result.perf?.let {
                    "\n\nPerformance:\n" +
                        "  Generation: ${it.tEvalMs}ms for ${it.nTokens} tokens\n" +
                        "  Speed: ${"%.1f".format(it.tokensPerSecond)} tok/s"
                }.orEmpty()
                _state.value = _state.value.copy(
                    testRunning = false,
                    testProgress = null,
                    testResult = "Raw JSON: $trimmed\nElapsed: ${elapsedMs}ms$performance",
                    sessionCacheLogs = cacheDiagnostics(result.cache),
                    testParsed = parsed?.let {
                        "amount=${it.amount}, type=${it.type.name.lowercase()}, " +
                            "counterparty=${it.counterparty ?: "-"}, account=${it.account ?: "-"}"
                    } ?: "Parsed: null (non-financial)"
                )
            }
            is SlmExtractionResult.Null -> {
                _state.value = _state.value.copy(
                    testRunning = false,
                    testProgress = null,
                    testResult = "Model returned null (not a financial transaction)\n" +
                        "Elapsed: ${elapsedMs}ms",
                    sessionCacheLogs = cacheDiagnostics(result.cache),
                    testParsed = "N/A"
                )
            }
            is SlmExtractionResult.Error -> {
                _state.value = _state.value.copy(
                    testRunning = false,
                    testProgress = null,
                    testError = result.message
                )
            }
            is SlmExtractionResult.Stopped -> {
                _state.value = _state.value.copy(
                    testRunning = false,
                    testProgress = null,
                    testError = "Inference stopped"
                )
            }
        }
    }

    private fun cacheDiagnostics(
        cache: com.pocketfinancer.inference.SlmCacheDiagnostics
    ): List<String> = when {
        !cache.attempted -> listOf("This request did not use a session-cache prefix.")
        cache.hit -> buildList {
            add("KV cache hit (${cache.prefixTokens} prefix tokens).")
            cache.sessionFile?.let { add("Session: ${File(it).name}") }
        }
        else -> buildList {
            add("KV cache miss; the prefix was evaluated for this request.")
            if (cache.prefixTokens > 0) add("Prefix Size: ${cache.prefixTokens} tokens")
            cache.sessionFile?.let { add("Session: ${File(it).name}") }
        }
    }

    fun toggleProcessIncomingSms() {
        val next = !_state.value.processIncomingSms
        context.getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(PROCESS_INCOMING_SMS, next)
            .apply()
        _state.value = _state.value.copy(processIncomingSms = next)
    }

    fun setGbnfGrammarEnabled(enabled: Boolean) {
        slmProcessingPreferences.setGbnfGrammarEnabled(enabled)
    }

    fun resetOnboarding(onSuccess: () -> Unit) {
        if (resetJob?.isActive == true) return
        if (!_state.value.canResetOnboarding) {
            _state.value = _state.value.copy(
                modelLoadError = "Reset is unavailable while model work or downloads are active."
            )
            return
        }
        resetJob = viewModelScope.launch {
            _state.value = _state.value.copy(resetRunning = true, modelLoadError = null)
            var appFlowPause: SlmAppFlowPause? = null
            var selectionPause: SelectedModelMutationPause? = null
            var maintenance: com.pocketfinancer.inference.SlmMaintenanceLease? = null
            var smsWorkPause: SmsWorkAdmissionPause? = null
            var previousSelectedPin: SlmModelSpec? = null
            var resetIntentDurable = false
            var invokeSuccess = false
            try {
                // Non-suspending acquisition means cancellation cannot close
                // admission without delivering the token to this finally.
                smsWorkPause = smsWorkController.pauseAdmissions()
                smsWorkPause.cancelPending()

                // Pause admission first and synchronously drain every admitted
                // foreground workflow. Service stop alone is asynchronous and
                // cannot close the scan/download-before-native race.
                appFlowPause = appFlowCoordinator.tryPauseAndDrain(
                    SlmRuntimeOwner.SETTINGS_MANUAL
                )
                if (appFlowPause == null) {
                    error("Another onboarding reset is already draining app flows.")
                }

                selectionPause = selectedModelResidency.tryPauseMutations()
                if (selectionPause == null) {
                    error("A selected-model handoff is still completing.")
                }
                previousSelectedPin =
                    selectedModelResidency.currentSelectedSpec()
                        ?: resolvePersistedSelectedModelSpec()

                SyncService.stop(context)
                context.stopService(
                    android.content.Intent(context, OnboardingService::class.java)
                )
                smsWorkController.cancelPending()

                maintenance = slmRuntime.tryAcquireMaintenance(
                    owner = SlmRuntimeOwner.SETTINGS_MANUAL,
                    pinsToRelease = setOf(SlmRuntimeOwner.SELECTED_MODEL)
                )
                if (maintenance == null) {
                    error("Runtime became busy. Wait for it to finish, then retry reset.")
                }
                // Runtime maintenance removed the selected pin directly.
                selectionPause.recordExternalPinMutation()

                // Close the WorkManager admission race a second time while the
                // native and app-flow gates are both held.
                smsWorkController.cancelPending()
                withContext(Dispatchers.IO) {
                    // Cross-store reset cannot be fully atomic. Make the reset
                    // intent durable first: after process death, workers skip
                    // and Application will not restore the old selected pin.
                    val committed = context
                        .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(ONBOARDING_COMPLETED, false)
                        .remove(SELECTED_SLM_ID)
                        .putLong(
                            OnboardingRunGenerationStore.PREFERENCE_KEY,
                            onboardingRunGenerationStore.nextGeneration()
                        )
                        .commit()
                    check(committed) {
                        "Could not durably persist the onboarding reset."
                    }
                    resetIntentDurable = true
                    transactionRepository.clearDatabase()
                }
                homeSyncManager.resetState()
                onboardingSyncManager.reset()
                invokeSuccess = true
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    modelLoadError = "Reset failed: ${error.message}"
                )
            } finally {
                val cleanupError = withContext(NonCancellable) {
                    var firstFailure: Throwable? = null

                    suspend fun captureCleanup(block: suspend () -> Unit): Boolean {
                        try {
                            block()
                            return true
                        } catch (failure: Throwable) {
                            if (firstFailure == null) {
                                firstFailure = failure
                            } else {
                                firstFailure!!.addSuppressed(failure)
                            }
                            return false
                        }
                    }

                    val maintenanceReleased = if (maintenance == null) {
                        true
                    } else {
                        captureCleanup { maintenance?.release() }
                    }
                    // Never queue a restore behind a maintenance lease whose
                    // release failed; release every admission gate and surface
                    // the original cleanup failure instead.
                    if (maintenance != null &&
                        maintenanceReleased &&
                        !resetIntentDurable
                    ) {
                        captureCleanup {
                            selectionPause?.restorePin(previousSelectedPin)
                        }
                    }
                    captureCleanup { selectionPause?.release() }
                    captureCleanup { appFlowPause?.release() }
                    captureCleanup { smsWorkPause?.release() }
                    firstFailure
                }
                try {
                    if (cleanupError != null) {
                        invokeSuccess = false
                        Log.e(TAG, "Reset cleanup failed", cleanupError)
                        _state.value = _state.value.copy(
                            modelLoadError = "Reset cleanup failed: ${cleanupError.message}"
                        )
                    }
                } finally {
                    _state.value = _state.value.copy(resetRunning = false)
                }
            }
            if (invokeSuccess) onSuccess()
        }
    }

    fun getModelFilePath(): String {
        val tier = _state.value.selectedSlm ?: return "No SLM selected"
        return modelStorage.modelFile(tier.modelFile).absolutePath
    }

    private fun resolvePersistedSelectedModelSpec(): SlmModelSpec? {
        val selectedId = context.getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
            .getString(SELECTED_SLM_ID, null)
            ?: return null
        val tier = SlmTier.ALL_TIERS.find { it.id == selectedId } ?: return null
        val file = modelStorage.modelFile(tier.modelFile)
        if (!isPublishedModelArtifact(file)) return null
        val device = _state.value.deviceInfo ?: deviceCapabilities.assessDevice()
        return tier.toModelSpec(modelStorage, device)
    }

    private fun normalizedDownloadState(
        downloaderState: ModelDownloader.DownloadState
    ): ModelDownloader.DownloadState {
        if (downloaderState.isDownloading || downloaderState.isComplete) return downloaderState
        val tier = _state.value.selectedSlm ?: return downloaderState
        val file = modelStorage.modelFile(tier.modelFile)
        return if (isPublishedModelArtifact(file)) {
            downloaderState.copy(
                isComplete = true,
                progress = 1f,
                downloadedMb = file.length() / 1_048_576f,
                totalMb = file.length() / 1_048_576f,
                outputPath = file.absolutePath
            )
        } else {
            downloaderState
        }
    }

    private fun applyRuntimeState(runtime: SlmRuntimeState) {
        val selectedPin = runtime.pinnedModels[SlmRuntimeOwner.SELECTED_MODEL]
        val pending = when (val action = runtime.pendingAction) {
            is SlmPendingAction.ModelChange ->
                "Waiting to load ${File(action.model.modelPath).name}"
            SlmPendingAction.Unload ->
                "Unload pending until active work and owners finish"
            is SlmPendingAction.Maintenance ->
                "Maintenance requested by ${action.owner.value}"
            null -> null
        }
        val testIsQueued = _state.value.testRunning &&
            runtime.leasesByOwner.containsKey(SlmRuntimeOwner.SETTINGS_TEST) &&
            runtime.activeOperation?.owner != SlmRuntimeOwner.SETTINGS_TEST
        _state.value = _state.value.copy(
            modelLoaded = runtime.loadedModel != null,
            modelPath = runtime.loadedModel?.modelPath,
            modelPinnedByUser = selectedPin != null,
            runtimePhase = runtime.phase,
            // Scoped leases remain held through parsing/database persistence,
            // even while the native actor itself is idle.
            runtimeBusy = runtime.isBusy || runtime.leaseCount > 0,
            runtimeQueueDepth = runtime.queueDepth,
            runtimeLeaseCount = runtime.leaseCount,
            runtimeActiveOwner = runtime.activeOperation?.owner?.value,
            pendingRuntimeAction = pending,
            testProgress = if (testIsQueued) {
                "Queued behind ${runtime.activeOperation?.owner?.value ?: "another request"}"
            } else {
                _state.value.testProgress
            },
            runtimeError = runtime.lastError
        )
    }

    private fun buildDiagnostics(file: File): String = buildString {
        append("File: ${file.name}\n")
        append("Exists: ${file.exists()}\n")
        if (file.exists()) {
            append("Size: ${file.length() / 1_048_576} MB (${file.length()} bytes)\n")
            append("Readable: ${file.canRead()}\n")
        }
        _state.value.deviceInfo?.let { device ->
            append("RAM: ${"%.1f".format(device.ramGb)} GB (tier: ${device.ramTier})\n")
            append("High-perf CPU: ${device.isHighPerformanceDevice}\n")
            append("Free storage: ${"%.1f".format(device.storage.availableGb)} GB\n")
        }
    }

    private companion object {
        const val TAG = "PocketFinancer"
        const val APP_SETTINGS = ".app_settings"
        const val PROCESS_INCOMING_SMS = "process_incoming_sms"
        const val SELECTED_SLM_ID = "selected_slm_id"
        const val ONBOARDING_COMPLETED = "onboarding_completed"
        const val GRAMMAR_ASSET = "sms_extraction.gbnf"
    }
}
