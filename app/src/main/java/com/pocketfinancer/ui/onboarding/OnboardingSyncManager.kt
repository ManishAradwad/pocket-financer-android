package com.pocketfinancer.ui.onboarding

import android.content.Context
import android.content.Intent
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.setup.AdaptiveHistoryScanPolicy
import com.pocketfinancer.setup.SetupActionableError
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnboardingSyncManager @Inject constructor(
    private val runGenerationStore: OnboardingRunGenerationStore,
    private val appFlowCoordinator: SlmAppFlowCoordinator,
    private val setupImportStore: SetupImportStore? = null
) {
    private val historyScanPolicy = AdaptiveHistoryScanPolicy()

    enum class RunPurpose {
        INITIAL_SETUP,
        MODEL_UPGRADE
    }

    data class OnboardingSyncState(
        val runId: String? = null,
        val isRunning: Boolean = false,
        val isCancelling: Boolean = false,
        val isCancellationAllowed: Boolean = false,
        val isPreparingHistoricalModel: Boolean = false,
        val runPurpose: RunPurpose = RunPurpose.INITIAL_SETUP,
        val step: OnboardingStep = OnboardingStep.WELCOME,
        val selectedSlm: SlmTier? = null,
        val downloadState: ModelDownloader.DownloadState = ModelDownloader.DownloadState(),
        val isDownloading: Boolean = false,
        val isModelLoaded: Boolean = false,
        val modelLoadError: String? = null,
        val syncProgress: Float = 0f,
        val syncMessage: String = "",
        val syncLogs: List<String> = emptyList(),
        val syncEtaSeconds: Int = 0,
        val hasPermissions: Boolean = false,
        val hasNotificationPermission: Boolean = false,
        val showNotificationWarning: Boolean = false,
        val deniedCount: Int = 0,
        val syncTotalMessages: Int = 0,
        val syncTransactionalCount: Int = 0,
        val syncParsedCount: Int = 0,
        val syncSpendsTotal: Double = 0.0,
        val syncRecentTransactions: List<ExtractedTxPreview> = emptyList(),
        val activeHistoricalSms: HistoricalSmsProcessingActivity? = null
    )

    private val _syncState = MutableStateFlow(OnboardingSyncState())
    val syncState: StateFlow<OnboardingSyncState> = _syncState.asStateFlow()

    fun startOnboarding(context: Context, slm: SlmTier) {
        startRun(context, slm, RunPurpose.INITIAL_SETUP)
    }

    fun startHistoricalImport(
        context: Context,
        slm: SlmTier,
        coveredWindowDays: Int?
    ) {
        startRun(
            context = context,
            slm = slm,
            purpose = RunPurpose.INITIAL_SETUP,
            coveredWindowDays = coveredWindowDays
        )
    }

    /**
     * Re-checks the last durably verified window after an interrupted run.
     *
     * Candidates live only in process memory during the foreground historical
     * import, so advancing immediately beyond [resumeWindowDays] after process
     * death could skip a candidate that had been discovered but not saved.
     * Atomic source identity makes this conservative re-read idempotent.
     */
    fun resumeHistoricalImport(
        context: Context,
        slm: SlmTier,
        resumeWindowDays: Int
    ) {
        startRun(
            context = context,
            slm = slm,
            purpose = RunPurpose.INITIAL_SETUP,
            resumeWindowDays = resumeWindowDays
        )
    }

    fun startModelUpgrade(context: Context, slm: SlmTier) {
        startRun(context, slm, RunPurpose.MODEL_UPGRADE)
    }

    private fun startRun(
        context: Context,
        slm: SlmTier,
        purpose: RunPurpose,
        coveredWindowDays: Int? = null,
        resumeWindowDays: Int? = null
    ) {
        val stateBeforeAdmission = _syncState.value
        if (stateBeforeAdmission.isRunning) return
        val runId = newOnboardingRunId()
        val durableSetup = setupImportStore?.state?.value
        if (
            purpose == RunPurpose.INITIAL_SETUP &&
            durableSetup != null &&
            !durableSetup.modelPrepared &&
            !durableSetup.modelDownloadConfirmed
        ) {
            _syncState.compareAndSet(
                stateBeforeAdmission,
                stateBeforeAdmission.copy(
                    isRunning = false,
                    isDownloading = false,
                    isPreparingHistoricalModel = false,
                    activeHistoricalSms = null,
                    modelLoadError =
                        "Confirm the approximately 700 MB model download first."
                )
            )
            return
        }

        // Capture before checking the in-memory gate. If reset pauses after the
        // check, this old captured value becomes stale when reset commits. If
        // reset pauses before the check, admissionPaused rejects scheduling.
        // Re-reading the generation after the check would create a TOCTOU where
        // a racing pre-reset start could accidentally receive the new value.
        val requestedGeneration = runGenerationStore.currentGeneration()
        if (appFlowCoordinator.state.value.admissionPaused) {
            _syncState.compareAndSet(
                stateBeforeAdmission,
                stateBeforeAdmission.copy(
                    isRunning = false,
                    isDownloading = false,
                    isPreparingHistoricalModel = false,
                    activeHistoricalSms = null,
                    modelLoadError = if (purpose == RunPurpose.MODEL_UPGRADE) {
                        "Model upgrade is temporarily paused while other model maintenance completes."
                    } else {
                        "Onboarding start is paused while reset completes."
                    }
                )
            )
            return
        }
        val admittingState = OnboardingSyncState(
            runId = runId,
            isRunning = true,
            isCancellationAllowed = purpose == RunPurpose.MODEL_UPGRADE,
            isPreparingHistoricalModel = purpose == RunPurpose.INITIAL_SETUP,
            runPurpose = purpose,
            selectedSlm = slm,
            step = OnboardingStep.DOWNLOAD_SLM,
            syncMessage = if (purpose == RunPurpose.MODEL_UPGRADE) {
                "Starting the background model download..."
            } else {
                ""
            }
        )
        // The opaque run token is the in-memory start reservation. If another
        // start or state transition won after the initial read, this request
        // owns nothing and must not touch durable setup state or dispatch a
        // service. Failure rollback below is likewise scoped to this runId.
        if (!_syncState.compareAndSet(stateBeforeAdmission, admittingState)) {
            return
        }
        if (purpose == RunPurpose.INITIAL_SETUP) {
            val requestedWindowDays = initialHistoryWindowDays(
                policy = historyScanPolicy,
                coveredWindowDays = coveredWindowDays,
                resumeWindowDays = resumeWindowDays
            )
            try {
                setupImportStore?.update { setup ->
                    setup.copy(
                        // A cached artifact still needs runtime acquisition and
                        // an authoritative permission gate before scanning.
                        status = SetupImportStatus.DOWNLOADING,
                        activeScanWindowDays = requestedWindowDays,
                        activeScanProviderMaxDateMillis =
                            if (
                                resumeWindowDays != null &&
                                setup.activeScanWindowDays == resumeWindowDays
                            ) {
                                setup.activeScanProviderMaxDateMillis
                            } else {
                                null
                            },
                        pauseReason = null,
                        actionableError = null
                    )
                }
            } catch (_: Exception) {
                val message =
                    "Setup could not start because progress storage is " +
                        "unavailable. Saved transactions remain safe."
                setupImportStore?.let { store ->
                    val current = store.state.value
                    if (
                        current.status != SetupImportStatus.PERMISSION_NEEDED &&
                        !(
                            current.status == SetupImportStatus.FAILED &&
                                current.actionableError != null
                        )
                    ) {
                        store.publishVolatilePersistenceFallback(
                            current.copy(
                                status = SetupImportStatus.FAILED,
                                pauseReason = null,
                                actionableError = SetupActionableError(
                                    code = "SETUP_START_PERSISTENCE_FAILED",
                                    message = message,
                                    actionLabel = "Try again"
                                )
                            )
                        )
                    }
                }
                failRunAdmission(runId, message)
                return
            }
        }
        val intent = Intent(context, OnboardingService::class.java).apply {
            putExtra("EXTRA_SLM_ID", slm.id)
            putExtra(EXTRA_RUN_PURPOSE, purpose.name)
            putExtra(EXTRA_RUN_ID, runId)
            coveredWindowDays?.let {
                putExtra(EXTRA_COVERED_HISTORY_WINDOW_DAYS, it)
            }
            resumeWindowDays?.let {
                putExtra(EXTRA_RESUME_HISTORY_WINDOW_DAYS, it)
            }
        }
        runGenerationStore.stamp(intent, requestedGeneration)
        try {
            context.startForegroundService(intent)
        } catch (error: Exception) {
            val message =
                error.message ?: "Could not start background model work."
            if (purpose == RunPurpose.INITIAL_SETUP) {
                setupImportStore?.let { store ->
                    val setupMessage = error.message
                        ?: "Could not start background setup work."
                    try {
                        store.update {
                            it.copy(
                                status = SetupImportStatus.FAILED,
                                actionableError = SetupActionableError(
                                    code = "BACKGROUND_START_FAILED",
                                    message = setupMessage,
                                    actionLabel = "Try again"
                                )
                            )
                        }
                    } catch (_: Exception) {
                        store.publishVolatilePersistenceFallback(
                            store.state.value.copy(
                                status = SetupImportStatus.FAILED,
                                pauseReason = null,
                                actionableError = SetupActionableError(
                                    code = "BACKGROUND_START_FAILED",
                                    message = setupMessage,
                                    actionLabel = "Try again"
                                )
                            )
                        )
                    }
                }
            }
            failRunAdmission(runId, message)
        }
    }

    private fun failRunAdmission(runId: String, message: String) {
        _syncState.update { current ->
            if (current.runId != runId) {
                current
            } else {
                current.copy(
                    runId = null,
                    isRunning = false,
                    isCancelling = false,
                    isDownloading = false,
                    isCancellationAllowed = false,
                    isPreparingHistoricalModel = false,
                    activeHistoricalSms = null,
                    downloadState = current.downloadState.copy(
                        isDownloading = false
                    ),
                    syncMessage = message,
                    syncEtaSeconds = 0,
                    modelLoadError = message
                )
            }
        }
    }

    /**
     * Requests cancellation without claiming that cleanup has already
     * completed. The service publishes the terminal Cancelled state only after
     * downloader cancellation, selected-model rollback, and app-flow admission
     * release have all drained.
     */
    fun requestModelUpgradeCancellation(context: Context): Boolean {
        var requestedRunId: String? = null
        while (true) {
            val current = _syncState.value
            if (!current.isRunning ||
                current.isCancelling ||
                !current.isCancellationAllowed ||
                current.runPurpose != RunPurpose.MODEL_UPGRADE
            ) {
                return false
            }
            val cancelling = current.copy(
                isCancelling = true,
                isCancellationAllowed = false,
                syncMessage = "Cancelling model upgrade...",
                modelLoadError = null
            )
            if (_syncState.compareAndSet(current, cancelling)) {
                requestedRunId = current.runId
                break
            }
        }

        val serviceWasRunning = context.stopService(
            Intent(context, OnboardingService::class.java)
        )
        if (!serviceWasRunning) {
            completeCancellationIfRequested(requestedRunId)
        }
        return true
    }

    /**
     * Requests a user stop for the currently active historical import.
     *
     * The state CAS is the user-visible linearization point: the action is
     * disabled immediately, while the service remains running until the
     * coroutine, native request, runtime lease, provisional model pin, and
     * app-flow lease have all drained.
     */
    fun requestHistoricalImportCancellation(context: Context): Boolean {
        val runId = _syncState.value.runId ?: return false
        return requestHistoricalImportCancellation(
            context = context,
            expectedRunId = runId
        )
    }

    /**
     * Requests cancellation only when [expectedRunId] still owns the visible
     * historical run. UI actions must pass the run id captured by the rendered
     * card so a stale callback can never stop a successor operation.
     */
    fun requestHistoricalImportCancellation(
        context: Context,
        expectedRunId: String
    ): Boolean = requestHistoricalImportCancellationInternal(
        context = context,
        expectedRunId = expectedRunId,
        expectedCandidateKey = null,
        requireCandidateMatch = false
    )

    /** Candidate-scoped variant used by controls rendered from live UI state. */
    fun requestHistoricalImportCancellation(
        context: Context,
        expectedRunId: String,
        expectedCandidateKey: String?
    ): Boolean = requestHistoricalImportCancellationInternal(
        context = context,
        expectedRunId = expectedRunId,
        expectedCandidateKey = expectedCandidateKey,
        requireCandidateMatch = true
    )

    private fun requestHistoricalImportCancellationInternal(
        context: Context,
        expectedRunId: String,
        expectedCandidateKey: String?,
        requireCandidateMatch: Boolean
    ): Boolean {
        val visibleState = _syncState.value
        if (
            visibleState.runId != expectedRunId ||
            (
                requireCandidateMatch &&
                    visibleState.activeHistoricalSms?.candidateKey !=
                    expectedCandidateKey
                )
        ) {
            return false
        }
        val durableStatus = setupImportStore?.state?.value?.status ?: return false
        val cancellationRequested = if (requireCandidateMatch) {
            tryRequestHistoricalImportCancellationForTarget(
                runId = expectedRunId,
                durableStatus = durableStatus,
                expectedCandidateKey = expectedCandidateKey
            )
        } else {
            tryRequestHistoricalImportCancellation(
                runId = expectedRunId,
                durableStatus = durableStatus
            )
        }
        if (!cancellationRequested) {
            return false
        }

        val intent = Intent(context, OnboardingService::class.java).apply {
            action = ACTION_STOP_HISTORICAL_IMPORT
            putExtra(EXTRA_RUN_ID, expectedRunId)
        }
        return try {
            if (context.startService(intent) != null) {
                true
            } else {
                restoreHistoricalCancellationAfterDispatchFailure(
                    runId = expectedRunId,
                    errorMessage =
                        "Could not ask the background import to stop."
                )
                false
            }
        } catch (error: Exception) {
            restoreHistoricalCancellationAfterDispatchFailure(
                runId = expectedRunId,
                errorMessage = error.message
                    ?: "Could not ask the background import to stop."
            )
            false
        }
    }

    /** Enables Stop only after the durable setup phase is cancellable. */
    internal fun allowHistoricalImportCancellation(
        runId: String,
        durableStatus: SetupImportStatus?
    ): Boolean {
        if (!durableStatus.isHistoricalImportCancellable()) return false
        while (true) {
            val current = _syncState.value
            if (!historicalRunMatches(current, runId) || current.isCancelling) {
                return false
            }
            if (
                current.isCancellationAllowed &&
                !current.isPreparingHistoricalModel
            ) {
                return true
            }
            if (
                _syncState.compareAndSet(
                    current,
                    current.copy(
                        isCancellationAllowed = true,
                        isPreparingHistoricalModel = false
                    )
                )
            ) {
                return true
            }
        }
    }

    /** New UI requests must win exactly one CAS and cannot be repeated. */
    internal fun tryRequestHistoricalImportCancellation(
        runId: String,
        durableStatus: SetupImportStatus?
    ): Boolean = tryRequestHistoricalImportCancellationMatching(
        runId = runId,
        durableStatus = durableStatus,
        candidateMatches = { true }
    )

    private fun tryRequestHistoricalImportCancellationForTarget(
        runId: String,
        durableStatus: SetupImportStatus?,
        expectedCandidateKey: String?
    ): Boolean = tryRequestHistoricalImportCancellationMatching(
        runId = runId,
        durableStatus = durableStatus,
        candidateMatches = { state ->
            state.activeHistoricalSms?.candidateKey == expectedCandidateKey
        }
    )

    private fun tryRequestHistoricalImportCancellationMatching(
        runId: String,
        durableStatus: SetupImportStatus?,
        candidateMatches: (OnboardingSyncState) -> Boolean
    ): Boolean {
        while (true) {
            val current = _syncState.value
            if (
                !candidateMatches(current) ||
                !historicalCancellationMatches(
                    state = current,
                    requestedRunId = runId,
                    durableStatus = durableStatus
                )
            ) {
                return false
            }
            val cancelling = current.copy(
                isCancelling = true,
                isCancellationAllowed = false,
                syncMessage = "Stopping SMS processing...",
                modelLoadError = null
            )
            if (_syncState.compareAndSet(current, cancelling)) return true
        }
    }

    /**
     * Notification commands may observe the CAS already published by a UI
     * caller. They still belong to the same request, but a stale run ID never
     * gains authority over the active service run.
     */
    internal fun acceptHistoricalImportCancellation(
        runId: String,
        durableStatus: SetupImportStatus?
    ): Boolean {
        val current = _syncState.value
        if (
            current.isCancelling &&
            historicalRunMatches(current, runId)
        ) {
            return true
        }
        return tryRequestHistoricalImportCancellation(runId, durableStatus)
    }

    /**
     * Exactly one of a user stop or the short terminal durable commit can win.
     */
    internal fun tryBeginHistoricalImportCommit(runId: String): Boolean {
        while (true) {
            val current = _syncState.value
            if (
                !historicalRunMatches(current, runId) ||
                current.isCancelling
            ) {
                return false
            }
            // An unprepared-model failure was never cancellable. It may still
            // publish its truthful terminal state without manufacturing a CAS
            // race. Repeated terminal guards for the same single workflow are
            // idempotent after the first guard closes cancellation.
            if (!current.isCancellationAllowed) return true
            val committing = current.copy(
                isCancellationAllowed = false,
                syncMessage = "Finishing SMS import..."
            )
            if (_syncState.compareAndSet(current, committing)) return true
        }
    }

    internal fun historicalCancellationRequested(runId: String): Boolean {
        val current = _syncState.value
        return current.isCancelling && historicalRunMatches(current, runId)
    }

    internal fun completeHistoricalImportCancellation(runId: String): Boolean {
        while (true) {
            val current = _syncState.value
            if (!current.isCancelling || !historicalRunMatches(current, runId)) {
                return false
            }
            val stopped = current.copy(
                runId = null,
                isRunning = false,
                isCancelling = false,
                isCancellationAllowed = false,
                isPreparingHistoricalModel = false,
                isDownloading = false,
                downloadState = current.downloadState.copy(
                    isDownloading = false,
                    error = null
                ),
                syncProgress = 0f,
                syncLogs = emptyList(),
                syncEtaSeconds = 0,
                syncTotalMessages = 0,
                syncTransactionalCount = 0,
                syncParsedCount = 0,
                syncSpendsTotal = 0.0,
                syncRecentTransactions = emptyList(),
                activeHistoricalSms = null,
                syncMessage =
                    "SMS processing stopped. Completed saves remain on this device."
            )
            if (_syncState.compareAndSet(current, stopped)) return true
        }
    }

    /**
     * Closes a cancellation request when another durable terminal gate (most
     * importantly permission loss) won. This never manufactures a user pause.
     */
    internal fun completeHistoricalCancellationWithoutUserPause(
        runId: String,
        syncMessage: String
    ): Boolean {
        while (true) {
            val current = _syncState.value
            if (!current.isCancelling || !historicalRunMatches(current, runId)) {
                return false
            }
            val settled = current.copy(
                runId = null,
                isRunning = false,
                isCancelling = false,
                isCancellationAllowed = false,
                isPreparingHistoricalModel = false,
                isDownloading = false,
                syncProgress = 0f,
                syncLogs = emptyList(),
                syncEtaSeconds = 0,
                syncTotalMessages = 0,
                syncTransactionalCount = 0,
                syncParsedCount = 0,
                syncSpendsTotal = 0.0,
                syncRecentTransactions = emptyList(),
                activeHistoricalSms = null,
                syncMessage = syncMessage
            )
            if (_syncState.compareAndSet(current, settled)) return true
        }
    }

    private fun restoreHistoricalCancellationAfterDispatchFailure(
        runId: String,
        errorMessage: String
    ) {
        while (true) {
            val current = _syncState.value
            if (!current.isCancelling || !historicalRunMatches(current, runId)) {
                return
            }
            val restored = current.copy(
                isCancelling = false,
                // Reaching this rollback means the request CAS previously won,
                // so the run was already admitted to a cancellable historical
                // phase. Permission loss may now mask SCANNING/PROCESSING in
                // durable state, but a retry must retain that authority.
                isCancellationAllowed = true,
                syncMessage = errorMessage,
                modelLoadError = errorMessage
            )
            if (_syncState.compareAndSet(current, restored)) return
        }
    }

    /**
     * Linearization point between a cancellable activation and its short,
     * non-cancellable durable commit. Exactly one of cancellation or commit
     * can win the state compare-and-set.
     */
    internal fun tryBeginModelUpgradeCommit(): Boolean {
        while (true) {
            val current = _syncState.value
            if (!current.isRunning ||
                current.isCancelling ||
                !current.isCancellationAllowed ||
                current.runPurpose != RunPurpose.MODEL_UPGRADE
            ) {
                return false
            }
            val committing = current.copy(
                isCancellationAllowed = false,
                syncMessage = "Finishing model activation..."
            )
            if (_syncState.compareAndSet(current, committing)) return true
        }
    }

    internal fun completeCancellationIfRequested(expectedRunId: String? = null) {
        _syncState.update { current ->
            if (
                !current.isCancelling ||
                current.runPurpose != RunPurpose.MODEL_UPGRADE ||
                (expectedRunId != null && current.runId != expectedRunId)
            ) {
                current
            } else {
                current.copy(
                    runId = null,
                    isRunning = false,
                    isCancelling = false,
                    isCancellationAllowed = false,
                    isPreparingHistoricalModel = false,
                    isDownloading = false,
                    activeHistoricalSms = null,
                    downloadState = current.downloadState.copy(
                        isDownloading = false,
                        error = null
                    ),
                    syncMessage = "Cancelled"
                )
            }
        }
    }

    internal fun modelPreparationCompleted(modelFile: File) {
        val sizeMb = modelFile.length() / BYTES_PER_MEBIBYTE
        _syncState.update {
            it.copy(
                step = OnboardingStep.SYNCING,
                isDownloading = false,
                modelLoadError = null,
                downloadState = ModelDownloader.DownloadState(
                    isComplete = true,
                    progress = 1f,
                    downloadedMb = sizeMb,
                    totalMb = sizeMb,
                    outputPath = modelFile.absolutePath
                )
            )
        }
    }

    internal fun modelPreparationFailed(
        errorMessage: String,
        terminalDownloadState: ModelDownloader.DownloadState
    ) {
        val visibleError = terminalDownloadState.error
            ?: "Download failed: $errorMessage"
        _syncState.update {
            it.copy(
                step = OnboardingStep.DOWNLOAD_SLM,
                modelLoadError = visibleError,
                isRunning = false,
                isCancelling = false,
                isCancellationAllowed = false,
                isPreparingHistoricalModel = false,
                isDownloading = false,
                activeHistoricalSms = null,
                downloadState = terminalDownloadState.copy(
                    isDownloading = false,
                    isComplete = false,
                    error = visibleError
                )
            )
        }
        if (_syncState.value.runPurpose == RunPurpose.INITIAL_SETUP) {
            setupImportStore?.update {
                it.copy(
                    status = SetupImportStatus.FAILED,
                    actionableError = SetupActionableError(
                        code = "MODEL_PREPARATION_FAILED",
                        message = visibleError,
                        actionLabel = "Retry download"
                    )
                )
            }
        }
    }

    fun updateState(transform: (OnboardingSyncState) -> OnboardingSyncState) {
        _syncState.update(transform)
    }

    internal fun beginHistoricalSmsProcessing(
        runId: String,
        activity: HistoricalSmsProcessingActivity
    ): Boolean {
        while (true) {
            val current = _syncState.value
            if (!historicalRunMatches(current, runId) || current.isCancelling) {
                return false
            }
            if (
                _syncState.compareAndSet(
                    current,
                    current.copy(activeHistoricalSms = activity)
                )
            ) {
                return true
            }
        }
    }

    internal fun updateHistoricalSmsProcessing(
        runId: String,
        candidateKey: String,
        transform: (
            HistoricalSmsProcessingActivity
        ) -> HistoricalSmsProcessingActivity
    ): Boolean {
        while (true) {
            val current = _syncState.value
            val activity = current.activeHistoricalSms
            if (
                !historicalRunMatches(current, runId) ||
                activity?.candidateKey != candidateKey
            ) {
                return false
            }
            if (
                _syncState.compareAndSet(
                    current,
                    current.copy(activeHistoricalSms = transform(activity))
                )
            ) {
                return true
            }
        }
    }

    /**
     * Clearing is allowed while a matching run drains after cancellation or a
     * terminal transition. This is stricter than event publication so stale
     * callbacks cannot leave raw evidence resident in manager state.
     */
    internal fun clearHistoricalSmsProcessing(
        runId: String,
        candidateKey: String? = null
    ): Boolean {
        while (true) {
            val current = _syncState.value
            val activity = current.activeHistoricalSms ?: return true
            if (
                current.runId != runId ||
                (
                    candidateKey != null &&
                        activity.candidateKey != candidateKey
                    )
            ) {
                return false
            }
            if (
                _syncState.compareAndSet(
                    current,
                    current.copy(activeHistoricalSms = null)
                )
            ) {
                return true
            }
        }
    }

    fun reset() {
        _syncState.value = OnboardingSyncState()
    }

    companion object {
        private const val BYTES_PER_MEBIBYTE = 1_048_576f
        internal const val EXTRA_RUN_PURPOSE =
            "com.pocketfinancer.ui.onboarding.EXTRA_RUN_PURPOSE"
        internal const val EXTRA_RUN_ID =
            "com.pocketfinancer.ui.onboarding.EXTRA_RUN_ID"
        internal const val ACTION_STOP_HISTORICAL_IMPORT =
            "com.pocketfinancer.ui.onboarding.action.STOP_HISTORICAL_IMPORT"
        internal const val EXTRA_COVERED_HISTORY_WINDOW_DAYS =
            "com.pocketfinancer.ui.onboarding.EXTRA_COVERED_HISTORY_WINDOW_DAYS"
        internal const val EXTRA_RESUME_HISTORY_WINDOW_DAYS =
            "com.pocketfinancer.ui.onboarding.EXTRA_RESUME_HISTORY_WINDOW_DAYS"
    }
}

