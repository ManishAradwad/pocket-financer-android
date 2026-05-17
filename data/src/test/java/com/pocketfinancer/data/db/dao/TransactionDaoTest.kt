package com.pocketfinancer.data.db.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketfinancer.data.db.AppDatabase
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.db.entity.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class TransactionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: TransactionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.transactionDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and getById should return the transaction`() {
        runBlocking {
            val tx = TransactionEntity(
                id = UUID.randomUUID().toString(),
                amount = 1500.0,
                merchant = "Amazon",
                date = 1000L,
                type = "debit",
                accountId = "acct-1",
                rawMessage = "Rs.1500 debited from a/c XX6254",
                sender = "AX-HDFCBK"
            )
            dao.insert(tx)
            val loaded = dao.getById(tx.id)
            assertNotNull(loaded)
            assertEquals(tx.id, loaded!!.id)
            assertEquals(1500.0, loaded.amount)
            assertEquals("debit", loaded.type)
        }
    }

    @Test
    fun `getByType should filter transactions`() {
        runBlocking {
            val debit = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 100.0, merchant = "M1",
                date = 1000L, type = "debit", accountId = "a1",
                rawMessage = "Rs.100 debited", sender = "AX-BANK"
            )
            val credit = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 500.0, merchant = "M2",
                date = 2000L, type = "credit", accountId = "a2",
                rawMessage = "Rs.500 credited", sender = "AX-BANK"
            )
            dao.insertAll(listOf(debit, credit))

            val debits = dao.getByType("debit").first()
            assertEquals(1, debits.size)
            assertEquals(100.0, debits[0].amount)

            val credits = dao.getByType("credit").first()
            assertEquals(1, credits.size)
            assertEquals(500.0, credits[0].amount)
        }
    }

    @Test
    fun `getByDateRange should return transactions in range`() {
        runBlocking {
            val old = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 100.0, merchant = "Old",
                date = 1000L, type = "debit", accountId = "a1",
                rawMessage = "old", sender = "AX-BANK"
            )
            val mid = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 200.0, merchant = "Mid",
                date = 2000L, type = "debit", accountId = "a1",
                rawMessage = "mid", sender = "AX-BANK"
            )
            val recent = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 300.0, merchant = "Recent",
                date = 3000L, type = "credit", accountId = "a2",
                rawMessage = "recent", sender = "AX-BANK"
            )
            dao.insertAll(listOf(old, mid, recent))

            val inRange = dao.getByDateRange(1500L, 2500L).first()
            assertEquals(1, inRange.size)
            assertEquals("Mid", inRange[0].merchant)
        }
    }

    @Test
    fun `getByAccount should filter by account`() {
        runBlocking {
            val tx1 = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 100.0, merchant = "M1",
                date = 1000L, type = "debit", accountId = "acct-A",
                rawMessage = "msg1", sender = "AX-BANK"
            )
            val tx2 = TransactionEntity(
                id = UUID.randomUUID().toString(), amount = 200.0, merchant = "M2",
                date = 2000L, type = "credit", accountId = "acct-B",
                rawMessage = "msg2", sender = "AX-BANK"
            )
            dao.insertAll(listOf(tx1, tx2))

            val accountA = dao.getByAccount("acct-A").first()
            assertEquals(1, accountA.size)
            assertEquals(100.0, accountA[0].amount)
        }
    }

    @Test
    fun `insert with same id should replace`() {
        runBlocking {
            val id = UUID.randomUUID().toString()
            val original = TransactionEntity(
                id = id, amount = 100.0, merchant = "Original",
                date = 1000L, type = "debit", accountId = "a1",
                rawMessage = "original", sender = "AX-BANK"
            )
            dao.insert(original)

            val updated = TransactionEntity(
                id = id, amount = 999.0, merchant = "Updated",
                date = 1000L, type = "credit", accountId = "a1",
                rawMessage = "updated", sender = "AX-BANK"
            )
            dao.insert(updated)

            val loaded = dao.getById(id)
            assertNotNull(loaded)
            assertEquals(999.0, loaded!!.amount)
            assertEquals("credit", loaded.type)
        }
    }

    @Test
    fun `count should return correct number`() {
        runBlocking {
            assertEquals(0, dao.count())
            dao.insert(TransactionEntity(
                id = "t1", amount = 100.0, merchant = "M", date = 1000L,
                type = "debit", accountId = "a1", rawMessage = "m", sender = "AX-BANK"
            ))
            assertEquals(1, dao.count())
            dao.insert(TransactionEntity(
                id = "t2", amount = 200.0, merchant = "M2", date = 2000L,
                type = "credit", accountId = "a2", rawMessage = "m2", sender = "AX-BANK"
            ))
            assertEquals(2, dao.count())
        }
    }

    @Test
    fun `getRecent should return latest N`() {
        runBlocking {
            val txs = (1..5).map { i ->
                TransactionEntity(
                    id = UUID.randomUUID().toString(), amount = i * 100.0, merchant = "M$i",
                    date = i * 1000L, type = "debit", accountId = "a1",
                    rawMessage = "m$i", sender = "AX-BANK"
                )
            }
            dao.insertAll(txs)

            val recent = dao.getRecent(3).first()
            assertEquals(3, recent.size)
            assertEquals("M5", recent[0].merchant)
            assertEquals("M4", recent[1].merchant)
            assertEquals("M3", recent[2].merchant)
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class AccountDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: AccountDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.accountDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `insert and getById should return account`() {
        runBlocking {
            val account = AccountEntity(
                id = "acct-1",
                name = "A/c XX6254",
                bank = "HDFC Bank",
                type = "auto-extracted"
            )
            dao.insert(account)
            val loaded = dao.getById("acct-1")
            assertNotNull(loaded)
            assertEquals("A/c XX6254", loaded!!.name)
            assertEquals("HDFC Bank", loaded.bank)
        }
    }

    @Test
    fun `findByName should match correctly`() {
        runBlocking {
            val account = AccountEntity(
                id = "acct-2",
                name = "A/c XX0000",
                bank = "SBI",
                type = "auto-extracted"
            )
            dao.insert(account)
            val found = dao.findByName("A/c XX0000")
            assertNotNull(found)
            assertEquals("SBI", found!!.bank)
        }
    }

    @Test
    fun `findByName should return null for missing`() {
        runBlocking {
            val result = dao.findByName("nonexistent")
            assertNull(result)
        }
    }

    @Test
    fun `findByNameAndBank should match both fields`() {
        runBlocking {
            val account = AccountEntity(
                id = "acct-3",
                name = "A/c XX6254",
                bank = "HDFC Bank",
                type = "auto-extracted"
            )
            dao.insert(account)

            val match = dao.findByNameAndBank("A/c XX6254", "HDFC Bank")
            assertNotNull(match)

            val wrongBank = dao.findByNameAndBank("A/c XX6254", "SBI")
            assertNull(wrongBank)
        }
    }

    @Test
    fun `getAll should return all accounts`() {
        runBlocking {
            dao.insert(AccountEntity("a1", "A/c XX1111", "Bank A", "auto-extracted"))
            dao.insert(AccountEntity("a2", "A/c XX2222", "Bank B", "auto-extracted"))

            val all = dao.getAll().first()
            assertEquals(2, all.size)
            assertEquals("a1", all.find { it.id == "a1" }!!.id)
            assertEquals("a2", all.find { it.id == "a2" }!!.id)
        }
    }
}
