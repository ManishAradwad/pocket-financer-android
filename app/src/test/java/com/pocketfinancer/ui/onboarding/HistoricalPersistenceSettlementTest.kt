package com.pocketfinancer.ui.onboarding

import android.content.Context
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.data.model.Account
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.pipeline.PipelineService
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.setup.FakeSharedPreferences
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.sms.SmsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoricalPersistenceSettlementTest {

    @Test
    fun `stop during ledger commit also durably settles historical counters`() =
        runTest {
            val fakePreferences = FakeSharedPreferences()
            val setupImportStore = SetupImportStore(
                preferences = fakePreferences.preferences,
                hasSmsPermissions = true
            )
            setupImportStore.update {
                it.copy(
                    status = SetupImportStatus.PROCESSING,
                    modelDownloadConfirmed = true,
                    modelPrepared = true
                )
            }
            val fixture = pipelineFixture()

            val insertStarted = CompletableDeferred<Unit>()
            val allowInsertToFinish = CompletableDeferred<Unit>()
            val ledgerCommitted = AtomicBoolean(false)
            val insertResult = mockk<TransactionRepository.InsertResult>()
            every { insertResult.inserted } returns true
            coEvery {
                fixture.transactionRepository.insertIfAbsent(any())
            } coAnswers {
                insertStarted.complete(Unit)
                allowInsertToFinish.await()
                ledgerCommitted.set(true)
                insertResult
            }
            var counters = HistoricalImportCounters()
            var settlement: HistoricalPersistenceSettlement? = null

            val processing = async {
                fixture.pipeline.processSingle(
                    sms = transactionSms(),
                    lease = fixture.lease,
                    onPersistenceCommitted = { committed ->
                        settlement = settleHistoricalPersistedResult(
                            setupImportStore = setupImportStore,
                            alreadySavedCount = 0,
                            counters = counters,
                            result = committed
                        )
                        counters = checkNotNull(settlement).counters
                    }
                )
            }
            insertStarted.await()

            processing.cancel(CancellationException("user stopped history import"))
            allowInsertToFinish.complete(Unit)
            processing.join()

            assertTrue(processing.isCancelled)
            assertTrue(ledgerCommitted.get())
            coVerify(exactly = 1) {
                fixture.transactionRepository.insertIfAbsent(any())
            }
            assertTrue(checkNotNull(settlement).isDurable)
            assertEquals(1, setupImportStore.state.value.processedCount)
            assertEquals(1, setupImportStore.state.value.savedCount)
            assertEquals(1, counters.parsedCount)

            val restartedStore = SetupImportStore(
                preferences = fakePreferences.preferences,
                hasSmsPermissions = true
            )
            assertEquals(1, restartedStore.state.value.processedCount)
            assertEquals(1, restartedStore.state.value.savedCount)
        }

    @Test
    fun `checkpoint commit failure keeps ledger save distinct and stops durably advancing`() =
        runTest {
            val fakePreferences = FakeSharedPreferences()
            val setupImportStore = SetupImportStore(
                preferences = fakePreferences.preferences,
                hasSmsPermissions = true
            )
            setupImportStore.update {
                it.copy(
                    status = SetupImportStatus.PROCESSING,
                    modelDownloadConfirmed = true,
                    modelPrepared = true
                )
            }
            fakePreferences.commitSucceeds = false
            val fixture = pipelineFixture()
            var counters = HistoricalImportCounters()
            var settlement: HistoricalPersistenceSettlement? = null
            val callbackCount = AtomicInteger(0)

            val result = fixture.pipeline.processSingle(
                sms = transactionSms(),
                lease = fixture.lease,
                onPersistenceCommitted = { committed ->
                    callbackCount.incrementAndGet()
                    settlement = settleHistoricalPersistedResult(
                        setupImportStore = setupImportStore,
                        alreadySavedCount = 0,
                        counters = counters,
                        result = committed
                    )
                    counters = checkNotNull(settlement).counters
                }
            )

            val saved = result as PipelineService.ProcessingResult.Saved
            assertTrue(saved.newlyInserted)
            assertEquals(1, callbackCount.get())
            assertNotNull(checkNotNull(settlement).persistenceError)
            assertFalse(checkNotNull(settlement).isDurable)
            assertEquals(1, counters.processedCount)
            assertEquals(1, counters.parsedCount)
            assertEquals(0, counters.failedCount)
            assertEquals(SetupImportStatus.FAILED, setupImportStore.state.value.status)
            assertEquals(1, setupImportStore.state.value.processedCount)
            assertEquals(1, setupImportStore.state.value.savedCount)
            assertEquals(
                "SETUP_PROGRESS_PERSISTENCE_FAILED",
                setupImportStore.state.value.actionableError?.code
            )
            coVerify(exactly = 1) {
                fixture.transactionRepository.insertIfAbsent(any())
            }

            val context = mockk<Context>(relaxed = true)
            val generationStore = mockk<OnboardingRunGenerationStore>(
                relaxed = true
            )
            every { generationStore.currentGeneration() } returns 0L
            val manager = OnboardingSyncManager(
                runGenerationStore = generationStore,
                appFlowCoordinator = SlmAppFlowCoordinator(),
                setupImportStore = setupImportStore
            )

            manager.startHistoricalImport(
                context = context,
                slm = SlmTier.DEFAULT_ONBOARDING_SLM,
                coveredWindowDays = 90
            )

            assertFalse(manager.syncState.value.isRunning)
            assertNull(manager.syncState.value.runId)
            assertTrue(
                manager.syncState.value.modelLoadError
                    ?.contains("progress storage") == true
            )
            assertEquals(1, setupImportStore.state.value.processedCount)
            assertEquals(1, setupImportStore.state.value.savedCount)
            assertEquals(
                "SETUP_PROGRESS_PERSISTENCE_FAILED",
                setupImportStore.state.value.actionableError?.code
            )
            verify(exactly = 0) { context.startForegroundService(any()) }

            // A new process sees only the last successful checkpoint. It will
            // conservatively rediscover this SMS, and source-identity dedup
            // will recognize the ledger row rather than insert it again.
            fakePreferences.commitSucceeds = true
            val restartedStore = SetupImportStore(
                preferences = fakePreferences.preferences,
                hasSmsPermissions = true
            )
            assertEquals(0, restartedStore.state.value.processedCount)
            assertEquals(0, restartedStore.state.value.savedCount)
        }

    @Test
    fun `synchronous callback cancellation becomes truthful checkpoint failure`() {
        val fakePreferences = FakeSharedPreferences()
        val setupImportStore = SetupImportStore(
            preferences = fakePreferences.preferences,
            hasSmsPermissions = true
        )
        setupImportStore.update {
            it.copy(status = SetupImportStatus.PROCESSING)
        }
        fakePreferences.commitFailure =
            CancellationException("preference callback cancelled")

        val settlement = settleHistoricalPersistedResult(
            setupImportStore = setupImportStore,
            alreadySavedCount = 0,
            counters = HistoricalImportCounters(),
            result = PipelineService.ProcessingResult.Saved(
                transaction = extractedTransaction(),
                newlyInserted = true
            )
        )

        assertFalse(settlement.isDurable)
        assertTrue(settlement.persistenceError is CancellationException)
        assertEquals(1, settlement.counters.processedCount)
        assertEquals(1, settlement.counters.parsedCount)
        assertEquals(SetupImportStatus.FAILED, setupImportStore.state.value.status)
        assertEquals(1, setupImportStore.state.value.processedCount)
        assertEquals(1, setupImportStore.state.value.savedCount)
    }

    @Test
    fun `non-saved outcome checkpoint failure preserves exact counters`() {
        val fakePreferences = FakeSharedPreferences()
        val setupImportStore = SetupImportStore(
            preferences = fakePreferences.preferences,
            hasSmsPermissions = true
        )
        setupImportStore.update {
            it.copy(status = SetupImportStatus.PROCESSING)
        }
        fakePreferences.commitSucceeds = false
        val counters = HistoricalImportCounters(
            processedCount = 2,
            rejectedCount = 1,
            failedCount = 1
        )

        val settlement = checkpointHistoricalImportCounters(
            setupImportStore = setupImportStore,
            alreadySavedCount = 0,
            counters = counters,
            persistenceFailureMessage = "Import checkpoint unavailable"
        )

        assertFalse(settlement.isDurable)
        assertEquals(counters, settlement.counters)
        assertEquals(SetupImportStatus.FAILED, setupImportStore.state.value.status)
        assertEquals(2, setupImportStore.state.value.processedCount)
        assertEquals(0, setupImportStore.state.value.savedCount)
        assertEquals(1, setupImportStore.state.value.rejectedCount)
        assertEquals(1, setupImportStore.state.value.failedCount)
        assertEquals(
            "Import checkpoint unavailable",
            setupImportStore.state.value.actionableError?.message
        )
    }

    @Test
    fun `concurrent retry cannot overtake a failing durable admission`() =
        runTest {
            val fakePreferences = FakeSharedPreferences()
            val setupImportStore = SetupImportStore(
                preferences = fakePreferences.preferences,
                hasSmsPermissions = true
            )
            setupImportStore.update {
                it.copy(
                    status = SetupImportStatus.FAILED,
                    modelDownloadConfirmed = true,
                    modelPrepared = true
                )
            }
            val commitEntered = CountDownLatch(1)
            val releaseCommit = CountDownLatch(1)
            fakePreferences.commitSucceeds = false
            fakePreferences.beforeCommit = {
                commitEntered.countDown()
                check(releaseCommit.await(5, TimeUnit.SECONDS))
            }
            val context = mockk<Context>(relaxed = true)
            val generationStore = mockk<OnboardingRunGenerationStore>(
                relaxed = true
            )
            every { generationStore.currentGeneration() } returns 0L
            val manager = OnboardingSyncManager(
                runGenerationStore = generationStore,
                appFlowCoordinator = SlmAppFlowCoordinator(),
                setupImportStore = setupImportStore
            )

            val firstStart = async(Dispatchers.Default) {
                manager.startHistoricalImport(
                    context = context,
                    slm = SlmTier.DEFAULT_ONBOARDING_SLM,
                    coveredWindowDays = 90
                )
            }
            assertTrue(commitEntered.await(5, TimeUnit.SECONDS))

            // The first run still owns its in-memory reservation while its
            // durable admission is blocked, so this repeated click is inert.
            manager.startHistoricalImport(
                context = context,
                slm = SlmTier.DEFAULT_ONBOARDING_SLM,
                coveredWindowDays = 90
            )
            assertTrue(manager.syncState.value.isRunning)

            releaseCommit.countDown()
            firstStart.await()

            assertFalse(manager.syncState.value.isRunning)
            assertNull(manager.syncState.value.runId)
            assertEquals(SetupImportStatus.FAILED, setupImportStore.state.value.status)
            assertEquals(
                "SETUP_START_PERSISTENCE_FAILED",
                setupImportStore.state.value.actionableError?.code
            )
            verify(exactly = 0) { context.startForegroundService(any()) }
        }

    @Test
    fun `concurrent duplicate is checkpointed once and survives restart`() =
        runTest {
            val fakePreferences = FakeSharedPreferences()
            val setupImportStore = SetupImportStore(
                preferences = fakePreferences.preferences,
                hasSmsPermissions = true
            )
            setupImportStore.update {
                it.copy(
                    status = SetupImportStatus.PROCESSING,
                    processedCount = 2,
                    savedCount = 2
                )
            }
            val fixture = pipelineFixture(inserted = false)
            var counters = HistoricalImportCounters(processedCount = 2)
            var settlement: HistoricalPersistenceSettlement? = null

            val result = fixture.pipeline.processSingle(
                sms = transactionSms(),
                lease = fixture.lease,
                onPersistenceCommitted = { committed ->
                    settlement = settleHistoricalPersistedResult(
                        setupImportStore = setupImportStore,
                        alreadySavedCount = 2,
                        counters = counters,
                        result = committed
                    )
                    counters = checkNotNull(settlement).counters
                }
            )

            val saved = result as PipelineService.ProcessingResult.Saved
            assertFalse(saved.newlyInserted)
            assertTrue(checkNotNull(settlement).isDurable)
            assertNull(checkNotNull(settlement).persistenceError)
            assertEquals(3, counters.processedCount)
            assertEquals(0, counters.parsedCount)
            assertEquals(1, counters.concurrentDuplicateCount)
            assertEquals(3, setupImportStore.state.value.savedCount)
            coVerify(exactly = 1) {
                fixture.transactionRepository.insertIfAbsent(any())
            }

            val restartedStore = SetupImportStore(
                preferences = fakePreferences.preferences,
                hasSmsPermissions = true
            )
            assertEquals(3, restartedStore.state.value.processedCount)
            assertEquals(3, restartedStore.state.value.savedCount)
        }

    private data class PipelineFixture(
        val pipeline: PipelineService,
        val lease: SlmLease,
        val transactionRepository: TransactionRepository
    )

    private fun pipelineFixture(inserted: Boolean = true): PipelineFixture {
        val promptBuilder = mockk<PromptBuilder>()
        val extractionParser = mockk<ExtractionParser>()
        val transactionRepository = mockk<TransactionRepository>()
        val accountRepository = mockk<AccountRepository>()
        val preferences = mockk<SlmProcessingPreferences>()
        val modelStorage = mockk<SlmModelStorage>()
        val lease = mockk<SlmLease>()
        val model = SlmModelSpec(
            modelId = "test-model",
            modelPath = "build/test-model.gguf",
            hasThinkingMode = true
        )

        every { preferences.gbnfGrammarEnabled } returns MutableStateFlow(false)
        every { lease.model } returns model
        every { promptBuilder.buildExtractionPrompt(any(), any()) } returns
            "raw prompt"
        every { promptBuilder.buildChatPrompt(any(), any()) } returns
            "chat prompt"
        every { promptBuilder.getStaticPrefix() } returns "static prefix"
        coEvery { lease.extract(any()) } returns SlmExtractionResult.Success(
            json = """{"amount":500.0,"type":"credit"}""",
            model = model
        )
        every { extractionParser.parse(any()) } returns extractedTransaction()
        coEvery {
            accountRepository.getOrCreate(any(), any(), any())
        } returns Account(
            id = UUID.randomUUID().toString(),
            name = "A/c XX0000",
            bank = "HDFC Bank",
            type = "auto-extracted"
        )
        val insertResult = mockk<TransactionRepository.InsertResult>()
        every { insertResult.inserted } returns inserted
        coEvery { transactionRepository.insertIfAbsent(any()) } returns
            insertResult

        return PipelineFixture(
            pipeline = PipelineService(
                promptBuilder = promptBuilder,
                extractionParser = extractionParser,
                transactionRepository = transactionRepository,
                accountRepository = accountRepository,
                smsFilterPipeline = SmsFilterPipeline(),
                slmProcessingPreferences = preferences,
                modelStorage = modelStorage
            ),
            lease = lease,
            transactionRepository = transactionRepository
        )
    }

    private fun transactionSms() = SmsReader.SmsMessage(
        address = "AX-HDFCBK",
        body = "Rs.500 credited to a/c XX0000",
        date = 1_000L,
        type = 1
    )

    private fun extractedTransaction() =
        ExtractionParser.ExtractedTransaction(
            amount = 500.0,
            counterparty = "ACME",
            type = TransactionType.CREDIT,
            account = "A/c XX0000"
        )
}
