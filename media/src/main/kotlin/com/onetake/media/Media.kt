package com.onetake.media

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.onetake.engine.EditList
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Playback lifecycle exposed to the review screen. */
sealed interface PlaybackState {
    data object Idle : PlaybackState
    data object Preparing : PlaybackState
    data object Ready : PlaybackState
    data object Playing : PlaybackState
    data object Ended : PlaybackState
    data class Failed(val cause: Throwable) : PlaybackState
    data object Released : PlaybackState
}

/**
 * Plays the edit list immediately after its source videos have finalized.
 *
 * Every segment is one clipped playlist item. A segment's sample indices are
 * converted through the anchor belonging to that item's source key, so a
 * pickup from a later session cannot accidentally use the first session's
 * video clock.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CutPlayer(context: Context) : AutoCloseable {
    private val playerLooper: Looper
    private val listener: Player.Listener
    private var released = false
    private var firstFrameStartElapsedRealtime: Long? = null
    private val _firstFrameMillis = MutableStateFlow<Long?>(null)
    private val _state = MutableStateFlow<PlaybackState>(PlaybackState.Idle)

    val player: ExoPlayer
    val state: StateFlow<PlaybackState> = _state.asStateFlow()
    /** Emits null until Media3 has rendered the first video frame. */
    val firstFrameMillis: StateFlow<Long?> = _firstFrameMillis.asStateFlow()

    init {
        player = ExoPlayer.Builder(context.applicationContext).build()
        playerLooper = player.applicationLooper
        listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (released) return
                _state.value = when (playbackState) {
                    Player.STATE_IDLE -> PlaybackState.Idle
                    Player.STATE_BUFFERING -> PlaybackState.Preparing
                    Player.STATE_READY -> if (player.isPlaying) {
                        PlaybackState.Playing
                    } else {
                        PlaybackState.Ready
                    }
                    Player.STATE_ENDED -> PlaybackState.Ended
                    else -> PlaybackState.Idle
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (released || player.playbackState != Player.STATE_READY) return
                _state.value = if (isPlaying) PlaybackState.Playing else PlaybackState.Ready
            }

            override fun onRenderedFirstFrame() {
                val started = firstFrameStartElapsedRealtime ?: return
                if (_firstFrameMillis.value == null) {
                    _firstFrameMillis.value =
                        (SystemClock.elapsedRealtime() - started).coerceAtLeast(0L)
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (!released) _state.value = PlaybackState.Failed(error)
            }
        }
        player.addListener(listener)
    }

    /**
     * Replaces the playlist and prepares it for playback.
     *
     * Callers must create each [FinalizedVideo] only after
     * `VideoRecordEvent.Finalize` reports success. The planner checks the file
     * again before any Media3 state is changed.
     */
    fun prepare(
        editList: EditList,
        sources: Collection<FinalizedVideo>,
        playWhenReady: Boolean = true,
    ): List<MediaClipPlan> {
        check(!released) { "CutPlayer has been released" }
        check(Looper.myLooper() == playerLooper) {
            "CutPlayer methods must run on the player's application looper"
        }
        val plans = MediaClipPlanner.plan(editList, sources)
        val items = plans.map { it.toMediaItem() }
        _firstFrameMillis.value = null
        firstFrameStartElapsedRealtime = if (items.isEmpty()) {
            null
        } else {
            SystemClock.elapsedRealtime()
        }
        if (items.isEmpty()) {
            player.clearMediaItems()
            _state.value = PlaybackState.Idle
        } else {
            _state.value = PlaybackState.Preparing
            player.setMediaItems(items, true)
            player.playWhenReady = playWhenReady
            player.prepare()
        }
        return plans
    }

    /** The measured stop-to-first-frame value, or null before a frame renders. */
    fun stopToFirstFrameMillis(): Long? = _firstFrameMillis.value

    fun release() {
        if (released) return
        check(Looper.myLooper() == playerLooper) {
            "CutPlayer.release must run on the player's application looper"
        }
        released = true
        player.removeListener(listener)
        player.release()
        _state.value = PlaybackState.Released
    }

    override fun close() = release()
}

