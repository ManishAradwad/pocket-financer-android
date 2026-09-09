package com.pocketfinancer.inference

import javax.inject.Inject
import javax.inject.Singleton

data class DirectCandidateSelectorRequest(
    val prompt: String,
    val candidatePayloadJson: String,
    val grammar: String?,
    val profile: CandidateSelectorRuntimeProfile = CandidateSelectorRuntimeProfile()
)

data class DirectCandidateSelectorResult(
    val rawOutput: String?,
    val completion: String,
    val safeErrorCode: String?,
    val model: SlmModelSpec,
    val performance: SlmPerformanceData?,
    val cache: SlmCacheDiagnostics?
)

fun interface DirectCandidateSelector {
    suspend fun select(
        lease: SlmLease,
        request: DirectCandidateSelectorRequest
    ): DirectCandidateSelectorResult
}

@Singleton
class DefaultDirectCandidateSelector @Inject constructor() : DirectCandidateSelector {
    override suspend fun select(
        lease: SlmLease,
        request: DirectCandidateSelectorRequest
    ): DirectCandidateSelectorResult {
        val runtimeRequest = SlmExtractionRequest(
            messages = listOf(
                SlmChatMessage("system", request.prompt),
                SlmChatMessage("user", request.candidatePayloadJson)
            ),
            fallbackPrompt = request.prompt + "\n" + request.candidatePayloadJson,
            grammar = request.grammar,
            answerTokens = request.profile.answerTokenLimit,
            jsonCallback = null
        )
        return when (val result = lease.extract(runtimeRequest)) {
            is SlmExtractionResult.Success -> {
                val bytes = result.json.toByteArray(Charsets.UTF_8).size
                if (bytes > request.profile.rawOutputByteLimit) {
                    DirectCandidateSelectorResult(
                        null, "invalid", "runtime_output_truncated",
                        result.model, result.perf, result.cache
                    )
                } else {
                    DirectCandidateSelectorResult(
                        result.json, "complete", null,
                        result.model, result.perf, result.cache
                    )
                }
            }
            is SlmExtractionResult.Null -> DirectCandidateSelectorResult(
                null, "failed", "runtime_output_truncated", result.model, result.perf, result.cache
            )
            is SlmExtractionResult.Error -> DirectCandidateSelectorResult(
                null, "failed", "runtime_unavailable", result.model, null, null
            )
            is SlmExtractionResult.Stopped -> DirectCandidateSelectorResult(
                null, "interrupted", "operation_interrupted", result.model, null, null
            )
        }
    }
}
