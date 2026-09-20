package com.pocketfinancer.pipeline.sms

/** Strict, deliberately small JSON parser for the frozen extractor wire shape.
 * org.json is unsuitable here because it accepts duplicate keys and trailing data. */
object SmsExtractorValidator {
    fun validate(raw: String, source: String, byteLimit: Int = 16_384): SmsExtractorResult {
        if (hasUnpairedSurrogate(raw)) fail("extractor_malformed_json")
        if (raw.toByteArray(Charsets.UTF_8).size > byteLimit) fail("runtime_output_truncated")
        val root = try {
            StrictJson(raw).document()
        } catch (error: StrictJsonFailure) {
            fail(error.reasonCode)
        }
        val objectValue = root as? JsonValue.Obj ?: fail("extractor_output_not_object")
        val decision = string(
            objectValue,
            "decision",
            "extractor_decision_type_invalid"
        )
        return when (decision) {
            "none" -> {
                exactKeys(
                    objectValue,
                    setOf("decision"),
                    "extractor_non_posted_extra_fields"
                )
                SmsExtractorResult.None
            }
            "abstain" -> {
                exactKeys(
                    objectValue,
                    setOf("decision"),
                    "extractor_non_posted_extra_fields"
                )
                SmsExtractorResult.Abstain
            }
            "posted" -> posted(objectValue, source)
            else -> fail("extractor_unknown_decision")
        }
    }

    private fun posted(root: JsonValue.Obj, source: String): SmsExtractorResult.Posted {
        val required = setOf("decision", "amount", "direction", "account", "counterparty")
        if (root.values.keys != required) {
            when {
                "amount" !in root.values -> fail("extractor_missing_amount")
                "direction" !in root.values -> fail("extractor_missing_direction")
                "account" !in root.values -> fail("extractor_missing_account")
                else -> fail("extractor_posted_field_set_invalid")
            }
        }
        val amount = obj(root, "amount", "extractor_amount_invalid").also {
            exactKeys(
                it,
                setOf("value", "currency", "evidence"),
                "extractor_amount_invalid"
            )
        }
        val direction = obj(root, "direction", "extractor_direction_invalid").also {
            exactKeys(it, setOf("value", "evidence"), "extractor_direction_invalid")
        }
        val account = obj(root, "account", "extractor_account_reference_invalid").also {
            exactKeys(
                it,
                setOf("reference", "evidence"),
                "extractor_account_reference_invalid"
            )
        }
        val counterpartyValue = root.values.getValue("counterparty")
        val counterparty = if (counterpartyValue == JsonValue.Null) null else (counterpartyValue as? JsonValue.Obj)?.let {
            exactKeys(it, setOf("value", "evidence"), "extractor_counterparty_invalid")
            ExtractedCounterparty(
                nonEmptyString(it, "value", "extractor_counterparty_invalid"),
                span(obj(it, "evidence", "extractor_counterparty_invalid"), source)
            )
        } ?: fail("extractor_counterparty_invalid")
        val currency = nonEmptyString(amount, "currency", "extractor_currency_invalid")
        if (
            !Regex("^[A-Z]{3}$").matches(currency) ||
            currency !in CurrencyProfileRegistry.scales
        ) {
            fail("extractor_currency_invalid")
        }
        val directionValue = nonEmptyString(
            direction,
            "value",
            "extractor_direction_invalid"
        )
        if (directionValue !in setOf("debit", "credit")) fail("extractor_direction_invalid")
        return SmsExtractorResult.Posted(
            ExtractedAmount(
                nonEmptyString(amount, "value", "extractor_amount_invalid"),
                currency,
                span(obj(amount, "evidence", "extractor_amount_invalid"), source)
            ),
            ExtractedDirection(
                directionValue,
                span(obj(direction, "evidence", "extractor_direction_invalid"), source)
            ),
            ExtractedAccount(
                nonEmptyString(
                    account,
                    "reference",
                    "extractor_account_reference_invalid"
                ),
                span(
                    obj(account, "evidence", "extractor_account_reference_invalid"),
                    source
                )
            ),
            counterparty
        )
    }

    private fun span(value: JsonValue.Obj, source: String): UnicodeScalarSpan {
        exactKeys(
            value,
            setOf("start_scalar", "end_scalar", "text"),
            "extractor_evidence_invalid"
        )
        val start = integer(value, "start_scalar")
        val end = integer(value, "end_scalar")
        val text = string(value, "text", "extractor_evidence_mismatch")
        val exact = UnicodeScalarSpans.slice(source, start, end)
            ?: fail("extractor_evidence_out_of_bounds")
        if (text != exact) fail("extractor_evidence_mismatch")
        return UnicodeScalarSpan(start, end, exact)
    }