/** State of a foreground export operation. */
sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val progressPercent: Int) : ExportState
    data class Completed(val result: ExportResultInfo) : ExportState
    data class Failed(val cause: Throwable) : ExportState
    data object Cancelled : ExportState
}

data class ExportRequest(
    val editList: EditList,
    val sources: List<FinalizedVideo>,
    val captions: List<CaptionCue>,
    val output: File,
)

data class ExportResultInfo(
    val output: File,
    val elapsedMillis: Long,
    val filmedDurationMillis: Long,
) {
    val millisPerFilmedMinute: Long
        get() = if (filmedDurationMillis == 0L) 0L else
            (elapsedMillis * 60_000L) / filmedDurationMillis
}

/**
 * Burns captions into a new MP4 using Media3 Transformer.
 *
 * The operation is deliberately foreground-owned: the caller supplies its
 * foreground coroutine scope and renders [state] or the callback's states as
 * visible progress. This class does not enqueue background work.
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class CutExporter(context: Context) {
    private val applicationContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activeTransformer = AtomicReference<Transformer?>(null)
    private val exportActive = AtomicBoolean(false)
    private val cancelRequested = AtomicBoolean(false)
    private val _state = MutableStateFlow<ExportState>(ExportState.Idle)
    private var lastMillisPerFilmedMinute: Long? = null

    val state: StateFlow<ExportState> = _state.asStateFlow()
    val results: Flow<ExportState> = state

    /**
     * Runs one export in the caller's foreground coroutine.
     *
     * The callback is invoked for every visible state transition on the
     * Media3 main looper while the Transformer is active.
     */
    suspend fun export(
        request: ExportRequest,
        onState: (ExportState) -> Unit = {},
    ): ExportResultInfo {
        check(exportActive.compareAndSet(false, true)) { "An export is already running" }
        cancelRequested.set(false)
        var temporaryOutput: File? = null
        val elapsedStart = SystemClock.elapsedRealtime()
        try {
            val plans = MediaClipPlanner.plan(request.editList, request.sources)
            require(plans.isNotEmpty()) { "Cannot export an empty edit list" }
            val mappedCaptions = CaptionTimelineMapper.map(request.captions, plans)
            checkOutputPath(request.output, plans)
            val parent = request.output.parentFile
                ?: throw IllegalArgumentException("Export output has no parent directory")
            require(parent.isDirectory || parent.mkdirs()) {
                "Unable to create export output directory: ${parent.path}"
            }
            if (request.output.exists()) {
                throw IOException("Export output already exists: ${request.output.path}")
            }
            temporaryOutput = reserveTemporaryOutput(request.output, parent)
            emit(ExportState.Running(0), onState)
            checkNotCancelled()
            withContext(Dispatchers.Main.immediate) {
                exportOnMain(
                    plans = plans,
                    captions = mappedCaptions,
                    temporaryOutput = temporaryOutput,
                    onState = onState,
                )
            }
            checkNotCancelled()
            publishAtomically(temporaryOutput, request.output)
            temporaryOutput = null
            val result = ExportResultInfo(
                output = request.output,
                elapsedMillis = (SystemClock.elapsedRealtime() - elapsedStart).coerceAtLeast(0L),
                filmedDurationMillis = plans.sumOf { it.sourceEndMs - it.sourceStartMs },
            )
            lastMillisPerFilmedMinute = result.millisPerFilmedMinute
            emit(ExportState.Completed(result), onState)
            return result
        } catch (cancelled: CancellationException) {
            temporaryOutput?.delete()
            emit(ExportState.Cancelled, onState)
            throw cancelled
        } catch (failure: Throwable) {
            temporaryOutput?.delete()
            emit(ExportState.Failed(failure), onState)
            throw failure
        } finally {
            activeTransformer.set(null)
            cancelRequested.set(false)
            exportActive.set(false)
        }
    }

    suspend fun export(
        editList: EditList,
        sources: Collection<FinalizedVideo>,
        captions: List<CaptionCue>,
        output: File,
        onState: (ExportState) -> Unit = {},
    ): ExportResultInfo = export(
        ExportRequest(editList, sources.toList(), captions, output),
        onState,
    )

    /** Requests cancellation on the Transformer looper and leaves the inputs untouched. */
    fun cancel() {
        if (!exportActive.get()) return
        cancelRequested.set(true)
        val cancel = Runnable { activeTransformer.get()?.let { it.cancel() } }
        if (Looper.myLooper() == Looper.getMainLooper()) cancel.run() else mainHandler.post(cancel)
    }

    /** Returns the last completed export's measured time per filmed minute. */
    fun measuredExportMillisPerFilmedMinute(): Long? = lastMillisPerFilmedMinute

    private suspend fun exportOnMain(
        plans: List<MediaClipPlan>,
        captions: List<MappedCaption>,
        temporaryOutput: File,
        onState: (ExportState) -> Unit,
    ) {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "Media3 Transformer must be accessed from the main looper"
        }
        val items = plans.map { it.toEditedMediaItem() }
        val videoEffects: List<TextureOverlay> = if (captions.isEmpty()) {
            emptyList()
        } else {
            listOf(TimedCaptionOverlay(captions))
        }
        val effects = if (videoEffects.isEmpty()) {
            Effects(emptyList(), emptyList())
        } else {
            Effects(emptyList(), listOf(OverlayEffect(videoEffects)))
        }
        val composition = Composition.Builder(EditedMediaItemSequence.withAudioAndVideoFrom(items))
            .setEffects(effects)
            .build()
        val decoder = DefaultDecoderFactory.Builder(applicationContext)
            .setShouldConfigureOperatingRate(true)
            .build()
        val transformer = Transformer.Builder(applicationContext)
            .setAssetLoaderFactory(
                DefaultAssetLoaderFactory(
                    applicationContext,
                    decoder,
                    androidx.media3.common.util.Clock.DEFAULT,
                    null,
                ),
            )
            .build()
        activeTransformer.set(transformer)
        val progressJob: Job = CoroutineScope(currentCoroutineContext()).launch {
            val progressHolder = ProgressHolder()
            var lastProgress = 0
            while (isActive) {
                if (transformer.getProgress(progressHolder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                    val progress = progressHolder.progress.coerceIn(lastProgress, 99)
                    if (progress != lastProgress) {
                        lastProgress = progress
                        emit(ExportState.Running(progress), onState)
                    }
                }
                delay(PROGRESS_POLL_INTERVAL_MS)
            }
        }
        var listener: Transformer.Listener? = null
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val started = AtomicBoolean(false)
                val exportListener = object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onError(
                        composition: Composition,
                        exportResult: ExportResult,
                        exportException: ExportException,
                    ) {
                        if (!continuation.isActive) return
                        if (cancelRequested.get()) {
                            continuation.cancel(CancellationException("Export cancelled"))
                        } else {
                            continuation.resumeWithException(exportException)
                        }
                    }
                }
                listener = exportListener
                transformer.addListener(exportListener)
                continuation.invokeOnCancellation {
                    mainHandler.post {
                        if (started.get()) transformer.cancel()
                    }
                }
                try {
                    checkNotCancelled()
                    started.set(true)
                    transformer.start(composition, temporaryOutput.path)
                } catch (failure: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        } finally {
            progressJob.cancel()
            listener?.let(transformer::removeListener)
            activeTransformer.compareAndSet(transformer, null)
        }
    }

    private fun checkOutputPath(output: File, plans: List<MediaClipPlan>) {
        val outputPath = output.canonicalFile
        require(plans.none { it.source.file.canonicalFile == outputPath }) {
            "Export output must be different from every source video"
        }
    }

    private fun reserveTemporaryOutput(output: File, parent: File): File {
        val temporary = File.createTempFile(".${output.name}.", ".part", parent)
        check(temporary.delete()) { "Unable to reserve temporary export path" }
        return temporary
    }

    private fun publishAtomically(temporary: File, output: File) {
        try {
            Files.move(
                temporary.toPath(),
                output.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            if (output.exists() || !temporary.renameTo(output)) {
                throw IOException("Unable to publish export without overwriting an existing file")
            }
        }
    }

    private fun checkNotCancelled() {
        if (cancelRequested.get()) throw CancellationException("Export cancelled")
    }

    private fun emit(next: ExportState, onState: (ExportState) -> Unit) {
        _state.value = next
        onState(next)
    }

    private class TimedCaptionOverlay(
        private val captions: List<MappedCaption>,
    ) : TextOverlay() {
        private val settings = StaticOverlaySettings.Builder()
            .setBackgroundFrameAnchor(0f, 0.74f)
            .setOverlayFrameAnchor(0f, -1f)
            .build()
        private val hiddenSettings = StaticOverlaySettings.Builder()
            .setAlphaScale(0f)
            .build()
        private val blankText = android.text.SpannableString(" ")
        private var textScale = 0.5f
        private var maxTextWidth = DEFAULT_MAX_TEXT_WIDTH
        private val measurePaint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            textSize = BASE_TEXT_SIZE_PX.toFloat()
        }
        private val styledText = HashMap<Int, android.text.SpannableString>()

        override fun configure(videoSize: Size) {
            super.configure(videoSize)
            maxTextWidth = (videoSize.width * MAX_TEXT_WIDTH_FRACTION).coerceAtLeast(1f)
            textScale = (videoSize.width * TEXT_SIZE_FRACTION / BASE_TEXT_SIZE_PX)
                .coerceAtLeast(0.18f)
            measurePaint.textSize = BASE_TEXT_SIZE_PX * textScale
            styledText.clear()
        }

        override fun getText(presentationTimeUs: Long): android.text.SpannableString {
            val index = captionIndexAt(presentationTimeUs / 1_000L)
            if (index < 0) return blankText
            return styledText.getOrPut(index) {
                android.text.SpannableString(wrapText(captions[index].text)).also { text ->
                    if (text.isNotEmpty()) {
                        text.setSpan(
                            android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                            0,
                            text.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        text.setSpan(
                            android.text.style.ForegroundColorSpan(android.graphics.Color.WHITE),
                            0,
                            text.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        text.setSpan(
                            android.text.style.BackgroundColorSpan(0xB0000000.toInt()),
                            0,
                            text.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                        text.setSpan(
                            android.text.style.RelativeSizeSpan(textScale),
                            0,
                            text.length,
                            android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                }
            }
        }

        override fun getOverlaySettings(presentationTimeUs: Long): StaticOverlaySettings =
            if (captionIndexAt(presentationTimeUs / 1_000L) < 0) hiddenSettings else settings

        private fun captionIndexAt(timeMs: Long): Int {
            var low = 0
            var high = captions.lastIndex
            var candidate = -1
            while (low <= high) {
                val middle = (low + high) ushr 1
                if (captions[middle].startMs <= timeMs) {
                    candidate = middle
                    low = middle + 1
                } else {
                    high = middle - 1
                }
            }
            return if (candidate >= 0 && timeMs < captions[candidate].endMs) candidate else -1
        }

        private fun wrapText(text: String): String {
            val normalized = text.replace("\r\n", "\n").trim()
            if (normalized.isEmpty()) return normalized
            return normalized.split('\n').joinToString("\n") { line ->
                val words = line.trim().split(Regex("\\s+"))
                val lines = ArrayList<String>()
                var current = ""
                words.forEach { word ->
                    val candidate = if (current.isEmpty()) word else "$current $word"
                    if (current.isEmpty() || measurePaint.measureText(candidate) <= maxTextWidth) {
                        current = candidate
                    } else {
                        lines += current
                        current = word
                    }
                }
                if (current.isNotEmpty()) lines += current
                lines.joinToString("\n")
            }
        }

        companion object {
            private const val BASE_TEXT_SIZE_PX = 100
            private const val TEXT_SIZE_FRACTION = 0.055f
            private const val MAX_TEXT_WIDTH_FRACTION = 0.86f
            private const val DEFAULT_MAX_TEXT_WIDTH = 900f
        }
    }

    companion object {
        private const val PROGRESS_POLL_INTERVAL_MS = 150L
    }
}

private fun MediaClipPlan.toMediaItem(): MediaItem = MediaItem.Builder()
    .setUri(Uri.fromFile(source.file))
    .setClippingConfiguration(
        MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(sourceStartMs)
            .setEndPositionMs(sourceEndMs)
            .build(),
    )
    .build()

@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
private fun MediaClipPlan.toEditedMediaItem(): EditedMediaItem =
    EditedMediaItem.Builder(toMediaItem()).build()
