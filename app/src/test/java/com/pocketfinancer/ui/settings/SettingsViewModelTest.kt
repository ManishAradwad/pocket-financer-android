package com.pocketfinancer.ui.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.pocketfinancer.ProvisionalSelectedModelPin
import com.pocketfinancer.SelectedModelResidency
import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.data.repository.AccountRepository
import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.hardware.DeviceCapabilities
import com.pocketfinancer.inference.ModelDownloader
import com.pocketfinancer.inference.SlmActiveOperation
import com.pocketfinancer.inference.SlmExtractionRequest
import com.pocketfinancer.inference.SlmExtractionResult
import com.pocketfinancer.inference.SlmLease
import com.pocketfinancer.inference.SlmModelSpec
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.inference.SlmOperationKind
import com.pocketfinancer.inference.SlmPendingAction
import com.pocketfinancer.inference.SlmRuntime
import com.pocketfinancer.inference.SlmRuntimeOwner
import com.pocketfinancer.inference.SlmRuntimePhase
import com.pocketfinancer.inference.SlmRuntimeState
import com.pocketfinancer.pipeline.ExtractionParser
import com.pocketfinancer.pipeline.PromptBuilder
import com.pocketfinancer.pipeline.SlmProcessingPreferences
import com.pocketfinancer.pipeline.SmsFilterPipeline
import com.pocketfinancer.pipeline.SmsWorkController
import com.pocketfinancer.ui.home.HomeSyncManager
import com.pocketfinancer.ui.home.HomeSyncState
import com.pocketfinancer.ui.onboarding.OnboardingRunGenerationStore
import com.pocketfinancer.ui.onboarding.OnboardingSyncManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        mockkStatic(Log::class)
        every { Log.i(any<String>(), any<String>()) } returns 0
        every { Log.e(any<String>(), any<String>(), any<Throwable>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        Dispatchers.resetMain()
    }

    @Test
    fun `grammar preference is exposed and updated through settings state`() =
        runTest(dispatcher) {
            val fixture = fixture(gbnfInitiallyEnabled = false)
            val viewModel = fixture.createViewModel()

            runCurrent()
            assertFalse(viewModel.state.value.gbnfGrammarEnabled)

            viewModel.setGbnfGrammarEnabled(true)
            runCurrent()

            assertTrue(viewModel.state.value.gbnfGrammarEnabled)
        }

    @Test
    fun `test SMS snapshots disabled grammar for the complete queued inference`() =
        runTest(dispatcher) {
            val fixture = fixture(gbnfInitiallyEnabled = false, modelLoaded = true)
            var capturedGrammar: String? = "not captured"
            coEvery {
                fixture.runtime.acquire(SlmRuntimeOwner.SETTINGS_TEST, fixture.spec)
            } returns fixture.lease
            coEvery { fixture.lease.extract(any()) } coAnswers {
                capturedGrammar = firstArg<SlmExtractionRequest>().grammar
                SlmExtractionResult.Null(model = fixture.spec)
            }
            coEvery { fixture.lease.release() } returns Unit

            val viewModel = fixture.createViewModel()
            runCurrent()
            viewModel.runTestSms()
            // Change the preference after the click but before inference.
            fixture.gbnf.value = true
            advanceTimeBy(1_801)
            runCurrent()

            assertNull(capturedGrammar)
            verify(exactly = 0) {
                fixture.storage.readTextAsset("sms_extraction.gbnf")
            }
        }

    @Test
    fun `runtime state drives settings busy status and safe control enablement`() =
        runTest(dispatcher) {
            val fixture = fixture(modelLoaded = true)
            val viewModel = fixture.createViewModel()
            runCurrent()

            viewModel.downloadSelectedModel()
            assertTrue(viewModel.state.value.modelLoadError!!.contains("Unload"))

            val foregroundFlow = fixture.appFlowCoordinator.tryEnter(
                SlmRuntimeOwner.HOME_SYNC
            )
            runCurrent()
            assertTrue(viewModel.state.value.flowBusy)
            assertFalse(viewModel.state.value.canUnloadModel)
            assertFalse(viewModel.state.value.canResetOnboarding)
            foregroundFlow!!.release()
            runCurrent()

            val resetPause = fixture.appFlowCoordinator.tryPauseAndDrain(
                SlmRuntimeOwner.SETTINGS_MANUAL
            )
            runCurrent()
            assertTrue(viewModel.state.value.flowBusy)
            assertFalse(viewModel.state.value.canUnloadModel)
            resetPause!!.release()
            runCurrent()

            fixture.runtimeState.value = SlmRuntimeState(
                phase = SlmRuntimePhase.READY,
                loadedModel = fixture.spec,
                leaseCount = 1,
                leasesByOwner = mapOf(SlmRuntimeOwner.HOME_SYNC to 1),
                pinnedModels = mapOf(SlmRuntimeOwner.SELECTED_MODEL to fixture.spec)
            )
            runCurrent()

            assertTrue(viewModel.state.value.runtimeBusy)
            assertEquals(1, viewModel.state.value.runtimeLeaseCount)
            assertFalse(viewModel.state.value.canResetOnboarding)
            assertTrue(viewModel.state.value.canUnloadModel)

            fixture.runtimeState.value = SlmRuntimeState(
                phase = SlmRuntimePhase.RUNNING,
                loadedModel = fixture.spec,
                activeOperation = SlmActiveOperation(
                    requestId = 42,
                    kind = SlmOperationKind.EXTRACT,
                    owner = SlmRuntimeOwner.SMS_WORKER,
                    model = fixture.spec
                ),
                queueDepth = 2,
                pinnedModels = mapOf(SlmRuntimeOwner.SELECTED_MODEL to fixture.spec),
                pendingAction = SlmPendingAction.Unload
            )
            runCurrent()

            assertEquals("sms-worker", viewModel.state.value.runtimeActiveOwner)
            assertEquals(2, viewModel.state.value.runtimeQueueDepth)
            assertFalse(viewModel.state.value.canRunTest)
            assertTrue(viewModel.state.value.pendingRuntimeAction!!.contains("Unload"))

            fixture.runtimeState.value = SlmRuntimeState(
                phase = SlmRuntimePhase.ERROR,
                loadedModel = fixture.spec,
                pinnedModels = mapOf(SlmRuntimeOwner.SELECTED_MODEL to fixture.spec),
                lastError = "native load failed"
            )
            runCurrent()
            assertEquals("native load failed", viewModel.state.value.runtimeError)

            fixture.runtimeState.value = SlmRuntimeState(
                phase = SlmRuntimePhase.READY,
                loadedModel = fixture.spec,
                pinnedModels = mapOf(SlmRuntimeOwner.SELECTED_MODEL to fixture.spec)
            )
            runCurrent()

            assertFalse(viewModel.state.value.runtimeBusy)
            assertNull(viewModel.state.value.runtimeError)
            assertTrue(viewModel.state.value.canRunTest)
            assertTrue(viewModel.state.value.canResetOnboarding)
        }

    @Test
    fun `completed download auto-loads after its settings flow lease is released`() =
        runTest(dispatcher) {
            val fixture = fixture()
            val handoff = mockk<ProvisionalSelectedModelPin>(relaxed = true)
            coEvery {
                fixture.selectedModelResidency.beginProvisionalPin(any(), any())
            } returns handoff
            coEvery { handoff.commit(any()) } returns true

            val viewModel = fixture.createViewModel()
            runCurrent()
            val tier = viewModel.state.value.selectedSlm!!
            val output = fixture.storage.modelFile(tier.modelFile)
            coEvery {
                fixture.modelDownloader.download(any(), output)
            } coAnswers {
                output.parentFile?.mkdirs()
                output.writeText("controllable fake model")
                fixture.downloaderState.value = ModelDownloader.DownloadState(
                    isComplete = true,
                    progress = 1f,
                    outputPath = output.absolutePath
                )
                Result.success(output.absolutePath)
            }

            viewModel.downloadSelectedModel()
            runCurrent()

            coVerify(exactly = 1) {
                fixture.selectedModelResidency.beginProvisionalPin(any(), any())
            }
            assertTrue(viewModel.state.value.modelLoadError == null)
        }

    @Test
    fun `settings refuses to replace an existing final model artifact`() =
        runTest(dispatcher) {
            val fixture = fixture()
            val viewModel = fixture.createViewModel()
            runCurrent()
            val tier = viewModel.state.value.selectedSlm!!
            val output = fixture.storage.modelFile(tier.modelFile)
            output.parentFile?.mkdirs()
            output.writeText("existing model")

            viewModel.downloadSelectedModel()
            runCurrent()

            assertTrue(viewModel.state.value.modelLoadError!!.contains("will not replace"))
            coVerify(exactly = 0) {
                fixture.modelDownloader.download(any(), any())
            }
        }

    @Test
    fun `passive completed download state never repins after manual unload`() =
        runTest(dispatcher) {
            val fixture = fixture()
            fixture.createViewModel()
            runCurrent()

            fixture.downloaderState.value = ModelDownloader.DownloadState(
                isComplete = true,
                progress = 1f,
                outputPath = File(fixture.spec.modelPath).absolutePath
            )
            runCurrent()

            coVerify(exactly = 0) {
                fixture.selectedModelResidency.beginProvisionalPin(any(), any())
            }
        }

    @Test
    fun `successful settings download does not auto-load after onboarding reset`() =
        runTest(dispatcher) {
            val fixture = fixture(onboardingCompleted = false)
            val viewModel = fixture.createViewModel()
            runCurrent()
            val tier = viewModel.state.value.selectedSlm!!
            val output = fixture.storage.modelFile(tier.modelFile)
            coEvery {
                fixture.modelDownloader.download(any(), output)
            } coAnswers {
                output.parentFile?.mkdirs()
                output.writeText("controllable fake model")
                fixture.downloaderState.value = ModelDownloader.DownloadState(
                    isComplete = true,
                    progress = 1f,
                    outputPath = output.absolutePath
                )
                Result.success(output.absolutePath)
            }

            viewModel.downloadSelectedModel()
            runCurrent()

            coVerify(exactly = 0) {
                fixture.selectedModelResidency.beginProvisionalPin(any(), any())
            }
        }

    private fun fixture(
        gbnfInitiallyEnabled: Boolean = true,
        modelLoaded: Boolean = false,
        onboardingCompleted: Boolean = true
    ): Fixture {
        val context = mockk<Context>()
        val sharedPreferences = mockk<SharedPreferences>()
        val deviceCapabilities = mockk<DeviceCapabilities>()
        val runtime = mockk<SlmRuntime>()
        val storage = mockk<SlmModelStorage>()
        val promptBuilder = mockk<PromptBuilder>()
        val preferences = mockk<SlmProcessingPreferences>()
        val modelDownloader = mockk<ModelDownloader>(relaxed = true)
        val downloaderState = MutableStateFlow(ModelDownloader.DownloadState())
        val selectedModelResidency = mockk<SelectedModelResidency>(relaxed = true)
        val gbnf = MutableStateFlow(gbnfInitiallyEnabled)
        val models = File("build/test-settings-models/${System.nanoTime()}")
        val spec = SlmModelSpec(
            modelId = "test-model",
            modelPath = File(models, "test.gguf").absolutePath,
            artifactRevision = "test",
            hasThinkingMode = false
        )
        val runtimeState = MutableStateFlow(
            if (modelLoaded) {
                SlmRuntimeState(
                    phase = SlmRuntimePhase.READY,
                    loadedModel = spec,
                    pinnedModels = mapOf(SlmRuntimeOwner.SELECTED_MODEL to spec)
                )
            } else {
                SlmRuntimeState()
            }
        )
        val lease = mockk<SlmLease>()
        val appFlowCoordinator = SlmAppFlowCoordinator()

        every {
            context.getSharedPreferences(".app_settings", Context.MODE_PRIVATE)
        } returns sharedPreferences
        every { sharedPreferences.getString("selected_slm_id", null) } returns null
        every { sharedPreferences.getBoolean("process_incoming_sms", true) } returns true
        every {
            sharedPreferences.getBoolean("onboarding_completed", false)
        } returns onboardingCompleted
        every { preferences.gbnfGrammarEnabled } returns gbnf
        every { preferences.setGbnfGrammarEnabled(any()) } answers {
            gbnf.value = firstArg()
        }
        every { deviceCapabilities.assessDevice() } returns testDeviceInfo()
        every { storage.modelDirectory } returns models
        every { storage.modelFile(any()) } answers { File(models, firstArg<String>()) }
        every { runtime.state } returns runtimeState
        every { modelDownloader.state } returns downloaderState
        every { lease.owner } returns SlmRuntimeOwner.SETTINGS_TEST
        every { lease.model } returns spec
        every { lease.isReleased } returns false
        every { promptBuilder.getStaticPrefix() } returns "static prefix"
        every { promptBuilder.buildExtractionPrompt(any(), any()) } returns "raw prompt"
        every { promptBuilder.buildChatPrompt(any(), any()) } returns "chat prompt"

        return Fixture(
            context = context,
            deviceCapabilities = deviceCapabilities,
            runtime = runtime,
            runtimeState = runtimeState,
            storage = storage,
            promptBuilder = promptBuilder,
            preferences = preferences,
            gbnf = gbnf,
            spec = spec,
            lease = lease,
            appFlowCoordinator = appFlowCoordinator,
            modelDownloader = modelDownloader,
            downloaderState = downloaderState,
            selectedModelResidency = selectedModelResidency
        )
    }

    private data class Fixture(
        val context: Context,
        val deviceCapabilities: DeviceCapabilities,
        val runtime: SlmRuntime,
        val runtimeState: MutableStateFlow<SlmRuntimeState>,
        val storage: SlmModelStorage,
        val promptBuilder: PromptBuilder,
        val preferences: SlmProcessingPreferences,
        val gbnf: MutableStateFlow<Boolean>,
        val spec: SlmModelSpec,
        val lease: SlmLease,
        val appFlowCoordinator: SlmAppFlowCoordinator,
        val modelDownloader: ModelDownloader,
        val downloaderState: MutableStateFlow<ModelDownloader.DownloadState>,
        val selectedModelResidency: SelectedModelResidency
    ) {
        fun createViewModel(): SettingsViewModel {
            val homeSync = mockk<HomeSyncManager>()
            val onboardingSync = mockk<OnboardingSyncManager>()
            every { homeSync.syncState } returns MutableStateFlow(HomeSyncState())
            every { onboardingSync.syncState } returns MutableStateFlow(
                OnboardingSyncManager.OnboardingSyncState()
            )
            return SettingsViewModel(
                context = context,
                deviceCapabilities = deviceCapabilities,
                slmRuntime = runtime,
                modelStorage = storage,
                modelDownloader = modelDownloader,
                promptBuilder = promptBuilder,
                extractionParser = mockk<ExtractionParser>(relaxed = true),
                smsFilterPipeline = SmsFilterPipeline(),
                transactionRepository = mockk<TransactionRepository>(relaxed = true),
                accountRepository = mockk<AccountRepository>(relaxed = true),
                slmProcessingPreferences = preferences,
                smsWorkController = mockk<SmsWorkController>(relaxed = true),
                selectedModelResidency = selectedModelResidency,
                appFlowCoordinator = appFlowCoordinator,
                homeSyncManager = homeSync,
                onboardingSyncManager = onboardingSync,
                onboardingRunGenerationStore = mockk<OnboardingRunGenerationStore>(
                    relaxed = true
                )
            )
        }
    }

    private fun testDeviceInfo() = DeviceCapabilities.DeviceInfo(
        ramGb = 4f,
        ramTier = DeviceCapabilities.RamTier.OK,
        gpu = null,
        cpu = DeviceCapabilities.CpuInfo(
            cores = 4,
            features = emptySet(),
            hasI8mm = false,
            hasDotProd = false,
            hasFp16 = false,
            socModel = null
        ),
        storage = DeviceCapabilities.StorageInfo(
            totalBytes = 10_000_000_000L,
            availableBytes = 8_000_000_000L,
            usedBytes = 2_000_000_000L
        ),
        isHighPerformanceDevice = false
    )
}
