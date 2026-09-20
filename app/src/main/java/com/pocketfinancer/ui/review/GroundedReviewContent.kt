package com.pocketfinancer.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.pocketfinancer.data.repository.SmsFieldCorrection
import com.pocketfinancer.data.repository.SmsFieldGroundingClassification
import com.pocketfinancer.data.repository.SmsReviewAction
import com.pocketfinancer.data.repository.SmsReviewDetails
import com.pocketfinancer.data.repository.SmsReviewGrounding
import com.pocketfinancer.data.repository.SmsReviewProposal
import com.pocketfinancer.data.repository.SmsReviewSourceSpan
import java.text.DateFormat
import java.util.Date
import org.json.JSONObject

@Composable
internal fun GroundedReviewContent(
    details: SmsReviewDetails,
    state: ReviewUiState,
    padding: PaddingValues,
    viewModel: ReviewViewModel
) {
    val original = requireNotNull(details.groundedProposal)
    val initial = details.draftProposal ?: original
    var working by remember(details.reviewCase.revision) { mutableStateOf(initial) }
    var active by remember { mutableStateOf(ReviewField.AMOUNT) }
    val selections = remember(details.reviewCase.revision) {
        mutableStateMapOf<ReviewField, SmsReviewSourceSpan?>().apply {
            put(ReviewField.AMOUNT, initial.amountSpan)
            put(ReviewField.DIRECTION, initial.directionSpan)
            put(ReviewField.ACCOUNT, initial.accountSpan)
            put(ReviewField.COUNTERPARTY, initial.counterpartySpan)
            details.draftCorrections.filter { it.evidenceJson == null }.forEach { correction ->
                ReviewField.entries.firstOrNull {
                    it.name.equals(correction.field, ignoreCase = true)
                }?.let { put(it, null) }
            }
        }
    }
    val source = details.source.rawMessage
    val corrections = reviewCorrections(original, working, selections)
    val requiredReady = selections[ReviewField.AMOUNT] != null &&
        selections[ReviewField.DIRECTION] != null &&
        selections[ReviewField.ACCOUNT] != null
    val duplicateBlocked = working.duplicateStatus == "already_persisted"
    val accountBlocked = working.accountStatus == "ambiguous"
    val canConfirm = requiredReady && !duplicateBlocked && !accountBlocked && !state.loading

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("${details.source.sender} · ${DateFormat.getDateTimeInstance().format(Date(working.receiptTimestampEpochMs))}")
        Text("Receipt time is read-only. Review the highlighted source wording before saving.")
        Text(reasonSummary(details))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            ReviewField.entries.forEach { field ->
                val selected = selections[field]
                AssistChip(
                    onClick = { active = field },
                    label = { Text(field.label) },
                    modifier = Modifier.semantics {
                        contentDescription = if (selected == null) {
                            "${field.label}, missing"
                        } else {
                            "${field.label}, selected: ${selected.text}"
                        }
                    }
                )
            }
        }
        EvidenceSelectionText(
            source = source,
            selections = selections,
            activeField = active,
            onSelectionChanged = { span ->
                updateSelection(active, span, working)?.let { updated ->
                    working = updated
                    selections[active] = span
                }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                selections[active] = null
                if (active == ReviewField.COUNTERPARTY) {
                    working = working.copy(counterparty = null, counterpartySpan = null)
                }
            }) { Text("Clear ${active.label}") }
            TextButton(onClick = {
                val extractor = original.span(active)
                selections[active] = extractor
                working = working.resetField(active, original)
            }) { Text("Reselect extractor value") }
        }
        Text("Amount: ${working.amountMinorUnits} ${working.currency} minor units")
        Text("Direction: ${working.direction}")
        Text("Account evidence: ${working.accountReference}")
        Text("Counterparty: ${working.counterparty ?: "Not supplied"}")
        Text(accountPreview(working, details))
        if (duplicateBlocked) Text("This source event was already saved. Confirmation is blocked.")
        if (accountBlocked) Text("More than one owned account matches this evidence. Confirmation is blocked.")
        Button(
            onClick = {
                viewModel.resolveGrounded(
                    if (corrections.isEmpty()) SmsReviewAction.CONFIRM else SmsReviewAction.CORRECT,
                    corrections
                )
            },
            enabled = canConfirm,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Confirm transaction") }
        OutlinedButton(
            onClick = { viewModel.resolveGrounded(SmsReviewAction.SAVE_DRAFT, corrections) },
            enabled = !state.loading,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Save draft") }
        TextButton(
            enabled = !state.loading,
            onClick = { viewModel.resolve(SmsReviewAction.RETRY, retryConfiguration = "current") }
        ) { Text("Retry extraction") }
        TextButton(
            enabled = !state.loading,
            onClick = { viewModel.resolveGrounded(SmsReviewAction.REJECT, emptyList()) }
        ) { Text("Not a transaction") }
        state.error?.let { Text(it) }
    }
}

