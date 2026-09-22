package com.pocketfinancer.inference

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.test.runTest

class DefaultDirectCandidateSelectorTest {
    @Test
    fun `selector forwards decoded callback to the runtime request in order`() = runTest {
        val decoded = mutableListOf<String>()
        val model = SlmModelSpec("test", "/tmp/test.gguf")
        var capturedRequest: SlmExtractionRequest? = null
        val lease = object : SlmLease {
            override val owner = SlmRuntimeOwner.HOME_SYNC
            override val model = model
            override val isReleased = false

            override suspend fun extract(
                request: SlmExtractionRequest
            ): SlmExtractionResult {
                capturedRequest = request
                request.jsonCallback?.onToken("{")
                request.jsonCallback?.onToken("\"decision\":\"none\"}")
                return SlmExtractionResult.Success(
                    json = "{\"decision\":\"none\"}",
                    model = model
                )
            }

            override suspend fun countTokens(
                text: String,
                addSpecial: Boolean
            ): Int = 1

            override suspend fun release() = Unit
        }

        val result = DefaultDirectCandidateSelector().select(
            lease = lease,
            request = DirectCandidateSelectorRequest(
                prompt = "prompt",
                candidatePayloadJson = "{}",
                grammar = "root ::= object",
                jsonCallback = SlmTokenCallback(decoded::add)
            )
        )

        assertNotNull(capturedRequest?.jsonCallback)
        assertEquals(listOf("{", "\"decision\":\"none\"}"), decoded)
        assertEquals("{\"decision\":\"none\"}", result.rawOutput)
    }
}