    private fun exactKeys(
        value: JsonValue.Obj,
        expected: Set<String>,
        reason: String
    ) {
        if (value.values.keys != expected) fail(reason)
    }
    private fun obj(value: JsonValue.Obj, key: String, reason: String): JsonValue.Obj =
        value.values[key] as? JsonValue.Obj ?: fail(reason)
    private fun string(value: JsonValue.Obj, key: String, reason: String): String =
        (value.values[key] as? JsonValue.Str)?.value ?: fail(reason)
    private fun nonEmptyString(value: JsonValue.Obj, key: String, reason: String): String =
        string(value, key, reason).takeIf { it.isNotEmpty() } ?: fail(reason)
    private fun integer(value: JsonValue.Obj, key: String): Int =
        (value.values[key] as? JsonValue.Num)?.raw?.toIntOrNull()?.takeIf { it >= 0 }
            ?: fail("extractor_evidence_out_of_bounds")
    private fun hasUnpairedSurrogate(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                Character.isHighSurrogate(current) -> {
                    if (
                        index + 1 >= value.length ||
                        !Character.isLowSurrogate(value[index + 1])
                    ) {
                        return true
                    }
                    index += 2
                }
                Character.isLowSurrogate(current) -> return true
                else -> index += 1
            }
        }
        return false
    }
    private fun fail(reason: String): Nothing = throw SmsExtractorValidationException(reason)
}

private sealed interface JsonValue {
    data class Obj(val values: Map<String, JsonValue>) : JsonValue
    data class Str(val value: String) : JsonValue
    data class Num(val raw: String) : JsonValue
    data object Bool : JsonValue
    data object Null : JsonValue
}

private class StrictJsonFailure(val reasonCode: String) : IllegalArgumentException(reasonCode)

private class StrictJson(private val input: String) {
    private var at = 0
    fun document(): JsonValue {
        val value = value()
        whitespace()
        if (at != input.length) {
            throw StrictJsonFailure("extractor_extra_content")
        }
        return value
    }
    private fun value(): JsonValue { whitespace(); return when (peek()) {
        '{' -> obj(); '"' -> JsonValue.Str(string()); 'n' -> literal("null", JsonValue.Null)
        't' -> literal("true", JsonValue.Bool); 'f' -> literal("false", JsonValue.Bool)
        '-', in '0'..'9' -> number(); else -> bad()
    }}
    private fun obj(): JsonValue.Obj { take('{'); whitespace(); val map = linkedMapOf<String, JsonValue>(); if (consume('}')) return JsonValue.Obj(map)
        while (true) { whitespace(); if (peek() != '"') bad(); val key = string(); if (map.containsKey(key)) throw StrictJsonFailure("extractor_duplicate_json_key"); whitespace(); take(':'); map[key] = value(); whitespace(); if (consume('}')) return JsonValue.Obj(map); take(',') }
    }
    private fun string(): String { take('"'); val out = StringBuilder(); while (at < input.length) { val c = input[at++]; when (c) { '"' -> return out.toString(); '\\' -> { val e = next(); when (e) { '"','\\','/' -> out.append(e); 'b' -> out.append('\b'); 'f' -> out.append('\u000c'); 'n' -> out.append('\n'); 'r' -> out.append('\r'); 't' -> out.append('\t'); 'u' -> { val high = hex(); if (high in 0xD800..0xDBFF) { if (next() != '\\' || next() != 'u') bad(); val low = hex(); if (low !in 0xDC00..0xDFFF) bad(); out.appendCodePoint(Character.toCodePoint(high.toChar(), low.toChar())) } else if (high in 0xDC00..0xDFFF) bad() else out.append(high.toChar()) }; else -> bad() } }; else -> { if (c.code < 0x20) bad(); out.append(c) } } }; bad() }
    private fun number(): JsonValue.Num { val start = at; consume('-'); if (consume('0')) { } else { digit1(); while (peekOrNull()?.isDigit() == true) at++ }; if (consume('.')) { digit1(); while (peekOrNull()?.isDigit() == true) at++ }; if (peekOrNull() in listOf('e','E')) { at++; if (peekOrNull() in listOf('+','-')) at++; digit1(); while (peekOrNull()?.isDigit() == true) at++ }; return JsonValue.Num(input.substring(start, at)) }
    private fun literal(text: String, value: JsonValue): JsonValue { if (!input.regionMatches(at, text, 0, text.length)) bad(); at += text.length; return value }
    private fun whitespace() {
        while (peekOrNull() in setOf('\t', '\n', '\r', ' ')) at++
    }
    private fun hex(): Int { if (at + 4 > input.length) bad(); val s = input.substring(at, at + 4); at += 4; return s.toIntOrNull(16) ?: bad() }
    private fun digit1() { if (peekOrNull()?.isDigit() != true) bad(); at++ }
    private fun consume(c: Char): Boolean = if (peekOrNull() == c) { at++; true } else false
    private fun take(c: Char) { if (!consume(c)) bad() }
    private fun peek(): Char = peekOrNull() ?: bad()
    private fun peekOrNull(): Char? = input.getOrNull(at)
    private fun next(): Char = if (at < input.length) input[at++] else bad()
    private fun bad(): Nothing = throw StrictJsonFailure("extractor_malformed_json")
}
