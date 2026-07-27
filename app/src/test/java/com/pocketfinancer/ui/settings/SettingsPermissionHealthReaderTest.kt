package com.pocketfinancer.ui.settings

import android.app.NotificationManager
import android.os.Build
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPermissionHealthReaderTest {

    @Test
    fun `android 13 notification runtime denial is unhealthy`() {
        val health = resolveSettingsPermissionHealth(
            sdkInt = Build.VERSION_CODES.TIRAMISU,
            readSmsPermissionGranted = true,
            receiveSmsPermissionGranted = true,
            postNotificationsPermissionGranted = false,
            appNotificationsEnabled = true,
            progressChannelImportance = NotificationManager.IMPORTANCE_DEFAULT
        )

        assertTrue(health.notificationPermissionRequired)
        assertFalse(health.notificationRuntimePermissionGranted)
        assertFalse(health.progressNotificationsHealthy)
    }

    @Test
    fun `global notification disablement is unhealthy on every supported sdk`() {
        val health = resolveSettingsPermissionHealth(
            sdkInt = Build.VERSION_CODES.S,
            readSmsPermissionGranted = true,
            receiveSmsPermissionGranted = true,
            postNotificationsPermissionGranted = true,
            appNotificationsEnabled = false,
            progressChannelImportance = NotificationManager.IMPORTANCE_DEFAULT
        )

        assertTrue(health.notificationRuntimePermissionGranted)
        assertFalse(health.appNotificationsEnabled)
        assertFalse(health.progressNotificationsHealthy)
    }

    @Test
    fun `disabled progress channel is unhealthy`() {
        val health = resolveSettingsPermissionHealth(
            sdkInt = Build.VERSION_CODES.O,
            readSmsPermissionGranted = true,
            receiveSmsPermissionGranted = true,
            postNotificationsPermissionGranted = true,
            appNotificationsEnabled = true,
            progressChannelImportance = NotificationManager.IMPORTANCE_NONE
        )

        assertFalse(health.progressNotificationChannelEnabled)
        assertFalse(health.progressNotificationsHealthy)
    }

    @Test
    fun `missing channel is healthy until Android creates it`() {
        val health = resolveSettingsPermissionHealth(
            sdkInt = Build.VERSION_CODES.O,
            readSmsPermissionGranted = true,
            receiveSmsPermissionGranted = true,
            postNotificationsPermissionGranted = true,
            appNotificationsEnabled = true,
            progressChannelImportance = null
        )

        assertTrue(health.progressNotificationChannelEnabled)
        assertTrue(health.progressNotificationsHealthy)
    }
}
