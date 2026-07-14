package com.pocketfinancer.inference

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads GGUF model files from HuggingFace with progress tracking.
 *
 * Uses standard HttpURLConnection (no extra deps). Handles HuggingFace
 * LFS redirects automatically via HttpURLConnection follow-redirects.
 */
@Singleton
class ModelDownloader @Inject constructor() {
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

    private val _state = MutableStateFlow(DownloadState())
    val state: StateFlow<DownloadState> = _state.asStateFlow()

    private var downloadJob: Job? = null

    /**
     * Download a file from [url] to [destFile].
     *
     * Checks the server's Content-Length via a HEAD request. If the local
     * file already exists AND its size matches, skips the download.
     * Otherwise, downloads fresh.
     *
     * Reports progress via [state] StateFlow.
     */
    suspend fun download(url: String, destFile: File): Result<String> =
        withContext(Dispatchers.IO) {
            _state.value = DownloadState(isDownloading = true, progress = 0f)

            try {
                // ── Check if already downloaded (HEAD request) ──
                val remoteSize = fetchContentLength(url)
                if (remoteSize > 0 && destFile.exists() && destFile.length() == remoteSize) {
                    _state.value = DownloadState(
                        isDownloading = false,
                        isComplete = true,
                        progress = 1f,
                        downloadedMb = destFile.length() / 1_048_576f,
                        totalMb = destFile.length() / 1_048_576f,
                        outputPath = destFile.absolutePath
                    )
                    return@withContext Result.success(destFile.absolutePath)
                }

                // Determine starting byte for resumption
                val startByte = if (remoteSize > 0 && destFile.exists() && destFile.length() < remoteSize) {
                    destFile.length()
                } else {
                    0L
                }
                android.util.Log.d("ModelDownloader", "download: remoteSize=$remoteSize, destFileExists=${destFile.exists()}, destFileLength=${destFile.length()}, startByte=$startByte")

                if (startByte == 0L && destFile.exists()) {
                    destFile.delete()
                }

                downloadJob = coroutineContext[Job]
                performDownload(url, destFile, remoteSize, startByte)
            } catch (e: CancellationException) {
                // Keep partial file for resuming
                _state.value = DownloadState(error = "Download cancelled")
                Result.failure(e)
            } catch (e: IOException) {
                // Keep partial file for resuming
                _state.value = DownloadState(
                    isDownloading = false,
                    error = "Network error: ${e.message}"
                )
                Result.failure(e)
            } catch (e: Exception) {
                // Keep partial file for resuming
                _state.value = DownloadState(
                    isDownloading = false,
                    error = "Download failed: ${e.message}"
                )
                Result.failure(e)
            }
        }

    fun cancel() {
        downloadJob?.cancel()
    }

    // ── Internal ──────────────────────────────────────────────────────────

