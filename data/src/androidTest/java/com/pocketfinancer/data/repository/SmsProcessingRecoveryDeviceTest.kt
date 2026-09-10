package com.pocketfinancer.data.repository

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.AdmittedSmsSourceEntity
import com.pocketfinancer.data.db.entity.SmsProcessingOperationEntity
import java.util.UUID
import kotlinx.coroutines.runBlocking
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Uses a separate encrypted test database; never opens the installed app's data. */
@RunWith(AndroidJUnit4::class)
class SmsProcessingRecoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "sms-recovery-${UUID.randomUUID()}.db"
    private val key = UUID.randomUUID().toString().toByteArray()
    private lateinit var database: AppDatabase
    private lateinit var store: SmsProcessingStore

    @Before fun setUp() {
        System.loadLibrary("sqlcipher")
        open()
    }

    private fun open() {
        database = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf())).build()
        store = SmsProcessingStore(database, database.smsProcessingDao())
    }

    @After fun tearDown() {
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun expiredClaimRecoversAfterReopenAndFencesLateWriter() = runBlocking {
        val operation = admit()
        val claim = store.claim(operation.id, 1_001)
        database.close()
        open()
        val now = claim.expiresAt + 1
        assertEquals(1, store.recoverExpiredOperations(now))
        assertEquals(0, store.recoverExpiredOperations(now))
        val review = database.smsProcessingDao().getReviewCaseForOperation(operation.id)
        assertNotNull(review)
        assertEquals("open", review!!.state)
        assertEquals("Invented payment alert for recovery testing.", store.sourceEvidence(operation.sourceId).body)
        assertTrue(runCatching { store.recordReconstruction(claim, "{}", now) }.isFailure)
        assertNull(database.smsProcessingDao().getReconstructedResult(operation.id))
        database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM transactions").use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    @Test fun liveHeartbeatPreventsRecovery() = runBlocking {
        val operation = admit()
        val claim = store.claim(operation.id, 1_001)
        val renewed = store.heartbeat(claim, claim.expiresAt - 1)
        assertEquals(0, store.recoverExpiredOperations(claim.expiresAt + 1))
        assertNull(database.smsProcessingDao().getReviewCaseForOperation(operation.id))
        assertEquals(1, store.recoverExpiredOperations(renewed.expiresAt + 1))
    }

    @Test fun draftAndIdempotentFeedbackSurviveEncryptedReopen() = runBlocking {
        val operation = admit()
        val claim = store.claim(operation.id, 1_001)
        val reviewId = store.retainForReview(claim, listOf("runtime_unavailable"), 1_002)
        val command = SmsReviewCommand(
            UUID.randomUUID().toString(), reviewId, 0, SmsReviewAction.SAVE_DRAFT,
            listOf(SmsFieldCorrection("amount_minor_units",
                SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE,
                null, null, null, "12345"))
        )
        reviewRepository().resolve(command, 1_003)
        database.close()
        open()
        val details = reviewRepository().details(reviewId)
        assertEquals("draft", details.reviewCase.state)
        assertTrue(details.reviewCase.draftJson!!.contains("12345"))
        assertEquals(1L, details.reviewCase.revision)
        assertTrue(reviewRepository().resolve(command, 1_004).replayed)
        assertEquals(1, reviewRepository().details(reviewId).feedback.size)
        assertTrue(runCatching {
            reviewRepository().resolve(command.copy(actionId = UUID.randomUUID().toString()), 1_005)
        }.isFailure)
    }

    @Test fun completeManualCorrectionDoesNotRequireModelProposal() = runBlocking {
        val operation = admit()
        val claim = store.claim(operation.id, 1_001)
        val reviewId = store.retainForReview(claim, listOf("runtime_unavailable"), 1_002)
        val accountId = UUID.randomUUID().toString()
        database.accountDao().insert(com.pocketfinancer.data.db.entity.AccountEntity(
            accountId, "Invented account", "Test bank", "manual"))
        val values = mapOf("amount_minor_units" to "12345", "currency" to "\"INR\"",
            "direction" to "\"debit\"", "counterparty" to "\"Invented shop\"",
            "account_id" to "\"$accountId\"", "occurred_at_epoch_ms" to "1000")
        val command = SmsReviewCommand(UUID.randomUUID().toString(), reviewId, 0,
            SmsReviewAction.CORRECT, values.map { (field, value) ->
                SmsFieldCorrection(field, SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE,
                    null, null, null, value)
            })
        // Partial correction must remain uncommitted, with no feedback revision advance.
        assertTrue(runCatching {
            reviewRepository().resolve(command.copy(corrections = command.corrections.dropLast(1)), 1_003)
        }.isFailure)
        assertEquals(0L, reviewRepository().details(reviewId).reviewCase.revision)
        assertTrue(runCatching {
            reviewRepository().resolve(command.copy(action = SmsReviewAction.CONFIRM,
                corrections = emptyList()), 1_003)
        }.isFailure)
        listOf("1.5", "9223372036854775808", "\"12345\"").forEach { invalidAmount ->
            assertTrue(runCatching {
                reviewRepository().resolve(command.copy(corrections = command.corrections.map {
                    if (it.field == "amount_minor_units") it.copy(newValueJson = invalidAmount) else it
                }), 1_003)
            }.isFailure)
        }
        assertTrue(runCatching {
            reviewRepository().resolve(command.copy(corrections = command.corrections + command.corrections.first()), 1_003)
        }.isFailure)
        assertEquals(0L, reviewRepository().details(reviewId).reviewCase.revision)
        reviewRepository().resolve(command, 1_004)
        val transaction = database.transactionDao().getBySourceEvent(operation.sourceId, operation.stableEventId)!!
        assertEquals(12345L, transaction.exactMinorUnits)
        assertEquals("INR", transaction.currencyCode)
        assertEquals("user_supplied", transaction.currencyProvenance)
        assertEquals("user_corrected_time", transaction.timestampProvenance)
        assertNull(database.smsProcessingDao().getReconstructedResult(operation.id))
        assertEquals(1, database.transactionRevisionDao().getHistory(transaction.id).size)
        assertTrue(reviewRepository().resolve(command, 1_005).replayed)
        assertEquals(1, database.transactionRevisionDao().getHistory(transaction.id).size)
    }
    private fun reviewRepository() = SmsReviewRepository(database, database.smsProcessingDao(),
        database.transactionDao(), database.transactionRevisionDao(), database.accountDao())

    private suspend fun admit(): SmsProcessingOperationEntity {
        val sourceId = UUID.randomUUID().toString()
        store.admitSource(AdmittedSmsSourceEntity(sourceId, "synthetic", sourceId, null,
            SmsProcessingStore.sha256(sourceId), null, "TEST", "Invented payment alert for recovery testing.",
            1_000, 1, "diagnostic", UUID.randomUUID().toString(), 1_000, "retained"))
        return SmsProcessingOperationEntity(UUID.randomUUID().toString(), sourceId, null,
            UUID.randomUUID().toString(), "diagnostic", "{}", SmsProcessingStore.sha256("{}"),
            "native-integration-v1", "admitted", 0, null, 0, null, 1_000, 1_000, null, null, 0)
            .also { store.createOperation(it) }
    }
}
