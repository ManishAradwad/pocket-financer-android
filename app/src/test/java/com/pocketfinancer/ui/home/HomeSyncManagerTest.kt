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
import com.pocketfinancer.pipeline.IncomingSmsQueueResult
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.sms.SmsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSyncManagerTest {

    @Test
    fun `flow contention is not reported as an empty successful scan`() =
        runBlocking {
            val coordinator = SlmAppFlowCoordinator()
            val pause = checkNotNull(
                coordinator.tryPauseAndDrain(SlmRuntimeOwner.SETTINGS_MANUAL)
            )
            val setupImportStore = mockk<SetupImportStore>(relaxed = true)
            every { setupImportStore.state } returns MutableStateFlow(
                SetupImportState(
                    status = SetupImportStatus.READY,
                    modelPrepared = true
                )
            )
            val manager = HomeSyncManager(
                context = mockk(relaxed = true),
                smsRepository = mockk(relaxed = true),
                smsFilterPipeline = SmsFilterPipeline(),
                transactionRepository = mockk(relaxed = true),
                accountRepository = mockk(relaxed = true),
                slmRuntime = mockk(relaxed = true),
                appFlowCoordinator = coordinator,
                modelStorage = mockk(relaxed = true),
                deviceCapabilities = mockk(relaxed = true),
                promptBuilder = mockk(relaxed = true),
                extractionParser = mockk(relaxed = true),
                slmProcessingPreferences = mockk(relaxed = true),
                setupImportStore = setupImportStore
            )

            manager.checkForUnsyncedSms()

            assertEquals(
                HomeSyncState.RecentScanOutcome.NOT_RUN,
                manager.syncState.value.recentScanOutcome
            )
            assertEquals(
                "The recent scan is waiting for another local setup or maintenance operation.",
                manager.syncState.value.scanError
            )
            pause.release()
        }

    @Test
    fun `saved and concurrent-existing terminal rows discard Home source evidence`() =
        runBlocking {
            val modelDirectory = createTempDir(prefix = "home-sync-privacy")
            val modelFile = File(
                modelDirectory,
                SlmTier.QWEN3_0_6B_Q8_0.modelFile
            )
            modelFile.writeBytes(byteArrayOf(1))
            try {
                val context = mockk<Context>()
                val sharedPreferences = mockk<SharedPreferences>()
                val transactionRepository = mockk<TransactionRepository>()
                val accountRepository = mockk<AccountRepository>()
                val runtime = mockk<SlmRuntime>()
                val lease = mockk<SlmLease>()
                val storage = mockk<SlmModelStorage>()
                val deviceCapabilities = mockk<DeviceCapabilities>()
                val promptBuilder = mockk<PromptBuilder>()
                val extractionParser = mockk<ExtractionParser>()
                val preferences = mockk<SlmProcessingPreferences>()
                val spec = SlmModelSpec(
                    modelId = SlmTier.QWEN3_0_6B_Q8_0.id,
                    modelPath = modelFile.absolutePath,
                    artifactRevision = "test",
                    hasThinkingMode = false
                )

                every {
                    context.getSharedPreferences(
                        ".app_settings",
                        Context.MODE_PRIVATE
                    )
                } returns sharedPreferences
                every {
                    sharedPreferences.getString("selected_slm_id", null)
                } returns null
                every {
                    sharedPreferences.getBoolean(
                        "onboarding_completed",
                        false
                    )
                } returns true
                every {
                    preferences.gbnfGrammarEnabled
                } returns MutableStateFlow(false)
                coEvery {
                    transactionRepository.exists(any<SmsSourceIdentity>())
                } returns false
                every {
                    deviceCapabilities.assessDevice()
                } returns testDeviceInfo()
                every { storage.modelDirectory } returns modelDirectory
                every { storage.modelFile(any()) } answers {
                    File(modelDirectory, firstArg<String>())
                }
                every { runtime.state } returns MutableStateFlow(SlmRuntimeState())
                every { lease.model } returns spec
                every { lease.isReleased } returns false
                every { lease.owner } returns SlmRuntimeOwner.HOME_SYNC
                coEvery {
                    runtime.acquire(SlmRuntimeOwner.HOME_SYNC, any())
                } returns lease
                coEvery { lease.release() } returns Unit
                every {
                    promptBuilder.buildExtractionPrompt(any(), any())
                } returns "raw prompt"
                every { promptBuilder.getStaticPrefix() } returns "static prefix"
                every {
                    promptBuilder.buildChatPrompt(any(), any())
                } returns "chat prompt"
                every { extractionParser.parse(any()) } returns
                    ExtractionParser.ExtractedTransaction(
                        amount = 500.0,
                        counterparty = "Example Merchant",
                        type = TransactionType.DEBIT,
                        account = null
                    )
                coEvery { accountRepository.ensureDefault() } returns Account(
                    id = "account",
                    name = "Primary",
                    bank = "Example Bank",
                    type = "auto-extracted"
                )

                var insertionCount = 0
                val repositoryInsertSources =
                    mutableListOf<Pair<String, String>>()
                coEvery {
                    transactionRepository.insertIfAbsent(any())
                } coAnswers {
                    val input =
                        firstArg<TransactionRepository.NewTransaction>()
                    insertionCount += 1
                    repositoryInsertSources += input.sender to input.rawMessage
                    TransactionRepository.InsertResult(
                        transaction = Transaction(
                            id = "transaction-$insertionCount",
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
                        inserted = insertionCount == 1
                    )
                }
                coEvery { lease.extract(any()) } coAnswers {
                    firstArg<SlmExtractionRequest>()
                        .thinkingCallback
                        ?.onToken("echoed source evidence")
                    firstArg<SlmExtractionRequest>()
                        .jsonCallback
                        ?.onToken("echoed source evidence")
                    SlmExtractionResult.Success(
                        json =
                            """{"amount":500,"counterparty":"Example Merchant","type":"debit","account":null}""",
                        model = spec
                    )
                }

                val manager = HomeSyncManager(
                    context = context,
                    smsRepository = mockk<SmsRepository>(),
                    smsFilterPipeline = SmsFilterPipeline(),
                    transactionRepository = transactionRepository,
                    accountRepository = accountRepository,
                    slmRuntime = runtime,
                    appFlowCoordinator = SlmAppFlowCoordinator(),
                    modelStorage = storage,
                    deviceCapabilities = deviceCapabilities,
                    promptBuilder = promptBuilder,
                    extractionParser = extractionParser,
                    slmProcessingPreferences = preferences,
                    setupImportStore = readySetupStore()
                )

                assertEquals(
                    IncomingSmsQueueResult.QUEUED_TRANSACTION,
                    manager.queueIncomingSms(
                    address = "AX-HDFCBK",
                    body =
                        "Rs.500 debited from a/c XX0000 at Example Merchant",
                    date = 1000L
                    )
                )
                assertEquals(
                    IncomingSmsQueueResult.QUEUED_TRANSACTION,
                    manager.queueIncomingSms(
                    address = "AX-HDFCBK",
                    body =
                        "Rs.700 debited from a/c XX0000 at Other Merchant",
                    date = 2000L
                    )
                )
                manager.executeSync(mockk())

                val queue = manager.syncState.value.queue
                assertEquals("synced", queue[0].status)
                assertEquals("Saved transaction", queue[0].sender)
                assertEquals("", queue[0].body)
                assertEquals(500.0, queue[0].parsedAmount)
                assertEquals("Example Merchant", queue[0].parsedMerchant)

                assertEquals("already_saved", queue[1].status)
                assertEquals("Already in ledger", queue[1].sender)
                assertEquals("", queue[1].body)
                assertEquals(null, queue[1].parsedAmount)
                assertEquals(null, queue[1].parsedMerchant)

                assertEquals(
                    listOf(
                        "AX-HDFCBK" to
                            "Rs.500 debited from a/c XX0000 at Example Merchant",
                        "AX-HDFCBK" to
                            "Rs.700 debited from a/c XX0000 at Other Merchant"
                    ),
                    repositoryInsertSources
                )
                assertEquals("", manager.syncState.value.thinkingOutput)
                assertEquals("", manager.syncState.value.jsonOutput)
            } finally {
                modelDirectory.deleteRecursively()
            }
        }

    @Test
    fun `grammar changes apply between SMS items in a foreground batch`() = runBlocking {
        val modelDirectory = createTempDir(prefix = "home-sync-models")
        val modelFile = File(modelDirectory, SlmTier.QWEN3_0_6B_Q8_0.modelFile)
        modelFile.writeBytes(byteArrayOf(1))
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        try {
            val context = mockk<Context>()
            val sharedPreferences = mockk<SharedPreferences>()
            val smsRepository = mockk<SmsRepository>()
            val transactionRepository = mockk<TransactionRepository>()
            val accountRepository = mockk<AccountRepository>()
            val runtime = mockk<SlmRuntime>()
            val lease = mockk<SlmLease>()
            val storage = mockk<SlmModelStorage>()
            val deviceCapabilities = mockk<DeviceCapabilities>()
            val promptBuilder = mockk<PromptBuilder>()
            val extractionParser = mockk<ExtractionParser>()
            val preferences = mockk<SlmProcessingPreferences>()
            val appFlowCoordinator = SlmAppFlowCoordinator()
            val gbnfEnabled = MutableStateFlow(true)
            val capturedGrammar = mutableListOf<String?>()
            val device = testDeviceInfo()
            val spec = SlmModelSpec(
                modelId = SlmTier.QWEN3_0_6B_Q8_0.id,
                modelPath = modelFile.absolutePath,
                artifactRevision = "test",
                hasThinkingMode = false
            )

            every {
                context.getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
            } returns sharedPreferences
            every { sharedPreferences.getString("selected_slm_id", null) } returns null
            every { sharedPreferences.getBoolean("onboarding_completed", false) } returns true
            every { preferences.gbnfGrammarEnabled } returns gbnfEnabled
            coEvery {
                transactionRepository.exists(any<SmsSourceIdentity>())
            } returns false
            every { deviceCapabilities.assessDevice() } returns device
            every { storage.modelDirectory } returns modelDirectory
            every { storage.modelFile(any()) } answers {
                File(modelDirectory, firstArg<String>())
            }
            every { storage.readTextAsset("sms_extraction.gbnf") } returns "root ::= ..."
            every { runtime.state } returns MutableStateFlow(SlmRuntimeState())
            every { lease.model } returns spec
            every { lease.isReleased } returns false
            every { lease.owner } returns SlmRuntimeOwner.HOME_SYNC
            coEvery {
                runtime.acquire(SlmRuntimeOwner.HOME_SYNC, any())
            } returns lease
            coEvery { lease.release() } returns Unit
            every { promptBuilder.buildExtractionPrompt(any(), any()) } returns "raw prompt"
            every { promptBuilder.getStaticPrefix() } returns "static prefix"
            every { promptBuilder.buildChatPrompt(any(), any()) } returns "chat prompt"
            coEvery { lease.extract(any()) } coAnswers {
                capturedGrammar += firstArg<SlmExtractionRequest>().grammar
                if (capturedGrammar.size == 1) {
                    gbnfEnabled.value = false
                }
                SlmExtractionResult.Null(model = spec)
            }

            val manager = HomeSyncManager(
                context = context,
                smsRepository = smsRepository,
                smsFilterPipeline = SmsFilterPipeline(),
                transactionRepository = transactionRepository,
                accountRepository = accountRepository,
                slmRuntime = runtime,
                appFlowCoordinator = appFlowCoordinator,
                modelStorage = storage,
                deviceCapabilities = deviceCapabilities,
                promptBuilder = promptBuilder,
                extractionParser = extractionParser,
                slmProcessingPreferences = preferences,
                setupImportStore = readySetupStore()
            )

            manager.queueIncomingSms(
                address = "AX-HDFCBK",
                body = "Rs.500 credited to a/c XX0000",
                date = 1000L
            )
            manager.queueIncomingSms(
                address = "AX-HDFCBK",
                body = "Rs.700 debited from a/c XX0000",
                date = 2000L
            )
            manager.executeSync(mockk())

            assertEquals(listOf("root ::= ...", null), capturedGrammar)
            coVerify(exactly = 1) { lease.release() }

            assertEquals(HomeSyncState.Status.DONE, manager.syncState.value.status)
            val queuedAfterCompletion = manager.queueIncomingSms(
                address = "AX-HDFCBK",
                body = "Rs.900 debited from a/c XX0000",
                date = 3000L
            )
            assertEquals(
                IncomingSmsQueueResult.QUEUED_TRANSACTION,
                queuedAfterCompletion
            )
            assertEquals(HomeSyncState.Status.IDLE, manager.syncState.value.status)

            val duplicateResult = manager.queueIncomingSms(
                address = "AX-HDFCBK",
                body = "Rs.900 debited from a/c XX0000",
                date = 3000L
            )
            assertEquals(IncomingSmsQueueResult.IGNORED, duplicateResult)

            val legitimateSameSenderAndTime = manager.queueIncomingSms(
                address = "AX-HDFCBK",
                body = "Rs.1,200 credited to a/c XX0000",
                date = 3000L
            )
            assertEquals(
                IncomingSmsQueueResult.QUEUED_TRANSACTION,
                legitimateSameSenderAndTime
            )

            val nonTransactionResult = manager.queueIncomingSms(
                address = "VK-SHOP",
                body = "Your OTP is 123456. Do not share it.",
                date = 3500L
            )
            assertEquals(
                IncomingSmsQueueResult.IGNORED,
                nonTransactionResult
            )

            val queueBeforePause = manager.syncState.value.queue
            val pause = appFlowCoordinator.tryPauseAndDrain(
                SlmRuntimeOwner.SETTINGS_MANUAL
            )
            val admittedDuringReset = manager.queueIncomingSms(
                address = "AX-HDFCBK",
                body = "Rs.1,100 debited from a/c XX0000",
                date = 4000L
            )
            assertEquals(
                IncomingSmsQueueResult.ADMISSION_PAUSED,
                admittedDuringReset
            )
            assertEquals(queueBeforePause, manager.syncState.value.queue)
            pause!!.release()
        } finally {
            unmockkStatic(Log::class)
            modelDirectory.deleteRecursively()
        }
    }

    private fun readySetupStore(): SetupImportStore {
        val store = mockk<SetupImportStore>(relaxed = true)
        every { store.state } returns MutableStateFlow(
            SetupImportState(
                status = SetupImportStatus.READY,
                modelPrepared = true
            )
        )
        return store
    }

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
}
