package com.pocketfinancer.pipeline

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object SmsNotificationHelper {

    const val CHANNEL_ID = "sms_parsing_channel"
    private const val SUMMARY_NOTIFICATION_ID = 99999

    /**
     * Create the notification channel required for posting notifications on API 26+.
     */
    fun createNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "SMS Transaction Sync"
            val descriptionText = "Displays stages of local transaction SMS parsing and sync alerts."
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(CHANNEL_ID, name, importance).apply {
                description = descriptionText
            }
            val notificationManager: NotificationManager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Get a unique notification ID for a specific SMS.
     */
    fun getNotificationId(sender: String, date: Long): Int {
        return (sender + date.toString()).hashCode()
    }

    private fun getAppPendingIntent(context: Context): PendingIntent {
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        } ?: Intent()
        
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Post a notification indicating that an SMS has been skipped because it is non-transactional.
     */
    fun showSkippedNotification(context: Context, sender: String, body: String, date: Long) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(sender, date)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning) // fallback built-in icon
            .setContentTitle("SMS Skipped")
            .setContentText("SMS from $sender contains non-transactional content.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("SMS from $sender contains non-transactional content:\n\"$body\""))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent(context))

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    /**
     * Post/update an ongoing notification representing a stage of processing.
     */
    fun showProcessingNotification(
        context: Context,
        sender: String,
        date: Long,
        stageText: String
    ) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(sender, date)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Processing Transaction SMS")
            .setContentText("$stageText (Sender: $sender)")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true) // Cannot be swiped away
            .setAutoCancel(false)
            .setContentIntent(getAppPendingIntent(context))

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    /**
     * Post a notification indicating successful database sync.
     */
    fun showSuccessNotification(
        context: Context,
        sender: String,
        date: Long,
        amount: Double,
        merchant: String
    ) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(sender, date)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("SMS Synced")
            .setContentText("Extracted ₹${"%,.2f".format(amount)} at $merchant.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false) // Can be swiped away now
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent(context))

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    /**
     * Post a notification indicating processing failed.
     */
    fun showFailureNotification(
        context: Context,
        sender: String,
        date: Long,
        reason: String
    ) {
        createNotificationChannel(context)
        val notificationId = getNotificationId(sender, date)

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("SMS Sync Failed")
            .setContentText("Could not sync message from $sender: $reason")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOngoing(false) // Can be swiped away
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent(context))

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(notificationId, builder.build())
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    /**
     * Post/update the summary notification showing the total count of unsynced transaction messages.
     */
    fun showUnsyncedSummaryNotification(context: Context, unsyncedCount: Int) {
        createNotificationChannel(context)
        
        if (unsyncedCount <= 0) {
            cancelUnsyncedSummaryNotification(context)
            return
        }

        val text = if (unsyncedCount == 1) {
            "1 transaction is yet to be synced. Tap to sync."
        } else {
            "$unsyncedCount transactions are yet to be synced. Tap to sync."
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle("Unsynced Transactions")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(getAppPendingIntent(context))

        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.notify(SUMMARY_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            // Permission not granted
        }
    }

    fun cancelUnsyncedSummaryNotification(context: Context) {
        try {
            val notificationManager = NotificationManagerCompat.from(context)
            notificationManager.cancel(SUMMARY_NOTIFICATION_ID)
        } catch (e: Exception) {
            // Silently fail
        }
    }
}
