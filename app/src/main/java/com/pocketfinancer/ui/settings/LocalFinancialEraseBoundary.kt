package com.pocketfinancer.ui.settings

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Runs the irreversible portion of erase-all as one non-cancellable boundary.
 *
 * The durable marker, shell lock, and run-generation increment are committed
 * first. Compose may remove the Settings ViewModel as soon as that commit is
 * observed, so every remaining step stays inside this boundary. The final
 * setup commit clears the marker only after encrypted evidence is gone.
 *
 * Notification and in-memory cleanup failures are aggregated rather than
 * preventing the essential final setup commit after Room has already cleared.
 */
internal suspend fun runLocalFinancialEraseCriticalSection(
    beginDurableErase: () -> Unit,
    onDurableEraseStarted: () -> Unit,
    clearEncryptedData: suspend () -> Unit,
    cancelFinancialNotifications: () -> Unit,
    resetInMemoryState: () -> Unit,
    commitSetupReset: () -> Unit,
    onCommitted: () -> Unit
): Throwable? {
    var cleanupFailure: Throwable? = null

    fun captureCleanup(block: () -> Unit) {
        try {
            block()
        } catch (failure: Throwable) {
            if (cleanupFailure == null) {
                cleanupFailure = failure
            } else {
                cleanupFailure!!.addSuppressed(failure)
            }
        }
    }

    withContext(NonCancellable) {
        beginDurableErase()
        onDurableEraseStarted()
        clearEncryptedData()
        captureCleanup(cancelFinancialNotifications)
        captureCleanup(resetInMemoryState)
        commitSetupReset()
        onCommitted()
    }
    return cleanupFailure
}
