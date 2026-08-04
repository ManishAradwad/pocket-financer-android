package com.pocketfinancer.ui.home

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelUpgradeSessionDismissalStoreTest {
    @Test
    fun `dismissal survives observers but remains scoped to one store instance`() {
        val session = ModelUpgradeSessionDismissalStore()

        session.dismiss("model-a")

        assertTrue("model-a" in session.dismissedTierIds.value)
        assertFalse("model-b" in session.dismissedTierIds.value)
        assertEquals(emptySet<String>(), ModelUpgradeSessionDismissalStore().dismissedTierIds.value)
    }

    @Test
    fun `dismissing one target does not hide a newer recommendation`() {
        val session = ModelUpgradeSessionDismissalStore()

        session.dismiss("model-a")
        session.dismiss("model-a")

        assertEquals(setOf("model-a"), session.dismissedTierIds.value)
        assertFalse("model-b" in session.dismissedTierIds.value)
    }

    @Test
    fun `concurrent dismissals preserve every target`() = runBlocking {
        val session = ModelUpgradeSessionDismissalStore()
        val targetIds = (0 until 512).map { "model-$it" }
        val start = CompletableDeferred<Unit>()
        val jobs = targetIds.map { targetId ->
            launch(Dispatchers.Default) {
                start.await()
                session.dismiss(targetId)
            }
        }

        start.complete(Unit)
        jobs.joinAll()

        assertEquals(targetIds.toSet(), session.dismissedTierIds.value)
    }
}
