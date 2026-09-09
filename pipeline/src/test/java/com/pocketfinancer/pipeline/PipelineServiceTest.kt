package com.pocketfinancer.pipeline

import com.pocketfinancer.data.repository.ProcessingConfigurationRepository
import com.pocketfinancer.data.repository.SmsProcessingStore
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.pipeline.sms.DefaultSmsProcessingCoordinator
import com.pocketfinancer.pipeline.sms.SmsOperationConfiguration
import com.pocketfinancer.pipeline.sms.SmsOperationSnapshot
import com.pocketfinancer.pipeline.sms.SmsOperationSnapshotFactory
import com.pocketfinancer.pipeline.sms.SmsProcessingOutcome
import com.pocketfinancer.sms.SmsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class PipelineServiceTest {
    private lateinit var store: SmsProcessingStore
    private lateinit var snapshotFactory: SmsOperationSnapshotFactory
    private lateinit var configuration: ProcessingConfigurationRepository
    private lateinit var coordinator: DefaultSmsProcessingCoordinator
    private lateinit var lease: SlmLease
    private lateinit var pipeline: PipelineService
    private val snapshot = snapshot()

    @Before
    fun setUp() {
        store = mockk(relaxed = true)
        snapshotFactory = mockk()
        configuration = mockk()
        coordinator = mockk()
        lease = mockk()
        every { lease.model } returns SlmModelSpec(
            modelId = "test-selector",
            modelPath = "synthetic-model.gguf"
        )
        every { configuration.enabledProfiles("INR") } returns listOf("core-en", "india")
        coEvery {
            snapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any()
            )
        } returns snapshot
        pipeline = PipelineService(store, snapshotFactory, configuration, coordinator)
    }

    @Test
    fun `pipeline starts with null state`() = runTest {
        assertNull(pipeline.pipelineState.first())
    }

    @Test
    fun `source is admitted before missing configuration pauses processing`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns null

        val result = pipeline.processSingle(message(), lease)

        assertEquals(PipelineService.ProcessingResult.AwaitingConfiguration, result)
        coVerify(exactly = 1) { store.admitSource(any()) }
        coVerify(exactly = 0) {
            snapshotFactory.create(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any()
            )
        }
        coVerify(exactly = 0) { coordinator.processUsingLease(any(), any(), any(), any()) }
    }

    @Test
    fun `configured processing delegates once and retains review outcome`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { coordinator.processUsingLease(any(), snapshot, lease, any()) } returns
            SmsProcessingOutcome.RetainedForReview(
                snapshot.operationId,
                "review-id",
                listOf("persistence_blocked_by_rollout_mode")
            )

        val result = pipeline.processSingle(message(), lease, trigger = "manual")

        val skipped = assertIs<PipelineService.ProcessingResult.Skipped>(result)
        assertEquals(PipelineService.SkipReason.RETAINED_FOR_REVIEW, skipped.reason)
        coVerify(exactly = 1) { store.admitSource(any()) }
        coVerify(exactly = 1) {
            coordinator.processUsingLease(any(), snapshot, lease, any())
        }
    }

    @Test
    fun `worker entrypoint lets coordinator retain an unavailable model`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        every { coordinator.currentSelectorModelId() } returns null
        coEvery { coordinator.process(any(), snapshot, any()) } returns
            SmsProcessingOutcome.RetainedForReview(
                snapshot.operationId,
                "review-id",
                listOf("runtime_unavailable")
            )

        val result = pipeline.processSingle(message(), trigger = "realtime")

        val skipped = assertIs<PipelineService.ProcessingResult.Skipped>(result)
        assertEquals(PipelineService.SkipReason.RETAINED_FOR_REVIEW, skipped.reason)
        coVerify(exactly = 1) { coordinator.process(any(), snapshot, any()) }
        coVerify(exactly = 0) {
            coordinator.processUsingLease(any(), any(), any(), any())
        }
    }

    @Test
    fun `retryable coordinator outcome remains retryable`() = runTest {
        every { configuration.confirmedPrimaryCurrency() } returns "INR"
        coEvery { coordinator.processUsingLease(any(), snapshot, lease, any()) } returns
            SmsProcessingOutcome.RetryableFailure(
                snapshot.operationId,
                "review-id",
                "runtime_unavailable",
                "model_available"
            )

        val failure = assertIs<PipelineService.ProcessingResult.Failure>(
            pipeline.processSingle(message(), lease)
        )

        assertEquals(true, failure.retryable)
        assertEquals("Saved for retry: runtime_unavailable", failure.message)
    }

    private fun message() = SmsReader.SmsMessage(
        address = "SYNTH",
        body = "INR 10 was debited from account **1234 at SYNTH SHOP.",
        date = 1_700_000_000_000,
        type = 1,
        providerMessageId = "synthetic-provider-id"
    )

    private fun snapshot(): SmsOperationSnapshot {
        val operationId = "11111111-1111-4111-8111-111111111111"
        return SmsOperationSnapshot(
            operationId = operationId,
            parentOperationId = null,
            stableEventId = "22222222-2222-4222-8222-222222222222",
            configuration = SmsOperationConfiguration(
                operationId = operationId,
                parentOperationId = null,
                sourceId = "synthetic-source",
                sourceRefHash = "a".repeat(64),
                trigger = "manual",
                createdAtEpochMs = 1_700_000_000_000,
                primaryCurrency = "INR",
                enabledProfiles = listOf("core-en", "india"),
                sourceTimestampEpochMs = 1_700_000_000_000,
                sourceTimestampProvenance = "acquisition_supplied_message_time",
                admissionTimestampEpochMs = 1_700_000_000_000,
                timezoneId = "UTC",
                releaseManifestHash = "b".repeat(64),
                currencyAssetHash = "c".repeat(64),
                profileAssetHashes = emptyMap(),
                selectorEligible = true,
                selectorIneligibilityReason = null,
                selectorModelId = "test-selector",
                selectorModelHash = null,
                selectorRuntimeVersion = "test-runtime",
                osVersion = "test-os",
                deviceCohort = "test-device",
                promptHash = "d".repeat(64)
            ),
            configurationJson = "{}",
            configurationHash = "e".repeat(64)
        )
    }
}
