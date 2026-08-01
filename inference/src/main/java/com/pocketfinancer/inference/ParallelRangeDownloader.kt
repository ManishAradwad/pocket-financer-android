package com.pocketfinancer.inference

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64
import java.util.Properties
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

internal data class ParallelDownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
    val speedBytesPerSecond: Long,
    val etaSeconds: Long
)

/**
 * Chunked ranged transfer used by [ModelDownloader].
 *
 * Returns false only when the origin ignores byte ranges, allowing the caller
 * to fall back to its sequential implementation. All other protocol failures
 * are surfaced rather than risking publication of an incomplete artifact.
 */
internal class ParallelRangeDownloader(
    private val connectionFactory: HttpConnectionFactory,
    private val chunkSize: Int = DEFAULT_CHUNK_SIZE,
    private val initialParallelism: Int = DEFAULT_INITIAL_PARALLELISM,
    private val maxParallelism: Int = DEFAULT_MAX_PARALLELISM
) {
    private data class RemoteIdentity(
        val totalBytes: Long,
        val validator: RemoteValidator?
    )

    private data class Manifest(
        val totalBytes: Long,
        val chunkSize: Int,
        val validator: RemoteValidator?,
        val completed: BooleanArray
    )

    private data class RemoteValidator(
        val header: ValidatorHeader,
        val value: String
    )

    private enum class ValidatorHeader(val httpName: String) {
        ETAG("ETag"),
        X_LINKED_ETAG("X-Linked-Etag"),
        LAST_MODIFIED("Last-Modified")
    }

    suspend fun download(
        url: String,
        partFile: File,
        expectedTotalBytes: Long,
        legacyPrefixBytes: Long,
        onProgress: (ParallelDownloadProgress) -> Unit
    ): Boolean {
        val remote = fetchIdentity(url, expectedTotalBytes)
        val metadataFile = File(partFile.parentFile, partFile.name + ".meta")
        val manifest = loadOrCreateManifest(
            partFile = partFile,
            metadataFile = metadataFile,
            remote = remote,
            legacyPrefixBytes = legacyPrefixBytes
        )
        if (manifest.completed.all { it }) {
            RandomAccessFile(partFile, "rw").use { it.channel.force(true) }
            metadataFile.delete()
            return true
        }

        try {
            RandomAccessFile(partFile, "rw").use { randomFile ->
                if (randomFile.length() != remote.totalBytes) {
                    randomFile.setLength(remote.totalBytes)
                }
                val channel = randomFile.channel
                val committed = AtomicLong(completedBytes(manifest))
                val inFlight = AtomicLong(0L)
                val networkBytes = AtomicLong(0L)
                val startTime = System.currentTimeMillis()
                val lastProgressTime = AtomicLong(0L)
                val manifestLock = Any()

                suspend fun runBatch(workerCount: Int, claimLimit: Int) {
                    val queue = ConcurrentLinkedQueue<Int>()
                    manifest.completed.indices
                        .filterNot { manifest.completed[it] }
                        .forEach(queue::add)
                    val claims = AtomicInteger(0)
                    val failure = AtomicReference<Throwable?>(null)
                    supervisorScope {
                        val jobs = List(minOf(workerCount, queue.size.coerceAtLeast(1))) {
                            launch(Dispatchers.IO) {
                                while (failure.get() == null) {
                                    currentCoroutineContext().ensureActive()
                                    if (claims.getAndIncrement() >= claimLimit) break
                                    val index = queue.poll() ?: break
                                    try {
                                        downloadChunk(
                                            url = url,
                                            remote = remote,
                                            manifest = manifest,
                                            index = index,
                                            channel = channel,
                                            committed = committed,
                                            inFlight = inFlight,
                                            networkBytes = networkBytes,
                                            startTime = startTime,
                                            lastProgressTime = lastProgressTime,
                                            onProgress = onProgress
                                        )
                                        synchronized(manifestLock) {
                                            channel.force(false)
                                            manifest.completed[index] = true
                                            persistManifest(metadataFile, manifest)
                                            committed.addAndGet(chunkLength(manifest, index))
                                        }
                                    } catch (error: Throwable) {
                                        failure.compareAndSet(null, error)
                                    }
                                }
                            }
                        }
                        jobs.forEach { it.join() }
                    }
                    failure.get()?.let { throw it }
                }

                var retryCount = 0
                while (true) {
                    try {
                        runBatch(initialParallelism, initialParallelism)
                        break
                    } catch (unsupported: RangeUnsupportedException) {
                        throw unsupported
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (permanent: PermanentRangeException) {
                        throw permanent
                    } catch (error: IOException) {
                        retryCount += 1
                        if (retryCount > MAX_BATCH_RETRIES) throw error
                        delay(backoffMillis(retryCount))
                    }
                }

                var workers = maxParallelism
                retryCount = 0
                while (manifest.completed.any { !it }) {
                    try {
                        runBatch(workers, Int.MAX_VALUE)
                        retryCount = 0
                    } catch (unsupported: RangeUnsupportedException) {
                        throw unsupported
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (permanent: PermanentRangeException) {
                        throw permanent
                    } catch (error: IOException) {
                        retryCount += 1
                        if (retryCount > MAX_BATCH_RETRIES) throw error
                        workers = (workers - 1).coerceAtLeast(1)
                        delay(backoffMillis(retryCount))
                    }
                }
                channel.force(true)
                emitProgress(
                    committed = committed.get(),
                    inFlight = 0L,
                    total = remote.totalBytes,
                    networkBytes = networkBytes.get(),
                    startTime = startTime,
                    lastProgressTime = lastProgressTime,
                    force = true,
                    onProgress = onProgress
                )
            }
        } catch (_: RangeUnsupportedException) {
            metadataFile.delete()
            RandomAccessFile(partFile, "rw").use {
                it.setLength(legacyPrefixBytes.coerceAtMost(expectedTotalBytes))
            }
            return false
        }

        metadataFile.delete()
        return true
    }

    private suspend fun downloadChunk(
        url: String,
        remote: RemoteIdentity,
        manifest: Manifest,
        index: Int,
        channel: java.nio.channels.FileChannel,
        committed: AtomicLong,
        inFlight: AtomicLong,
        networkBytes: AtomicLong,
        startTime: Long,
        lastProgressTime: AtomicLong,
        onProgress: (ParallelDownloadProgress) -> Unit
    ) {
        val start = index.toLong() * manifest.chunkSize
        val end = (start + manifest.chunkSize - 1L).coerceAtMost(remote.totalBytes - 1L)
        val expectedBytes = end - start + 1L
        val connection = openConnectionWithRedirects(
            urlString = url,
            method = "GET",
            rangeStart = start,
            rangeEnd = end,
            validator = remote.validator?.value
        )
        var attemptBytes = 0L
        try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> throw RangeUnsupportedException()
                HttpURLConnection.HTTP_PARTIAL -> Unit
                else -> throw if (code == 408 || code == 429 || code >= 500) {
                    IOException("Server returned transient HTTP " + code + " for a model range.")
                } else {
                    PermanentRangeException("Server returned HTTP " + code + " for a model range.")
                }
            }
            val contentRange = connection.getHeaderField("Content-Range")
                ?.let(::parseContentRange)
                ?: throw PermanentRangeException(
                    "Server omitted Content-Range for a ranged model request."
                )
            if (contentRange.first != start ||
                contentRange.second != end ||
                contentRange.third != remote.totalBytes
            ) {
                throw PermanentRangeException("Server returned an inconsistent model byte range.")
            }
            remote.validator?.let { expectedValidator ->
                val responseValidator = connection.getHeaderField(
                    expectedValidator.header.httpName
                )
                if (responseValidator == null ||
                    responseValidator != expectedValidator.value
                ) {
                    throw PermanentRangeException(
                        "Remote model identity changed during download."
                    )
                }
            }

            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                var position = start
                while (attemptBytes < expectedBytes) {
                    currentCoroutineContext().ensureActive()
                    val requested = minOf(
                        buffer.size.toLong(),
                        expectedBytes - attemptBytes
                    ).toInt()
                    val bytesRead = input.read(buffer, 0, requested)
                    if (bytesRead == -1) break
                    val byteBuffer = ByteBuffer.wrap(buffer, 0, bytesRead)
                    while (byteBuffer.hasRemaining()) {
                        val written = channel.write(byteBuffer, position)
                        position += written
                    }
                    attemptBytes += bytesRead
                    inFlight.addAndGet(bytesRead.toLong())
                    networkBytes.addAndGet(bytesRead.toLong())
                    emitProgress(
                        committed = committed.get(),
                        inFlight = inFlight.get(),
                        total = remote.totalBytes,
                        networkBytes = networkBytes.get(),
                        startTime = startTime,
                        lastProgressTime = lastProgressTime,
                        force = false,
                        onProgress = onProgress
                    )
                }
            }
            if (attemptBytes != expectedBytes) {
                throw IOException(
                    "Model range " + start + "-" + end + " ended after " +
                        attemptBytes + " of " + expectedBytes + " bytes."
                )
            }
        } catch (timeout: SocketTimeoutException) {
            throw IOException("Timed out while downloading model range.", timeout)
        } finally {
            inFlight.addAndGet(-attemptBytes)
            connection.disconnect()
        }
    }

    private fun emitProgress(
        committed: Long,
        inFlight: Long,
        total: Long,
        networkBytes: Long,
        startTime: Long,
        lastProgressTime: AtomicLong,
        force: Boolean,
        onProgress: (ParallelDownloadProgress) -> Unit
    ) {
        val now = System.currentTimeMillis()
        val previous = lastProgressTime.get()
        if (!force && now - previous < PROGRESS_INTERVAL_MS) return
        if (!force && !lastProgressTime.compareAndSet(previous, now)) return
        if (force) lastProgressTime.set(now)
        val visible = (committed + inFlight).coerceIn(0L, total)
        val elapsed = (now - startTime).coerceAtLeast(1L)
        val speed = (networkBytes * 1000.0 / elapsed).toLong()
        onProgress(
            ParallelDownloadProgress(
                downloadedBytes = visible,
                totalBytes = total,
                speedBytesPerSecond = speed,
                etaSeconds = if (speed > 0L) (total - visible) / speed else 0L
            )
        )
    }

    private fun fetchIdentity(url: String, expectedTotalBytes: Long): RemoteIdentity {
        val connection = openConnectionWithRedirects(url, method = "HEAD")
        try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IOException("Server returned HTTP " + code + " for model metadata.")
            }
            val total = connection.contentLengthLong
            if (total > 0L && total != expectedTotalBytes) {
                throw PermanentRangeException("Remote model size changed before download.")
            }
            val validator = VALIDATOR_HEADERS.firstNotNullOfOrNull { header ->
                connection.getHeaderField(header.httpName)?.let { value ->
                    RemoteValidator(header, value)
                }
            }
            return RemoteIdentity(expectedTotalBytes, validator)
        } finally {
            connection.disconnect()
        }
    }

    private fun openConnectionWithRedirects(
        urlString: String,
        method: String,
        rangeStart: Long? = null,
        rangeEnd: Long? = null,
        validator: String? = null
    ): HttpURLConnection {
        var currentUrl = urlString
        repeat(MAX_REDIRECTS) {
            val connection = connectionFactory.open(URL(currentUrl))
            connection.requestMethod = method
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept-Encoding", "identity")
            if (rangeStart != null) {
                connection.setRequestProperty("Range", "bytes=" + rangeStart + "-" + rangeEnd)
                validator?.let { connection.setRequestProperty("If-Range", it) }
            }
            if (connection.responseCode.isRedirect()) {
                val location = connection.getHeaderField("Location")
                connection.disconnect()
                if (location == null) {
                    throw IOException("Redirect received without Location header.")
                }
                currentUrl = URL(URL(currentUrl), location).toString()
            } else {
                return connection
            }
        }
        throw IOException("Too many redirects while downloading model.")
    }

    private fun loadOrCreateManifest(
        partFile: File,
        metadataFile: File,
        remote: RemoteIdentity,
        legacyPrefixBytes: Long
    ): Manifest {
        readManifest(metadataFile)?.let { existing ->
            if (existing.totalBytes == remote.totalBytes &&
                existing.chunkSize == chunkSize &&
                existing.validator == remote.validator &&
                partFile.isFile
            ) {
                return existing
            }
            metadataFile.delete()
            RandomAccessFile(partFile, "rw").use { it.setLength(0L) }
        }

        val chunkCount = ((remote.totalBytes + chunkSize - 1L) / chunkSize).toInt()
        val completed = BooleanArray(chunkCount)
        val prefix = legacyPrefixBytes.coerceIn(0L, remote.totalBytes)
        for (index in completed.indices) {
            val endExclusive = minOf((index + 1L) * chunkSize, remote.totalBytes)
            completed[index] = endExclusive <= prefix
        }
        val manifest = Manifest(remote.totalBytes, chunkSize, remote.validator, completed)
        // The manifest must exist before preallocation. Otherwise file length
        // could be mistaken for completed content after a process death.
        persistManifest(metadataFile, manifest)
        return manifest
    }

    private fun readManifest(file: File): Manifest? {
        if (!file.isFile) return null
        return runCatching {
            val properties = Properties().apply { file.inputStream().use(::load) }
            val total = properties.getProperty("totalBytes").toLong()
            val storedChunkSize = properties.getProperty("chunkSize").toInt()
            val count = ((total + storedChunkSize - 1L) / storedChunkSize).toInt()
            val completed = BooleanArray(count)
            properties.getProperty("completed", "")
                .split(',')
                .mapNotNull(String::toIntOrNull)
                .filter { it in completed.indices }
                .forEach { completed[it] = true }
            val validator = properties.getProperty("validator")
                ?.takeIf { it.isNotEmpty() }
                ?.let { encodedValue ->
                    RemoteValidator(
                        header = ValidatorHeader.valueOf(
                            properties.getProperty("validatorHeader")
                        ),
                        value = String(
                            Base64.getDecoder().decode(encodedValue),
                            StandardCharsets.UTF_8
                        )
                    )
                }
            Manifest(total, storedChunkSize, validator, completed)
        }.getOrNull()
    }

    private fun persistManifest(file: File, manifest: Manifest) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        val properties = Properties().apply {
            setProperty("totalBytes", manifest.totalBytes.toString())
            setProperty("chunkSize", manifest.chunkSize.toString())
            setProperty(
                "validator",
                manifest.validator?.let {
                    Base64.getEncoder().encodeToString(
                        it.value.toByteArray(StandardCharsets.UTF_8)
                    )
                }.orEmpty()
            )
            setProperty("validatorHeader", manifest.validator?.header?.name.orEmpty())
            setProperty(
                "completed",
                manifest.completed.indices
                    .filter { manifest.completed[it] }
                    .joinToString(",")
            )
        }
        FileOutputStream(temporary).use { output ->
            properties.store(output, null)
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }

    private fun completedBytes(manifest: Manifest): Long =
        manifest.completed.indices.sumOf { index ->
            if (manifest.completed[index]) chunkLength(manifest, index) else 0L
        }

    private fun chunkLength(manifest: Manifest, index: Int): Long {
        val start = index.toLong() * manifest.chunkSize
        return (manifest.totalBytes - start).coerceAtMost(manifest.chunkSize.toLong())
    }

    private fun parseContentRange(value: String): Triple<Long, Long, Long>? {
        val match = CONTENT_RANGE.matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: -1L
        return if (end >= start) Triple(start, end, total) else null
    }

    private fun Int.isRedirect(): Boolean =
        this == HttpURLConnection.HTTP_MOVED_PERM ||
            this == HttpURLConnection.HTTP_MOVED_TEMP ||
            this == HttpURLConnection.HTTP_SEE_OTHER ||
            this == 307 ||
            this == 308

    private fun backoffMillis(retry: Int): Long =
        RETRY_BASE_DELAY_MS * (1L shl (retry - 1))

    private class RangeUnsupportedException : IOException()
    private class PermanentRangeException(message: String) : IOException(message)

    private companion object {
        const val DEFAULT_CHUNK_SIZE = 8 * 1024 * 1024
        const val DEFAULT_INITIAL_PARALLELISM = 2
        const val DEFAULT_MAX_PARALLELISM = 4
        const val MAX_BATCH_RETRIES = 4
        const val RETRY_BASE_DELAY_MS = 500L
        const val BUFFER_SIZE = 128 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
        const val MAX_REDIRECTS = 5
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val USER_AGENT = "PocketFinancer/1.0"
        val VALIDATOR_HEADERS = listOf(
            ValidatorHeader.ETAG,
            ValidatorHeader.X_LINKED_ETAG,
            ValidatorHeader.LAST_MODIFIED
        )
        val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
    }
}
