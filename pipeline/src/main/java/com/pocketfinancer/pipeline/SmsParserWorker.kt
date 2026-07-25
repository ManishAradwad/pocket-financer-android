package com.pocketfinancer.pipeline

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.resolveActiveSlmTier
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.sms.SmsWorkScheduler
import dagger.Binds
import dagger.Module
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Background worker to process one transaction SMS.
 *
 * This worker runs in the application's default process. It obtains temporary
 * residency from the process-wide SLM runtime and releases only its own lease;
 * it never directly loads or unloads the native model.
 */
class SmsParserWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface ParserWorkerEntryPoint {
        fun smsFilterPipeline(): SmsFilterPipeline
        fun slmRuntime(): SlmRuntime
        fun slmModelStorage(): SlmModelStorage
        fun deviceCapabilities(): DeviceCapabilities
        fun pipelineService(): PipelineService
        fun smsRepository(): SmsRepository
        fun transactionRepository(): TransactionRepository
        fun homeSyncDelegate(): HomeSyncDelegate
    }

    override suspend fun doWork(): Result {
        // A malformed item is terminal for that item, but must not poison the
        // unique chain and prevent already-appended SMS work from running.
        val address = inputData.getString(KEY_ADDRESS) ?: return Result.success()
        val body = inputData.getString(KEY_BODY) ?: return Result.success()
        val date = inputData.getLong(KEY_DATE, 0L)
        val type = inputData.getInt(KEY_TYPE, 1)

        return protectSmsParserChain(
            onFailure = { error ->
                Log.e(TAG, "Exception in background SMS parser", error)
                runCatching {
                    SmsNotificationHelper.showFailureNotification(
                        applicationContext,
                        address,
                        date,
                        error.message ?: "Unknown error"
                    )
                }.onFailure { notificationError ->
                    Log.e(TAG, "Failed to show parser failure notification", notificationError)
                }
            },
            retryOrFinishChain = ::retryOrFinishChain
        ) {
            processValidInput(address, body, date, type)
        }
    }

    /**
     * Everything after structurally-valid WorkData lives under the chain-safe
     * exception boundary in [doWork]. This includes Hilt entry-point lookup,
     * foreground delegation, filtering, runtime acquisition, and persistence.
     */
    private suspend fun processValidInput(
        address: String,
        body: String,
        date: Long,
        type: Int
    ): Result {
        val prefs = applicationContext.getSharedPreferences(
            APP_SETTINGS,
            Context.MODE_PRIVATE
        )
        if (!isOnboardingCompleteForSmsWork(prefs)) {
            // Persisted work can be recreated after process death. Once reset
            // has committed onboarding=false it is stale and must never repopulate
            // the freshly-cleared database.
            Log.i(TAG, "Onboarding is incomplete; discarding stale parser work.")
            return Result.success()
        }
        val processIncoming = prefs.getBoolean(PROCESS_INCOMING_SMS, true)

        val entryPoint = EntryPointAccessors.fromApplication(
            applicationContext,
            ParserWorkerEntryPoint::class.java
        )
        val smsFilterPipeline = entryPoint.smsFilterPipeline()
        val homeSyncDelegate = entryPoint.homeSyncDelegate()
        val isForeground = homeSyncDelegate.isAppInForeground()

        if (!processIncoming) {
            Log.i(TAG, "Background SMS processing is disabled; skipping inference.")
            if (isForeground) {
                homeSyncDelegate.queueIncomingSms(address, body, date)
            }
            return Result.success()
        }

        if (isForeground) {
            Log.i(TAG, "Routing SMS through the foreground sync service.")
            if (homeSyncDelegate.queueIncomingSms(address, body, date)) {
                homeSyncDelegate.startSyncService()
            }
            return Result.success()
        }

        val sms = SmsReader.SmsMessage(address, body, date, type)
        if (!smsFilterPipeline.isTransactional(address, body)) {
            Log.i(TAG, "SMS from $address is non-transactional; skipping inference.")
            SmsNotificationHelper.showSkippedNotification(
                applicationContext,
                address,
                body,
                date
            )
            return Result.success()
        }

        val runtime = entryPoint.slmRuntime()
        val modelStorage = entryPoint.slmModelStorage()
        val pipelineService = entryPoint.pipelineService()
        var lease: SlmLease? = null

        val workerResult = try {
            SmsNotificationHelper.showProcessingNotification(
                applicationContext,
                address,
                date,
                "Initializing local AI engine..."
            )

            val device = entryPoint.deviceCapabilities().assessDevice()
            val tier = resolveActiveSlmTier(
                applicationContext,
                modelStorage.modelDirectory,
                device
            )
            if (tier == null) {
                val message = "No viable SLM for this device (RAM below minimum)."
                Log.e(TAG, message)
                SmsNotificationHelper.showFailureNotification(
                    applicationContext,
                    address,
                    date,
                    message
                )
                return Result.success()
            }

            val modelFile = modelStorage.modelFile(tier.modelFile)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                val message = "Selected model ${tier.name} is not downloaded yet."
                Log.e(TAG, message)
                SmsNotificationHelper.showFailureNotification(
                    applicationContext,
                    address,
                    date,
                    message
                )
                return Result.success()
            }

            SmsNotificationHelper.showProcessingNotification(
                applicationContext,
                address,
                date,
                "Waiting for local AI model ${tier.name}..."
            )
            val spec = SlmModelSpec(
                modelId = tier.id,
                modelPath = modelFile.canonicalPath,
                artifactRevision =
                    "${tier.modelFile}:${modelFile.length()}:${modelFile.lastModified()}",
                contextSize = 3072,
                gpuLayers = 0,
                numThreads = 0,
                hasFp16 = device.cpu?.hasFp16 ?: false,
                hasThinkingMode = tier.hasThinkingMode
            )
            val acquiredLease = runtime.acquire(SlmRuntimeOwner.SMS_WORKER, spec)
            lease = acquiredLease

            // The first onboarding check can race with a reset while this
            // acquire waits behind runtime maintenance. Re-check after the
            // lease handoff and before any extraction/persistence; returning
            // here still releases the lease in the surrounding finally.
            if (!isOnboardingCompleteForSmsWork(prefs)) {
                Log.i(
                    TAG,
                    "Onboarding reset completed while runtime acquisition was queued; " +
                        "discarding stale parser work."
                )
                return Result.success()
            }

            SmsNotificationHelper.showProcessingNotification(
                applicationContext,
                address,
                date,
                "Analyzing transaction content..."
            )

            when (val processing = pipelineService.processSingle(sms, acquiredLease)) {
                is PipelineService.ProcessingResult.Saved -> {
                    val parsed = processing.transaction
                    SmsNotificationHelper.showSuccessNotification(
                        applicationContext,
                        address,
                        date,
                        parsed.amount,
                        parsed.counterparty ?: "Unknown Merchant"
                    )
                    Result.success()
                }

                is PipelineService.ProcessingResult.Skipped -> {
                    Log.i(TAG, "Pipeline skipped SMS: ${processing.reason}")
                    SmsNotificationHelper.showSkippedNotification(
                        applicationContext,
                        address,
                        body,
                        date
                    )
                    Result.success()
                }

                is PipelineService.ProcessingResult.Stopped -> {
                    val message = "Local AI inference was stopped before completion."
                    Log.w(TAG, message)
                    SmsNotificationHelper.showFailureNotification(
                        applicationContext,
                        address,
                        date,
                        message
                    )
                    retryOrFinishChain()
                }

                is PipelineService.ProcessingResult.Failure -> {
                    Log.e(TAG, processing.message)
                    SmsNotificationHelper.showFailureNotification(
                        applicationContext,
                        address,
                        date,
                        processing.message
                    )
                    if (processing.retryable) {
                        retryOrFinishChain()
                    } else {
                        Result.success()
                    }
                }
            }
        } finally {
            // Idempotent and non-cancellable: release only this worker's claim.
            lease?.let { acquiredLease ->
                withContext(NonCancellable) {
                    acquiredLease.release()
                }
            }
        }

        updateUnsyncedSummary(entryPoint, smsFilterPipeline)
        return workerResult
    }

    private suspend fun updateUnsyncedSummary(
        entryPoint: ParserWorkerEntryPoint,
        smsFilterPipeline: SmsFilterPipeline
    ) {
        try {
            val smsRepository = entryPoint.smsRepository()
            val transactionRepository = entryPoint.transactionRepository()
            val rawMessages = smsRepository.fetchHistory(daysBack = 7, limit = 250)
            val transactionalMessages = rawMessages.filter { message ->
                smsFilterPipeline.isTransactional(message.address, message.body)
            }
            val unsyncedCount = transactionalMessages.count { message ->
                !transactionRepository.exists(message.address, message.date)
            }
            SmsNotificationHelper.showUnsyncedSummaryNotification(
                applicationContext,
                unsyncedCount
            )
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.e(TAG, "Failed to update unsynced count summary", error)
        }
    }

    /**
     * Exhausting retries is terminal for this SMS, not for the unique chain.
     * Returning failure would automatically fail already-appended dependents.
     */
    private fun retryOrFinishChain(): Result =
        if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }

    private companion object {
        const val TAG = "SmsParserWorker"
        const val APP_SETTINGS = ".app_settings"
        const val PROCESS_INCOMING_SMS = "process_incoming_sms"
        const val KEY_ADDRESS = "address"
        const val KEY_BODY = "body"
        const val KEY_DATE = "date"
        const val KEY_TYPE = "type"
        const val MAX_RETRY_ATTEMPTS = 3
    }
}

