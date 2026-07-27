package com.pocketfinancer.ui.home

import android.Manifest
import android.os.Build
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.ui.theme.*
import com.pocketfinancer.ui.transactions.TelemetryLogsViewer
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToTab: (String) -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val context = LocalContext.current

    val pData = state.periodData[selectedPeriod] ?: PeriodData()
    var showDrawer by remember { mutableStateOf(false) }
    var selectedTelemetrySmsId by remember { mutableStateOf<String?>(null) }
    var showModelDownloadConfirmation by remember { mutableStateOf(false) }
    var showHowThisWorks by remember { mutableStateOf(false) }
    var pendingBackgroundAction by remember {
        mutableStateOf<SetupCardAction?>(null)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionHealth()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val runSetupAction: (SetupCardAction) -> Unit = { action ->
        when (action.target()) {
            SetupCardActionTarget.SCAN_OLDER ->
                viewModel.scanOlderMessages()
            SetupCardActionTarget.RETRY_RECENT_SYNC ->
                viewModel.checkForUnsynced()
            SetupCardActionTarget.START_SETUP ->
                viewModel.startSetupOrResume()
            SetupCardActionTarget.RESTORE_PERMISSION -> Unit
        }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {
        pendingBackgroundAction?.let(runSetupAction)
        pendingBackgroundAction = null
    }
    val requestNotificationThenRun: (SetupCardAction) -> Unit = { action ->
        val needsRuntimePermission =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
        if (needsRuntimePermission) {
            pendingBackgroundAction = action
            notificationPermissionLauncher.launch(
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            runSetupAction(action)
        }
    }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val readGranted =
            result[Manifest.permission.READ_SMS] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_SMS
                ) == PackageManager.PERMISSION_GRANTED
        val receiveGranted =
            result[Manifest.permission.RECEIVE_SMS] == true ||
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECEIVE_SMS
                ) == PackageManager.PERMISSION_GRANTED
        viewModel.onSmsPermissionResult(readGranted && receiveGranted)
    }

    val onSetupAction: (SetupCardAction) -> Unit = { action ->
        when (action) {
            SetupCardAction.RESTORE_PERMISSION -> {
                smsPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.READ_SMS,
                        Manifest.permission.RECEIVE_SMS
                    )
                )
            }
            SetupCardAction.PREPARE_MODEL -> {
                if (state.setupImportState.modelDownloadConfirmed) {
                    requestNotificationThenRun(action)
                } else {
                    showModelDownloadConfirmation = true
                }
            }
            SetupCardAction.RETRY_RECENT_SYNC ->
                runSetupAction(action)
            else -> requestNotificationThenRun(action)
        }
    }

    val dateEyebrow = when (selectedPeriod) {
        "Day" -> {
            val date = SimpleDateFormat("EEE, d MMM", Locale.getDefault()).format(Date())
            "Today · $date"
        }
        "Week" -> {
            val today = Calendar.getInstance()
            val start = today.clone() as Calendar
            val currentDay = start.get(Calendar.DAY_OF_WEEK)
            val diff = if (currentDay == Calendar.SUNDAY) -6 else Calendar.MONDAY - currentDay
            start.add(Calendar.DAY_OF_YEAR, diff)
            val end = start.clone() as Calendar
            end.add(Calendar.DAY_OF_YEAR, 6)
            val fmt = SimpleDateFormat("MMM d", Locale.getDefault())
            "This week · ${fmt.format(start.time)} – ${fmt.format(end.time)}"
        }
        else -> {
            val date = SimpleDateFormat("MMMM yyyy", Locale.getDefault()).format(Date())
            "This month · $date"
        }
    }

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
                    .heightIn(min = 64.dp)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(M3_Primary.copy(alpha = 0.3f), M3_Primary)
                                )
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "₹",
                            color = M3_OnPrimary,
                            style = AppTypography.sectionHeadingBold
                        )
                    }
                    Text(
                        text = "pocketFinancer",
                        color = M3_OnSurface,
                        style = MaterialTheme.typography.titleLarge
                    )
                }

                IconButton(
                    onClick = { onNavigateToTab("settings") },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = M3_OnSurfaceVariant
                    )
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    SetupImportCard(
                        state = state.setupImportState,
                        onAction = onSetupAction,
                        onHowThisWorks = { showHowThisWorks = true }
                    )
                }

                // ── Hero Card ──
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
                        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(
                                            if (pData.amount > 0.0) {
                                                M3_Error
                                            } else {
                                                M3_OnSurfaceVariant.copy(alpha = 0.55f)
                                            },
                                            CircleShape
                                        )
                                )
                                Text(
                                    text = "$dateEyebrow • Spends",
                                    color = M3_OnSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "₹",
                                    color = M3_OnSurfaceVariant.copy(alpha = 0.6f),
                                    style = AppTypography.amountMedium.copy(
                                        fontWeight = FontWeight.Light
                                    )
                                )
                                Text(
                                    text = String.format("%,.2f", pData.amount),
                                    color = M3_OnSurface,
                                    style = AppTypography.amountHero
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${pData.txnCount} transactions",
                                    color = M3_OnSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium)
                                )
                                val isLess = pData.deltaDir == "less"
                                val isSame = pData.deltaDir == "same"
                                val containerColor = when {
                                    isSame -> M3_SurfaceContainerHigh
                                    isLess -> M3_PosContainer
                                    else -> M3_ErrorContainer
                                }
                                val textColor = when {
                                    isSame -> M3_OnSurfaceVariant
                                    isLess -> M3_OnPosContainer
                                    else -> M3_OnErrorContainer
                                }
                                val icon = if (isLess) {
                                    Icons.Rounded.ArrowDownward
                                } else {
                                    Icons.Rounded.ArrowUpward
                                }

                                Row(
                                    modifier = Modifier
                                        .background(containerColor, RoundedCornerShape(100))
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (!isSame) {
                                        Icon(
                                            imageVector = icon,
                                            contentDescription = null,
                                            tint = textColor,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Text(
                                        text = pData.deltaLabel,
                                        color = textColor,
                                        style = AppTypography.bodySmallBold
                                    )
                                }
                            }
                        }
                    }
                }

                // ── Model Upgrade Banner ──
                val upgradeRec = state.upgradeRecommendation
                if (
                    state.setupImportState.modelPrepared &&
                    upgradeRec.isUpgradeAvailable &&
                    !upgradeRec.isDismissed
                ) {
                    item {
                        ModelUpgradeBanner(
                            recommendation = upgradeRec,
                            onUpgrade = { viewModel.startModelUpgrade() },
                            onCancel = { viewModel.cancelModelUpgrade() },
                            onDismiss = { viewModel.dismissUpgradeBanner() }
                        )
                    }
                }

                // ── Sync Banner ──
                if (
                    state.setupImportState.modelPrepared &&
                    manualRecentSyncAvailable(
                        state.setupImportState.status
                    )
                ) {
                    item {
                        SyncStrip(
                            syncState = state.syncState,
                            onStartSync = { viewModel.startSync() },
                            onInspectSync = { showDrawer = true },
                            onCheckForUnsynced = {
                                viewModel.checkForUnsynced()
                            }
                        )
                    }
                }

                // ── Period Switcher ──
                item {
                    val periods = listOf("Day", "Week", "Month")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(M3_SurfaceContainerLow)
                            .padding(4.dp),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        periods.forEach { period ->
                            val isSelected = selectedPeriod == period
                            val periodInfo = state.periodData[period] ?: PeriodData()
                            val btnBg = if (isSelected) M3_SecondaryContainer else Color.Transparent
                            val btnText = if (isSelected) M3_OnSecondaryContainer else M3_OnSurfaceVariant
                            val valText = if (isSelected) M3_OnSecondaryContainer.copy(alpha = 0.8f) else M3_OnSurfaceVariant.copy(alpha = 0.6f)

                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(btnBg)
                                    .clickable { viewModel.selectPeriod(period) }
                                    .padding(vertical = 8.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = period,
                                    color = btnText,
                                    style = MaterialTheme.typography.labelLarge.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "₹${String.format("%,.0f", periodInfo.amount)}",
                                    color = valText,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }

                // ── Recent synced transactions card ──
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
                        border = BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f))
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recent synced transactions",
                                    color = M3_OnSurface,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100))
                                        .clickable { onNavigateToTab("transactions") }
                                        .padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                                ) {
                                    Text(
                                        text = "All",
                                        color = M3_Primary,
                                        style = MaterialTheme.typography.labelLarge
                                    )
                                    Icon(
                                        imageVector = Icons.Default.ChevronRight,
                                        contentDescription = null,
                                        tint = M3_Primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                            HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.2f))

                            if (pData.recent.isEmpty()) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = selectedPeriodEmptyMessage(
                                            selectedPeriod = selectedPeriod,
                                            totalTransactionCount =
                                                state.totalTransactionCount,
                                            setupStatus =
                                                state.setupImportState.status
                                        ),
                                        color = M3_OnSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                pData.recent.forEachIndexed { idx, tx ->
                                    val gradient = getAvatarGradient(tx.merchant)
                                    val textColor = getAvatarTextColor(tx.merchant)
                                    val icon = getMerchantIcon(tx.merchant)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { onNavigateToTab("transactions") }
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
                                                        text = getInitials(tx.merchant),
                                                        color = textColor,
                                                        style = MaterialTheme.typography.titleMedium
                                                    )
                                                }
                                            }
                                            Column {
                                                Text(
                                                    text = tx.merchant,
                                                    color = M3_OnSurface,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.CreditCard,
                                                        contentDescription = null,
                                                        tint = M3_OnSurfaceVariant.copy(alpha = 0.6f),
                                                        modifier = Modifier.size(12.dp)
                                                    )
                                                    Text(
                                                        text = getAccountShortLabel(tx.accountLabel),
                                                        color = M3_OnSurfaceVariant,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }
                                        }
                                        Column(
                                            horizontalAlignment = Alignment.End,
                                            modifier = Modifier.padding(start = 8.dp)
                                        ) {
                                            Text(
                                                text = (if (tx.type == TransactionType.CREDIT) "+" else "−") + "₹${String.format("%,.2f", tx.amount)}",
                                                color = if (tx.type == TransactionType.CREDIT) M3_Pos else M3_OnSurface,
                                                style = AppTypography.amountCompact
                                            )
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = formatTime(tx.date),
                                                color = M3_OnSurfaceVariant,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                    if (idx < pData.recent.size - 1) {
                                        HorizontalDivider(
                                            modifier = Modifier.padding(horizontal = 16.dp),
                                            color = M3_OutlineVariant.copy(alpha = 0.2f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ── Drawer Overlay ──
        if (showDrawer) {
            ModalBottomSheet(
                onDismissRequest = { showDrawer = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = M3_SurfaceContainer,
                contentColor = M3_OnSurface,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .width(48.dp)
                            .height(4.dp)
                            .background(M3_OutlineVariant.copy(alpha = 0.4f), RoundedCornerShape(2.dp))
                    )
                }
            ) {
                DrawerContent(
                    syncState = state.syncState,
                    onNavigate = { route ->
                        showDrawer = false
                        onNavigateToTab(route)
                    },
                    onDismiss = { showDrawer = false },
                    onResetSync = { viewModel.resetSyncState() },
                    onItemClick = { item ->
                        selectedTelemetrySmsId = item.id
                    }
                )
            }
        }

        // ── Telemetry Logs Sheet ──
        // Keep only an opaque row id in Compose state and resolve the row from
        // the latest queue snapshot. The manager replaces terminal rows with
        // privacy-safe copies after processing.
        val telemetrySms = selectedTelemetrySmsId?.let { selectedId ->
            state.syncState.queue.firstOrNull { it.id == selectedId }
        }
        if (telemetrySms != null) {
            val currentIndex = state.syncState.currentIndex
            val isActive = state.syncState.status == HomeSyncState.Status.SYNCING &&
                    currentIndex != null &&
                    currentIndex < state.syncState.queue.size &&
                    state.syncState.queue[currentIndex].id == telemetrySms.id

            val activeStageIndex = if (isActive) {
                state.syncState.currentStageIndex ?: 0
            } else if (
                telemetrySms.status == "synced" ||
                telemetrySms.status == "already_saved" ||
                telemetrySms.status == "filtered_out"
            ) {
                4
            } else {
                0
            }

            val finalThinkingOutput = if (isActive) {
                state.syncState.thinkingOutput
            } else {
                ""
            }

            val finalJsonOutput = if (isActive) {
                state.syncState.jsonOutput
            } else if (telemetrySms.status == "synced") {
                "Raw JSON output was not retained after sync."
            } else if (telemetrySms.status == "already_saved") {
                "Raw JSON output was not retained for an existing transaction."
            } else if (telemetrySms.status == "filtered_out") {
                "No transaction JSON was retained for this message."
            } else if (telemetrySms.status == "error") {
                "Output is unavailable because processing did not finish."
            } else {
                ""
            }

            val finalParsedOutput = if (isActive) {
                if (state.syncState.jsonOutput.isNotEmpty()) {
                    val parsed = viewModel.getParsedOutput(state.syncState.jsonOutput)
                    if (parsed == "Parsed: null (non-financial)" && activeStageIndex < 3) {
                        "Waiting for complete JSON..."
                    } else {
                        parsed
                    }
                } else {
                    ""
                }
            } else if (telemetrySms.status == "synced") {
                "Saved transaction: amount=${telemetrySms.parsedAmount ?: "-"}, counterparty=${telemetrySms.parsedMerchant ?: "-"}"
            } else if (telemetrySms.status == "already_saved") {
                "The encrypted ledger already owns this source evidence."
            } else if (telemetrySms.status == "filtered_out") {
                "No transaction was saved for this message."
            } else if (telemetrySms.status == "error") {
                "The message could not be saved. The failed stage was not retained."
            } else {
                ""
            }

            ModalBottomSheet(
                onDismissRequest = { selectedTelemetrySmsId = null },
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
                val hasThinking = state.syncState.hasThinkingMode
                val performanceText = if (isActive) {
                    state.syncState.activeSmsPerformance
                } else {
                    null
                }

                TelemetryLogsViewer(
                    sender = telemetrySms.sender,
                    body = telemetrySms.body,
                    status = telemetrySms.status,
                    hasThinkingMode = hasThinking,
                    isActive = isActive,
                    activeStageIndex = activeStageIndex,
                    thinkingOutput = finalThinkingOutput,
                    jsonOutput = finalJsonOutput,
                    filterLogs = viewModel.getFilterLogs(telemetrySms),
                    kvLogs = viewModel.getKvCacheLogs(telemetrySms),
                    slmPrompt = viewModel.getSlmPrompt(telemetrySms),
                    parsedOutput = finalParsedOutput,
                    performanceText = performanceText,
                    activeModelName = state.syncState.activeModelName,
                    onClose = { selectedTelemetrySmsId = null }
                )
            }
        }
    }

    if (showModelDownloadConfirmation) {
        AlertDialog(
            onDismissRequest = { showModelDownloadConfirmation = false },
            title = { Text("Prepare the on-device AI model?") },
            text = {
                Text(
                    "This explicitly starts an approximately 700 MB download. " +
                        "The model stays on this device. Historical SMS scanning " +
                        "begins after the model is ready and can resume if interrupted."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.confirmModelDownload()
                        showModelDownloadConfirmation = false
                        requestNotificationThenRun(
                            SetupCardAction.PREPARE_MODEL
                        )
                    }
                ) {
                    Text("Download and continue")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showModelDownloadConfirmation = false }
                ) {
                    Text("Not now")
                }
            }
        )
    }

    if (showHowThisWorks) {
        AlertDialog(
            onDismissRequest = { showHowThisWorks = false },
            title = { Text("How this works") },
            text = {
                Text(
                    "Pocket Financer first checks 7 days of SMS locally. If no " +
                        "eligible alert is found, it widens to 30 and then 90 days. " +
                        "Only eligible candidates reach the on-device model. A saved " +
                        "transaction keeps its encrypted source SMS and sender; " +
                        "rejected messages are removed after processing."
                )
            },
            confirmButton = {
                TextButton(onClick = { showHowThisWorks = false }) {
                    Text("Got it")
                }
            }
        )
    }
}

