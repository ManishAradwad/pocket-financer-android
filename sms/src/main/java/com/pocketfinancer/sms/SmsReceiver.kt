package com.pocketfinancer.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for incoming SMS. [goAsync] keeps the receiver alive until
 * the automatic-processing policy is applied. After opt-in, raw evidence
 * reaches encrypted storage and only opaque WorkManager input is queued; while
 * OFF, the message remains available to a later manual inbox scan.
 */
class SmsReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsReceiverEntryPoint {
        fun smsWorkScheduler(): SmsWorkScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (!messages.isNullOrEmpty()) {
                val firstMsg = messages[0]
                val address = firstMsg.originatingAddress ?: ""
                val date = firstMsg.timestampMillis
                val body = messages.joinToString("") { it.messageBody ?: "" }

                val msg = SmsReader.SmsMessage(
                    address = address,
                    body = body,
                    date = date,
                    type = 1,  // Inbox
                    sourceTimestamp = firstMsg.timestampMillis
                )
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val appContext = context.applicationContext
                        val entryPoint = EntryPointAccessors.fromApplication(
                            appContext,
                            SmsReceiverEntryPoint::class.java
                        )
                        entryPoint.smsWorkScheduler().scheduleSmsParsing(msg)
                    } catch (e: Exception) {
                        Log.e(
                            "SmsReceiver",
                            "Failed to apply incoming SMS processing policy",
                            e
                        )
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}

