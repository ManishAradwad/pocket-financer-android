package com.pocketfinancer.ui.home

import android.content.Context
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.inference.LlamaEngine
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.sms.SmsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeSyncManagerTest {

    @Test
    fun `grammar changes apply between SMS items in a foreground batch`() {
        runBlocking {
        val smsRepository = mockk<SmsRepository>()
        val transactionRepository = mockk<TransactionRepository>()
        val accountRepository = mockk<AccountRepository>()
        val llamaEngine = mockk<LlamaEngine>()
        val deviceCapabilities = mockk<DeviceCapabilities>()
        val promptBuilder = mockk<PromptBuilder>()
        val extractionParser = mockk<ExtractionParser>()
        val preferences = mockk<SlmProcessingPreferences>()
        val gbnfEnabled = MutableStateFlow(true)
        val capturedGrammar = mutableListOf<String?>()

        every { preferences.gbnfGrammarEnabled } returns gbnfEnabled
        coEvery { transactionRepository.exists(any(), any()) } returns false
        every { llamaEngine.isModelLoaded() } returns true
        every { llamaEngine.hasThinkingMode } returns false
        every { llamaEngine.getModelPath() } returns "model.gguf"
        every { llamaEngine.readAsset("sms_extraction.gbnf") } returns "root ::= ..."
        every { promptBuilder.buildExtractionPrompt(any(), any()) } returns "raw prompt"
        every { promptBuilder.getStaticPrefix() } returns "static prefix"
        every { llamaEngine.applyChatTemplate(any(), any()) } returns "chat prompt"
        coEvery {
            llamaEngine.inferForExtraction(any(), any(), any(), any(), any(), any(), any())
        } coAnswers {
            capturedGrammar += secondArg<String?>()
            if (capturedGrammar.size == 1) {
                gbnfEnabled.value = false
            }
            LlamaEngine.InferenceResult.Null
        }

        val manager = HomeSyncManager(
            context = mockk(),
            smsRepository = smsRepository,
            smsFilterPipeline = SmsFilterPipeline(),
            transactionRepository = transactionRepository,
            accountRepository = accountRepository,
            llamaEngine = llamaEngine,
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
        manager.executeSync(mockk<Context>())

            assertEquals(listOf("root ::= ...", null), capturedGrammar)
        }
    }
}
