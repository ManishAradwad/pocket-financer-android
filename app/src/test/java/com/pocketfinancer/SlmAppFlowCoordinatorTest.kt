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
}
