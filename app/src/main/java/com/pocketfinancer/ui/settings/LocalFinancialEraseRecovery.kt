package com.pocketfinancer.ui.settings

import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.isPublishedModelArtifact
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.pipeline.SmsWorkController
import com.pocketfinancer.setup.SetupImportStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

data class LocalFinancialEraseRecoveryResult(
    val recovered: Boolean,
    val cleanupFailure: Throwable? = null
)

/**
 * Completes an erase whose durable begin commit survived process death.
 *
 * Startup calls this before selected-model restoration or encrypted-outbox
 * reconciliation. The persisted shell lock and generation already reject stale
 * service/worker starts; this process-local pause closes fresh scheduler
 * admission while the encrypted tables are cleared again idempotently.
 */
@Singleton
class LocalFinancialEraseRecovery internal constructor(
    private val setupImportStore: SetupImportStore,
    private val transactionRepository: TransactionRepository,
    private val modelStorage: SlmModelStorage,
    private val smsWorkController: SmsWorkController,
    private val cancelFinancialNotifications: () -> Unit
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        setupImportStore: SetupImportStore,
        transactionRepository: TransactionRepository,
        modelStorage: SlmModelStorage,
        smsWorkController: SmsWorkController
    ) : this(
        setupImportStore = setupImportStore,
        transactionRepository = transactionRepository,
        modelStorage = modelStorage,
        smsWorkController = smsWorkController,
        cancelFinancialNotifications = {
            NotificationManagerCompat.from(context).cancelAll()
        }
    )

    fun isRecoveryPending(): Boolean =
        setupImportStore.isLocalFinancialErasePending()

    suspend fun recoverIfNeeded(): LocalFinancialEraseRecoveryResult {
        if (!isRecoveryPending()) {
            return LocalFinancialEraseRecoveryResult(recovered = false)
        }

        val admissionPause = smsWorkController.pauseAdmissions()
        try {
            admissionPause.cancelPending()
            val retainedModelPrepared = SlmTier.ALL_TIERS.any { tier ->
                isPublishedModelArtifact(modelStorage.modelFile(tier.modelFile))
            }
            val cleanupFailure = withContext(Dispatchers.IO) {
                runLocalFinancialEraseCriticalSection(
                    beginDurableErase = {
                        check(setupImportStore.isLocalFinancialErasePending()) {
                            "Local financial erase recovery marker disappeared"
                        }
                    },
                    onDurableEraseStarted = {},
                    clearEncryptedData = transactionRepository::clearDatabase,
                    cancelFinancialNotifications = cancelFinancialNotifications,
                    resetInMemoryState = {},
                    commitSetupReset = {
                        setupImportStore.finishLocalFinancialErase(
                            retainedModelPrepared = retainedModelPrepared
                        )
                    },
                    onCommitted = {}
                )
            }
            return LocalFinancialEraseRecoveryResult(
                recovered = true,
                cleanupFailure = cleanupFailure
            )
        } finally {
            withContext(NonCancellable) {
                admissionPause.release()
            }
        }
    }
}
