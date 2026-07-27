package com.pocketfinancer.pipeline

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
