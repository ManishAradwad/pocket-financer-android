package com.pocketfinancer.ui.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalFinancialEraseBoundaryTest {

    @Test
    fun `view model cancellation cannot publish first run before erase completes`() =
        runTest {
            val eraseStarted = CompletableDeferred<Unit>()
            val allowEraseToFinish = CompletableDeferred<Unit>()
            val actions = mutableListOf<String>()
            var committed = false

            val job = launch {
                runLocalFinancialEraseCriticalSection(
                    beginDurableErase = {
                        actions += "begin"
                    },
                    onDurableEraseStarted = {
                        actions += "started"
                    },
                    clearEncryptedData = {
                        actions += "clear"
                        eraseStarted.complete(Unit)
                        allowEraseToFinish.await()
                    },
                    cancelFinancialNotifications = {
                        actions += "notifications"
                    },
                    resetInMemoryState = {
                        actions += "memory"
                    },
                    commitSetupReset = {
                        actions += "first_run"
                    },
                    onCommitted = {
                        committed = true
                    }
                )
            }

            eraseStarted.await()
            job.cancel()
            allowEraseToFinish.complete(Unit)
            job.join()

            assertEquals(
                listOf(
                    "begin",
                    "started",
                    "clear",
                    "notifications",
                    "memory",
                    "first_run"
                ),
                actions
            )
            assertTrue(committed)
            assertTrue(job.isCancelled)
        }

    @Test
    fun `ancillary cleanup failure cannot strand final setup reset after database clear`() =
        runTest {
            val actions = mutableListOf<String>()

            val failure = runLocalFinancialEraseCriticalSection(
                beginDurableErase = { actions += "begin" },
                onDurableEraseStarted = { actions += "started" },
                clearEncryptedData = { actions += "clear" },
                cancelFinancialNotifications = {
                    actions += "notifications"
                    error("notification service unavailable")
                },
                resetInMemoryState = { actions += "memory" },
                commitSetupReset = { actions += "first_run" },
                onCommitted = { actions += "committed" }
            )

            assertEquals(
                listOf(
                    "begin",
                    "started",
                    "clear",
                    "notifications",
                    "memory",
                    "first_run",
                    "committed"
                ),
                actions
            )
            assertNotNull(failure)
            assertTrue(failure!!.message!!.contains("notification service"))
        }
}
