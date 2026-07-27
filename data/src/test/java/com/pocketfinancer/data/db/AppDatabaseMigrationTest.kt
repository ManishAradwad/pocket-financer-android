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
    fun `v3 to v4 preserves exact duplicates with stable unique identities`() {
        migrationHelper.createDatabase(DATABASE_NAME, 3).apply {
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
            DATABASE_NAME,
            4,
            true,
            AppDatabase.MIGRATION_3_4
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
        migrated.query(
            """
            SELECT COUNT(*)
            FROM sqlite_master
            WHERE type = 'index'
              AND name IN (
                'index_transactions_sourceConnector_sourceMessageId',
                'index_transactions_sourceConnector_sourceFingerprint'
              )
              AND sql LIKE 'CREATE UNIQUE INDEX%'
            """.trimIndent()
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(2, cursor.getInt(0))
        }
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
            DATABASE_NAME
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
            }
        } finally {
            database.close()
        }
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
            arrayOf(
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

    private companion object {
        const val DATABASE_NAME = "migration-v3-v4"
        const val TEST_SENDER = "AX-HDFCBK"
        const val DUPLICATE_BODY = "Rs 500 debited at Merchant"
        const val SENT_AT = 1_200_000L
        const val RECEIVED_AT = 1_234_567L
    }
}
