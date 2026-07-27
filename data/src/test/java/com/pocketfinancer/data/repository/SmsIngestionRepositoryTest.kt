package com.pocketfinancer.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.data.model.TransactionType
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SmsIngestionRepositoryTest {
    private lateinit var database: AppDatabase
    private lateinit var ingestionRepository: SmsIngestionRepository
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var accountRepository: AccountRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        ingestionRepository = SmsIngestionRepository(
            database,
            database.transactionDao(),
            database.queuedSmsCandidateDao()
        )
        accountRepository = AccountRepository(
            database.accountDao(),
            database.transactionDao(),
            runConsolidationOnInit = false
        )
        transactionRepository = TransactionRepository(
            database,
            database.transactionDao(),
            accountRepository,
            database.queuedSmsCandidateDao()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun `provider row and broadcast fallback converge while provider id is preserved`() =
        runBlocking {
            val sentAt = TEST_DATE
            val receivedAt = TEST_DATE + 500L
            val broadcast = candidate(
                providerId = null,
                date = sentAt,
                sourceTimestamp = sentAt
            )
            val provider = candidate(
                providerId = "991",
                date = receivedAt,
                sourceTimestamp = sentAt
            )

            val first = ingestionRepository.admit(broadcast)
            val second = ingestionRepository.admit(provider)

            assertEquals(first.candidateKey, second.candidateKey)
            assertEquals(1, ingestionRepository.pendingCount())
            assertEquals(
                "991",
                ingestionRepository.get(first.candidateKey)
                    ?.sourceIdentity
                    ?.providerMessageId
            )
            assertEquals(
                provider.sourceIdentity.alternateFingerprint,
                ingestionRepository.get(first.candidateKey)
                    ?.sourceIdentity
                    ?.alternateFingerprint
            )
            assertEquals(
                receivedAt,
                ingestionRepository.get(first.candidateKey)?.date
            )
        }

    @Test
    fun `concurrent retries admit one durable candidate`() = runBlocking {
        val attempts = coroutineScope {
            List(12) {
                async(Dispatchers.Default) {
                    ingestionRepository.admit(candidate(providerId = "42"))
                }
            }.awaitAll()
        }

        assertEquals(1, ingestionRepository.pendingCount())
        assertEquals(1, attempts.map { it.candidateKey }.distinct().size)
    }

    @Test
    fun `same sender and timestamp with different evidence remains legitimate`() =
        runBlocking {
            val first = candidate(
                providerId = "100",
                body = "Rs 100 debited at Merchant A"
            )
            val second = candidate(
                providerId = "101",
                body = "Rs 200 debited at Merchant B"
            )

            ingestionRepository.admit(first)
            ingestionRepository.admit(second)

            assertEquals(2, ingestionRepository.pendingCount())
            assertTrue(
                first.sourceIdentity.fallbackFingerprint !=
                    second.sourceIdentity.fallbackFingerprint
            )
        }

    @Test
    fun `automatic off removes pending but preserves claimed and manual candidates`() =
        runBlocking {
            val claimedAdmission = ingestionRepository.admit(
                candidate(providerId = "1", body = "Rs 1 debited")
            )
            ingestionRepository.admit(
                candidate(providerId = "2", body = "Rs 2 debited")
            )
            val manual = candidate(
                providerId = "3",
                body = "Rs 3 debited",
                origin = SmsCandidateOrigin.MANUAL
            )
            val manualAdmission = ingestionRepository.admit(manual)
            assertNotNull(
                ingestionRepository.claim(
                    claimedAdmission.candidateKey,
                    claimToken = "running-worker"
                )
            )

            assertEquals(1, ingestionRepository.discardPendingAutomatic())

            assertNotNull(ingestionRepository.get(claimedAdmission.candidateKey))
            assertNotNull(ingestionRepository.get(manualAdmission.candidateKey))
            assertEquals(2, ingestionRepository.pendingCount())
        }

    @Test
    fun `startup recovery lists only pending automatic candidates`() =
        runBlocking {
            val first = ingestionRepository.admit(
                candidate(providerId = "recovery-1", body = "Rs 1 debited")
            )
            val claimed = ingestionRepository.admit(
                candidate(providerId = "recovery-2", body = "Rs 2 debited")
            )
            ingestionRepository.admit(
                candidate(
                    providerId = "recovery-manual",
                    body = "Rs 3 debited",
                    origin = SmsCandidateOrigin.MANUAL
                )
            )
            val last = ingestionRepository.admit(
                candidate(providerId = "recovery-3", body = "Rs 4 debited")
            )
            assertNotNull(
                ingestionRepository.claim(
                    candidateKey = claimed.candidateKey,
                    claimToken = "already-running"
                )
            )

            assertEquals(
                setOf(first.candidateKey, last.candidateKey),
                ingestionRepository.pendingAutomaticCandidateKeys().toSet()
            )
        }

    @Test
    fun `claim-owned discard cannot delete a replacement claim`() = runBlocking {
        val admitted = ingestionRepository.admit(
            candidate(providerId = "claim-boundary")
        )
        assertNotNull(
            ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "first",
                now = 1_000L
            )
        )
        assertNotNull(
            ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "replacement",
                now = 1_000L +
                    SmsIngestionRepository.DEFAULT_STALE_CLAIM_MS +
                    1L
            )
        )

        assertFalse(
            ingestionRepository.discardClaimed(
                candidateKey = admitted.candidateKey,
                claimToken = "first"
            )
        )
        assertFalse(
            ingestionRepository.discardAutomaticBeforeClaim(
                candidateKey = admitted.candidateKey,
                claimToken = "first"
            )
        )
        assertNotNull(ingestionRepository.get(admitted.candidateKey))
        assertTrue(
            ingestionRepository.discardClaimed(
                candidateKey = admitted.candidateKey,
                claimToken = "replacement"
            )
        )
        assertNull(ingestionRepository.get(admitted.candidateKey))
    }

    @Test
    fun `retry release and process restart claim never duplicate the candidate`() =
        runBlocking {
            val admitted = ingestionRepository.admit(candidate(providerId = "9"))
            val firstClaim = ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "work-id",
                now = 10_000L
            )
            assertNotNull(firstClaim)
            assertTrue(
                ingestionRepository.releaseForRetry(
                    candidateKey = admitted.candidateKey,
                    claimToken = "work-id",
                    error = "retry"
                )
            )

            val retryClaim = ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "work-id",
                now = 11_000L
            )
            assertNotNull(retryClaim)
            assertEquals(2, retryClaim.attemptCount)

            val competingClaim = ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "different-work",
                now = 11_001L
            )
            assertNull(competingClaim)
            val recoveredAfterProcessDeath = ingestionRepository.claim(
                candidateKey = admitted.candidateKey,
                claimToken = "replacement-work",
                now = 11_000L +
                    SmsIngestionRepository.DEFAULT_STALE_CLAIM_MS +
                    1L
            )
            assertNotNull(recoveredAfterProcessDeath)
            assertEquals("replacement-work", recoveredAfterProcessDeath.claimToken)
            assertEquals(1, ingestionRepository.pendingCount())
        }

    @Test
    fun `concurrent transaction persistence inserts once and consumes candidate`() =
        runBlocking {
            val admitted = ingestionRepository.admit(candidate(providerId = "77"))
            val account = accountRepository.ensureDefault()
            val source = candidate(providerId = "77").sourceIdentity
            val newTransaction = TransactionRepository.NewTransaction(
                amount = 500.0,
                merchant = "Merchant",
                date = TEST_DATE,
                type = TransactionType.DEBIT,
                accountId = account.id,
                rawMessage = TEST_BODY,
                sender = TEST_SENDER,
                sourceIdentity = source
            )

            val results = coroutineScope {
                List(8) {
                    async(Dispatchers.Default) {
                        transactionRepository.insertIfAbsent(newTransaction)
                    }
                }.awaitAll()
            }

            assertEquals(1, results.count { it.inserted })
            assertEquals(1, transactionRepository.count())
            assertNull(ingestionRepository.get(admitted.candidateKey))
            assertTrue(transactionRepository.exists(source))
        }

    @Test
    fun `edits and repository restart preserve raw evidence and source identity`() =
        runBlocking {
            val account = accountRepository.ensureDefault()
            val source = SmsSourceIdentity.androidSms(
                providerMessageId = "88",
                sender = TEST_SENDER,
                body = TEST_BODY,
                sourceTimestamp = TEST_DATE,
                messageType = 1
            )
            val saved = transactionRepository.insert(
                TransactionRepository.NewTransaction(
                    amount = 500.0,
                    merchant = "Before",
                    date = TEST_DATE,
                    type = TransactionType.DEBIT,
                    accountId = account.id,
                    rawMessage = TEST_BODY,
                    sender = TEST_SENDER,
                    sourceIdentity = source
                )
            )

            val edited = transactionRepository.updateTransaction(
                id = saved.id,
                amount = 600.0,
                merchant = "After",
                type = TransactionType.CREDIT,
                accountId = account.id
            )
            val restartedRepository = TransactionRepository(
                database,
                database.transactionDao(),
                accountRepository,
                database.queuedSmsCandidateDao()
            )
            val reloaded = restartedRepository.findBySource(source)

            assertNotNull(edited)
            assertNotNull(reloaded)
            assertEquals(TEST_BODY, reloaded.rawMessage)
            assertEquals(TEST_SENDER, reloaded.sender)
            assertEquals(source, reloaded.sourceIdentity)
            assertTrue(reloaded.isEdited)
            assertFalse(reloaded.merchant == "Before")
        }

    @Test
    fun `explicit local data erase removes ledger evidence and queued candidates`() =
        runBlocking {
            val account = accountRepository.ensureDefault()
            val savedSource = candidate(providerId = "saved").sourceIdentity
            transactionRepository.insert(
                TransactionRepository.NewTransaction(
                    amount = 500.0,
                    merchant = "Merchant",
                    date = TEST_DATE,
                    type = TransactionType.DEBIT,
                    accountId = account.id,
                    rawMessage = TEST_BODY,
                    sender = TEST_SENDER,
                    sourceIdentity = savedSource
                )
            )
            ingestionRepository.admit(
                candidate(
                    providerId = "queued",
                    body = "Rs 700 credited at Another Merchant"
                )
            )

            transactionRepository.clearDatabase()

            assertEquals(0, transactionRepository.count())
            assertEquals(0, ingestionRepository.pendingCount())
            assertNull(transactionRepository.findBySource(savedSource))
        }

    private fun candidate(
        providerId: String?,
        body: String = TEST_BODY,
        origin: SmsCandidateOrigin = SmsCandidateOrigin.AUTOMATIC,
        date: Long = TEST_DATE,
        sourceTimestamp: Long = date
    ): SmsIngestionRepository.NewCandidate {
        val source = SmsSourceIdentity.androidSms(
            providerMessageId = providerId,
            sender = TEST_SENDER,
            body = body,
            sourceTimestamp = sourceTimestamp,
            messageType = 1,
            receivedTimestamp = date
        )
        return SmsIngestionRepository.NewCandidate(
            sourceIdentity = source,
            sender = TEST_SENDER,
            rawMessage = body,
            date = date,
            sourceTimestamp = sourceTimestamp,
            messageType = 1,
            origin = origin
        )
    }

    private companion object {
        const val TEST_SENDER = "AX-HDFCBK"
        const val TEST_BODY = "Rs 500 debited at Merchant"
        const val TEST_DATE = 1_234_567L
    }
}
