package com.pocketfinancer.data.db

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.dao.QueuedSmsCandidateDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.db.entity.QueuedSmsCandidateEntity
import com.pocketfinancer.data.db.entity.TransactionEntity
import com.pocketfinancer.data.model.SmsSourceIdentity
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room database with SQLCipher for AES-256 encryption at rest.
 *
 * Every byte written to the SQLite file is encrypted. Its random passphrase is
 * wrapped by Android Keystore-backed encrypted preferences; initialization
 * fails closed if that protection is unavailable.
 */
@Database(
    entities = [
        TransactionEntity::class,
        AccountEntity::class,
        QueuedSmsCandidateEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao
    abstract fun queuedSmsCandidateDao(): QueuedSmsCandidateDao

    companion object {
        private const val DB_NAME = "pocketfinancer.db"
        private const val SQLCIPHER_KDF_ITERATIONS = 256_000

        fun getOrCreatePassphrase(context: Context): ByteArray {
            val encryptedPrefs = try {
                val masterKey = MasterKey.Builder(context)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    context,
                    ".encrypted_db_secrets",
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                )
            } catch (error: Exception) {
                throw IllegalStateException(
                    "Encrypted local storage is unavailable because the " +
                        "Android Keystore could not protect its database key.",
                    error
                )
            }

            val plainPrefs = context.getSharedPreferences(
                ".db_secrets",
                Context.MODE_PRIVATE
            )
            val existingEncrypted =
                encryptedPrefs.getString("db_passphrase", null)
            if (existingEncrypted != null) {
                if (plainPrefs.contains("db_passphrase")) {
                    check(
                        plainPrefs.edit()
                            .remove("db_passphrase")
                            .commit()
                    ) {
                        "Could not erase the legacy plaintext database key."
                    }
                }
                return existingEncrypted.toByteArray(Charsets.UTF_8)
            }

            // Upgrade installations that previously fell back to plaintext:
            // first durably wrap the existing key, then erase that plaintext
            // copy. This keeps the existing encrypted database readable.
            val existingPlain = plainPrefs.getString("db_passphrase", null)
            if (existingPlain != null) {
                check(
                    encryptedPrefs.edit()
                        .putString("db_passphrase", existingPlain)
                        .commit()
                ) {
                    "Could not protect the existing database key."
                }
                check(plainPrefs.edit().remove("db_passphrase").commit()) {
                    "Could not erase the legacy plaintext database key."
                }
                return existingPlain.toByteArray(Charsets.UTF_8)
            }

            val random = ByteArray(32)
            java.security.SecureRandom().nextBytes(random)
            val hex = random.joinToString("") { "%02x".format(it) }
            check(
                encryptedPrefs.edit()
                    .putString("db_passphrase", hex)
                    .commit()
            ) {
                "Could not protect the new database key."
            }
            return hex.toByteArray(Charsets.UTF_8)
        }

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN isEdited INTEGER NOT NULL DEFAULT 0")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE transactions ADD COLUMN slmPromptEvalMs INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN slmEvalMs INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN slmNumTokens INTEGER")
                db.execSQL("ALTER TABLE transactions ADD COLUMN slmModelName TEXT")
            }
        }

        /**
         * Adds durable source identity and the encrypted processing queue.
         *
         * V3 allowed exact duplicate rows. The migration keeps every row: the
         * first occurrence owns the canonical fallback identity, while later
         * exact occurrences receive stable synthetic identities derived from
         * their existing primary keys. A future inbox scan therefore converges
         * on the canonical row without deleting historical user data.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `transactions_v4` (
                        `id` TEXT NOT NULL,
                        `amount` REAL NOT NULL,
                        `merchant` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `type` TEXT NOT NULL,
                        `accountId` TEXT NOT NULL,
                        `rawMessage` TEXT NOT NULL,
                        `sender` TEXT NOT NULL,
                        `isEdited` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        `slmPromptEvalMs` INTEGER,
                        `slmEvalMs` INTEGER,
                        `slmNumTokens` INTEGER,
                        `slmModelName` TEXT,
                        `sourceConnector` TEXT NOT NULL,
                        `sourceProviderMessageId` TEXT,
                        `sourceMessageId` TEXT NOT NULL,
                        `sourceFingerprint` TEXT NOT NULL,
                        `sourceAlternateFingerprint` TEXT,
                        PRIMARY KEY(`id`)
                    )
                    """.trimIndent()
                )

                val claimedFingerprints = mutableSetOf<String>()
                db.query(
                    """
                    SELECT *
                    FROM `transactions`
                    ORDER BY `createdAt` ASC, `id` ASC
                    """.trimIndent()
                ).use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow("id")
                    val amountIndex = cursor.getColumnIndexOrThrow("amount")
                    val merchantIndex = cursor.getColumnIndexOrThrow("merchant")
                    val dateIndex = cursor.getColumnIndexOrThrow("date")
                    val typeIndex = cursor.getColumnIndexOrThrow("type")
                    val accountIdIndex = cursor.getColumnIndexOrThrow("accountId")
                    val rawMessageIndex = cursor.getColumnIndexOrThrow("rawMessage")
                    val senderIndex = cursor.getColumnIndexOrThrow("sender")
                    val isEditedIndex = cursor.getColumnIndexOrThrow("isEdited")
                    val createdAtIndex = cursor.getColumnIndexOrThrow("createdAt")
                    val updatedAtIndex = cursor.getColumnIndexOrThrow("updatedAt")
                    val promptEvalIndex = cursor.getColumnIndexOrThrow("slmPromptEvalMs")
                    val evalIndex = cursor.getColumnIndexOrThrow("slmEvalMs")
                    val tokenIndex = cursor.getColumnIndexOrThrow("slmNumTokens")
                    val modelNameIndex = cursor.getColumnIndexOrThrow("slmModelName")

                    while (cursor.moveToNext()) {
                        val id = cursor.getString(idIndex)
                        val date = cursor.getLong(dateIndex)
                        val sender = cursor.getString(senderIndex)
                        val rawMessage = cursor.getString(rawMessageIndex)
                        val canonical = SmsSourceIdentity.androidSms(
                            providerMessageId = null,
                            sender = sender,
                            body = rawMessage,
                            sourceTimestamp = date,
                            messageType = 1
                        )
                        val sourceIdentity =
                            if (claimedFingerprints.add(canonical.fallbackFingerprint)) {
                                canonical
                            } else {
                                SmsSourceIdentity.legacyDuplicate(canonical, id).also {
                                    check(claimedFingerprints.add(it.fallbackFingerprint)) {
                                        "Could not allocate legacy source identity for $id"
                                    }
                                }
                            }

                        val values = ContentValues().apply {
                            put("id", id)
                            put("amount", cursor.getDouble(amountIndex))
                            put("merchant", cursor.getString(merchantIndex))
                            put("date", date)
                            put("type", cursor.getString(typeIndex))
                            put("accountId", cursor.getString(accountIdIndex))
                            put("rawMessage", rawMessage)
                            put("sender", sender)
                            put("isEdited", cursor.getInt(isEditedIndex))
                            put("createdAt", cursor.getLong(createdAtIndex))
                            put("updatedAt", cursor.getLong(updatedAtIndex))
                            putNullableLong("slmPromptEvalMs", cursor, promptEvalIndex)
                            putNullableLong("slmEvalMs", cursor, evalIndex)
                            putNullableInt("slmNumTokens", cursor, tokenIndex)
                            putNullableString("slmModelName", cursor, modelNameIndex)
                            put("sourceConnector", sourceIdentity.connector)
                            putNull("sourceProviderMessageId")
                            put("sourceMessageId", sourceIdentity.messageId)
                            put(
                                "sourceFingerprint",
                                sourceIdentity.fallbackFingerprint
                            )
                            putNull("sourceAlternateFingerprint")
                        }
                        check(
                            db.insert(
                                "transactions_v4",
                                SQLiteDatabase.CONFLICT_ABORT,
                                values
                            ) != -1L
                        ) {
                            "Failed to preserve transaction $id during migration"
                        }
                    }
                }

                db.execSQL("DROP TABLE `transactions`")
                db.execSQL("ALTER TABLE `transactions_v4` RENAME TO `transactions`")
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_accountId` " +
                        "ON `transactions` (`accountId`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_date` " +
                        "ON `transactions` (`date`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_transactions_type` " +
                        "ON `transactions` (`type`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_transactions_sourceConnector_sourceMessageId` " +
                        "ON `transactions` (`sourceConnector`, `sourceMessageId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_transactions_sourceConnector_sourceFingerprint` " +
                        "ON `transactions` (`sourceConnector`, `sourceFingerprint`)"
                )

                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `queued_sms_candidates` (
                        `candidateKey` TEXT NOT NULL,
                        `sourceConnector` TEXT NOT NULL,
                        `sourceMessageId` TEXT NOT NULL,
                        `sourceFingerprint` TEXT NOT NULL,
                        `sourceAlternateFingerprint` TEXT,
                        `sourceProviderMessageId` TEXT,
                        `sender` TEXT NOT NULL,
                        `rawMessage` TEXT NOT NULL,
                        `date` INTEGER NOT NULL,
                        `sourceTimestamp` INTEGER NOT NULL,
                        `messageType` INTEGER NOT NULL,
                        `origin` TEXT NOT NULL,
                        `state` TEXT NOT NULL,
                        `claimToken` TEXT,
                        `claimedAt` INTEGER,
                        `attemptCount` INTEGER NOT NULL,
                        `lastError` TEXT,
                        `createdAt` INTEGER NOT NULL,
                        `updatedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`candidateKey`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_queued_sms_candidates_sourceConnector_sourceMessageId` " +
                        "ON `queued_sms_candidates` (`sourceConnector`, `sourceMessageId`)"
                )
                db.execSQL(
                    "CREATE UNIQUE INDEX IF NOT EXISTS " +
                        "`index_queued_sms_candidates_sourceConnector_sourceFingerprint` " +
                        "ON `queued_sms_candidates` (`sourceConnector`, `sourceFingerprint`)"
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS " +
                        "`index_queued_sms_candidates_origin_state` " +
                        "ON `queued_sms_candidates` (`origin`, `state`)"
                )
            }
        }

        private fun ContentValues.putNullableLong(
            column: String,
            cursor: android.database.Cursor,
            index: Int
        ) {
            if (cursor.isNull(index)) putNull(column) else put(column, cursor.getLong(index))
        }

        private fun ContentValues.putNullableInt(
            column: String,
            cursor: android.database.Cursor,
            index: Int
        ) {
            if (cursor.isNull(index)) putNull(column) else put(column, cursor.getInt(index))
        }

        private fun ContentValues.putNullableString(
            column: String,
            cursor: android.database.Cursor,
            index: Int
        ) {
            if (cursor.isNull(index)) putNull(column) else put(column, cursor.getString(index))
        }
    }

    /**
     * Module-level provider for Hilt.
     */
    @Singleton
    class Factory @Inject constructor(
        @ApplicationContext private val context: Context
    ) {
        fun create(): AppDatabase {
            System.loadLibrary("sqlcipher")
            val passphrase = getOrCreatePassphrase(context)
            val factory = SupportOpenHelperFactory(passphrase)

            val builder = Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

            return builder.build()
        }
    }
}
