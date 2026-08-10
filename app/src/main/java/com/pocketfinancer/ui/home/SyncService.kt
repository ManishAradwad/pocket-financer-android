package com.pocketfinancer.ui.home

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.SlmAppFlowLease
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.pipeline.SmsNotificationHelper
import com.pocketfinancer.ui.onboarding.OnboardingRunGenerationStore
import com.pocketfinancer.ui.smsprocessing.SmsProcessingTarget
import com.pocketfinancer.ui.smsprocessing.ownsManualProcessingTarget
import dagger.hilt.android.AndroidEntryPoint
import java.util.UUID
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class SyncService : Service() {

    @Inject
    lateinit var syncManager: HomeSyncManager

    @Inject
    lateinit var runGenerationStore: OnboardingRunGenerationStore

    @Inject
    lateinit var appFlowCoordinator: SlmAppFlowCoordinator

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val jobStateLock = Any()
    private var job: Job? = null
    private var activeJobRetireThroughStartId: Int = 0
    @Volatile
    private var userStopRequestedRunId: String? = null

    companion object {
        private const val TAG = "SyncService"
        private const val NOTIFICATION_ID = 20002
        private const val APP_SETTINGS = ".app_settings"
        private const val ONBOARDING_COMPLETED = "onboarding_completed"
        internal const val ACTION_START =
            "com.pocketfinancer.action.START_MANUAL_SMS_SYNC"
        internal const val ACTION_STOP =
            "com.pocketfinancer.action.STOP_MANUAL_SMS_SYNC"
        internal const val EXTRA_RUN_ID =
            "com.pocketfinancer.extra.MANUAL_SMS_SYNC_RUN_ID"
        internal const val EXTRA_EXPECTED_CANDIDATE_KEY =
            "com.pocketfinancer.extra.MANUAL_SMS_SYNC_CANDIDATE_KEY"
        internal const val EXTRA_REQUIRE_CANDIDATE_MATCH =
            "com.pocketfinancer.extra.MANUAL_SMS_SYNC_EXACT_TARGET"

        fun start(
            context: Context,
            runId: String = UUID.randomUUID().toString()
        ): String {
            require(runId.isNotBlank()) { "Manual sync run id cannot be blank" }
            // Capture before Android accepts the start. A reset racing after
            // this read advances the generation atomically with relocking the
            // shell, so delayed delivery cannot repopulate erased data.
            val generation = context
                .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
                .getLong(
                    OnboardingRunGenerationStore.PREFERENCE_KEY,
                    OnboardingRunGenerationStore.INITIAL_GENERATION
                )
            val intent = Intent(context, SyncService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RUN_ID, runId)
                putExtra(
                    OnboardingRunGenerationStore.EXTRA_RUN_GENERATION,
                    generation
                )
            }
            context.startForegroundService(intent)
            return runId
        }

        fun requestStop(context: Context, runId: String): Boolean {
            if (runId.isBlank()) return false
            return context.startService(stopIntent(context, runId)) != null
        }

        fun requestStop(
            context: Context,
            target: SmsProcessingTarget.ManualRecent
        ): Boolean =
            context.startService(stopIntent(context, target)) != null

        internal fun stopIntent(context: Context, runId: String): Intent =
            Intent(context, SyncService::class.java).apply {
                action = ACTION_STOP
                data = Uri.parse(
                    "${context.packageName}://manual-sync/stop/" +
                        Uri.encode(runId)
                )
                putExtra(EXTRA_RUN_ID, runId)
            }

        internal fun stopIntent(
            context: Context,
            target: SmsProcessingTarget.ManualRecent
        ): Intent = stopIntent(context, target.runId).apply {
            putExtra(EXTRA_REQUIRE_CANDIDATE_MATCH, true)
            target.candidateKey?.let {
                putExtra(EXTRA_EXPECTED_CANDIDATE_KEY, it)
            }
        }

        internal fun requestedRunId(intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_RUN_ID)
                ?.takeIf { it.isNotBlank() }

        internal fun stoppedNotificationCopy(): ManualSyncStoppedCopy =
            ManualSyncStoppedCopy(
                title = "SMS Processing Stopped",
                text = "Completed saves remain available."
            )

        fun stop(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            context.stopService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "SyncService Created")
        SmsNotificationHelper.createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "SyncService OnStartCommand")

        // Stop commands must be matched before the active-job shortcut. An old
        // notification therefore cannot cancel whichever run happens to be
        // active when its PendingIntent is eventually delivered.
        if (intent?.action == ACTION_STOP) {
            val requestedRunId = requestedRunId(intent)
            val requiresCandidateMatch = intent.getBooleanExtra(
                EXTRA_REQUIRE_CANDIDATE_MATCH,
                false
            )
            val matchesActiveRun = if (requiresCandidateMatch) {
                syncManager.requestServiceStop(
                    runId = requestedRunId,
                    expectedCandidateKey = intent.getStringExtra(
                        EXTRA_EXPECTED_CANDIDATE_KEY
                    )
                )
            } else {
                syncManager.requestServiceStop(requestedRunId)
            }
            manualSyncStopRejectionMessage(
                requiresCandidateMatch = requiresCandidateMatch,
                stopAccepted = matchesActiveRun
            )?.let { message ->
                showManualSyncStopRejectionFeedback(this, message)
            }
            if (matchesActiveRun) {
                userStopRequestedRunId = requestedRunId
                val activeJob = jobSnapshotRecordingStart(startId)
                if (
                    shouldKeepManualSyncDraining(
                        jobIsPresent = activeJob != null
                    )
                ) {
                    // A first stop updates immediately. Repeated delivery while
                    // a cancelled job drains must not race terminal publication
                    // by reposting an ongoing notification after settlement.
                    if (activeJob?.isActive == true) {
                        updateNotification(
                            title = "Stopping SMS Processing",
                            text =
                                "Stopping safely; finishing any save already in progress…",
                            progress = 0f,
                            ongoing = true,
                            runId = requestedRunId,
                            showStopAction = false
                        )
                    }
                    activeJob?.cancel(
                        CancellationException(
                            "User stopped manual SMS run $requestedRunId"
                        )
                    )
                } else {
                    syncManager.settleServiceCancellation(requestedRunId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    showStoppedNotification()
                    stopSelfResult(startId)
                }
            } else {
                val activeJob = jobSnapshotRecordingStart(startId)
                if (
                    !shouldKeepManualSyncDraining(
                        jobIsPresent = activeJob != null
                    )
                ) {
                    stopSelfResult(startId)
                }
            }
            return START_NOT_STICKY
        }

        val drainingJob = jobSnapshotRecordingStart(startId)
        if (
            shouldKeepManualSyncDraining(
                jobIsPresent = drainingJob != null
            )
        ) {
            if (intent?.action == ACTION_START) {
                requestedRunId(intent)?.let { runId ->
                    syncManager.acknowledgeServiceStart(
                        runId = runId,
                        accepted = false
                    )
                }
            }
            Log.i(TAG, "SyncService is already active; rejecting duplicate start")
            return START_NOT_STICKY
        }

        val runId = requestedRunId(intent)
        if (intent?.action != ACTION_START || runId == null) {
            Log.i(TAG, "Rejecting manual sync start without a run identity")
            stopSelfResult(startId)
            return START_NOT_STICKY
        }

        val initialNotification = buildNotification(
            title = "Syncing Transactions",
            text = "Initializing recent-message scan…",
            progress = 0f,
            ongoing = true,
            runId = runId,
            showStopAction = true
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        val shellUnlocked = getSharedPreferences(
            APP_SETTINGS,
            Context.MODE_PRIVATE
        ).getBoolean(ONBOARDING_COMPLETED, false)
        if (
            !manualSyncStartAllowed(
                shellUnlocked = shellUnlocked,
                generationIsCurrent = runGenerationStore.isCurrent(intent)
            )
        ) {
            Log.i(TAG, "Rejecting stale, unstamped, or pre-shell sync start")
            syncManager.acknowledgeServiceStart(
                runId = runId,
                accepted = false
            )
            if (stopSelfResult(startId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return START_NOT_STICKY
        }

        if (!syncManager.beginServiceRun(runId)) {
            Log.i(TAG, "Rejecting manual sync start while another run owns state")
            syncManager.acknowledgeServiceStart(
                runId = runId,
                accepted = false
            )
            if (stopSelfResult(startId)) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
            return START_NOT_STICKY
        }
        syncManager.acknowledgeServiceStart(
            runId = runId,
            accepted = true
        )
        userStopRequestedRunId = null

        var retireThroughStartId = startId
        lateinit var launchedJob: Job
        launchedJob = serviceScope.launch(start = CoroutineStart.LAZY) {
            var progressObserver: Job? = null
            var serviceFlowLease: SlmAppFlowLease? = null
            suspend fun publishCompletedRun(
                publishNotification: (HomeSyncState) -> Unit
            ): Boolean = syncManager.withCompletedServiceRunHandoff(
                runId = runId
            ) { terminalState ->
                progressObserver?.cancelAndJoin()
                progressObserver = null
                publishNotification(terminalState)
                synchronized(jobStateLock) {
                    check(job === launchedJob) {
                        "Manual terminal handoff lost service job ownership"
                    }
                    check(syncManager.finishServiceRun(runId)) {
                        "Manual terminal handoff lost manager ownership"
                    }
                    retireThroughStartId = maxOf(
                        retireThroughStartId,
                        activeJobRetireThroughStartId
                    )
                    job = null
                    activeJobRetireThroughStartId = 0
                }
            }
            try {
                // The service owns an outer admission lease through terminal
                // notification publication. Reset can therefore cancel and
                // join this whole job before clearing notifications/data.
                val admittedFlow = appFlowCoordinator.enterWhenAvailable(
                    SlmRuntimeOwner.HOME_SYNC
                )
                serviceFlowLease = admittedFlow
                val stillUnlocked = getSharedPreferences(
                    APP_SETTINGS,
                    Context.MODE_PRIVATE
                ).getBoolean(ONBOARDING_COMPLETED, false)
                if (
                    !manualSyncStartAllowed(
                        shellUnlocked = stillUnlocked,
                        generationIsCurrent =
                            runGenerationStore.isCurrent(intent)
                    )
                ) {
                    Log.i(
                        TAG,
                        "Rejecting sync start made stale while waiting for admission"
                    )
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    return@launch
                }
                // Listen to HomeSyncManager progress to dynamically update the notification text and bar
                progressObserver = launch {
                    syncManager.syncState.collect { state ->
                        if (state.activeRunId != runId) return@collect
                        when (state.status) {
                            HomeSyncState.Status.SCANNING ->
                                updateNotification(
                                    title = "Syncing Transactions",
                                    text = "Scanning recent messages…",
                                    progress = 0f,
                                    ongoing = true,
                                    runId = runId,
                                    showStopAction = true
                                )
                            HomeSyncState.Status.SYNCING -> {
                                val total = state.queue.size
                                val current = state.currentIndex ?: 0
                                val activeSms = state.currentIndex
                                    ?.takeIf { it in state.queue.indices }
                                    ?.let(state.queue::get)
                                val progress = if (total > 0) {
                                    current.toFloat() / total
                                } else {
                                    0f
                                }
                                val text = if (activeSms != null) {
                                    "Analyzing SMS ${current + 1} of $total: " +
                                        "${activeSms.sender}…"
                                } else {
                                    "Processing SMS ${current + 1} of $total…"
                                }
                                updateNotification(
                                    title = "Syncing Transactions",
                                    text = text,
                                    progress = progress,
                                    ongoing = true,
                                    runId = runId,
                                    showStopAction = true
                                )
                            }
                            HomeSyncState.Status.CANCELLING ->
                                updateNotification(
                                    title = "Stopping SMS Processing",
                                    text =
                                        "Stopping safely; finishing any save already in progress…",
                                    progress = 0f,
                                    ongoing = true,
                                    runId = runId,
                                    showStopAction = false
                                )
                            HomeSyncState.Status.IDLE,
                            HomeSyncState.Status.DONE -> Unit
                        }
                    }
                }

                // Check again for unsynced messages and run execution
                syncManager.checkForUnsyncedSms(admittedFlow, runId)
                if (syncManager.isServiceStopRequested(runId)) {
                    throw CancellationException(
                        "Manual SMS processing was stopped after scanning"
                    )
                }
                var scannedState = syncManager.syncState.value
                when (scannedState.recentScanOutcome) {
                    HomeSyncState.RecentScanOutcome.FAILED ->
                        error(
                            scannedState.scanError
                                ?: "The recent SMS scan failed."
                        )
                    HomeSyncState.RecentScanOutcome.PERMISSION_NEEDED ->
                        error("SMS access is required to scan recent alerts.")
                    HomeSyncState.RecentScanOutcome.NOT_RUN ->
                        error(
                            scannedState.scanError
                                ?: "The recent SMS scan did not start."
                        )
                    HomeSyncState.RecentScanOutcome.SUCCESS -> Unit
                }
                if (scannedState.queue.none { it.status == "pending" }) {
                    if (syncManager.tryCompleteNoWorkServiceRun(runId)) {
                        val completed = publishCompletedRun { terminalState ->
                            stopForeground(STOP_FOREGROUND_REMOVE)
                            showNoEligibleNotification(
                                terminalState.recentScanWindowDays
                            )
                        }
                        if (completed) return@launch
                    }
                    if (syncManager.isServiceStopRequested(runId)) {
                        throw CancellationException(
                            "Manual SMS processing was stopped after scanning"
                        )
                    }
                    // A concurrently queued incoming alert can make the stale
                    // no-work snapshot obsolete. Continue with the latest queue.
                    scannedState = syncManager.syncState.value
                    if (scannedState.queue.none { it.status == "pending" }) {
                        error("Recent scan completion lost service ownership")
                    }
                }
                while (true) {
                    syncManager.executeSync(
                        this@SyncService,
                        admittedFlow,
                        runId
                    )
                    currentCoroutineContext().ensureActive()

                    val finalState = syncManager.syncState.value
                    finalState.syncError?.let { error(it) }
                    val completed = publishCompletedRun { terminalState ->
                        val totalSynced = terminalState.queue.count {
                            it.status == "synced"
                        }
                        val totalSkipped = terminalState.queue.count {
                            it.status == "filtered_out"
                        }
                        val totalErrors = terminalState.queue.count {
                            it.status == "error"
                        }
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        showCompletionNotification(
                            totalSynced,
                            totalSkipped,
                            totalErrors
                        )
                    }
                    if (completed) break
                    if (syncManager.isServiceStopRequested(runId)) {
                        throw CancellationException(
                            "Manual SMS processing was stopped during terminal handoff"
                        )
                    }
                }

            } catch (cancelled: CancellationException) {
                val publishStopped =
                    userStopRequestedRunId == runId ||
                        syncManager.isServiceStopRequested(runId)
                withContext(NonCancellable) {
                    progressObserver?.cancelAndJoin()
                    progressObserver = null
                    try {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        if (publishStopped) {
                            showStoppedNotification()
                        }
                    } finally {
                        // Admission is released last so reset cannot clear app
                        // state/notifications between settlement and publication.
                        synchronized(jobStateLock) {
                            syncManager.settleServiceCancellation(runId)
                            if (job === launchedJob) {
                                retireThroughStartId = maxOf(
                                    retireThroughStartId,
                                    activeJobRetireThroughStartId
                                )
                                job = null
                                activeJobRetireThroughStartId = 0
                            }
                        }
                        serviceFlowLease?.release()
                        serviceFlowLease = null
                    }
                }
                Log.i(TAG, "Sync cancelled and drained")
            } catch (e: Exception) {
                withContext(NonCancellable) {
                    progressObserver?.cancelAndJoin()
                    progressObserver = null
                    Log.e(TAG, "Error during sync execution", e)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    showErrorNotification(e.message ?: "Unknown sync error")
                    synchronized(jobStateLock) {
                        syncManager.finishServiceRun(runId)
                        if (job === launchedJob) {
                            retireThroughStartId = maxOf(
                                retireThroughStartId,
                                activeJobRetireThroughStartId
                            )
                            job = null
                            activeJobRetireThroughStartId = 0
                        }
                    }
                }
            } finally {
                withContext(NonCancellable) {
                    progressObserver?.cancelAndJoin()
                    serviceFlowLease?.release()
                    synchronized(jobStateLock) {
                        syncManager.finishServiceRun(runId)
                        if (job === launchedJob) {
                            retireThroughStartId = maxOf(
                                retireThroughStartId,
                                activeJobRetireThroughStartId
                            )
                            job = null
                            activeJobRetireThroughStartId = 0
                        }
                    }
                }
                if (userStopRequestedRunId == runId) {
                    userStopRequestedRunId = null
                }
                stopSelfResult(retireThroughStartId)
            }
        }
        synchronized(jobStateLock) {
            job = launchedJob
            activeJobRetireThroughStartId = startId
        }
        launchedJob.invokeOnCompletion { completionCause ->
            // A LAZY coroutine can be cancelled after start() schedules it but
            // before its body enters the try/finally above. In that case the
            // completion callback is the only owner able to settle state and
            // retire Android start IDs. Normal body exits clear `job` first.
            val fallbackRetireStartId = synchronized(jobStateLock) {
                if (job !== launchedJob) {
                    null
                } else {
                    val publishStopped =
                        userStopRequestedRunId == runId ||
                            syncManager.isServiceStopRequested(runId)
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    if (publishStopped) {
                        showStoppedNotification()
                        syncManager.settleServiceCancellation(runId)
                    } else {
                        syncManager.finishServiceRun(runId)
                    }
                    val retirementId = maxOf(
                        retireThroughStartId,
                        activeJobRetireThroughStartId
                    )
                    job = null
                    activeJobRetireThroughStartId = 0
                    if (userStopRequestedRunId == runId) {
                        userStopRequestedRunId = null
                    }
                    Log.w(
                        TAG,
                        "Settled service job from completion fallback",
                        completionCause
                    )
                    retirementId
                }
            }
            fallbackRetireStartId?.let(::stopSelfResult)
        }
        launchedJob.start()

        return START_NOT_STICKY
    }

    private fun buildNotification(
        title: String,
        text: String,
        progress: Float,
        ongoing: Boolean,
        runId: String?,
        showStopAction: Boolean
    ): Notification {
        val builder = NotificationCompat.Builder(this, SmsNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .setContentIntent(getAppPendingIntent())

        if (ongoing) {
            builder.setProgress(100, (progress * 100).toInt(), false)
            if (showStopAction && !runId.isNullOrBlank()) {
                builder.addAction(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "Stop",
                    getStopPendingIntent(runId)
                )
            }
        }
        return builder.build()
    }

    private fun updateNotification(
        title: String,
        text: String,
        progress: Float,
        ongoing: Boolean,
        runId: String?,
        showStopAction: Boolean
    ) {
        val notification = buildNotification(
            title = title,
            text = text,
            progress = progress,
            ongoing = ongoing,
            runId = runId,
            showStopAction = showStopAction
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notifySafely(nm, notification)
    }

    private fun showCompletionNotification(synced: Int, skipped: Int, errors: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val copy = manualSyncTerminalNotificationCopy(
            saved = synced,
            rejected = skipped,
            failed = errors
        )

        val builder = NotificationCompat.Builder(this, SmsNotificationHelper.CHANNEL_ID)
            .setSmallIcon(
                if (errors > 0) {
                    android.R.drawable.stat_notify_error
                } else {
                    android.R.drawable.stat_sys_download_done
                }
            )
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent())

        notifySafely(nm, builder.build())
    }

    private fun showNoEligibleNotification(windowDays: Int?) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val range = windowDays?.let { " in the last $it days" }.orEmpty()
        val builder = NotificationCompat.Builder(this, SmsNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Recent SMS Scan Finished")
            .setContentText("No new eligible transaction alerts were found$range.")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent())

        notifySafely(nm, builder.build())
    }

    private fun showErrorNotification(error: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = NotificationCompat.Builder(this, SmsNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("SMS Sync Failed")
            .setContentText(error)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent())

        notifySafely(nm, builder.build())
    }

    private fun showStoppedNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val copy = stoppedNotificationCopy()
        val notification = NotificationCompat.Builder(
            this,
            SmsNotificationHelper.CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_menu_close_clear_cancel)
            .setContentTitle(copy.title)
            .setContentText(copy.text)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent())
            .build()
        notifySafely(nm, notification)
    }

    /**
     * POST_NOTIFICATIONS is contextual and optional. The user may deny it while
     * still allowing the foreground operation to proceed, so a terminal or
     * progress post must never turn successful local work into a service error.
     */
    private fun notifySafely(
        notificationManager: NotificationManager,
        notification: Notification
    ) {
        runCatching {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }.onFailure { error ->
            Log.w(TAG, "Notification post was unavailable", error)
        }
    }

    private fun getAppPendingIntent(): PendingIntent {
        val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        } ?: Intent()

        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun getStopPendingIntent(runId: String): PendingIntent =
        PendingIntent.getService(
            this,
            2,
            stopIntent(this, runId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun jobSnapshotRecordingStart(startId: Int): Job? =
        synchronized(jobStateLock) {
            job.also { current ->
                if (current != null) {
                    // Ownership, not coroutine activity, is the consistency
                    // boundary. A completed LAZY job still owns notification
                    // and manager settlement until its completion callback
                    // atomically clears this field.
                    activeJobRetireThroughStartId = maxOf(
                        activeJobRetireThroughStartId,
                        startId
                    )
                }
            }
        }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "SyncService Destroyed")
        synchronized(jobStateLock) { job }?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}

internal fun manualSyncStartAllowed(
    shellUnlocked: Boolean,
    generationIsCurrent: Boolean
): Boolean = shellUnlocked && generationIsCurrent

internal fun shouldKeepManualSyncDraining(
    jobIsPresent: Boolean
): Boolean = jobIsPresent

internal fun manualSyncStopRejectionMessage(
    requiresCandidateMatch: Boolean,
    stopAccepted: Boolean
): String? = if (requiresCandidateMatch && !stopAccepted) {
    "Stop wasn't applied because this processing step is no longer active. " +
        "No other SMS processing was stopped."
} else {
    null
}

internal fun manualSyncPreDispatchRejectionMessage(
    state: HomeSyncState,
    target: SmsProcessingTarget.ManualRecent
): String? = manualSyncStopRejectionMessage(
    requiresCandidateMatch = true,
    stopAccepted = state.ownsManualProcessingTarget(target)
)

internal fun showManualSyncStopRejectionFeedback(
    context: Context,
    message: String
) {
    runCatching {
        Toast.makeText(
            context.applicationContext,
            message,
            Toast.LENGTH_LONG
        ).show()
    }.onFailure { error ->
        Log.w("ManualSyncStop", "Stop rejection feedback was unavailable", error)
    }
}

internal data class ManualSyncStoppedCopy(
    val title: String,
    val text: String
)

internal data class ManualSyncTerminalNotificationCopy(
    val title: String,
    val text: String
)

internal fun manualSyncTerminalNotificationCopy(
    saved: Int,
    rejected: Int,
    failed: Int
): ManualSyncTerminalNotificationCopy {
    require(saved >= 0 && rejected >= 0 && failed >= 0)
    val text = buildString {
        append("$saved saved locally")
        if (rejected > 0) append(", $rejected rejected")
        if (failed > 0) {
            append(
                ", $failed " +
                    if (failed == 1) {
                        "needs another attempt"
                    } else {
                        "need another attempt"
                    }
            )
        }
    }
    return ManualSyncTerminalNotificationCopy(
        title = if (failed > 0) {
            "SMS Sync Needs Attention"
        } else {
            "SMS Sync Complete"
        },
        text = text
    )
}
