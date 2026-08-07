package com.pocketfinancer.ui.home

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.data.model.Account
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.inference.SlmExtractionRequest
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.inference.SlmRuntimeState
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.setup.FakeSharedPreferences
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.sms.SmsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ManualSyncCancellationTest {

    @Test
    fun `stop matcher rejects stale and missing run identities`() {
        assertTrue(manualSyncStopMatches("current-run", "current-run"))
        assertFalse(manualSyncStopMatches("current-run", "stale-run"))
        assertFalse(manualSyncStopMatches("current-run", null))
        assertFalse(manualSyncStopMatches(null, "current-run"))
        assertFalse(manualSyncStopMatches("", ""))
    }

    @Test
    fun `service start acknowledgement keeps run identity and outcome`() {
        val fixture = cancellationFixture()
        try {
            fixture.manager.acknowledgeServiceStart(
                runId = "rejected-run",
                accepted = false
            )
            assertEquals(
                ManualServiceStartAcknowledgement(
                    runId = "rejected-run",
                    accepted = false
                ),
                fixture.manager.serviceStartAcknowledgement.value
            )

            fixture.manager.acknowledgeServiceStart(
                runId = "accepted-run",
                accepted = true
            )
            assertEquals(
                ManualServiceStartAcknowledgement(
                    runId = "accepted-run",
                    accepted = true
                ),
                fixture.manager.serviceStartAcknowledgement.value
            )
        } finally {
            fixture.modelDirectory.deleteRecursively()
        }
    }

    @Test
    fun `timed out service run cannot acquire ownership after reservation retires`() {
        val fixture = cancellationFixture()
        try {
            val lateRun = fixture.manager.tryReserveServiceStart()!!
            assertEquals(
                lateRun,
                fixture.manager.manualOperationReservation.value?.id
            )
            assertTrue(
                fixture.manager.revokeUnacknowledgedServiceStart(lateRun)
            )
            assertEquals(
                null,
                fixture.manager.manualOperationReservation.value
            )
            assertFalse(fixture.manager.beginServiceRun(lateRun))

            val acceptedRun = fixture.manager.tryReserveServiceStart()!!
            assertTrue(fixture.manager.beginServiceRun(acceptedRun))
            assertFalse(
                fixture.manager.revokeUnacknowledgedServiceStart(
                    acceptedRun
                )
            )
            assertEquals(
                null,
                fixture.manager.manualOperationReservation.value
            )
            assertTrue(fixture.manager.finishServiceRun(acceptedRun))
        } finally {
            fixture.modelDirectory.deleteRecursively()
        }
    }

    @Test
    fun `acknowledged service start ignores its later timeout`() {
        val fixture = cancellationFixture()
        try {
            val runId = fixture.manager.tryReserveServiceStart()!!
            assertTrue(fixture.manager.beginServiceRun(runId))
            fixture.manager.acknowledgeServiceStart(
                runId = runId,
                accepted = true
            )
            assertTrue(fixture.manager.finishServiceRun(runId))

            assertFalse(
                fixture.manager.revokeUnacknowledgedServiceStart(runId)
            )
            assertTrue(fixture.manager.beginServiceRun(runId))
            assertTrue(fixture.manager.finishServiceRun(runId))
        } finally {
            fixture.modelDirectory.deleteRecursively()
        }
    }

    @Test
    fun `recent scan reservation is process wide and token scoped`() {
        val fixture = cancellationFixture()
        try {
            val reservation = fixture.manager.tryReserveRecentScan()!!

            assertEquals(
                ManualOperationReservationKind.RECENT_SCAN,
                fixture.manager.manualOperationReservation.value?.kind
            )
            assertEquals(null, fixture.manager.tryReserveServiceStart())
            fixture.manager.releaseManualOperationReservation("stale-token")
            assertEquals(
                reservation,
                fixture.manager.manualOperationReservation.value?.id
            )

            fixture.manager.releaseManualOperationReservation(reservation)
            assertEquals(
                null,
                fixture.manager.manualOperationReservation.value
            )
        } finally {
            fixture.modelDirectory.deleteRecursively()
        }
    }

    @Test
    fun `settlement preserves completed items and clears private transients`() {
        val completed = SyncSmsItem(
            id = "saved",
            sender = "AX-BANK",
            body = "Rs 500 debited from a/c XX0000",
            date = 1L,
            status = "syncing"
        ).withPrivacySafeStatus("synced")
        val current = SyncSmsItem(
            id = "pending",
            sender = "AX-BANK",
            body = "Rs 700 debited from a/c XX0000",
            date = 2L,
            status = "syncing"
        )
        val active = HomeSyncState(
            status = HomeSyncState.Status.CANCELLING,
            activeRunId = "current-run",
            cancellationRequested = true,
            queue = listOf(completed, current),
            currentIndex = 1,
            currentStageIndex = 2,
            thinkingOutput = "echoed private SMS",
            jsonOutput = "echoed private SMS",
            activeSmsPerformance = "10 tok/s"
        )

        assertNull(settledManualSyncCancellation(active, "stale-run"))
        val settled = settledManualSyncCancellation(active, "current-run")!!

        assertEquals(HomeSyncState.Status.IDLE, settled.status)
        assertEquals(completed, settled.queue[0])
        assertEquals("pending", settled.queue[1].status)
        assertEquals("AX-BANK", settled.queue[1].sender)
        assertEquals("", settled.thinkingOutput)
        assertEquals("", settled.jsonOutput)
        assertNull(settled.activeSmsPerformance)
        assertNull(settled.activeRunId)
        assertFalse(settled.cancellationRequested)
    }

    @Test
    fun `owned service job remains in drain branch through completion`() =
        runBlocking {
        val cleanupStarted = CompletableDeferred<Unit>()
        val releaseCleanup = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    releaseCleanup.await()
                }
            }
        }

        job.cancel()
        withTimeout(5_000) { cleanupStarted.await() }
        assertFalse(job.isActive)
        assertFalse(job.isCompleted)
        assertTrue(
            shouldKeepManualSyncDraining(
                jobIsPresent = true
            )
        )

        releaseCleanup.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertTrue(job.isCompleted)
        // The completion callback, not Job.isCompleted, retires ownership.
        assertTrue(
            shouldKeepManualSyncDraining(
                jobIsPresent = true
            )
        )
        assertFalse(
            shouldKeepManualSyncDraining(
                jobIsPresent = false
            )
        )
        }

    @Test
    fun `stop before persistence leaves current SMS pending`() = runBlocking {
        val extractionStarted = CompletableDeferred<Unit>()
        val releaseExtraction = CompletableDeferred<Unit>()
        val fixture = cancellationFixture(
            extractionStarted = extractionStarted,
            releaseExtraction = releaseExtraction,
            historyMessages = listOf(
                historyMessage("provider-1", 1_000L, "Rs.500 debited")
            )
        )
        try {
            fixture.manager.checkForUnsyncedSms()
            val runId = "pre-persistence-run"
            assertTrue(fixture.manager.beginServiceRun(runId))
            val flow = fixture.coordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC)!!
            val execution = async(Dispatchers.Default) {
                fixture.manager.executeSync(mockk(), flow, runId)
            }

            withTimeout(5_000) { extractionStarted.await() }
            val renderedCandidateKey =
                fixture.manager.syncState.value.queue.single().id
            assertFalse(
                fixture.manager.requestServiceStop(
                    runId = runId,
                    expectedCandidateKey = "stale-candidate"
                )
            )
            assertTrue(
                fixture.manager.requestServiceStop(
                    runId = runId,
                    expectedCandidateKey = renderedCandidateKey
                )
            )
            assertEquals(
                HomeSyncState.Status.CANCELLING,
                fixture.manager.syncState.value.status
            )
            assertEquals("", fixture.manager.syncState.value.thinkingOutput)
            assertEquals("", fixture.manager.syncState.value.jsonOutput)
            releaseExtraction.complete(Unit)
            expectCancellation { execution.await() }
            flow.release()

            coVerify(exactly = 0) {
                fixture.transactionRepository.insertIfAbsent(any())
            }
            assertTrue(fixture.manager.settleServiceCancellation(runId))
            assertEquals("pending", fixture.manager.syncState.value.queue.single().status)
            assertEquals(
                "MANUAL_PROCESSING_INTERRUPTED",
                fixture.setupStore.state.value.actionableError?.code
            )
        } finally {
            fixture.modelDirectory.deleteRecursively()
        }
    }

    @Test
    fun `stop during persistence finishes current save then settles`() = runBlocking {
        val persistenceStarted = CompletableDeferred<Unit>()
        val releasePersistence = CompletableDeferred<Unit>()
        val history = listOf(
            historyMessage("provider-1", 1_000L, "Rs.500 debited"),
            historyMessage("provider-2", 2_000L, "Rs.700 debited")
        )
        val fixture = cancellationFixture(
            persistenceStarted = persistenceStarted,
            releasePersistence = releasePersistence,
            historyMessages = history
        )
        try {
            fixture.manager.checkForUnsyncedSms()
            assertEquals(2, fixture.setupStore.state.value.recentEligibleCandidateCount)
            val runId = "in-persistence-run"
            assertTrue(fixture.manager.beginServiceRun(runId))
            val flow = fixture.coordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC)!!
            val execution = async(Dispatchers.Default) {
                fixture.manager.executeSync(mockk(), flow, runId)
            }

            withTimeout(5_000) { persistenceStarted.await() }
            assertEquals(3, fixture.manager.syncState.value.currentStageIndex)
            assertTrue(fixture.manager.requestServiceStop(runId))
            execution.cancel(CancellationException("service stop"))
            releasePersistence.complete(Unit)
            expectCancellation { execution.await() }
            flow.release()

            coVerify(exactly = 1) {
                fixture.transactionRepository.insertIfAbsent(any())
            }
            assertEquals(
                listOf("synced", "pending"),
                fixture.manager.syncState.value.queue.map { it.status }
            )
            assertTrue(fixture.manager.settleServiceCancellation(runId))
            assertEquals(HomeSyncState.Status.IDLE, fixture.manager.syncState.value.status)
            assertEquals(
                listOf("synced", "pending"),
                fixture.manager.syncState.value.queue.map { it.status }
            )
            assertEquals(1, fixture.setupStore.state.value.recentProcessedCount)
            assertEquals(1, fixture.setupStore.state.value.recentSavedCount)
            assertEquals(0, fixture.setupStore.state.value.recentFailedCount)
            assertEquals(
                "MANUAL_PROCESSING_INTERRUPTED",
                fixture.setupStore.state.value.actionableError?.code
            )
        } finally {
            fixture.modelDirectory.deleteRecursively()
        }
    }

    @Test
    fun `failed progress bookkeeping never reverses a successful save`() =
        runBlocking {
            val fixture = cancellationFixture(
                historyMessages = listOf(
                    historyMessage(
                        "provider-1",
                        1_000L,
                        "Rs.500 debited"
                    )
                )
            )
            try {
                fixture.manager.checkForUnsyncedSms()
                fixture.setupPreferences.commitSucceeds = false

                fixture.manager.executeSync(mockk())

                assertEquals(
                    HomeSyncState.Status.DONE,
                    fixture.manager.syncState.value.status
                )
                assertEquals(
                    "synced",
                    fixture.manager.syncState.value.queue.single().status
                )
                assertEquals(
                    0,
                    fixture.setupStore.state.value.recentFailedCount
                )
            } finally {
                fixture.modelDirectory.deleteRecursively()
            }
        }

    @Test
    fun `failed item still reaches done when durable error commit fails`() =
        runBlocking {
            val fixture = cancellationFixture(
                historyMessages = listOf(
                    historyMessage(
                        "provider-1",
                        1_000L,
                        "Rs.500 debited"
                    )
                )
            )
            mockkStatic(Log::class)
            every {
                Log.e(any<String>(), any<String>(), any<Throwable>())
            } returns 0
            try {
                fixture.manager.checkForUnsyncedSms()
                fixture.setupPreferences.commitSucceeds = false
                coEvery {
                    fixture.transactionRepository.insertIfAbsent(any())
                } throws IllegalStateException("database unavailable")

                fixture.manager.executeSync(mockk())

                assertEquals(
                    HomeSyncState.Status.DONE,
                    fixture.manager.syncState.value.status
                )
                assertEquals(
                    "error",
                    fixture.manager.syncState.value.queue.single().status
                )
                assertEquals(
                    0,
                    fixture.setupStore.state.value.recentFailedCount
                )
            } finally {
                unmockkStatic(Log::class)
                fixture.modelDirectory.deleteRecursively()
            }
        }

    @Test
    fun `permission loss between items preserves first save and skips next`() =
        runBlocking {
            val fixture = cancellationFixture(
                historyMessages = listOf(
                    historyMessage("provider-1", 1_000L, "Rs.500 debited"),
                    historyMessage("provider-2", 2_000L, "Rs.700 debited")
                )
            )
            try {
                fixture.manager.checkForUnsyncedSms()
                coEvery {
                    fixture.transactionRepository.insertIfAbsent(any())
                } coAnswers {
                    val input =
                        firstArg<TransactionRepository.NewTransaction>()
                    fixture.smsPermissionGranted.value = false
                    TransactionRepository.InsertResult(
                        transaction = Transaction(
                            id = "transaction",
                            amount = input.amount,
                            merchant = input.merchant,
                            date = input.date,
                            type = input.type,
                            accountId = input.accountId,
                            accountLabel = "Primary",
                            rawMessage = input.rawMessage,
                            sender = input.sender,
                            sourceIdentity = input.sourceIdentity
                        ),
                        inserted = true
                    )
                }

                expectCancellation {
                    fixture.manager.executeSync(mockk())
                }

                coVerify(exactly = 1) {
                    fixture.transactionRepository.insertIfAbsent(any())
                }
                assertEquals(
                    listOf("synced", "pending"),
                    fixture.manager.syncState.value.queue.map { it.status }
                )
                assertEquals(
                    SetupImportStatus.PERMISSION_NEEDED,
                    fixture.setupStore.state.value.status
                )
                assertEquals(1, fixture.setupStore.state.value.recentSavedCount)
                assertEquals(0, fixture.setupStore.state.value.recentFailedCount)
            } finally {
                fixture.modelDirectory.deleteRecursively()
            }
        }

    @Test
    fun `done dismissal waits for service ownership to clear`() = runBlocking {
        val fixture = cancellationFixture(
            historyMessages = listOf(
                historyMessage("provider-1", 1_000L, "Rs.500 debited")
            )
        )
        try {
            fixture.manager.checkForUnsyncedSms()
            val runId = "terminal-owner-run"
            assertTrue(fixture.manager.beginServiceRun(runId))
            val flow = fixture.coordinator.tryEnter(
                SlmRuntimeOwner.HOME_SYNC
            )!!

            fixture.manager.executeSync(mockk(), flow, runId)
            assertEquals(
                HomeSyncState.Status.DONE,
                fixture.manager.syncState.value.status
            )
            assertEquals(runId, fixture.manager.syncState.value.activeRunId)
            assertFalse(fixture.manager.requestServiceStop(runId))

            fixture.manager.resetState()
            assertEquals(1, fixture.manager.syncState.value.queue.size)

            assertTrue(fixture.manager.finishServiceRun(runId))
            fixture.manager.resetState()
            assertEquals(HomeSyncState(), fixture.manager.syncState.value)
            flow.release()
        } finally {
            fixture.modelDirectory.deleteRecursively()
        }
    }

    @Test
    fun `empty scan completion linearizes against stop`() = runBlocking {
        val fixture = cancellationFixture()
        try {
            val runId = "empty-scan-run"
            assertTrue(fixture.manager.beginServiceRun(runId))
            val flow = fixture.coordinator.tryEnter(
                SlmRuntimeOwner.HOME_SYNC
            )!!
            fixture.manager.checkForUnsyncedSms(flow, runId)

            assertTrue(fixture.manager.tryCompleteNoWorkServiceRun(runId))
            assertEquals(
                HomeSyncState.Status.DONE,
                fixture.manager.syncState.value.status
            )
            assertFalse(fixture.manager.requestServiceStop(runId))
            assertTrue(
                fixture.manager.withCompletedServiceRunHandoff(runId) {
                    assertTrue(fixture.manager.finishServiceRun(runId))
                }
            )
            flow.release()
        } finally {
            fixture.modelDirectory.deleteRecursively()
        }
    }

    @Test
    fun `candidate scoped stop distinguishes a rendered scan gap`() = runBlocking {
        val fixture = cancellationFixture()
        try {
            val runId = "scan-gap-run"
            assertTrue(fixture.manager.beginServiceRun(runId))

            assertFalse(
                fixture.manager.requestServiceStop(
                    runId = runId,
                    expectedCandidateKey = "candidate-not-yet-visible"
                )
            )
            assertTrue(
                fixture.manager.requestServiceStop(
                    runId = runId,
                    expectedCandidateKey = null
                )
            )
        } finally {
            fixture.modelDirectory.deleteRecursively()
        }
    }

    @Test
    fun `stop winning empty scan prevents completion publication`() =
        runBlocking {
            val fixture = cancellationFixture()
            try {
                val runId = "stopped-empty-scan-run"
                assertTrue(fixture.manager.beginServiceRun(runId))
                val flow = fixture.coordinator.tryEnter(
                    SlmRuntimeOwner.HOME_SYNC
                )!!
                fixture.manager.checkForUnsyncedSms(flow, runId)

                assertTrue(fixture.manager.requestServiceStop(runId))
                assertFalse(
                    fixture.manager.tryCompleteNoWorkServiceRun(runId)
                )
                assertEquals(
                    HomeSyncState.Status.CANCELLING,
                    fixture.manager.syncState.value.status
                )
                assertTrue(fixture.manager.settleServiceCancellation(runId))
                flow.release()
            } finally {
                fixture.modelDirectory.deleteRecursively()
            }
        }

    private suspend fun expectCancellation(block: suspend () -> Unit) {
        try {
            withTimeout(5_000) { block() }
            fail("Expected cancellation")
        } catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Timed out waiting for cancellation", timeout)
        } catch (_: CancellationException) {
            // Expected terminal state for the owning service job.
        }
    }

    private fun cancellationFixture(
        extractionStarted: CompletableDeferred<Unit>? = null,
        releaseExtraction: CompletableDeferred<Unit>? = null,
        persistenceStarted: CompletableDeferred<Unit>? = null,
        releasePersistence: CompletableDeferred<Unit>? = null,
        historyMessages: List<SmsReader.SmsMessage> = emptyList()
    ): CancellationFixture {
        val modelDirectory = createTempDirectory("manual-cancel-model").toFile()
        val modelFile = File(modelDirectory, SlmTier.QWEN3_0_6B_Q8_0.modelFile)
        modelFile.writeBytes(byteArrayOf(1))
        val context = mockk<Context>()
        val sharedPreferences = mockk<SharedPreferences>()
        val smsRepository = mockk<SmsRepository>()
        val smsPermissionGranted = MutableStateFlow(true)
        val transactionRepository = mockk<TransactionRepository>()
        val accountRepository = mockk<AccountRepository>()
        val runtime = mockk<SlmRuntime>()
        val lease = mockk<SlmLease>()
        val storage = mockk<SlmModelStorage>()
        val deviceCapabilities = mockk<DeviceCapabilities>()
        val promptBuilder = mockk<PromptBuilder>()
        val extractionParser = mockk<ExtractionParser>()
        val preferences = mockk<SlmProcessingPreferences>()
        val coordinator = SlmAppFlowCoordinator()
        val setupPreferences = FakeSharedPreferences()
        val setupStore = readySetupStore(setupPreferences)
        val spec = SlmModelSpec(
            modelId = SlmTier.QWEN3_0_6B_Q8_0.id,
            modelPath = modelFile.absolutePath,
            artifactRevision = "test",
            hasThinkingMode = false
        )

        every { context.getSharedPreferences(any(), Context.MODE_PRIVATE) } returns
            sharedPreferences
        every { sharedPreferences.getBoolean("onboarding_completed", false) } returns true
        every { sharedPreferences.getString("selected_slm_id", null) } returns null
        every { smsRepository.hasPermissions() } answers {
            smsPermissionGranted.value
        }
        every {
            smsRepository.fetchHistory(
                daysBack = any(),
                limit = any(),
                maxDate = any()
            )
        } returns historyMessages
        every { preferences.gbnfGrammarEnabled } returns MutableStateFlow(false)
        coEvery { transactionRepository.exists(any<SmsSourceIdentity>()) } returns false
        coEvery {
            transactionRepository.preserveSourceMetadataIfExists(any(), any())
        } returns false
        every { deviceCapabilities.assessDevice() } returns testDeviceInfo()
        every { storage.modelDirectory } returns modelDirectory
        every { storage.modelFile(any()) } answers {
            File(modelDirectory, firstArg<String>())
        }
        every { runtime.state } returns MutableStateFlow(SlmRuntimeState())
        every { lease.model } returns spec
        every { lease.isReleased } returns false
        every { lease.owner } returns SlmRuntimeOwner.HOME_SYNC
        coEvery { runtime.acquire(SlmRuntimeOwner.HOME_SYNC, any()) } returns lease
        coEvery { lease.release() } returns Unit
        every { promptBuilder.buildExtractionPrompt(any(), any()) } returns "raw"
        every { promptBuilder.getStaticPrefix() } returns "prefix"
        every { promptBuilder.buildChatPrompt(any(), any()) } returns "chat"
        every { extractionParser.parse(any()) } returns
            ExtractionParser.ExtractedTransaction(
                amount = 500.0,
                counterparty = "Merchant",
                type = TransactionType.DEBIT,
                account = null
            )
        coEvery { accountRepository.ensureDefault() } coAnswers {
            persistenceStarted?.complete(Unit)
            releasePersistence?.await()
            Account("account", "Primary", "Bank", "auto-extracted")
        }
        coEvery { transactionRepository.insertIfAbsent(any()) } coAnswers {
            val input = firstArg<TransactionRepository.NewTransaction>()
            TransactionRepository.InsertResult(
                transaction = Transaction(
                    id = "transaction",
                    amount = input.amount,
                    merchant = input.merchant,
                    date = input.date,
                    type = input.type,
                    accountId = input.accountId,
                    accountLabel = "Primary",
                    rawMessage = input.rawMessage,
                    sender = input.sender,
                    sourceIdentity = input.sourceIdentity
                ),
                inserted = true
            )
        }
        coEvery { lease.extract(any()) } coAnswers {
            val request = firstArg<SlmExtractionRequest>()
            request.thinkingCallback?.onToken("private thinking")
            request.jsonCallback?.onToken("private json")
            extractionStarted?.complete(Unit)
            releaseExtraction?.await()
            SlmExtractionResult.Success(
                json = """{"amount":500,"counterparty":"Merchant","type":"debit"}""",
                model = spec
            )
        }

        val manager = HomeSyncManager(
            context = context,
            smsRepository = smsRepository,
            smsFilterPipeline = SmsFilterPipeline(),
            transactionRepository = transactionRepository,
            accountRepository = accountRepository,
            slmRuntime = runtime,
            appFlowCoordinator = coordinator,
            modelStorage = storage,
            deviceCapabilities = deviceCapabilities,
            promptBuilder = promptBuilder,
            extractionParser = extractionParser,
            slmProcessingPreferences = preferences,
            setupImportStore = setupStore
        )
        return CancellationFixture(
            manager,
            coordinator,
            transactionRepository,
            setupStore,
            setupPreferences,
            smsPermissionGranted,
            modelDirectory
        )
    }

    private fun readySetupStore(
        preferences: FakeSharedPreferences = FakeSharedPreferences()
    ): SetupImportStore {
        val store = SetupImportStore(
            preferences.preferences,
            hasSmsPermissions = true
        )
        store.completeRequiredPermissions()
        store.update {
            SetupImportState(
                status = SetupImportStatus.READY,
                modelPrepared = true
            )
        }
        return store
    }

    private fun historyMessage(
        providerId: String,
        date: Long,
        amountText: String
    ) = SmsReader.SmsMessage(
        address = "AX-HDFCBK",
        body = "$amountText from a/c XX0000 at Merchant",
        date = date,
        type = 1,
        providerMessageId = providerId
    )

    private fun testDeviceInfo() = DeviceCapabilities.DeviceInfo(
        ramGb = 4f,
        ramTier = DeviceCapabilities.RamTier.OK,
        gpu = null,
        cpu = DeviceCapabilities.CpuInfo(
            cores = 4,
            features = emptySet(),
            hasI8mm = false,
            hasDotProd = false,
            hasFp16 = false,
            socModel = null
        ),
        storage = DeviceCapabilities.StorageInfo(
            totalBytes = 10_000_000_000L,
            availableBytes = 8_000_000_000L,
            usedBytes = 2_000_000_000L
        ),
        isHighPerformanceDevice = false
    )

    private data class CancellationFixture(
        val manager: HomeSyncManager,
        val coordinator: SlmAppFlowCoordinator,
        val transactionRepository: TransactionRepository,
        val setupStore: SetupImportStore,
        val setupPreferences: FakeSharedPreferences,
        val smsPermissionGranted: MutableStateFlow<Boolean>,
        val modelDirectory: File
    )
}
