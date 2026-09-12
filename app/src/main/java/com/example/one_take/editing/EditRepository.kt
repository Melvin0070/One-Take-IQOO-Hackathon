package com.example.one_take.editing

import android.content.Context
import com.example.one_take.recordingFingerprint
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Stores non-destructive edit decisions separately from the source video. */
internal class EditRepository internal constructor(
    private val metadataDirectory: File,
) {
    internal constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)
    )

    /** Reads a valid decision for [source], or null when metadata is absent/stale. */
    fun read(source: File): EditDecision? {
        if (!source.isFile) return null
        val metadataFile = metadataFileFor(source)
        if (!metadataFile.isFile) return null

        return try {
            val root = JSONObject(metadataFile.readText(StandardCharsets.UTF_8))
            if (root.optInt(SCHEMA_VERSION_KEY, -1) != SCHEMA_VERSION) return null

            val sourceObject = root.optJSONObject(SOURCE_KEY) ?: return null
            if (sourceObject.optString(PATH_KEY) != sourceIdentity(source)) return null
            if (!sourceObject.has(NAME_KEY) || sourceObject.getString(NAME_KEY) != source.name) return null
            if (!sourceObject.has(SIZE_KEY) || sourceObject.getLong(SIZE_KEY) != source.length()) return null
            if (!sourceObject.has(MODIFIED_KEY) ||
                sourceObject.getLong(MODIFIED_KEY) != source.lastModified()
            ) {
                return null
            }

            val cuts = parseCuts(root.optJSONArray(CUTS_KEY) ?: return null) ?: return null
            if (sourceObject.has("sha256") && sourceObject.getString("sha256") != recordingFingerprint(source)) return null
            EditDecision(root.getLong(DURATION_KEY), cuts)
        } catch (_: Exception) {
            // Corrupt or partially-written metadata must not break playback.
            null
        }
    }

    /** Atomically replaces the decision metadata. The source video is untouched. */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun write(source: File, decision: EditDecision) {
        require(source.isFile) { "Edit source does not exist: ${source.path}" }

        val root = JSONObject()
            .put(SCHEMA_VERSION_KEY, SCHEMA_VERSION)
            .put(
                SOURCE_KEY,
                JSONObject()
                    .put(PATH_KEY, sourceIdentity(source))
                    .put(NAME_KEY, source.name)
                    .put(SIZE_KEY, source.length())
                    .put(MODIFIED_KEY, source.lastModified())
                    .put("sha256", recordingFingerprint(source))
            )
            .put(DURATION_KEY, decision.durationMs)
            .put(CUTS_KEY, decision.cuts.toJson())

        val metadataFile = metadataFileFor(source)
        ensureDirectory()
        val temporaryFile = File.createTempFile(".${metadataFile.name}.", ".tmp", metadataDirectory)
        try {
            FileOutputStream(temporaryFile).use { stream ->
                stream.write(root.toString().toByteArray(StandardCharsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            if (!temporaryFile.renameTo(metadataFile)) {
                throw IOException("Unable to publish edit metadata")
            }
        } finally {
            temporaryFile.delete()
        }
    }

    fun remove(source: File): Boolean {
        val metadataFile = metadataFileFor(source)
        return !metadataFile.exists() || metadataFile.delete()
    }

    /** Removes metadata whose source is no longer a file in [videoDirectory]. */
    fun pruneMissingSources(videoDirectory: File) {
        val directory = try {
            videoDirectory.canonicalFile
        } catch (_: IOException) {
            videoDirectory.absoluteFile
        }

        metadataDirectory.listFiles()
            ?.filter { it.name.endsWith(METADATA_SUFFIX) }
            ?.forEach { metadata ->
                val sourcePath = try {
                    val root = JSONObject(metadata.readText(StandardCharsets.UTF_8))
                    root.optJSONObject(SOURCE_KEY)?.optString(PATH_KEY).orEmpty()
                } catch (_: Exception) {
                    ""
                }
                if (sourcePath.isBlank()) return@forEach

                val source = File(sourcePath)
                val sourceParent = try {
                    source.canonicalFile.parentFile
                } catch (_: IOException) {
                    source.absoluteFile.parentFile
                }
                if (sourceParent != directory || !source.isFile) metadata.delete()
            }
    }

    private fun metadataFileFor(source: File): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(sourceIdentity(source).toByteArray(StandardCharsets.UTF_8))
        return File(metadataDirectory, digest.toHex() + METADATA_SUFFIX)
    }

    private fun sourceIdentity(source: File): String = try {
        source.canonicalPath
    } catch (_: IOException) {
        source.absolutePath
    }

    private fun ensureDirectory() {
        if (metadataDirectory.exists()) {
            require(metadataDirectory.isDirectory) {
                "Edit metadata path is not a directory: ${metadataDirectory.path}"
            }
            return
        }
        if (!metadataDirectory.mkdirs() && !metadataDirectory.isDirectory) {
            throw IOException("Unable to create edit metadata directory")
        }
    }

    private fun parseCuts(array: JSONArray): List<EditCut>? {
        val cuts = ArrayList<EditCut>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return null
            if (!item.has(ID_KEY) || !item.has(START_KEY) || !item.has(END_KEY) ||
                !item.has(REASON_KEY) || !item.has(ENABLED_KEY)
            ) {
                return null
            }
            cuts += EditCut(
                id = item.getString(ID_KEY),
                startMs = item.getLong(START_KEY),
                endMs = item.getLong(END_KEY),
                reason = item.getString(REASON_KEY),
                enabled = item.getBoolean(ENABLED_KEY),
            )
        }
        return cuts
    }

    private fun List<EditCut>.toJson(): JSONArray = JSONArray().also { array ->
        forEach { cut ->
            array.put(
                JSONObject()
                    .put(ID_KEY, cut.id)
                    .put(START_KEY, cut.startMs)
                    .put(END_KEY, cut.endMs)
                    .put(REASON_KEY, cut.reason)
                    .put(ENABLED_KEY, cut.enabled)
            )
        }
    }

    private fun ByteArray.toHex(): String {
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = HEX[value ushr 4]
            chars[index * 2 + 1] = HEX[value and 0x0f]
        }
        return String(chars)
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val DIRECTORY_NAME = "edit_metadata"
        private const val METADATA_SUFFIX = ".edits.json"
        private const val SCHEMA_VERSION_KEY = "schemaVersion"
        private const val SOURCE_KEY = "source"
        private const val PATH_KEY = "path"
        private const val NAME_KEY = "name"
        private const val SIZE_KEY = "size"
        private const val MODIFIED_KEY = "lastModified"
        private const val DURATION_KEY = "durationMs"
        private const val CUTS_KEY = "cuts"
        private const val ID_KEY = "id"
        private const val START_KEY = "startMs"
        private const val END_KEY = "endMs"
        private const val REASON_KEY = "reason"
        private const val ENABLED_KEY = "enabled"
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
