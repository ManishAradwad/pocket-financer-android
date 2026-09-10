package com.pocketfinancer.pipeline

import com.pocketfinancer.inference.SlmModelSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutomaticSmsProcessingActivityTest {
    @Test
    fun `direct selector activity exposes only bounded json`() {
        val store = AutomaticSmsProcessingActivityStore()
        val owner = AutomaticSmsProcessingOwner("candidate", "claim")
        val session = requireNotNull(store.begin(owner, "BANK", "INR 10 debited", 1L))

        session.onEvent(
            PipelineService.ProcessingEvent.InferenceStarted(
                model = SlmModelSpec("test", "/tmp/test.gguf"),
                grammarEnabled = true,
                answerTokenBudget = 512
            )
        )
        session.onEvent(PipelineService.ProcessingEvent.JsonTokenDelta("x".repeat(70_000)))

        val activity = requireNotNull(store.activity.value)
        assertEquals(AutomaticSmsProcessingStage.GENERATING, activity.stage)
        assertEquals(512, activity.answerTokenBudget)
        assertEquals(64_000, activity.jsonOutput.length)
        assertTrue(activity.jsonOutputTruncated)

        session.close()
        assertNull(store.activity.value)
    }

    @Test
    fun `stale owner cannot clear successor activity`() {
        val store = AutomaticSmsProcessingActivityStore()
        val owner = AutomaticSmsProcessingOwner("candidate", "claim")
        val session = requireNotNull(store.begin(owner, "BANK", "body", 1L))

        assertTrue(store.clear(owner))
        session.close()
        assertNull(store.activity.value)
    }
}
