package com.pocketfinancer.ui.onboarding

import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.pipeline.PipelineService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoricalSmsProcessingActivityTest {
    @Test
    fun `direct selector events expose json without a reasoning channel`() {
        val snapshots = mutableListOf<HistoricalSmsProcessingActivity>()
        val observer = HistoricalSmsProcessingObserver(activity(), snapshots::add)
        observer.onEvent(
            PipelineService.ProcessingEvent.InferenceStarted(
                model = SlmModelSpec("test", "/tmp/test.gguf"),
                grammarEnabled = true,
                answerTokenBudget = 512
            )
        )
        observer.onEvent(PipelineService.ProcessingEvent.JsonTokenDelta("{\"decision\":"))
        observer.onEvent(PipelineService.ProcessingEvent.JsonTokenDelta("\"abstain\"}"))
        observer.flush()

        val latest = snapshots.last()
        assertEquals(HistoricalSmsProcessingStage.GENERATING, latest.stage)
        assertEquals("{\"decision\":\"abstain\"}", latest.jsonOutput)
        assertEquals(512, latest.answerTokenBudget)
        assertTrue(latest.grammarEnabled == true)
    }

    @Test
    fun `json preview is bounded`() {
        val snapshots = mutableListOf<HistoricalSmsProcessingActivity>()
        val observer = HistoricalSmsProcessingObserver(activity(), snapshots::add)
        observer.onEvent(PipelineService.ProcessingEvent.JsonTokenDelta("x".repeat(70_000)))
        observer.flush()

        assertEquals(64_000, snapshots.last().jsonOutput.length)
        assertTrue(snapshots.last().jsonOutputTruncated)
    }

    private fun activity() = HistoricalSmsProcessingActivity(
        candidateKey = "candidate",
        sender = "BANK",
        body = "INR 10 debited",
        date = 1L,
        position = 1,
        total = 1
    )
}
