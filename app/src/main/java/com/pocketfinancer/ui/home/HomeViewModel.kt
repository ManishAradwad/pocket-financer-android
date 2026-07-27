package com.pocketfinancer.ui.home

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketfinancer.data.model.Transaction
import com.pocketfinancer.data.model.TransactionType
import com.pocketfinancer.data.repository.TransactionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.pipeline.AutomaticProcessingPreferences
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.hardware.SlmTier
import com.pocketfinancer.hardware.isPublishedModelArtifact
import com.pocketfinancer.hardware.resolveActiveSlmTier
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.setup.SetupImportState
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import com.pocketfinancer.setup.reconcileSetupModelAvailability
import com.pocketfinancer.sms.SmsRepository
import com.pocketfinancer.ui.onboarding.OnboardingStep
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import java.util.Calendar
import javax.inject.Inject

data class PeriodData(
    val amount: Double = 0.0,
    val txnCount: Int = 0,
    val deltaDir: String = "same", // "less" | "more" | "same"
    val deltaLabel: String = "No spending in either period",
    val recent: List<Transaction> = emptyList()
)

data class ModelUpgradeRecommendation(
    val isUpgradeAvailable: Boolean = false,
    val recommendedSlm: SlmTier? = null,
    val currentSlm: SlmTier? = null,
    val downloadState: ModelDownloader.DownloadState = ModelDownloader.DownloadState(),
    val isDownloading: Boolean = false,
    val isRunning: Boolean = false,
    val isCancelling: Boolean = false,
    val canCancel: Boolean = false,
    val isApplying: Boolean = false,
    val statusMessage: String? = null,
    val error: String? = null,
    val isDebugEmulatorOverride: Boolean = false,
    val isDismissed: Boolean = false
)

