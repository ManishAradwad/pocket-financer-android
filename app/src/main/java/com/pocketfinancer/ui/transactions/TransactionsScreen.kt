package com.pocketfinancer.ui.transactions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.rounded.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.hilt.navigation.compose.hiltViewModel
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.model.Account
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.home.SyncSmsItem
import com.pocketfinancer.ui.theme.*
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    onNavigateToTab: (String) -> Unit = {},
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var selectedProcessingSms by remember { mutableStateOf<SyncSmsItem?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(M3_Surface)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // ── Header ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Transactions",
                    color = M3_OnSurface,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = { /* Search functionality - coming soon */ },
                        modifier = Modifier
                            .size(40.dp)
                            .background(M3_SurfaceContainer, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                            tint = M3_OnSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(
                        onClick = { /* Filter list - coming soon */ },
                        modifier = Modifier
                            .size(40.dp)
                            .background(M3_SurfaceContainer, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Filters",
                            tint = M3_OnSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // ── Segmented Control ──
            val segments = listOf("All", "Debits", "Credits")
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                segments.forEachIndexed { index, label ->
                    val selected = state.activeSegment == label
                    SegmentedButton(
                        selected = selected,
                        onClick = { viewModel.updateSegment(label) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = segments.size),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = M3_SecondaryContainer,
                            activeContentColor = M3_OnSecondaryContainer,
                            inactiveContainerColor = M3_SurfaceContainerLowest,
                            inactiveContentColor = M3_OnSurfaceVariant
                        )
                    ) {
                        Text(
                            text = label,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }


            // ── Accounts Filter Chips ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val isAllSelected = state.selectedAccountId == "All"
                FilterChip(
                    selected = isAllSelected,
                    onClick = { viewModel.selectAccount("All") },
                    label = { Text("All Accounts", fontSize = 11.sp, fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = M3_Primary,
                        selectedLabelColor = M3_OnPrimary,
                        containerColor = M3_SurfaceContainerLow,
                        labelColor = M3_OnSurfaceVariant
                    )
                )

                state.accounts.forEach { acc ->
                    val isSelected = state.selectedAccountId == acc.id
                    val shortName = getAccountShortName(acc)
                    FilterChip(
                        selected = isSelected,
                        onClick = { viewModel.selectAccount(acc.id) },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(getAccountColor(acc.name), CircleShape)
                                )
                                Text(shortName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = M3_SurfaceContainerHigh,
                            selectedLabelColor = M3_OnSurface,
                            containerColor = M3_SurfaceContainerLow,
                            labelColor = M3_OnSurfaceVariant
                        ),
                        border = if (isSelected) BorderStroke(1.dp, M3_Primary) else null
                    )
                }
            }

            // ── Month Summary Metric Row ──
            val debitsSum = state.transactions
                .filter { it.type == TransactionType.DEBIT }
                .sumOf { it.amount }
            
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .background(M3_SurfaceContainerLowest, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Current View Outbound",
                    color = M3_OnSurfaceVariant,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = "OUT  ₹${String.format("%,.2f", debitsSum)}",
                    color = M3_OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            // ── Group transactions by date ──
            val groupedTransactions = remember(state.transactions) {
                state.transactions.groupBy { formatDateKey(it.date) }
            }

            val showEmptyState = state.transactions.isEmpty() && state.syncState.status != HomeSyncState.Status.SYNCING

            if (showEmptyState) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No transactions found",
                        color = M3_OnSurfaceVariant,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f)
                ) {
                    // Under Processing SMS Card
                    if (state.syncState.status == HomeSyncState.Status.SYNCING || state.syncState.status == HomeSyncState.Status.DONE) {
                        val isComplete = state.syncState.status == HomeSyncState.Status.DONE
                        val activeIndex = if (isComplete) {
                            (state.syncState.queue.size - 1).coerceAtLeast(0)
                        } else {
                            state.syncState.currentIndex ?: 0
                        }
                        val activeSms = if (activeIndex < state.syncState.queue.size) {
                            state.syncState.queue[activeIndex]
                        } else null

                        if (activeSms != null) {
                            item {
                                ActiveSyncCard(
                                    activeSms = activeSms,
                                    currentStageIndex = if (isComplete) 3 else (state.syncState.currentStageIndex ?: 0),
                                    isComplete = isComplete,
                                    onClick = { selectedProcessingSms = activeSms }
                                )
                            }
                        }
                    }

                    if (state.transactions.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillParentMaxSize()
                                    .padding(bottom = 64.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No transactions found",
                                    color = M3_OnSurfaceVariant,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        groupedTransactions.forEach { (dateKey, txList) ->
                        // Header showing date and total debit/credit for the day
                        val dayDebits = txList.filter { it.type == TransactionType.DEBIT }.sumOf { it.amount }
                        val dayCredits = txList.filter { it.type == TransactionType.CREDIT }.sumOf { it.amount }

                        item {
                            DayHeaderRow(
                                dateText = formatDayHeader(txList.first().date),
                                debitTotal = dayDebits,
                                creditTotal = dayCredits
                            )
                        }

                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 6.dp),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = M3_SurfaceContainerLow
                                ),
                                border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f))
                            ) {
                                Column {
                                    txList.forEachIndexed { index, transaction ->
                                        val itemShape = when {
                                            txList.size == 1 -> RoundedCornerShape(16.dp)
                                            index == 0 -> RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                                            index == txList.size - 1 -> RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
                                            else -> androidx.compose.ui.graphics.RectangleShape
                                        }
                                        TransactionItem(
                                            transaction = transaction,
                                            shape = itemShape,
                                            showDivider = index < txList.size - 1,
                                            onClick = { viewModel.selectTransaction(transaction) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Transaction Details Bottom Sheet ──
        if (state.selectedTransaction != null) {
            val tx = state.selectedTransaction!!

            val gradient = getAvatarGradient(tx.merchant)
            val textColor = getAvatarTextColor(tx.merchant)
            val icon = getMerchantIcon(tx.merchant)

            var isEditing by remember(tx.id) { mutableStateOf(false) }

            var editAmount by remember(tx.id) { mutableStateOf(tx.amount.toString()) }
            var editMerchant by remember(tx.id) { mutableStateOf(tx.merchant) }
            var editAccountName by remember(tx.id) { mutableStateOf(tx.accountLabel ?: "") }
            var editType by remember(tx.id) { mutableStateOf(tx.type) }
            var typeDropdownExpanded by remember { mutableStateOf(false) }

            ModalBottomSheet(
                onDismissRequest = { viewModel.selectTransaction(null) },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = M3_SurfaceContainerLow,
                contentColor = M3_OnSurface,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .width(48.dp)
                            .height(6.dp)
                            .background(M3_OutlineVariant.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                ) {
                    if (!isEditing) {
                        // Centered Receipt Header
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(64.dp)
                                    .background(gradient, RoundedCornerShape(18.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                if (icon != null) {
                                    Icon(
                                        imageVector = icon,
                                        contentDescription = null,
                                        tint = textColor,
                                        modifier = Modifier.size(32.dp)
                                    )
                                } else {
                                    Text(
                                        text = getInitials(tx.merchant),
                                        color = textColor,
                                        fontSize = 22.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = tx.merchant,
                                color = M3_OnSurface,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${formatFullDate(tx.date)} · ${formatTime(tx.date)}",
                                color = M3_OnSurfaceVariant,
                                fontSize = 13.sp
                            )
                            if (tx.isEdited) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier
                                        .background(M3_PrimaryContainer.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = M3_OnPrimaryContainer,
                                        modifier = Modifier.size(12.dp)
                                    )
                                    Text(
                                        text = "Corrected by User",
                                        color = M3_OnPrimaryContainer,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = (if (tx.type == TransactionType.CREDIT) "+" else "−") + "₹${String.format("%,.2f", tx.amount)}",
                                color = if (tx.type == TransactionType.CREDIT) M3_Pos else M3_OnSurface,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }

                        // Centered Badges Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Type Badge
                                val badgeBg = if (tx.type == TransactionType.CREDIT) M3_PosContainer else M3_SecondaryContainer
                                val badgeText = if (tx.type == TransactionType.CREDIT) M3_OnPosContainer else M3_OnSecondaryContainer
                                Row(
                                    modifier = Modifier
                                        .background(badgeBg, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = if (tx.type == TransactionType.CREDIT) Icons.Default.ArrowUpward else Icons.Default.ArrowDownward,
                                        contentDescription = null,
                                        tint = badgeText,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = if (tx.type == TransactionType.CREDIT) "Credit" else "Debit",
                                        color = badgeText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Account Badge
                                Row(
                                    modifier = Modifier
                                        .background(M3_SurfaceContainer, RoundedCornerShape(8.dp))
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.CreditCard,
                                        contentDescription = null,
                                        tint = M3_OnSurfaceVariant,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = tx.accountLabel ?: "Unknown Account",
                                        color = M3_OnSurface,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(16.dp))

                        // SLM OUTPUT Codeblock Section
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Terminal,
                                contentDescription = null,
                                tint = M3_OnSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "SLM OUTPUT",
                                color = M3_OnSurfaceVariant,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3_SurfaceContainerLowest, RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            val slmProps = listOf(
                                "amount" to tx.amount.toString(),
                                "type" to "\"${tx.type.name.lowercase()}\"",
                                "counterparty" to "\"${tx.merchant}\"",
                                "date" to "\"${formatDate(tx.date)}\"",
                                "account" to "\"${tx.accountLabel ?: ""}\""
                            )
                            slmProps.forEach { (key, value) ->
                                Row(
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Text(
                                        text = key,
                                        color = M3_OnSurfaceVariant,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.width(100.dp)
                                    )
                                    Text(
                                        text = ":",
                                        color = M3_OnSurfaceVariant,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        modifier = Modifier.padding(horizontal = 4.dp)
                                    )
                                    Text(
                                        text = value,
                                        color = if (value.startsWith("\"")) Color(0xFF98C379) else Color(0xFF61AFEF),
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = { isEditing = true },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = M3_Primary,
                                contentColor = M3_OnPrimary
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Correct Extraction Details",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    } else {
                        // Edit Mode Title Row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Correct Extracted Info",
                                color = M3_OnSurface,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "EDIT MODE",
                                color = M3_Primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .background(M3_PrimaryContainer.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        // Editable form inputs
                        OutlinedTextField(
                            value = editMerchant,
                            onValueChange = { editMerchant = it },
                            label = { Text("Merchant / Counterparty") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = M3_Primary,
                                unfocusedBorderColor = M3_OutlineVariant,
                                focusedLabelColor = M3_Primary,
                                unfocusedLabelColor = M3_OnSurfaceVariant,
                                focusedTextColor = M3_OnSurface,
                                unfocusedTextColor = M3_OnSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = editAmount,
                            onValueChange = { editAmount = it },
                            label = { Text("Amount (₹)") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = M3_Primary,
                                unfocusedBorderColor = M3_OutlineVariant,
                                focusedLabelColor = M3_Primary,
                                unfocusedLabelColor = M3_OnSurfaceVariant,
                                focusedTextColor = M3_OnSurface,
                                unfocusedTextColor = M3_OnSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedTextField(
                            value = editAccountName,
                            onValueChange = { editAccountName = it },
                            label = { Text("Account / Card Name") },
                            singleLine = true,
                            placeholder = { Text("e.g. A/c XX1234") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = M3_Primary,
                                unfocusedBorderColor = M3_OutlineVariant,
                                focusedLabelColor = M3_Primary,
                                unfocusedLabelColor = M3_OnSurfaceVariant,
                                focusedTextColor = M3_OnSurface,
                                unfocusedTextColor = M3_OnSurface
                            )
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = if (editType == TransactionType.CREDIT) "Credit" else "Debit",
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("Transaction Type") },
                                trailingIcon = {
                                    IconButton(onClick = { typeDropdownExpanded = !typeDropdownExpanded }) {
                                        Icon(
                                            imageVector = Icons.Default.ArrowDropDown,
                                            contentDescription = null,
                                            tint = M3_OnSurfaceVariant
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().clickable { typeDropdownExpanded = !typeDropdownExpanded },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = M3_Primary,
                                    unfocusedBorderColor = M3_OutlineVariant,
                                    focusedLabelColor = M3_Primary,
                                    unfocusedLabelColor = M3_OnSurfaceVariant,
                                    focusedTextColor = M3_OnSurface,
                                    unfocusedTextColor = M3_OnSurface
                                )
                            )
                            DropdownMenu(
                                expanded = typeDropdownExpanded,
                                onDismissRequest = { typeDropdownExpanded = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.85f)
                                    .background(M3_SurfaceContainerHigh)
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Debit", color = M3_OnSurface) },
                                    onClick = {
                                        editType = TransactionType.DEBIT
                                        typeDropdownExpanded = false
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Credit", color = M3_OnSurface) },
                                    onClick = {
                                        editType = TransactionType.CREDIT
                                        typeDropdownExpanded = false
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            OutlinedButton(
                                onClick = { isEditing = false },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = M3_OnSurfaceVariant
                                ),
                                border = BorderStroke(1.dp, M3_OutlineVariant)
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    val parsedAmount = editAmount.toDoubleOrNull() ?: 0.0
                                    viewModel.updateTransaction(
                                        id = tx.id,
                                        amount = parsedAmount,
                                        merchant = editMerchant,
                                        type = editType,
                                        accountName = editAccountName
                                    )
                                    isEditing = false
                                },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = M3_Primary,
                                    contentColor = M3_OnPrimary
                                )
                            ) {
                                Text("Save Changes")
                            }
                        }
                        Spacer(modifier = Modifier.height(20.dp))
                        HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.3f))
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // SOURCE SMS Section
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Sms,
                            contentDescription = null,
                            tint = M3_OnSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = "SOURCE SMS",
                            color = M3_OnSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(M3_SurfaceContainerLowest, RoundedCornerShape(12.dp))
                            .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(12.dp))
                            .clip(RoundedCornerShape(12.dp))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3_SurfaceContainerLow)
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "from  ",
                                color = M3_OnSurfaceVariant,
                                fontSize = 10.sp
                            )
                            Text(
                                text = tx.sender,
                                color = M3_OnPrimaryContainer,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .background(M3_PrimaryContainer, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = tx.rawMessage,
                            color = M3_OnSurfaceVariant,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = 18.sp,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
            }
        }

        // ── Telemetry Details Bottom Sheet ──
        if (selectedProcessingSms != null) {
            val sms = selectedProcessingSms!!
            
            // Extract attention weights
            val amountMatch = Regex("(?i)(?:inr|rs\\.?|₹)\\s*([\\d,]+\\.\\d{2})").find(sms.body)?.value ?: "..."
            val merchantMatch = Regex("(?i)at\\s+([^\\s]+)|to\\s+([^\\s]+)|by\\s+([^\\s]+)").find(sms.body)?.groupValues?.get(1)?.takeIf { it.isNotBlank() } ?: "..."
            val suffixMatch = Regex("(?i)(?:card|a/c)\\s*(?:xx)?(\\d{4})").find(sms.body)?.groupValues?.get(1) ?: "..."
            
            val isSyncDone = state.syncState.status == HomeSyncState.Status.DONE
            val activeStageIndex = if (isSyncDone) 3 else (state.syncState.currentStageIndex ?: 0)
            val isStagePassedExtract = isSyncDone || activeStageIndex >= 2

            ModalBottomSheet(
                onDismissRequest = { selectedProcessingSms = null },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = M3_SurfaceContainerLow,
                contentColor = M3_OnSurface,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .width(48.dp)
                            .height(6.dp)
                            .background(M3_OutlineVariant.copy(alpha = 0.6f), RoundedCornerShape(3.dp))
                    )
                }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 32.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Title
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Memory,
                                contentDescription = null,
                                tint = Color(0xFFF2C94C),
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "LIVE SLM RUNTIME ENGINE",
                                color = M3_OnSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Text(
                            text = "Close Logs",
                            color = M3_OnSurfaceVariant,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .background(M3_SurfaceContainerHigh, RoundedCornerShape(100))
                                .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(100))
                                .clickable { selectedProcessingSms = null }
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    // Hardware Context
                    Card(
                        colors = CardDefaults.cardColors(containerColor = M3_Surface),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.15f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.DeveloperMode,
                                    contentDescription = null,
                                    tint = M3_Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column {
                                    Text(
                                        text = "Local Device CPU Runtime",
                                        color = M3_OnSurface,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Qwen-1.7B-Chat-Int4.gguf",
                                        color = M3_OnSurfaceVariant,
                                        fontSize = 9.sp,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    text = "28 ms/tok",
                                    color = M3_Primary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "36.7 tok/sec",
                                    color = M3_OnSurfaceVariant,
                                    fontSize = 9.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }

                    // Original input
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "ORIGINAL RAW INPUT STRING",
                            color = M3_OnSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "\"${sms.body}\"",
                            color = M3_OnSurfaceVariant,
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3_Surface, RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        )
                    }

                    // Compiler progress stages
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "COMPILER PROGRESS PIPELINE",
                            color = M3_OnSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(M3_Surface, RoundedCornerShape(12.dp))
                                .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)), RoundedCornerShape(12.dp))
                                .padding(12.dp)
                        ) {
                            com.pocketfinancer.ui.home.PipelineStagesView(activeIndex = activeStageIndex)
                        }
                    }

                    // Token weights and attention
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "STRUCTURED KEY ATTENTION VALUES",
                            color = M3_OnSurfaceVariant,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        
                        val weights = listOf(
                            Triple("Amount/Value", amountMatch, 0.99f),
                            Triple("Merchant/Counterparty", merchantMatch, 0.98f),
                            Triple("Issuer Ledger Suffix", if (suffixMatch != "...") "XX$suffixMatch" else "...", 0.95f)
                        )

                        weights.forEach { (field, token, weight) ->
                            Card(
                                colors = CardDefaults.cardColors(containerColor = M3_Surface),
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.15f))
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = field.uppercase(),
                                            color = M3_Primary,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = if (isStagePassedExtract) token else "...",
                                            color = M3_OnSurface,
                                            fontSize = 12.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier
                                                .background(M3_SurfaceContainer, RoundedCornerShape(6.dp))
                                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = if (isStagePassedExtract) "conf: ${"%.1f".format(weight * 100)}%" else "evaluating",
                                            color = if (isStagePassedExtract) M3_Pos else M3_OnSurfaceVariant.copy(alpha = 0.3f),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            fontWeight = FontWeight.Bold
                                        )
                                        Spacer(modifier = Modifier.height(6.dp))
                                        val fillWidth = if (isStagePassedExtract) weight else 0f
                                        Box(
                                            modifier = Modifier
                                                .width(64.dp)
                                                .height(4.dp)
                                                .background(M3_SurfaceContainer, RoundedCornerShape(100))
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxHeight()
                                                    .fillMaxWidth(fillWidth)
                                                    .background(M3_Pos, RoundedCornerShape(100))
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Zero data warning
                    Card(
                        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerHigh),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = null,
                                tint = M3_Pos,
                                modifier = Modifier.size(18.dp)
                            )
                            Column {
                                Text(
                                    text = "Zero Data Left Your Screen",
                                    color = M3_OnSurface,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Parameters run natively using llama.cpp within WebAssembly boundaries. Internet permission was not requested nor required.",
                                    color = M3_OnSurfaceVariant,
                                    fontSize = 9.sp,
                                    lineHeight = 13.sp
                                )
                            }
                        }
                    }

                    if (isSyncDone) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                viewModel.resetSyncState()
                                selectedProcessingSms = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = M3_Pos,
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text("Done", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
}


@Composable
fun DayHeaderRow(
    dateText: String,
    debitTotal: Double,
    creditTotal: Double
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(M3_Surface.copy(alpha = 0.95f))
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = dateText.uppercase(),
            color = M3_OnSurfaceVariant,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (debitTotal > 0.0) {
                Text(
                    text = "↓ ₹${String.format("%,.0f", debitTotal)}",
                    color = M3_OnErrorContainer,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(M3_ErrorContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            if (creditTotal > 0.0) {
                Text(
                    text = "↑ ₹${String.format("%,.0f", creditTotal)}",
                    color = M3_OnPosContainer,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(M3_PosContainer, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun TransactionItem(
    transaction: Transaction,
    shape: androidx.compose.ui.graphics.Shape,
    showDivider: Boolean,
    onClick: () -> Unit
) {
    val gradient = getAvatarGradient(transaction.merchant)
    val textColor = getAvatarTextColor(transaction.merchant)
    val icon = getMerchantIcon(transaction.merchant)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(gradient, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (icon != null) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            tint = textColor,
                            modifier = Modifier.size(20.dp)
                        )
                    } else {
                        Text(
                            text = getInitials(transaction.merchant),
                            color = textColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = transaction.merchant,
                        color = M3_OnSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = transaction.accountLabel ?: "Unknown Card",
                            color = M3_OnSurfaceVariant,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .background(M3_SurfaceContainerHigh, RoundedCornerShape(4.dp))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                        if (transaction.isEdited) {
                            Text(
                                text = "Corrected",
                                color = M3_OnPrimaryContainer,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .background(M3_PrimaryContainer, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }
            }
            Column(
                horizontalAlignment = Alignment.End,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = (if (transaction.type == TransactionType.CREDIT) "+" else "−") + "₹${String.format("%,.2f", transaction.amount)}",
                    color = if (transaction.type == TransactionType.CREDIT) M3_Pos else M3_OnSurface,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = formatTime(transaction.date),
                    color = M3_OnSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }
        if (showDivider) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = M3_OutlineVariant.copy(alpha = 0.2f)
            )
        }
    }
}

// ── Helpers ──

private fun formatDateKey(timestampMs: Long): String {
    val date = Date(timestampMs)
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(date)
}

private fun formatDayHeader(timestampMs: Long): String {
    val date = Date(timestampMs)
    val now = Date()
    val fmtDate = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
    val headerFmt = SimpleDateFormat("EEE, d MMM", Locale.getDefault())

    val isToday = fmtDate.format(date) == fmtDate.format(now)
    val formatted = headerFmt.format(date)
    return if (isToday) "Today · $formatted" else formatted
}

private fun formatTime(timestampMs: Long): String {
    val date = Date(timestampMs)
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(date)
}

private fun formatDate(timestampMs: Long): String {
    val date = Date(timestampMs)
    val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    return fmt.format(date)
}

private fun formatFullDate(timestampMs: Long): String {
    val date = Date(timestampMs)
    val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
    return fmt.format(date)
}

private fun getInitials(name: String): String {
    if (name.isEmpty()) return "?"
    if (name.contains("@")) return name[0].uppercase()
    val parts = name.trim().split("\\s+".toRegex())
    return if (parts.size > 1) {
        (parts[0][0].toString() + parts[1][0].toString()).uppercase()
    } else {
        name.take(2).uppercase()
    }
}

private fun getAvatarGradient(name: String): Brush {
    val hash = name.hashCode()
    val index = Math.abs(hash) % 5
    return when (index) {
        0 -> Brush.linearGradient(listOf(Color(0xFF452B0E), Color(0xFF2C1B08)))
        1 -> Brush.linearGradient(listOf(Color(0xFF12472B), Color(0xFF0A2919)))
        2 -> Brush.linearGradient(listOf(Color(0xFF0E3E75), Color(0xFF072445)))
        3 -> Brush.linearGradient(listOf(Color(0xFF421863), Color(0xFF260D3A)))
        else -> Brush.linearGradient(listOf(Color(0xFF611221), Color(0xFF380812)))
    }
}

private fun getAvatarTextColor(name: String): Color {
    val hash = name.hashCode()
    val index = Math.abs(hash) % 5
    return when (index) {
        0 -> Color(0xFFFFDCC1)
        1 -> Color(0xFFC7F3C8)
        2 -> Color(0xFFD3E4FF)
        3 -> Color(0xFFF2DAFF)
        else -> Color(0xFFFFDAD9)
    }
}

@Composable
private fun getMerchantIcon(name: String): ImageVector? {
    val lower = name.lowercase()
    return when {
        lower.contains("zomato") || lower.contains("swiggy") || lower.contains("food") || lower.contains("restaurant") || lower.contains("dine") || lower.contains("cafe") -> Icons.Rounded.Restaurant
        lower.contains("uber") || lower.contains("ola") || lower.contains("cab") || lower.contains("ride") || lower.contains("auto") || lower.contains("transport") -> Icons.Rounded.DirectionsCar
        lower.contains("amazon") || lower.contains("flipkart") || lower.contains("tatacliq") || lower.contains("myntra") || lower.contains("mall") || lower.contains("shop") || lower.contains("store") || lower.contains("grocer") -> Icons.Rounded.ShoppingBag
        lower.contains("netflix") || lower.contains("spotify") || lower.contains("hotstar") || lower.contains("prime") || lower.contains("music") || lower.contains("youtube") || lower.contains("media") || lower.contains("movie") || lower.contains("show") -> Icons.Rounded.PlayArrow
        lower.contains("gym") || lower.contains("fit") || lower.contains("sport") || lower.contains("workout") -> Icons.Rounded.FitnessCenter
        lower.contains("flight") || lower.contains("travel") || lower.contains("hotel") || lower.contains("trip") || lower.contains("makemytrip") || lower.contains("irctc") || lower.contains("rail") -> Icons.Rounded.Flight
        lower.contains("hospital") || lower.contains("pharmacy") || lower.contains("med") || lower.contains("health") || lower.contains("clinic") || lower.contains("doctor") -> Icons.Rounded.MedicalServices
        lower.contains("electric") || lower.contains("water") || lower.contains("gas") || lower.contains("bill") || lower.contains("recharge") || lower.contains("telecom") || lower.contains("jio") || lower.contains("airtel") || lower.contains("vi ") -> Icons.Rounded.Receipt
        lower.contains("bank") || lower.contains("paytm") || lower.contains("gpay") || lower.contains("phonepe") || lower.contains("upi") || lower.contains("hdfc") || lower.contains("sbi") || lower.contains("icici") || lower.contains("axis") || lower.contains("transfer") -> Icons.Rounded.AccountBalance
        lower.contains("card") || lower.contains("visa") || lower.contains("mastercard") || lower.contains("amex") || lower.contains("rupay") || lower.contains("credit") -> Icons.Rounded.CreditCard
        else -> null
    }
}

@Composable
fun ActiveSyncCard(
    activeSms: SyncSmsItem,
    currentStageIndex: Int,
    isComplete: Boolean,
    onClick: () -> Unit
) {
    val cardColor = if (isComplete) M3_PosContainer.copy(alpha = 0.2f) else M3_SurfaceContainerLow
    val borderColor = if (isComplete) M3_Pos.copy(alpha = 0.25f) else Color(0xFFF2C94C).copy(alpha = 0.3f)
    val indicatorColor = if (isComplete) M3_Pos else Color(0xFFF2C94C)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = cardColor),
        border = BorderStroke(1.dp, borderColor)
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .background(indicatorColor.copy(alpha = 0.1f), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isComplete) {
                            Icon(
                                imageVector = Icons.Rounded.CheckCircle,
                                contentDescription = null,
                                tint = M3_Pos,
                                modifier = Modifier.size(16.dp)
                            )
                        } else {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color(0xFFF2C94C),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                    Column {
                        Text(
                            text = if (isComplete) "LOCAL PIPELINE COMPLETED" else "LOCAL PIPELINE ENGINE RUNNING",
                            color = indicatorColor,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = if (isComplete) "Finished sync" else "Pending from ${activeSms.sender}",
                            color = M3_OnSurface,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Text(
                    text = if (isComplete) "COMPLETE" else "PROCESSING",
                    color = indicatorColor,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .background(indicatorColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = activeSms.body,
                color = M3_OnSurfaceVariant,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(10.dp))
            HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.15f))
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val stageName = if (isComplete) "Database Persistence" else when (currentStageIndex) {
                    0 -> "Pre-Filter Check"
                    1 -> "Phase 1: Thinking Pass"
                    2 -> "Phase 2: Grammar Constraint"
                    else -> "Database Persistence"
                }
                Text(
                    text = "SLM Stage: $stageName",
                    color = M3_OnSurfaceVariant,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "View extraction logs",
                    color = M3_Primary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

private fun getAccountShortName(acc: Account): String {
    val bank = acc.bank.replace(" Bank", "")
    val name = acc.name
    val runs = Regex("\\d+").findAll(name).toList()
    val digits = if (runs.isNotEmpty()) runs.last().value.takeLast(4) else ""
    return if (digits.isNotEmpty()) "$bank $digits" else name
}

private fun getAccountColor(name: String): Color {
    val hash = name.hashCode()
    return when (Math.abs(hash) % 5) {
        0 -> Color(0xFFF2C94C)
        1 -> Color(0xFF27AE60)
        2 -> Color(0xFF2F80ED)
        3 -> Color(0xFF9B51E0)
        else -> Color(0xFFEB5757)
    }
}
