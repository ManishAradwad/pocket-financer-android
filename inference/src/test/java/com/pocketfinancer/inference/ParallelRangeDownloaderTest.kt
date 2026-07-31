package com.pocketfinancer.inference

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ParallelRangeDownloaderTest {
    private val testDirectory =
        Files.createTempDirectory("parallel-model-downloader-test").toFile()

    @After
    fun tearDown() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun `downloads exact chunks and scales beyond initial workers`() = runBlocking {
        val remote = ByteArray(96) { it.toByte() }
        val server = RangeServer(remote, slowReads = true)
        val part = File(testDirectory, "model.gguf.part")
        val progress = mutableListOf<ParallelDownloadProgress>()
        val downloader = ParallelRangeDownloader(
            connectionFactory = server,
            chunkSize = 8,
            initialParallelism = 2,
            maxParallelism = 4
        )

        val completed = downloader.download(
            url = TEST_URL,
            partFile = part,
            expectedTotalBytes = remote.size.toLong(),
            legacyPrefixBytes = 0L,
            onProgress = progress::add
        )

        assertTrue(completed)
        assertArrayEquals(remote, part.readBytes())
        assertTrue(server.maximumConcurrentGets.get() >= 3)
        assertTrue(server.requestedRanges.all { it.last - it.first + 1L == 8L })
        assertEquals(remote.indices.step(8).map(Int::toLong), server.requestedRanges.map { it.first }.sorted())
        assertFalse(File(testDirectory, "model.gguf.part.meta").exists())
        assertEquals(remote.size.toLong(), progress.last().downloadedBytes)
    }

    @Test
    fun `range rejection falls back without losing legacy prefix`() = runBlocking {
        val remote = ByteArray(32) { (it + 1).toByte() }
        val legacy = remote.copyOfRange(0, 7)
        val part = File(testDirectory, "model.gguf.part").apply { writeBytes(legacy) }
        val downloader = ParallelRangeDownloader(
            connectionFactory = RangeServer(remote, ignoreRanges = true),
            chunkSize = 8,
            initialParallelism = 2,
            maxParallelism = 4
        )

        val completed = downloader.download(
            url = TEST_URL,
            partFile = part,
            expectedTotalBytes = remote.size.toLong(),
            legacyPrefixBytes = legacy.size.toLong(),
            onProgress = {}
        )

        assertFalse(completed)
        assertArrayEquals(legacy, part.readBytes())
        assertFalse(File(testDirectory, "model.gguf.part.meta").exists())
    }

    private class RangeServer(
        private val remote: ByteArray,
        private val ignoreRanges: Boolean = false,
        private val slowReads: Boolean = false
    ) : HttpConnectionFactory {
        val requestedRanges = java.util.Collections.synchronizedList(mutableListOf<LongRange>())
        val maximumConcurrentGets = AtomicInteger(0)
        private val activeGets = AtomicInteger(0)

        override fun open(url: URL): HttpURLConnection =
            object : HttpURLConnection(url) {
                override fun connect() = Unit
                override fun disconnect() = Unit
                override fun usingProxy(): Boolean = false

                override fun getResponseCode(): Int {
                    if (requestMethod == "HEAD") return HTTP_OK
                    return if (ignoreRanges) HTTP_OK else HTTP_PARTIAL
                }

                override fun getContentLengthLong(): Long {
                    if (requestMethod == "HEAD") return remote.size.toLong()
                    val range = requestedRange()
                    return range.last - range.first + 1L
                }

                override fun getHeaderField(name: String?): String? {
                    if (name == "ETag") return "\"test-model\""
                    if (name != "Content-Range" || requestMethod == "HEAD" || ignoreRanges) {
                        return null
                    }
                    val range = requestedRange()
                    return "bytes " + range.first + "-" + range.last + "/" + remote.size
                }

                override fun getInputStream(): InputStream {
                    val range = requestedRange()
                    requestedRanges += range
                    val bytes = remote.copyOfRange(range.first.toInt(), range.last.toInt() + 1)
                    return object : ByteArrayInputStream(bytes) {
                        private var registered = false

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (!registered) {
                                registered = true
                                val active = activeGets.incrementAndGet()
                                maximumConcurrentGets.accumulateAndGet(active, ::maxOf)
                                if (slowReads) Thread.sleep(40)
                            }
                            val count = super.read(buffer, offset, length)
                            if (count == -1 && registered) {
                                registered = false
                                activeGets.decrementAndGet()
                            }
                            return count
                        }

                        override fun close() {
                            if (registered) {
                                registered = false
                                activeGets.decrementAndGet()
                            }
                            super.close()
                        }
                    }
                }

                private fun requestedRange(): LongRange {
                    val value = getRequestProperty("Range")
                        ?.removePrefix("bytes=")
                        ?: error("Expected Range request")
                    val start = value.substringBefore('-').toLong()
                    val end = value.substringAfter('-').toLong()
                    return start..end
                }
            }
    }

    private companion object {
        const val TEST_URL = "https://example.test/model.gguf"
    }
}
