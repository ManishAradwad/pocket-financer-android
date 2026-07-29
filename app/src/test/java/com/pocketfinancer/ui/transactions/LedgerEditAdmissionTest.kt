package com.pocketfinancer.ui.transactions

import com.pocketfinancer.SlmAppFlowCoordinator
import com.pocketfinancer.inference.SlmRuntimeOwner
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LedgerEditAdmissionTest {
    @Test
    fun `erase pause cancels and drains an admitted ledger edit`() = runTest {
        val coordinator = SlmAppFlowCoordinator()
        val editStarted = CompletableDeferred<Unit>()
        var reachedAfterSuspension = false

        val edit = launch {
            withLedgerEditAdmission(coordinator) {
                editStarted.complete(Unit)
                awaitCancellation()
                reachedAfterSuspension = true
            }
        }
        editStarted.await()

        val pause = coordinator.tryPauseAndDrain(
            SlmRuntimeOwner.SETTINGS_MANUAL
        )

        assertTrue(edit.isCancelled)
        assertFalse(reachedAfterSuspension)
        assertTrue(coordinator.state.value.admissionPaused)
        pause!!.release()
    }

    @Test
    fun `ledger edits are rejected while erase owns admission`() = runTest {
        val coordinator = SlmAppFlowCoordinator()
        val pause = coordinator.tryPauseAndDrain(
            SlmRuntimeOwner.SETTINGS_MANUAL
        )!!
        var mutated = false

        val result = withLedgerEditAdmission(coordinator) {
            mutated = true
        }
        runCurrent()

        assertNull(result)
        assertFalse(mutated)
        pause.release()
    }
}
