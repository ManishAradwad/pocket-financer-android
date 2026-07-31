package com.pocketfinancer.ui.model

import com.pocketfinancer.inference.ModelDownloader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelDownloadProgressTest {
    @Test
    fun `progress text includes percentage transfer speed and eta`() {
        val text = ModelDownloader.DownloadState(
            progress = 0.425f,
            downloadedMb = 425f,
            totalMb = 1_000f,
            speedMbps = 12.34f,
            etaSeconds = 3_661L
        ).toProgressText()

        assertEquals("43%", text.percentage)
        assertEquals("425.0 / 1000.0 MB", text.transferred)
        assertEquals("12.3 MB/s", text.speed)
        assertEquals("ETA: 1h 1m", text.eta)
    }

    @Test
    fun `unmeasured speed and eta stay hidden`() {
        val text = ModelDownloader.DownloadState(
            progress = 0.1f,
            downloadedMb = 10f,
            totalMb = 100f
        ).toProgressText()

        assertNull(text.speed)
        assertNull(text.eta)
    }

    @Test
    fun `eta formats seconds and minutes`() {
        assertEquals("42s", formatDownloadEta(42L))
        assertEquals("2m 5s", formatDownloadEta(125L))
    }
}
