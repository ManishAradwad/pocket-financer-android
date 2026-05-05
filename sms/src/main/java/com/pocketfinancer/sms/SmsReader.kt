package com.pocketfinancer.sms

import android.content.ContentResolver
import android.net.Uri
import android.provider.Telephony
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
        val type: Int            // 1 = inbox, 2 = sent, etc.
    )

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
        val projection = arrayOf("_id", "address", "body", "date", "type")
        val selection = "date >= ? AND date <= ?"
        val selectionArgs = arrayOf(filter.minDate.toString(), filter.maxDate.toString())
        val sortOrder = "date DESC"

        val cursor = context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
            ?: return emptyList()

        val results = mutableListOf<SmsMessage>()
        cursor.use {
            val addressIdx = it.getColumnIndex("address")
            val bodyIdx = it.getColumnIndex("body")
            val dateIdx = it.getColumnIndex("date")
            val typeIdx = it.getColumnIndex("type")

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

                results.add(
                    SmsMessage(
                        address = address ?: "",
                        body = body ?: "",
                        date = if (dateIdx >= 0) it.getLong(dateIdx) else 0L,
                        type = if (typeIdx >= 0) it.getInt(typeIdx) else 1
                    )
                )
                count++
            }
        }
        return results
    }
}
