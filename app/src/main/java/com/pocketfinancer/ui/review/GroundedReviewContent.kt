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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import com.pocketfinancer.data.repository.SmsReviewRetainedEvidence
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
    if (details.operation.contractReleaseId == "native-integration-v5") {
        AutomaticPolicyReviewContent(details, state, padding, viewModel)
        return
    }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AutomaticPolicyReviewContent(
    details: SmsReviewDetails,
    state: ReviewUiState,
    padding: PaddingValues,
    viewModel: ReviewViewModel
) {
    val retained = requireNotNull(details.retainedEvidence)
    val seed = remember(details.reviewCase.revision, details.reviewCase.draftJson) {
        automaticReviewSeed(details, retained)
    }
    var working by remember(details.reviewCase.revision) { mutableStateOf(seed.working) }
    var selectedAccountId by remember(details.reviewCase.revision) {
        mutableStateOf(seed.selectedAccountId)
    }
    var active by remember { mutableStateOf(ReviewField.AMOUNT) }
    var accountMenu by remember { mutableStateOf(false) }
    val selections = remember(details.reviewCase.revision) {
        mutableStateMapOf<ReviewField, SmsReviewSourceSpan?>().apply {
            putAll(seed.selections)
        }
    }
    val source = details.source.rawMessage
    val duplicateBlocked = details.groundedProposal?.duplicateStatus == "already_persisted" ||
        details.reviewCase.reasonCodesJson.contains("duplicate_already_persisted")
    val selectedAccountExists = details.accounts.any { it.id == selectedAccountId }
    val canConfirm = working.amountMinorUnits?.let { it > 0 } == true &&
        selections[ReviewField.AMOUNT] != null &&
        working.direction in setOf("debit", "credit") &&
        selectedAccountExists &&
        !duplicateBlocked &&
        !state.loading
    val corrections = automaticReviewCorrections(
        working,
        selections,
        selectedAccountId
    )

    Column(
        Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "${details.source.sender} · " +
                DateFormat.getDateTimeInstance().format(Date(retained.receiptTimestampEpochMs))
        )
        Text("Receipt time is read-only. The complete SMS remains unchanged below.")
        Text(reasonSummary(details))
        Text("Processing reached: ${retained.furthestStage.replace('_', ' ')}")
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
        ) {
            ReviewField.entries.forEach { field ->
                val assigned = when (field) {
                    ReviewField.AMOUNT -> working.amountMinorUnits != null && selections[field] != null
                    ReviewField.DIRECTION -> working.direction != null
                    ReviewField.ACCOUNT -> selectedAccountExists
                    ReviewField.COUNTERPARTY -> working.counterparty != null
                }
                AssistChip(
                    onClick = { active = field },
                    label = { Text(field.label) },
                    modifier = Modifier.semantics {
                        contentDescription = if (assigned) {
                            "${field.label}, assigned"
                        } else {
                            "${field.label}, unassigned"
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
                updateAutomaticSelection(active, span, working)?.let { updated ->
                    working = updated
                    selections[active] = span
                    if (active == ReviewField.ACCOUNT) selectedAccountId = ""
                }
            }
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
                selections[active] = null
                working = working.clear(active)
                if (active == ReviewField.ACCOUNT) selectedAccountId = ""
            }) { Text("Clear ${active.label}") }
            TextButton(
                enabled = retained.slmFields[active.wireName] != null,
                onClick = {
                    retained.slmFields[active.wireName]?.let { evidence ->
                        selections[active] = evidence.sourceSpan
                        working = working.fromRetained(active, evidence.normalizedValueJson)
                        if (active == ReviewField.ACCOUNT) selectedAccountId = ""
                    }
                }
            ) { Text("Reselect model value") }
        }

        Text(
            working.amountMinorUnits?.let {
                "Amount: $it ${working.currency} minor units"
            } ?: "Amount: Unassigned"
        )
        Text("Direction: ${working.direction?.replaceFirstChar(Char::uppercase) ?: "Unassigned"}")
        if (selections[ReviewField.DIRECTION] == null) {
            Text("No selectable direction wording is assigned. Choose explicitly:")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { working = working.copy(direction = "debit") }) {
                    Text("Debit")
                }
                OutlinedButton(onClick = { working = working.copy(direction = "credit") }) {
                    Text("Credit")
                }
            }
        }
        Text("Account wording: ${working.accountReference ?: "Unassigned"}")
        ExposedDropdownMenuBox(
            expanded = accountMenu,
            onExpandedChange = { accountMenu = it }
        ) {
            OutlinedTextField(
                value = details.accounts.firstOrNull { it.id == selectedAccountId }?.name.orEmpty(),
                onValueChange = {},
                readOnly = true,
                label = { Text("Existing account (required)") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(accountMenu) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(
                expanded = accountMenu,
                onDismissRequest = { accountMenu = false }
            ) {
                details.accounts.forEach { account ->
                    DropdownMenuItem(
                        text = { Text("${account.name} · ${account.bank}") },
                        onClick = {
                            selectedAccountId = account.id
                            accountMenu = false
                        }
                    )
                }
            }
        }
        if (details.accounts.isEmpty()) {
            Text("No existing account is available. Create one before confirming this review.")
        }
        Text("Counterparty: ${working.counterparty ?: "Not supplied"}")

        if (retained.analyzerSuggestions.isNotEmpty()) {
            Text("Analyzer suggestions — not model output")
            retained.analyzerSuggestions.forEach { suggestion ->
                Text("${suggestion.kind.replaceFirstChar(Char::uppercase)} suggestion: ${suggestion.summary}")
            }
        }
        if (duplicateBlocked) {
            Text("This source event was already saved. Confirmation is blocked.")
        }
        Button(
            onClick = {
                viewModel.resolveGrounded(SmsReviewAction.CORRECT, corrections)
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

private data class AutomaticReviewWorking(
    val amountMinorUnits: Long?,
    val currency: String,
    val direction: String?,
    val accountReference: String?,
    val counterparty: String?
)

private data class AutomaticReviewSeed(
    val working: AutomaticReviewWorking,
    val selectedAccountId: String,
    val selections: Map<ReviewField, SmsReviewSourceSpan?>
)

private fun automaticReviewSeed(
    details: SmsReviewDetails,
    retained: SmsReviewRetainedEvidence
): AutomaticReviewSeed {
    val proposal = details.groundedProposal
    var working = AutomaticReviewWorking(
        amountMinorUnits = proposal?.amountMinorUnits,
        currency = proposal?.currency ?: retained.primaryCurrency,
        direction = proposal?.direction,
        accountReference = proposal?.accountReference,
        counterparty = proposal?.counterparty
    )
    val selections = mutableMapOf<ReviewField, SmsReviewSourceSpan?>(
        ReviewField.AMOUNT to proposal?.amountSpan,
        ReviewField.DIRECTION to proposal?.directionSpan,
        ReviewField.ACCOUNT to proposal?.accountSpan,
        ReviewField.COUNTERPARTY to proposal?.counterpartySpan
    )
    ReviewField.entries.forEach { field ->
        retained.slmFields[field.wireName]?.let { evidence ->
            selections[field] = evidence.sourceSpan
            if (evidence.validationState == "valid") {
                working = working.fromRetained(field, evidence.normalizedValueJson)
            }
        }
    }
    var selectedAccountId = ""
    details.draftCorrections.forEach { correction ->
        when (correction.field) {
            "amount" -> {
                selections[ReviewField.AMOUNT] = correction.span(details.source.rawMessage)
                working = if (correction.newValueJson == "null") {
                    working.copy(amountMinorUnits = null)
                } else {
                    val value = JSONObject(correction.newValueJson)
                    working.copy(
                        amountMinorUnits = value.optLong("minor_units").takeIf { it > 0 },
                        currency = value.optString("currency", working.currency).uppercase()
                    )
                }
            }
            "direction" -> {
                selections[ReviewField.DIRECTION] = correction.span(details.source.rawMessage)
                working = working.copy(
                    direction = correction.newValueJson.stringLiteral()
                        ?.takeIf { it in setOf("debit", "credit") }
                )
            }
            "account" -> {
                selections[ReviewField.ACCOUNT] = correction.span(details.source.rawMessage)
                working = if (correction.newValueJson == "null") {
                    working.copy(accountReference = null)
                } else {
                    working.copy(
                        accountReference = JSONObject(correction.newValueJson)
                            .optString("reference").takeIf(String::isNotBlank)
                    )
                }
            }
            "counterparty" -> {
                selections[ReviewField.COUNTERPARTY] = correction.span(details.source.rawMessage)
                working = working.copy(counterparty = correction.newValueJson.stringLiteral())
            }
            "account_id" -> selectedAccountId =
                correction.newValueJson.stringLiteral().orEmpty()
        }
    }
    return AutomaticReviewSeed(working, selectedAccountId, selections)
}

private fun updateAutomaticSelection(
    field: ReviewField,
    span: SmsReviewSourceSpan,
    current: AutomaticReviewWorking
): AutomaticReviewWorking? = runCatching {
    when (field) {
        ReviewField.AMOUNT -> current.copy(
            amountMinorUnits = SmsReviewGrounding.minorUnits(span.text, current.currency)
        )
        ReviewField.DIRECTION -> current.copy(
            direction = requireNotNull(SmsReviewGrounding.directionFrom(span.text))
        )
        ReviewField.ACCOUNT -> current.copy(
            accountReference = SmsReviewGrounding.normalizeAccount(span.text)
                .also { require(it.isNotBlank()) }
        )
        ReviewField.COUNTERPARTY -> current.copy(
            counterparty = SmsReviewGrounding.normalizeText(span.text)
                .also { require(it.isNotBlank()) }
        )
    }
}.getOrNull()

private fun AutomaticReviewWorking.clear(field: ReviewField): AutomaticReviewWorking = when (field) {
    ReviewField.AMOUNT -> copy(amountMinorUnits = null)
    ReviewField.DIRECTION -> copy(direction = null)
    ReviewField.ACCOUNT -> copy(accountReference = null)
    ReviewField.COUNTERPARTY -> copy(counterparty = null)
}

private fun AutomaticReviewWorking.fromRetained(
    field: ReviewField,
    normalizedValueJson: String?
): AutomaticReviewWorking = runCatching {
    when (field) {
        ReviewField.AMOUNT -> {
            val value = JSONObject(requireNotNull(normalizedValueJson))
            copy(
                amountMinorUnits = value.getLong("minor_units"),
                currency = value.getString("currency").uppercase()
            )
        }
        ReviewField.DIRECTION -> copy(
            direction = requireNotNull(normalizedValueJson.stringLiteral())
                .also { require(it in setOf("debit", "credit")) }
        )
        ReviewField.ACCOUNT -> copy(
            accountReference = requireNotNull(normalizedValueJson.stringLiteral())
                .takeIf(String::isNotBlank)
        )
        ReviewField.COUNTERPARTY -> copy(
            counterparty = normalizedValueJson.stringLiteral()?.takeIf(String::isNotBlank)
        )
    }
}.getOrElse { clear(field) }

private fun automaticReviewCorrections(
    working: AutomaticReviewWorking,
    selections: Map<ReviewField, SmsReviewSourceSpan?>,
    selectedAccountId: String
): List<SmsFieldCorrection> = buildList {
    fun add(
        field: String,
        span: SmsReviewSourceSpan?,
        value: String,
        classification: SmsFieldGroundingClassification =
            SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS
    ) {
        add(
            SmsFieldCorrection(
                field = field,
                classification = classification,
                previousRevisionId = null,
                candidateId = null,
                evidenceJson = span?.let(::spanJson),
                newValueJson = value
            )
        )
    }
    val amountSpan = selections[ReviewField.AMOUNT]
    add(
        "amount",
        amountSpan,
        if (working.amountMinorUnits == null || amountSpan == null) {
            "null"
        } else {
            JSONObject()
                .put("minor_units", working.amountMinorUnits)
                .put("currency", working.currency)
                .toString()
        }
    )
    val directionSpan = selections[ReviewField.DIRECTION]
    add(
        "direction",
        directionSpan,
        working.direction?.let(JSONObject::quote) ?: "null",
        if (directionSpan == null) {
            SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE
        } else {
            SmsFieldGroundingClassification.SUPPLIED_SOURCE_SUPPORTED_CANDIDATE_MISS
        }
    )
    val accountSpan = selections[ReviewField.ACCOUNT]
    if (accountSpan != null && working.accountReference != null) {
        add(
            "account",
            accountSpan,
            JSONObject().put("reference", working.accountReference).toString()
        )
    }
    add(
        "account_id",
        null,
        selectedAccountId.takeIf(String::isNotBlank)?.let(JSONObject::quote) ?: "null",
        SmsFieldGroundingClassification.SUPPLIED_MANUAL_UNGROUNDED_VALUE
    )
    val counterpartySpan = selections[ReviewField.COUNTERPARTY]
    add(
        "counterparty",
        counterpartySpan,
        if (counterpartySpan != null && working.counterparty != null) {
            JSONObject.quote(working.counterparty)
        } else {
            "null"
        }
    )
}

private val ReviewField.wireName: String
    get() = name.lowercase()

private fun SmsFieldCorrection.span(source: String): SmsReviewSourceSpan? =
    evidenceJson?.let { json ->
        runCatching {
            val value = JSONObject(json)
            SmsReviewGrounding.span(
                source,
                value.getInt("start_scalar"),
                value.getInt("end_scalar")
            ).also { require(it.text == value.getString("text")) }
        }.getOrNull()
    }

private fun String?.stringLiteral(): String? {
    if (this == null || this == "null") return null
    return runCatching { JSONObject("{\"value\":$this}").getString("value") }.getOrNull()
}

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
