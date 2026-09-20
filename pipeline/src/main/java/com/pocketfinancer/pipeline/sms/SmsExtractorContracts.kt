package com.pocketfinancer.pipeline.sms

sealed interface SmsExtractorResult {
    data object None : SmsExtractorResult
    data object Abstain : SmsExtractorResult
    data class Posted(
        val amount: ExtractedAmount,
        val direction: ExtractedDirection,
        val account: ExtractedAccount,
        val counterparty: ExtractedCounterparty?
    ) : SmsExtractorResult
}

data class ExtractedAmount(val value: String, val currency: String, val evidence: UnicodeScalarSpan)
data class ExtractedDirection(val value: String, val evidence: UnicodeScalarSpan)
data class ExtractedAccount(val reference: String, val evidence: UnicodeScalarSpan)
data class ExtractedCounterparty(val value: String, val evidence: UnicodeScalarSpan)

data class NormalizedSmsExtraction(
    val minorUnits: Long,
    val currency: String,
    val currencyScale: Int,
    val direction: String,
    val accountReference: String,
    val accountEvidence: UnicodeScalarSpan,
    val counterparty: String?,
    val amountEvidence: UnicodeScalarSpan,
    val directionEvidence: UnicodeScalarSpan,
    val counterpartyEvidence: UnicodeScalarSpan?
)

class SmsExtractorValidationException(val reasonCode: String) : IllegalArgumentException(reasonCode)
