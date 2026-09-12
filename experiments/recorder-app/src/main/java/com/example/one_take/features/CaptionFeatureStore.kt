package com.example.one_take.features

import android.util.Log
import kotlinx.coroutines.isActive
import android.content.Context
import android.os.StatFs
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicLong
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Owns the lifecycle of the downloadable offline caption model.
 *
 * The store is process-scoped so navigating away from the marketplace does not
 * cancel an active download.  A completed model is accepted only after both
 * its exact size and SHA-256 digest match [OfflineCaptionModel].
 */
internal class CaptionFeatureStore private constructor(context: Context) {
    companion object {
        private const val PREFS_NAME = "caption_feature"
        private const val KEY_INSTALLED = "model_installed"
        private const val KEY_ENABLED = "model_enabled"
        private const val MIN_FREE_SPACE_BYTES = 16L * 1024L * 1024L
        private const val CONNECT_TIMEOUT_MILLIS = 15_000
        private const val READ_TIMEOUT_MILLIS = 30_000
        private const val BUFFER_SIZE = 32 * 1024
        private const val PROGRESS_STEP_BYTES = 256L * 1024L

        @Volatile
        private var instance: CaptionFeatureStore? = null

        fun get(context: Context): CaptionFeatureStore {
            return instance ?: synchronized(this) {
                instance ?: CaptionFeatureStore(context.applicationContext).also { instance = it }
            }
        }
    }

    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val featureDirectory = File(appContext.noBackupFilesDir, "features/offline-captions")

    /** The only file that a future caption runtime may open. */
    val modelFile: File = File(featureDirectory, OfflineCaptionModel.FILE_NAME)

    private val partialFile = File(featureDirectory, "${OfflineCaptionModel.FILE_NAME}.part")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val validationGeneration = AtomicLong(0L)
    private var downloadJob: Job? = null

    @Volatile
    private var activeConnection: HttpURLConnection? = null

    var installed by androidx.compose.runtime.mutableStateOf(false)
        private set

    var enabled by androidx.compose.runtime.mutableStateOf(false)
        private set

    var state by androidx.compose.runtime.mutableStateOf<CaptionFeatureState>(CaptionFeatureState.Checking)
        private set

    /** Set by the caption pipeline while it has an open model lease. */
    var inUse by androidx.compose.runtime.mutableStateOf(false)
        internal set

    init {
        validateExistingArtifact()
    }

    /** Starts a fresh download. Any interrupted .part file is discarded. */
    fun download() {
        if (installed || inUse || state is CaptionFeatureState.Checking) return
        if (state is CaptionFeatureState.Downloading || state is CaptionFeatureState.Verifying) return
        if (downloadJob?.isActive == true) return

        // Publish the busy state before launching on IO so a second tap cannot
        // create a second request during coroutine scheduling.
        state = CaptionFeatureState.Downloading(0L, OfflineCaptionModel.EXPECTED_BYTES)
        downloadJob = scope.launch {
            try {
                prepareDirectory()
                checkFreeSpace()
                partialFile.delete()

                val connection = openConnection()
                try {
                    val responseCode = connection.responseCode
                    if (responseCode !in 200..299) {
                        Log.w("CaptionFeatureStore", "Model HTTP response: $responseCode")
                        throw DownloadFailure(CaptionFeatureError.Network)
                    }
                    val responseLength = connection.contentLengthLong
                    if (responseLength > OfflineCaptionModel.EXPECTED_BYTES) {
                        throw DownloadFailure(CaptionFeatureError.DownloadTooLarge)
                    }
                    downloadToPartialFile(connection, responseLength)
                } finally {
                    activeConnection = null
                    connection.disconnect()
                }

                state = CaptionFeatureState.Verifying
                if (!OfflineCaptionModel.isVerifiedArtifact(partialFile)) {
                    throw DownloadFailure(CaptionFeatureError.ChecksumMismatch)
                }
                coroutineContext.ensureActive()

                // Both paths are inside the same feature directory, so this
                // rename is atomic on Android's app filesystem.
                if (modelFile.exists() && !modelFile.delete()) {
                    throw DownloadFailure(CaptionFeatureError.InstallFailed)
                }
                if (!partialFile.renameTo(modelFile)) {
                    throw DownloadFailure(CaptionFeatureError.InstallFailed)
                }

                installed = true
                enabled = true
                preferences.edit()
                    .putBoolean(KEY_INSTALLED, true)
                    .putBoolean(KEY_ENABLED, true)
                    .apply()
                state = CaptionFeatureState.Ready
            } catch (cancelled: CancellationException) {
                partialFile.delete()
                if (!installed) state = CaptionFeatureState.Idle
                throw cancelled
            } catch (failure: DownloadFailure) {
                partialFile.delete()
                if (!installed) state = CaptionFeatureState.Error(failure.reason)
            } catch (_: SocketTimeoutException) {
                partialFile.delete()
                if (!installed) state = CaptionFeatureState.Error(CaptionFeatureError.Network)
            } catch (failure: IOException) {
                Log.w("CaptionFeatureStore", "Model download failed", failure)
                partialFile.delete()
                if (!installed) state = CaptionFeatureState.Error(CaptionFeatureError.Network)
            } catch (_: Exception) {
                partialFile.delete()
                if (!installed) state = CaptionFeatureState.Error(CaptionFeatureError.InstallFailed)
            } finally {
                if (!coroutineContext.isActive && !installed) state = CaptionFeatureState.Idle
                activeConnection = null
                downloadJob = null
            }
        }
    }

