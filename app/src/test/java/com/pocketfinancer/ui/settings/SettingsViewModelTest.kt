package com.pocketfinancer.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.inference.LlamaEngine
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsFilterPipeline
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import io.mockk.coEvery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `grammar preference is exposed and updated through settings state`() = runTest(dispatcher) {
        val context = mockk<Context>()
        val sharedPreferences = mockk<SharedPreferences>()
        val deviceCapabilities = mockk<DeviceCapabilities>()
        val llamaEngine = mockk<LlamaEngine>()
        val preferences = mockk<SlmProcessingPreferences>()
        val gbnfEnabled = MutableStateFlow(false)

        every { context.getSharedPreferences(".app_settings", Context.MODE_PRIVATE) } returns sharedPreferences
        every { sharedPreferences.getString("selected_slm_id", null) } returns null
        every { sharedPreferences.getBoolean("process_incoming_sms", true) } returns true
        every { preferences.gbnfGrammarEnabled } returns gbnfEnabled
        every { preferences.setGbnfGrammarEnabled(any()) } answers {
            gbnfEnabled.value = firstArg<Boolean>()
        }
        every { deviceCapabilities.assessDevice() } returns testDeviceInfo()
        every { llamaEngine.getModelStorageDir() } returns File("build/nonexistent-models")
        every { llamaEngine.isModelLoaded() } returns false
        every { llamaEngine.getModelPath() } returns null

        val viewModel = SettingsViewModel(
            context = context,
            deviceCapabilities = deviceCapabilities,
            llamaEngine = llamaEngine,
            modelDownloader = ModelDownloader(),
            promptBuilder = mockk<PromptBuilder>(relaxed = true),
            extractionParser = mockk<ExtractionParser>(relaxed = true),
            smsFilterPipeline = SmsFilterPipeline(),
            transactionRepository = mockk<TransactionRepository>(relaxed = true),
            accountRepository = mockk<AccountRepository>(relaxed = true),
            slmProcessingPreferences = preferences
        )

        runCurrent()
        assertFalse(viewModel.state.value.gbnfGrammarEnabled)

        viewModel.setGbnfGrammarEnabled(true)
        runCurrent()

        assertTrue(viewModel.state.value.gbnfGrammarEnabled)
    }

    @Test
    fun `test SMS snapshots disabled grammar for the complete inference`() = runTest(dispatcher) {
        val context = mockk<Context>()
        val sharedPreferences = mockk<SharedPreferences>()
        val deviceCapabilities = mockk<DeviceCapabilities>()
        val llamaEngine = mockk<LlamaEngine>()
        val promptBuilder = mockk<PromptBuilder>()
        val preferences = mockk<SlmProcessingPreferences>()
        val gbnfEnabled = MutableStateFlow(false)
        val inferenceCalled = CountDownLatch(1)
        var capturedGrammar: String? = "not captured"

        every { context.getSharedPreferences(".app_settings", Context.MODE_PRIVATE) } returns sharedPreferences
        every { sharedPreferences.getString("selected_slm_id", null) } returns null
        every { sharedPreferences.getBoolean("process_incoming_sms", true) } returns true
        every { preferences.gbnfGrammarEnabled } returns gbnfEnabled
        every { deviceCapabilities.assessDevice() } returns testDeviceInfo()
        every { llamaEngine.getModelStorageDir() } returns File("build/nonexistent-models")
        every { llamaEngine.isModelLoaded() } returns true
        every { llamaEngine.getModelPath() } returns null
        every { llamaEngine.hasThinkingMode } returns false
        every { promptBuilder.getStaticPrefix() } returns "static prefix"
        every { promptBuilder.buildExtractionPrompt(any(), any()) } returns "raw prompt"
        every { promptBuilder.buildChatPrompt(any(), any()) } returns "chat prompt"
        coEvery {
            llamaEngine.inferForExtraction(any(), null, any(), any(), any(), any(), any())
        } coAnswers {
            capturedGrammar = secondArg()
            inferenceCalled.countDown()
            LlamaEngine.InferenceResult.Null
        }

        val viewModel = SettingsViewModel(
            context = context,
            deviceCapabilities = deviceCapabilities,
            llamaEngine = llamaEngine,
            modelDownloader = ModelDownloader(),
            promptBuilder = promptBuilder,
            extractionParser = mockk<ExtractionParser>(relaxed = true),
            smsFilterPipeline = SmsFilterPipeline(),
            transactionRepository = mockk<TransactionRepository>(relaxed = true),
            accountRepository = mockk<AccountRepository>(relaxed = true),
            slmProcessingPreferences = preferences
        )

        runCurrent()
        viewModel.runTestSms()
        advanceTimeBy(1_801)
        runCurrent()

        assertTrue(inferenceCalled.await(5, TimeUnit.SECONDS))
        assertNull(capturedGrammar)
        verify(exactly = 0) { llamaEngine.readAsset("sms_extraction.gbnf") }
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
