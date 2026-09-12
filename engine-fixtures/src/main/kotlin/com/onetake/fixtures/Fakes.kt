package com.onetake.fixtures

import com.onetake.engine.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The fakes. Deterministic by construction — same script, same ledger, every run.
 *
 * These are not test doubles in the usual sense. They are the seam through which a
 * RECORDED session re-enters the system, which makes them three things at once:
 * R14's replay mechanism, the eval harness's input, and the UI lane's way of building
 * screens before the aligner exists.
 *
 * If a test built on these is ever flaky, that is not a flaky test. It is real
 * non-determinism in `:engine` that would have broken replay on stage.
 */

/**
 * Replays a scripted sequence of results. No threads, no timers — [accept] returns
 * whatever the script says is due by the frame's sample index, synchronously.
 */
class FakeRecognizer(
    private val program: List<Programmed>,
) : Recognizer {

    data class Programmed(val atSample: Long, val result: RecognizerResult)

    private val _results = MutableSharedFlow<RecognizerResult>(replay = 0, extraBufferCapacity = 256)
    override val results: Flow<RecognizerResult> = _results.asSharedFlow()

    private var emitted = 0
    private var started = false

    override fun start(cfg: RecognizerConfig) { started = true }

    override fun accept(frame: AudioFrame) {
        check(started) { "FakeRecognizer.accept before start() — the real one silently produces nothing here" }
        val until = frame.startSample + frame.pcm.size
        while (emitted < program.size && program[emitted].atSample < until) {
            _results.tryEmit(program[emitted].result)
            emitted++
        }
    }

    override fun close() { started = false }

    /** Everything the program would emit, in order. For driving the engine directly. */
    fun asInputs(): List<EngineInput> = program.map { EngineInput.Recognized(it.result) }

    companion object {
        /**
         * Build a program from plain text, one entry per utterance. Words are laid out
         * evenly across the utterance — good enough for coverage logic, which is all
         * these timestamps may be used for anyway.
         */
        fun ofUtterances(vararg utterances: Utterance): FakeRecognizer =
            FakeRecognizer(utterances.map { u ->
                val words = u.text.trim().split(" ").filter { it.isNotEmpty() }
                val span = (u.endSample - u.startSample).coerceAtLeast(1)
                val per = span / words.size.coerceAtLeast(1)
                Programmed(
                    atSample = u.endSample,
                    result = RecognizerResult.Final(
                        words = words.mapIndexed { i, w ->
                            Word(w, u.startSample + i * per, u.startSample + (i + 1) * per, 1.0f)
                        },
                        startSample = u.startSample,
                        endSample = u.endSample,
                    ),
                )
            })
    }

    data class Utterance(val text: String, val startSample: Long, val endSample: Long)
}

/** Emits speech boundaries at pre-declared sample indices. These ARE safe to cut on. */
class FakeVad(segments: List<LongRange>) : VoiceActivity {

    private val boundaries: List<Pair<Long, VadEvent>> = segments
        .flatMap { listOf(it.first to VadEvent.SpeechStart(it.first), it.last to VadEvent.SpeechEnd(it.last)) }
        .sortedBy { it.first }

    private var next = 0

    override fun accept(frame: AudioFrame): VadEvent? {
        val until = frame.startSample + frame.pcm.size
        if (next < boundaries.size && boundaries[next].first < until) {
            return boundaries[next++].second
        }
        return null
    }

    fun asInputs(): List<EngineInput> = boundaries.map { EngineInput.Vad(it.second) }
}

/** Emits vision frames, including the face-leaves-frame case the off-frame rule needs. */
class FakeVision(private val program: List<VisionFrame>) : VisionSignal {

    private val _frames = MutableSharedFlow<VisionFrame>(replay = 0, extraBufferCapacity = 256)
    override val frames: Flow<VisionFrame> = _frames.asSharedFlow()

    fun emitAll() = program.forEach { _frames.tryEmit(it) }

    fun asInputs(): List<EngineInput> = program.map { EngineInput.Vision(it) }

    companion object {
        /** Face present throughout, one inference per [everyMillis]. */
        fun facePresent(durationSamples: Long, everyMillis: Long = 500): FakeVision {
            val step = everyMillis.millisToSamples()
            return FakeVision((0 until durationSamples step step).map {
                VisionFrame(it, faceInFrame = true, bbox = null, inferenceMicros = 195, processor = "NPU")
            })
        }

        /** Face present, except inside [offFrame]. Drives the off-frame take rule. */
        fun faceLeavesFrame(
            durationSamples: Long,
            offFrame: LongRange,
            everyMillis: Long = 500,
        ): FakeVision {
            val step = everyMillis.millisToSamples()
            return FakeVision((0 until durationSamples step step).map {
                VisionFrame(it, faceInFrame = it !in offFrame, bbox = null,
                    inferenceMicros = 195, processor = "NPU")
            })
        }
    }
}

/** Silence, in frames of the size the real capture path produces. */
fun silentFrames(fromSample: Long, count: Int, frameSamples: Int = 512): List<AudioFrame> =
    (0 until count).map { AudioFrame(ShortArray(frameSamples), fromSample + it.toLong() * frameSamples) }