internal fun SetupImportStatus?.isHistoricalImportCancellable(): Boolean =
    this == SetupImportStatus.SCANNING || this == SetupImportStatus.PROCESSING

internal fun newOnboardingRunId(): String = UUID.randomUUID().toString()

internal fun historicalRunMatches(
    state: OnboardingSyncManager.OnboardingSyncState,
    requestedRunId: String
): Boolean =
    requestedRunId.isNotBlank() &&
        state.runId == requestedRunId &&
        state.isRunning &&
        state.runPurpose == OnboardingSyncManager.RunPurpose.INITIAL_SETUP

internal fun historicalCancellationBelongsToRun(
    state: OnboardingSyncManager.OnboardingSyncState,
    requestedRunId: String,
    durableStatus: SetupImportStatus?
): Boolean =
    historicalRunMatches(state, requestedRunId) &&
        (
            durableStatus.isHistoricalImportCancellable() ||
                (
                    durableStatus in setOf(
                        SetupImportStatus.PERMISSION_NEEDED,
                        SetupImportStatus.PAUSED
                    ) &&
                        state.isCancellationAllowed
                    )
            )

internal fun historicalCancellationMatches(
    state: OnboardingSyncManager.OnboardingSyncState,
    requestedRunId: String,
    durableStatus: SetupImportStatus?
): Boolean =
    historicalCancellationBelongsToRun(
        state = state,
        requestedRunId = requestedRunId,
        durableStatus = durableStatus
    ) &&
        !state.isCancelling &&
        state.isCancellationAllowed
