package com.pocketfinancer.ui.onboarding

import com.pocketfinancer.pipeline.PipelineService
import java.io.File

/**
 * Process-only detail for the one historical SMS candidate currently being
 * evaluated. Raw source evidence and model output must never be persisted in
 * setup progress, WorkManager data, or saved UI state.
 */
data class HistoricalSmsProcessingActivity(
    val candidateKey: String,
    val sender: String,
    val body: String,
    val date: Long,
    val position: Int,
    val total: Int,
    val stage: HistoricalSmsProcessingStage =
        HistoricalSmsProcessingStage.FILTERING,
    val modelName: String? = null,
    val grammarEnabled: Boolean? = null,
    val answerTokenBudget: Int = 0,
    val jsonOutput: String = "",
    val jsonOutputTruncated: Boolean = false,
    val performance: HistoricalSlmPerformance? = null,
    val cache: HistoricalSlmCacheTelemetry? = null
) {
    init {
        require(candidateKey.isNotBlank()) {
            "Historical SMS activity requires an opaque candidate key"
        }
        require(position > 0) { "Historical SMS position must be positive" }
        require(total > 0) { "Historical SMS total must be positive" }
        require(position <= total) {
            "Historical SMS position cannot exceed its total"
        }
        require(answerTokenBudget >= 0) {
            "Answer token budget must not be negative"
        }
    }

    val stageIndex: Int
        get() = when (stage) {
            HistoricalSmsProcessingStage.FILTERING -> 0
            HistoricalSmsProcessingStage.GENERATING -> 1
            HistoricalSmsProcessingStage.PERSISTING -> 2
    }
}

internal fun OnboardingSyncManager.OnboardingSyncState
    .withoutHistoricalSmsActivity(): OnboardingSyncManager.OnboardingSyncState =
    copy(activeHistoricalSms = null)

enum class HistoricalSmsProcessingStage {
    FILTERING,
    GENERATING,
    PERSISTING
}

data class HistoricalSlmPerformance(
    val promptEvalMs: Long,
    val evalMs: Long,
    val generatedTokens: Int
) {
    val tokensPerSecond: Double
        get() = if (evalMs > 0L) {
            generatedTokens.toDouble() / (evalMs / 1_000.0)
        } else {
            0.0
        }
}

/** Deliberately omits the internal session-file path. */
data class HistoricalSlmCacheTelemetry(
    val attempted: Boolean,
    val hit: Boolean,
    val prefixTokens: Int
)

/**
 * Coalesces native token callbacks before publishing Compose-facing snapshots.
 * This avoids rebuilding ever-growing strings and recomposing once per token
 * while still flushing stage changes immediately.
 */
internal class HistoricalSmsProcessingObserver(
    initial: HistoricalSmsProcessingActivity,
    private val publish: (HistoricalSmsProcessingActivity) -> Unit,
    private val nanoTime: () -> Long = System::nanoTime
) : PipelineService.ProcessingObserver {
    private var activity = initial
    private val json = StringBuilder(initial.jsonOutput)
    private var jsonTruncated = initial.jsonOutputTruncated
    private var lastPublishedNanos: Long? = null

    @Synchronized
    override fun onEvent(event: PipelineService.ProcessingEvent) {
        when (event) {
            is PipelineService.ProcessingEvent.GroundedStage -> {
                activity = activity.copy(
                    stage = when (event.stage) {
                        "claim", "analysis", "triage" ->
                            HistoricalSmsProcessingStage.FILTERING
                        "selector_execution", "selector_validation", "reconstruction" ->
                            HistoricalSmsProcessingStage.GENERATING
                        "account_resolution", "persistence_gate", "settlement" ->
                            HistoricalSmsProcessingStage.PERSISTING
                        else -> activity.stage
                    }
                )
                publishSnapshot(force = true)
            }

            PipelineService.ProcessingEvent.DeterministicFilterStarted,
            PipelineService.ProcessingEvent.DeterministicFilterPassed,
            PipelineService.ProcessingEvent.DeterministicFilterRejected -> {
                activity = activity.copy(
                    stage = HistoricalSmsProcessingStage.FILTERING
                )
                publishSnapshot(force = true)
            }

            is PipelineService.ProcessingEvent.InferenceStarted -> {
                activity = activity.copy(
                    stage = HistoricalSmsProcessingStage.GENERATING,
                    modelName = File(event.model.modelPath).name,
                    grammarEnabled = event.grammarEnabled,
                    answerTokenBudget = event.answerTokenBudget
                )
                publishSnapshot(force = true)
            }

            is PipelineService.ProcessingEvent.JsonTokenDelta -> {
                val enteredJson =
                    activity.stage != HistoricalSmsProcessingStage.GENERATING
                jsonTruncated = json.appendBounded(event.delta) || jsonTruncated
                activity = activity.copy(
                    stage = HistoricalSmsProcessingStage.GENERATING
                )
                publishSnapshot(force = enteredJson)
            }

            is PipelineService.ProcessingEvent.InferenceCompleted -> {
                event.json?.let { completedJson ->
                    json.clear()
                    jsonTruncated = json.appendBounded(completedJson)
                }
                activity = activity.copy(
                    stage = HistoricalSmsProcessingStage.GENERATING,
                    modelName = File(event.model.modelPath).name,
                    performance = event.perf?.let { perf ->
                        HistoricalSlmPerformance(
                            promptEvalMs = perf.tPromptEvalMs,
                            evalMs = perf.tEvalMs,
                            generatedTokens = perf.nTokens
                        )
                    },
                    cache = event.cache?.let { cache ->
                        HistoricalSlmCacheTelemetry(
                            attempted = cache.attempted,
                            hit = cache.hit,
                            prefixTokens = cache.prefixTokens
                        )
                    }
                )
                publishSnapshot(force = true)
            }

            PipelineService.ProcessingEvent.PersistenceStarted -> {
                activity = activity.copy(
                    stage = HistoricalSmsProcessingStage.PERSISTING
                )
                publishSnapshot(force = true)
            }
        }
    }

    @Synchronized
    fun flush() {
        publishSnapshot(force = true)
    }

    private fun publishSnapshot(force: Boolean) {
        val now = nanoTime()
        val previous = lastPublishedNanos
        if (
            !force &&
            previous != null &&
            now - previous < TOKEN_PUBLISH_INTERVAL_NANOS
        ) {
            return
        }
        activity = activity.copy(
            jsonOutput = json.toString(),
            jsonOutputTruncated = jsonTruncated
        )
        publish(activity)
        lastPublishedNanos = now
    }

    /** Returns true when any part of [delta] could not be retained. */
    private fun StringBuilder.appendBounded(delta: String): Boolean {
        val remaining = MAX_TRANSIENT_OUTPUT_CHARS - length
        if (remaining <= 0) return delta.isNotEmpty()
        if (delta.length <= remaining) {
            append(delta)
            return false
        }
        append(delta, 0, remaining)
        return true
    }

    private companion object {
        const val MAX_TRANSIENT_OUTPUT_CHARS = 64_000
        const val TOKEN_PUBLISH_INTERVAL_NANOS = 50_000_000L
    }
}
