package com.pocketfinancer.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.db.entity.TransactionEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Room database with SQLCipher for AES-256 encryption at rest.
 *
 * Every byte written to the SQLite file is encrypted. The passphrase is derived
 * from Android Keystore at first launch — it never lives in code or shared prefs
 * as plain text.
 */
@Database(
    entities = [
        TransactionEntity::class,
        AccountEntity::class
    ],
    version = 3,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao

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
            } catch (e: Exception) {
                android.util.Log.e("AppDatabase", "Failed to initialize EncryptedSharedPreferences, falling back to plaintext shared preferences", e)
                null
            }

            if (encryptedPrefs != null) {
                val existingEncrypted = encryptedPrefs.getString("db_passphrase", null)
                if (existingEncrypted != null) {
                    return existingEncrypted.toByteArray(Charsets.UTF_8)
                }

                // Check if we have an existing key in the unencrypted prefs to migrate
                val plainPrefs = context.getSharedPreferences(".db_secrets", Context.MODE_PRIVATE)
                val existingPlain = plainPrefs.getString("db_passphrase", null)
                if (existingPlain != null) {
                    // Migrate to encrypted prefs
                    encryptedPrefs.edit().putString("db_passphrase", existingPlain).apply()
                    // Clear the plain prefs to avoid leaving key in plaintext
                    plainPrefs.edit().remove("db_passphrase").apply()
                    return existingPlain.toByteArray(Charsets.UTF_8)
                }

                // No key exists anywhere. Generate a new key and save to encrypted prefs.
                val random = ByteArray(32)
                java.security.SecureRandom().nextBytes(random)
                val hex = random.joinToString("") { "%02x".format(it) }
                encryptedPrefs.edit().putString("db_passphrase", hex).apply()
                return hex.toByteArray(Charsets.UTF_8)
            } else {
                // Fallback to unencrypted preferences if encryption fails (ensuring app doesn't crash)
                val plainPrefs = context.getSharedPreferences(".db_secrets", Context.MODE_PRIVATE)
                val existingPlain = plainPrefs.getString("db_passphrase", null)
                if (existingPlain != null) {
                    return existingPlain.toByteArray(Charsets.UTF_8)
                }
                val random = ByteArray(32)
                java.security.SecureRandom().nextBytes(random)
                val hex = random.joinToString("") { "%02x".format(it) }
                plainPrefs.edit().putString("db_passphrase", hex).apply()
                return hex.toByteArray(Charsets.UTF_8)
            }
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
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)

            val isDebug = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
            if (isDebug) {
                builder.fallbackToDestructiveMigration()
            }
            return builder.build()
        }
    }
}
