package com.example.one_take.vision

import android.content.Context
import com.example.one_take.recordingFingerprint
import android.util.AtomicFile
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import org.json.JSONArray
import org.json.JSONObject

/** A recording-relative sample kept beside a raw video. */
internal data class FaceSample(
    val timeMs: Long,
    val centerX: Float,
    val centerY: Float,
    val faceWidth: Float
) {
    init {
        require(timeMs >= 0L) { "timeMs must be non-negative" }
        require(centerX in 0f..1f) { "centerX must be normalized" }
        require(centerY in 0f..1f) { "centerY must be normalized" }
        require(faceWidth in 0f..1f) { "faceWidth must be normalized" }
    }
}

/**
 * Stores face-track metadata separately from the raw recording.
 *
 * The raw MP4 is never rewritten. A sidecar is written through [AtomicFile]
 * so a process death cannot leave a partially written JSON document that is
 * mistaken for a complete track on the next launch.
 */
internal class FaceTrackRepository {
    companion object {
        private const val DIRECTORY_NAME = "videos"
        private const val SIDECAR_SUFFIX = ".faces.json"
        private const val SAMPLES_KEY = "samples"
        private const val TIME_KEY = "timeMs"
        private const val CENTER_X_KEY = "centerX"
        private const val CENTER_Y_KEY = "centerY"
        private const val WIDTH_KEY = "faceWidth"
        private const val SOURCE_LENGTH_KEY = "sourceLength"
        private const val SOURCE_MODIFIED_KEY = "sourceModified"
    }

    private val directory: File

    constructor(context: Context) : this(File(context.filesDir, DIRECTORY_NAME))

    /** Visible for JVM tests and for callers that already own an app directory. */
    internal constructor(directory: File) {
        this.directory = directory
    }

    /** Returns null when a track is absent or malformed. */
    fun read(source: File): List<FaceSample>? {
        val sidecar = sidecarFor(source)
        if (!sidecar.isFile || !source.isFile) return null

        return try {
            val root = JSONObject(sidecar.readText(StandardCharsets.UTF_8))
            if (root.optLong(SOURCE_LENGTH_KEY, Long.MIN_VALUE) != source.length() ||
                root.optLong(SOURCE_MODIFIED_KEY, Long.MIN_VALUE) != source.lastModified()
            ) {
                return null
            }
            val array = root.optJSONArray(SAMPLES_KEY) ?: return null
            if (root.optString("sourceSha256") != recordingFingerprint(source)) return null
            val samples = ArrayList<FaceSample>(array.length())
            var previousTime = -1L
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: return null
                val timeMs = item.optLong(TIME_KEY, Long.MIN_VALUE)
                val centerX = item.optDouble(CENTER_X_KEY, Double.NaN).toFloat()
                val centerY = item.optDouble(CENTER_Y_KEY, Double.NaN).toFloat()
                val faceWidth = item.optDouble(WIDTH_KEY, Double.NaN).toFloat()
                if (timeMs < 0L || timeMs < previousTime ||
                    !centerX.isFinite() || !centerY.isFinite() || !faceWidth.isFinite()
                ) {
                    return null
                }
                samples += FaceSample(timeMs, centerX, centerY, faceWidth)
                previousTime = timeMs
            }
            samples
        } catch (_: Exception) {
            null
        }
    }

    /** Writes a complete replacement sidecar, preserving the source video. */
    @Throws(IOException::class)
    fun write(source: File, samples: List<FaceSample>) {
        require(samples.zipWithNext().all { (left, right) -> left.timeMs <= right.timeMs }) {
            "Face samples must be ordered by time"
        }
        val sidecar = sidecarFor(source)
        if (samples.isEmpty()) {
            remove(source)
            return
        }
        if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create face-track directory")
        }

        val root = JSONObject()
        root.put("sourceSha256", recordingFingerprint(source))
        val array = JSONArray()
        samples.forEach { sample ->
            array.put(
                JSONObject()
                    .put(TIME_KEY, sample.timeMs)
                    .put(CENTER_X_KEY, sample.centerX.toDouble())
                    .put(CENTER_Y_KEY, sample.centerY.toDouble())
                    .put(WIDTH_KEY, sample.faceWidth.toDouble())
            )
        }
        root.put(SAMPLES_KEY, array)
        root.put(SOURCE_LENGTH_KEY, source.length())
        root.put(SOURCE_MODIFIED_KEY, source.lastModified())

        val atomicFile = AtomicFile(sidecar)
        var stream: FileOutputStream? = null
        try {
            val output = atomicFile.startWrite()
            stream = output
            val writer = OutputStreamWriter(output, StandardCharsets.UTF_8)
            writer.write(root.toString())
            writer.flush()
            output.fd.sync()
            atomicFile.finishWrite(output)
            stream = null
        } catch (exception: Exception) {
            stream?.let { atomicFile.failWrite(it) }
            if (exception is IOException) throw exception
            throw IOException("Unable to write face track", exception)
        }
    }

    fun remove(source: File): Boolean {
        val sidecar = sidecarFor(source)
        return !sidecar.exists() || sidecar.delete()
    }

    /** Removes tracks whose raw source no longer exists. */
    fun pruneMissingSources(): Int {
        if (!directory.isDirectory) return 0
        var removed = 0
        directory.listFiles { file -> file.name.endsWith(SIDECAR_SUFFIX) }
            ?.forEach { sidecar ->
                val sourceName = sidecar.name.removePrefix(".").removeSuffix(SIDECAR_SUFFIX)
                val source = File(directory, sourceName)
                if (!source.isFile && sidecar.delete()) removed++
            }
        return removed
    }

    private fun sidecarFor(source: File): File {
        val parent = source.parentFile ?: directory
        return File(parent, ".${source.name}$SIDECAR_SUFFIX")
    }
}
