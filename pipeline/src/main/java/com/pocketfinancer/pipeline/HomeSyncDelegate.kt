package com.pocketfinancer.pipeline

interface SmsWorkerFlowLease {
    suspend fun release()
}

interface HomeSyncDelegate {
    /**
     * Admits one direct background SMS workflow before it resolves the
     * persisted model selection. A null result means selected-model
     * maintenance currently owns admission and the WorkManager item must retry.
     */
    suspend fun tryEnterSmsWorkerFlow(): SmsWorkerFlowLease?
}
