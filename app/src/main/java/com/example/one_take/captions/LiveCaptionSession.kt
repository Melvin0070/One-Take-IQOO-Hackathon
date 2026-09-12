package com.example.one_take.captions

import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.abs

/** One take's live transcription. CameraX remains the owner of the saved original. */
internal class LiveCaptionSession(val microphone: LiveMicrophone = LiveMicrophone()) {
    var completedWindows = 0
        private set
    var failure: String? = null
        private set
    private var captured = FloatArray(0)

    suspend fun transcribe(model: File, onUpdate: suspend (List<CaptionSegment>) -> Unit): List<CaptionSegment> {
        val timeline = LiveCaptionTimeline()
        try {
            WhisperEngine().withSession(model, optimizeForStreaming = true) { session ->
                var processedSamples = 0
                var windowStart = 0
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val stopped = !microphone.running
                    if (stopped) microphone.awaitStopped()
                    val count = microphone.sampleCount
                    val minimumNew = if (processedSamples == 0) 5 * RATE else 3 * RATE
                    if (!stopped && count - processedSamples < minimumNew) {
                        delay(100)
                        continue
                    }
                    val audio = microphone.snapshot()
                    if (audio.size > processedSamples || (stopped && windowStart < audio.size)) {
                        if (audio.size - windowStart > 20 * RATE) {
                            error("Live captions could not keep up; finishing from the recording.")
                        }
                        val window = audio.copyOfRange(windowStart.coerceAtMost(audio.size), audio.size)
                        // Only skips inference on near-digital silence. Pauses come from the microphone's
                        // VAD; gating Whisper on that VAD would drop soft speech it scores as non-speech.
                        if (window.any { abs(it) > .008f }) {
                            val segments = session.transcribe(if (window.size < RATE) window.copyOf(RATE) else window)
                            val endMs = audio.size * 1000L / RATE
                            timeline.append(windowStart * 1000L / RATE, segments,
                                if (stopped) endMs else (endMs - 1_000).coerceAtLeast(0))
                            completedWindows++
                            onUpdate(timeline.snapshot())
                        }
                        processedSamples = audio.size
                        // Retain uncommitted words and some context at the boundary.
                        windowStart = ((timeline.committedThroughMs - 1_500).coerceAtLeast(0) * RATE / 1000).toInt()
                        if (window.none { abs(it) > .008f }) windowStart = (audio.size - RATE).coerceAtLeast(0)
                    }
                    if (stopped) break
                }
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            failure = exception.message ?: "Live captions unavailable"
        }
        // After an inference failure the microphone is still stopped by the recording owner.
        microphone.awaitStopped()
        captured = microphone.snapshot()
        return timeline.snapshot()
    }

    /** Only publish live timestamps when the actual MP4 audio confirms them. */
    fun reconcile(reference: FloatArray, segments: List<CaptionSegment>): List<CaptionSegment>? {
        if (failure != null || microphone.failure != null || segments.isEmpty()) return null
        val offset = AudioAlignment.offsetMs(reference, captured) ?: return null
        val duration = reference.size * 1000L / RATE
        return segments.mapNotNull { segment ->
            val shiftedStart = runCatching { Math.addExact(segment.startMs, offset) }.getOrNull()
                ?: return@mapNotNull null
            val shiftedEnd = runCatching { Math.addExact(segment.endMs, offset) }.getOrNull()
                ?: return@mapNotNull null
            val start = shiftedStart.coerceIn(0, duration)
            val end = shiftedEnd.coerceIn(0, duration)
            if (end > start) {
                val shiftedWords = if (segment.hasReliableWordEvidence()) {
                    segment.words.shiftAndBound(offset, start, end)
                } else {
                    emptyList()
                }
                val aligned = segment.copy(
                    startMs = start,
                    endMs = end,
                    words = shiftedWords,
                )
                aligned.copy(words = shiftedWords.takeIf { aligned.hasReliableWordEvidence() }.orEmpty())
            } else {
                null
            }
        }
    }

    /**
     * Aligns the microphone clock independently of ASR success.
     *
     * Pause candidates are produced from this same microphone stream, so this offset is the
     * only information needed to move them onto the finalized MP4 timeline.  A microphone
     * failure makes the clock untrustworthy; an ASR failure does not.
     */
    fun pauseOffsetMs(reference: FloatArray): Long? {
        if (microphone.failure != null) return null
        val liveAudio = captured.takeIf { it.isNotEmpty() } ?: microphone.snapshot()
        return AudioAlignment.offsetMs(reference, liveAudio)
    }

    companion object { private const val RATE = 16_000 }
}
