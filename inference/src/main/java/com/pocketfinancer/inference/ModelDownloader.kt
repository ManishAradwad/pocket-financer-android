package com.pocketfinancer.inference

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.util.IdentityHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun interface HttpConnectionFactory {
    fun open(url: URL): HttpURLConnection
}

/**
 * Downloads GGUF model files from HuggingFace with progress tracking.
 *
 * Downloaded bytes are written only to a same-directory `.part` file. The
 * final model path appears atomically after the complete byte count has been
 * verified. An existing final artifact is never truncated, appended to, or
 * replaced by this class.
 */
@Singleton
class ModelDownloader {
    data class DownloadState(
        val isDownloading: Boolean = false,
        val progress: Float = 0f,
        val downloadedMb: Float = 0f,
        val totalMb: Float = 0f,
        val speedMbps: Float = 0f,
        val etaSeconds: Long = 0,
        val error: String? = null,
        val isComplete: Boolean = false,
        val outputPath: String? = null
    )

    private val connectionFactory: HttpConnectionFactory

    @Inject
    constructor() {
        connectionFactory = HttpConnectionFactory { url ->
            url.openConnection() as HttpURLConnection
        }
    }

    internal constructor(connectionFactory: HttpConnectionFactory) {
        this.connectionFactory = connectionFactory
    }

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    // ModelDownloader is a process-wide Hilt singleton. Serializing here keeps
    // its progress/cancellation state and filesystem promotion single-owner
    // even if two UI/service callers reach it at the same time.
    private val downloadMutex = Mutex()

    // Identity, rather than Job equality, is the cancellation authority. A
    // request is registered before waiting on downloadMutex so a queued owner
    // can cancel itself without affecting the active owner.
    private val requestJobsLock = Any()
    private val requestJobs = IdentityHashMap<Job, Int>()

