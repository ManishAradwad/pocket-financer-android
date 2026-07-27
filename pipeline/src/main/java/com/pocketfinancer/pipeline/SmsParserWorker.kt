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
import com.pocketfinancer.data.model.QueuedSmsCandidate
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.repository.SmsIngestionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.resolveActiveSlmTier
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.sms.SmsReader
import com.pocketfinancer.sms.SmsScheduleResult
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
 * Processes one encrypted queued SMS candidate.
 *
 * WorkManager receives only [KEY_CANDIDATE_KEY]. Sender and body remain inside
 * the SQLCipher database until terminal rejection or until the transaction row
 * atomically takes ownership of that evidence.
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
        fun homeSyncDelegate(): HomeSyncDelegate
        fun smsIngestionRepository(): SmsIngestionRepository
        fun automaticProcessingPreferences(): AutomaticProcessingPreferences
    }

    override suspend fun doWork(): Result {
        val candidateKey = inputData.getString(KEY_CANDIDATE_KEY)
            ?.takeIf(String::isNotBlank)
            ?: return Result.success()
        val claimToken = id.toString()

        val entryPoint = try {
            EntryPointAccessors.fromApplication(
                applicationContext,
                ParserWorkerEntryPoint::class.java
            )
        } catch (error: Exception) {
            Log.e(TAG, "SMS worker dependency lookup failed", error)
            return retryOrFinishChain()
        }
        // Older builds posted an exact-looking backlog summary from a
        // truncated heuristic scan. It was neither complete nor proof that
        // those messages were transactions, so retire any surviving copy and
        // never recreate it from the automatic worker.
        SmsNotificationHelper.cancelLegacyUnsyncedSummaryNotification(
            applicationContext
        )
        val ingestionRepository = entryPoint.smsIngestionRepository()
        val candidate = ingestionRepository.get(candidateKey)
            ?: return Result.success()

        val appPreferences = applicationContext.getSharedPreferences(
            APP_SETTINGS,
            Context.MODE_PRIVATE
        )
        if (!isOnboardingCompleteForSmsWork(appPreferences)) {
            ingestionRepository.discardTerminal(candidateKey)
            return Result.success()
        }

        val claimDecision = claimSmsCandidateForRun(
            candidate = candidate,
            claimToken = claimToken,
            automaticProcessingPreferences =
                entryPoint.automaticProcessingPreferences(),
            ingestionRepository = ingestionRepository
        )
        if (claimDecision is SmsCandidateClaimDecision.AutomaticDisabled) {
            return Result.success()
        }
        val claimed =
            (claimDecision as SmsCandidateClaimDecision.Claimed).candidate
        if (claimed == null) {
            // Another worker owns this candidate. It will either finish or
            // release it; duplicate work must never run inference concurrently.
            return if (ingestionRepository.get(candidateKey) == null) {
                Result.success()
            } else {
                retryOrFinishChain()
            }
        }

        return try {
            processClaimedCandidate(
                entryPoint = entryPoint,
                preferences = appPreferences,
                candidate = claimed,
                claimToken = claimToken
            )
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) {
                ingestionRepository.releaseForRetry(
                    candidateKey = candidateKey,
                    claimToken = claimToken,
                    error = "Worker cancelled"
                )
            }
            throw cancelled
        } catch (error: Exception) {
            Log.e(
                TAG,
                "Encrypted SMS candidate processing failed: " +
                    error.javaClass.simpleName
            )
            runCatching {
                SmsNotificationHelper.showFailureNotification(
                    applicationContext,
                    claimed.sender,
                    claimed.date,
                    "Local processing will retry."
                )
            }
            retryClaimedOrDiscard(
                ingestionRepository = ingestionRepository,
                candidate = claimed,
                claimToken = claimToken,
                error = error.javaClass.simpleName
            )
        }
    }

    private suspend fun processClaimedCandidate(
        entryPoint: ParserWorkerEntryPoint,
        preferences: SharedPreferences,
        candidate: QueuedSmsCandidate,
        claimToken: String
    ): Result {
        val ingestionRepository = entryPoint.smsIngestionRepository()
        val flowLease = entryPoint.homeSyncDelegate().tryEnterSmsWorkerFlow()
            ?: return retryClaimedOrDiscard(
                ingestionRepository = ingestionRepository,
                candidate = candidate,
                claimToken = claimToken,
                error = "Model maintenance owns admission"
            )
        return try {
            processDirectlyInBackground(
                entryPoint = entryPoint,
                preferences = preferences,
                candidate = candidate,
                claimToken = claimToken
            )
        } finally {
            withContext(NonCancellable) {
                flowLease.release()
            }
        }
    }

    private suspend fun processDirectlyInBackground(
        entryPoint: ParserWorkerEntryPoint,
        preferences: SharedPreferences,
        candidate: QueuedSmsCandidate,
        claimToken: String
    ): Result {
        val ingestionRepository = entryPoint.smsIngestionRepository()
        val sms = SmsReader.SmsMessage(
            address = candidate.sender,
            body = candidate.rawMessage,
            date = candidate.date,
            type = candidate.messageType,
            providerMessageId = candidate.sourceIdentity.providerMessageId,
            sourceTimestamp = candidate.sourceTimestamp
        )
        val filter = entryPoint.smsFilterPipeline()
        if (!filter.isTransactional(sms.address, sms.body)) {
            discardOwnedTerminalCandidate(
                ingestionRepository = ingestionRepository,
                candidateKey = candidate.candidateKey,
                claimToken = claimToken
            )
            SmsNotificationHelper.showSkippedNotification(
                applicationContext,
                sms.address,
                sms.body,
                sms.date
            )
            return Result.success()
        }

        val runtime = entryPoint.slmRuntime()
        val modelStorage = entryPoint.slmModelStorage()
        var lease: SlmLease? = null
        val workerResult = try {
            SmsNotificationHelper.showProcessingNotification(
                applicationContext,
                sms.address,
                sms.date,
                "Preparing on-device processing..."
            )
            val device = entryPoint.deviceCapabilities().assessDevice()
            val tier = resolveActiveSlmTier(
                applicationContext,
                modelStorage.modelDirectory,
                device
            )
            if (tier == null) {
                return retryClaimedOrDiscard(
                    ingestionRepository,
                    candidate,
                    claimToken,
                    "No supported on-device model"
                )
            }

            val modelFile = modelStorage.modelFile(tier.modelFile)
            if (!modelFile.exists() || modelFile.length() == 0L) {
                return retryClaimedOrDiscard(
                    ingestionRepository,
                    candidate,
                    claimToken,
                    "On-device model is not prepared"
                )
            }

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

            // Reset may commit while model acquisition is suspended. Both the
            // preference and durable claim must still be valid before inference
            // can reach ledger persistence.
            if (
                !isOnboardingCompleteForSmsWork(preferences) ||
                !ingestionRepository.isClaimOwned(
                    candidate.candidateKey,
                    claimToken
                )
            ) {
                // A stale replacement now owns this encrypted evidence. The old
                // worker must stop without deleting the replacement's claim.
                return Result.success()
            }

            SmsNotificationHelper.showProcessingNotification(
                applicationContext,
                sms.address,
                sms.date,
                "Analyzing transaction content..."
            )
            when (
                val processing = entryPoint.pipelineService()
                    .processSingle(sms, acquiredLease)
            ) {
                is PipelineService.ProcessingResult.Saved -> {
                    // insertIfAbsent deletes the matching queued evidence in the
                    // same Room transaction, whether it inserted or observed a
                    // concurrent winner.
                    if (processing.newlyInserted) {
                        SmsNotificationHelper.showSuccessNotification(
                            applicationContext,
                            sms.address,
                            sms.date,
                            processing.transaction.amount,
                            processing.transaction.counterparty
                                ?: "Unknown Merchant"
                        )
                    }
                    Result.success()
                }

                is PipelineService.ProcessingResult.Skipped -> {
                    discardOwnedTerminalCandidate(
                        ingestionRepository = ingestionRepository,
                        candidateKey = candidate.candidateKey,
                        claimToken = claimToken
                    )
                    SmsNotificationHelper.showSkippedNotification(
                        applicationContext,
                        sms.address,
                        sms.body,
                        sms.date
                    )
                    Result.success()
                }

                is PipelineService.ProcessingResult.Stopped ->
                    retryClaimedOrDiscard(
                        ingestionRepository,
                        candidate,
                        claimToken,
                        "On-device inference stopped"
                    )

                is PipelineService.ProcessingResult.Failure ->
                    if (processing.retryable) {
                        retryClaimedOrDiscard(
                            ingestionRepository,
                            candidate,
                            claimToken,
                            "On-device extraction failed"
                        )
                    } else {
                        discardOwnedTerminalCandidate(
                            ingestionRepository = ingestionRepository,
                            candidateKey = candidate.candidateKey,
                            claimToken = claimToken
                        )
                        Result.success()
                    }
            }
        } finally {
            lease?.let { acquiredLease ->
                withContext(NonCancellable) {
                    acquiredLease.release()
                }
            }
        }

        return workerResult
    }

    private suspend fun retryClaimedOrDiscard(
        ingestionRepository: SmsIngestionRepository,
        candidate: QueuedSmsCandidate,
        claimToken: String,
        error: String
    ): Result {
        return if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
            ingestionRepository.releaseForRetry(
                candidateKey = candidate.candidateKey,
                claimToken = claimToken,
                error = error
            )
            Result.retry()
        } else {
            discardOwnedTerminalCandidate(
                ingestionRepository = ingestionRepository,
                candidateKey = candidate.candidateKey,
                claimToken = claimToken
            )
            SmsNotificationHelper.showFailureNotification(
                applicationContext,
                candidate.sender,
                candidate.date,
                "Could not process this alert on device."
            )
            Result.success()
        }
    }

    /**
     * Exhausting retries is terminal for this item, not any unrelated work.
     */
    private fun retryOrFinishChain(): Result =
        if (runAttemptCount < MAX_RETRY_ATTEMPTS) {
            Result.retry()
        } else {
            Result.success()
        }

    internal companion object {
        const val TAG = "SmsParserWorker"
        const val KEY_CANDIDATE_KEY = "candidate_key"
        const val WORK_TAG = "sms-parser-candidates"
        private const val APP_SETTINGS = ".app_settings"
        private const val MAX_RETRY_ATTEMPTS = 3

        fun uniqueWorkName(candidateKey: String): String =
            "sms-parser-$candidateKey"
    }
}

