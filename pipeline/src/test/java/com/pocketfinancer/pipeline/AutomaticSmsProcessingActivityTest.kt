package com.pocketfinancer.pipeline

import com.pocketfinancer.data.model.QueuedSmsCandidate
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.inference.SlmCacheDiagnostics
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmPerformanceData
import kotlin.test.assertFailsWith
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticSmsProcessingActivityTest {

    @Test
    fun `durable automatic claim is visible before any processing work`() =
        runTest {
            val store = AutomaticSmsProcessingActivityStore()
            val candidate = candidate(claimToken = "claim-1")

            val result = withAutomaticSmsProcessingActivity(
                candidate = candidate,
                claimToken = "claim-1",
                store = store
            ) { session ->
                assertNotNull(session)
                assertEquals(
                    AutomaticSmsProcessingActivity(
                        owner = AutomaticSmsProcessingOwner(
                            candidateKey = "candidate-1",
                            claimToken = "claim-1"
                        ),
                        sender = "AX-HDFCBK",
                        body = "Rs 500 debited",
                        date = 1_100L,
                        stage = AutomaticSmsProcessingStage.PREPARING,
                        detail = "Preparing automatic SMS processing."
                    ),
                    store.activity.value
                )
                "claimed-gap-observed"
            }

            assertEquals("claimed-gap-observed", result)
            assertNull(store.activity.value)
        }

    @Test
    fun `filter pass and model preparation remain visible until claim finishes`() =
        runTest {
            val store = AutomaticSmsProcessingActivityStore()

            withAutomaticSmsProcessingActivity(
                candidate = candidate(claimToken = "model-claim"),
                claimToken = "model-claim",
                store = store
            ) { session ->
                session!!
                session.filtering()
                assertEquals(
                    AutomaticSmsProcessingStage.FILTERING,
                    store.activity.value?.stage
                )
                assertNull(store.activity.value?.filterResult)

                session.filterPassed()
                session.loadingModel()
                assertEquals(
                    AutomaticSmsProcessingStage.LOADING_MODEL,
                    store.activity.value?.stage
                )
                assertEquals(
                    AutomaticSmsFilterResult.PASSED,
                    store.activity.value?.filterResult
                )
            }

            assertNull(store.activity.value)
        }

    @Test
    fun `missing model retry is visible only while exact claim is live`() =
        runTest {
            val store = AutomaticSmsProcessingActivityStore()

            withAutomaticSmsProcessingActivity(
                candidate = candidate(claimToken = "retry-claim"),
                claimToken = "retry-claim",
                store = store
            ) { session ->
                session!!.filterPassed()
                session.loadingModel()
                session.retrying("Waiting for the on-device model to be prepared.")
                val retry = store.activity.value
                assertEquals(AutomaticSmsProcessingStage.RETRYING, retry?.stage)
                assertEquals(
                    AutomaticSmsFilterResult.PASSED,
                    retry?.filterResult
                )
                assertEquals(
                    "Waiting for the on-device model to be prepared.",
                    retry?.detail
                )
            }

            assertNull(store.activity.value)
        }

    @Test
    fun `cancellation and exception exact-clear source evidence`() = runTest {
        val store = AutomaticSmsProcessingActivityStore()

        assertFailsWith<CancellationException> {
            withAutomaticSmsProcessingActivity(
                candidate = candidate(claimToken = "cancelled-claim"),
                claimToken = "cancelled-claim",
                store = store
            ) {
                assertEquals("Rs 500 debited", store.activity.value?.body)
                throw CancellationException("cancelled")
            }
        }
        assertNull(store.activity.value)

        assertFailsWith<IllegalStateException> {
            withAutomaticSmsProcessingActivity(
                candidate = candidate(claimToken = "failed-claim"),
                claimToken = "failed-claim",
                store = store
            ) {
                assertEquals("AX-HDFCBK", store.activity.value?.sender)
                error("failed")
            }
        }
        assertNull(store.activity.value)
    }

    @Test
    fun `stale owner cannot update clear or begin over its replacement`() {
        val store = AutomaticSmsProcessingActivityStore()
        val oldOwner = AutomaticSmsProcessingOwner("candidate-1", "old-claim")
        val old = store.begin(
            owner = oldOwner,
            sender = "Old sender",
            body = "Old source",
            date = 1L
        )!!
        old.close()

        val replacementOwner = AutomaticSmsProcessingOwner(
            "candidate-1",
            "replacement-claim"
        )
        val replacement = store.begin(
            owner = replacementOwner,
            sender = "New sender",
            body = "New source",
            date = 2L
        )!!
        val replacementSnapshot = store.activity.value!!

        old.retrying("A stale callback must be ignored.")
        old.close()
        assertFalse(store.clear(oldOwner))
        assertFalse(
            store.publish(
                oldOwner,
                replacementSnapshot.copy(owner = oldOwner)
            )
        )
        assertSame(replacementSnapshot, store.activity.value)

        assertNull(
            store.begin(
                owner = AutomaticSmsProcessingOwner(
                    "candidate-2",
                    "late-old-claim"
                ),
                sender = "Late sender",
                body = "Late source",
                date = 3L
            )
        )
        assertSame(replacementSnapshot, store.activity.value)

        replacement.close()
        assertNull(store.activity.value)
    }

    @Test
    fun `observer bounds output and omits private cache path`() {
        val store = AutomaticSmsProcessingActivityStore()
        var now = 0L
        val session = store.begin(
            owner = AutomaticSmsProcessingOwner("candidate-1", "observer-claim"),
            sender = "AX-HDFCBK",
            body = "Rs 500 debited",
            date = 1L,
            nanoTime = { now }
        )!!
        val model = model()
        session.onEvent(
            PipelineService.ProcessingEvent.InferenceStarted(
                model = model,
                thinkingEnabled = true,
                grammarEnabled = true,
                thinkingTokenBudget = 1_024,
                answerTokenBudget = 256
            )
        )
        now += 100_000_000L
        session.onEvent(
            PipelineService.ProcessingEvent.ThinkingTokenDelta(
                "t".repeat(MAX_AUTOMATIC_OUTPUT_CHARS + 10)
            )
        )
        now += 100_000_000L
        session.onEvent(
            PipelineService.ProcessingEvent.JsonTokenDelta(
                "j".repeat(MAX_AUTOMATIC_OUTPUT_CHARS + 10)
            )
        )
        session.onEvent(
            PipelineService.ProcessingEvent.InferenceCompleted(
                SlmExtractionResult.Success(
                    json = "{\"amount\":500}",
                    perf = SlmPerformanceData(
                        tLoadMs = 10L,
                        tPromptEvalMs = 20L,
                        tEvalMs = 40L,
                        nTokens = 8
                    ),
                    model = model,
                    cache = SlmCacheDiagnostics(
                        attempted = true,
                        hit = true,
                        sessionFile = "/private/session/path",
                        prefixTokens = 12
                    )
                )
            )
        )

        val activity = store.activity.value!!
        assertEquals(MAX_AUTOMATIC_OUTPUT_CHARS, activity.thinkingOutput.length)
        assertTrue(activity.thinkingOutputTruncated)
        assertEquals("{\"amount\":500}", activity.jsonOutput)
        assertFalse(activity.jsonOutputTruncated)
        assertEquals(
            AutomaticSmsSlmPerformance(
                promptEvalMs = 20L,
                evalMs = 40L,
                generatedTokens = 8
            ),
            activity.performance
        )
        assertEquals(
            AutomaticSmsSlmCacheTelemetry(
                attempted = true,
                hit = true,
                prefixTokens = 12
            ),
            activity.cache
        )
        session.close()
        assertNull(store.activity.value)
    }

    @Test
    fun `manual worker remains independent from automatic activity`() = runTest {
        val store = AutomaticSmsProcessingActivityStore()
        val automatic = store.begin(
            owner = AutomaticSmsProcessingOwner("candidate-auto", "auto-claim"),
            sender = "Automatic sender",
            body = "Automatic source",
            date = 1L
        )!!
        val snapshot = store.activity.value

        withAutomaticSmsProcessingActivity(
            candidate = candidate(
                candidateKey = "candidate-manual",
                claimToken = "manual-claim",
                origin = SmsCandidateOrigin.MANUAL
            ),
            claimToken = "manual-claim",
            store = store
        ) { session ->
            assertNull(session)
            assertSame(snapshot, store.activity.value)
        }

        assertSame(snapshot, store.activity.value)
        automatic.close()
        assertNull(store.activity.value)
    }

    private fun candidate(
        candidateKey: String = "candidate-1",
        claimToken: String,
        origin: SmsCandidateOrigin = SmsCandidateOrigin.AUTOMATIC
    ): QueuedSmsCandidate = QueuedSmsCandidate(
        candidateKey = candidateKey,
        sourceIdentity = SmsSourceIdentity.androidSms(
            providerMessageId = "provider-id",
            sender = "AX-HDFCBK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1,
            receivedTimestamp = 1_100L
        ),
        sender = "AX-HDFCBK",
        rawMessage = "Rs 500 debited",
        date = 1_100L,
        sourceTimestamp = 1_000L,
        messageType = 1,
        origin = origin,
        claimToken = claimToken,
        attemptCount = 1
    )

    private fun model(): SlmModelSpec = SlmModelSpec(
        modelId = "test-model",
        modelPath = "test-model.gguf",
        hasThinkingMode = true
    )
}
