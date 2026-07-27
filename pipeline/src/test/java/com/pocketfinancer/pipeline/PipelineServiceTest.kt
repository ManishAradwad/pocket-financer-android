package com.pocketfinancer.pipeline

import com.pocketfinancer.data.model.Account
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.inference.SlmExtractionRequest
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.sms.SmsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

class PipelineServiceTest {

    private lateinit var lease: SlmLease
    private lateinit var model: SlmModelSpec
    private lateinit var promptBuilder: PromptBuilder
    private lateinit var extractionParser: ExtractionParser
    private lateinit var transactionRepository: TransactionRepository
    private lateinit var accountRepository: AccountRepository
    private lateinit var preferences: SlmProcessingPreferences
    private lateinit var modelStorage: SlmModelStorage
    private lateinit var gbnfEnabled: MutableStateFlow<Boolean>
    private lateinit var pipeline: PipelineService

    private val testSender = "AX-HDFCBK"
    private val testBody = "Rs.500 credited to a/c XX0000"

    @Before
    fun setUp() {
        model = SlmModelSpec(
            modelId = "test-model",
            modelPath = "build/test-model.gguf",
            hasThinkingMode = true
        )
        lease = mockk(relaxed = true)
        promptBuilder = mockk(relaxed = true)
        extractionParser = mockk()
        transactionRepository = mockk(relaxed = true)
        accountRepository = mockk()
        preferences = mockk()
        modelStorage = mockk()
        gbnfEnabled = MutableStateFlow(true)

        every { lease.model } returns model
        every {
            promptBuilder.buildExtractionPrompt(any(), any())
        } returns "Sender: AX-HDFCBK\nSMS: Rs.500 credited\nOutput:"
        every {
            promptBuilder.buildChatPrompt(any(), any())
        } returns "<manual-chat-template>"
        every { promptBuilder.getStaticPrefix() } returns "<static-prefix>"
        every {
            modelStorage.readTextAsset("sms_extraction.gbnf")
        } returns "root ::= ..."
        every { preferences.gbnfGrammarEnabled } returns gbnfEnabled
        every { extractionParser.parse(any()) } returns null
        coEvery { lease.extract(any()) } returns SlmExtractionResult.Null(model)

        val defaultAccount = Account(
            id = UUID.randomUUID().toString(),
            name = "__UNKNOWN__",
            bank = "Unknown Account",
            type = "auto-extracted"
        )
        coEvery { accountRepository.ensureDefault() } returns defaultAccount
        coEvery {
            accountRepository.getOrCreate(any(), any(), any())
        } returns Account(
            id = UUID.randomUUID().toString(),
            name = "A/c XX0000",
            bank = "HDFC Bank",
            type = "auto-extracted"
        )
        val insertResult = mockk<TransactionRepository.InsertResult>()
        every { insertResult.inserted } returns true
        coEvery {
            transactionRepository.insertIfAbsent(any())
        } returns insertResult

        pipeline = PipelineService(
            promptBuilder = promptBuilder,
            extractionParser = extractionParser,
            transactionRepository = transactionRepository,
            accountRepository = accountRepository,
            smsFilterPipeline = SmsFilterPipeline(),
            slmProcessingPreferences = preferences,
            modelStorage = modelStorage
        )
    }

    @Test
    fun `pipeline starts with null state`() = runTest {
        assertNull(pipeline.pipelineState.first())
    }

    @Test
    fun `enabled grammar is snapshotted and passed in immutable request`() = runTest {
        val request = slot<SlmExtractionRequest>()

        pipeline.processSingle(transactionSms(), lease)

        coVerify(exactly = 1) { lease.extract(capture(request)) }
        assertEquals("root ::= ...", request.captured.grammar)
        verify(exactly = 1) {
            modelStorage.readTextAsset("sms_extraction.gbnf")
        }
    }

    @Test
    fun `disabled grammar skips asset and sends null grammar`() = runTest {
        gbnfEnabled.value = false
        val request = slot<SlmExtractionRequest>()

        pipeline.processSingle(transactionSms(), lease)

        coVerify(exactly = 1) { lease.extract(capture(request)) }
        assertNull(request.captured.grammar)
        verify(exactly = 0) {
            modelStorage.readTextAsset("sms_extraction.gbnf")
        }
    }

