package com.pocketfinancer.inference

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ModelDownloaderAndroidFilesystemTest {

    @Test
    fun freshDownloadPublishesFinalArtifactOnAppPrivateFilesystem() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testDirectory = File(
            context.cacheDir,
            "model-downloader-publication-${System.nanoTime()}"
        )
        assertTrue(testDirectory.mkdirs())

        try {
            val remoteBytes = "tiny-complete-model".toByteArray()
            val finalFile = File(testDirectory, "model.gguf")
            val partFile = File(testDirectory, "model.gguf.part")
            val downloader = ModelDownloader(TinyHttpConnectionFactory(remoteBytes))

            assertFalse(finalFile.exists())
            assertFalse(partFile.exists())

            val result = downloader.download(TEST_URL, finalFile)

            assertTrue(
                "Publication failed on the Android app-private filesystem: " +
                    result.exceptionOrNull(),
                result.isSuccess
            )
            assertTrue(finalFile.isFile)
            assertArrayEquals(remoteBytes, finalFile.readBytes())
            assertFalse(partFile.exists())
            assertTrue(downloader.state.value.isComplete)
        } finally {
            testDirectory.deleteRecursively()
        }
    }

    private class TinyHttpConnectionFactory(
        private val remoteBytes: ByteArray
    ) : HttpConnectionFactory {
        override fun open(url: URL): HttpURLConnection =
            object : HttpURLConnection(url) {
                override fun connect() = Unit

                override fun disconnect() = Unit

                override fun usingProxy(): Boolean = false

                override fun getResponseCode(): Int = HTTP_OK

                override fun getContentLengthLong(): Long =
                    remoteBytes.size.toLong()

                override fun getInputStream() =
                    ByteArrayInputStream(remoteBytes)
            }
    }

    private companion object {
        const val TEST_URL = "https://example.test/model.gguf"
    }
}
