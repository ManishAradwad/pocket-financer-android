package com.pocketfinancer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketfinancer.data.db.entity.QueuedSmsCandidateEntity

@Dao
interface QueuedSmsCandidateDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(candidate: QueuedSmsCandidateEntity): Long

    @Query("SELECT * FROM queued_sms_candidates WHERE candidateKey = :candidateKey")
    suspend fun getByKey(candidateKey: String): QueuedSmsCandidateEntity?

    @Query(
        """
        SELECT * FROM queued_sms_candidates
        WHERE sourceConnector = :connector
          AND (
            sourceMessageId = :messageId
            OR sourceFingerprint = :fingerprint
            OR sourceAlternateFingerprint = :fingerprint
            OR (
                :alternateFingerprint IS NOT NULL
                AND (
                    sourceFingerprint = :alternateFingerprint
                    OR sourceAlternateFingerprint = :alternateFingerprint
                )
            )
          )
        LIMIT 1
        """
    )
    suspend fun findBySource(
        connector: String,
        messageId: String,
        fingerprint: String,
        alternateFingerprint: String?
    ): QueuedSmsCandidateEntity?

    @Query(
        """
        UPDATE queued_sms_candidates
        SET sourceProviderMessageId =
                COALESCE(sourceProviderMessageId, :providerMessageId),
            sourceAlternateFingerprint =
                COALESCE(
                    sourceAlternateFingerprint,
                    CASE
                        WHEN sourceFingerprint != :fingerprint
                            THEN :fingerprint
                        WHEN :alternateFingerprint IS NOT NULL
                            AND sourceFingerprint != :alternateFingerprint
                            THEN :alternateFingerprint
                        ELSE NULL
                    END
                ),
            date = CASE
                WHEN :providerMessageId IS NOT NULL THEN :date
                ELSE date
            END,
            sourceTimestamp = CASE
                WHEN :providerMessageId IS NOT NULL THEN :sourceTimestamp
                ELSE sourceTimestamp
            END,
            updatedAt = :updatedAt
        WHERE candidateKey = :candidateKey
        """
    )
    suspend fun preserveSourceMetadata(
        candidateKey: String,
        providerMessageId: String?,
        fingerprint: String,
        alternateFingerprint: String?,
        date: Long,
        sourceTimestamp: Long,
        updatedAt: Long
    )

    @Query(
        """
        UPDATE queued_sms_candidates
        SET state = 'claimed',
            claimToken = :claimToken,
            claimedAt = :claimedAt,
            attemptCount = attemptCount + 1,
            lastError = NULL,
            updatedAt = :claimedAt
        WHERE candidateKey = :candidateKey
          AND (
            state = 'pending'
            OR claimToken = :claimToken
            OR claimedAt IS NULL
            OR claimedAt <= :staleBefore
          )
        """
    )
    suspend fun claim(
        candidateKey: String,
        claimToken: String,
        claimedAt: Long,
        staleBefore: Long
    ): Int

    @Query(
        """
        UPDATE queued_sms_candidates
        SET state = 'pending',
            claimToken = NULL,
            claimedAt = NULL,
            lastError = :lastError,
            updatedAt = :updatedAt
        WHERE candidateKey = :candidateKey
          AND state = 'claimed'
          AND claimToken = :claimToken
        """
    )
    suspend fun releaseForRetry(
        candidateKey: String,
        claimToken: String,
        lastError: String?,
        updatedAt: Long
    ): Int

    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM queued_sms_candidates
            WHERE candidateKey = :candidateKey
              AND state = 'claimed'
              AND claimToken = :claimToken
        )
        """
    )
    suspend fun isClaimOwned(candidateKey: String, claimToken: String): Boolean

    @Query(
        """
        DELETE FROM queued_sms_candidates
        WHERE candidateKey = :candidateKey
          AND state = 'claimed'
          AND claimToken = :claimToken
        """
    )
    suspend fun deleteClaimed(
        candidateKey: String,
        claimToken: String
    ): Int

    @Query("DELETE FROM queued_sms_candidates WHERE candidateKey = :candidateKey")
    suspend fun deleteByKey(candidateKey: String): Int

    @Query(
        """
        DELETE FROM queued_sms_candidates
        WHERE sourceConnector = :connector
          AND (
            sourceMessageId = :messageId
            OR sourceFingerprint = :fingerprint
            OR sourceAlternateFingerprint = :fingerprint
            OR (
                :alternateFingerprint IS NOT NULL
                AND (
                    sourceFingerprint = :alternateFingerprint
                    OR sourceAlternateFingerprint = :alternateFingerprint
                )
            )
          )
        """
    )
    suspend fun deleteBySource(
        connector: String,
        messageId: String,
        fingerprint: String,
        alternateFingerprint: String?
    ): Int

    @Query(
        """
        DELETE FROM queued_sms_candidates
        WHERE origin = 'automatic' AND state = 'pending'
        """
    )
    suspend fun deletePendingAutomatic(): Int

    @Query(
        """
        DELETE FROM queued_sms_candidates
        WHERE candidateKey = :candidateKey
          AND origin = 'automatic'
          AND (
            state = 'pending'
            OR (state = 'claimed' AND claimToken = :claimToken)
          )
        """
    )
    suspend fun deleteAutomaticBeforeClaim(
        candidateKey: String,
        claimToken: String
    ): Int

    @Query(
        """
        SELECT candidateKey FROM queued_sms_candidates
        WHERE origin = 'automatic' AND state = 'pending'
        ORDER BY createdAt ASC, candidateKey ASC
        """
    )
    suspend fun getPendingAutomaticKeys(): List<String>

    @Query("SELECT COUNT(*) FROM queued_sms_candidates")
    suspend fun count(): Int

    @Query(
        """
        SELECT COUNT(*) FROM queued_sms_candidates
        WHERE origin = :origin AND state = :state
        """
    )
    suspend fun countByOriginAndState(origin: String, state: String): Int
}
