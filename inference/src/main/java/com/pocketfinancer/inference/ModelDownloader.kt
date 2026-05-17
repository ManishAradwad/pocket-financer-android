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
class ModelDownloader @Inject constructor(
    @ApplicationContext private val context: Context
) {
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

            // Partial or missing — download fresh
            if (destFile.exists()) destFile.delete()

            _state.value = DownloadState(isDownloading = true, progress = 0f)

            try {
                downloadJob = coroutineContext[Job]
                performDownload(url, destFile, remoteSize)
            } catch (e: CancellationException) {
                destFile.delete()
                _state.value = DownloadState(error = "Download cancelled")
                Result.failure(e)
            } catch (e: IOException) {
                destFile.delete()
                _state.value = DownloadState(
                    isDownloading = false,
                    error = "Network error: ${e.message}"
                )
                Result.failure(e)
            } catch (e: Exception) {
                destFile.delete()
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

    /**
     * HEAD request to get the remote file size without downloading.
     * Returns -1 if the size can't be determined.
     */
    @Throws(IOException::class)
    private fun fetchContentLength(urlStr: String): Long {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            requestMethod = "HEAD"
            connectTimeout = 10_000
            readTimeout = 10_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PocketFinancer/1.0")
        }
        try {
            return connection.contentLengthLong
        } finally {
            connection.disconnect()
        }
    }

    @Throws(IOException::class)
    private fun performDownload(
        urlStr: String,
        destFile: File,
        expectedTotalBytes: Long
    ): Result<String> {
        val url = URL(urlStr)
        val connection = url.openConnection() as HttpURLConnection
        connection.apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "PocketFinancer/1.0")
        }

        val contentLength = connection.contentLengthLong
        val totalBytes = if (contentLength > 0) contentLength else expectedTotalBytes
        val totalMb = totalBytes.toFloat() / 1_048_576f

        destFile.parentFile?.mkdirs()

        connection.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead: Long = 0
                val startTime = System.currentTimeMillis()
                var lastUpdateTime = startTime

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = System.currentTimeMillis()

                    if (now - lastUpdateTime >= 250 || totalRead == totalBytes) {
                        val totalElapsed = (now - startTime).coerceAtLeast(1)
                        val speedBps = (totalRead * 1000.0 / totalElapsed).toLong()
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
                    destFile.delete()
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