data class HomeUiState(
    val selectedPeriod: String = "Day", // "Day" | "Week" | "Month"
    val periodData: Map<String, PeriodData> = emptyMap(),
    val totalTransactionCount: Int = 0,
    val syncState: HomeSyncState = HomeSyncState(),
    val upgradeRecommendation: ModelUpgradeRecommendation = ModelUpgradeRecommendation(),
    val setupImportState: SetupImportState = SetupImportState(),
    val automaticProcessingEnabled: Boolean =
        AutomaticProcessingPreferences.DEFAULT_ENABLED
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val transactionRepository: TransactionRepository,
    private val syncManager: HomeSyncManager,
    private val smsFilterPipeline: SmsFilterPipeline,
    private val promptBuilder: PromptBuilder,
    private val slmRuntime: SlmRuntime,
    private val modelStorage: SlmModelStorage,
    private val extractionParser: ExtractionParser,
    private val deviceCapabilities: DeviceCapabilities,
    private val modelDownloader: ModelDownloader,
    private val onboardingSyncManager: OnboardingSyncManager,
    private val smsRepository: SmsRepository,
    private val setupImportStore: SetupImportStore,
    private val automaticProcessingPreferences:
        AutomaticProcessingPreferences
) : ViewModel() {

    private val _selectedPeriod = MutableStateFlow("Day")
    val selectedPeriod: StateFlow<String> = _selectedPeriod.asStateFlow()
    private val _isDismissed = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        transactionRepository.getAllByDateDesc(),
        _selectedPeriod,
        syncManager.syncState,
        modelDownloader.state,
        onboardingSyncManager.syncState,
        _isDismissed,
        setupImportStore.state,
        automaticProcessingPreferences.enabled
    ) { flows ->
        @Suppress("UNCHECKED_CAST")
        val txs = flows[0] as List<Transaction>
        val period = flows[1] as String
        val syncState = flows[2] as HomeSyncState
        val downloadState = flows[3] as ModelDownloader.DownloadState
        val onboardingSyncState = flows[4] as OnboardingSyncManager.OnboardingSyncState
        val isDismissed = flows[5] as Boolean
        val setupImportState = flows[6] as SetupImportState
        val automaticProcessingEnabled = flows[7] as Boolean

        val periodDataMap = calculatePeriodData(txs)
        val device = deviceCapabilities.assessDevice()
        val currentSlm = resolveActiveSlmTier(context, modelStorage.modelDirectory, device)
        val allowDebugOverride = allowDebugEmulatorOverride()
        val upgradeTarget = selectModelUpgradeTarget(
            device = device,
            allowDebugEmulatorOverride = allowDebugOverride
        )
        // Once a model-upgrade operation starts, its target is an immutable
        // operation snapshot. Keep showing that same target after cancellation
        // or failure even if live storage changes while the partial/final
        // artifact is written.
        val unfinishedManagedTarget =
            unfinishedModelUpgradeTarget(onboardingSyncState)
        val recommendedSlm = unfinishedManagedTarget ?: upgradeTarget.tier
        val hasUpgrade = isHigherQualityModel(currentSlm, recommendedSlm)
        val isThisUpgradeRun =
            onboardingSyncState.runPurpose ==
                OnboardingSyncManager.RunPurpose.MODEL_UPGRADE &&
                onboardingSyncState.selectedSlm == recommendedSlm
        val isUpgradeRunning = isThisUpgradeRun && onboardingSyncState.isRunning
        val activeDs = if (isThisUpgradeRun) {
            onboardingSyncState.downloadState
        } else {
            downloadState
        }

        val upgradeRec = ModelUpgradeRecommendation(
            isUpgradeAvailable = hasUpgrade,
            recommendedSlm = recommendedSlm,
            currentSlm = currentSlm,
            downloadState = activeDs,
            isDownloading =
                isUpgradeRunning && onboardingSyncState.isDownloading,
            isRunning = isUpgradeRunning,
            isCancelling =
                isUpgradeRunning && onboardingSyncState.isCancelling,
            canCancel =
                isUpgradeRunning &&
                    onboardingSyncState.isCancellationAllowed &&
                    !onboardingSyncState.isCancelling,
            isApplying =
                isUpgradeRunning &&
                    !onboardingSyncState.isCancelling &&
                    onboardingSyncState.step == OnboardingStep.SYNCING,
            statusMessage = onboardingSyncState.syncMessage
                .takeIf { isThisUpgradeRun && it.isNotBlank() },
            error = onboardingSyncState.modelLoadError
                .takeIf { isThisUpgradeRun },
            isDebugEmulatorOverride =
                upgradeTarget.isDebugEmulatorOverride ||
                    (
                        allowDebugOverride &&
                            unfinishedManagedTarget ==
                                SlmTier.QWEN3_1_7B_Q4_K_M &&
                            selectModelUpgradeTarget(
                                device = device,
                                allowDebugEmulatorOverride = false
                            ).tier == SlmTier.DEFAULT_ONBOARDING_SLM
                    ),
            isDismissed = isDismissed
        )

        HomeUiState(
            selectedPeriod = period,
            periodData = periodDataMap,
            totalTransactionCount = txs.size,
            syncState = syncState,
            upgradeRecommendation = upgradeRec,
            setupImportState = setupImportState,
            automaticProcessingEnabled = automaticProcessingEnabled
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = HomeUiState()
    )

    init {
        refreshPermissionHealth()
    }

    fun selectPeriod(period: String) {
        _selectedPeriod.value = period
    }

    fun checkForUnsynced() {
        viewModelScope.launch {
            if (
                !manualRecentSyncAvailable(
                    setupImportStore.state.value.status
                )
            ) {
                return@launch
            }
            if (!smsRepository.hasPermissions()) {
                setupImportStore.reconcilePermission(granted = false)
                return@launch
            }
            syncManager.checkForUnsyncedSms()
            val scanState = syncManager.syncState.value
            val pendingCount = scanState.queue.count { it.status == "pending" }
            val toastMsg = when (scanState.recentScanOutcome) {
                HomeSyncState.RecentScanOutcome.FAILED ->
                    scanState.scanError ?: "The recent SMS scan failed."
                HomeSyncState.RecentScanOutcome.PERMISSION_NEEDED ->
                    "Restore SMS access to scan recent alerts."
                HomeSyncState.RecentScanOutcome.SUCCESS ->
                    if (pendingCount > 0) {
                        "Found $pendingCount eligible message" +
                            if (pendingCount == 1) {
                                " ready to process."
                            } else {
                                "s ready to process."
                            }
                    } else {
                        "No new eligible alerts were found in this recent scan."
                    }
                HomeSyncState.RecentScanOutcome.NOT_RUN ->
                    scanState.scanError
                        ?: "The recent SMS scan did not start."
            }
            android.widget.Toast.makeText(context, toastMsg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun startSync() {
        if (
            !manualRecentSyncAvailable(
                setupImportStore.state.value.status
            )
        ) {
            return
        }
        if (!smsRepository.hasPermissions()) {
            setupImportStore.reconcilePermission(granted = false)
            return
        }
        viewModelScope.launch {
            SyncService.start(context)
        }
    }

    fun refreshPermissionHealth() {
        reconcilePreparedModelAvailability()
        setupImportStore.reconcilePermission(
            granted = smsRepository.hasPermissions()
        )
    }

    fun onSmsPermissionResult(granted: Boolean) {
        setupImportStore.reconcilePermission(granted)
    }

    fun confirmModelDownload() {
        setupImportStore.setModelDownloadConfirmed(true)
    }

    fun startSetupOrResume() {
        val setup = reconcilePreparedModelAvailability()
        if (!smsRepository.hasPermissions()) {
            setupImportStore.reconcilePermission(granted = false)
            return
        }
        if (!setup.modelPrepared && !setup.modelDownloadConfirmed) return

        val resumableWindow =
            (setup.activeScanWindowDays ?: setup.coverageWindowDays)
            ?.takeIf {
                setup.status in setOf(
                    SetupImportStatus.PAUSED,
                    SetupImportStatus.FAILED
                )
            }
        if (resumableWindow != null) {
            onboardingSyncManager.resumeHistoricalImport(
                context = context,
                slm = currentSetupTier(),
                resumeWindowDays = resumableWindow
            )
        } else {
            onboardingSyncManager.startOnboarding(
                context,
                currentSetupTier()
            )
        }
    }

    fun scanOlderMessages() {
        if (!smsRepository.hasPermissions()) {
            setupImportStore.reconcilePermission(granted = false)
            return
        }
        val setup = reconcilePreparedModelAvailability()
        if (!setup.modelPrepared) {
            startSetupOrResume()
            return
        }
        onboardingSyncManager.startHistoricalImport(
            context = context,
            slm = currentSetupTier(),
            coveredWindowDays = setup.coverageWindowDays
        )
    }

    fun startModelUpgrade() {
        val device = deviceCapabilities.assessDevice()
        val recommendedSlm =
            unfinishedModelUpgradeTarget(onboardingSyncManager.syncState.value)
                ?: selectModelUpgradeTarget(
                    device = device,
                    allowDebugEmulatorOverride = allowDebugEmulatorOverride()
                ).tier
                ?: return
        onboardingSyncManager.startModelUpgrade(context, recommendedSlm)
    }

    fun cancelModelUpgrade() {
        val activeRun = onboardingSyncManager.syncState.value
        if (!canCancelModelUpgrade(activeRun)) return

        // The downloader keeps its partial artifact, so starting the upgrade
        // again resumes rather than discarding already downloaded bytes.
        onboardingSyncManager.requestModelUpgradeCancellation(context)
    }

    fun dismissUpgradeBanner() {
        _isDismissed.value = true
    }

    fun resetSyncState() {
        syncManager.resetState()
    }

    private fun currentSetupTier(): SlmTier {
        val preparedModel = publishedSetupTier()
        return setupTierForPreparation(
            modelPrepared =
                setupImportStore.state.value.modelPrepared &&
                    preparedModel != null,
            resolvedActiveTier = preparedModel
        )
    }

    /**
     * Setup may reuse only a model artifact that is actually published on
     * disk. Hardware recommendation is intentionally not a fallback here:
     * before preparation it could turn the explicit ~700 MB confirmation into
     * an unconfirmed multi-gigabyte download.
     */
    private fun publishedSetupTier(): SlmTier? {
        val selectedId = context
            .getSharedPreferences(APP_SETTINGS, Context.MODE_PRIVATE)
            .getString(KEY_SELECTED_SLM_ID, null)
        val selectedTier = SlmTier.ALL_TIERS.find { it.id == selectedId }
        return buildList {
            selectedTier?.let(::add)
            add(SlmTier.DEFAULT_ONBOARDING_SLM)
            addAll(SlmTier.ALL_TIERS)
        }
            .distinctBy { it.id }
            .firstOrNull { tier ->
                isPublishedModelArtifact(
                    modelStorage.modelFile(tier.modelFile)
                )
            }
    }

    private fun reconcilePreparedModelAvailability(): SetupImportState {
        return setupImportStore.reconcileModelAvailability(
            hasPublishedModel = publishedSetupTier() != null
        )
    }

    private fun calculatePeriodData(txs: List<Transaction>): Map<String, PeriodData> {
        // Date timestamps
        val now = System.currentTimeMillis()

        // 1. Today calculations
        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val todayStart = calendar.timeInMillis
        val yesterdayStart = todayStart - 24 * 60 * 60 * 1000L
        val yesterdayEnd = todayStart - 1

        val todayDebits = txs.filter { it.date >= todayStart && it.type == TransactionType.DEBIT }
        val todayAmount = todayDebits.sumOf { it.amount }
        val todayCount = todayDebits.size
        val yesterdayAmount = txs.filter { it.date in yesterdayStart..yesterdayEnd && it.type == TransactionType.DEBIT }.sumOf { it.amount }
        
        val todayComparison = spendingComparison(
            currentAmount = todayAmount,
            previousAmount = yesterdayAmount,
            previousPeriodLabel = "yesterday"
        )

        // 2. Week calculations (Start of current week, e.g. Monday)
        val weekCal = Calendar.getInstance()
        weekCal.set(Calendar.HOUR_OF_DAY, 0)
        weekCal.set(Calendar.MINUTE, 0)
        weekCal.set(Calendar.SECOND, 0)
        weekCal.set(Calendar.MILLISECOND, 0)
        // Set to Monday of this week
        val currentDayOfWeek = weekCal.get(Calendar.DAY_OF_WEEK)
        val daysDiff = if (currentDayOfWeek == Calendar.SUNDAY) -6 else Calendar.MONDAY - currentDayOfWeek
        weekCal.add(Calendar.DAY_OF_YEAR, daysDiff)
        val thisWeekStart = weekCal.timeInMillis
        val lastWeekStart = thisWeekStart - 7 * 24 * 60 * 60 * 1000L
        val lastWeekEnd = thisWeekStart - 1

        val thisWeekDebits = txs.filter { it.date >= thisWeekStart && it.type == TransactionType.DEBIT }
        val thisWeekAmount = thisWeekDebits.sumOf { it.amount }
        val thisWeekCount = thisWeekDebits.size
        val lastWeekAmount = txs.filter { it.date in lastWeekStart..lastWeekEnd && it.type == TransactionType.DEBIT }.sumOf { it.amount }

        val weekComparison = spendingComparison(
            currentAmount = thisWeekAmount,
            previousAmount = lastWeekAmount,
            previousPeriodLabel = "last week"
        )

        // 3. Month calculations
        val monthCal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_MONTH, 1)
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val thisMonthStart = monthCal.timeInMillis
        monthCal.add(Calendar.MONTH, -1)
        val lastMonthStart = monthCal.timeInMillis
        val lastMonthEnd = thisMonthStart - 1

        val thisMonthDebits = txs.filter { it.date >= thisMonthStart && it.type == TransactionType.DEBIT }
        val thisMonthAmount = thisMonthDebits.sumOf { it.amount }
        val thisMonthCount = thisMonthDebits.size
        val lastMonthAmount = txs.filter { it.date in lastMonthStart..lastMonthEnd && it.type == TransactionType.DEBIT }.sumOf { it.amount }

        val monthComparison = spendingComparison(
            currentAmount = thisMonthAmount,
            previousAmount = lastMonthAmount,
            previousPeriodLabel = "last month"
        )

        return mapOf(
            "Day" to PeriodData(
                amount = todayAmount,
                txnCount = todayCount,
                deltaDir = todayComparison.direction,
                deltaLabel = todayComparison.label,
                recent = todayDebits.take(5)
            ),
            "Week" to PeriodData(
                amount = thisWeekAmount,
                txnCount = thisWeekCount,
                deltaDir = weekComparison.direction,
                deltaLabel = weekComparison.label,
                recent = thisWeekDebits.take(5)
            ),
            "Month" to PeriodData(
                amount = thisMonthAmount,
                txnCount = thisMonthCount,
                deltaDir = monthComparison.direction,
                deltaLabel = monthComparison.label,
                recent = thisMonthDebits.take(5)
            )
        )
    }

    fun getFilterLogs(item: SyncSmsItem): List<String> {
        if (!item.hasDiagnosticSourceEvidence()) {
            return listOf(SOURCE_EVIDENCE_UNAVAILABLE)
        }
        return smsFilterPipeline.filterWithDetails(item.sender, item.body).logs
    }

    fun getKvCacheLogs(item: SyncSmsItem): List<String> {
        if (!item.hasDiagnosticSourceEvidence()) {
            return listOf(SOURCE_EVIDENCE_UNAVAILABLE)
        }
        return listOf(
            "KV cache telemetry is captured from the exact runtime request.",
            "Historical transactions do not currently persist cache-hit diagnostics."
        )
    }

    fun getSlmPrompt(item: SyncSmsItem): String {
        if (!item.hasDiagnosticSourceEvidence()) {
            return SOURCE_EVIDENCE_UNAVAILABLE
        }
        val rawPrompt = promptBuilder.buildExtractionPrompt(
            item.sender,
            item.body
        )
        val hasThinking = slmRuntime.state.value.loadedModel?.hasThinkingMode ?: true
        return promptBuilder.buildChatPrompt(rawPrompt, enableThinking = hasThinking)
    }

    fun getParsedOutput(jsonStr: String): String {
        val parsed = extractionParser.parse(jsonStr)
        return parsed?.let {
            "amount=${it.amount}, type=${it.type.name.lowercase()}, counterparty=${it.counterparty ?: "-"}, account=${it.account ?: "-"}"
        } ?: "Parsed: null (non-financial)"
    }

    private companion object {
        const val SOURCE_EVIDENCE_UNAVAILABLE =
            "Source evidence is unavailable after terminal processing."
        const val APP_SETTINGS = ".app_settings"
        const val KEY_SELECTED_SLM_ID = "selected_slm_id"
    }

    private fun isProbablyEmulator(): Boolean =
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.startsWith("unknown") ||
            Build.MODEL.contains("Emulator", ignoreCase = true) ||
            Build.MODEL.contains("Android SDK built for", ignoreCase = true) ||
            Build.PRODUCT.contains("sdk", ignoreCase = true)

    private fun allowDebugEmulatorOverride(): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0 &&
            isProbablyEmulator()
}

internal fun setupTierForPreparation(
    modelPrepared: Boolean,
    resolvedActiveTier: SlmTier?
): SlmTier = if (modelPrepared) {
    resolvedActiveTier ?: SlmTier.DEFAULT_ONBOARDING_SLM
} else {
    SlmTier.DEFAULT_ONBOARDING_SLM
}

internal fun canCancelModelUpgrade(
    state: OnboardingSyncManager.OnboardingSyncState
): Boolean =
    state.isRunning &&
        !state.isCancelling &&
        state.isCancellationAllowed &&
        state.runPurpose == OnboardingSyncManager.RunPurpose.MODEL_UPGRADE

internal fun unfinishedModelUpgradeTarget(
    state: OnboardingSyncManager.OnboardingSyncState
): SlmTier? =
    state.selectedSlm.takeIf {
        state.runPurpose == OnboardingSyncManager.RunPurpose.MODEL_UPGRADE &&
            state.step != OnboardingStep.COMPLETED
    }

internal data class SpendingComparison(
    val direction: String,
    val label: String
)

internal fun spendingComparison(
    currentAmount: Double,
    previousAmount: Double,
    previousPeriodLabel: String
): SpendingComparison {
    val delta = currentAmount - previousAmount
    if (kotlin.math.abs(delta) < 0.005) {
        return SpendingComparison(
            direction = "same",
            label = if (currentAmount == 0.0 && previousAmount == 0.0) {
                "No spending in either period"
            } else {
                "Same as $previousPeriodLabel"
            }
        )
    }
    val direction = if (delta > 0) "more" else "less"
    return SpendingComparison(
        direction = direction,
        label =
            "₹${String.format("%,.0f", kotlin.math.abs(delta))} " +
                "$direction than $previousPeriodLabel"
    )
}
