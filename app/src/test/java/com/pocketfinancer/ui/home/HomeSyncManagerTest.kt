package com.pocketfinancer.ui.home

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pocketfinancer.SlmAppFlowCoordinator
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
            coEvery { transactionRepository.exists(any(), any()) } returns false
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
                slmProcessingPreferences = preferences
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