@Composable
private fun SetupImportCard(
    state: com.pocketfinancer.setup.SetupImportState,
    onAction: (SetupCardAction) -> Unit,
    onHowThisWorks: () -> Unit
) {
    val model = setupImportCardModel(state)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (state.status) {
                SetupImportStatus.PERMISSION_NEEDED,
                SetupImportStatus.FAILED -> M3_ErrorContainer.copy(alpha = 0.42f)
                SetupImportStatus.READY,
                SetupImportStatus.READY_NO_HISTORY ->
                    M3_PosContainer.copy(alpha = 0.42f)
                else -> M3_SurfaceContainerLow
            }
        ),
        border = BorderStroke(
            1.dp,
            M3_OutlineVariant.copy(alpha = 0.35f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = model.eyebrow,
                    color = M3_OnSurfaceVariant,
                    style = AppTypography.eyebrow
                )
                Icon(
                    imageVector = when (state.status) {
                        SetupImportStatus.PERMISSION_NEEDED ->
                            Icons.Rounded.Lock
                        SetupImportStatus.DOWNLOADING ->
                            Icons.Rounded.Download
                        SetupImportStatus.SCANNING ->
                            Icons.Rounded.Search
                        SetupImportStatus.PROCESSING ->
                            Icons.Rounded.Memory
                        SetupImportStatus.READY,
                        SetupImportStatus.READY_NO_HISTORY ->
                            Icons.Rounded.Verified
                        SetupImportStatus.FAILED ->
                            Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.Shield
                    },
                    contentDescription = null,
                    tint = M3_Primary,
                    modifier = Modifier.size(20.dp)
                )
            }
            Text(
                text = model.title,
                color = M3_OnSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = model.body,
                color = M3_OnSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium
            )
            model.evidence?.let { evidence ->
                Text(
                    text = evidence,
                    color = M3_OnSurface,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            if (model.showProgress) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = M3_Primary,
                    trackColor = M3_SurfaceContainerHigh
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                model.primaryAction?.let { action ->
                    Button(
                        onClick = { onAction(action) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(model.primaryLabel.orEmpty())
                    }
                }
                TextButton(
                    onClick = onHowThisWorks,
                    modifier = if (model.primaryAction == null) {
                        Modifier.fillMaxWidth()
                    } else {
                        Modifier
                    }
                ) {
                    Text("How this works")
                }
            }
        }
    }
}

