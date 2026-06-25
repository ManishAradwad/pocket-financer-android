package com.pocketfinancer.pipeline

import com.pocketfinancer.data.model.Account
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.inference.LlamaEngine
import com.pocketfinancer.sms.SmsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PipelineServiceTest {

    private lateinit var llamaEngine: LlamaEngine
    private lateinit var promptBuilder: PromptBuilder
    private lateinit var extractionParser: ExtractionParser
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var pipeline: PipelineService

    private val testSender = "AX-HDFCBK"
    private val testBody = "Rs.500 credited to a/c XX0000"

    @Before
    fun setUp() {
        llamaEngine = mockk(relaxed = true)
        promptBuilder = mockk(relaxed = true)
        extractionParser = mockk()
        transactionRepository = mockk(relaxed = true)
        accountRepository = mockk()

        every { llamaEngine.isModelLoaded() } returns true
        every { promptBuilder.buildExtractionPrompt(any(), any()) } returns "Sender: AX-HDFCBK\nSMS: Rs.500 credited\nOutput:"
        every { promptBuilder.buildChatPrompt(any(), any()) } returns """<|im_start|>system
You are a helpful financial SMS extraction assistant.
<|im_end|>
<|im_start|>user
Sender: AX-HDFCBK
SMS: Rs.500 credited
Output:
<|im_end|>
<|im_start|>assistant
"""
        every { llamaEngine.applyChatTemplate(any(), any()) } returns null
        every { llamaEngine.readAsset("sms_extraction.gbnf") } returns "root ::= ..."

        val defaultAccount = Account(
            id = UUID.randomUUID().toString(),
            name = "__UNKNOWN__",
            bank = "Unknown Bank",
            type = "auto-extracted"
        )
        coEvery { accountRepository.ensureDefault() } returns defaultAccount
        coEvery { accountRepository.getOrCreate(any(), any(), any()) } returns Account(
            id = UUID.randomUUID().toString(),
            name = "A/c XX0000",
            bank = "HDFC Bank",
            type = "auto-extracted"
        )

        pipeline = PipelineService(
            llamaEngine = llamaEngine,
            promptBuilder = promptBuilder,
            extractionParser = extractionParser,
            transactionRepository = transactionRepository,
            accountRepository = accountRepository,
            smsFilterPipeline = SmsFilterPipeline()
        )
    }

    @Test
    fun `pipeline should start with null state`() {
        runBlocking {
            val state = pipeline.pipelineState.first()
            assertNull(state)
        }
    }

    @Test
    fun `enqueue should process SMS and emit EXTRACTED for valid transaction`() {
        runBlocking {
            every { extractionParser.parse(any()) } returns ExtractionParser.ExtractedTransaction(
                amount = 500.0,
                counterparty = "UPI Ref 12345",
                type = TransactionType.CREDIT,
                account = "A/c XX0000"
            )
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Success(
                    json = """{"amount": 500.0, "type": "credit", "account": "A/c XX0000", "counterparty": "UPI Ref 12345"}""",
                    perf = null
                )
            pipeline.enqueue(SmsReader.SmsMessage(testSender, testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            val finalState = pipeline.pipelineState.first()
            assertNotNull(finalState)
        }
    }

    @Test
    fun `enqueue should process SMS and emit SKIPPED for null result`() {
        runBlocking {
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Null
            pipeline.enqueue(SmsReader.SmsMessage(testSender, testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            val finalState = pipeline.pipelineState.first()
            assertNotNull(finalState)
            assertEquals(PipelineService.Stage.SKIPPED, finalState.stage)
        }
    }

    @Test
    fun `enqueue should emit ERROR when model is not loaded`() {
        runBlocking {
            every { llamaEngine.isModelLoaded() } returns false
            pipeline.enqueue(SmsReader.SmsMessage(testSender, testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            val finalState = pipeline.pipelineState.first()
            assertNotNull(finalState)
            assertEquals(PipelineService.Stage.ERROR, finalState.stage)
        }
    }

    @Test
    fun `enqueue should emit ERROR when inference fails`() {
        runBlocking {
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Error("OOM: out of memory")
            pipeline.enqueue(SmsReader.SmsMessage(testSender, testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            val finalState = pipeline.pipelineState.first()
            assertNotNull(finalState)
            assertEquals(PipelineService.Stage.ERROR, finalState.stage)
        }
    }

    @Test
    fun `queue should drop messages beyond max size`() {
        runBlocking {
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Null
            for (i in 0..200) {
                pipeline.enqueue(SmsReader.SmsMessage(testSender, "Rs $i credited to a/c XX0000", i * 1000L, 1))
            }
            pipeline.drain(timeoutMs = 5000)
            assert(pipeline.queueSize == 0)
        }
    }

    @Test
    fun `pipeline should use applyChatTemplate before falling back to manual`() {
        runBlocking {
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Null
            pipeline.enqueue(SmsReader.SmsMessage(testSender, testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            verify(atLeast = 1) { llamaEngine.applyChatTemplate(any(), any()) }
        }
    }

    @Test
    fun `enqueueBatch should process all messages`() {
        runBlocking {
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Null
            val messages = (1..5).map { i ->
                SmsReader.SmsMessage(testSender, "Rs $i credited to a/c XX0000", i * 1000L, 1)
            }
            pipeline.enqueueBatch(messages)
            pipeline.drain(timeoutMs = 5000)
            val finalState = pipeline.pipelineState.first()
            assertNotNull(finalState)
        }
    }

    @Test
    fun `SAVED stage should include transaction details`() {
        runBlocking {
            every { extractionParser.parse(any()) } returns ExtractionParser.ExtractedTransaction(
                amount = 1500.0,
                counterparty = "Amazon Pay",
                type = TransactionType.DEBIT,
                account = "A/c XX6254"
            )
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Success(
                    json = """{"amount": 1500.0, "type": "debit", "account": "A/c XX6254", "counterparty": "Amazon Pay"}""",
                    perf = null
                )
            pipeline.enqueue(SmsReader.SmsMessage(testSender, testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            coVerify(atLeast = 1) { transactionRepository.insert(any()) }
        }
    }

    @Test
    fun `pipeline should process messages sequentially`() {
        runBlocking {
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Null
            pipeline.enqueue(SmsReader.SmsMessage(testSender, "Rs 100 credited to a/c XX0000", 1000L, 1))
            pipeline.enqueue(SmsReader.SmsMessage(testSender, "Rs 200 credited to a/c XX0000", 2000L, 1))
            pipeline.enqueue(SmsReader.SmsMessage(testSender, "Rs 300 credited to a/c XX0000", 3000L, 1))
            pipeline.drain(timeoutMs = 5000)
            assertEquals(0, pipeline.queueSize)
        }
    }

    @Test
    fun `enqueue should emit ERROR when inference is stopped`() {
        runBlocking {
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Stopped
            pipeline.enqueue(SmsReader.SmsMessage(testSender, testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            val finalState = pipeline.pipelineState.first()
            assertNotNull(finalState)
            assertEquals(PipelineService.Stage.ERROR, finalState.stage)
            assertEquals("Inference stopped", finalState.message)
        }
    }

    @Test
    fun `enqueue should emit SKIPPED when parsing returns null after success`() {
        runBlocking {
            every { extractionParser.parse(any()) } returns null
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Success(
                    json = """{"amount": null}""",
                    perf = null
                )
            pipeline.enqueue(SmsReader.SmsMessage(testSender, testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            val finalState = pipeline.pipelineState.first()
            assertNotNull(finalState)
            assertEquals(PipelineService.Stage.SKIPPED, finalState.stage)
            assertEquals("Nonnull filter rejected extraction", finalState.message)
        }
    }

    @Test
    fun `enqueue should silently drop non-transactional SMS`() {
        runBlocking {
            val nonTxnSms = SmsReader.SmsMessage("+919999999999", "Hello there, Rs. 500", 1000L, 1)
            pipeline.enqueue(nonTxnSms)
            pipeline.drain(timeoutMs = 1000)
            assertEquals(0, pipeline.queueSize)
            coVerify(exactly = 0) { llamaEngine.inferForExtraction(any(), any(), any(), any(), any(), any()) }
        }
    }

    @Test
    fun `enqueue should fallback to inferred bank merchant name when counterparty is missing`() {
        runBlocking {
            every { extractionParser.parse(any()) } returns ExtractionParser.ExtractedTransaction(
                amount = 1500.0,
                counterparty = null,
                type = TransactionType.DEBIT,
                account = "A/c XX6254"
            )
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Success(
                    json = """{"amount": 1500.0, "type": "debit", "account": "A/c XX6254"}""",
                    perf = null
                )
            pipeline.enqueue(SmsReader.SmsMessage("AX-HDFCBK", testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            coVerify {
                transactionRepository.insert(match {
                    it.merchant == "Transaction (HDFC Bank)"
                })
            }
        }
    }

    @Test
    fun `enqueue should fallback to Unknown Merchant when counterparty and bank are unknown`() {
        runBlocking {
            every { extractionParser.parse(any()) } returns ExtractionParser.ExtractedTransaction(
                amount = 1500.0,
                counterparty = null,
                type = TransactionType.DEBIT,
                account = "A/c XX6254"
            )
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Success(
                    json = """{"amount": 1500.0, "type": "debit", "account": "A/c XX6254"}""",
                    perf = null
                )
            pipeline.enqueue(SmsReader.SmsMessage("UNKNOWN_SENDER", testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            coVerify {
                transactionRepository.insert(match {
                    it.merchant == "Unknown Merchant"
                })
            }
        }
    }

    @Test
    fun `pipeline should use manual Qwen3 chat template if applyChatTemplate returns null`() {
        runBlocking {
            every { llamaEngine.applyChatTemplate(any(), any()) } returns null
            coEvery { llamaEngine.inferForExtraction(any(), any(), any(), any(), any(), any()) } returns
                LlamaEngine.InferenceResult.Null
            pipeline.enqueue(SmsReader.SmsMessage(testSender, testBody, 1000L, 1))
            pipeline.drain(timeoutMs = 5000)
            verify(atLeast = 1) { promptBuilder.buildChatPrompt(any(), any()) }
        }
    }
}
