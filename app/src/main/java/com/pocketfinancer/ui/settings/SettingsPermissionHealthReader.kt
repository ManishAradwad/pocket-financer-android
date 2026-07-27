package com.pocketfinancer.ui.settings

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.pocketfinancer.pipeline.SmsNotificationHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

data class SettingsPermissionHealthSnapshot(
    val readSmsPermissionGranted: Boolean,
    val receiveSmsPermissionGranted: Boolean,
    val notificationPermissionRequired: Boolean,
    val notificationRuntimePermissionGranted: Boolean,
    val appNotificationsEnabled: Boolean,
    val progressNotificationChannelEnabled: Boolean
) {
    val progressNotificationsHealthy: Boolean
        get() = notificationRuntimePermissionGranted &&
            appNotificationsEnabled &&
            progressNotificationChannelEnabled
}

internal fun resolveSettingsPermissionHealth(
    sdkInt: Int,
    readSmsPermissionGranted: Boolean,
    receiveSmsPermissionGranted: Boolean,
    postNotificationsPermissionGranted: Boolean,
    appNotificationsEnabled: Boolean,
    progressChannelImportance: Int?
): SettingsPermissionHealthSnapshot {
    val notificationPermissionRequired = sdkInt >= Build.VERSION_CODES.TIRAMISU
    val channelEnabled = sdkInt < Build.VERSION_CODES.O ||
        progressChannelImportance == null ||
        progressChannelImportance != NotificationManager.IMPORTANCE_NONE
    return SettingsPermissionHealthSnapshot(
        readSmsPermissionGranted = readSmsPermissionGranted,
        receiveSmsPermissionGranted = receiveSmsPermissionGranted,
        notificationPermissionRequired = notificationPermissionRequired,
        notificationRuntimePermissionGranted =
            !notificationPermissionRequired || postNotificationsPermissionGranted,
        appNotificationsEnabled = appNotificationsEnabled,
        progressNotificationChannelEnabled = channelEnabled
    )
}

/**
 * Reads effective permission health, including Android controls that are not
 * represented by runtime permission grants.
 */
@Singleton
class SettingsPermissionHealthReader @Inject constructor(
    @ApplicationContext private val context: Context
) {
    fun read(): SettingsPermissionHealthSnapshot {
        val sdkInt = Build.VERSION.SDK_INT
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        val channelImportance = if (sdkInt >= Build.VERSION_CODES.O) {
            notificationManager
                ?.getNotificationChannel(SmsNotificationHelper.CHANNEL_ID)
                ?.importance
        } else {
            null
        }
        return resolveSettingsPermissionHealth(
            sdkInt = sdkInt,
            readSmsPermissionGranted = hasPermission(Manifest.permission.READ_SMS),
            receiveSmsPermissionGranted = hasPermission(Manifest.permission.RECEIVE_SMS),
            postNotificationsPermissionGranted =
                hasPermission(Manifest.permission.POST_NOTIFICATIONS),
            appNotificationsEnabled = runCatching {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }.getOrDefault(false),
            progressChannelImportance = channelImportance
        )
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) ==
            PackageManager.PERMISSION_GRANTED
}
