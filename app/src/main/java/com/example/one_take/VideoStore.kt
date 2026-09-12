package com.example.one_take

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.charset.StandardCharsets

/**
 * Owns the private directory in which recordings are written.
 *
 * A pending marker is kept beside each output while CameraX is writing it.
 * The marker is locked for the lifetime of the recording, so recovery can
 * safely skip an output that is still being finalized by another recorder.
 */
internal class VideoStore(filesDir: File) {
    companion object {
        private const val DIRECTORY_NAME = "videos"
        private const val VIDEO_PREFIX = "video_"
        private const val VIDEO_EXTENSION = ".mp4"
        private const val PENDING_SUFFIX = ".pending"
        private const val MAX_CREATE_ATTEMPTS = 100
        /** Serializes marker creation and recovery inside this process. */
        private val processLock = Any()
    }

    val directory: File = File(filesDir, DIRECTORY_NAME)

    /**
     * Creates a unique output path and an active, locked marker for it.
     * The caller must finish the returned handle with [PendingRecording.complete]
     * or [PendingRecording.discard].
     */
    @Throws(IOException::class)
    fun createPendingRecording(): PendingRecording {
        synchronized(processLock) {
            ensureDirectory()
            repeat(MAX_CREATE_ATTEMPTS) { attempt ->
                val suffix = if (attempt == 0) "" else "_$attempt"
                val fileName = "$VIDEO_PREFIX${System.currentTimeMillis()}$suffix$VIDEO_EXTENSION"
                val outputFile = File(directory, fileName)
                val markerFile = markerFileFor(outputFile)

                if (outputFile.exists() || markerFile.exists() || !markerFile.createNewFile()) {
                    return@repeat
                }

                val channel = RandomAccessFile(markerFile, "rw").channel
                val lock = tryAcquireLock(channel)
                if (lock == null) {
                    closeQuietly(channel)
                    markerFile.delete()
                    return@repeat
                }

                try {
                    channel.write(ByteBuffer.wrap(fileName.toByteArray(StandardCharsets.UTF_8)))
                    channel.force(true)
                    return PendingRecording(outputFile, markerFile, channel, lock)
                } catch (exception: IOException) {
                    closeQuietly(lock)
                    closeQuietly(channel)
                    markerFile.delete()
                    throw exception
                }
            }
        }
        throw IOException("Unable to create a unique video file")
    }

