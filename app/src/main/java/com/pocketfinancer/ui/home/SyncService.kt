package com.pocketfinancer.ui.home

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.pocketfinancer.pipeline.SmsNotificationHelper
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import javax.inject.Inject

@AndroidEntryPoint
class SyncService : Service() {

    @Inject
    lateinit var syncManager: HomeSyncManager

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var job: Job? = null

    companion object {
        private const val TAG = "SyncService"
        private const val NOTIFICATION_ID = 20002

        fun start(context: Context) {
            val intent = Intent(context, SyncService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

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

        if (job != null && job!!.isActive) {
            Log.i(TAG, "SyncService is already active. Appended message will be processed by the current run.")
            return START_NOT_STICKY
        }

        val initialNotification = buildNotification("Syncing Transactions", "Initializing sync queue...", 0f, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        job = serviceScope.launch {
            try {
                // Listen to HomeSyncManager progress to dynamically update the notification text and bar
                val flowJob = launch {
                    syncManager.syncState.collect { state ->
                        if (state.status == HomeSyncState.Status.SYNCING) {
                            val total = state.queue.size
                            val current = state.currentIndex ?: 0
                            val activeSms = if (state.currentIndex != null && state.currentIndex < state.queue.size) {
                                state.queue[state.currentIndex]
                            } else null

                            val progress = if (total > 0) (current.toFloat() / total) else 0f
                            val text = if (activeSms != null) {
                                "Analyzing SMS ${current + 1} of $total: ${activeSms.sender}..."
                            } else {
                                "Processing SMS ${current + 1} of $total..."
                            }

                            updateNotification(
                                title = "Syncing Transactions",
                                text = text,
                                progress = progress,
                                ongoing = true
                            )
                        }
                    }
                }

                // Check again for unsynced messages and run execution
                syncManager.checkForUnsyncedSms()
                syncManager.executeSync(this@SyncService)
                flowJob.cancel()

                // Gather sync metrics
                val finalState = syncManager.syncState.value
                val totalSynced = finalState.queue.count { it.status == "synced" }
                val totalSkipped = finalState.queue.count { it.status == "filtered_out" }
                val totalErrors = finalState.queue.count { it.status == "error" }

                stopForeground(STOP_FOREGROUND_REMOVE)
                showCompletionNotification(totalSynced, totalSkipped, totalErrors)

            } catch (e: Exception) {
                Log.e(TAG, "Error during sync execution", e)
                stopForeground(STOP_FOREGROUND_REMOVE)
                showErrorNotification(e.message ?: "Unknown sync error")
            } finally {
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(title: String, text: String, progress: Float, ongoing: Boolean): Notification {
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
        }
        return builder.build()
    }

    private fun updateNotification(title: String, text: String, progress: Float, ongoing: Boolean) {
        val notification = buildNotification(title, text, progress, ongoing)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(synced: Int, skipped: Int, errors: Int) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val text = buildString {
            append("$synced synced successfully")
            if (skipped > 0) append(", $skipped skipped")
            if (errors > 0) append(", $errors failed")
        }

        val builder = NotificationCompat.Builder(this, SmsNotificationHelper.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SMS Sync Completed")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent())

        nm.notify(NOTIFICATION_ID, builder.build())
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

        nm.notify(NOTIFICATION_ID, builder.build())
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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.i(TAG, "SyncService Destroyed")
        job?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }
}
