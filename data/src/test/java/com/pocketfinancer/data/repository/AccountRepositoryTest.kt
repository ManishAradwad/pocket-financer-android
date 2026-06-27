package com.pocketfinancer.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.model.TransactionType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class AccountRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: AccountRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = AccountRepository(db.accountDao(), db.transactionDao(), runConsolidationOnInit = false)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getOrCreate should create new account when not found`() {
        runBlocking {
            val account = repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            assertEquals("HDFC Bank A/c XX6254", account.name)
            assertEquals("HDFC Bank", account.bank)
            assertNotNull(account.id)
        }
    }

    @Test
    fun `getOrCreate should return existing account when found`() {
        runBlocking {
            val first = repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            val second = repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            assertEquals(first.id, second.id)
        }
    }

    @Test
    fun `getOrCreate should not match on name alone without matching bank`() {
        runBlocking {
            val first = repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            val second = repo.getOrCreate("A/c XX6254", "SBI", "auto-extracted")
            assertNotEquals(first.id, second.id)
        }
    }

    @Test
    fun `ensureDefault should create UNKNOWN account on first call`() {
        runBlocking {
            val account = repo.ensureDefault()
            assertEquals("__UNKNOWN__", account.name)
            assertEquals("Unknown Bank", account.bank)
            assertEquals("auto-extracted", account.type)
        }
    }

    @Test
    fun `ensureDefault should be idempotent`() {
        runBlocking {
            val first = repo.ensureDefault()
            val second = repo.ensureDefault()
            assertEquals(first.id, second.id)
        }
    }

    @Test
    fun `getAll should return all accounts`() {
        runBlocking {
            repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            repo.getOrCreate("A/c XX0000", "SBI", "auto-extracted")
            val all = repo.getAll().first()
            assertEquals(2, all.size)
        }
    }

    @Test
    fun `consolidateAccounts should merge duplicate accounts and update transaction references`() {
        runBlocking {
            val acc1 = com.pocketfinancer.data.db.entity.AccountEntity("id1", "Unknown Bank A/c XX9141", "Unknown Bank", "auto-extracted")
            val acc2 = com.pocketfinancer.data.db.entity.AccountEntity("id2", "A/C **9141", "Unknown Bank", "auto-extracted")
            val acc3 = com.pocketfinancer.data.db.entity.AccountEntity("id3", "HDFC Bank A/c XX9141", "HDFC Bank", "auto-extracted")
            
            db.accountDao().insert(acc1)
            db.accountDao().insert(acc2)
            db.accountDao().insert(acc3)

            val tx1 = com.pocketfinancer.data.db.entity.TransactionEntity("tx1", 100.0, "M1", 1000L, "DEBIT", "id1", "raw1", "sender1", false)
            val tx2 = com.pocketfinancer.data.db.entity.TransactionEntity("tx2", 200.0, "M2", 2000L, "DEBIT", "id2", "raw2", "sender2", false)
            val tx3 = com.pocketfinancer.data.db.entity.TransactionEntity("tx3", 300.0, "M3", 3000L, "DEBIT", "id3", "raw3", "sender3", false)

            db.transactionDao().insert(tx1)
            db.transactionDao().insert(tx2)
            db.transactionDao().insert(tx3)

            repo.consolidateAccounts()

            val allAccs = repo.getAllOnce()
            assertEquals(1, allAccs.size)
            assertEquals("id3", allAccs[0].id)
            assertEquals("HDFC Bank A/c XX9141", allAccs[0].name)

            val tx1Updated = db.transactionDao().getById("tx1")
            val tx2Updated = db.transactionDao().getById("tx2")
            val tx3Updated = db.transactionDao().getById("tx3")

            assertEquals("id3", tx1Updated?.accountId)
            assertEquals("id3", tx2Updated?.accountId)
            assertEquals("id3", tx3Updated?.accountId)
        }
    }

    @Test
    fun `getById should return null for missing account`() {
        runBlocking {
            val result = repo.getById("nonexistent")
            assertNull(result)
        }
    }

    @Test
    fun `consolidateAccounts should merge duplicates and delete stale even if no known-bank accounts exist`() {
        runBlocking {
            val acc1 = com.pocketfinancer.data.db.entity.AccountEntity("id1", "Unknown Bank A/c XX9141", "Unknown Bank", "auto-extracted")
            val acc2 = com.pocketfinancer.data.db.entity.AccountEntity("id2", "A/C **9141", "Unknown Bank", "auto-extracted")
            
            db.accountDao().insert(acc1)
            db.accountDao().insert(acc2)

            val tx1 = com.pocketfinancer.data.db.entity.TransactionEntity("tx1", 100.0, "M1", 1000L, "DEBIT", "id2", "raw1", "sender1", false)
            db.transactionDao().insert(tx1)

            repo.consolidateAccounts()

            val allAccs = repo.getAllOnce()
            assertEquals(1, allAccs.size)
            assertEquals("id1", allAccs[0].id)

            val tx1Updated = db.transactionDao().getById("tx1")
            assertEquals("id1", tx1Updated?.accountId)
        }
    }

    @Test
    fun `consolidateAccounts should merge multiple known accounts of same bank`() {
        runBlocking {
            val acc1 = com.pocketfinancer.data.db.entity.AccountEntity("id1", "HDFC Bank A/c XX9141", "HDFC Bank", "auto-extracted")
            val acc2 = com.pocketfinancer.data.db.entity.AccountEntity("id2", "HDFC A/c XX9141", "HDFC Bank", "auto-extracted")
            
            db.accountDao().insert(acc1)
            db.accountDao().insert(acc2)

            val tx1 = com.pocketfinancer.data.db.entity.TransactionEntity("tx1", 100.0, "M1", 1000L, "DEBIT", "id2", "raw1", "sender1", false)
            db.transactionDao().insert(tx1)

            repo.consolidateAccounts()

            val allAccs = repo.getAllOnce()
            assertEquals(1, allAccs.size)
            assertEquals("id1", allAccs[0].id)

            val tx1Updated = db.transactionDao().getById("tx1")
            assertEquals("id1", tx1Updated?.accountId)
        }
    }

    @Test
    fun `getOrCreate should infer bank name from account name`() {
        runBlocking {
            val account = repo.getOrCreate("HDFC Card XX1234", "Unknown Bank", "auto-extracted")
            assertEquals("HDFC Bank", account.bank)
            assertEquals("HDFC Bank Card XX1234", account.name)
        }
    }

    @Test
    fun `getOrCreate should handle account name with no normalizable digits`() {
        runBlocking {
            val account = repo.getOrCreate("MyCash", "HDFC Bank", "auto-extracted")
            assertEquals("MyCash", account.name)
            assertEquals("HDFC Bank", account.bank)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class TransactionRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var mockAccountRepo: AccountRepository
    private lateinit var repo: TransactionRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        mockAccountRepo = AccountRepository(db.accountDao(), db.transactionDao(), runConsolidationOnInit = false)
        repo = TransactionRepository(db, db.transactionDao(), mockAccountRepo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert should create transaction with UUID`() {
        runBlocking {
            val account = mockAccountRepo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            val tx = repo.insert(
                TransactionRepository.NewTransaction(
                    amount = 1500.0,
                    merchant = "Amazon",
                    date = 1000L,
                    type = TransactionType.DEBIT,
                    accountId = account.id,
                    rawMessage = "Rs.1500 debited",
                    sender = "AX-HDFCBK"
                )
            )
            assertNotNull(tx.id)
            assertEquals(1500.0, tx.amount)
            assertEquals("Amazon", tx.merchant)
            assertEquals(TransactionType.DEBIT, tx.type)
            assertEquals(account.id, tx.accountId)
        }
    }

    @Test
    fun `getAllByDateDesc should return all transactions`() {
        runBlocking {
            val account = mockAccountRepo.ensureDefault()
            repo.insert(TransactionRepository.NewTransaction(100.0, "M1", 1000L, TransactionType.DEBIT, account.id, "m1", "AX-BANK"))
            repo.insert(TransactionRepository.NewTransaction(200.0, "M2", 2000L, TransactionType.CREDIT, account.id, "m2", "AX-BANK"))
            val all = repo.getAllByDateDesc().first()
            assertEquals(2, all.size)
        }
    }

    @Test
    fun `count should track inserts`() {
        runBlocking {
            val account = mockAccountRepo.ensureDefault()
            assertEquals(0, repo.count())
            repo.insert(TransactionRepository.NewTransaction(100.0, "M1", 1000L, TransactionType.DEBIT, account.id, "m1", "AX-BANK"))
            assertEquals(1, repo.count())
            repo.insert(TransactionRepository.NewTransaction(200.0, "M2", 2000L, TransactionType.CREDIT, account.id, "m2", "AX-BANK"))
            assertEquals(2, repo.count())
        }
    }

    @Test
    fun `sumDebitsSince and sumCreditsSince should return correct totals`() {
        runBlocking {
            val account = mockAccountRepo.ensureDefault()
            val now = System.currentTimeMillis()
            repo.insert(TransactionRepository.NewTransaction(100.0, "D1", now - 1000, TransactionType.DEBIT, account.id, "d1", "AX-BANK"))
            repo.insert(TransactionRepository.NewTransaction(50.0, "D1", now - 500, TransactionType.DEBIT, account.id, "d1", "AX-BANK"))
            repo.insert(TransactionRepository.NewTransaction(500.0, "C1", now - 1000, TransactionType.CREDIT, account.id, "c1", "AX-BANK"))
            assertEquals(150.0, repo.sumDebitsSince(now - 5000))
            assertEquals(500.0, repo.sumCreditsSince(now - 5000))
        }
    }

    @Test
    fun `updateTransaction should modify existing transaction fields and set isEdited to true`() {
        runBlocking {
            val account = mockAccountRepo.ensureDefault()
            val tx = repo.insert(
                TransactionRepository.NewTransaction(
                    amount = 100.0,
                    merchant = "Merchant Old",
                    date = 1000L,
                    type = TransactionType.DEBIT,
                    accountId = account.id,
                    rawMessage = "Rs 100 spent",
                    sender = "AX-HDFCBK"
                )
            )
            
            val updated = repo.updateTransaction(
                id = tx.id,
                amount = 200.0,
                merchant = "Merchant New",
                type = TransactionType.CREDIT,
                accountId = account.id
            )
            
            assertNotNull(updated)
            assertEquals(200.0, updated.amount)
            assertEquals("Merchant New", updated.merchant)
            assertEquals(TransactionType.CREDIT, updated.type)
            assertEquals(true, updated.isEdited)
        }
    }

    @Test
    fun `updateTransaction should return null when transaction ID is not found`() {
        runBlocking {
            val updated = repo.updateTransaction(
                id = "nonexistent_id",
                amount = 200.0,
                merchant = "Merchant New",
                type = TransactionType.CREDIT,
                accountId = "some_account_id"
            )
            assertNull(updated)
        }
    }

    @Test
    fun `exists should return true when transaction with sender and date exists`() {
        runBlocking {
            val account = mockAccountRepo.ensureDefault()
            repo.insert(
                TransactionRepository.NewTransaction(
                    amount = 100.0,
                    merchant = "M1",
                    date = 5000L,
                    type = TransactionType.DEBIT,
                    accountId = account.id,
                    rawMessage = "Rs 100 spent",
                    sender = "AX-HDFCBK"
                )
            )
            
            assertTrue(repo.exists("AX-HDFCBK", 5000L))
            assertFalse(repo.exists("AX-HDFCBK", 6000L))
            assertFalse(repo.exists("AX-OTHER", 5000L))
        }
    }

    @Test
    fun `clearDatabase should delete all tables`() {
        runBlocking {
            val account = mockAccountRepo.ensureDefault()
            repo.insert(TransactionRepository.NewTransaction(100.0, "M1", 1000L, TransactionType.DEBIT, account.id, "m1", "AX-BANK"))
            assertEquals(1, repo.count())
            
            repo.clearDatabase()
            assertEquals(0, repo.count())
        }
    }

    @Test
    fun `getByType should filter transactions correctly`() {
        runBlocking {
            val account = mockAccountRepo.ensureDefault()
            repo.insert(TransactionRepository.NewTransaction(100.0, "M1", 1000L, TransactionType.DEBIT, account.id, "m1", "AX-BANK"))
            repo.insert(TransactionRepository.NewTransaction(200.0, "M2", 2000L, TransactionType.CREDIT, account.id, "m2", "AX-BANK"))
            
            val debits = repo.getByType(TransactionType.DEBIT).first()
            assertEquals(1, debits.size)
            assertEquals(100.0, debits[0].amount)

            val credits = repo.getByType(TransactionType.CREDIT).first()
            assertEquals(1, credits.size)
            assertEquals(200.0, credits[0].amount)
        }
    }

    @Test
    fun `getByDateRange should return transactions within range`() {
        runBlocking {
            val account = mockAccountRepo.ensureDefault()
            repo.insert(TransactionRepository.NewTransaction(100.0, "M1", 1000L, TransactionType.DEBIT, account.id, "m1", "AX-BANK"))
            repo.insert(TransactionRepository.NewTransaction(200.0, "M2", 2000L, TransactionType.CREDIT, account.id, "m2", "AX-BANK"))
            repo.insert(TransactionRepository.NewTransaction(300.0, "M3", 3000L, TransactionType.DEBIT, account.id, "m3", "AX-BANK"))
            
            val range = repo.getByDateRange(1500L, 2500L).first()
            assertEquals(1, range.size)
            assertEquals(200.0, range[0].amount)
        }
    }
}
