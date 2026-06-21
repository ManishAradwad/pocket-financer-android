package com.pocketfinancer.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketfinancer.data.db.entity.TransactionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(transaction: TransactionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(transactions: List<TransactionEntity>)

    @Query("SELECT * FROM transactions ORDER BY date DESC")
    fun getAllByDateDesc(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE date >= :startMs AND date <= :endMs ORDER BY date DESC")
    fun getByDateRange(startMs: Long, endMs: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE type = :type ORDER BY date DESC")
    fun getByType(type: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE accountId = :accountId ORDER BY date DESC")
    fun getByAccount(accountId: String): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions ORDER BY date DESC LIMIT :limit")
    fun getRecent(limit: Int = 20): Flow<List<TransactionEntity>>

    @Query("SELECT COUNT(*) FROM transactions")
    suspend fun count(): Int

    @Query("SELECT SUM(amount) FROM transactions WHERE type = :type AND date >= :sinceMs")
    suspend fun sumByTypeSince(type: String, sinceMs: Long): Double?

    @Query("SELECT * FROM transactions WHERE id = :id")
    suspend fun getById(id: String): TransactionEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM transactions WHERE sender = :sender AND date = :date)")
    suspend fun exists(sender: String, date: Long): Boolean

    @Query("UPDATE transactions SET accountId = :newAccountId WHERE accountId = :oldAccountId")
    suspend fun updateTransactionsAccount(oldAccountId: String, newAccountId: String)
}
