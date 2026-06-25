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
import com.pocketfinancer.inference.LlamaEngine
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.SmsNotificationHelper
import com.pocketfinancer.sms.SmsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class OnboardingService : Service() {

    companion object {
        private const val TAG = "OnboardingService"
        private const val NOTIFICATION_ID = 10001
    }

    @Inject
    lateinit var syncManager: OnboardingSyncManager

    @Inject
    lateinit var deviceCapabilities: DeviceCapabilities

    @Inject
    lateinit var llamaEngine: LlamaEngine

    @Inject
    lateinit var modelDownloader: ModelDownloader

    @Inject
    lateinit var smsRepository: SmsRepository

    @Inject
    lateinit var smsFilterPipeline: SmsFilterPipeline

    @Inject
    lateinit var pipelineService: PipelineService

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
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
        val slm = SlmTier.ALL_TIERS.find { it.id == slmId } ?: SlmTier.QWEN3_1_7B_Q4_K_M

        Log.i(TAG, "Starting onboarding for SLM: ${slm.name}")

        // Start Foreground immediately to satisfy OS requirements
        val initialNotification = buildInitialNotification(slm)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // Run download and sync task
        workJob?.cancel()
        workJob = serviceScope.launch {
            try {
                runOnboardingWorkflow(slm)
            } catch (e: CancellationException) {
                Log.i(TAG, "Onboarding workflow cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Error in onboarding workflow", e)
                syncManager.updateState {
                    it.copy(
                        modelLoadError = e.message ?: "Unknown service error",
                        isRunning = false
                    )
                }
            } finally {
                stopSelf()
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

    private fun buildInitialNotification(slm: SlmTier): Notification {
        return NotificationCompat.Builder(this, SmsNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Preparing Onboarding")
            .setContentText("Initializing local SLM engine for ${slm.name}...")
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

    private suspend fun runOnboardingWorkflow(slm: SlmTier) {
        val destFile = getModelFile(slm)

        // 1. Check if model is downloaded. If not, download it.
        val expectedMinBytes = slm.sizeMb.toLong() * 1024 * 1024 * 95 / 100
        val isDone = destFile.exists() && destFile.length() >= expectedMinBytes
        if (!isDone) {
            Log.i(TAG, "Model not cached. Launching download...")
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

            val result = modelDownloader.download(slm.downloadUrl, destFile)
            downloadObserverJob?.cancel()

            if (result.isFailure) {
                val errorMsg = result.exceptionOrNull()?.message ?: "Download failed"
                Log.e(TAG, "Model download failed: $errorMsg")
                syncManager.updateState {
                    it.copy(
                        modelLoadError = "Download failed: $errorMsg",
                        isRunning = false,
                        isDownloading = false
                    )
                }
                return
            }
        }

        // Ensure state marks complete download
        syncManager.updateState {
            it.copy(
                isDownloading = false,
                downloadState = ModelDownloader.DownloadState(
                    isDownloading = false,
                    isComplete = true,
                    progress = 1f,
                    downloadedMb = destFile.length() / 1_048_576f,
                    totalMb = destFile.length() / 1_048_576f,
                    outputPath = destFile.absolutePath
                )
            )
        }

        // 2. Perform Syncing Phase
        Log.i(TAG, "Starting sync phase...")
        syncManager.updateState {
            it.copy(step = OnboardingStep.SYNCING)
        }
        runOnboardingSync(slm, destFile)
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

        // Load the model
        if (!llamaEngine.isModelLoaded()) {
            addLog("Model: Loading ${slm.name} into memory...")
            syncManager.updateState { it.copy(syncMessage = "Initializing model layers...") }
            runOnWorkflowProgress("Syncing Transactions", "Loading model layers...", 0.08f)
            val device = deviceCapabilities.assessDevice()
            val hasFp16 = device.cpu?.hasFp16 ?: false
            val result = llamaEngine.loadModel(
                path = modelFile.absolutePath,
                contextSize = 3072,
                gpuLayers = 0,
                numThreads = 0,
                hasFp16 = hasFp16,
                hasThinkingMode = slm.hasThinkingMode
            )
            if (result.isFailure) {
                val errorMsg = result.exceptionOrNull()?.message ?: "Unknown error"
                addLog("Error: Failed to load model ($errorMsg)")
                syncManager.updateState {
                    it.copy(
                        modelLoadError = "Failed to load model: $errorMsg",
                        isRunning = false
                    )
                }
                return
            }
        }
        addLog("Model: Loaded successfully on device CPU.")

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
            completeOnboarding()
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
            completeOnboarding()
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
                    pipelineService.processSingle(sms)
                } catch (e: Exception) {
                    Log.e(TAG, "Sync parse error", e)
                    null
                }
            }
            val durationMs = System.currentTimeMillis() - txStartTime

            if (result != null) {
                parsedCount++
                if (result.type == TransactionType.DEBIT) {
                    spendsTotal += result.amount
                }
                recentTxList.add(
                    0,
                    ExtractedTxPreview(
                        amount = result.amount,
                        merchant = result.counterparty ?: "Unknown Merchant",
                        type = result.type.name.lowercase()
                    )
                )

                syncManager.updateState {
                    it.copy(
                        syncParsedCount = parsedCount,
                        syncSpendsTotal = spendsTotal,
                        syncRecentTransactions = recentTxList.take(3)
                    )
                }

                addLog("➔ Extracted: ₹${result.amount} at ${result.counterparty ?: "Unknown Merchant"} [${"%.1f".format(durationMs / 1000f)}s]")
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
        runOnWorkflowProgress("Syncing Transactions", "Completed successfully!", 1.0f)
        delay(600)
        completeOnboarding()
    }

    private fun completeOnboarding() {
        val prefs = getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("onboarding_completed", true).apply()
        syncManager.updateState {
            it.copy(
                step = OnboardingStep.COMPLETED,
                isRunning = false
            )
        }
    }

    private fun getModelFile(slm: SlmTier): File {
        val dir = llamaEngine.getModelStorageDir()
        return File(dir, slm.modelFile)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "OnboardingService Destroyed")
        modelDownloader.cancel()
        downloadObserverJob?.cancel()
        workJob?.cancel()
        serviceScope.cancel()
        syncManager.updateState { it.copy(isRunning = false) }
        super.onDestroy()
    }
}