    /** Returns finalized and inactive interrupted recordings, newest first. */
    fun listVideos(): List<File> {
        synchronized(processLock) {
            if (!directory.isDirectory) return emptyList()
            return directory.listFiles { file ->
                file.isFile &&
                    file.name.startsWith(VIDEO_PREFIX) &&
                    file.name.endsWith(VIDEO_EXTENSION, ignoreCase = true) &&
                    isInactive(file)
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
        }
    }

    /**
     * Deletes an inactive recording owned by this store.
     * Active pending files are left alone so a stop/finalize callback cannot
     * race a user initiated delete.
     */
    fun deleteVideo(file: File): Boolean {
        synchronized(processLock) {
            val ownedFile = ownedFileOrNull(file) ?: return false
            val marker = markerFileFor(ownedFile)
            if (!marker.exists()) return !ownedFile.exists() || ownedFile.delete()
            val handle = acquireMarker(marker) ?: return false
            return handle.use {
                val deleted = !ownedFile.exists() || ownedFile.delete()
                if (deleted) marker.delete()
                deleted
            }
        }
    }

    /**
     * Recovers pending outputs left by a previous process.
     * A locked marker belongs to an active recorder and is skipped. A playable
     * output is retained and its marker is removed; an invalid partial output
     * is removed together with its marker. If validation itself fails, the
     * marker is preserved for a later attempt rather than risking data loss.
     */
    fun recoverPendingFiles(isPlayable: (File) -> Boolean?): List<File> {
        synchronized(processLock) {
            if (!directory.isDirectory) return emptyList()

            val recovered = mutableListOf<File>()
            val markers = directory.listFiles { file ->
                file.isFile && file.name.startsWith(".$VIDEO_PREFIX") && file.name.endsWith(PENDING_SUFFIX)
            } ?: return emptyList()

            for (markerFile in markers) {
                val outputFile = outputFileFor(markerFile) ?: continue
                val markerHandle = acquireMarker(markerFile) ?: continue

                markerHandle.use {
                    if (!outputFile.exists()) {
                        markerFile.delete()
                    } else {
                        val playable = try {
                            isPlayable(outputFile)
                        } catch (_: Exception) {
                            null
                        }

                        when (playable) {
                            true -> {
                                recovered += outputFile
                                markerFile.delete()
                            }

                            false -> {
                                outputFile.delete()
                                markerFile.delete()
                            }

                            null -> Unit
                        }
                    }
                }
            }
            return recovered
        }
    }

    private fun isInactive(file: File): Boolean {
        val marker = markerFileFor(file)
        if (!marker.exists()) return true
        val handle = acquireMarker(marker) ?: return false
        return handle.use { true }
    }

    private fun ensureDirectory() {
        if (directory.exists()) {
            if (!directory.isDirectory) {
                throw IOException("Video path is not a directory")
            }
            return
        }
        if (!directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create the app video directory")
        }
    }

    private fun markerFileFor(outputFile: File): File {
        return File(directory, ".${outputFile.name}$PENDING_SUFFIX")
    }

    private fun outputFileFor(markerFile: File): File? {
        val markerName = markerFile.name
        val prefix = ".$VIDEO_PREFIX"
        if (!markerName.startsWith(prefix) || !markerName.endsWith(PENDING_SUFFIX)) return null

        val outputName = markerName
            .removePrefix(".")
            .removeSuffix(PENDING_SUFFIX)
        if (!outputName.startsWith(VIDEO_PREFIX) || !outputName.endsWith(VIDEO_EXTENSION)) return null
        return File(directory, outputName)
    }

    private fun ownedFileOrNull(file: File): File? {
        return try {
            val ownedDirectory = directory.canonicalFile
            val candidate = file.canonicalFile
            if (candidate.parentFile == ownedDirectory &&
                candidate.name.startsWith(VIDEO_PREFIX) &&
                candidate.name.endsWith(VIDEO_EXTENSION, ignoreCase = true)
            ) {
                candidate
            } else {
                null
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun tryAcquireLock(channel: FileChannel): FileLock? {
        return try {
            channel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: IOException) {
            null
        }
    }

    private fun acquireMarker(markerFile: File): MarkerHandle? {
        val channel = try {
            RandomAccessFile(markerFile, "rw").channel
        } catch (_: IOException) {
            return null
        }
        val lock = tryAcquireLock(channel)
        if (lock == null) {
            closeQuietly(channel)
            return null
        }
        return MarkerHandle(channel, lock)
    }

    private fun closeQuietly(closeable: AutoCloseable) {
        try {
            closeable.close()
        } catch (_: Exception) {
            // The original operation already failed; cleanup is best effort.
        }
    }

    private class MarkerHandle(
        private val channel: FileChannel,
        private val lock: FileLock
    ) : AutoCloseable {
        override fun close() {
            try {
                lock.release()
            } finally {
                channel.close()
            }
        }
    }

    internal class PendingRecording internal constructor(
        val outputFile: File,
        private val markerFile: File,
        private val markerChannel: FileChannel,
        private val markerLock: FileLock
    ) {
        private var finished = false

        @Synchronized
        fun complete() {
            finish(deleteOutput = false)
        }

        @Synchronized
        fun discard() {
            finish(deleteOutput = true)
        }

        /**
         * Closes the active lock but keeps both marker and output for a later
         * recovery pass when media validation could not reach a conclusion.
         */
        @Synchronized
        fun preserveForRecovery() {
            finish(deleteOutput = false, deleteMarker = false)
        }

        private fun finish(deleteOutput: Boolean, deleteMarker: Boolean = true) {
            if (finished) return
            finished = true
            synchronized(processLock) {
                try {
                    markerLock.release()
                } catch (_: IOException) {
                    // Continue closing the marker and cleaning up the output.
                } finally {
                    closeQuietly(markerChannel)
                }
                if (deleteMarker) {
                    markerFile.delete()
                } else {
                    // Signal that the output is now available without removing its recovery marker.
                    markerFile.setLastModified(System.currentTimeMillis())
                }
                if (deleteOutput) outputFile.delete()
            }
        }

        private fun closeQuietly(closeable: AutoCloseable) {
            try {
                closeable.close()
            } catch (_: Exception) {
                // Cleanup is best effort after a recording failure.
            }
        }
    }
}
