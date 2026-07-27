package com.pocketfinancer.data.model

enum class SmsCandidateOrigin(val persistedValue: String) {
    AUTOMATIC("automatic"),
    MANUAL("manual");

    companion object {
        fun fromPersisted(value: String): SmsCandidateOrigin =
            entries.firstOrNull { it.persistedValue == value } ?: AUTOMATIC
    }
}

/**
 * Decrypted candidate evidence. Instances should be kept only for the active
 * operation; the durable copy lives in the SQLCipher-backed Room database.
 */
data class QueuedSmsCandidate(
    val candidateKey: String,
    val sourceIdentity: SmsSourceIdentity,
    val sender: String,
    val rawMessage: String,
    val date: Long,
    val sourceTimestamp: Long,
    val messageType: Int,
    val origin: SmsCandidateOrigin,
    val claimToken: String?,
    val attemptCount: Int
)
