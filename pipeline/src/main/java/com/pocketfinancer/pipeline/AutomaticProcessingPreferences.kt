package com.pocketfinancer.pipeline

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The single persisted owner of whether newly received SMS alerts may be
 * processed automatically.
 *
 * This class owns preference persistence, observation, and the process-wide
 * consistency boundary shared by intake and WorkManager claims.
 * [AutomaticSmsOperationGate] ensures only one automatic candidate can reach
 * this claim boundary at a time. Disabling therefore prevents new or pending
 * automatic work while that one operation finishes; manual scans remain
 * available.
 */
@Singleton
class AutomaticProcessingPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    private val _enabled = MutableStateFlow(
        preferences.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
    )
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    private val consistencyBoundary = Mutex()

    /**
     * Serializes automatic admission/claim against preference changes.
     *
     * Callers must keep the complete durable admission or claim inside [block].
     * Once a claim returns from this boundary it owns an ON snapshot and later
     * disabling cannot cancel that operation.
     */
    suspend fun <T> withConsistencyBoundary(
        block: suspend (enabled: Boolean) -> T
    ): T = consistencyBoundary.withLock {
        block(_enabled.value)
    }

    /**
     * Persists OFF and removes pending evidence while holding the same boundary
     * used by scheduler admission and worker claim.
     *
     * The cleanup is non-cancellable once requested. If it fails, OFF remains
     * durable and callers may retry cleanup before enabling again.
     */
    suspend fun disableAndCleanupPending(
        cleanup: suspend () -> Int
    ): Int = withContext(NonCancellable) {
        consistencyBoundary.withLock {
            persistEnabledLocked(false)
            cleanup()
        }
    }

    /**
     * Retries pending cleanup while still OFF, then reopens intake atomically.
     */
    suspend fun enableAfterCleanupPending(
        cleanup: suspend () -> Int
    ): Int = withContext(NonCancellable) {
        consistencyBoundary.withLock {
            val removed = cleanup()
            persistEnabledLocked(true)
            removed
        }
    }

    private fun persistEnabledLocked(enabled: Boolean) {
        check(
            preferences.edit()
                .putBoolean(KEY_ENABLED, enabled)
                .commit()
        ) {
            "Could not persist the automatic-processing preference."
        }
        _enabled.value = enabled
    }

    companion object {
        const val PREFERENCES_NAME = ".app_settings"
        const val KEY_ENABLED = "process_incoming_sms"
        const val DEFAULT_ENABLED = true
    }
}
