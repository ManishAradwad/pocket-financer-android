package com.pocketfinancer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "queued_sms_candidates",
    indices = [
        Index(
            value = ["sourceConnector", "sourceMessageId"],
            unique = true
        ),
        Index(
            value = ["sourceConnector", "sourceFingerprint"],
            unique = true
        ),
        Index(value = ["origin", "state"])
    ]
)
data class QueuedSmsCandidateEntity(
    @PrimaryKey
    val candidateKey: String,
    val sourceConnector: String,
    val sourceMessageId: String,
    val sourceFingerprint: String,
    val sourceAlternateFingerprint: String?,
    val sourceProviderMessageId: String?,
    val sender: String,
    val rawMessage: String,
    val date: Long,
    val sourceTimestamp: Long,
    val messageType: Int,
    val origin: String,
    val state: String = STATE_PENDING,
    val claimToken: String? = null,
    val claimedAt: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    companion object {
        const val STATE_PENDING = "pending"
        const val STATE_CLAIMED = "claimed"
    }
}
