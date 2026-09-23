package com.pocketfinancer.ui.smsprocessing

import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.AccountEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Explicit emulator-only audit. Default instrumentation runs skip production app data. */
@RunWith(AndroidJUnit4::class)
class SmsSyntheticRuntimeAuditTest {
    @Test
    fun inspectSyntheticEmulatorState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("smsSyntheticAudit") == "true")
        val database = AppDatabase.Factory(
            instrumentation.targetContext.applicationContext
        ).create()
        try {
            inspectDatabase(database, arguments, instrumentation)
        } finally {
            database.close()
        }
    }

    private suspend fun inspectDatabase(
        database: AppDatabase,
        arguments: Bundle,
        instrumentation: android.app.Instrumentation,
    ) {
        if (arguments.getString("seedHdfc1234Account") == "true") {
            val name = "HDFC Bank A/c XX1234"
            if (database.accountDao().findByName(name) == null) {
                database.accountDao().insert(
                    AccountEntity(
                        id = UUID.nameUUIDFromBytes(
                            "synthetic-emulator-hdfc-1234".toByteArray()
                        ).toString(),
                        name = name,
                        bank = "HDFC Bank",
                        type = "synthetic-test"
                    )
                )
            }
        }

        val sql = database.openHelper.readableDatabase
        val operationStates = mutableListOf<String>()
        sql.query(
            "SELECT state, COUNT(*) FROM sms_processing_operations GROUP BY state ORDER BY state"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                operationStates += "${cursor.getString(0)}:${cursor.getInt(1)}"
            }
        }
        fun grouped(query: String): String = sql.query(query).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add("${cursor.getString(0)}:${cursor.getInt(1)}")
                }
            }.joinToString(",")
        }
        fun count(table: String): Int = sql.query("SELECT COUNT(*) FROM $table").use {
            cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getInt(0)
        }
        val partialFields = mutableMapOf<String, Int>()
        sql.query("SELECT fieldEvidenceJson FROM sms_review_case_v2_extensions")
            .use { cursor ->
                while (cursor.moveToNext()) {
                    val fields = JSONArray(cursor.getString(0))
                    for (index in 0 until fields.length()) {
                        val field = fields.getJSONObject(index)
                        val category = listOf(
                            field.getString("origin"),
                            field.getString("field"),
                            field.getString("validation_state")
                        ).joinToString("/")
                        partialFields[category] = (partialFields[category] ?: 0) + 1
                    }
                }
            }
        val summary = listOf(
            "operations=${operationStates.joinToString(",")}",
            "triggers=${grouped("SELECT trigger, COUNT(*) FROM sms_processing_operations GROUP BY trigger ORDER BY trigger")}",
            "attempts=${count("sms_selector_attempts")}",
            "attemptCompletions=${grouped("SELECT completion, COUNT(*) FROM sms_selector_attempts GROUP BY completion ORDER BY completion")}",
            "reviews=${count("sms_review_cases")}",
            "partialFields=${partialFields.toSortedMap().entries.joinToString(",") { "${it.key}:${it.value}" }}",
            "reviewReasons=${grouped("SELECT reasonCodesJson, COUNT(*) FROM sms_review_cases GROUP BY reasonCodesJson ORDER BY reasonCodesJson")}",
            "transactions=${count("transactions")}",
            "queued=${count("queued_sms_candidates")}",
            "accounts=${count("accounts")}",
        ).joinToString(" ")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", summary) })
    }
}
