package com.pocketfinancer.data.repository

import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.dao.TransactionDao
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.model.Account
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository (
    private val accountDao: AccountDao,
    private val transactionDao: TransactionDao,
    runConsolidationOnInit: Boolean = true
) {
    init {
        if (runConsolidationOnInit) {
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    consolidateAccounts()
                } catch (e: Exception) {
                    // Safe-guard logging
                }
            }
        }
    }

    @Inject
    constructor(
        accountDao: AccountDao,
        transactionDao: TransactionDao
    ) : this(accountDao, transactionDao, runConsolidationOnInit = true)

    suspend fun consolidateAccounts() {
        val allAccounts = accountDao.getAllOnce()
        val digitsGroups = allAccounts.filter { normalizeAccountName(it.name) != null }
            .groupBy { normalizeAccountName(it.name)!!.second }

        for ((digits, list) in digitsGroups) {
            if (list.size <= 1) continue

            val (unknowns, knowns) = list.partition { it.bank == "Unknown Bank" || it.bank == "Unknown" }

            if (knowns.isNotEmpty()) {
                val canonical = knowns.first()
                for (dup in unknowns) {
                    transactionDao.updateTransactionsAccount(dup.id, canonical.id)
                    accountDao.delete(dup.id)
                }

                val knownsByBank = knowns.groupBy { it.bank }
                for ((bank, bankList) in knownsByBank) {
                    if (bankList.size > 1) {
                        val firstKnown = bankList.first()
                        for (i in 1 until bankList.size) {
                            val dup = bankList[i]
                            transactionDao.updateTransactionsAccount(dup.id, firstKnown.id)
                            accountDao.delete(dup.id)
                        }
                    }
                }
            } else {
                val canonical = unknowns.first()
                for (i in 1 until unknowns.size) {
                    val dup = unknowns[i]
                    transactionDao.updateTransactionsAccount(dup.id, canonical.id)
                    accountDao.delete(dup.id)
                }
            }
        }
    }

    fun getAll(): Flow<List<Account>> =
        accountDao.getAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: String): Account? =
        accountDao.getById(id)?.toDomain()

    suspend fun getAllOnce(): List<Account> =
        accountDao.getAllOnce().map { it.toDomain() }

    /**
     * Find an existing account by (name, bank) or create it atomically.
     * Used by the pipeline when the SLM extracts an account label.
     * Normalizes the name and matches by last 4 digits.
     */
    suspend fun getOrCreate(name: String, bank: String, type: String): Account {
        val finalBank = if (bank == "Unknown Bank" || bank.isBlank()) {
            inferBankFromName(name)
        } else {
            bank
        }

        // Try to normalize account suffix digits
        val normalizedPair = normalizeAccountName(name)
        if (normalizedPair != null) {
            val (category, digits) = normalizedPair
            val capCategory = if (category == "card") "Card" else "A/c"
            val bankPrefix = if (finalBank != "Unknown Bank") "$finalBank " else ""
            val normalizedName = "$bankPrefix$capCategory XX$digits"

            // Look for existing account with the same digits and bank
            val allAccounts = accountDao.getAllOnce()
            val existing = allAccounts.find { acc ->
                val pair = normalizeAccountName(acc.name)
                pair != null && pair.second == digits && (acc.bank == finalBank || finalBank == "Unknown Bank")
            }
            if (existing != null) {
                return existing.toDomain()
            }

            // Create new normalized account
            val entity = AccountEntity(
                id = UUID.randomUUID().toString(),
                name = normalizedName,
                bank = finalBank,
                type = type
            )
            accountDao.insert(entity)
            return entity.toDomain()
        }

        val existing = accountDao.findByNameAndBank(name, finalBank)
        if (existing != null) return existing.toDomain()

        val entity = AccountEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            bank = finalBank,
            type = type
        )
        accountDao.insert(entity)
        return entity.toDomain()
    }

    private fun normalizeAccountName(account: String): Pair<String, String>? {
        if (account.isBlank()) return null
        val category = if (account.contains("card", ignoreCase = true)) "card" else "account"
        val runs = Regex("\\d+").findAll(account).toList()
        val longRuns = runs.filter { it.value.length >= 3 }
        if (longRuns.isEmpty()) return null
        val lastDigits = longRuns.last().value.takeLast(4)
        return Pair(category, lastDigits)
    }

    private fun inferBankFromName(name: String): String {
        val upper = name.uppercase()
        return when {
            upper.contains("HDFC") -> "HDFC Bank"
            upper.contains("AXIS") -> "Axis Bank"
            upper.contains("ICICI") -> "ICICI Bank"
            upper.contains("SBI") -> "State Bank of India"
            upper.contains("KOTAK") -> "Kotak Bank"
            else -> "Unknown Bank"
        }
    }

    /**
     * Returns the default "__UNKNOWN__" account, creating it on first access.
     * Used as a fallback when extraction yields no account info.
     */
    suspend fun ensureDefault(): Account {
        val name = "__UNKNOWN__"
        val bank = "Unknown Bank"
        val type = "auto-extracted"

        val existing = accountDao.findByNameAndBank(name, bank)
        if (existing != null) return existing.toDomain()

        return getOrCreate(name, bank, type)
    }

    private fun AccountEntity.toDomain() = Account(
        id = id,
        name = name,
        bank = bank,
        type = type
    )
}
