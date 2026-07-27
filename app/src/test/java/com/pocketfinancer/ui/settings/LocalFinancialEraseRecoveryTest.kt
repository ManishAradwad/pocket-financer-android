package com.pocketfinancer.ui.settings

import com.pocketfinancer.data.repository.TransactionRepository
import com.pocketfinancer.inference.SlmModelStorage
import com.pocketfinancer.pipeline.SmsWorkAdmissionPause
import com.pocketfinancer.pipeline.SmsWorkController
import com.pocketfinancer.setup.FakeSharedPreferences
import com.pocketfinancer.setup.SetupImportStatus
import com.pocketfinancer.setup.SetupImportStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalFinancialEraseRecoveryTest {

    @Test
    fun `process restart completes marked erase before releasing admission`() =
        runTest {
            val fake = FakeSharedPreferences(
                initialValues = mapOf(
                    SetupImportStore.KEY_ONBOARDING_COMPLETED to true,
                    "selected_slm_id" to "old-model",
                    "onboarding_run_generation" to 4L
                )
            )
            SetupImportStore(
                fake.preferences,
                hasSmsPermissions = true
            ).beginLocalFinancialErase(nextRunGeneration = 5L)
            val restartedStore = SetupImportStore(
                fake.preferences,
                hasSmsPermissions = true
            )
            val repository = mockk<TransactionRepository>()
            val storage = mockk<SlmModelStorage>()
            val workController = mockk<SmsWorkController>()
            val admissionPause = mockk<SmsWorkAdmissionPause>()
            val actions = mutableListOf<String>()

            every { workController.pauseAdmissions() } returns admissionPause
            coEvery { admissionPause.cancelPending() } coAnswers {
                actions += "cancel work"
            }
            coEvery { repository.clearDatabase() } coAnswers {
                actions += "clear database"
            }
            coEvery { admissionPause.release() } coAnswers {
                assertFalse(restartedStore.isLocalFinancialErasePending())
                actions += "release admission"
            }
            every { storage.modelFile(any()) } answers {
                File(
                    "build/test-erase-recovery/${System.nanoTime()}",
                    firstArg()
                )
            }
            val recovery = LocalFinancialEraseRecovery(
                setupImportStore = restartedStore,
                transactionRepository = repository,
                modelStorage = storage,
                smsWorkController = workController,
                cancelFinancialNotifications = {
                    actions += "cancel notifications"
                }
            )

            val result = recovery.recoverIfNeeded()

            assertTrue(result.recovered)
            assertNull(result.cleanupFailure)
            assertEquals(
                listOf(
                    "cancel work",
                    "clear database",
                    "cancel notifications",
                    "release admission"
                ),
                actions
            )
            assertFalse(restartedStore.isLocalFinancialErasePending())
            assertEquals(
                false,
                fake.values[SetupImportStore.KEY_ONBOARDING_COMPLETED]
            )
            assertNull(fake.values["selected_slm_id"])
            assertEquals(5L, fake.values["onboarding_run_generation"])
            assertEquals(
                SetupImportStatus.NOT_STARTED,
                restartedStore.state.value.status
            )
        }

    @Test
    fun `database failure leaves marker for the next restart and releases admission`() =
        runTest {
            val fake = FakeSharedPreferences()
            val store = SetupImportStore(
                fake.preferences,
                hasSmsPermissions = true
            )
            store.beginLocalFinancialErase(nextRunGeneration = 1L)
            val repository = mockk<TransactionRepository>()
            val storage = mockk<SlmModelStorage>()
            val workController = mockk<SmsWorkController>()
            val admissionPause = mockk<SmsWorkAdmissionPause>()

            every { workController.pauseAdmissions() } returns admissionPause
            coEvery { admissionPause.cancelPending() } returns Unit
            coEvery {
                repository.clearDatabase()
            } throws IllegalStateException("database unavailable")
            coEvery { admissionPause.release() } returns Unit
            every { storage.modelFile(any()) } answers {
                File(
                    "build/test-erase-recovery/${System.nanoTime()}",
                    firstArg()
                )
            }
            var notificationsCancelled = false
            val recovery = LocalFinancialEraseRecovery(
                setupImportStore = store,
                transactionRepository = repository,
                modelStorage = storage,
                smsWorkController = workController,
                cancelFinancialNotifications = {
                    notificationsCancelled = true
                }
            )

            var failure: Throwable? = null
            try {
                recovery.recoverIfNeeded()
            } catch (error: Throwable) {
                failure = error
            }

            assertTrue(failure is IllegalStateException)
            assertTrue(store.isLocalFinancialErasePending())
            assertFalse(notificationsCancelled)
            coVerify(exactly = 1) { admissionPause.release() }
        }

    @Test
    fun `startup recovery is a no-op without durable marker`() =
        runTest {
            val store = SetupImportStore(
                FakeSharedPreferences().preferences,
                hasSmsPermissions = true
            )
            val repository = mockk<TransactionRepository>(relaxed = true)
            val storage = mockk<SlmModelStorage>(relaxed = true)
            val workController = mockk<SmsWorkController>(relaxed = true)
            val recovery = LocalFinancialEraseRecovery(
                setupImportStore = store,
                transactionRepository = repository,
                modelStorage = storage,
                smsWorkController = workController,
                cancelFinancialNotifications = {}
            )

            val result = recovery.recoverIfNeeded()

            assertFalse(result.recovered)
            assertNull(result.cleanupFailure)
            coVerify(exactly = 0) { repository.clearDatabase() }
        }
}
