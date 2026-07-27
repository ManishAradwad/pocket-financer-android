package com.pocketfinancer.data.model

import java.security.MessageDigest
import java.util.Locale

/**
 * Stable identity for an item delivered by an external connector.
 *
 * [messageId] prefers the connector's provider id when it is available.
 * [fallbackFingerprint] is always populated so an Android broadcast (which has
 * no provider `_id`) and the later inbox row converge on the same evidence.
 */
data class SmsSourceIdentity(
    val connector: String,
    val messageId: String,
    val fallbackFingerprint: String,
    val providerMessageId: String? = null,
    /**
     * Compatibility alias for evidence whose connector exposed two timestamps.
     *
     * Android provider rows use the sent timestamp for the canonical
     * fingerprint so they converge with the receive broadcast. The provider's
     * received timestamp is retained here so rows migrated from V3 (which only
     * stored that received timestamp) still resolve to the same transaction.
     */
    val alternateFingerprint: String? = null
) {
    init {
        require(connector.isNotBlank()) { "Source connector must not be blank" }
        require(messageId.isNotBlank()) { "Source message id must not be blank" }
        require(fallbackFingerprint.isNotBlank()) {
            "Source fallback fingerprint must not be blank"
        }
        require(alternateFingerprint?.isNotBlank() != false) {
            "Source alternate fingerprint must not be blank"
        }
    }

    /**
     * Opaque key safe to persist in WorkManager Data and notification ids.
     * It contains no sender, body, provider id, or timestamp in plaintext.
     *
     * The connector's authoritative message id is the consistency boundary:
     * provider-backed rows with identical evidence remain distinct, while a
     * provider-less delivery still uses its deterministic fallback message id.
     */
    val opaqueCandidateKey: String
        get() = "sms_" + sha256(
            lengthDelimited(
                connector,
                messageId
            )
        )

    companion object {
        const val ANDROID_SMS_CONNECTOR = "android_sms"
        private const val PROVIDER_PREFIX = "provider:"
        private const val FALLBACK_PREFIX = "fallback:"
        private const val LEGACY_DUPLICATE_PREFIX = "legacy_duplicate:"

        fun androidSms(
            providerMessageId: String?,
            sender: String,
            body: String,
            sourceTimestamp: Long,
            messageType: Int,
            receivedTimestamp: Long? = null
        ): SmsSourceIdentity {
            val normalizedProviderId = providerMessageId
                ?.trim()
                ?.takeIf(String::isNotEmpty)
            val fingerprint = androidSmsFingerprint(
                sender = sender,
                body = body,
                timestamp = sourceTimestamp,
                messageType = messageType
            )
            val alternateFingerprint = receivedTimestamp
                ?.takeIf { it > 0L && it != sourceTimestamp }
                ?.let { timestamp ->
                    androidSmsFingerprint(
                        sender = sender,
                        body = body,
                        timestamp = timestamp,
                        messageType = messageType
                    )
                }
            return SmsSourceIdentity(
                connector = ANDROID_SMS_CONNECTOR,
                messageId = normalizedProviderId
                    ?.let { "$PROVIDER_PREFIX$it" }
                    ?: "$FALLBACK_PREFIX$fingerprint",
                fallbackFingerprint = fingerprint,
                providerMessageId = normalizedProviderId,
                alternateFingerprint = alternateFingerprint
            )
        }

        /**
         * V3 could contain exact duplicate transactions. A unique source index
         * must not delete or overwrite them, so the first row receives the
         * canonical fallback identity and each later row gets a stable identity
         * derived from its already-persisted transaction id.
         */
        fun legacyDuplicate(
            canonical: SmsSourceIdentity,
            transactionId: String
        ): SmsSourceIdentity {
            val suffix = sha256(
                lengthDelimited(
                    canonical.connector,
                    canonical.fallbackFingerprint,
                    transactionId
                )
            )
            val synthetic = "$LEGACY_DUPLICATE_PREFIX" +
                "${canonical.fallbackFingerprint}:$suffix"
            return SmsSourceIdentity(
                connector = canonical.connector,
                messageId = synthetic,
                fallbackFingerprint = synthetic,
                providerMessageId = null,
                alternateFingerprint = null
            )
        }

        private fun androidSmsFingerprint(
            sender: String,
            body: String,
            timestamp: Long,
            messageType: Int
        ): String = sha256(
            lengthDelimited(
                ANDROID_SMS_CONNECTOR,
                sender.trim().uppercase(Locale.ROOT),
                body,
                timestamp.toString(),
                messageType.toString()
            )
        )

        private fun lengthDelimited(vararg values: String): ByteArray {
            val bytes = values.map { it.toByteArray(Charsets.UTF_8) }
            val totalSize = bytes.sumOf { Integer.BYTES + it.size }
            val output = ByteArray(totalSize)
            var offset = 0
            bytes.forEach { value ->
                val size = value.size
                output[offset++] = (size ushr 24).toByte()
                output[offset++] = (size ushr 16).toByte()
                output[offset++] = (size ushr 8).toByte()
                output[offset++] = size.toByte()
                value.copyInto(output, destinationOffset = offset)
                offset += value.size
            }
            return output
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256")
                .digest(bytes)
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
