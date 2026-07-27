package com.pocketfinancer.sms

import android.content.ContentResolver
import android.net.Uri
import android.provider.Telephony
import com.pocketfinancer.data.model.SmsSourceIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reads SMS inbox via ContentResolver. Ported from the React Native SmsModule.kt.
 */
@Singleton
class SmsReader @Inject constructor(
    @ApplicationContext private val context: android.content.Context
) {
    data class SmsMessage(
        val address: String,
        val body: String,
        val date: Long,          // epoch millis
        val type: Int,            // 1 = inbox, 2 = sent, etc.
        /** Raw Android SMS provider `_id`; broadcasts legitimately have none. */
        val providerMessageId: String? = null,
        /**
         * Timestamp used only for source identity.
         *
         * Provider rows prefer `date_sent` so they converge with
         * SmsMessage.timestampMillis from the receive broadcast. [date] remains
         * the provider's received timestamp used by the ledger and UI.
         */
        val sourceTimestamp: Long = date
    ) {
        val sourceIdentity: SmsSourceIdentity
            get() = SmsSourceIdentity.androidSms(
                providerMessageId = providerMessageId,
                sender = address,
                body = body,
                sourceTimestamp = sourceTimestamp,
                messageType = type,
                receivedTimestamp = date
            )
    }

    data class SmsFilter(
        val minDate: Long = 0L,
        val maxDate: Long = Long.MAX_VALUE,
        val addressPattern: String? = null,
        val limit: Int = 100,
        val offset: Int = 0
    )

    /**
     * Query the SMS inbox with optional filters.
     */
    fun fetchInbox(filter: SmsFilter = SmsFilter()): List<SmsMessage> {
        val uri = Uri.parse("content://sms/inbox")
        val projection = arrayOf(
            "_id",
            "address",
            "body",
            "date",
            "date_sent",
            "type"
        )
        val selection = "date >= ? AND date <= ?"
        val selectionArgs = arrayOf(filter.minDate.toString(), filter.maxDate.toString())
        val sortOrder = "date DESC"

        val cursor = context.contentResolver.query(
            uri,
            projection,
            selection,
            selectionArgs,
            sortOrder
        ) ?: throw IllegalStateException(
            "The SMS provider did not return a readable inbox cursor."
        )

        val results = mutableListOf<SmsMessage>()
        cursor.use {
            val addressIdx = it.getColumnIndex("address")
            val bodyIdx = it.getColumnIndex("body")
            val dateIdx = it.getColumnIndex("date")
            val dateSentIdx = it.getColumnIndex("date_sent")
            val typeIdx = it.getColumnIndex("type")
            val providerIdIdx = it.getColumnIndex("_id")

            // Skip offset
            if (filter.offset > 0) {
                it.move(filter.offset)
            }

            var count = 0
            while (it.moveToNext() && count < filter.limit) {
                val address = if (addressIdx >= 0) it.getString(addressIdx) else ""
                val body = if (bodyIdx >= 0) it.getString(bodyIdx) else ""

                // Optional address pattern filter (regex)
                if (!filter.addressPattern.isNullOrEmpty()) {
                    try {
                        val regex = filter.addressPattern.toRegex(RegexOption.IGNORE_CASE)
                        if (!regex.containsMatchIn(address ?: "")) continue
                    } catch (_: Exception) {
                        // invalid regex, skip filter
                    }
                }

                val receivedDate =
                    if (dateIdx >= 0) it.getLong(dateIdx) else 0L
                val sentDate =
                    if (dateSentIdx >= 0 && !it.isNull(dateSentIdx)) {
                        it.getLong(dateSentIdx)
                    } else {
                        0L
                    }
                results.add(
                    SmsMessage(
                        address = address ?: "",
                        body = body ?: "",
                        date = receivedDate,
                        type = if (typeIdx >= 0) it.getInt(typeIdx) else 1,
                        providerMessageId = if (
                            providerIdIdx >= 0 && !it.isNull(providerIdIdx)
                        ) {
                            it.getString(providerIdIdx)
                        } else {
                            null
                        },
                        sourceTimestamp = sentDate.takeIf { it > 0L }
                            ?: receivedDate
                    )
                )
                count++
            }
        }
        return results
    }

    /**
     * Cheap existence probe used to distinguish an actually empty inbox from
     * one whose messages are all older than the adaptive discovery window.
     *
     * Only `_id` is projected and at most the first provider row is inspected;
     * no SMS body or sender is materialized.
     */
    fun hasAnyInboxMessage(maxDate: Long = Long.MAX_VALUE): Boolean {
        val cursor = context.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("_id"),
            "date <= ?",
            arrayOf(maxDate.toString()),
            "date DESC"
        ) ?: throw IllegalStateException(
            "The SMS provider did not return a readable inbox cursor."
        )
        return cursor.use { it.moveToFirst() }
    }
}