    /**
     * Download [url] to [destFile] without ever writing to [destFile].
     *
     * If the server reports a size and the final artifact already has that
     * exact size, this is a no-op. Any other existing final artifact is left
     * untouched and reported as an unsupported replacement. Interrupted work
     * remains in `<filename>.part` for a later resume.
     */
    suspend fun download(url: String, destFile: File): Result<String> =
        withDownloadRequest {
            downloadMutex.withLock {
                withContext(Dispatchers.IO) {
                    _state.value = DownloadState(isDownloading = true, progress = 0f)

                    try {
                        val finalFile = destFile.absoluteFile
                        val parent = finalFile.parentFile
                            ?: throw IOException("Model destination has no parent directory.")
                        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                            throw IOException(
                                "Could not create model directory: ${parent.absolutePath}"
                            )
                        }
                        if (!parent.isDirectory) {
                            throw IOException("Model destination parent is not a directory.")
                        }

                        val partFile = File(parent, "${finalFile.name}.part")
                        val remoteSize = fetchContentLength(url)

                        existingFinalResult(finalFile, remoteSize)?.let { existing ->
                            _state.value = completedState(finalFile, finalFile.length())
                            return@withContext Result.success(existing)
                        }

                        if (finalFile.exists()) {
                            throw existingArtifactError(finalFile, remoteSize)
                        }

                        val startByte = when {
                            !partFile.exists() -> 0L
                            remoteSize > 0L && partFile.length() <= remoteSize ->
                                partFile.length()
                            remoteSize <= 0L -> partFile.length()
                            // A partial artifact is disposable; a final artifact is
                            // not. A response from byte zero safely refreshes only
                            // this private staging path.
                            else -> 0L
                        }

                        if (remoteSize > 0L && startByte == remoteSize) {
                            promoteCompletedPart(partFile, finalFile)
                            _state.value = completedState(finalFile, remoteSize)
                            return@withContext Result.success(finalFile.absolutePath)
                        }

                        val totalBytes = performDownload(
                            urlStr = url,
                            partFile = partFile,
                            expectedTotalBytes = remoteSize,
                            requestedStartByte = startByte
                        )
                        currentCoroutineContext().ensureActive()
                        promoteCompletedPart(partFile, finalFile)
                        _state.value = completedState(finalFile, totalBytes)
                        Result.success(finalFile.absolutePath)
                    } catch (cancelled: CancellationException) {
                        // Deliberately retain the `.part` artifact for resumption.
                        _state.value = DownloadState(error = "Download cancelled")
                        throw cancelled
                    } catch (error: IOException) {
                        _state.value = DownloadState(
                            error = error.message?.let { "Download failed: $it" }
                                ?: "Download failed"
                        )
                        Result.failure(error)
                    } catch (error: Exception) {
                        _state.value = DownloadState(
                            error = "Download failed: ${error.message}"
                        )
                        Result.failure(error)
                    }
                }
            }
        }

    /**
     * Returns an immutable cached final artifact for validation by the native
     * GGUF loader, or downloads one when no final artifact exists.
     *
     * This is intentionally narrower than [download]: it is for a flow whose
     * next operation is native model loading, which performs the authoritative
     * format validation. It keeps completed onboarding usable offline without
     * ever treating the resumable `.part` file as a model or replacing a final
     * file. Settings replacement actions must continue to call [download].
     */
    suspend fun prepareForNativeValidation(
        url: String,
        destFile: File
    ): Result<String> =
        withDownloadRequest {
            val cached = downloadMutex.withLock {
                withContext(Dispatchers.IO) {
                    val finalFile = destFile.absoluteFile
                    if (finalFile.isFile && finalFile.length() > 0L) {
                        _state.value = completedState(finalFile, finalFile.length())
                        Result.success(finalFile.absolutePath)
                    } else {
                        null
                    }
                }
            }
            cached ?: download(url, destFile)
        }

    /**
     * Cancels only the coroutine that owns a download request.
     *
     * [ownerJob] must be the exact non-null [Job] in the calling coroutine's
     * context (normally the value returned by its `launch`). It is cancelled
     * only while it is executing or waiting for [download] or
     * [prepareForNativeValidation]. This also cancels a request waiting on
     * [downloadMutex], without touching the active request owned by another
     * caller. An active request retains its `.part` artifact for resumption.
     */
    fun cancel(ownerJob: Job) {
        synchronized(requestJobsLock) {
            if (requestJobs.containsKey(ownerJob)) {
                ownerJob.cancel(
                    CancellationException("Model download cancelled by its owner")
                )
            }
        }
    }

    private suspend fun <T> withDownloadRequest(
        block: suspend () -> T
    ): T {
        val ownerJob = currentCoroutineContext()[Job]
            ?: error("A model download requires a coroutine Job.")
        synchronized(requestJobsLock) {
            requestJobs[ownerJob] = (requestJobs[ownerJob] ?: 0) + 1
        }
        try {
            return block()
        } finally {
            synchronized(requestJobsLock) {
                val remainingRegistrations = (requestJobs[ownerJob] ?: 1) - 1
                if (remainingRegistrations == 0) {
                    requestJobs.remove(ownerJob)
                } else {
                    requestJobs[ownerJob] = remainingRegistrations
                }
            }
        }
    }

    private fun existingFinalResult(finalFile: File, remoteSize: Long): String? =
        if (remoteSize > 0L &&
            finalFile.isFile &&
            finalFile.length() == remoteSize
        ) {
            finalFile.absolutePath
        } else {
            null
        }

    private fun existingArtifactError(finalFile: File, remoteSize: Long): IOException {
        val detail = if (remoteSize > 0L) {
            "it is ${finalFile.length()} bytes; the remote artifact is $remoteSize bytes"
        } else {
            "the server did not provide a size, so the existing artifact cannot be verified"
        }
        return IOException(
            "A model already exists at ${finalFile.absolutePath}, but $detail. " +
                "The existing model was left unchanged. Replacing model files requires " +
                "exclusive runtime maintenance and is not supported by the downloader."
        )
    }

    private fun completedState(finalFile: File, totalBytes: Long): DownloadState {
        val totalMb = totalBytes / BYTES_PER_MEBIBYTE
        return DownloadState(
            isComplete = true,
            progress = 1f,
            downloadedMb = finalFile.length() / BYTES_PER_MEBIBYTE,
            totalMb = totalMb,
            outputPath = finalFile.absolutePath
        )
    }

    /**
     * Publishes the staging artifact without exposing a partially written final.
     *
     * Android SELinux denies hard-link creation for untrusted apps, including
     * links within app-private storage. A same-directory move uses the platform
     * filesystem provider's rename path instead. Omitting REPLACE_EXISTING
     * preserves the no-replace contract; [downloadMutex] makes this class the
     * sole process writer and the app-private directory excludes external
     * writers.
     */
    @Throws(IOException::class)
    private fun promoteCompletedPart(partFile: File, finalFile: File) {
        if (!partFile.isFile) {
            throw IOException("Completed partial model is missing.")
        }
        if (finalFile.exists()) {
            throw existingArtifactError(finalFile, partFile.length())
        }

        val expectedLength = partFile.length()
        try {
            Files.move(partFile.toPath(), finalFile.toPath())
        } catch (exists: FileAlreadyExistsException) {
            throw existingArtifactError(finalFile, expectedLength)
        } catch (error: UnsupportedOperationException) {
            throw IOException(
                "This filesystem cannot atomically publish the completed model; " +
                    "the partial download was retained.",
                error
            )
        }

        if (!finalFile.isFile || finalFile.length() != expectedLength) {
            throw IOException(
                "Published model size changed during finalization: expected " +
                    "$expectedLength bytes, found ${finalFile.length()} bytes."
            )
        }
    }

    @Throws(IOException::class)
    private fun openConnectionWithRedirects(
        urlString: String,
        startByte: Long
    ): HttpURLConnection {
        var currentUrl = urlString
        repeat(MAX_REDIRECTS) {
            val connection = connectionFactory.open(URL(currentUrl))
            connection.apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
                if (startByte > 0L) {
                    setRequestProperty("Range", "bytes=$startByte-")
                }
            }

            val responseCode = connection.responseCode
            if (responseCode.isRedirect()) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl == null) {
                    throw IOException("Redirect received without Location header.")
                }
                currentUrl = URL(URL(currentUrl), newUrl).toString()
            } else {
                return connection
            }
        }
        throw IOException("Too many redirects ($MAX_REDIRECTS).")
    }

    /**
     * HEAD request to obtain the exact remote byte count.
     *
     * A successful response without Content-Length returns -1. Network and HTTP
     * failures remain failures rather than being mistaken for an unknown size.
     */
    @Throws(IOException::class)
    private fun fetchContentLength(urlString: String): Long {
        var currentUrl = urlString
        repeat(MAX_REDIRECTS) {
            val connection = connectionFactory.open(URL(currentUrl))
            connection.apply {
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
            }

            val responseCode = connection.responseCode
            if (responseCode.isRedirect()) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl == null) {
                    throw IOException("Redirect received without Location header.")
                }
                currentUrl = URL(URL(currentUrl), newUrl).toString()
            } else {
                try {
                    if (responseCode !in 200..299) {
                        throw IOException("Server returned HTTP $responseCode for model metadata.")
                    }
                    return connection.contentLengthLong
                } finally {
                    connection.disconnect()
                }
            }
        }
        throw IOException("Too many redirects ($MAX_REDIRECTS).")
    }

    @Throws(IOException::class)
    private suspend fun performDownload(
        urlStr: String,
        partFile: File,
        expectedTotalBytes: Long,
        requestedStartByte: Long
    ): Long {
        val connection = openConnectionWithRedirects(urlStr, requestedStartByte)
        try {
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK &&
                responseCode != HttpURLConnection.HTTP_PARTIAL
            ) {
                throw IOException("Server returned HTTP $responseCode while downloading model.")
            }

            val contentRange = connection.getHeaderField("Content-Range")
                ?.let(::parseContentRange)
            val actualStartByte = when {
                requestedStartByte > 0L &&
                    responseCode == HttpURLConnection.HTTP_PARTIAL -> {
                    if (contentRange == null || contentRange.first != requestedStartByte) {
                        throw IOException(
                            "Server returned an invalid range for the partial model download."
                        )
                    }
                    requestedStartByte
                }
                responseCode == HttpURLConnection.HTTP_PARTIAL -> {
                    if (contentRange == null || contentRange.first != 0L) {
                        throw IOException("Server returned an unexpected partial response.")
                    }
                    0L
                }
                else -> 0L
            }

            val responseBytes = connection.contentLengthLong
            if (contentRange != null) {
                val rangedBytes = contentRange.second - contentRange.first + 1L
                if (responseBytes > 0L && rangedBytes != responseBytes) {
                    throw IOException(
                        "Server returned inconsistent partial-response metadata."
                    )
                }
                if (contentRange.third > 0L &&
                    contentRange.second >= contentRange.third
                ) {
                    throw IOException("Server returned an invalid model byte range.")
                }
            }
            val rangeTotal = contentRange?.third?.takeIf { it > 0L }
            val responseTotal = when {
                rangeTotal != null -> rangeTotal
                responseBytes > 0L -> actualStartByte + responseBytes
                else -> -1L
            }
            val totalBytes = when {
                expectedTotalBytes > 0L &&
                    responseTotal > 0L &&
                    expectedTotalBytes != responseTotal ->
                    throw IOException(
                        "Remote model size changed from $expectedTotalBytes to $responseTotal bytes."
                    )
                expectedTotalBytes > 0L -> expectedTotalBytes
                responseTotal > 0L -> responseTotal
                else -> throw IOException(
                    "The server did not provide a model size; completion cannot be verified."
                )
            }

            val totalMb = totalBytes / BYTES_PER_MEBIBYTE
            val buffer = ByteArray(BUFFER_SIZE)
            connection.inputStream.buffered(BUFFER_SIZE).use { input ->
                FileOutputStream(partFile, actualStartByte > 0L).use { fileOutput ->
                    val output = BufferedOutputStream(fileOutput, BUFFER_SIZE)
                    var totalRead = actualStartByte
                    val sessionStartTime = System.currentTimeMillis()
                    val sessionStartBytes = actualStartByte
                    var lastUpdateTime = sessionStartTime

                    while (true) {
                        val bytesRead = input.read(buffer)
                        if (bytesRead == -1) break
                        currentCoroutineContext().ensureActive()
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= PROGRESS_INTERVAL_MS ||
                            totalRead == totalBytes
                        ) {
                            val elapsed = (now - sessionStartTime).coerceAtLeast(1L)
                            val speedBps =
                                ((totalRead - sessionStartBytes) * 1000.0 / elapsed).toLong()
                            _state.value = DownloadState(
                                isDownloading = true,
                                progress = (totalRead.toFloat() / totalBytes)
                                    .coerceIn(0f, 1f),
                                downloadedMb = totalRead / BYTES_PER_MEBIBYTE,
                                totalMb = totalMb,
                                speedMbps = speedBps / BYTES_PER_MEBIBYTE,
                                etaSeconds = if (speedBps > 0L) {
                                    ((totalBytes - totalRead).coerceAtLeast(0L) / speedBps)
                                } else {
                                    0L
                                }
                            )
                            lastUpdateTime = now
                        }
                    }

                    output.flush()
                    fileOutput.fd.sync()
                    if (totalRead != totalBytes) {
                        throw IOException(
                            "Download incomplete: received $totalRead of $totalBytes bytes."
                        )
                    }
                }
            }
            return totalBytes
        } finally {
            connection.disconnect()
        }
    }

    private fun parseContentRange(value: String): Triple<Long, Long, Long>? {
        val match = CONTENT_RANGE.matchEntire(value.trim()) ?: return null
        val start = match.groupValues[1].toLongOrNull() ?: return null
        val end = match.groupValues[2].toLongOrNull() ?: return null
        val total = match.groupValues[3].toLongOrNull() ?: -1L
        if (end < start) return null
        return Triple(start, end, total)
    }

    private fun Int.isRedirect(): Boolean =
        this == HttpURLConnection.HTTP_MOVED_PERM ||
            this == HttpURLConnection.HTTP_MOVED_TEMP ||
            this == HttpURLConnection.HTTP_SEE_OTHER ||
            this == 307 ||
            this == 308

    private companion object {
        const val MAX_REDIRECTS = 5
        const val BUFFER_SIZE = 128 * 1024
        const val PROGRESS_INTERVAL_MS = 250L
        const val USER_AGENT = "PocketFinancer/1.0"
        const val BYTES_PER_MEBIBYTE = 1_048_576f
        val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""")
    }
}
