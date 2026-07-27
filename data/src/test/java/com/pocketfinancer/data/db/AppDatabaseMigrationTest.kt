package com.pocketfinancer.data.db

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.data.repository.SmsIngestionRepository
import kotlinx.coroutines.runBlocking
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppDatabaseMigrationTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun `v3 to v5 preserves exact duplicates with stable unique identities`() {
        migrationHelper.createDatabase(V3_DATABASE_NAME, 3).apply {
            insertV3Transaction(
                id = "tx-a",
                rawMessage = "Rs 500 debited at Merchant",
                createdAt = 10L
            )
            insertV3Transaction(
                id = "tx-b",
                rawMessage = "Rs 500 debited at Merchant",
                createdAt = 20L
            )
            insertV3Transaction(
                id = "tx-c",
                rawMessage = "Rs 700 debited at Another Merchant",
                createdAt = 30L
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            V3_DATABASE_NAME,
            5,
            true,
            AppDatabase.MIGRATION_3_4,
            AppDatabase.MIGRATION_4_5
        )

        migrated.query(
            """
            SELECT id, rawMessage, sourceMessageId, sourceFingerprint
            FROM transactions
            ORDER BY id
            """.trimIndent()
        ).use { cursor ->
            assertEquals(3, cursor.count)
            val identities = mutableSetOf<String>()
            val bodies = mutableListOf<String>()
            var legacyDuplicates = 0
            while (cursor.moveToNext()) {
                bodies += cursor.getString(1)
                identities += cursor.getString(2)
                if (cursor.getString(2).startsWith("legacy_duplicate:")) {
                    legacyDuplicates += 1
                }
            }
            assertEquals(3, identities.size)
            assertEquals(1, legacyDuplicates)
            assertEquals(
                2,
                bodies.count { it == "Rs 500 debited at Merchant" }
            )
        }
        assertIndexUnique(
            database = migrated,
            table = "transactions",
            index = "index_transactions_sourceConnector_sourceMessageId",
            expectedUnique = true
        )
        assertIndexUnique(
            database = migrated,
            table = "transactions",
            index = "index_transactions_sourceConnector_sourceFingerprint",
            expectedUnique = false
        )
        migrated.query(
            """
            SELECT COUNT(*)
            FROM sqlite_master
            WHERE type = 'table' AND name = 'queued_sms_candidates'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
        }
        migrated.close()

        // V3 only had the provider's received date. A new provider row uses
        // DATE_SENT canonically but must still resolve this migrated row through
        // its received-date compatibility fingerprint.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            V3_DATABASE_NAME
        )
            .allowMainThreadQueries()
            .build()
        try {
            runBlocking {
                val providerIdentity = SmsSourceIdentity.androidSms(
                    providerMessageId = "provider-991",
                    sender = TEST_SENDER,
                    body = DUPLICATE_BODY,
                    sourceTimestamp = SENT_AT,
                    messageType = 1,
                    receivedTimestamp = RECEIVED_AT
                )
                val ingestionRepository = SmsIngestionRepository(
                    database,
                    database.transactionDao(),
                    database.queuedSmsCandidateDao()
                )

                val admission = ingestionRepository.admit(
                    SmsIngestionRepository.NewCandidate(
                        sourceIdentity = providerIdentity,
                        sender = TEST_SENDER,
                        rawMessage = DUPLICATE_BODY,
                        date = RECEIVED_AT,
                        sourceTimestamp = SENT_AT,
                        messageType = 1,
                        origin = SmsCandidateOrigin.MANUAL
                    )
                )

                assertEquals(
                    "tx-a",
                    assertIs<SmsIngestionRepository.AdmissionResult.AlreadySaved>(
                        admission
                    ).transactionId
                )
                assertEquals(3, database.transactionDao().count())
                assertEquals(0, database.queuedSmsCandidateDao().count())
                val canonical = assertNotNull(
                    database.transactionDao().getById("tx-a")
                )
                assertEquals(
                    providerIdentity.fallbackFingerprint,
                    canonical.sourceAlternateFingerprint
                )
                assertEquals(
                    "provider-991",
                    canonical.sourceProviderMessageId
                )
                assertEquals(providerIdentity.messageId, canonical.sourceMessageId)
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun `v4 to v5 validates and preserves duplicate legacy provenance and queue keys`() {
        migrationHelper.createDatabase(V4_DATABASE_NAME, 4).apply {
            insertV4Transaction(
                id = "legacy-provider-a",
                sourceProviderMessageId = "same-provider-id",
                sourceMessageId = "fallback:legacy-a",
                sourceFingerprint = "legacy-fingerprint-a"
            )
            insertV4Transaction(
                id = "legacy-provider-b",
                sourceProviderMessageId = "same-provider-id",
                sourceMessageId = "fallback:legacy-b",
                sourceFingerprint = "legacy-fingerprint-b"
            )
            insertV4Candidate(
                candidateKey = "sms_existing_workmanager_key",
                sourceProviderMessageId = "queued-provider-id",
                sourceMessageId = "fallback:queued-v4",
                sourceFingerprint = "queued-v4-fingerprint"
            )
            close()
        }

        val migrated = migrationHelper.runMigrationsAndValidate(
            V4_DATABASE_NAME,
            5,
            true,
            AppDatabase.MIGRATION_4_5
        )

        migrated.query(
            """
            SELECT COUNT(*), COUNT(DISTINCT id)
            FROM transactions
            WHERE sourceProviderMessageId = 'same-provider-id'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
            assertEquals(2, cursor.getInt(1))
        }
        migrated.query(
            """
            SELECT sourceMessageId, sourceFingerprint
            FROM queued_sms_candidates
            WHERE candidateKey = 'sms_existing_workmanager_key'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("fallback:queued-v4", cursor.getString(0))
            assertEquals("queued-v4-fingerprint", cursor.getString(1))
        }
        assertIndexUnique(
            database = migrated,
            table = "transactions",
            index = "index_transactions_sourceConnector_sourceFingerprint",
            expectedUnique = false
        )

        // V5 permits exact-identical evidence when authoritative ids differ.
        migrated.insertV4Transaction(
            id = "provider-exact-a",
            sourceProviderMessageId = "provider-a",
            sourceMessageId = "provider:provider-a",
            sourceFingerprint = "exact-shared-fingerprint"
        )
        migrated.insertV4Transaction(
            id = "provider-exact-b",
            sourceProviderMessageId = "provider-b",
            sourceMessageId = "provider:provider-b",
            sourceFingerprint = "exact-shared-fingerprint"
        )
        migrated.query(
            """
            SELECT COUNT(*)
            FROM transactions
            WHERE sourceFingerprint = 'exact-shared-fingerprint'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
        migrated.close()
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertV3Transaction(
        id: String,
        rawMessage: String,
        createdAt: Long
    ) {
        execSQL(
            """
            INSERT INTO transactions (
                id, amount, merchant, date, type, accountId, rawMessage, sender,
                isEdited, createdAt, updatedAt, slmPromptEvalMs, slmEvalMs,
                slmNumTokens, slmModelName
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                id,
                500.0,
                "Merchant",
                RECEIVED_AT,
                "debit",
                "account-id",
                rawMessage,
                "AX-HDFCBK",
                0,
                createdAt,
                createdAt,
                null,
                null,
                null,
                null
            )
        )
    }

    private fun assertIndexUnique(
        database: androidx.sqlite.db.SupportSQLiteDatabase,
        table: String,
        index: String,
        expectedUnique: Boolean
    ) {
        database.query("PRAGMA index_list(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val uniqueIndex = cursor.getColumnIndexOrThrow("unique")
            var actual: Boolean? = null
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIndex) == index) {
                    actual = cursor.getInt(uniqueIndex) == 1
                    break
                }
            }
            assertNotNull(actual, "Missing index $index")
            assertEquals(expectedUnique, actual)
        }
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertV4Transaction(
        id: String,
        sourceProviderMessageId: String?,
        sourceMessageId: String,
        sourceFingerprint: String
    ) {
        execSQL(
            """
            INSERT INTO transactions (
                id, amount, merchant, date, type, accountId, rawMessage, sender,
                isEdited, createdAt, updatedAt, slmPromptEvalMs, slmEvalMs,
                slmNumTokens, slmModelName, sourceConnector,
                sourceProviderMessageId, sourceMessageId, sourceFingerprint,
                sourceAlternateFingerprint
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                id,
                500.0,
                "Merchant",
                RECEIVED_AT,
                "debit",
                "account-id",
                DUPLICATE_BODY,
                TEST_SENDER,
                0,
                10L,
                10L,
                null,
                null,
                null,
                null,
                SmsSourceIdentity.ANDROID_SMS_CONNECTOR,
                sourceProviderMessageId,
                sourceMessageId,
                sourceFingerprint,
                null
            )
        )
    }

    private fun androidx.sqlite.db.SupportSQLiteDatabase.insertV4Candidate(
        candidateKey: String,
        sourceProviderMessageId: String?,
        sourceMessageId: String,
        sourceFingerprint: String
    ) {
        execSQL(
            """
            INSERT INTO queued_sms_candidates (
                candidateKey, sourceConnector, sourceMessageId,
                sourceFingerprint, sourceAlternateFingerprint,
                sourceProviderMessageId, sender, rawMessage, date,
                sourceTimestamp, messageType, origin, state, claimToken,
                claimedAt, attemptCount, lastError, createdAt, updatedAt
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            arrayOf<Any?>(
                candidateKey,
                SmsSourceIdentity.ANDROID_SMS_CONNECTOR,
                sourceMessageId,
                sourceFingerprint,
                null,
                sourceProviderMessageId,
                TEST_SENDER,
                DUPLICATE_BODY,
                RECEIVED_AT,
                SENT_AT,
                1,
                SmsCandidateOrigin.AUTOMATIC.persistedValue,
                "pending",
                null,
                null,
                0,
                null,
                10L,
                10L
            )
        )
    }

    private companion object {
        const val V3_DATABASE_NAME = "migration-v3-v5"
        const val V4_DATABASE_NAME = "migration-v4-v5"
        const val TEST_SENDER = "AX-HDFCBK"
        const val DUPLICATE_BODY = "Rs 500 debited at Merchant"
        const val SENT_AT = 1_200_000L
        const val RECEIVED_AT = 1_234_567L
    }
}
