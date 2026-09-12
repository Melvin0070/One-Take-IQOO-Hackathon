package com.onetake.npu.whisper.install

import android.content.Context
import com.onetake.npu.TinyWhisperBundleSpec
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/** A cooperative cancellation hook for a long local archive install. */
fun interface WhisperInstallCancellation {
    fun isCancelled(): Boolean

    companion object {
        val NONE = WhisperInstallCancellation { Thread.currentThread().isInterrupted }
    }
}

/** An invalid, incomplete, or unsafe Qualcomm Whisper model package. */
class WhisperBundleInstallException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Installs the pinned Qualcomm Whisper graph artifacts without exposing a
 * partially extracted directory to a graph runtime.
 *
 * The active bundle is selected by a small pointer file.  A new archive is
 * extracted into a private staging directory, verified, renamed into a version
 * directory, and published by replacing that pointer.  A failed or cancelled
 * install therefore leaves the previous active directory untouched.
 */
class WhisperBundleInstaller private constructor(
    private val storageDirectory: File,
    private val spec: InternalWhisperBundleSpec,
    @Suppress("UNUSED_PARAMETER") private val constructorMarker: Unit,
) {
    constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, STORAGE_RELATIVE_PATH),
        InternalWhisperBundleSpec.PRODUCTION,
        Unit,
    )

    /** Returns the active verified bundle directory, or null when none exists. */
    fun resolveInstalled(): File? = synchronized(PROCESS_LOCK) {
        resolveInstalledLocked()
    }

    /**
     * Installs the pinned archive from local storage and returns its directory.
     *
     * The archive is checked against [TinyWhisperBundleSpec.ARCHIVE_SHA256] before
     * extraction.  Call this from an IO dispatcher for a large archive.
     */
    @Throws(WhisperBundleInstallException::class)
    fun install(archive: File): File = install(archive, WhisperInstallCancellation.NONE)

    /** Installs the archive while observing a caller-owned cancellation hook. */
    @Throws(WhisperBundleInstallException::class)
    fun install(archive: File, cancellation: WhisperInstallCancellation): File =
        synchronized(PROCESS_LOCK) {
            installLocked(archive, cancellation)
        }

    companion object {
        private const val STORAGE_RELATIVE_PATH = "models/qualcomm-whisper"
        private const val ACTIVE_POINTER = "active"
        private const val POINTER_TEMP = "active.part"
        private const val STAGING_PREFIX = ".staging-"
        private const val VERSION_PREFIX = "v-"
        private const val BUFFER_SIZE = 1024 * 1024
        private const val MAX_ZIP_ENTRIES = 16

        private val PROCESS_LOCK = Any()

        /** Resolves the application-wide active bundle without retaining an installer. */
        @JvmStatic
        fun resolveInstalled(context: Context): File? = WhisperBundleInstaller(context).resolveInstalled()

    }

    internal constructor(storageDirectory: File) : this(
        storageDirectory,
        InternalWhisperBundleSpec.PRODUCTION,
        Unit,
    )

    internal constructor(
        storageDirectory: File,
        spec: InternalWhisperBundleSpec,
    ) : this(storageDirectory, spec, Unit)

    private fun installLocked(
        archive: File,
        cancellation: WhisperInstallCancellation,
    ): File {
        checkCancellation(cancellation)
        val inputArchive = regularReadableFile(archive, "archive")
        if (inputArchive.length() != spec.archiveSizeBytes) {
            throw WhisperBundleInstallException(
                "Whisper archive size mismatch: expected ${spec.archiveSizeBytes}, " +
                    "got ${inputArchive.length()}",
            )
        }
        verifyDigest(inputArchive, spec.archiveSha256, "Whisper archive", cancellation)
        resolveInstalledLocked()?.let { return it }

        ensureStorageDirectory()
        val staging = File(storageDirectory, STAGING_PREFIX + UUID.randomUUID())
        if (!staging.mkdir()) {
            throw WhisperBundleInstallException("Unable to create Whisper staging directory")
        }
        var publishedDirectory: File? = null
        try {
            extractAndVerify(inputArchive, staging, cancellation)
            checkCancellation(cancellation)

            val versionDirectory = File(
                storageDirectory,
                VERSION_PREFIX + System.currentTimeMillis() + "-" + UUID.randomUUID(),
            )
            if (!staging.renameTo(versionDirectory)) {
                throw WhisperBundleInstallException("Unable to publish verified Whisper files")
            }
            publishedDirectory = versionDirectory
            validateBundleDirectory(versionDirectory)
            checkCancellation(cancellation)
            publishPointer(versionDirectory.name)
            return versionDirectory
        } catch (failure: WhisperBundleInstallException) {
            publishedDirectory?.deleteRecursively()
            staging.deleteRecursively()
            throw failure
        } catch (failure: IOException) {
            publishedDirectory?.deleteRecursively()
            staging.deleteRecursively()
            throw WhisperBundleInstallException("Whisper bundle installation failed", failure)
        } catch (failure: RuntimeException) {
            publishedDirectory?.deleteRecursively()
            staging.deleteRecursively()
            throw WhisperBundleInstallException("Whisper bundle installation failed", failure)
        }
    }

    private fun extractAndVerify(
        archive: File,
        staging: File,
        cancellation: WhisperInstallCancellation,
    ) {
        try {
            ZipFile(archive).use { zip ->
                val entries = zip.entries().toListChecked(MAX_ZIP_ENTRIES)
                val seen = HashSet<String>(entries.size)
                var foundRootDirectory = false
                for (entry in entries) {
                    checkCancellation(cancellation)
                    val name = entry.name
                    validateEntryName(name)
                    if (!seen.add(name)) {
                        throw WhisperBundleInstallException("Whisper archive contains duplicate entry: $name")
                    }
                    if (name == TinyWhisperBundleSpec.ARCHIVE_ROOT) {
                        if (!entry.isDirectory) {
                            throw WhisperBundleInstallException("Whisper archive root is not a directory")
                        }
                        foundRootDirectory = true
                        continue
                    }
                    val artifact = spec.artifactByArchivePath[name]
                        ?: throw WhisperBundleInstallException(
                            "Whisper archive contains unsupported entry: $name",
                        )
                    if (entry.isDirectory) {
                        throw WhisperBundleInstallException("Whisper artifact is a directory: $name")
                    }
                    if (entry.size < 0L || entry.size != artifact.sizeBytes) {
                        throw WhisperBundleInstallException(
                            "Whisper artifact size mismatch for ${artifact.name}: " +
                                "expected ${artifact.sizeBytes}, got ${entry.size}",
                        )
                    }
                    if (entry.compressedSize > spec.archiveSizeBytes) {
                        throw WhisperBundleInstallException("Whisper archive entry is too large: $name")
                    }
                    val output = File(staging, artifact.name)
                    extractArtifact(zip, entry, artifact, output, cancellation)
                }
                if (!foundRootDirectory) {
                    throw WhisperBundleInstallException("Whisper archive root directory is missing")
                }
                val expected = spec.artifactByArchivePath.keys
                if (!seen.containsAll(expected)) {
                    val missing = expected.filterNot(seen::contains).joinToString()
                    throw WhisperBundleInstallException("Whisper archive is incomplete; missing: $missing")
                }
                if (seen.size != expected.size + 1) {
                    throw WhisperBundleInstallException("Whisper archive contains unsupported entries")
                }
            }
        } catch (failure: WhisperBundleInstallException) {
            throw failure
        } catch (failure: IOException) {
            throw WhisperBundleInstallException("Unable to read Whisper archive", failure)
        } catch (failure: RuntimeException) {
            throw WhisperBundleInstallException("Unable to read Whisper archive", failure)
        }
        validateBundleDirectory(staging)
    }

    private fun extractArtifact(
        zip: ZipFile,
        entry: ZipEntry,
        artifact: InternalWhisperBundleSpec.Artifact,
        output: File,
        cancellation: WhisperInstallCancellation,
    ) {
        if (output.exists() || !output.createNewFile()) {
            throw WhisperBundleInstallException("Whisper artifact output already exists: ${artifact.name}")
        }
        try {
            zip.getInputStream(entry).use { input ->
                FileOutputStream(output).use { file ->
                    BufferedInputStream(input, BUFFER_SIZE).use { bufferedInput ->
                        BufferedOutputStream(file, BUFFER_SIZE).use { bufferedOutput ->
                            val digest = MessageDigest.getInstance("SHA-256")
                            val buffer = ByteArray(BUFFER_SIZE)
                            var total = 0L
                            while (true) {
                                checkCancellation(cancellation)
                                val read = bufferedInput.read(buffer)
                                if (read < 0) break
                                total += read
                                if (total > artifact.sizeBytes) {
                                    throw WhisperBundleInstallException(
                                        "Whisper artifact exceeds its size limit: ${artifact.name}",
                                    )
                                }
                                digest.update(buffer, 0, read)
                                bufferedOutput.write(buffer, 0, read)
                            }
                            bufferedOutput.flush()
                            if (total != artifact.sizeBytes) {
                                throw WhisperBundleInstallException(
                                    "Whisper artifact is incomplete: ${artifact.name}",
                                )
                            }
                            val actual = digest.toHex()
                            if (actual != artifact.sha256) {
                                throw WhisperBundleInstallException(
                                    "Whisper artifact SHA-256 mismatch: ${artifact.name}",
                                )
                            }
                        }
                    }
                }
            }
        } catch (failure: WhisperBundleInstallException) {
            output.delete()
            throw failure
        } catch (failure: IOException) {
            output.delete()
            throw WhisperBundleInstallException("Unable to extract Whisper artifact: ${artifact.name}", failure)
        }
    }

    private fun publishPointer(versionName: String) {
        val pointer = File(storageDirectory, ACTIVE_POINTER)
        val temporary = File(storageDirectory, POINTER_TEMP)
        if (temporary.exists() && !temporary.delete()) {
            throw WhisperBundleInstallException("Unable to replace stale Whisper pointer")
        }
        try {
            FileOutputStream(temporary).use { output ->
                output.write(versionName.toByteArray(Charsets.UTF_8))
                output.write('\n'.code)
                output.fd.sync()
            }
            if (!temporary.renameTo(pointer)) {
                throw WhisperBundleInstallException("Unable to publish active Whisper bundle")
            }
        } catch (failure: WhisperBundleInstallException) {
            temporary.delete()
            throw failure
        } catch (failure: IOException) {
            temporary.delete()
            throw WhisperBundleInstallException("Unable to publish active Whisper bundle", failure)
        }
    }

    private fun resolveInstalledLocked(): File? {
        val pointer = File(storageDirectory, ACTIVE_POINTER)
        if (!pointer.isFile || !pointer.canRead()) return null
        val versionName = try {
            pointer.readText(Charsets.UTF_8).trim()
        } catch (_: IOException) {
            return null
        }
        if (!isSafeVersionName(versionName)) return null
        val version = File(storageDirectory, versionName)
        if (!isContainedDirectory(version)) return null
        return try {
            validateBundleDirectory(version)
            version
        } catch (_: WhisperBundleInstallException) {
            null
        }
    }

    private fun validateBundleDirectory(directory: File) {
        if (!directory.isDirectory || !directory.canRead()) {
            throw WhisperBundleInstallException("Whisper bundle directory is unavailable")
        }
        val seen = HashSet<String>(spec.artifacts.size)
        for (artifact in spec.artifacts) {
            val file = File(directory, artifact.name)
            if (!isContainedFile(file)) {
                throw WhisperBundleInstallException("Whisper artifact is unavailable: ${artifact.name}")
            }
            if (!seen.add(file.name)) {
                throw WhisperBundleInstallException("Whisper artifact is duplicated: ${artifact.name}")
            }
            if (file.length() != artifact.sizeBytes) {
                throw WhisperBundleInstallException(
                    "Whisper artifact size mismatch for ${artifact.name}: " +
                        "expected ${artifact.sizeBytes}, got ${file.length()}",
                )
            }
            verifyDigest(file, artifact.sha256, artifact.name, WhisperInstallCancellation.NONE)
        }
    }

    private fun ensureStorageDirectory() {
        if (!storageDirectory.exists() && !storageDirectory.mkdirs()) {
            throw WhisperBundleInstallException("Unable to create Whisper model directory")
        }
        if (!storageDirectory.isDirectory || !storageDirectory.canRead() || !storageDirectory.canWrite()) {
            throw WhisperBundleInstallException("Whisper model directory is unavailable")
        }
    }

    private fun isContainedDirectory(candidate: File): Boolean {
        if (!candidate.isDirectory || !candidate.canRead()) return false
        return try {
            val canonical = candidate.canonicalFile
            canonical.name == candidate.name &&
                canonical.parentFile?.canonicalFile == storageDirectory.canonicalFile
        } catch (_: IOException) {
            false
        }
    }

    private fun isContainedFile(candidate: File): Boolean {
        if (!candidate.isFile || !candidate.canRead()) return false
        return try {
            val canonical = candidate.canonicalFile
            canonical.name == candidate.name &&
                canonical.parentFile?.canonicalFile == candidate.parentFile?.canonicalFile
        } catch (_: IOException) {
            false
        }
    }

    private fun regularReadableFile(file: File, label: String): File {
        val canonical = try {
            file.canonicalFile
        } catch (failure: IOException) {
            throw WhisperBundleInstallException("Unable to resolve $label", failure)
        }
        if (!canonical.isFile || !canonical.canRead()) {
            throw WhisperBundleInstallException("$label is missing or unreadable")
        }
        return canonical
    }

    private fun verifyDigest(
        file: File,
        expected: String,
        label: String,
        cancellation: WhisperInstallCancellation,
    ) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            BufferedInputStream(FileInputStream(file), BUFFER_SIZE).use { input ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    checkCancellation(cancellation)
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                    total += read
                }
            }
            if (label == "Whisper archive" && total != spec.archiveSizeBytes) {
                throw WhisperBundleInstallException("$label size changed while reading")
            }
            if (digest.toHex() != expected) {
                throw WhisperBundleInstallException("$label SHA-256 mismatch")
            }
        } catch (failure: WhisperBundleInstallException) {
            throw failure
        } catch (failure: IOException) {
            throw WhisperBundleInstallException("Unable to read $label", failure)
        }
    }

    private fun checkCancellation(cancellation: WhisperInstallCancellation) {
        if (cancellation.isCancelled()) {
            throw WhisperBundleInstallException("Whisper bundle installation cancelled")
        }
    }

    private fun validateEntryName(name: String) {
        if (name.isEmpty() || name.indexOf('\u0000') >= 0 || name.indexOf('\\') >= 0) {
            throw WhisperBundleInstallException("Whisper archive contains an unsafe entry path")
        }
        if (name.startsWith('/') || name.startsWith("../") || name.contains("/../") ||
            name == ".." || name.contains("//") || name.split('/').any { it == "." || it == ".." }
        ) {
            throw WhisperBundleInstallException("Whisper archive contains an unsafe entry path: $name")
        }
        if (name != TinyWhisperBundleSpec.ARCHIVE_ROOT && !name.startsWith(TinyWhisperBundleSpec.ARCHIVE_ROOT)) {
            throw WhisperBundleInstallException("Whisper archive entry is outside its root: $name")
        }
    }

    private fun isSafeVersionName(value: String): Boolean =
        value.startsWith(VERSION_PREFIX) && value.length < 160 &&
            value.all { it.isLetterOrDigit() || it == '-' || it == '_' }

}