/**
 * Concrete implementation of the SMS worker scheduler.
 */
@Singleton
class SmsWorkSchedulerImpl @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val admissionGate: SmsWorkAdmissionGate
) : SmsWorkScheduler {
    override fun scheduleSmsParsing(address: String, body: String, date: Long) {
        Log.i("SmsWorkScheduler", "Scheduling background SMS parser for: $address")
        val data = workDataOf(
            "address" to address,
            "body" to body,
            "date" to date
        )
        val request = OneTimeWorkRequestBuilder<SmsParserWorker>()
            .setInputData(data)
            .build()

        // Secondary defence only: all native callers, including flows outside
        // WorkManager, are serialized by SlmRuntime.
        val admitted = admissionGate.enqueueIfOpen {
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_SMS_PARSER_WORK,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
        if (!admitted) {
            Log.i(
                "SmsWorkScheduler",
                "Dropping SMS parser enqueue while destructive reset owns admission."
            )
        }
    }

    internal companion object {
        const val UNIQUE_SMS_PARSER_WORK = "sms-parser-runtime-chain"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SmsPipelineModule {
    @Binds
    @Singleton
    abstract fun bindSmsWorkScheduler(impl: SmsWorkSchedulerImpl): SmsWorkScheduler

    @Binds
    @Singleton
    abstract fun bindSmsWorkController(
        impl: WorkManagerSmsWorkController
    ): SmsWorkController
}

internal fun isOnboardingCompleteForSmsWork(
    preferences: SharedPreferences
): Boolean = preferences.getBoolean("onboarding_completed", false)

internal suspend fun protectSmsParserChain(
    onFailure: (Exception) -> Unit,
    retryOrFinishChain: () -> androidx.work.ListenableWorker.Result,
    block: suspend () -> androidx.work.ListenableWorker.Result
): androidx.work.ListenableWorker.Result = try {
    block()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Exception) {
    runCatching { onFailure(error) }
    retryOrFinishChain()
}
