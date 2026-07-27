package com.pocketfinancer.ui.onboarding

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.isPublishedModelArtifact
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.SmsNotificationHelper
import com.pocketfinancer.setup.AdaptiveHistoryScanPolicy
import com.pocketfinancer.setup.HistoryScanDecision
import com.pocketfinancer.setup.SetupActionableError
import com.pocketfinancer.setup.SetupEmptyReason
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.setup.SetupPauseReason
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.SelectedModelResidency
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.toModelSpec
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingService : Service() {

    companion object {
        private const val TAG = "OnboardingService"
        private const val NOTIFICATION_ID = 10001
        private const val TERMINAL_NOTIFICATION_ID = 10002
        private const val TERMINAL_NOTIFICATION_TIMEOUT_MS = 5_000L
    }

    @Inject
    lateinit var syncManager: OnboardingSyncManager

    @Inject
    lateinit var deviceCapabilities: DeviceCapabilities

    @Inject
    lateinit var slmRuntime: SlmRuntime

    @Inject
    lateinit var modelStorage: SlmModelStorage

    @Inject
    lateinit var selectedModelResidency: SelectedModelResidency

    @Inject
    lateinit var appFlowCoordinator: SlmAppFlowCoordinator

    @Inject
    lateinit var runGenerationStore: OnboardingRunGenerationStore

    @Inject
    lateinit var modelDownloader: ModelDownloader

    @Inject
    lateinit var smsRepository: SmsRepository

    @Inject
    lateinit var smsFilterPipeline: SmsFilterPipeline

    @Inject
    lateinit var pipelineService: PipelineService

    @Inject
    lateinit var transactionRepository: TransactionRepository

    @Inject
    lateinit var setupImportStore: SetupImportStore

    private val historyScanPolicy = AdaptiveHistoryScanPolicy()

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val workflowMutex = Mutex()
    private var workJob: Job? = null
    private var downloadObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "OnboardingService Created")
        SmsNotificationHelper.createNotificationChannel(this)
    }

    private var lastNotificationTitle = "Preparing Onboarding"
    private var lastNotificationText = "Initializing local SLM engine..."
    private var lastNotificationProgress = 0f

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "ACTION_NOTIFICATION_DISMISSED") {
            Log.i(TAG, "Notification dismissed by user. Re-asserting foreground notification.")
            reassertNotification()
            return START_NOT_STICKY
        }

        val slmId = intent?.getStringExtra("EXTRA_SLM_ID")
        val slm = SlmTier.ALL_TIERS.find { it.id == slmId } ?: SlmTier.DEFAULT_ONBOARDING_SLM
        val runPurpose = intent
            ?.getStringExtra(OnboardingSyncManager.EXTRA_RUN_PURPOSE)
            ?.let { value ->
                OnboardingSyncManager.RunPurpose.entries
                    .firstOrNull { it.name == value }
            }
            ?: OnboardingSyncManager.RunPurpose.INITIAL_SETUP
        val coveredHistoryWindowDays = intent
            ?.takeIf {
                it.hasExtra(
                    OnboardingSyncManager.EXTRA_COVERED_HISTORY_WINDOW_DAYS
                )
            }
            ?.getIntExtra(
                OnboardingSyncManager.EXTRA_COVERED_HISTORY_WINDOW_DAYS,
                0
            )
            ?.takeIf { it > 0 }
        val resumeHistoryWindowDays = intent
            ?.takeIf {
                it.hasExtra(
                    OnboardingSyncManager.EXTRA_RESUME_HISTORY_WINDOW_DAYS
                )
            }
            ?.getIntExtra(
                OnboardingSyncManager.EXTRA_RESUME_HISTORY_WINDOW_DAYS,
                0
            )
            ?.takeIf { it > 0 }

        getSystemService(NotificationManager::class.java)
            .cancel(TERMINAL_NOTIFICATION_ID)
        Log.i(TAG, "Starting $runPurpose for SLM: ${slm.name}")

        // Start Foreground immediately to satisfy OS requirements
        val initialNotification = buildInitialNotification(slm, runPurpose)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        if (!runGenerationStore.isCurrent(intent)) {
            Log.i(
                TAG,
                "Rejecting stale or unstamped onboarding start"
            )
            if (stopSelfResult(startId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return START_NOT_STICKY
        }

        // A repeated start first cancels and drains the previous coordinator
        // request. Starting a replacement before the old JNI call returned
        // would allow two onboarding workflows to race over model residency.
        workJob?.cancel()
        val launchedJob = serviceScope.launch {
            workflowMutex.withLock {
                val flowOwner = when (runPurpose) {
                    OnboardingSyncManager.RunPurpose.INITIAL_SETUP ->
                        SlmRuntimeOwner.ONBOARDING
                    OnboardingSyncManager.RunPurpose.MODEL_UPGRADE ->
                        SlmRuntimeOwner.MODEL_UPGRADE
                }
                val flowLease = appFlowCoordinator.tryEnter(flowOwner)
                if (flowLease == null) {
                    Log.i(TAG, "$runPurpose start rejected while app-flow admission is paused")
                    syncManager.updateState {
                        it.copy(
                            isRunning = false,
                            isCancelling = false,
                            isCancellationAllowed = false,
                            isDownloading = false,
                            modelLoadError = if (
                                runPurpose ==
                                    OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
                            ) {
                                "Model upgrade is temporarily paused while other model maintenance completes."
                            } else {
                                "Onboarding start is paused while reset completes."
                            }
                        )
                    }
                    if (
                        runPurpose ==
                        OnboardingSyncManager.RunPurpose.INITIAL_SETUP
                    ) {
                        setupImportStore.update {
                            it.copy(
                                status = SetupImportStatus.PAUSED,
                                pauseReason = SetupPauseReason.INTERRUPTED,
                                actionableError = SetupActionableError(
                                    code = "SETUP_ADMISSION_PAUSED",
                                    message = "Setup is waiting for other local model maintenance to finish.",
                                    actionLabel = "Resume setup"
                                )
                            )
                        }
                    }
                    showTerminalNotification(
                        title = "Model Work Paused",
                        text = "Open Pocket Financer to try again."
                    )
                    stopSelfResult(startId)
                    return@withLock
                }
                try {
                    runOnboardingWorkflow(
                        slm = slm,
                        runPurpose = runPurpose,
                        coveredHistoryWindowDays = coveredHistoryWindowDays,
                        resumeHistoryWindowDays = resumeHistoryWindowDays
                    )
                } catch (e: CancellationException) {
                    Log.i(TAG, "Onboarding workflow cancelled")
                } catch (e: Exception) {
                    Log.e(TAG, "Error in onboarding workflow", e)
                    syncManager.updateState {
                        it.copy(
                            modelLoadError = e.message ?: "Unknown service error",
                            isRunning = false,
                            isCancelling = false,
                            isCancellationAllowed = false,
                            isDownloading = false
                        )
                    }
                    if (
                        runPurpose ==
                        OnboardingSyncManager.RunPurpose.INITIAL_SETUP
                    ) {
                        setupImportStore.update {
                            it.copy(
                                status = SetupImportStatus.FAILED,
                                actionableError = SetupActionableError(
                                    code = "SETUP_SERVICE_FAILED",
                                    message = e.message
                                        ?: "Background setup stopped unexpectedly.",
                                    actionLabel = "Try again"
                                )
                            )
                        }
                    }
                    showTerminalNotification(
                        title = if (
                            runPurpose ==
                                OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
                        ) {
                            "AI Model Upgrade Failed"
                        } else {
                            "Pocket Financer Setup Paused"
                        },
                        text = "Open Pocket Financer to try again."
                    )
                } finally {
                    withContext(NonCancellable) {
                        flowLease.release()
                    }
                    // A cancelled older start must not tear down a newer
                    // replacement workflow that is waiting on the mutex.
                    stopSelfResult(startId)
                }
            }
        }
        workJob = launchedJob
        launchedJob.invokeOnCompletion {
            // Also covers cancellation before the coroutine first runs. A
            // replaced older start cannot finish cancellation for its successor.
            if (workJob === launchedJob) {
                syncManager.completeCancellationIfRequested()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun runOnWorkflowProgress(title: String, text: String, progress: Float) {
        lastNotificationTitle = title
        lastNotificationText = text
        lastNotificationProgress = progress

        withContext(Dispatchers.Main) {
            val builder = NotificationCompat.Builder(this@OnboardingService, SmsNotificationHelper.CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(true)
                .setProgress(100, (progress * 100).toInt(), false)
                .setDeleteIntent(getDeletePendingIntent())
                .setContentIntent(getAppPendingIntent())
            
            val notification = builder.build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        }
    }

    /**
     * Ends foreground progress immediately, then posts a short-lived,
     * dismissible result without a progress bar. Keeping the terminal result
     * on a separate ID prevents service destruction from reviving or retaining
     * the old ongoing foreground notification.
     */
    private suspend fun showTerminalNotification(
        title: String,
        text: String
    ) {
        lastNotificationTitle = title
        lastNotificationText = text
        lastNotificationProgress = 1f

        withContext(Dispatchers.Main) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            val notification = NotificationCompat.Builder(
                this@OnboardingService,
                SmsNotificationHelper.CHANNEL_ID
            )
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(false)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                .setProgress(0, 0, false)
                .setTimeoutAfter(TERMINAL_NOTIFICATION_TIMEOUT_MS)
                .setContentIntent(getAppPendingIntent())
                .build()

            runCatching {
                getSystemService(NotificationManager::class.java)
                    .notify(TERMINAL_NOTIFICATION_ID, notification)
            }.onFailure { error ->
                Log.w(TAG, "Could not post terminal model-work notification", error)
            }
        }
    }

    private fun buildInitialNotification(
        slm: SlmTier,
        runPurpose: OnboardingSyncManager.RunPurpose
    ): Notification {
        val title = when (runPurpose) {
            OnboardingSyncManager.RunPurpose.INITIAL_SETUP ->
                "Preparing Onboarding"
            OnboardingSyncManager.RunPurpose.MODEL_UPGRADE ->
                "Preparing AI Model Upgrade"
        }
        val text = "Preparing ${slm.name}..."
        lastNotificationTitle = title
        lastNotificationText = text
        lastNotificationProgress = 0f
        return NotificationCompat.Builder(this, SmsNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setProgress(100, 0, true)
            .setDeleteIntent(getDeletePendingIntent())
            .setContentIntent(getAppPendingIntent())
            .build()
    }

    private fun getDeletePendingIntent(): android.app.PendingIntent {
        val intent = Intent(this, OnboardingService::class.java).apply {
            action = "ACTION_NOTIFICATION_DISMISSED"
        }
        return android.app.PendingIntent.getService(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getAppPendingIntent(): android.app.PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        } ?: Intent()

        return android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun reassertNotification() {
        val builder = NotificationCompat.Builder(this, SmsNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(lastNotificationTitle)
            .setContentText(lastNotificationText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setProgress(100, (lastNotificationProgress * 100).toInt(), false)
            .setDeleteIntent(getDeletePendingIntent())
            .setContentIntent(getAppPendingIntent())

        val notification = builder.build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private suspend fun runOnboardingWorkflow(
        slm: SlmTier,
        runPurpose: OnboardingSyncManager.RunPurpose,
        coveredHistoryWindowDays: Int?,
        resumeHistoryWindowDays: Int?
    ) {
        val destFile = getModelFile(slm)

        // 1. Use an immutable cached final artifact or download through the
        // staging path. Cached onboarding remains available offline; the
        // native GGUF load below is its authoritative validity check.
        Log.i(TAG, "Preparing model artifact...")
        syncManager.updateState {
            it.copy(
                step = OnboardingStep.DOWNLOAD_SLM,
                isDownloading = true
            )
        }
        if (runPurpose == OnboardingSyncManager.RunPurpose.INITIAL_SETUP) {
            setupImportStore.update {
                it.copy(
                    status = if (it.modelPrepared) {
                        SetupImportStatus.SCANNING
                    } else {
                        SetupImportStatus.DOWNLOADING
                    },
                    pauseReason = null,
                    actionableError = null
                )
            }
        }

        // Observe downloader progress
        downloadObserverJob = serviceScope.launch {
            modelDownloader.state.collect { ds ->
                syncManager.updateState {
                    it.copy(downloadState = ds, isDownloading = ds.isDownloading)
                }

                if (ds.isDownloading) {
                    val progressPercent = (ds.progress * 100).toInt()
                    val speedText = if (ds.speedMbps > 0) " • ${"%.1f".format(ds.speedMbps)} MB/s" else ""
                    val etaText = if (ds.etaSeconds > 0) {
                        val mins = ds.etaSeconds / 60
                        val secs = ds.etaSeconds % 60
                        " • " + (if (mins > 0) "${mins}m ${secs}s" else "${secs}s") + " left"
                    } else ""
                    runOnWorkflowProgress(
                        title = "Downloading Local AI Model",
                        text = "$progressPercent%$speedText$etaText",
                        progress = ds.progress
                    )
                }
            }
        }

        val result = try {
            modelDownloader.prepareForNativeValidation(slm.downloadUrl, destFile)
        } finally {
            downloadObserverJob?.cancel()
            downloadObserverJob = null
        }

        if (result.isFailure) {
            val errorMsg = result.exceptionOrNull()?.message ?: "Download failed"
            Log.e(TAG, "Model download failed: $errorMsg")
            syncManager.modelPreparationFailed(
                errorMessage = errorMsg,
                terminalDownloadState = modelDownloader.state.value
            )
            showTerminalNotification(
                title = if (
                    runPurpose ==
                        OnboardingSyncManager.RunPurpose.MODEL_UPGRADE
                ) {
                    "AI Model Download Paused"
                } else {
                    "Setup Download Paused"
                },
                text = "Open Pocket Financer to retry."
            )
            return
        }

        // Publish one authoritative state transition. The screen switches on
        // `step`, so this also removes the Download action before sync starts.
        syncManager.modelPreparationCompleted(destFile)

        when (runPurpose) {
            OnboardingSyncManager.RunPurpose.INITIAL_SETUP -> {
                // Initial setup continues into the one-time inbox scan.
                Log.i(TAG, "Starting sync phase...")
                runOnboardingSync(
                    slm = slm,
                    coveredHistoryWindowDays = coveredHistoryWindowDays,
                    resumeHistoryWindowDays = resumeHistoryWindowDays
                )
            }
            OnboardingSyncManager.RunPurpose.MODEL_UPGRADE -> {
                // A post-onboarding model upgrade must not unexpectedly re-run
                // the historical SMS import. Native loading and durable
                // selection are the complete consistency boundary.
                Log.i(TAG, "Activating model upgrade...")
                activateModelUpgrade(slm)
            }
        }
    }

    private suspend fun activateModelUpgrade(slm: SlmTier) {
        syncManager.updateState {
            it.copy(
                step = OnboardingStep.SYNCING,
                syncProgress = 0.94f,
                syncMessage = "Waiting for active AI work to finish...",
                modelLoadError = null
            )
        }
        runOnWorkflowProgress(
            title = "Upgrading Local AI Model",
            text = "Waiting for active AI work to finish...",
            progress = 0.94f
        )

        // Downloading may coexist with foreground/background extraction. The
        // persisted model selection changes only after every already-admitted
        // workflow finishes, while this pause blocks new workflows from
        // resolving the old selection. One extraction therefore never runs
        // partly against the old model and partly against the new model.
        val admissionPause = checkNotNull(
            appFlowCoordinator.tryPauseAdmissionAndDrainOthers(
                SlmRuntimeOwner.MODEL_UPGRADE
            )
        ) {
            "Another model maintenance operation is already in progress"
        }

        var provisionalPin: com.pocketfinancer.ProvisionalSelectedModelPin? = null
        try {
            syncManager.updateState {
                it.copy(
                    syncProgress = 0.96f,
                    syncMessage = "Activating ${slm.name}..."
                )
            }
            runOnWorkflowProgress(
                title = "Upgrading Local AI Model",
                text = "Validating and activating ${slm.name}...",
                progress = 0.96f
            )

            val spec = slm.toModelSpec(
                modelStorage,
                deviceCapabilities.assessDevice()
            )
            val handoff = selectedModelResidency.beginProvisionalPin(
                spec = spec,
                persistedFallback = resolvePersistedSelectedModelSpec()
            )
            provisionalPin = handoff
            if (!syncManager.tryBeginModelUpgradeCommit()) {
                throw CancellationException(
                    "Model upgrade cancellation won before durable commit"
                )
            }
            val committed = withContext(NonCancellable) {
                handoff.commit {
                    persistSelectedModel(slm)
                }
            }
            check(committed) {
                "Selected-model ownership changed before the upgrade completed"
            }

            syncManager.updateState {
                it.copy(
                    step = OnboardingStep.COMPLETED,
                    isRunning = false,
                    isCancelling = false,
                    isCancellationAllowed = false,
                    isDownloading = false,
                    isModelLoaded = true,
                    syncProgress = 1f,
                    syncMessage = "${slm.name} is ready",
                    modelLoadError = null
                )
            }
            showTerminalNotification(
                title = "AI Model Upgrade Complete",
                text = "${slm.name} is ready to use."
            )
        } finally {
            withContext(NonCancellable) {
                provisionalPin?.rollbackUnlessCommitted()
                admissionPause.release()
            }
        }
    }

    private suspend fun runOnboardingSync(
        slm: SlmTier,
        coveredHistoryWindowDays: Int?,
        resumeHistoryWindowDays: Int?
    ) {
        val logs = mutableListOf<String>()

        fun addLog(msg: String) {
            logs.add(msg)
            syncManager.updateState { it.copy(syncLogs = logs.toList()) }
        }

        addLog("System: Initializing local sync...")
        syncManager.updateState {
            it.copy(
                syncProgress = 0.05f,
                syncMessage = "Warming up local AI engine..."
            )
        }
        runOnWorkflowProgress("Syncing Transactions", "Initializing AI engine...", 0.05f)
        delay(800)

        addLog("Model: Loading ${slm.name} into memory...")
        syncManager.updateState { it.copy(syncMessage = "Initializing model layers...") }
        runOnWorkflowProgress("Syncing Transactions", "Loading model layers...", 0.08f)
        val spec = slm.toModelSpec(modelStorage, deviceCapabilities.assessDevice())
        var batchLease: SlmLease? = null
        var provisionalPin: com.pocketfinancer.ProvisionalSelectedModelPin? = null
        try {
            val persistedRollback = resolvePersistedSelectedModelSpec()
            val handoff = selectedModelResidency.beginProvisionalPin(
                spec = spec,
                persistedFallback = persistedRollback
            )
            provisionalPin = handoff
            val activeLease = try {
                slmRuntime.acquire(SlmRuntimeOwner.ONBOARDING, spec)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                val errorMsg = error.message ?: "Unknown error"
                addLog("Error: Failed to load model ($errorMsg)")
                syncManager.updateState {
                    it.copy(
                        modelLoadError = "Failed to load model: $errorMsg",
                        isRunning = false,
                        isModelLoaded = false
                    )
                }
                setupImportStore.update {
                    it.copy(
                        status = SetupImportStatus.FAILED,
                        modelPrepared = false,
                        actionableError = SetupActionableError(
                            code = "MODEL_LOAD_FAILED",
                            message = "The downloaded model could not be prepared: $errorMsg",
                            actionLabel = "Try again"
                        )
                    )
                }
                return
            }
            batchLease = activeLease

            suspend fun completeRun(
                notificationTitle: String,
                notificationText: String
            ): Boolean {
                if (!ensureSmsPermissionForTerminalPublication()) {
                    return false
                }
                val stillOwnsSelection = withContext(NonCancellable) {
                    handoff.commit {
                        persistCompletedSelection(slm)
                    }
                }
                check(stillOwnsSelection) {
                    "Selected-model ownership changed before onboarding completed"
                }
                publishOnboardingCompleted()
                showTerminalNotification(
                    title = notificationTitle,
                    text = notificationText
                )
                return true
            }

            addLog("Model: Loaded successfully on device CPU.")
            syncManager.updateState { it.copy(isModelLoaded = true) }
            setupImportStore.update {
                it.copy(
                    modelPrepared = true,
                    status = SetupImportStatus.SCANNING,
                    pauseReason = null,
                    actionableError = null
                )
            }

            var historyWindowDays = initialHistoryWindowDays(
                policy = historyScanPolicy,
                coveredWindowDays = coveredHistoryWindowDays,
                resumeWindowDays = resumeHistoryWindowDays
            )
            // Every adaptive query shares one immutable upper bound. Coverage
            // therefore describes exactly what the provider was asked for,
            // even when discovery widens from 7 to 30 to 90 days.
            val durableBeforeScan = setupImportStore.state.value
            val providerMaxDate = historicalScanProviderMaxDate(
                state = durableBeforeScan,
                resumeWindowDays = resumeHistoryWindowDays,
                nowMillis = System.currentTimeMillis()
            )
            var rawMessages =
                emptyList<com.pocketfinancer.sms.SmsReader.SmsMessage>()
            var transactionalMessages =
                emptyList<com.pocketfinancer.sms.SmsReader.SmsMessage>()
            var alreadySavedCount = 0

            while (true) {
                setupImportStore.update {
                    it.copy(
                        status = SetupImportStatus.SCANNING,
                        activeScanWindowDays = historyWindowDays,
                        activeScanProviderMaxDateMillis = providerMaxDate,
                        emptyReason = null,
                        pauseReason = null,
                        actionableError = null
                    )
                }
                syncManager.updateState {
                    it.copy(
                        syncProgress = 0.15f,
                        syncMessage =
                            "Checking the last $historyWindowDays days..."
                    )
                }
                runOnWorkflowProgress(
                    "Checking SMS History",
                    "Scanning the last $historyWindowDays days...",
                    0.15f
                )
                addLog(
                    "SmsReader: Querying inbox history " +
                        "(last $historyWindowDays days)..."
                )

                rawMessages = try {
                    withContext(Dispatchers.IO) {
                        smsRepository.fetchHistory(
                            daysBack = historyWindowDays,
                            limit = Int.MAX_VALUE,
                            maxDate = providerMaxDate
                        )
                    }
                } catch (securityError: SecurityException) {
                    setupImportStore.reconcilePermission(granted = false)
                    syncManager.updateState {
                        it.copy(
                            isRunning = false,
                            modelLoadError =
                                "SMS access was removed during the scan."
                        )
                    }
                    return
                }
                if (!smsRepository.hasPermissions()) {
                    ensureSmsPermissionForTerminalPublication()
                    return
                }
                val deterministicallyEligible = rawMessages.filter { message ->
                    smsFilterPipeline.isTransactional(
                        message.address,
                        message.body
                    )
                }
                transactionalMessages = withContext(Dispatchers.IO) {
                    val unsaved = mutableListOf<
                        com.pocketfinancer.sms.SmsReader.SmsMessage
                    >()
                    for (message in deterministicallyEligible) {
                        if (
                            !transactionRepository
                                .preserveSourceMetadataIfExists(
                                    sourceIdentity = message.sourceIdentity,
                                    receivedDate = message.date
                                )
                        ) {
                            unsaved += message
                        }
                    }
                    unsaved
                }
                alreadySavedCount =
                    deterministicallyEligible.size - transactionalMessages.size
                val inboxHasAnyMessage = if (
                    rawMessages.isEmpty() &&
                    historyWindowDays >=
                        historyScanPolicy.widestAutomaticWindowDays
                ) {
                    try {
                        withContext(Dispatchers.IO) {
                            smsRepository.hasAnyInboxMessage(providerMaxDate)
                        }
                    } catch (securityError: SecurityException) {
                        setupImportStore.reconcilePermission(granted = false)
                        syncManager.updateState {
                            it.copy(
                                isRunning = false,
                                modelLoadError =
                                    "SMS access was removed during the scan."
                            )
                        }
                        return
                    }
                } else {
                    null
                }
                if (!smsRepository.hasPermissions()) {
                    ensureSmsPermissionForTerminalPublication()
                    return
                }
                val scanCompletedAt = System.currentTimeMillis()
                setupImportStore.update {
                    it.copy(
                        coverageStartMillis = (
                            providerMaxDate -
                                TimeUnit.DAYS.toMillis(
                                    historyWindowDays.toLong()
                                )
                            ).coerceAtLeast(0L),
                        coverageEndMillis = providerMaxDate,
                        coverageWindowDays = historyWindowDays,
                        providerMessageCount = rawMessages.size,
                        eligibleCandidateCount =
                            deterministicallyEligible.size,
                        processedCount = alreadySavedCount,
                        savedCount = alreadySavedCount,
                        rejectedCount = 0,
                        failedCount = 0,
                        lastSuccessfulScanMillis = scanCompletedAt
                    )
                }
                syncManager.updateState {
                    it.copy(
                        syncTotalMessages = rawMessages.size,
                        syncTransactionalCount =
                            transactionalMessages.size
                    )
                }
                addLog(
                    "SmsReader: ${rawMessages.size} messages checked; " +
                        "${transactionalMessages.size} new eligible" +
                        if (alreadySavedCount > 0) {
                            "; $alreadySavedCount already saved."
                        } else {
                            "."
                        }
                )

                when (
                    val decision = historyScanPolicy.decide(
                        windowDays = historyWindowDays,
                        providerMessageCount = rawMessages.size,
                        eligibleCandidateCount =
                            transactionalMessages.size,
                        inboxHasAnyMessage = inboxHasAnyMessage
                    )
                ) {
                    is HistoryScanDecision.Widen -> {
                        addLog(
                            "Discovery: No eligible alerts; widening to " +
                                "${decision.nextWindowDays} days."
                        )
                        historyWindowDays = decision.nextWindowDays
                    }

                    is HistoryScanDecision.Process -> break

                    is HistoryScanDecision.NoEligibleHistory -> {
                        if (!ensureSmsPermissionForTerminalPublication()) {
                            return
                        }
                        val terminalStatus =
                            if (alreadySavedCount > 0) {
                                SetupImportStatus.READY
                            } else {
                                SetupImportStatus.READY_NO_HISTORY
                            }
                        val terminalEmptyReason =
                            if (alreadySavedCount > 0) {
                                SetupEmptyReason.NO_ADDITIONAL_MESSAGES
                            } else {
                                decision.reason
                            }
                        setupImportStore.update {
                            it.copy(
                                status = terminalStatus,
                                activeScanWindowDays = null,
                                activeScanProviderMaxDateMillis = null,
                                emptyReason = terminalEmptyReason,
                                actionableError = null
                            )
                        }
                        syncManager.updateState {
                            it.copy(
                                syncProgress = 1f,
                                syncMessage = if (
                                    terminalStatus ==
                                        SetupImportStatus.READY
                                ) {
                                    "Saved transaction history is ready."
                                } else {
                                    "Ready for a future manual or automatic scan."
                                }
                            )
                        }
                        completeRun(
                            notificationTitle =
                                "Pocket Financer Is Ready",
                            notificationText = if (
                                terminalStatus == SetupImportStatus.READY
                            ) {
                                "Previously saved transaction history is ready."
                            } else {
                                "No eligible history was found in the checked range."
                            }
                        )
                        return
                    }
                }
            }

            addLog(
                "Pipeline: Found ${transactionalMessages.size} " +
                    "eligible alerts to process."
            )
            setupImportStore.update {
                it.copy(
                    status = SetupImportStatus.PROCESSING,
                    processedCount = alreadySavedCount,
                    savedCount = alreadySavedCount,
                    rejectedCount = 0,
                    failedCount = 0,
                    emptyReason = null,
                    actionableError = null
                )
            }

        val messagesToProcess = transactionalMessages
        val totalCount = messagesToProcess.size
        addLog("AI: Beginning local offline parsing for $totalCount transactions...")

        val loopStartTime = System.currentTimeMillis()
        val defaultTimePerTxMs = 10000L
        var parsedCount = 0
        var processedCount = alreadySavedCount
        var rejectedCount = 0
        var failedCount = 0
        var concurrentDuplicateCount = 0
        var spendsTotal = 0.0
        val recentTxList = mutableListOf<ExtractedTxPreview>()

        for ((index, sms) in messagesToProcess.withIndex()) {
            val progressBase = 0.25f
            val progressScale = 0.70f
            val currentProgress = progressBase + (index.toFloat() / totalCount) * progressScale

            // Set initial/dynamic ETA
            val currentRemaining = totalCount - index
            val elapsedSoFar = System.currentTimeMillis() - loopStartTime
            val estimatedAvgTime = if (index > 0) (elapsedSoFar / index) else defaultTimePerTxMs
            val currentEtaSec = ((estimatedAvgTime * currentRemaining) / 1000f).toInt().coerceAtLeast(1)

            syncManager.updateState {
                it.copy(
                    syncProgress = currentProgress,
                    syncMessage =
                        "Analyzing SMS ${index + 1} of $totalCount...",
                    syncEtaSeconds = currentEtaSec
                )
            }
            runOnWorkflowProgress(
                "Syncing Transactions",
                "Analyzing SMS ${index + 1} of $totalCount (${currentEtaSec}s left)",
                currentProgress
            )

            addLog(
                "AI: Analyzing transaction ${index + 1}/$totalCount..."
            )

            val txStartTime = System.currentTimeMillis()
            val result = withContext(Dispatchers.IO) {
                try {
                    pipelineService.processSingle(sms, activeLease)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Sync parse error", e)
                    PipelineService.ProcessingResult.Failure(
                        message = e.message ?: "Unknown pipeline error",
                        retryable = true
                    )
                }
            }
            val durationMs = System.currentTimeMillis() - txStartTime

            if (result is PipelineService.ProcessingResult.Stopped) {
                throw CancellationException("Onboarding inference stopped")
            }
            processedCount++
            if (result is PipelineService.ProcessingResult.Failure) {
                failedCount++
                setupImportStore.update {
                    it.copy(
                        processedCount = processedCount,
                        savedCount =
                            alreadySavedCount +
                                parsedCount +
                                concurrentDuplicateCount,
                        rejectedCount = rejectedCount,
                        failedCount = failedCount
                    )
                }
                addLog("➔ Failed: ${result.message}")
                continue
            }
            if (result is PipelineService.ProcessingResult.Saved) {
                val transaction = result.transaction
                if (result.newlyInserted) {
                    parsedCount++
                    if (transaction.type == TransactionType.DEBIT) {
                        spendsTotal += transaction.amount
                    }
                    recentTxList.add(
                        0,
                        ExtractedTxPreview(
                            amount = transaction.amount,
                            merchant = transaction.counterparty
                                ?: "Unknown Merchant",
                            type = transaction.type.name.lowercase()
                        )
                    )

                    syncManager.updateState {
                        it.copy(
                            syncParsedCount = parsedCount,
                            syncSpendsTotal = spendsTotal,
                            syncRecentTransactions = recentTxList.take(3)
                        )
                    }

                    addLog("➔ Extracted: ₹${transaction.amount} at ${transaction.counterparty ?: "Unknown Merchant"} [${"%.1f".format(durationMs / 1000f)}s]")
                    addLog("➔ Saved to encrypted local database.")
                } else {
                    concurrentDuplicateCount++
                    addLog(
                        "➔ Already saved by another processing path; " +
                            "not counted as a new transaction."
                    )
                }
            } else {
                rejectedCount++
                addLog("➔ Skipped (non-transactional content detected) [${"%.1f".format(durationMs / 1000f)}s]")
            }
            setupImportStore.update {
                it.copy(
                    processedCount = processedCount,
                    savedCount =
                        alreadySavedCount +
                            parsedCount +
                            concurrentDuplicateCount,
                    rejectedCount = rejectedCount,
                    failedCount = failedCount
                )
            }
        }

        syncManager.updateState {
            it.copy(
                syncProgress = 0.98f,
                syncMessage = "Finalizing local results...",
                syncEtaSeconds = 0
            )
        }
        runOnWorkflowProgress(
            "Finishing SMS Import",
            "Saving verified results...",
            0.98f
        )

        if (!ensureSmsPermissionForTerminalPublication()) {
            return
        }
        val terminalSavedCount =
            alreadySavedCount + parsedCount + concurrentDuplicateCount
        val terminalStatus = setupTerminalStatus(
            savedCount = terminalSavedCount,
            failedCount = failedCount,
            concurrentDuplicateCount = 0
        )
        setupImportStore.update {
            it.copy(
                status = terminalStatus,
                processedCount = processedCount,
                savedCount = terminalSavedCount,
                rejectedCount = rejectedCount,
                failedCount = failedCount,
                activeScanWindowDays = if (
                    terminalStatus == SetupImportStatus.FAILED
                ) {
                    it.activeScanWindowDays
                } else {
                    null
                },
                activeScanProviderMaxDateMillis = if (
                    terminalStatus == SetupImportStatus.FAILED
                ) {
                    it.activeScanProviderMaxDateMillis
                } else {
                    null
                },
                emptyReason = if (
                    terminalStatus == SetupImportStatus.READY_NO_HISTORY
                ) {
                    SetupEmptyReason.CANDIDATES_REJECTED
                } else {
                    null
                },
                actionableError = if (failedCount > 0) {
                    SetupActionableError(
                        code = "SMS_PROCESSING_FAILED",
                        message =
                            "$failedCount eligible alert" +
                                if (failedCount == 1) {
                                    " could not be processed."
                                } else {
                                    "s could not be processed."
                                },
                        actionLabel = "Retry import"
                    )
                } else {
                    null
                }
            )
        }
        addLog(
            "System: Import finished: $parsedCount saved, " +
                "$rejectedCount rejected, $failedCount failed" +
                if (concurrentDuplicateCount > 0) {
                    ", $concurrentDuplicateCount already saved."
                } else {
                    "."
                }
        )
        syncManager.updateState {
            it.copy(
                syncProgress = 1.0f,
                syncMessage = when (terminalStatus) {
                    SetupImportStatus.FAILED ->
                        "Import finished with items to retry."
                    SetupImportStatus.READY_NO_HISTORY ->
                        "Ready for the next eligible alert."
                    else -> "SMS import completed."
                }
            )
        }
        completeRun(
            notificationTitle = when (terminalStatus) {
                SetupImportStatus.FAILED ->
                    "SMS Import Needs Attention"
                SetupImportStatus.READY_NO_HISTORY ->
                    "Pocket Financer Is Ready"
                else -> "SMS Import Complete"
            },
            notificationText = when (terminalStatus) {
                SetupImportStatus.FAILED ->
                    "$parsedCount saved; $failedCount need another attempt."
                SetupImportStatus.READY_NO_HISTORY ->
                    "No transaction was saved from the checked candidates."
                else -> "$parsedCount transaction${if (parsedCount == 1) "" else "s"} saved locally."
            }
        )
        } finally {
            withContext(NonCancellable) {
                batchLease?.release()
                provisionalPin?.rollbackUnlessCommitted()
            }
        }
    }

    /**
     * Permission can be revoked while provider rows are filtered or while the
     * model is parsing an already-read candidate. Re-check immediately before
     * any READY publication so restart/recovery shows the permission gate
     * instead of a stale terminal success.
     */
    private fun ensureSmsPermissionForTerminalPublication(): Boolean {
        if (smsRepository.hasPermissions()) return true
        setupImportStore.reconcilePermission(granted = false)
        syncManager.updateState {
            it.copy(
                isRunning = false,
                isDownloading = false,
                isCancellationAllowed = false,
                syncMessage = "SMS access must be restored to finish setup.",
                modelLoadError =
                    "SMS access was removed before setup could finish."
            )
        }
        return false
    }

    private fun persistCompletedSelection(slm: SlmTier): Boolean {
        val prefs = getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
        return prefs.edit()
            .putBoolean("onboarding_completed", true)
            .putString("selected_slm_id", slm.id)
            .commit()
    }

    private fun persistSelectedModel(slm: SlmTier): Boolean {
        val prefs = getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
        return prefs.edit()
            .putString("selected_slm_id", slm.id)
            .commit()
    }

    private fun publishOnboardingCompleted() {
        syncManager.updateState {
            scrubCompletedOnboardingState(it)
        }
    }

    private fun resolvePersistedSelectedModelSpec(): SlmModelSpec? {
        val selectedId = getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
            .getString("selected_slm_id", null)
            ?: return null
        val tier = SlmTier.ALL_TIERS.find { it.id == selectedId } ?: return null
        val file = modelStorage.modelFile(tier.modelFile)
        if (!isPublishedModelArtifact(file)) return null
        return tier.toModelSpec(modelStorage, deviceCapabilities.assessDevice())
    }

    private fun getModelFile(slm: SlmTier): File {
        return modelStorage.modelFile(slm.modelFile)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "OnboardingService Destroyed")
        workJob?.let(modelDownloader::cancel)
        downloadObserverJob?.cancel()
        workJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        syncManager.updateState {
            if (it.isCancelling) {
                // The workflow's non-cancellable finally block owns the
                // terminal Cancelled transition after rollback/gate release.
                it.copy(isDownloading = false)
            } else {
                it.copy(
                    isRunning = false,
                    isCancellationAllowed = false,
                    isDownloading = false
                )
            }
        }
        if (setupImportStore.state.value.isActive) {
            setupImportStore.update {
                it.copy(
                    status = SetupImportStatus.PAUSED,
                    pauseReason = SetupPauseReason.INTERRUPTED,
                    actionableError = SetupActionableError(
                        code = SetupImportStore.ERROR_INTERRUPTED,
                        message = "Setup stopped before it finished. Completed work is still saved.",
                        actionLabel = "Resume setup"
                    )
                )
            }
        }
        super.onDestroy()
    }
}

internal fun initialHistoryWindowDays(
    policy: AdaptiveHistoryScanPolicy,
    coveredWindowDays: Int?,
    resumeWindowDays: Int?
): Int = resumeWindowDays ?: policy.firstWindowAfter(coveredWindowDays)

internal fun historicalScanProviderMaxDate(
    state: SetupImportState,
    resumeWindowDays: Int?,
    nowMillis: Long
): Long =
    state.activeScanProviderMaxDateMillis
        ?.takeIf {
            resumeWindowDays != null &&
                state.activeScanWindowDays == resumeWindowDays
        }
        ?: state.coverageEndMillis
            ?.takeIf {
                resumeWindowDays != null &&
                    state.coverageWindowDays == resumeWindowDays &&
                    state.lastSuccessfulScanMillis != null
            }
        ?: nowMillis

internal fun scrubCompletedOnboardingState(
    state: OnboardingSyncManager.OnboardingSyncState
): OnboardingSyncManager.OnboardingSyncState = state.copy(
    step = OnboardingStep.COMPLETED,
    isRunning = false,
    isModelLoaded = true,
    syncMessage = "Setup finished",
    syncLogs = emptyList()
)

internal fun setupTerminalStatus(
    savedCount: Int,
    failedCount: Int,
    concurrentDuplicateCount: Int
): SetupImportStatus = when {
    failedCount > 0 -> SetupImportStatus.FAILED
    savedCount > 0 || concurrentDuplicateCount > 0 ->
        SetupImportStatus.READY
    else -> SetupImportStatus.READY_NO_HISTORY
}
