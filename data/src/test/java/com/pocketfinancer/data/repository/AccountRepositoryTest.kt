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
        repo = AccountRepository(db.accountDao())
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `getOrCreate should create new account when not found`() {
        runBlocking {
            val account = repo.getOrCreate("A/c XX6254", "HDFC Bank", "auto-extracted")
            assertEquals("A/c XX6254", account.name)
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
    fun `getById should return null for missing account`() {
        runBlocking {
            val result = repo.getById("nonexistent")
            assertNull(result)
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
        mockAccountRepo = AccountRepository(db.accountDao())
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
}
