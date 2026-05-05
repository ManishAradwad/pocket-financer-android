package com.pocketfinancer.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
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

    companion object {
        /** Shared channel so SmsRepository can listen to incoming SMS. */
        private val smsChannel = Channel<SmsReader.SmsMessage>(Channel.BUFFERED)

        fun incomingSmsFlow(): Flow<SmsReader.SmsMessage> = smsChannel.receiveAsFlow()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val msg = SmsReader.SmsMessage(
                    address = sms.originatingAddress ?: "",
                    body = sms.messageBody ?: "",
                    date = sms.timestampMillis,
                    type = 1  // Inbox
                )
                smsChannel.trySend(msg)
            }
        }
    }
}
