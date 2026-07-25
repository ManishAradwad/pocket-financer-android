package com.pocketfinancer

import com.pocketfinancer.inference.SlmRuntimeOwner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SlmAppFlowCoordinatorTest {

    @Test
    fun `pause rejects new flows and does not return before cancelled flow cleanup`() = runTest {
        val coordinator = SlmAppFlowCoordinator()
        val flowStarted = CompletableDeferred<Unit>()
        val cleanupStarted = CompletableDeferred<Unit>()
        val allowCleanup = CompletableDeferred<Unit>()

        val flow = launch {
            val lease = checkNotNull(coordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC))
            flowStarted.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    allowCleanup.await()
                    lease.release()
                }
            }
        }
        flowStarted.await()

        val pausing = async {
            coordinator.tryPauseAndDrain(SlmRuntimeOwner.SETTINGS_MANUAL)
        }
        runCurrent()
        cleanupStarted.await()

        assertTrue(coordinator.state.value.admissionPaused)
        assertFalse(pausing.isCompleted)
        assertNull(coordinator.tryEnter(SlmRuntimeOwner.ONBOARDING))

        allowCleanup.complete(Unit)
        runCurrent()
        val pause = pausing.await()
        assertNotNull(pause)
        assertEquals(0, coordinator.state.value.activeCount)
        assertNull(coordinator.tryEnter(SlmRuntimeOwner.SETTINGS_TEST))

        pause!!.release()
        val admitted = coordinator.tryEnter(SlmRuntimeOwner.SETTINGS_TEST)
        assertNotNull(admitted)
        admitted!!.release()
        flow.join()
    }

    @Test
    fun `cancelled pause acquisition drains and reopens admission without leaking gate`() =
        runTest {
            val coordinator = SlmAppFlowCoordinator()
            val flowStarted = CompletableDeferred<Unit>()
            val cleanupStarted = CompletableDeferred<Unit>()
            val allowCleanup = CompletableDeferred<Unit>()

            val flow = launch {
                val lease = checkNotNull(coordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC))
                flowStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) {
                        cleanupStarted.complete(Unit)
                        allowCleanup.await()
                        lease.release()
                    }
                }
            }
            flowStarted.await()

            val pausing = launch {
                coordinator.tryPauseAndDrain(SlmRuntimeOwner.SETTINGS_MANUAL)
            }
            runCurrent()
            cleanupStarted.await()
            assertTrue(coordinator.state.value.admissionPaused)

            pausing.cancel()
            allowCleanup.complete(Unit)
            runCurrent()
            pausing.join()
            flow.join()

            assertFalse(coordinator.state.value.admissionPaused)
            val admitted = coordinator.tryEnter(SlmRuntimeOwner.ONBOARDING)
            assertNotNull(admitted)
            admitted!!.release()
        }

    @Test
    fun `model upgrade waits for old flows and blocks new admission during handoff`() =
        runTest {
            val coordinator = SlmAppFlowCoordinator()
            val oldFlowStarted = CompletableDeferred<Unit>()
            val finishOldFlow = CompletableDeferred<Unit>()

            val oldFlow = launch {
                val lease = checkNotNull(
                    coordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC)
                )
                oldFlowStarted.complete(Unit)
                try {
                    finishOldFlow.await()
                } finally {
                    lease.release()
                }
            }
            oldFlowStarted.await()

            val handoffReady = CompletableDeferred<SlmAppFlowPause>()
            val finishUpgrade = CompletableDeferred<Unit>()
            val upgrade = launch {
                val lease = checkNotNull(
                    coordinator.tryEnter(SlmRuntimeOwner.MODEL_UPGRADE)
                )
                var pause: SlmAppFlowPause? = null
                try {
                    pause = checkNotNull(
                        coordinator.tryPauseAdmissionAndDrainOthers(
                            SlmRuntimeOwner.MODEL_UPGRADE
                        )
                    )
                    handoffReady.complete(pause)
                    finishUpgrade.await()
                } finally {
                    pause?.release()
                    lease.release()
                }
            }
            runCurrent()

            assertTrue(coordinator.state.value.admissionPaused)
            assertFalse(handoffReady.isCompleted)
            assertEquals(2, coordinator.state.value.activeCount)
            assertNull(coordinator.tryEnter(SlmRuntimeOwner.SMS_WORKER))

            finishOldFlow.complete(Unit)
            runCurrent()
            val admissionPause = handoffReady.await()

            assertTrue(coordinator.state.value.admissionPaused)
            assertEquals(
                mapOf(SlmRuntimeOwner.MODEL_UPGRADE to 1),
                coordinator.state.value.activeByOwner
            )
            assertNull(coordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC))

            admissionPause.release()
            val newlyAdmitted = coordinator.tryEnter(SlmRuntimeOwner.SMS_WORKER)
            assertNotNull(newlyAdmitted)
            newlyAdmitted!!.release()
            finishUpgrade.complete(Unit)
            upgrade.join()
            oldFlow.join()
        }

    @Test
    fun `cancelled model upgrade handoff reopens admission without cancelling old flow`() =
        runTest {
            val coordinator = SlmAppFlowCoordinator()
            val oldFlowStarted = CompletableDeferred<Unit>()
            val finishOldFlow = CompletableDeferred<Unit>()
            val upgradeStarted = CompletableDeferred<Unit>()

            val oldFlow = launch {
                val lease = checkNotNull(
                    coordinator.tryEnter(SlmRuntimeOwner.HOME_SYNC)
                )
                oldFlowStarted.complete(Unit)
                try {
                    finishOldFlow.await()
                } finally {
                    lease.release()
                }
            }
            oldFlowStarted.await()

            val upgrade = launch {
                val lease = checkNotNull(
                    coordinator.tryEnter(SlmRuntimeOwner.MODEL_UPGRADE)
                )
                upgradeStarted.complete(Unit)
                try {
                    coordinator.tryPauseAdmissionAndDrainOthers(
                        SlmRuntimeOwner.MODEL_UPGRADE
                    )
                } finally {
                    lease.release()
                }
            }
            upgradeStarted.await()
            runCurrent()
            assertTrue(coordinator.state.value.admissionPaused)

            upgrade.cancel()
            upgrade.join()

            assertFalse(coordinator.state.value.admissionPaused)
            assertEquals(
                mapOf(SlmRuntimeOwner.HOME_SYNC to 1),
                coordinator.state.value.activeByOwner
            )
            val newlyAdmitted = coordinator.tryEnter(SlmRuntimeOwner.SMS_WORKER)
            assertNotNull(newlyAdmitted)
            newlyAdmitted!!.release()

            finishOldFlow.complete(Unit)
            oldFlow.join()
        }

    @Test
    fun `foreground service waits for model handoff before admission`() =
        runTest {
            val coordinator = SlmAppFlowCoordinator()
            val pause = checkNotNull(
                coordinator.tryPauseAndDrain(SlmRuntimeOwner.MODEL_UPGRADE)
            )
            val leaseReady = CompletableDeferred<Unit>()
            val finishService = CompletableDeferred<Unit>()

            val waitingService = launch {
                val lease =
                    coordinator.enterWhenAvailable(SlmRuntimeOwner.HOME_SYNC)
                leaseReady.complete(Unit)
                try {
                    finishService.await()
                } finally {
                    lease.release()
                }
            }
            runCurrent()
            assertFalse(leaseReady.isCompleted)

            pause.release()
            runCurrent()
            leaseReady.await()

            assertEquals(
                mapOf(SlmRuntimeOwner.HOME_SYNC to 1),
                coordinator.state.value.activeByOwner
            )
            finishService.complete(Unit)
            waitingService.join()
        }
}