/**
 * Encrypted admission followed by an opaque, idempotent WorkManager handoff.
 */
@Singleton
class SmsWorkSchedulerImpl @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val admissionGate: SmsWorkAdmissionGate,
    private val ingestionRepository: SmsIngestionRepository,
    private val automaticProcessingPreferences: AutomaticProcessingPreferences
) : SmsWorkScheduler {
    override suspend fun scheduleSmsParsing(
        message: SmsReader.SmsMessage
    ): SmsScheduleResult =
        automaticProcessingPreferences.withConsistencyBoundary { enabled ->
            if (!enabled) {
                ingestionRepository.discardPendingAutomatic()
                return@withConsistencyBoundary SmsScheduleResult.AUTOMATIC_DISABLED
            }

            val admission = ingestionRepository.admit(
                SmsIngestionRepository.NewCandidate(
                    sourceIdentity = message.sourceIdentity,
                    sender = message.address,
                    rawMessage = message.body,
                    date = message.date,
                    sourceTimestamp = message.sourceTimestamp,
                    messageType = message.type,
                    origin = SmsCandidateOrigin.AUTOMATIC
                )
            )
            if (admission is SmsIngestionRepository.AdmissionResult.AlreadySaved) {
                return@withConsistencyBoundary SmsScheduleResult.ALREADY_SAVED
            }

            val candidateKey = admission.candidateKey
            if (!enqueueCandidate(candidateKey)) {
                ingestionRepository.discardPendingAutomatic()
                return@withConsistencyBoundary SmsScheduleResult.ADMISSION_PAUSED
            }
            SmsScheduleResult.SCHEDULED
        }

    override suspend fun reconcilePendingAutomaticWork() {
        val candidateKeys =
            automaticProcessingPreferences.withConsistencyBoundary { enabled ->
                if (!enabled) {
                    ingestionRepository.discardPendingAutomatic()
                    emptyList()
                } else {
                    ingestionRepository.pendingAutomaticCandidateKeys()
                }
            }
        if (candidateKeys.isEmpty()) {
            return
        }

        for (candidateKey in candidateKeys) {
            val shouldContinue =
                automaticProcessingPreferences.withConsistencyBoundary { enabled ->
                    if (!enabled) {
                        ingestionRepository.discardPendingAutomatic()
                        false
                    } else {
                        // A destructive maintenance boundary owns admission.
                        // The encrypted row remains durable for the next
                        // reconciliation.
                        enqueueCandidate(candidateKey)
                    }
                }
            if (!shouldContinue) {
                return
            }
        }
    }

    private fun enqueueCandidate(candidateKey: String): Boolean {
        val request = OneTimeWorkRequestBuilder<SmsParserWorker>()
            .setInputData(
                workDataOf(SmsParserWorker.KEY_CANDIDATE_KEY to candidateKey)
            )
            .addTag(SmsParserWorker.WORK_TAG)
            .build()
        val admitted = admissionGate.enqueueIfOpen {
            WorkManager.getInstance(context).enqueueUniqueWork(
                SmsParserWorker.uniqueWorkName(candidateKey),
                ExistingWorkPolicy.KEEP,
                request
            )
        }
        return admitted
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

internal sealed interface SmsCandidateClaimDecision {
    data object AutomaticDisabled : SmsCandidateClaimDecision

    data class Claimed(
        val candidate: QueuedSmsCandidate?
    ) : SmsCandidateClaimDecision
}

internal suspend fun claimSmsCandidateForRun(
    candidate: QueuedSmsCandidate,
    claimToken: String,
    automaticProcessingPreferences: AutomaticProcessingPreferences,
    ingestionRepository: SmsIngestionRepository
): SmsCandidateClaimDecision {
    if (candidate.origin != SmsCandidateOrigin.AUTOMATIC) {
        return SmsCandidateClaimDecision.Claimed(
            ingestionRepository.claim(
                candidateKey = candidate.candidateKey,
                claimToken = claimToken
            )
        )
    }

    return automaticProcessingPreferences.withConsistencyBoundary { enabled ->
        if (!enabled) {
            // OFF won before this invocation could establish a claim. Pending
            // evidence, or a same-WorkSpec claim left by process death, can be
            // removed without touching a replacement owner's claim.
            ingestionRepository.discardAutomaticBeforeClaim(
                candidateKey = candidate.candidateKey,
                claimToken = claimToken
            )
            SmsCandidateClaimDecision.AutomaticDisabled
        } else {
            SmsCandidateClaimDecision.Claimed(
                ingestionRepository.claim(
                    candidateKey = candidate.candidateKey,
                    claimToken = claimToken
                )
            )
        }
    }
}

internal suspend fun discardOwnedTerminalCandidate(
    ingestionRepository: SmsIngestionRepository,
    candidateKey: String,
    claimToken: String
): Boolean = ingestionRepository.discardClaimed(
    candidateKey = candidateKey,
    claimToken = claimToken
)

internal inline fun <T> foldIncomingSmsQueueResult(
    queueResult: IncomingSmsQueueResult,
    onQueuedTransaction: () -> T,
    onIgnored: () -> T,
    onAdmissionPaused: () -> T
): T = when (queueResult) {
    IncomingSmsQueueResult.QUEUED_TRANSACTION -> onQueuedTransaction()
    IncomingSmsQueueResult.IGNORED -> onIgnored()
    IncomingSmsQueueResult.ADMISSION_PAUSED -> onAdmissionPaused()
}

internal suspend fun <T> withSmsWorkerFlowAdmission(
    delegate: HomeSyncDelegate,
    onAdmissionPaused: () -> T,
    block: suspend () -> T
): T {
    val flowLease = delegate.tryEnterSmsWorkerFlow()
        ?: return onAdmissionPaused()
    return try {
        block()
    } finally {
        withContext(NonCancellable) {
            flowLease.release()
        }
    }
}

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
