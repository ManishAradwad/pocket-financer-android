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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * BroadcastReceiver for incoming SMS. Emits each received SMS via a
 * Channel that SmsRepository exposes as a Flow.
 *
 * Ported from the React Native SmsReceiver.kt — RN bridge replaced with
 * Channel-based Flow emission.
 */
class SmsReceiver : BroadcastReceiver() {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface SmsReceiverEntryPoint {
        fun smsWorkScheduler(): SmsWorkScheduler
    }

    companion object {
        /** Shared channel so SmsRepository can listen to incoming SMS. */
        private val smsChannel = Channel<SmsReader.SmsMessage>(Channel.BUFFERED)

        fun incomingSmsFlow(): Flow<SmsReader.SmsMessage> = smsChannel.receiveAsFlow()
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
                    type = 1  // Inbox
                )
                // 1. Emits SMS via channel (for UI)
                smsChannel.trySend(msg)

                // 2. Schedule background processing via WorkManager (using scheduler entrypoint)
                try {
                    val appContext = context.applicationContext
                    val entryPoint = EntryPointAccessors.fromApplication(
                        appContext,
                        SmsReceiverEntryPoint::class.java
                    )
                    entryPoint.smsWorkScheduler().scheduleSmsParsing(
                        address = msg.address,
                        body = msg.body,
                        date = msg.date
                    )
                } catch (e: Exception) {
                    Log.e("SmsReceiver", "Failed to schedule background SMS parsing", e)
                }
            }
        }
    }
}

