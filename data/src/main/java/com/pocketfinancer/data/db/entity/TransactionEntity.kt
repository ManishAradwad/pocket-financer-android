package com.pocketfinancer.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "transactions",
    indices = [
        Index(value = ["accountId"]),
        Index(value = ["date"]),
        Index(value = ["type"])
    ]
)
data class TransactionEntity(
    @PrimaryKey
    val id: String,                     // UUID generated at insert time
    val amount: Double,
    val merchant: String,
    val date: Long,                     // epoch millis (SMS arrival timestamp)
    val type: String,                   // "debit" | "credit"
    val accountId: String,              // foreign key to accounts table
    val rawMessage: String,             // original SMS body (encrypted at rest)
    val sender: String,                 // SMS sender address (e.g. "AX-HDFCBK")
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
