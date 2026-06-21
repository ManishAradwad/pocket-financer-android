package com.pocketfinancer.pipeline

interface HomeSyncDelegate {
    suspend fun queueIncomingSms(address: String, body: String, date: Long): Boolean
    fun startSyncService()
    fun isAppInForeground(): Boolean
}