    @Test
    fun `in-flight SMS keeps grammar snapshot and next SMS uses new setting`() = runTest {
        val inferenceStarted = CompletableDeferred<Unit>()
        val releaseFirstInference = CompletableDeferred<Unit>()
        val capturedGrammar = mutableListOf<String?>()

        coEvery { lease.extract(any()) } coAnswers {
            val request = firstArg<SlmExtractionRequest>()
            capturedGrammar += request.grammar
            if (capturedGrammar.size == 1) {
                inferenceStarted.complete(Unit)
                releaseFirstInference.await()
            }
            SlmExtractionResult.Null(model)
        }

        val firstSms = async {
            pipeline.processSingle(transactionSms(date = 1_000L), lease)
        }
        inferenceStarted.await()
        gbnfEnabled.value = false
        releaseFirstInference.complete(Unit)
        firstSms.await()

        pipeline.processSingle(transactionSms(date = 2_000L), lease)

        assertEquals(listOf("root ::= ...", null), capturedGrammar)
        verify(exactly = 1) {
            modelStorage.readTextAsset("sms_extraction.gbnf")
        }
    }

    @Test
    fun `raw messages and fallback prompt are handed to runtime for atomic templating`() =
        runTest {
            val request = slot<SlmExtractionRequest>()

            pipeline.processSingle(transactionSms(), lease)

            coVerify { lease.extract(capture(request)) }
            assertEquals(listOf("system", "user"), request.captured.messages.map { it.role })
            assertEquals("<manual-chat-template>", request.captured.fallbackPrompt)
            assertEquals("<static-prefix>", request.captured.staticPrefix)
            verify {
                promptBuilder.buildChatPrompt(
                    rawPrompt = any(),
                    enableThinking = true
                )
            }
        }

    @Test
    fun `successful extraction persists outside runtime call with exact model artifact`() =
        runTest {
            every { extractionParser.parse(any()) } returns extractedTransaction()
            coEvery { lease.extract(any()) } returns SlmExtractionResult.Success(
                json = """{"amount":500.0,"type":"credit"}""",
                model = model
            )

            val result = pipeline.processSingle(transactionSms(), lease)

            assertIs<PipelineService.ProcessingResult.Saved>(result)
            coVerify(exactly = 1) {
                transactionRepository.insertIfAbsent(match {
                    it.amount == 500.0 &&
                        it.merchant == "UPI Ref 12345" &&
                        it.slmModelName == "test-model.gguf"
                })
            }
            assertEquals(PipelineService.Stage.SAVED, pipeline.pipelineState.value?.stage)
        }

    @Test
    fun `null inference is typed as skipped rather than operational failure`() = runTest {
        val result = pipeline.processSingle(transactionSms(), lease)

        assertEquals(
            PipelineService.ProcessingResult.Skipped(
                PipelineService.SkipReason.NOT_TRANSACTION
            ),
            result
        )
        assertEquals(PipelineService.Stage.SKIPPED, pipeline.pipelineState.value?.stage)
    }

    @Test
    fun `parser rejection is distinguished from model null`() = runTest {
        coEvery { lease.extract(any()) } returns SlmExtractionResult.Success(
            json = """{"amount":null}""",
            model = model
        )

        val result = pipeline.processSingle(transactionSms(), lease)

        assertEquals(
            PipelineService.ProcessingResult.Skipped(
                PipelineService.SkipReason.EXTRACTION_REJECTED
            ),
            result
        )
    }

    @Test
    fun `stopped inference has typed stopped result`() = runTest {
        coEvery { lease.extract(any()) } returns SlmExtractionResult.Stopped(model)

        val result = pipeline.processSingle(transactionSms(), lease)

        assertEquals(PipelineService.ProcessingResult.Stopped, result)
        assertEquals("Inference stopped", pipeline.pipelineState.value?.message)
    }

    @Test
    fun `runtime error is retryable typed failure`() = runTest {
        coEvery { lease.extract(any()) } returns SlmExtractionResult.Error(
            message = "OOM: out of memory",
            model = model
        )

        val result = pipeline.processSingle(transactionSms(), lease)

        assertEquals(
            PipelineService.ProcessingResult.Failure(
                message = "OOM: out of memory",
                retryable = true
            ),
            result
        )
    }

    @Test
    fun `cancellation is never converted into pipeline failure`() = runTest {
        coEvery { lease.extract(any()) } throws CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            pipeline.processSingle(transactionSms(), lease)
        }
        coVerify(exactly = 0) { transactionRepository.insertIfAbsent(any()) }
    }

    @Test
    fun `non-transactional SMS never reaches runtime`() = runTest {
        val sms = SmsReader.SmsMessage(
            address = "+919999999999",
            body = "Hello there, Rs. 500",
            date = 1_000L,
            type = 1
        )

        val result = pipeline.processSingle(sms, lease)

        assertIs<PipelineService.ProcessingResult.Skipped>(result)
        coVerify(exactly = 0) { lease.extract(any()) }
    }

    private fun transactionSms(date: Long = 1_000L) = SmsReader.SmsMessage(
        address = testSender,
        body = testBody,
        date = date,
        type = 1
    )

    private fun extractedTransaction() = ExtractionParser.ExtractedTransaction(
        amount = 500.0,
        counterparty = "UPI Ref 12345",
        type = TransactionType.CREDIT,
        account = "A/c XX0000"
    )
}
