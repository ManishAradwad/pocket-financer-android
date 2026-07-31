package com.pocketfinancer.ui.home

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
}
