package com.pocketfinancer.pipeline

import android.content.Context
import android.content.SharedPreferences
import com.pocketfinancer.data.model.QueuedSmsCandidate
import com.pocketfinancer.data.model.SmsCandidateOrigin
import com.pocketfinancer.data.model.SmsSourceIdentity
import com.pocketfinancer.data.repository.SmsIngestionRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticProcessingPreferencesTest {

    @Test
    fun `missing preference defaults on and explicit changes survive recreation`() =
        runTest {
        val fixture = Fixture()
        val preferences = fixture.preferences
        assertTrue(preferences.enabled.value)

        preferences.disableAndCleanupPending { 0 }
        assertFalse(preferences.enabled.value)
        assertFalse(fixture.recreate().enabled.value)

        preferences.enableAfterCleanupPending { 0 }
        assertTrue(fixture.recreate().enabled.value)
    }

    @Test
    fun `claim that wins boundary finishes before disable removes pending work`() =
        runTest {
            val fixture = Fixture()
            val claimStarted = CompletableDeferred<Unit>()
            val allowClaimToFinish = CompletableDeferred<Unit>()
            var claimFinished = false
            var cleanupStarted = false

            val claim = launch {
                fixture.preferences.withConsistencyBoundary { enabled ->
                    assertTrue(enabled)
                    claimStarted.complete(Unit)
                    allowClaimToFinish.await()
                    claimFinished = true
                }
            }
            claimStarted.await()
            val disable = launch {
                fixture.preferences.disableAndCleanupPending {
                    cleanupStarted = true
                    1
                }
            }
            runCurrent()

            assertFalse(cleanupStarted)
            allowClaimToFinish.complete(Unit)
            claim.join()
            disable.join()

            assertTrue(claimFinished)
            assertTrue(cleanupStarted)
            assertFalse(fixture.preferences.enabled.value)
        }

    @Test
    fun `disable that wins boundary prevents a later automatic claim`() =
        runTest {
            val fixture = Fixture()
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanupToFinish = CompletableDeferred<Unit>()
            var claimRan = false

            val disable = launch {
                fixture.preferences.disableAndCleanupPending {
                    cleanupStarted.complete(Unit)
                    allowCleanupToFinish.await()
                    1
                }
            }
            cleanupStarted.await()
            val claim = launch {
                fixture.preferences.withConsistencyBoundary { enabled ->
                    if (enabled) claimRan = true
                }
            }
            runCurrent()

            assertFalse(claimRan)
            allowCleanupToFinish.complete(Unit)
            disable.join()
            claim.join()

            assertFalse(fixture.preferences.enabled.value)
            assertFalse(claimRan)
        }

    @Test
    fun `retry release that wins boundary is visible to following OFF cleanup`() =
        runTest {
            val fixture = Fixture()
            val ingestionRepository = mockk<SmsIngestionRepository>()
            val retryNotificationStarted = CompletableDeferred<Unit>()
            val allowRetryNotification = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            var pendingAutomaticEvidence = false
            coEvery {
                ingestionRepository.releaseForRetry(
                    candidateKey = "opaque-key",
                    claimToken = "work-id",
                    error = "local failure"
                )
            } coAnswers {
                pendingAutomaticEvidence = true
                true
            }

            val settlement = async {
                settleClaimedCandidateForRetry(
                    candidate = automaticCandidate(),
                    claimToken = "work-id",
                    error = "local failure",
                    automaticProcessingPreferences = fixture.preferences,
                    ingestionRepository = ingestionRepository,
                    onReleasedForRetry = {
                        retryNotificationStarted.complete(Unit)
                        allowRetryNotification.await()
                    }
                )
            }
            retryNotificationStarted.await()
            val disable = launch {
                fixture.preferences.disableAndCleanupPending {
                    assertTrue(pendingAutomaticEvidence)
                    pendingAutomaticEvidence = false
                    cleanupStarted.complete(Unit)
                    1
                }
            }
            runCurrent()

            assertFalse(cleanupStarted.isCompleted)
            allowRetryNotification.complete(Unit)
            assertEquals(
                SmsCandidateRetrySettlement.RELEASED_FOR_RETRY,
                settlement.await()
            )
            disable.join()

            assertTrue(cleanupStarted.isCompleted)
            assertFalse(pendingAutomaticEvidence)
            assertFalse(fixture.preferences.enabled.value)
            coVerify(exactly = 0) {
                ingestionRepository.discardClaimed(any(), any())
            }
        }

    @Test
    fun `OFF that wins boundary deletes claimed automatic retry instead of releasing it`() =
        runTest {
            val fixture = Fixture()
            val ingestionRepository = mockk<SmsIngestionRepository>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()
            coEvery {
                ingestionRepository.discardClaimed(
                    candidateKey = "opaque-key",
                    claimToken = "work-id"
                )
            } returns true

            val disable = launch {
                fixture.preferences.disableAndCleanupPending {
                    cleanupStarted.complete(Unit)
                    allowCleanup.await()
                    1
                }
            }
            cleanupStarted.await()
            val settlement = async {
                settleClaimedCandidateForRetry(
                    candidate = automaticCandidate(),
                    claimToken = "work-id",
                    error = "local failure",
                    automaticProcessingPreferences = fixture.preferences,
                    ingestionRepository = ingestionRepository
                )
            }
            runCurrent()

            coVerify(exactly = 0) {
                ingestionRepository.releaseForRetry(any(), any(), any())
            }
            allowCleanup.complete(Unit)
            disable.join()

            assertEquals(
                SmsCandidateRetrySettlement.FINISHED,
                settlement.await()
            )
            coVerify(exactly = 1) {
                ingestionRepository.discardClaimed(
                    candidateKey = "opaque-key",
                    claimToken = "work-id"
                )
            }
        }

    @Test
    fun `only running automatic candidate finishes while OFF removes the waiter`() =
        runTest {
            val fixture = Fixture()
            val operationGate = AutomaticSmsOperationGate()
            val runningStarted = CompletableDeferred<Unit>()
            val allowRunningToFinish = CompletableDeferred<Unit>()
            var pendingWaiterEvidence = true
            var waiterClaimed = false

            val running = launch {
                operationGate.withCandidate(SmsCandidateOrigin.AUTOMATIC) {
                    runningStarted.complete(Unit)
                    allowRunningToFinish.await()
                }
            }
            runningStarted.await()
            val waiter = launch {
                operationGate.withCandidate(SmsCandidateOrigin.AUTOMATIC) {
                    fixture.preferences.withConsistencyBoundary { enabled ->
                        if (enabled && pendingWaiterEvidence) {
                            waiterClaimed = true
                        }
                    }
                }
            }
            runCurrent()
            assertFalse(waiterClaimed)

            fixture.preferences.disableAndCleanupPending {
                pendingWaiterEvidence = false
                1
            }
            assertFalse(fixture.preferences.enabled.value)

            allowRunningToFinish.complete(Unit)
            running.join()
            waiter.join()

            assertFalse(waiterClaimed)
            assertFalse(pendingWaiterEvidence)
        }

    private fun automaticCandidate() = QueuedSmsCandidate(
        candidateKey = "opaque-key",
        sourceIdentity = SmsSourceIdentity.androidSms(
            providerMessageId = "provider-id",
            sender = "AX-HDFCBK",
            body = "Rs 500 debited",
            sourceTimestamp = 1_000L,
            messageType = 1,
            receivedTimestamp = 1_100L
        ),
        sender = "AX-HDFCBK",
        rawMessage = "Rs 500 debited",
        date = 1_100L,
        sourceTimestamp = 1_000L,
        messageType = 1,
        origin = SmsCandidateOrigin.AUTOMATIC,
        claimToken = "work-id",
        attemptCount = 1
    )

    private class Fixture(
        private var storedValue: Boolean? = null
    ) {
        private val context = mockk<Context>()
        private val sharedPreferences = mockk<SharedPreferences>()
        private val editor = mockk<SharedPreferences.Editor>()

        init {
            every {
                context.getSharedPreferences(
                    AutomaticProcessingPreferences.PREFERENCES_NAME,
                    Context.MODE_PRIVATE
                )
            } returns sharedPreferences
            every {
                sharedPreferences.getBoolean(
                    AutomaticProcessingPreferences.KEY_ENABLED,
                    AutomaticProcessingPreferences.DEFAULT_ENABLED
                )
            } answers {
                storedValue ?: AutomaticProcessingPreferences.DEFAULT_ENABLED
            }
            every { sharedPreferences.edit() } returns editor
            every {
                editor.putBoolean(
                    AutomaticProcessingPreferences.KEY_ENABLED,
                    any()
                )
            } answers {
                storedValue = secondArg()
                editor
            }
            every { editor.commit() } returns true
        }

        val preferences = AutomaticProcessingPreferences(context)

        fun recreate(): AutomaticProcessingPreferences =
            AutomaticProcessingPreferences(context)
    }
}
