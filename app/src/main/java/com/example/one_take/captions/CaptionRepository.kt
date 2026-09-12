package com.example.one_take.captions

import android.content.Context
import com.example.one_take.recordingFingerprint
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.StandardCharsets

/**
 * Stores caption timestamps separately from the video file.
 *
 * Caption metadata lives under [Context.noBackupFilesDir], rather than beside
 * recordings, so the video library's file observer never treats metadata as a
 * recording. Each entry is tied to the source file's name, size, and modified
 * time. A caption file for an older version of a recording therefore cannot be
 * accidentally shown for a newer version.
 */
internal class CaptionRepository internal constructor(
    private val metadataDirectory: File
) {
    /** Creates a repository in the app's no-backup metadata directory. */
    internal constructor(context: Context) : this(
        File(context.applicationContext.noBackupFilesDir, DIRECTORY_NAME)
    )

    companion object {
        private const val SCHEMA_VERSION = 1
        private const val DIRECTORY_NAME = "caption_metadata"
        private const val CAPTION_SUFFIX = ".captions.json"
        private const val SOURCE_KEY = "source"
        private const val SOURCE_NAME_KEY = "name"
        private const val SOURCE_SIZE_KEY = "size"
        private const val SOURCE_MODIFIED_KEY = "lastModified"
        private const val SEGMENTS_KEY = "segments"
        private const val START_KEY = "startMs"
        private const val END_KEY = "endMs"
        private const val TEXT_KEY = "text"
        private const val WORDS_KEY = "words"
        private const val CONFIDENCE_KEY = "confidence"

    }

    /**
     * Reads captions for [source], or returns null when no valid matching
     * metadata exists.
     */
    fun read(source: File): List<CaptionSegment>? {
        if (!source.isFile) return null

        val metadataFile = metadataFileFor(source)
        if (!metadataFile.isFile) return null

        return try {
            val root = JSONObject(metadataFile.readText(StandardCharsets.UTF_8))
            if (root.optInt("schemaVersion", -1) != SCHEMA_VERSION) return null

            val sourceObject = root.optJSONObject(SOURCE_KEY) ?: return null
            if (!sourceObject.has(SOURCE_NAME_KEY) ||
                sourceObject.getString(SOURCE_NAME_KEY) != source.name
            ) return null
            if (!sourceObject.has(SOURCE_SIZE_KEY) ||
                sourceObject.getLong(SOURCE_SIZE_KEY) != source.length()
            ) {
                return null
            }
            if (!sourceObject.has(SOURCE_MODIFIED_KEY) ||
                sourceObject.getLong(SOURCE_MODIFIED_KEY) != source.lastModified()
            ) {
                return null
            }

            // Legacy captions remain readable. Every new save is content-bound.
            if (sourceObject.has("sha256") &&
                sourceObject.getString("sha256") != recordingFingerprint(source)) return null
            val array = root.optJSONArray(SEGMENTS_KEY) ?: return null
            parseSegments(array)
        } catch (_: Exception) {
            // A partial write or an older schema should be retried by the caller
            // after the source has settled, rather than breaking video playback.
            null
        }
    }

    /**
     * Atomically replaces the caption metadata for [source]. The source video
     * itself is never changed.
     */
    @Throws(IOException::class, IllegalArgumentException::class)
    fun write(source: File, segments: List<CaptionSegment>) {
        require(source.isFile) { "Caption source does not exist: ${source.path}" }
        validateSegments(segments)

        val root = JSONObject()
            .put("schemaVersion", SCHEMA_VERSION)
            .put(
                SOURCE_KEY,
                JSONObject()
                    .put(SOURCE_NAME_KEY, source.name)
                    .put(SOURCE_SIZE_KEY, source.length())
                    .put(SOURCE_MODIFIED_KEY, source.lastModified())
                    .put("sha256", recordingFingerprint(source))
            )
            .put(SEGMENTS_KEY, segments.toJson())

        val metadataFile = metadataFileFor(source)
        ensureDirectory()
        val temporaryFile = File.createTempFile(".${metadataFile.name}.", ".tmp", metadataDirectory)
        try {
            FileOutputStream(temporaryFile).use { stream ->
                stream.write(root.toString().toByteArray(StandardCharsets.UTF_8))
                stream.flush()
                stream.fd.sync()
            }
            moveAtomically(temporaryFile, metadataFile)
        } finally {
            // The temp file is our only write artifact. It is safe to remove it
            // if a move failed, while an existing metadata file stays intact.
            temporaryFile.delete()
        }
    }

    fun remove(source: File): Boolean {
        val metadata = metadataFileFor(source)
        return !metadata.exists() || metadata.delete()
    }

    fun pruneMissingSources(videoDirectory: File) {
        metadataDirectory.listFiles()?.filter { it.name.endsWith(CAPTION_SUFFIX) }?.forEach { metadata ->
            val sourceName = metadata.name.removeSuffix(CAPTION_SUFFIX)
            if (!File(videoDirectory, sourceName).isFile) metadata.delete()
        }
    }

    private fun metadataFileFor(source: File): File {
        // source.name cannot contain a path separator, so this stays inside the
        // dedicated no-backup directory even when the recording was renamed.
        return File(metadataDirectory, source.name + CAPTION_SUFFIX)
    }

    private fun ensureDirectory() {
        if (metadataDirectory.exists()) {
            require(metadataDirectory.isDirectory) {
                "Caption metadata path is not a directory: ${metadataDirectory.path}"
            }
            return
        }
        if (!metadataDirectory.mkdirs() && !metadataDirectory.isDirectory) {
            throw IOException("Unable to create caption metadata directory")
        }
    }

    private fun moveAtomically(source: File, destination: File) {
        // Both files are in the same directory. Android's rename is atomic on
        // the filesystem used by app-private storage and keeps API 24 support.
        if (!source.renameTo(destination)) {
            throw IOException("Unable to publish caption metadata")
        }
    }

    private fun parseSegments(array: JSONArray): List<CaptionSegment>? {
        val parsed = ArrayList<CaptionSegment>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return null
            if (!item.has(START_KEY) || !item.has(END_KEY) || !item.has(TEXT_KEY)) return null

            val startMs = item.getLong(START_KEY)
            val endMs = item.getLong(END_KEY)
            val segment = CaptionSegment(
                startMs = startMs,
                endMs = endMs,
                text = item.getString(TEXT_KEY),
                words = if (item.has(WORDS_KEY)) {
                    parseWords(item.optJSONArray(WORDS_KEY) ?: return null, startMs, endMs)
                        ?: return null
                } else {
                    emptyList()
                },
            )
            if (!isValid(segment)) return null
            if (parsed.lastOrNull()?.let { segment.startMs < it.endMs } == true) return null
            parsed += segment
        }
        return parsed
    }

    private fun validateSegments(segments: List<CaptionSegment>) {
        var previousEnd = 0L
        segments.forEachIndexed { index, segment ->
            require(isValid(segment)) { "Invalid caption segment at index $index" }
            require(index == 0 || segment.startMs >= previousEnd) {
                "Caption segments must be sorted and non-overlapping"
            }
            previousEnd = segment.endMs
        }
    }

    private fun isValid(segment: CaptionSegment): Boolean {
        return segment.startMs >= 0L &&
            segment.endMs > segment.startMs &&
            segment.text.isNotBlank() &&
            segment.hasReliableWordEvidence() &&
            segment.words.zipWithNext().all { (left, right) ->
                right.startMs >= left.endMs
            } &&
            segment.words.all { isValid(it, segment) }
    }

    private fun isValid(word: CaptionWord, segment: CaptionSegment): Boolean {
        return word.startMs >= segment.startMs &&
            word.endMs > word.startMs &&
            word.endMs <= segment.endMs &&
            word.text.isNotBlank() &&
            word.confidence.isFinite() &&
            word.confidence in 0f..1f
    }

    private fun parseWords(array: JSONArray, segmentStartMs: Long, segmentEndMs: Long): List<CaptionWord>? {
        val parsed = ArrayList<CaptionWord>(array.length())
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: return null
            if (!item.has(START_KEY) || !item.has(END_KEY) ||
                !item.has(TEXT_KEY) || !item.has(CONFIDENCE_KEY)
            ) {
                return null
            }
            val word = CaptionWord(
                startMs = item.getLong(START_KEY),
                endMs = item.getLong(END_KEY),
                text = item.getString(TEXT_KEY),
                confidence = item.getDouble(CONFIDENCE_KEY).toFloat(),
            )
            val segment = CaptionSegment(segmentStartMs, segmentEndMs, "word")
            if (!isValid(word, segment)) return null
            if (parsed.lastOrNull()?.let { word.startMs < it.endMs } == true) return null
            parsed += word
        }
        return parsed
    }

    private fun List<CaptionSegment>.toJson(): JSONArray {
        return JSONArray().also { array ->
            forEach { segment ->
                val item = JSONObject()
                        .put(START_KEY, segment.startMs)
                        .put(END_KEY, segment.endMs)
                        .put(TEXT_KEY, segment.text)
                if (segment.words.isNotEmpty()) item.put(WORDS_KEY, segment.words.wordsToJson())
                array.put(item)
            }
        }
    }

    private fun List<CaptionWord>.wordsToJson(): JSONArray {
        return JSONArray().also { array ->
            forEach { word ->
                array.put(
                    JSONObject()
                        .put(START_KEY, word.startMs)
                        .put(END_KEY, word.endMs)
                        .put(TEXT_KEY, word.text)
                        .put(CONFIDENCE_KEY, word.confidence.toDouble())
                )
            }
        }
    }
}