private fun updateSelection(
    field: ReviewField,
    span: SmsReviewSourceSpan,
    current: SmsReviewProposal
): SmsReviewProposal? = runCatching {
    when (field) {
        ReviewField.AMOUNT -> current.copy(
            amountMinorUnits = SmsReviewGrounding.minorUnits(span.text, current.currency),
            amountSpan = span
        )
        ReviewField.DIRECTION -> current.copy(
            direction = requireNotNull(SmsReviewGrounding.directionFrom(span.text)),
            directionSpan = span
        )
        ReviewField.ACCOUNT -> current.copy(
            accountReference = SmsReviewGrounding.normalizeAccount(span.text).also { require(it.isNotBlank()) },
            accountSpan = span,
            accountStatus = "unresolved",
            resolvedAccountId = null
        )
        ReviewField.COUNTERPARTY -> current.copy(
            counterparty = SmsReviewGrounding.normalizeText(span.text).also { require(it.isNotBlank()) },
            counterpartySpan = span
        )
    }
}.getOrNull()

private fun reviewCorrections(
    original: SmsReviewProposal,
    working: SmsReviewProposal,
    selections: Map<ReviewField, SmsReviewSourceSpan?>
): List<SmsFieldCorrection> = buildList {
    fun add(field: String, span: SmsReviewSourceSpan?, value: String) {
        add(
            SmsFieldCorrection(
                field = field,
                classification = SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS,
                previousRevisionId = null,
                candidateId = null,
                evidenceJson = span?.let(::spanJson),
                newValueJson = value
            )
        )
    }
    val amountSpan = selections[ReviewField.AMOUNT]
    if (amountSpan == null || amountSpan != original.amountSpan ||
        working.amountMinorUnits != original.amountMinorUnits
    ) {
        add("amount", amountSpan, if (amountSpan == null) "null" else JSONObject()
            .put("minor_units", working.amountMinorUnits).put("currency", working.currency).toString())
    }
    val directionSpan = selections[ReviewField.DIRECTION]
    if (directionSpan == null || directionSpan != original.directionSpan ||
        working.direction != original.direction
    ) {
        add("direction", directionSpan, if (directionSpan == null) "null" else JSONObject.quote(working.direction))
    }
    val accountSpan = selections[ReviewField.ACCOUNT]
    if (accountSpan == null || accountSpan != original.accountSpan ||
        working.accountReference != original.accountReference
    ) {
        add("account", accountSpan, if (accountSpan == null) "null" else JSONObject()
            .put("reference", working.accountReference).toString())
    }
    val counterpartySpan = selections[ReviewField.COUNTERPARTY]
    if (counterpartySpan != original.counterpartySpan || working.counterparty != original.counterparty) {
        add(
            "counterparty",
            counterpartySpan,
            working.counterparty?.let(JSONObject::quote) ?: "null"
        )
    }
}

private fun spanJson(span: SmsReviewSourceSpan): String = JSONObject()
    .put("start_scalar", span.startScalar)
    .put("end_scalar", span.endScalar)
    .put("text", span.text)
    .toString()

private fun SmsReviewProposal.span(field: ReviewField): SmsReviewSourceSpan? = when (field) {
    ReviewField.AMOUNT -> amountSpan
    ReviewField.DIRECTION -> directionSpan
    ReviewField.ACCOUNT -> accountSpan
    ReviewField.COUNTERPARTY -> counterpartySpan
}

private fun SmsReviewProposal.resetField(
    field: ReviewField,
    original: SmsReviewProposal
): SmsReviewProposal = when (field) {
    ReviewField.AMOUNT -> copy(amountMinorUnits = original.amountMinorUnits, amountSpan = original.amountSpan)
    ReviewField.DIRECTION -> copy(direction = original.direction, directionSpan = original.directionSpan)
    ReviewField.ACCOUNT -> copy(
        accountReference = original.accountReference,
        accountSpan = original.accountSpan,
        accountStatus = original.accountStatus,
        resolvedAccountId = original.resolvedAccountId
    )
    ReviewField.COUNTERPARTY -> copy(
        counterparty = original.counterparty,
        counterpartySpan = original.counterpartySpan
    )
}

private fun reasonSummary(details: SmsReviewDetails): String {
    val reasons = details.reviewCase.reasonCodesJson
        .removePrefix("[").removeSuffix("]")
        .replace("\"", "")
        .split(',').map(String::trim).filter(String::isNotBlank)
    return reasons.firstOrNull()?.replace('_', ' ')?.replaceFirstChar(Char::uppercase)
        ?: "Review required"
}

private fun accountPreview(value: SmsReviewProposal, details: SmsReviewDetails): String = when {
    value.accountStatus == "ambiguous" -> "Account: ambiguous match"
    value.resolvedAccountId != null -> {
        val name = details.accounts.firstOrNull { it.id == value.resolvedAccountId }?.name
        "Account: ${name ?: "matched owned account"}"
    }
    value.accountStatus == "unresolved" ->
        "Account: aliases will be rechecked on confirmation; one match is reused, otherwise a new local account is created"
    else -> "Account: a new local account will be created only when you confirm"
}