@Composable
fun SyncStrip(
    syncState: HomeSyncState,
    onStartSync: () -> Unit,
    onInspectSync: () -> Unit,
    onCheckForUnsynced: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
    ) {
        when (syncState.status) {
            HomeSyncState.Status.IDLE -> {
                val pendingCount = syncState.queue.count { it.status == "pending" }
                if (pendingCount > 0) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(M3_PrimaryContainer)
                            .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)), RoundedCornerShape(20.dp))
                            .clickable { isExpanded = !isExpanded }
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Header Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(M3_OnPrimaryContainer.copy(alpha = 0.1f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDownload,
                                    contentDescription = null,
                                    tint = M3_Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "INCOMING MESSAGE STREAM",
                                    color = M3_OnPrimaryContainer.copy(alpha = 0.8f),
                                    style = AppTypography.eyebrow
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "$pendingCount Unsynced SMS Found",
                                    color = M3_OnSurface,
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = if (isExpanded) "Click to collapse" else "Click to view pending messages",
                                    color = M3_OnPrimaryContainer.copy(alpha = 0.75f),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (!isExpanded) {
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(100))
                                        .background(M3_Primary)
                                        .clickable { onStartSync() }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.PlayArrow,
                                        contentDescription = null,
                                        tint = M3_OnPrimary,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Text(
                                        text = "Process",
                                        color = M3_OnPrimary,
                                        style = AppTypography.eyebrow
                                    )
                                }
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.KeyboardArrowUp,
                                    contentDescription = "Collapse",
                                    tint = M3_OnPrimaryContainer.copy(alpha = 0.75f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }

                        if (isExpanded) {
                            HorizontalDivider(color = M3_OnPrimaryContainer.copy(alpha = 0.15f))
                            
                            // List of SMS yet to be synced
                            val pendingSmsList = syncState.queue.filter { it.status == "pending" }
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 220.dp)
                                    .verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                pendingSmsList.forEach { sms ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(M3_OnPrimaryContainer.copy(alpha = 0.05f))
                                            .padding(10.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(24.dp)
                                                .background(M3_OnPrimaryContainer.copy(alpha = 0.1f), CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = getInitials(sms.sender),
                                                color = M3_OnPrimaryContainer,
                                                style = AppTypography.eyebrow
                                            )
                                        }
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = sms.sender,
                                                    color = M3_OnSurface,
                                                    style = MaterialTheme.typography.labelSmall
                                                )
                                                Text(
                                                    text = formatTime(sms.date),
                                                    color = M3_OnPrimaryContainer.copy(alpha = 0.6f),
                                                    style = AppTypography.timestamp
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = sms.body,
                                                color = M3_OnSurfaceVariant,
                                                style = AppTypography.monoBody,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }

                            // Start Syncing Button
                            Button(
                                onClick = {
                                    onStartSync()
                                    onInspectSync()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = M3_Primary),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(100),
                                contentPadding = PaddingValues(vertical = 10.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = null,
                                    tint = M3_OnPrimary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Start Syncing & Inspect",
                                    color = M3_OnPrimary,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(M3_SurfaceContainerLow)
                            .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.2f)), RoundedCornerShape(20.dp))
                            .clickable { 
                                if (syncState.queue.isNotEmpty()) {
                                    onInspectSync()
                                } else {
                                    onCheckForUnsynced()
                                }
                            }
                            .padding(horizontal = 14.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .background(M3_SurfaceContainerHigh, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = M3_OnSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                 text = "MANUAL SMS SCAN",
                                 color = M3_OnSurfaceVariant.copy(alpha = 0.8f),
                                 style = MaterialTheme.typography.labelSmall
                             )
                             Spacer(modifier = Modifier.height(2.dp))
                             Text(
                                 text = "Check for recent eligible alerts",
                                 color = M3_OnSurface,
                                 style = AppTypography.bodyMediumBold
                             )
                             Spacer(modifier = Modifier.height(2.dp))
                             Text(
                                 text = "No pending items are currently shown. Scan results, not queue emptiness, determine coverage.",
                                 color = M3_OnSurfaceVariant,
                                 style = MaterialTheme.typography.bodySmall
                             )
                        }
                        Row(
                            modifier = Modifier
                                .background(M3_SurfaceContainerHigh, RoundedCornerShape(100))
                                .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(100))
                                .clickable { onCheckForUnsynced() }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                tint = M3_Primary,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Scan",
                                color = M3_Primary,
                                style = AppTypography.eyebrow
                            )
                        }
                    }
                }
            }

            HomeSyncState.Status.SYNCING -> {
                val total = syncState.queue.size
                val current = (syncState.currentIndex ?: 0) + 1
                val activeSms = if (syncState.currentIndex != null && syncState.currentIndex < syncState.queue.size) {
                    syncState.queue[syncState.currentIndex]
                } else null

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(M3_SurfaceContainer)
                        .border(BorderStroke(2.dp, Color(0xFFF2C94C).copy(alpha = 0.25f)), RoundedCornerShape(20.dp))
                        .clickable { onInspectSync() }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(Color(0xFFF2C94C).copy(alpha = 0.10f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color(0xFFF2C94C),
                            strokeWidth = 2.dp
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "RUNNING LOCAL QWEN SLM",
                                color = Color(0xFFF2C94C),
                                style = AppTypography.eyebrow
                            )
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(Color(0xFFF2C94C), CircleShape)
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (activeSms != null) "Analyzing ${activeSms.sender}..." else "Processing SMS stream...",
                            color = M3_OnSurface,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "Message $current of $total",
                            color = M3_OnSurfaceVariant,
                            style = AppTypography.eyebrow
                        )
                    }
                    Row(
                        modifier = Modifier
                            .background(M3_SurfaceContainerHigh, RoundedCornerShape(100))
                            .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(100))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Visibility,
                            contentDescription = null,
                            tint = M3_Primary,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "Inspect",
                            color = M3_Primary,
                            style = AppTypography.eyebrow
                        )
                    }
                }
            }

            HomeSyncState.Status.DONE -> {
                val hasFailures = homeSyncHasFailures(syncState)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(
                            if (hasFailures) M3_ErrorContainer else M3_PosContainer
                        )
                        .clickable { onInspectSync() }
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (hasFailures) {
                                    M3_OnErrorContainer.copy(alpha = 0.1f)
                                } else {
                                    M3_OnPosContainer.copy(alpha = 0.1f)
                                },
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (hasFailures) {
                                Icons.Rounded.ErrorOutline
                            } else {
                                Icons.Default.CheckCircle
                            },
                            contentDescription = null,
                            tint = if (hasFailures) M3_Error else M3_Pos,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (hasFailures) {
                                "PROCESSING FINISHED WITH ISSUES"
                            } else {
                                "PROCESSING FINISHED"
                            },
                            color = if (hasFailures) {
                                M3_Error
                            } else {
                                M3_Pos.copy(alpha = 0.9f)
                            },
                            style = AppTypography.eyebrow
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = syncState.syncError
                                ?: "Manual processing finished",
                            color = M3_OnSurface,
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = homeSyncCompletionSummary(syncState),
                            color = M3_OnPosContainer.copy(alpha = 0.85f),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Box(
                        modifier = Modifier
                            .background(Color.White.copy(alpha = 0.20f), RoundedCornerShape(100))
                            .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)), RoundedCornerShape(100))
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "View Logs",
                            color = Color.White,
                            style = AppTypography.eyebrow
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DrawerContent(
    syncState: HomeSyncState,
    onNavigate: (String) -> Unit,
    onDismiss: () -> Unit,
    onResetSync: () -> Unit,
    onItemClick: (SyncSmsItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .padding(bottom = 32.dp)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Terminal,
                    contentDescription = null,
                    tint = M3_Primary,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "On-Device Local SLM Monitor",
                    color = M3_OnSurface,
                    style = AppTypography.titleSmallBold
                )
            }
            Text(
                text = "Close",
                color = M3_OnSurfaceVariant,
                style = AppTypography.bodySmallBold,
                modifier = Modifier
                    .background(M3_SurfaceContainerHigh, RoundedCornerShape(100))
                    .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(100))
                    .clickable { onDismiss() }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        HorizontalDivider(color = M3_OutlineVariant.copy(alpha = 0.15f))
        Spacer(modifier = Modifier.height(16.dp))

        // Target Extraction Queue
        Text(
            text = "TARGET EXTRACTION QUEUE",
            color = M3_OnSurfaceVariant,
            style = AppTypography.eyebrow
        )
        Spacer(modifier = Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f, fill = false)
                .maxHeight(300.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(syncState.queue) { idx, item ->
                val isActive = idx == syncState.currentIndex
                val isComplete =
                    item.status == "synced" ||
                        item.status == "already_saved"
                val isFiltered = item.status == "filtered_out"
                val isError = item.status == "error"

                val bg = when {
                    isActive -> Color(0xFFF2C94C).copy(alpha = 0.05f)
                    isComplete -> M3_PosContainer.copy(alpha = 0.20f)
                    isFiltered -> M3_OutlineVariant.copy(alpha = 0.10f)
                    else -> M3_SurfaceContainerLow
                }
                val border = when {
                    isActive -> BorderStroke(1.dp, Color(0xFFF2C94C).copy(alpha = 0.3f))
                    isComplete -> BorderStroke(1.dp, M3_Pos.copy(alpha = 0.25f))
                    isFiltered -> BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.20f))
                    else -> BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.15f))
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(bg)
                        .border(border, RoundedCornerShape(12.dp))
                        .clickable { onItemClick(item) }
                        .padding(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(modifier = Modifier.padding(top = 2.dp)) {
                        when {
                            isComplete -> {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(M3_Pos, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                            isFiltered -> {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(M3_OutlineVariant, CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Block,
                                        contentDescription = null,
                                        tint = M3_OnSurface,
                                        modifier = Modifier.size(11.dp)
                                    )
                                }
                            }
                            isActive -> {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .background(Color(0xFFF2C94C).copy(alpha = 0.25f), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.FlashOn,
                                        contentDescription = null,
                                        tint = Color(0xFFF2C94C),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                            }
                            else -> {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .border(BorderStroke(1.dp, M3_OutlineVariant), CircleShape),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = (idx + 1).toString(),
                                        color = M3_OnSurfaceVariant,
                                        style = AppTypography.eyebrow.copy(
                                            platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                                                includeFontPadding = false
                                            ),
                                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                        )
                                    )
                                }
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = item.sender,
                                color = M3_OnSurface,
                                style = AppTypography.bodySmallBold
                            )
                            Text(
                                text = formatTime(item.date),
                                color = M3_OnSurfaceVariant,
                                style = AppTypography.timestamp
                            )
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = item.body,
                            color = M3_OnSurfaceVariant,
                            style = AppTypography.monoBody,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )

                        if (isActive) {
                            Spacer(modifier = Modifier.height(8.dp))
                            PipelineStagesView(
                                activeIndex = syncState.currentStageIndex ?: 0,
                                thinkingOutput = syncState.thinkingOutput,
                                hasThinkingMode = syncState.hasThinkingMode
                            )
                        }

                        if (isFiltered) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Filtered: non-transactional metadata matched",
                                color = M3_Error,
                                style = AppTypography.eyebrow,
                                modifier = Modifier
                                    .background(M3_ErrorContainer.copy(alpha = 0.1f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }

                        if (isComplete && item.parsedAmount != null) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(M3_PosContainer.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                                    .border(BorderStroke(1.dp, M3_Pos.copy(alpha = 0.1f)), RoundedCornerShape(6.dp))
                                    .padding(6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Extracted: ${item.parsedMerchant}",
                                    color = M3_OnPosContainer,
                                    style = AppTypography.eyebrow
                                )
                                Text(
                                    text = "₹${item.parsedAmount}",
                                    color = M3_OnPosContainer,
                                    style = AppTypography.eyebrowBold
                                )
                            }
                        }
                    }
                }
            }
        }

        if (syncState.status == HomeSyncState.Status.SYNCING) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = { onNavigate("transactions") },
                colors = ButtonDefaults.buttonColors(containerColor = M3_Primary),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(100)
            ) {
                Text(
                    text = "Inspect Active SLM Token Logs",
                    color = M3_OnPrimary,
                    style = AppTypography.bodySmallBold
                )
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = M3_OnPrimary,
                    modifier = Modifier.size(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "Observe compiler attention vectors & parsed JSON in realtime.",
                color = M3_OnSurfaceVariant.copy(alpha = 0.7f),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }

        if (syncState.status == HomeSyncState.Status.DONE) {
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = {
                    onResetSync()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = M3_Pos),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(100)
            ) {
                Text(
                    text = "Done",
                    color = Color.White,
                    style = AppTypography.bodySmallBold
                )
            }
        }
    }
}

