package com.pocketfinancer.ui.onboarding

import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.inference.SlmCacheDiagnostics
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmPerformanceData
import com.pocketfinancer.pipeline.PipelineService
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class HistoricalSmsProcessingActivityTest {

    private val model = SlmModelSpec(
        modelId = "test-model",
        modelPath = "build/test-history-model.gguf",
        hasThinkingMode = true
    )

    @Test
    fun `direct-output model enters JSON generation with exact request facts`() {
        val snapshots = mutableListOf<HistoricalSmsProcessingActivity>()
        val observer = HistoricalSmsProcessingObserver(
            initial = activity(),
            publish = { snapshots += it }
        )

        observer.onEvent(
            PipelineService.ProcessingEvent.InferenceStarted(
                model = SlmModelSpec(
                    modelId = "direct-model",
                    modelPath = "build/direct-model.gguf",
                    hasThinkingMode = false
                ),
                thinkingEnabled = false,
                grammarEnabled = true,
                thinkingTokenBudget = 1_024,
                answerTokenBudget = 256
            )
        )

        val latest = snapshots.last()
        assertEquals(HistoricalSmsProcessingStage.GENERATING, latest.stage)
        assertFalse(latest.hasThinkingMode)
        assertEquals("direct-model.gguf", latest.modelName)
        assertEquals(true, latest.grammarEnabled)
        assertEquals(1_024, latest.thinkingTokenBudget)
        assertEquals(256, latest.answerTokenBudget)
    }

    @Test
    fun `token deltas are coalesced and flush preserves complete outputs`() {
        val now = 1L
        val snapshots = mutableListOf<HistoricalSmsProcessingActivity>()
        val observer = HistoricalSmsProcessingObserver(
            initial = activity(),
            publish = { snapshots += it },
            nanoTime = { now }
        )
        observer.onEvent(inferenceStarted())
        val beforeTokens = snapshots.size

        observer.onEvent(
            PipelineService.ProcessingEvent.ThinkingTokenDelta("first ")
        )
        observer.onEvent(
            PipelineService.ProcessingEvent.ThinkingTokenDelta("second")
        )
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("{\"amount\":")
        )
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("500}")
        )
        observer.flush()

        assertEquals(beforeTokens + 2, snapshots.size)
        assertEquals("first second", snapshots.last().thinkingOutput)
        assertEquals("{\"amount\":500}", snapshots.last().jsonOutput)
        assertEquals(
            HistoricalSmsProcessingStage.GENERATING,
            snapshots.last().stage
        )
    }

    @Test
    fun `inference diagnostics and persistence stage are published exactly`() {
        val snapshots = mutableListOf<HistoricalSmsProcessingActivity>()
        val observer = HistoricalSmsProcessingObserver(
            initial = activity(),
            publish = { snapshots += it }
        )
        observer.onEvent(inferenceStarted())
        observer.onEvent(
            PipelineService.ProcessingEvent.InferenceCompleted(
                SlmExtractionResult.Success(
                    json = "{\"amount\":500}",
                    perf = SlmPerformanceData(
                        tLoadMs = 4,
                        tPromptEvalMs = 20,
                        tEvalMs = 200,
                        nTokens = 10
                    ),
                    model = model,
                    cache = SlmCacheDiagnostics(
                        attempted = true,
                        hit = true,
                        sessionFile = "private/session.bin",
                        prefixTokens = 42
                    )
                )
            )
        )
        observer.onEvent(PipelineService.ProcessingEvent.PersistenceStarted)

        val latest = snapshots.last()
        assertEquals(HistoricalSmsProcessingStage.PERSISTING, latest.stage)
        assertEquals("{\"amount\":500}", latest.jsonOutput)
        assertEquals(20L, latest.performance?.promptEvalMs)
        assertEquals(200L, latest.performance?.evalMs)
        assertEquals(10, latest.performance?.generatedTokens)
        assertEquals(
            HistoricalSlmCacheTelemetry(
                attempted = true,
                hit = true,
                prefixTokens = 42
            ),
            latest.cache
        )
    }

    @Test
    fun `transient model output is bounded and marked when truncated`() {
        val snapshots = mutableListOf<HistoricalSmsProcessingActivity>()
        val observer = HistoricalSmsProcessingObserver(
            initial = activity(),
            publish = { snapshots += it }
        )
        observer.onEvent(inferenceStarted())
        observer.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta("x".repeat(70_000))
        )
        observer.flush()

        assertEquals(64_000, snapshots.last().jsonOutput.length)
        assertTrue(snapshots.last().jsonOutputTruncated)
    }

    @Test
    fun `run and candidate ownership reject stale telemetry and permit scrub`() {
        val manager = OnboardingSyncManager(
            runGenerationStore = mockk(relaxed = true),
            appFlowCoordinator = SlmAppFlowCoordinator()
        )
        manager.updateState {
            OnboardingSyncManager.OnboardingSyncState(
                runId = "run-1",
                isRunning = true,
                runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP
            )
        }
        assertTrue(manager.beginHistoricalSmsProcessing("run-1", activity()))

        assertFalse(
            manager.updateHistoricalSmsProcessing("stale-run", "candidate-1") {
                it.copy(body = "stale body")
            }
        )
        assertFalse(
            manager.updateHistoricalSmsProcessing("run-1", "other-candidate") {
                it.copy(body = "wrong candidate")
            }
        )
        assertEquals("raw body", manager.syncState.value.activeHistoricalSms?.body)

        val next = activity().copy(
            candidateKey = "candidate-2",
            body = "next raw body",
            position = 2
        )
        assertTrue(manager.beginHistoricalSmsProcessing("run-1", next))
        assertEquals(
            "next raw body",
            manager.syncState.value.activeHistoricalSms?.body
        )
        assertFalse(
            manager.updateHistoricalSmsProcessing("run-1", "candidate-1") {
                it.copy(body = "late callback")
            }
        )

        manager.updateState { it.copy(isRunning = false) }
        assertTrue(
            manager.clearHistoricalSmsProcessing(
                runId = "run-1",
                candidateKey = "candidate-2"
            )
        )
        assertNull(manager.syncState.value.activeHistoricalSms)
    }

    @Test
    fun `completed state scrubs all active source and model output`() {
        val completed = scrubCompletedOnboardingState(
            OnboardingSyncManager.OnboardingSyncState(
                runId = "run-1",
                isRunning = true,
                activeHistoricalSms = activity().copy(
                    thinkingOutput = "private reasoning",
                    jsonOutput = "private output"
                )
            )
        )

        assertNull(completed.activeHistoricalSms)
    }

    @Test
    fun `historical cancellation settlement scrubs active source and tokens`() {
        val manager = OnboardingSyncManager(
            runGenerationStore = mockk(relaxed = true),
            appFlowCoordinator = SlmAppFlowCoordinator()
        )
        manager.updateState {
            OnboardingSyncManager.OnboardingSyncState(
                runId = "run-1",
                isRunning = true,
                isCancelling = true,
                isCancellationAllowed = true,
                runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
                activeHistoricalSms = activity().copy(
                    thinkingOutput = "private reasoning",
                    jsonOutput = "private output"
                )
            )
        }

        assertTrue(manager.completeHistoricalImportCancellation("run-1"))
        assertNull(manager.syncState.value.activeHistoricalSms)
    }

    private fun activity() = HistoricalSmsProcessingActivity(
        candidateKey = "candidate-1",
        sender = "AX-HDFCBK",
        body = "raw body",
        date = 1_000L,
        position = 1,
        total = 2
    )

    private fun inferenceStarted() =
        PipelineService.ProcessingEvent.InferenceStarted(
            model = model,
            thinkingEnabled = true,
            grammarEnabled = false,
            thinkingTokenBudget = 1_024,
            answerTokenBudget = 256
        )
}
