package com.pocketfinancer.pipeline

enum class IncomingSmsQueueResult {
    QUEUED_TRANSACTION,
    IGNORED,
    ADMISSION_PAUSED
}

interface SmsWorkerFlowLease {
    suspend fun release()
}

interface HomeSyncDelegate {
    suspend fun queueIncomingSms(
        address: String,
        body: String,
        date: Long
    ): IncomingSmsQueueResult

    /**
     * Admits one direct background SMS workflow before it resolves the
     * persisted model selection. A null result means selected-model
     * maintenance currently owns admission and the WorkManager item must retry.
     */
    suspend fun tryEnterSmsWorkerFlow(): SmsWorkerFlowLease?

    fun startSyncService()
    fun isAppInForeground(): Boolean
}
