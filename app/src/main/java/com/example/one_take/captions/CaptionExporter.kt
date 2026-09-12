package com.example.one_take.captions

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import com.example.one_take.editing.EditDecision
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.common.MediaItem
import androidx.media3.common.util.Size
import androidx.media3.common.util.Clock
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Burns timed caption segments into a new MP4 using Media3 Transformer.
 *
 * The input file and any pre-existing output are never modified. Transformer
 * writes to a private sibling temporary file and the completed result is moved
 * into place only after the listener reports success.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal class CaptionExporter(private val context: Context) {
    suspend fun export(
        source: File,
        captions: List<CaptionSegment>,
        output: File,
        edits: EditDecision? = null,
        preset: CaptionPreset = CaptionPreset.CLEAN,
        onProgress: (Int) -> Unit = {}
    ): File {
        require(source.isFile) { "Caption source does not exist: ${source.path}" }
        require(source.canonicalFile != output.canonicalFile) {
            "Caption output must be different from its source"
        }
        if (output.exists()) throw IOException("Caption output already exists: ${output.path}")

        val parent = output.parentFile ?: throw IllegalArgumentException("Caption output has no parent")
        require(parent.isDirectory || parent.mkdirs()) {
            "Unable to create caption output directory: ${parent.path}"
        }
        val sourceCaptions = captions.sortedBy { it.startMs }
        validateSegments(sourceCaptions)
        val boundedCaptions = if (edits == null) sourceCaptions else sourceCaptions.mapNotNull { caption ->
            val end = caption.endMs.coerceAtMost(edits.durationMs)
            if (end > caption.startMs) caption.copy(endMs = end) else null
        }
        val normalizedCaptions = edits?.mapCaptions(boundedCaptions) ?: boundedCaptions
        validateSegments(normalizedCaptions)

        onProgress(0)
        val temporaryOutput = File.createTempFile(".${output.name}.", ".mp4", parent)
        // Transformer expects to own the output path. createTempFile reserves a
        // unique name for us, then the empty reservation must be removed.
        check(temporaryOutput.delete()) { "Unable to reserve caption output" }

        var outputReserved = false
        try {
            // Reserve the destination after the preflight check. Transformer
            // writes to the sibling temp path, and the final rename replaces
            // only this empty reservation, never another caller's output.
            if (!output.createNewFile()) {
                throw IOException("Caption output already exists: ${output.path}")
            }
            outputReserved = true
            withContext(Dispatchers.Main.immediate) {
                exportOnMain(source, normalizedCaptions, temporaryOutput, edits, preset, onProgress)
            }
            moveIntoPlace(temporaryOutput, output)
            outputReserved = false
            onProgress(100)
            return output
        } catch (cancelled: CancellationException) {
            temporaryOutput.delete()
            if (outputReserved && output.length() == 0L) output.delete()
            throw cancelled
        } catch (failure: Throwable) {
            temporaryOutput.delete()
            if (outputReserved && output.length() == 0L) output.delete()
            throw failure
        }
    }

    private suspend fun exportOnMain(
        source: File,
        captions: List<CaptionSegment>,
        temporaryOutput: File,
        edits: EditDecision?,
        preset: CaptionPreset,
        onProgress: (Int) -> Unit
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Media3 Transformer must be accessed from the main thread"
        }

        val overlay = TimedCaptionOverlay(captions, preset)
        val videoEffects: List<TextureOverlay> = listOf(overlay)
        val effects = Effects(emptyList(), listOf(OverlayEffect(videoEffects)))
        val ranges = edits?.takeIf { decision -> decision.cuts.any { it.enabled } }?.keptRanges()
        val items = ranges?.map { range ->
            val media = MediaItem.Builder().setUri(Uri.fromFile(source))
                .setClippingConfiguration(MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(range.startMs).setEndPositionMs(range.endMs).build())
                .build()
            EditedMediaItem.Builder(media).build()
        } ?: listOf(EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source))).build())
        // Composition effects receive the final concatenated timeline, so captions
        // use edited timestamps once regardless of how many source clips remain.
        val composition = Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(items))
            .setEffects(effects).build()

        val decoder = DefaultDecoderFactory.Builder(context.applicationContext)
            .setShouldConfigureOperatingRate(true)
            .build()
        val transformer = Transformer.Builder(context.applicationContext)
            .setAssetLoaderFactory(DefaultAssetLoaderFactory(context.applicationContext, decoder, Clock.DEFAULT, null))
            .build()
        var listener: Transformer.Listener? = null
        val progressJob: Job = CoroutineScope(currentCoroutineContext()).launch {
            val progressHolder = ProgressHolder()
            var lastProgress = 0
            while (isActive) {
                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val progress = progressHolder.progress.coerceIn(lastProgress, 99)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        onProgress(progress)
                    }
                }
                delay(PROGRESS_POLL_INTERVAL_MS)
            }
        }

        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val started = AtomicBoolean(false)
                val mainHandler = Handler(Looper.getMainLooper())
                val exportListener = object : Transformer.Listener {
                    override fun onCompleted(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult
                    ) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: androidx.media3.transformer.ExportResult,
                        exportException: ExportException
                    ) {
                        if (continuation.isActive) continuation.resumeWithException(exportException)
                    }
                }
                listener = exportListener
                transformer.addListener(exportListener)
                continuation.invokeOnCancellation {
                    // Transformer.cancel must run on its application looper.
                    mainHandler.post {
                        if (started.get()) transformer.cancel()
                    }
                }

                try {
                    if (continuation.isActive) {
                        started.set(true)
                        transformer.start(composition, temporaryOutput.path)
                    }
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        } finally {
            progressJob.cancel()
            listener?.let { transformer.removeListener(it) }
        }
    }

    private fun moveIntoPlace(temporaryOutput: File, output: File) {
        // Both paths are siblings in app-private storage. renameTo is atomic
        // on the Android filesystems used here and is available from API 24.
        if (!temporaryOutput.renameTo(output)) {
            throw IOException("Unable to publish captioned video")
        }
    }

    private fun validateSegments(segments: List<CaptionSegment>) {
        var previousEnd = 0L
        segments.forEachIndexed { index, segment ->
            require(segment.startMs >= 0L && segment.endMs > segment.startMs) {
                "Invalid caption interval at index $index"
            }
            require(segment.text.isNotBlank()) { "Caption text is blank at index $index" }
            require(index == 0 || segment.startMs >= previousEnd) {
                "Caption segments must be sorted and non-overlapping"
            }
            previousEnd = segment.endMs
        }
    }

    private class TimedCaptionOverlay(
        private val segments: List<CaptionSegment>,
        private val preset: CaptionPreset,
    ) : TextOverlay() {
        private val settings = StaticOverlaySettings.Builder()
            // Keep two lines of captions above the bottom safe area.
            .setBackgroundFrameAnchor(0f, 2f * preset.bottomAnchor - 1f)
            .setOverlayFrameAnchor(0f, -1f)
            .build()
        private val hiddenSettings = StaticOverlaySettings.Builder()
            .setAlphaScale(0f)
            .build()
        // TextOverlay requires a non-zero bitmap even when no caption is active.
        private val blankText = SpannableString(" ")
        private val measurePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = TEXT_SIZE_PIXELS.toFloat()
        }
        private var textSizeScale = 0.5f
        private var maxTextWidth = DEFAULT_MAX_TEXT_WIDTH
        private val wrappedText = HashMap<Int, SpannableString>()

        override fun configure(videoSize: Size) {
            super.configure(videoSize)
            maxTextWidth = (videoSize.width * MAX_TEXT_WIDTH_FRACTION).coerceAtLeast(1f)
            textSizeScale = (videoSize.width * preset.textSizeFraction / TEXT_SIZE_PIXELS).coerceAtLeast(0.18f)
            measurePaint.textSize = TEXT_SIZE_PIXELS * textSizeScale
            wrappedText.clear()
        }

        override fun getText(presentationTimeUs: Long): SpannableString {
            val timeMs = presentationTimeUs / 1_000L
            val index = segmentIndexAt(timeMs)
            if (index < 0) return blankText
            return wrappedText.getOrPut(index) {
                styledText(wrapText(segments[index].text, maxTextWidth))
            }
        }

        override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings {
            return if (segmentIndexAt(presentationTimeUs / 1_000L) < 0) hiddenSettings else settings
        }

        private fun segmentIndexAt(timeMs: Long): Int {
            var low = 0
            var high = segments.lastIndex
            var candidate = -1
            while (low <= high) {
                val middle = (low + high) ushr 1
                if (segments[middle].startMs <= timeMs) {
                    candidate = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return if (candidate >= 0 && timeMs < segments[candidate].endMs) candidate else -1
        }

        private fun styledText(text: String): SpannableString {
            return SpannableString(text).also { spannable ->
                if (spannable.isNotEmpty()) {
                    if (preset.bold) spannable.setSpan(StyleSpan(Typeface.BOLD), 0,
                        spannable.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    spannable.setSpan(
                        ForegroundColorSpan(preset.textColorArgb),
                        0,
                        spannable.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        BackgroundColorSpan(preset.backgroundColorArgb),
                        0,
                        spannable.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                    spannable.setSpan(
                        RelativeSizeSpan(textSizeScale),
                        0,
                        spannable.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            }
        }

        private fun wrapText(text: String, maxWidth: Float): String {
            val normalized = text.replace("\r\n", "\n").trim()
            if (normalized.isEmpty()) return normalized
            return normalized.split('\n').joinToString("\n") { line ->
                wrapLine(line.trim(), maxWidth)
            }
        }

        private fun wrapLine(line: String, maxWidth: Float): String {
            if (line.isEmpty()) return line
            val words = line.split(Regex("\\s+"))
            val lines = ArrayList<String>()
            var current = ""
            for (word in words) {
                val candidate = if (current.isEmpty()) word else "$current $word"
                if (current.isEmpty() || measurePaint.measureText(candidate) <= maxWidth) {
                    current = candidate
                } else {
                    lines += current
                    current = word
                }
            }
            if (current.isNotEmpty()) lines += current
            return lines.joinToString("\n")
        }

        companion object {
            private const val TEXT_SIZE_PIXELS = 100
            private const val MAX_TEXT_WIDTH_FRACTION = 0.86f
            private const val DEFAULT_MAX_TEXT_WIDTH = 900f
        }
    }

    companion object {
        private const val PROGRESS_POLL_INTERVAL_MS = 150L
    }
}
