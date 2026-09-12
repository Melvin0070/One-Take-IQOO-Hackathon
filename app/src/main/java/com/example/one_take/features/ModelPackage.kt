package com.example.one_take.features

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * Immutable metadata for the first downloadable caption model.
 *
 * The revision is pinned so a future repository update cannot silently change
 * the bytes that the app installs.  This is a model data file only; the
 * caption runtime is shipped separately by the application.
 */
internal object OfflineCaptionModel {
    const val REPOSITORY = "ggerganov/whisper.cpp"
    const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    const val FILE_NAME = "ggml-tiny.bin"
    const val EXPECTED_BYTES = 77_691_713L
    const val EXPECTED_SHA256 = "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"
    const val LICENSE = "MIT"

    val downloadUrl: String
        get() = "https://huggingface.co/$REPOSITORY/resolve/$REVISION/$FILE_NAME?download=true"

    val repositoryUrl: String
        get() = "https://huggingface.co/$REPOSITORY"

    val licenseUrl: String
        get() = "https://github.com/ggerganov/whisper.cpp/blob/$REVISION/LICENSE"

    /** Returns the lowercase SHA-256 digest of [file]. */
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
    }

    /**
     * Checks both the exact byte count and digest before an artifact is usable.
     * The optional arguments keep this helper testable with small synthetic
     * payloads without ever downloading the production model in unit tests.
     */
    fun isVerifiedArtifact(
        file: File,
        expectedBytes: Long = EXPECTED_BYTES,
        expectedSha256: String = EXPECTED_SHA256
    ): Boolean {
        if (!file.isFile || file.length() != expectedBytes) return false
        return try {
            sha256(file).equals(expectedSha256, ignoreCase = true)
        } catch (_: Exception) {
            false
        }
    }
}

internal sealed interface CaptionFeatureState {
    data object Checking : CaptionFeatureState
    data object Idle : CaptionFeatureState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : CaptionFeatureState
    data object Verifying : CaptionFeatureState
    data object Ready : CaptionFeatureState
    data class Error(val reason: CaptionFeatureError) : CaptionFeatureState
}

internal enum class CaptionFeatureError {
    Network,
    InvalidResponse,
    DownloadTooLarge,
    IncompleteDownload,
    ChecksumMismatch,
    StorageUnavailable,
    InsufficientStorage,
    InstallFailed
}
