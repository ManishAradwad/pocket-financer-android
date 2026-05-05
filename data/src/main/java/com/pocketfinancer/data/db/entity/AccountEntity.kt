package com.pocketfinancer.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey
    val id: String,                     // UUID generated at insert time
    val name: String,                   // masked account label (e.g. "A/c XX6254")
    val bank: String,                   // detected or "Unknown Bank"
    val type: String,                   // "auto-extracted", "manual", etc.
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
