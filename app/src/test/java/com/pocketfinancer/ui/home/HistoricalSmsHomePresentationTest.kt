package com.pocketfinancer.ui.home

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.pocketfinancer.ui.onboarding.HistoricalSlmCacheTelemetry
import com.pocketfinancer.ui.onboarding.HistoricalSlmPerformance
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingActivity
import com.pocketfinancer.ui.onboarding.HistoricalSmsProcessingStage
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import com.pocketfinancer.ui.onboarding.withoutHistoricalSmsActivity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalSmsHomePresentationTest {

    @Test
    fun `home exposes activity only while initial setup is active`() {
        val activity = activity()

        assertSame(
            activity,
            activeHistoricalSmsForHome(
                OnboardingSyncManager.OnboardingSyncState(
                    isRunning = true,
                    runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
                    activeHistoricalSms = activity
                )
            )
        )
        assertNull(
            activeHistoricalSmsForHome(
                OnboardingSyncManager.OnboardingSyncState(
                    isRunning = false,
                    runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
                    activeHistoricalSms = activity
                )
            )
        )
        assertNull(
            activeHistoricalSmsForHome(
                OnboardingSyncManager.OnboardingSyncState(
                    isRunning = true,
                    runPurpose = OnboardingSyncManager.RunPurpose.MODEL_UPGRADE,
                    activeHistoricalSms = activity
                )
            )
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `sensitive activity is scrubbed while home has no collector`() = runTest {
        val activity = activity()
        val source = MutableStateFlow(
            OnboardingSyncManager.OnboardingSyncState(
                isRunning = true,
                runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
                activeHistoricalSms = activity
            )
        )
        val exposed = historicalSmsCardState(source, backgroundScope)
        runCurrent()
        assertEquals(activity.cardSnapshot(), exposed.value)

        val cardBeforeTokenUpdate = exposed.value
        source.value = source.value.copy(
            activeHistoricalSms = activity.copy(
                thinkingOutput = "private reasoning",
                jsonOutput = "private output"
            )
        )
        runCurrent()
        assertSame(cardBeforeTokenUpdate, exposed.value)

        source.value = source.value.copy(
            isRunning = false,
            activeHistoricalSms = null
        )
        runCurrent()

        assertNull(exposed.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `stopped home immediately drops activity and stays scrubbed`() = runTest {
        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val activity = activity().copy(
            thinkingOutput = "private reasoning",
            jsonOutput = "private output"
        )
        val source = MutableStateFlow<HistoricalSmsProcessingActivity?>(activity)
        val owner = TestLifecycleOwner()
        var displayed: HistoricalSmsProcessingActivity? = null
        val collection = backgroundScope.launch(dispatcher) {
            collectHistoricalSmsWhileStarted(
                lifecycle = owner.lifecycle,
                source = source,
                publish = { displayed = it }
            )
        }

        try {
            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertSame(activity, displayed)

            owner.registry.currentState = Lifecycle.State.CREATED
            runCurrent()
            assertNull(displayed)

            source.value = null
            runCurrent()
            assertNull(displayed)

            owner.registry.currentState = Lifecycle.State.STARTED
            runCurrent()
            assertNull(displayed)

            owner.registry.currentState = Lifecycle.State.DESTROYED
        } finally {
            collection.cancel()
            Dispatchers.resetMain()
        }
    }

    @Test
    fun `token-only activity changes are excluded from aggregate home state`() {
        val first = OnboardingSyncManager.OnboardingSyncState(
            isRunning = true,
            runPurpose = OnboardingSyncManager.RunPurpose.INITIAL_SETUP,
            activeHistoricalSms = activity().copy(thinkingOutput = "first")
        )
        val next = first.copy(
            activeHistoricalSms = first.activeHistoricalSms?.copy(
                thinkingOutput = "second",
                jsonOutput = "private output"
            )
        )

        assertEquals(
            first.withoutHistoricalSmsActivity(),
            next.withoutHistoricalSmsActivity()
        )
        assertNull(next.withoutHistoricalSmsActivity().activeHistoricalSms)
    }

    @Test
    fun `open telemetry survives candidate gaps but closes with the run`() {
        assertTrue(
            historicalTelemetryIsVisible(
                requested = true,
                historicalImportRunning = true
            )
        )
        assertFalse(
            historicalTelemetryIsVisible(
                requested = true,
                historicalImportRunning = false
            )
        )
        assertFalse(
            historicalTelemetryIsVisible(
                requested = false,
                historicalImportRunning = true
            )
        )
    }

    @Test
    fun `truncated JSON never produces a false non-financial parse label`() {
        val generating = activity().copy(
            stage = HistoricalSmsProcessingStage.GENERATING,
            jsonOutput = "{\"amount\":500",
            jsonOutputTruncated = true
        )
        val persisting = generating.copy(
            stage = HistoricalSmsProcessingStage.PERSISTING
        )

        assertEquals(
            "Live JSON preview truncated; waiting for inference to finish.",
            historicalParsedOutput(generating) {
                error("truncated preview must not be parsed")
            }
        )
        assertEquals(
            "Parsed successfully; full JSON was omitted from the live display.",
            historicalParsedOutput(persisting) {
                error("truncated output must not be parsed")
            }
        )
    }

    @Test
    fun `telemetry mapping preserves exact runtime and cache facts`() {
        val activity = activity().copy(
            grammarEnabled = false,
            thinkingTokenBudget = 1024,
            answerTokenBudget = 256,
            performance = HistoricalSlmPerformance(
                promptEvalMs = 321,
                evalMs = 2_500,
                generatedTokens = 75
            ),
            cache = HistoricalSlmCacheTelemetry(
                attempted = true,
                hit = false,
                prefixTokens = 411
            )
        )

        val facts = activity.toTelemetryRuntimeFacts()
        assertEquals(false, facts?.grammarEnabled)
        assertEquals(1024, facts?.thinkingTokenBudget)
        assertEquals(256, facts?.answerTokenBudget)
        assertEquals(321L, facts?.promptEvalMs)
        assertEquals(2_500L, facts?.evalMs)
        assertEquals(75, facts?.generatedTokens)
        assertEquals(true, facts?.cacheAttempted)
        assertEquals(false, facts?.cacheHit)
        assertEquals(411, facts?.cachePrefixTokens)

        val logs = activity.historicalCacheLogs()
        assertEquals("Prefix cache attempted: true", logs[0])
        assertEquals("Prefix cache hit: false", logs[1])
        assertEquals("Cached prefix tokens: 411", logs[2])
        assertFalse(logs.joinToString().contains("session", ignoreCase = true))
    }

    private fun activity() = HistoricalSmsProcessingActivity(
        candidateKey = "opaque-key",
        sender = "Bank",
        body = "Account ending 6254 was debited.",
        date = 0L,
        position = 1,
        total = 4
    )

    private class TestLifecycleOwner : LifecycleOwner {
        val registry: LifecycleRegistry = LifecycleRegistry.createUnsafe(this)
        override val lifecycle: Lifecycle = registry
    }
}