    /** Cancels the active request and removes its incomplete artifact. */
    fun cancelDownload() {
        val job = downloadJob ?: return
        job.cancel()
        activeConnection?.disconnect()
    }

    /**
     * Removes the installed model only when no caption pipeline holds it.
     * Returns false when the model is busy or the file could not be removed.
     */
    fun remove(): Boolean {
        if (inUse || downloadJob?.isActive == true || state is CaptionFeatureState.Checking) return false
        validationGeneration.incrementAndGet()
        val removed = !modelFile.exists() || modelFile.delete()
        if (!removed) return false

        partialFile.delete()
        installed = false
        enabled = false
        preferences.edit()
            .putBoolean(KEY_INSTALLED, false)
            .putBoolean(KEY_ENABLED, false)
            .apply()
        state = CaptionFeatureState.Idle
        return true
    }

    /** Enables the feature only after the verified model is installed. */
    fun setFeatureEnabled(value: Boolean) {
        if (inUse || !installed) return
        enabled = value
        preferences.edit().putBoolean(KEY_ENABLED, value).apply()
    }

    private fun validateExistingArtifact() {
        val generation = validationGeneration.incrementAndGet()
        scope.launch {
            partialFile.delete()
            val valid = OfflineCaptionModel.isVerifiedArtifact(modelFile)
            if (validationGeneration.get() != generation) return@launch

            if (valid) {
                installed = true
                enabled = preferences.getBoolean(KEY_ENABLED, true)
                preferences.edit()
                    .putBoolean(KEY_INSTALLED, true)
                    .putBoolean(KEY_ENABLED, enabled)
                    .apply()
                state = CaptionFeatureState.Ready
            } else {
                if (modelFile.exists()) modelFile.delete()
                installed = false
                enabled = false
                preferences.edit()
                    .putBoolean(KEY_INSTALLED, false)
                    .putBoolean(KEY_ENABLED, false)
                    .apply()
                state = CaptionFeatureState.Idle
            }
        }
    }

    private fun prepareDirectory() {
        if (!featureDirectory.exists() && !featureDirectory.mkdirs()) {
            throw DownloadFailure(CaptionFeatureError.StorageUnavailable)
        }
        if (!featureDirectory.isDirectory) {
            throw DownloadFailure(CaptionFeatureError.StorageUnavailable)
        }
    }

    private fun checkFreeSpace() {
        try {
            val available = StatFs(featureDirectory.absolutePath).availableBytes
            val required = OfflineCaptionModel.EXPECTED_BYTES + MIN_FREE_SPACE_BYTES
            if (available < required) {
                throw DownloadFailure(CaptionFeatureError.InsufficientStorage)
            }
        } catch (failure: DownloadFailure) {
            throw failure
        } catch (_: Exception) {
            throw DownloadFailure(CaptionFeatureError.StorageUnavailable)
        }
    }

    private fun openConnection(): HttpURLConnection {
        val connection = (URL(OfflineCaptionModel.downloadUrl).openConnection() as HttpURLConnection)
            .apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MILLIS
                readTimeout = READ_TIMEOUT_MILLIS
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("Accept-Encoding", "identity")
                setRequestProperty("User-Agent", "OneTake/0.1 offline-captions")
            }
        activeConnection = connection
        return connection
    }

    private suspend fun downloadToPartialFile(
        connection: HttpURLConnection,
        responseLength: Long
    ) {
        var downloaded = 0L
        var lastReported = 0L
        val total = if (responseLength > 0L) responseLength else OfflineCaptionModel.EXPECTED_BYTES

        try {
            BufferedInputStream(connection.inputStream, BUFFER_SIZE).use { input ->
                BufferedOutputStream(FileOutputStream(partialFile), BUFFER_SIZE).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        downloaded += read
                        if (downloaded > OfflineCaptionModel.EXPECTED_BYTES) {
                            throw DownloadFailure(CaptionFeatureError.DownloadTooLarge)
                        }
                        output.write(buffer, 0, read)
                        if (downloaded - lastReported >= PROGRESS_STEP_BYTES) {
                            state = CaptionFeatureState.Downloading(downloaded, total)
                            lastReported = downloaded
                        }
                    }
                    output.flush()
                }
            }
        } catch (failure: DownloadFailure) {
            throw failure
        }

        if (downloaded != OfflineCaptionModel.EXPECTED_BYTES) {
            throw DownloadFailure(CaptionFeatureError.IncompleteDownload)
        }
        state = CaptionFeatureState.Downloading(downloaded, total)
    }

    private class DownloadFailure(val reason: CaptionFeatureError) : IOException()
}