@Composable
fun PipelineStagesView(
    activeIndex: Int,
    thinkingOutput: String = "",
    hasThinkingMode: Boolean = true
) {
    val stages = if (hasThinkingMode) {
        listOf(
            "Pre-Filter Check" to "Checking message format and keywords",
            "Phase 1: Thinking Pass" to "Greedy reasoning pass on device CPU",
            "Phase 2: Structured JSON" to "Structured transaction extraction",
            "Database Persistence" to "Inserting transaction in encrypted DB"
        )
    } else {
        listOf(
            "Pre-Filter Check" to "Checking message format and keywords",
            "Phase 2: Structured JSON" to "Structured transaction extraction",
            "Database Persistence" to "Inserting transaction in encrypted DB"
        )
    }

    val mappedActiveIndex = if (hasThinkingMode) {
        activeIndex
    } else {
        when (activeIndex) {
            0 -> 0
            2 -> 1
            3 -> 2
            else -> 0
        }
    }

    var isPhase1Expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(M3_Surface, RoundedCornerShape(8.dp))
            .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.25f)), RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(bottom = 2.dp)
        ) {
            Icon(
                imageVector = Icons.Rounded.AutoAwesome,
                contentDescription = null,
                tint = M3_Primary,
                modifier = Modifier.size(10.dp)
            )
            Text(
                text = "QWEN.SLM PIPELINE STAGES:",
                color = M3_Primary,
                style = AppTypography.eyebrow
            )
        }

        stages.forEachIndexed { index, (name, _) ->
            val isCurrent = index == mappedActiveIndex
            val isDone = index < mappedActiveIndex
            val isPhase1 = hasThinkingMode && index == 1

            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(4.dp))
                        .then(
                            if (isPhase1 && thinkingOutput.isNotEmpty()) {
                                Modifier.clickable { isPhase1Expanded = !isPhase1Expanded }
                            } else Modifier
                        ),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val indicator = when {
                            isDone -> "●"
                            isCurrent -> "▶"
                            else -> "○"
                        }
                        val color = when {
                            isDone -> M3_Pos
                            isCurrent -> Color(0xFFF2C94C)
                            else -> M3_OnSurfaceVariant.copy(alpha = 0.3f)
                        }

                        Text(
                            text = indicator,
                            color = color,
                            style = AppTypography.eyebrow
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = name,
                                color = if (isCurrent) M3_OnSurface else if (isDone) M3_OnSurfaceVariant else M3_OnSurfaceVariant.copy(alpha = 0.4f),
                                style = AppTypography.eyebrow.copy(fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
                            )
                            if (isPhase1 && thinkingOutput.isNotEmpty()) {
                                Icon(
                                    imageVector = if (isPhase1Expanded) Icons.Rounded.ArrowDropUp else Icons.Rounded.ArrowDropDown,
                                    contentDescription = "Expand thinking output",
                                    tint = M3_Primary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    val badgeText = when {
                        isDone -> "done"
                        isCurrent -> "running"
                        else -> "idle"
                    }
                    val badgeBg = when {
                        isDone -> M3_PosContainer.copy(alpha = 0.2f)
                        isCurrent -> Color(0xFFF2C94C).copy(alpha = 0.15f)
                        else -> Color.Transparent
                    }
                    val badgeTextClr = when {
                        isDone -> M3_OnPosContainer
                        isCurrent -> Color(0xFFF2C94C)
                        else -> M3_OnSurfaceVariant.copy(alpha = 0.2f)
                    }

                    Text(
                        text = badgeText.uppercase(),
                        color = badgeTextClr,
                        style = AppTypography.eyebrow,
                        modifier = Modifier
                            .background(badgeBg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }

                if (isPhase1 && isPhase1Expanded) {
                    val displayOutput = thinkingOutput.ifEmpty { "Waiting for thinking tokens..." }
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 120.dp)
                            .background(M3_SurfaceContainerLow, RoundedCornerShape(6.dp))
                            .border(BorderStroke(1.dp, M3_OutlineVariant.copy(alpha = 0.3f)), RoundedCornerShape(6.dp))
                            .padding(6.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                        ) {
                            Text(
                                text = displayOutput,
                                color = M3_OnSurfaceVariant,
                                style = AppTypography.monoBody
                            )
                        }
                    }
                }
            }
        }
    }
}

