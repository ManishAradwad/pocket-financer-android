package com.pocketfinancer.inference

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloaderTest {
    private val testDirectory =
        Files.createTempDirectory("model-downloader-test").toFile()

    @After
    fun tearDown() {
        testDirectory.deleteRecursively()
    }

    @Test
    fun `exact-size existing final is a no-op`() = runBlocking {
        val remote = "complete-model".toByteArray()
        val final = File(testDirectory, "model.gguf").apply { writeBytes(remote) }
        val server = FakeServer(remote)
        val downloader = ModelDownloader(server)

        val result = downloader.download(TEST_URL, final)

        assertTrue(result.isSuccess)
        assertArrayEquals(remote, final.readBytes())
        assertEquals(0, server.getRequests)
        assertFalse(File(testDirectory, "model.gguf.part").exists())
        assertTrue(downloader.state.value.isComplete)
    }

    @Test
    fun `cached final can proceed to native validation while offline`() = runBlocking {
        val existing = "cached-gguf-candidate".toByteArray()
        val partial = "unrelated-partial".toByteArray()
        val final = File(testDirectory, "model.gguf").apply { writeBytes(existing) }
        val part = File(testDirectory, "model.gguf.part").apply { writeBytes(partial) }
        var connectionAttempts = 0
        val downloader = ModelDownloader(
            HttpConnectionFactory {
                connectionAttempts += 1
                throw AssertionError("Cached native validation must not use the network.")
            }
        )

        val result = downloader.prepareForNativeValidation(TEST_URL, final)

        assertTrue(result.isSuccess)
        assertEquals(final.absolutePath, result.getOrThrow())
        assertEquals(0, connectionAttempts)
        assertArrayEquals(existing, final.readBytes())
        assertArrayEquals(partial, part.readBytes())
        assertTrue(downloader.state.value.isComplete)
    }

    @Test
    fun `mismatched existing final fails without changing it`() = runBlocking {
        val existing = "existing".toByteArray()
        val final = File(testDirectory, "model.gguf").apply { writeBytes(existing) }
        val server = FakeServer("different-remote-model".toByteArray())
        val downloader = ModelDownloader(server)

        val result = downloader.download(TEST_URL, final)

        assertTrue(result.isFailure)
        assertArrayEquals(existing, final.readBytes())
        assertEquals(0, server.getRequests)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("left unchanged"))
    }

    @Test
    fun `unknown remote size cannot validate or replace existing final`() = runBlocking {
        val existing = "existing".toByteArray()
        val final = File(testDirectory, "model.gguf").apply { writeBytes(existing) }
        val server = FakeServer(
            remoteBytes = existing,
            headLength = -1L
        )
        val downloader = ModelDownloader(server)

        val result = downloader.download(TEST_URL, final)

        assertTrue(result.isFailure)
        assertArrayEquals(existing, final.readBytes())
        assertEquals(0, server.getRequests)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("cannot be verified"))
    }

    @Test
    fun `new download is staged then atomically published`() = runBlocking {
        val remote = "new-complete-model".toByteArray()
        val final = File(testDirectory, "model.gguf")
        val part = File(testDirectory, "model.gguf.part")
        val downloader = ModelDownloader(FakeServer(remote))

        val result = downloader.download(TEST_URL, final)

        assertTrue(result.isSuccess)
        assertArrayEquals(remote, final.readBytes())
        assertFalse(part.exists())
        assertEquals(final.absolutePath, result.getOrThrow())
    }

    @Test
    fun `partial artifact resumes without writing final until completion`() = runBlocking {
        val remote = "resumable-model".toByteArray()
        val final = File(testDirectory, "model.gguf")
        val part = File(testDirectory, "model.gguf.part").apply {
            writeBytes(remote.copyOfRange(0, 5))
        }
        val server = FakeServer(remote)
        val downloader = ModelDownloader(server)

        val result = downloader.download(TEST_URL, final)

        assertTrue(result.isSuccess)
        assertArrayEquals(remote, final.readBytes())
        assertFalse(part.exists())
        assertEquals(listOf(5L), server.requestedStarts)
    }

    @Test
    fun `active owner cancellation retains partial and never publishes final`() = runBlocking {
        val remote = "resumable-model".toByteArray()
        val initialPart = remote.copyOfRange(0, 5)
        val final = File(testDirectory, "model.gguf")
        val part = File(testDirectory, "model.gguf.part").apply {
            writeBytes(initialPart)
        }
        val blockingInput = BlockingInputStream(remote.copyOfRange(5, remote.size))
        val server = FakeServer(
            remoteBytes = remote,
            inputFactory = { blockingInput }
        )
        val downloader = ModelDownloader(server)

        val download = launch(Dispatchers.Default) {
            downloader.download(TEST_URL, final)
        }
        assertTrue(blockingInput.readEntered.await(5, TimeUnit.SECONDS))

        downloader.cancel(download)
        blockingInput.allowRead.countDown()
        download.join()

        assertTrue(download.isCancelled)
        assertFalse(final.exists())
        assertArrayEquals(initialPart, part.readBytes())
        assertEquals("Download cancelled", downloader.state.value.error)
    }

    @Test
    fun `cancelling queued upgrade does not cancel active settings download`() = runBlocking {
        val remote = "settings-owned-model".toByteArray()
        val settingsFinal = File(testDirectory, "settings-model.gguf")
        val upgradeFinal = File(testDirectory, "upgrade-model.gguf")
        val blockingInput = BlockingInputStream(remote)
        val downloader = ModelDownloader(
            FakeServer(
                remoteBytes = remote,
                inputFactory = { blockingInput }
            )
        )

        val settingsDownload = launch(Dispatchers.Default) {
            downloader.download(TEST_URL, settingsFinal)
        }
        assertTrue(blockingInput.readEntered.await(5, TimeUnit.SECONDS))

        // UNDISPATCHED reaches the locked mutex before returning, proving this
        // request is queued behind the Settings-owned download.
        val queuedUpgrade = launch(
            context = Dispatchers.Default,
            start = CoroutineStart.UNDISPATCHED
        ) {
            downloader.prepareForNativeValidation(TEST_URL, upgradeFinal)
        }

        try {
            downloader.cancel(queuedUpgrade)
            queuedUpgrade.join()

            assertTrue(queuedUpgrade.isCancelled)
            assertTrue(settingsDownload.isActive)
            assertTrue(downloader.state.value.isDownloading)
            assertFalse(settingsFinal.exists())
            assertFalse(upgradeFinal.exists())
        } finally {
            blockingInput.allowRead.countDown()
        }
        settingsDownload.join()

        assertArrayEquals(remote, settingsFinal.readBytes())
        assertFalse(upgradeFinal.exists())
        assertTrue(downloader.state.value.isComplete)
    }

    @Test
    fun `cancellation token stops owning job only while downloader request is pending`() =
        runBlocking {
            val remote = "completed-model".toByteArray()
            val final = File(testDirectory, "completed-model.gguf")
            val requestCompleted = CompletableDeferred<Unit>()
            val finishOwnerWork = CompletableDeferred<Unit>()
            val downloader = ModelDownloader(FakeServer(remote))

            val ownerJob = launch(Dispatchers.Default) {
                downloader.download(TEST_URL, final)
                requestCompleted.complete(Unit)
                finishOwnerWork.await()
            }
            requestCompleted.await()

            downloader.cancel(ownerJob)

            assertTrue(ownerJob.isActive)
            finishOwnerWork.complete(Unit)
            ownerJob.join()
            assertFalse(ownerJob.isCancelled)
        }

    @Test
    fun `final appearing before promotion is preserved and partial remains`() = runBlocking {
        val remote = "new-model".toByteArray()
        val existing = "other-owner-model".toByteArray()
        val final = File(testDirectory, "model.gguf")
        val part = File(testDirectory, "model.gguf.part")
        val input = FinalCreatingInputStream(remote) {
            final.writeBytes(existing)
        }
        val downloader = ModelDownloader(
            FakeServer(
                remoteBytes = remote,
                inputFactory = { input }
            )
        )

        val result = downloader.download(TEST_URL, final)

        assertTrue(result.isFailure)
        assertArrayEquals(existing, final.readBytes())
        assertArrayEquals(remote, part.readBytes())
    }

    private class FakeServer(
        private val remoteBytes: ByteArray,
        private val headLength: Long = remoteBytes.size.toLong(),
        private val inputFactory: (Long) -> InputStream = { start ->
            ByteArrayInputStream(
                remoteBytes.copyOfRange(start.toInt(), remoteBytes.size)
            )
        }
    ) : HttpConnectionFactory {
        var getRequests: Int = 0
            private set
        val requestedStarts = mutableListOf<Long>()

        override fun open(url: URL): HttpURLConnection =
            object : HttpURLConnection(url) {
                private var responseCounted = false

                override fun connect() = Unit

                override fun disconnect() = Unit

                override fun usingProxy(): Boolean = false

                override fun getResponseCode(): Int {
                    if (requestMethod == "HEAD") return HTTP_OK
                    if (!responseCounted) {
                        getRequests += 1
                        responseCounted = true
                    }
                    return if (requestedStart() > 0L) HTTP_PARTIAL else HTTP_OK
                }

                override fun getContentLengthLong(): Long {
                    if (requestMethod == "HEAD") return headLength
                    return (remoteBytes.size - requestedStart()).toLong()
                }

                override fun getHeaderField(name: String?): String? {
                    if (name != "Content-Range" || requestMethod == "HEAD") return null
                    val start = requestedStart()
                    if (start <= 0L) return null
                    return "bytes $start-${remoteBytes.lastIndex}/${remoteBytes.size}"
                }

                override fun getInputStream(): InputStream {
                    val start = requestedStart()
                    requestedStarts += start
                    return inputFactory(start)
                }

                private fun requestedStart(): Long =
                    getRequestProperty("Range")
                        ?.removePrefix("bytes=")
                        ?.substringBefore('-')
                        ?.toLongOrNull()
                        ?: 0L
            }
    }

    private class BlockingInputStream(
        private val bytes: ByteArray
    ) : InputStream() {
        val readEntered = CountDownLatch(1)
        val allowRead = CountDownLatch(1)
        private var delivered = false

        override fun read(): Int {
            val one = ByteArray(1)
            val count = read(one, 0, 1)
            return if (count < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            readEntered.countDown()
            allowRead.await()
            if (delivered) return -1
            delivered = true
            val count = minOf(length, bytes.size)
            bytes.copyInto(buffer, offset, 0, count)
            return count
        }
    }

    private class FinalCreatingInputStream(
        bytes: ByteArray,
        private val onEof: () -> Unit
    ) : ByteArrayInputStream(bytes) {
        private var created = false

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            val count = super.read(buffer, offset, length)
            if (count == -1 && !created) {
                created = true
                onEof()
            }
            return count
        }
    }

    private companion object {
        const val TEST_URL = "https://example.test/model.gguf"
    }
}
