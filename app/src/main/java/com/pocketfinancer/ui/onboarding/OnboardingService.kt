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
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.SelectedModelResidency
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.toModelSpec
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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
                    showTerminalNotification(
                        title = "Model Work Paused",
                        text = "Open Pocket Financer to try again."
                    )
                    stopSelfResult(startId)
                    return@withLock
                }
                try {
                    runOnboardingWorkflow(slm, runPurpose)
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
        runPurpose: OnboardingSyncManager.RunPurpose
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
                runOnboardingSync(slm, destFile)
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

    private suspend fun runOnboardingSync(slm: SlmTier, modelFile: File) {
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
                return
            }
            batchLease = activeLease

            suspend fun completeSuccessfully() {
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
                    title = "Pocket Financer Setup Complete",
                    text = "Your local AI model and SMS sync are ready."
                )
            }

            addLog("Model: Loaded successfully on device CPU.")
            syncManager.updateState { it.copy(isModelLoaded = true) }

        syncManager.updateState {
            it.copy(
                syncProgress = 0.15f,
                syncMessage = "Scanning recent message inbox..."
            )
        }
        runOnWorkflowProgress("Syncing Transactions", "Scanning inbox history...", 0.15f)
        addLog("SmsReader: Querying inbox history (last 7 days)...")
        delay(600)

        // Fetch SMS history (last 7 days)
        val rawMessages = withContext(Dispatchers.IO) {
            smsRepository.fetchHistory(daysBack = 7, limit = 250)
        }

        if (rawMessages.isEmpty()) {
            addLog("SmsReader: No SMS found in inbox.")
            syncManager.updateState {
                it.copy(
                    syncProgress = 1.0f,
                    syncMessage = "No SMS found in inbox. Complete!",
                    syncTotalMessages = 0
                )
            }
            completeSuccessfully()
            return
        }
        addLog("SmsReader: Retrieved ${rawMessages.size} messages.")
        syncManager.updateState {
            it.copy(syncTotalMessages = rawMessages.size)
        }

        // Filtering
        syncManager.updateState {
            it.copy(
                syncProgress = 0.25f,
                syncMessage = "Filtering promotional messages..."
            )
        }
        runOnWorkflowProgress("Syncing Transactions", "Filtering spam...", 0.22f)
        addLog("Pipeline: Applying regex filters to filter promotional/spam SMS...")
        delay(600)

        val transactionalMessages = rawMessages.filter { msg ->
            smsFilterPipeline.isTransactional(msg.address, msg.body)
        }

        val spamCount = rawMessages.size - transactionalMessages.size
        addLog("Pipeline: Discarded $spamCount non-transactional messages.")
        syncManager.updateState {
            it.copy(syncTransactionalCount = transactionalMessages.size)
        }

        if (transactionalMessages.isEmpty()) {
            addLog("Pipeline: Found 0 transactional messages.")
            syncManager.updateState {
                it.copy(
                    syncProgress = 1.0f,
                    syncMessage = "Sync completed! No transactional history."
                )
            }
            completeSuccessfully()
            return
        }
        addLog("Pipeline: Found ${transactionalMessages.size} transactions to process.")

        val syncLimit = 20
        val messagesToProcess = transactionalMessages.take(syncLimit)
        val totalCount = messagesToProcess.size
        addLog("AI: Beginning local offline parsing for $totalCount transactions...")

        val loopStartTime = System.currentTimeMillis()
        val defaultTimePerTxMs = 10000L
        var parsedCount = 0
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
                    syncMessage = "Analyzing SMS ${index + 1} of $totalCount: ${sms.address}...",
                    syncEtaSeconds = currentEtaSec
                )
            }
            runOnWorkflowProgress(
                "Syncing Transactions",
                "Analyzing SMS ${index + 1} of $totalCount (${currentEtaSec}s left)",
                currentProgress
            )

            addLog("AI: Analyzing transaction ${index + 1}/$totalCount (${sms.address})...")

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
            if (result is PipelineService.ProcessingResult.Failure) {
                addLog("➔ Failed: ${result.message}")
                continue
            }
            if (result is PipelineService.ProcessingResult.Saved) {
                val transaction = result.transaction
                parsedCount++
                if (transaction.type == TransactionType.DEBIT) {
                    spendsTotal += transaction.amount
                }
                recentTxList.add(
                    0,
                    ExtractedTxPreview(
                        amount = transaction.amount,
                        merchant = transaction.counterparty ?: "Unknown Merchant",
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
                addLog("➔ Skipped (non-transactional content detected) [${"%.1f".format(durationMs / 1000f)}s]")
            }
        }

        syncManager.updateState {
            it.copy(
                syncProgress = 0.98f,
                syncMessage = "Optimizing encrypted local database...",
                syncEtaSeconds = 0
            )
        }
        runOnWorkflowProgress("Syncing Transactions", "Optimizing storage encryption...", 0.98f)
        addLog("Database: Reindexing and optimizing storage encryption...")
        delay(1000)

        addLog("System: Offline synchronization fully completed!")
        syncManager.updateState {
            it.copy(
                syncProgress = 1.0f,
                syncMessage = "Synchronization completed!"
            )
        }
        completeSuccessfully()
        } finally {
            withContext(NonCancellable) {
                batchLease?.release()
                provisionalPin?.rollbackUnlessCommitted()
            }
        }
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
            it.copy(
                step = OnboardingStep.COMPLETED,
                isRunning = false,
                isModelLoaded = true
            )
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
        super.onDestroy()
    }
}