    @Throws(IOException::class)
    private fun openConnectionWithRedirects(urlStr: String, startByte: Long): HttpURLConnection {
        var currentUrl = urlStr
        var redirectsFollowed = 0
        val maxRedirects = 5

        while (redirectsFollowed < maxRedirects) {
            val url = URL(currentUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = false // Manually handle redirects
                setRequestProperty("User-Agent", "PocketFinancer/1.0")
                if (startByte > 0) {
                    setRequestProperty("Range", "bytes=$startByte-")
                }
            }

            val responseCode = connection.responseCode
            android.util.Log.d("ModelDownloader", "Redirect loop: url=$currentUrl, responseCode=$responseCode, rangeHeader=${connection.getRequestProperty("Range")}")
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == 307 || responseCode == 308 ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER
            ) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl == null) {
                    throw IOException("Redirect received without Location header")
                }
                currentUrl = if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                    newUrl
                } else {
                    val base = URL(currentUrl)
                    URL(base, newUrl).toString()
                }
                redirectsFollowed++
            } else {
                return connection
            }
        }
        throw IOException("Too many redirects ($maxRedirects)")
    }

    /**
     * HEAD request to get the remote file size without downloading.
     * Returns -1 if the size can't be determined.
     */
    @Throws(IOException::class)
    private fun fetchContentLength(urlStr: String): Long {
        var currentUrl = urlStr
        var redirectsFollowed = 0
        val maxRedirects = 5

        while (redirectsFollowed < maxRedirects) {
            val url = URL(currentUrl)
            val connection = url.openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "HEAD"
                connectTimeout = 10_000
                readTimeout = 10_000
                instanceFollowRedirects = false // Manually handle redirects
                setRequestProperty("User-Agent", "PocketFinancer/1.0")
            }

            val responseCode = connection.responseCode
            if (responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                responseCode == 307 || responseCode == 308 ||
                responseCode == HttpURLConnection.HTTP_SEE_OTHER
            ) {
                val newUrl = connection.getHeaderField("Location")
                connection.disconnect()
                if (newUrl == null) {
                    throw IOException("Redirect received without Location header")
                }
                currentUrl = if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                    newUrl
                } else {
                    val base = URL(currentUrl)
                    URL(base, newUrl).toString()
                }
                redirectsFollowed++
            } else {
                try {
                    return connection.contentLengthLong
                } finally {
                    connection.disconnect()
                }
            }
        }
        throw IOException("Too many redirects ($maxRedirects)")
    }

    @Throws(IOException::class)
    private suspend fun performDownload(
        urlStr: String,
        destFile: File,
        expectedTotalBytes: Long,
        startByte: Long
    ): Result<String> {
        val connection = openConnectionWithRedirects(urlStr, startByte)
        val responseCode = connection.responseCode
        android.util.Log.d("ModelDownloader", "performDownload: startByte=$startByte, finalResponseCode=$responseCode")
        // If we requested a range but server didn't respond with 206 Partial Content,
        // we must fallback to downloading from the beginning (0).
        val actualStartByte = if (startByte > 0 && responseCode == HttpURLConnection.HTTP_PARTIAL) {
            startByte
        } else {
            0L
        }

        val contentLength = connection.contentLengthLong
        val totalBytes = if (contentLength > 0) {
            if (actualStartByte > 0) actualStartByte + contentLength else contentLength
        } else {
            expectedTotalBytes
        }
        val totalMb = totalBytes.toFloat() / 1_048_576f

        destFile.parentFile?.mkdirs()

        val bufferSize = 128 * 1024 // 128 KB buffer
        connection.inputStream.buffered(bufferSize).use { input ->
            FileOutputStream(destFile, actualStartByte > 0).buffered(bufferSize).use { output ->
                val buffer = ByteArray(bufferSize)
                var bytesRead: Int
                var totalRead: Long = actualStartByte
                val sessionStartTime = System.currentTimeMillis()
                val sessionStartBytes = actualStartByte
                var lastUpdateTime = sessionStartTime

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    currentCoroutineContext().ensureActive()
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = System.currentTimeMillis()

                    if (now - lastUpdateTime >= 250 || totalRead == totalBytes) {
                        val sessionElapsed = (now - sessionStartTime).coerceAtLeast(1)
                        val speedBps = ((totalRead - sessionStartBytes) * 1000.0 / sessionElapsed).toLong()
                        val speedMbps = speedBps / 1_048_576f
                        val progress = if (totalBytes > 0) totalRead.toFloat() / totalBytes else 0f
                        val eta = if (speedBps > 0 && totalBytes > 0) {
                            ((totalBytes - totalRead) / speedBps)
                        } else 0L

                        _state.value = DownloadState(
                            isDownloading = true,
                            progress = progress,
                            downloadedMb = totalRead / 1_048_576f,
                            totalMb = totalMb,
                            speedMbps = speedMbps,
                            etaSeconds = eta
                        )

                        lastUpdateTime = now
                    }
                }

                // Verify download completed fully
                if (totalBytes > 0 && totalRead != totalBytes) {
                    connection.disconnect()
                    _state.value = DownloadState(
                        isDownloading = false,
                        error = "Download incomplete: ${totalRead / 1_048_576} MB of ${"%.0f".format(totalMb)} MB"
                    )
                    return Result.failure(IOException("Download incomplete: received ${totalRead} of $totalBytes bytes"))
                }
            }
        }

        connection.disconnect()

        _state.value = DownloadState(
            isDownloading = false,
            isComplete = true,
            progress = 1f,
            downloadedMb = destFile.length() / 1_048_576f,
            totalMb = totalMb,
            outputPath = destFile.absolutePath
        )

        return Result.success(destFile.absolutePath)
    }
}