internal class InternalWhisperBundleSpec(
    val archiveSizeBytes: Long,
    val archiveSha256: String,
    val artifacts: List<Artifact>,
    val artifactByArchivePath: Map<String, Artifact>,
) {
    data class Artifact(val name: String, val sizeBytes: Long, val sha256: String)

    companion object {
        val PRODUCTION = InternalWhisperBundleSpec(
            archiveSizeBytes = TinyWhisperBundleSpec.ARCHIVE_SIZE_BYTES,
            archiveSha256 = TinyWhisperBundleSpec.ARCHIVE_SHA256,
            artifacts = TinyWhisperBundleSpec.artifacts.map {
                Artifact(it.name, it.sizeBytes, it.sha256)
            },
            artifactByArchivePath = TinyWhisperBundleSpec.artifactByArchivePath.mapValues { (_, value) ->
                Artifact(value.name, value.sizeBytes, value.sha256)
            },
        )
    }
}

private fun java.util.Enumeration<out ZipEntry>.toListChecked(maxEntries: Int): List<ZipEntry> {
    val entries = ArrayList<ZipEntry>()
    while (hasMoreElements()) {
        if (entries.size == maxEntries) {
            throw WhisperBundleInstallException("Whisper archive contains too many entries")
        }
        entries += nextElement()
    }
    return entries
}

private fun MessageDigest.toHex(): String =
    digest().joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