// ── Shared Color Helpers ──

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

private fun formatTime(timestampMs: Long): String {
    val date = Date(timestampMs)
    val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    return fmt.format(date)
}

private fun Modifier.maxHeight(max: androidx.compose.ui.unit.Dp): Modifier {
    return this.heightIn(max = max)
}

private fun getAccountShortLabel(label: String?): String {
    if (label == null || label == "__UNKNOWN__") return "Unknown"
    
    val runs = Regex("\\d+").findAll(label).toList()
    val digits = if (runs.isNotEmpty()) runs.last().value.takeLast(4) else ""
    
    val cleanLabel = label
        .replace(Regex("A/c|Card|Account", RegexOption.IGNORE_CASE), "")
        .replace(Regex("XX\\d*", RegexOption.IGNORE_CASE), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        
    val shortBank = when {
        cleanLabel.contains("State Bank of India", ignoreCase = true) || cleanLabel.contains("SBI", ignoreCase = true) -> "SBI"
        cleanLabel.contains("HDFC", ignoreCase = true) -> "HDFC"
        cleanLabel.contains("ICICI", ignoreCase = true) -> "ICICI"
        cleanLabel.contains("Axis", ignoreCase = true) -> "Axis"
        cleanLabel.contains("Kotak", ignoreCase = true) -> "Kotak"
        cleanLabel.contains("Standard Chartered", ignoreCase = true) -> "SCB"
        cleanLabel.contains("Federal", ignoreCase = true) -> "Federal"
        cleanLabel.contains("Paytm", ignoreCase = true) -> "Paytm"
        cleanLabel.contains("PhonePe", ignoreCase = true) -> "PhonePe"
        cleanLabel.isBlank() -> "Bank"
        else -> {
            val firstWord = cleanLabel.split(" ").firstOrNull() ?: "Bank"
            if (firstWord.length > 8) firstWord.take(8) else firstWord
        }
    }
    
    return if (digits.isNotEmpty()) "$shortBank ••$digits" else shortBank
}

internal fun selectedPeriodEmptyMessage(
    selectedPeriod: String,
    totalTransactionCount: Int,
    setupStatus: SetupImportStatus
): String {
    if (totalTransactionCount > 0) {
        return "No spending transactions in the selected ${selectedPeriod.lowercase()} period"
    }
    return when (setupStatus) {
        SetupImportStatus.PERMISSION_NEEDED ->
            "SMS access is off. Restore it to discover transaction alerts."
        SetupImportStatus.NOT_STARTED,
        SetupImportStatus.DOWNLOADING,
        SetupImportStatus.SCANNING,
        SetupImportStatus.PROCESSING,
        SetupImportStatus.PAUSED ->
            "No transactions yet. The setup card shows what remains."
        SetupImportStatus.FAILED ->
            "No transactions were saved. The setup card explains what needs attention."
        SetupImportStatus.READY_NO_HISTORY ->
            "No eligible transaction history was found. The next eligible alert will appear here."
        SetupImportStatus.READY ->
            "No saved spending transactions in this period."
    }
}

internal fun homeSyncHasFailures(state: HomeSyncState): Boolean =
    state.syncError != null ||
        state.queue.any { it.status == "error" }

internal fun homeSyncCompletionSummary(state: HomeSyncState): String =
    buildString {
        val saved = state.queue.count { it.status == "synced" }
        val alreadySaved =
            state.queue.count { it.status == "already_saved" }
        val rejected =
            state.queue.count { it.status == "filtered_out" }
        val failed = state.queue.count { it.status == "error" }

        append("$saved saved")
        if (alreadySaved > 0) {
            append(" · $alreadySaved already present")
        }
        if (rejected > 0) append(" · $rejected rejected")
        if (failed > 0) append(" · $failed failed")
    }

@Composable
fun ModelUpgradeBanner(
    recommendation: ModelUpgradeRecommendation,
    onUpgrade: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit
) {
    val recSlm = recommendation.recommendedSlm ?: return
    val ds = recommendation.downloadState
    val wasCancelled =
        !recommendation.isRunning &&
            recommendation.statusMessage == "Cancelled"
    val eyebrow = if (recommendation.isDebugEmulatorOverride) {
        "DEBUG EMULATOR UPGRADE TEST"
    } else {
        "ACCURACY UPGRADE AVAILABLE"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = M3_SurfaceContainerLow),
        border = BorderStroke(1.dp, M3_Primary.copy(alpha = 0.35f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
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
                            .size(32.dp)
                            .background(M3_Primary.copy(alpha = 0.15f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bolt,
                            contentDescription = null,
                            tint = M3_Primary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Text(
                        text = eyebrow,
                        color = M3_Primary,
                        style = AppTypography.eyebrow
                    )
                }

                if (!recommendation.isRunning) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = "Dismiss model upgrade",
                            tint = M3_OnSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (recommendation.isDebugEmulatorOverride) {
                        "Test ${recSlm.name} on this emulator"
                    } else {
                        "Improve accuracy with ${recSlm.name}"
                    },
                    color = M3_OnSurface,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = if (recommendation.isDebugEmulatorOverride) {
                        "This debug emulator has enough RAM and storage to exercise the larger-model flow (~${"%.1f".format(recSlm.sizeGb)} GB). Inference may be slower than on an accelerated phone."
                    } else {
                        "Your phone supports a higher quality local AI model (~${"%.1f".format(recSlm.sizeGb)} GB) that can improve transaction extraction accuracy."
                    },
                    color = M3_OnSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (wasCancelled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_SurfaceContainer, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PauseCircle,
                        contentDescription = null,
                        tint = M3_OnSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "Upgrade canceled. Downloaded progress is kept for when you resume.",
                        color = M3_OnSurfaceVariant,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }

            recommendation.error
                ?.takeIf { !recommendation.isRunning }
                ?.let { error ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                M3_ErrorContainer.copy(alpha = 0.24f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Warning,
                            contentDescription = null,
                            tint = M3_Error,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = error,
                            color = M3_OnErrorContainer,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }

            if (recommendation.isDownloading && !recommendation.isCancelling) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_SurfaceContainer, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Downloading model in background...",
                            color = M3_OnSurface,
                            style = MaterialTheme.typography.labelSmall
                        )
                        Text(
                            text = "${"%.0f".format(ds.progress * 100)}%",
                            color = M3_Primary,
                            style = AppTypography.bodySmallBold
                        )
                    }
                    LinearProgressIndicator(
                        progress = { ds.progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = M3_Primary,
                        trackColor = M3_OutlineVariant.copy(alpha = 0.3f)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "${"%.1f".format(ds.downloadedMb)} / ${"%.1f".format(ds.totalMb)} MB",
                            color = M3_OnSurfaceVariant,
                            style = AppTypography.timestamp
                        )
                        if (ds.speedMbps > 0) {
                            Text(
                                text = "${"%.1f".format(ds.speedMbps)} MB/s",
                                color = M3_OnSurfaceVariant,
                                style = AppTypography.timestamp
                            )
                        }
                    }
                }
            } else if (recommendation.isRunning) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(M3_SurfaceContainer, RoundedCornerShape(12.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = M3_Primary,
                        trackColor = M3_OutlineVariant.copy(alpha = 0.3f)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = when {
                                recommendation.isCancelling ->
                                    "Cancelling model upgrade..."
                                recommendation.isApplying ->
                                    "Activating model..."
                                else -> "Preparing model upgrade..."
                            },
                            color = M3_OnSurface,
                            style = AppTypography.bodySmallBold
                        )
                        Text(
                            text = recommendation.statusMessage
                                ?: when {
                                    recommendation.isCancelling ->
                                        "Waiting for model work to stop safely."
                                    recommendation.isApplying ->
                                        "Validating the download and safely switching models."
                                    else ->
                                        "Starting the background download service."
                                },
                            color = M3_OnSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Later", color = M3_OnSurfaceVariant, style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = onUpgrade,
                        colors = ButtonDefaults.buttonColors(containerColor = M3_Primary),
                        shape = RoundedCornerShape(100)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Download,
                            contentDescription = null,
                            tint = M3_OnPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = when {
                                wasCancelled -> "Resume Upgrade"
                                recommendation.error != null -> "Retry Upgrade"
                                else -> "Upgrade Model"
                            },
                            color = M3_OnPrimary,
                            style = AppTypography.titleSmallBold
                        )
                    }
                }
            }

            if (recommendation.canCancel) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = null,
                            tint = M3_OnSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Cancel upgrade",
                            color = M3_OnSurfaceVariant,
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }
        }
    }
}
