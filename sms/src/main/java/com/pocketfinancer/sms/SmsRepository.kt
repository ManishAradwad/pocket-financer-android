package com.pocketfinancer.sms

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SmsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val smsReader: SmsReader
) {
    fun hasPermissions(): Boolean {
        val readGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED

        val receiveGranted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECEIVE_SMS
        ) == PackageManager.PERMISSION_GRANTED

        return readGranted && receiveGranted
    }

    /**
     * Fetch SMS history from the last N days.
     */
    fun fetchHistory(daysBack: Int = 90, limit: Int = 500): List<SmsReader.SmsMessage> {
        val minDate = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(daysBack.toLong())
        return smsReader.fetchInbox(
            SmsReader.SmsFilter(minDate = minDate, limit = limit)
        )
    }

    /**
     * Poll inbox periodically for new SMS (fallback when RECEIVE_SMS is denied).
     * Emits any SMS newer than the last seen timestamp.
     */
    fun pollInbox(intervalMs: Long = 30_000): Flow<List<SmsReader.SmsMessage>> = flow {
        var lastSeen = System.currentTimeMillis()
        while (true) {
            val messages = smsReader.fetchInbox(
                SmsReader.SmsFilter(minDate = lastSeen + 1, limit = 50)
            )
            if (messages.isNotEmpty()) {
                lastSeen = messages.maxOf { it.date }
                emit(messages)
            }
            kotlinx.coroutines.delay(intervalMs)
        }
    }

    /**
     * Listen for real-time incoming SMS via BroadcastReceiver.
     * Returns cold Flow — starts emitting when collected.
     */
    fun listenForIncomingSms(): Flow<SmsReader.SmsMessage> {
        return SmsReceiver.incomingSmsFlow()
    }
}
