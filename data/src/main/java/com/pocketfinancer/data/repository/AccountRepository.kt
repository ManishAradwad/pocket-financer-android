package com.pocketfinancer.data.repository

import com.pocketfinancer.data.db.dao.AccountDao
import com.pocketfinancer.data.db.entity.AccountEntity
import com.pocketfinancer.data.model.Account
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AccountRepository @Inject constructor(
    private val accountDao: AccountDao
) {
    fun getAll(): Flow<List<Account>> =
        accountDao.getAll().map { list -> list.map { it.toDomain() } }

    suspend fun getById(id: String): Account? =
        accountDao.getById(id)?.toDomain()

    /**
     * Find an existing account by (name, bank) or create it atomically.
     * Used by the pipeline when the SLM extracts an account label.
     */
    suspend fun getOrCreate(name: String, bank: String, type: String): Account {
        val existing = accountDao.findByNameAndBank(name, bank)
        if (existing != null) return existing.toDomain()

        val entity = AccountEntity(
            id = UUID.randomUUID().toString(),
            name = name,
            bank = bank,
            type = type
        )
        accountDao.insert(entity)
        return entity.toDomain()
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
