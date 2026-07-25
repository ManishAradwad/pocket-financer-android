package com.pocketfinancer.ui.onboarding

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundWorkNoticeTextTest {

    @Test
    fun `notification permission directs users to system progress`() {
        assertEquals(
            "You can switch apps and track progress in notifications.",
            backgroundWorkNoticeText(hasNotificationPermission = true)
        )
    }

    @Test
    fun `missing notification permission directs users back to the app`() {
        assertEquals(
            "You can switch apps and return to Pocket Financer to check progress.",
            backgroundWorkNoticeText(hasNotificationPermission = false)
        )
    }
}
