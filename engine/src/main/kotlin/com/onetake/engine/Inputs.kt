package com.onetake.engine

/**
 * Everything the platform pushes into the engine. Contract §4, frozen.
 *
 * Change nothing in this file without saying so out loud — eight lanes compile
 * against it, and a rename here is eight merge conflicts.
 */

/** One buffer of 16 kHz mono PCM. [startSample] is the absolute index of pcm[0]. */
data class AudioFrame(val pcm: ShortArray, val startSample: Long) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is AudioFrame && startSample == other.startSample && pcm.contentEquals(other.pcm))
    override fun hashCode(): Int = 31 * pcm.contentHashCode() + startSample.hashCode()
}

/**
 * One recognized word.
 *
 * READ THIS BEFORE YOU USE [startSample] OR [endSample] FOR ANYTHING:
 * these come from a streaming transducer's emission peaks on a 40 ms grid, over
 * BPE sub-word tokens. They are NOT acoustic word boundaries, the emission lag
 * is undocumented, and the sherpa maintainer's own answer to "can I use these to
 * align?" is "use a forced aligner instead".
 *
 * They are good for PROGRESS TRACKING — which line we are on, roughly where.
 * They are NOT good for CUTTING AUDIO. Cut on VAD sample indices, which are real
 * silences. Never cross the two. (Contract §2, the boxed correction.)
 */
data class Word(
    val text: String,
    val startSample: Long,
    val endSample: Long,
    val conf: Float,
)

sealed interface RecognizerResult {
    /** In-flight hypothesis. Drives the strip's "we hear you", never a verdict. */
    data class Partial(val text: String, val startSample: Long) : RecognizerResult

    /** Endpointed result. This is what the aligner judges. */
    data class Final(
        val words: List<Word>,
        val startSample: Long,
        val endSample: Long,
    ) : RecognizerResult
}

/** Voice activity. These sample indices ARE safe to cut audio on. */
sealed interface VadEvent {
    data class SpeechStart(val sample: Long) : VadEvent
    data class SpeechEnd(val sample: Long) : VadEvent
}

/**
 * One NPU vision inference. [processor] is the accelerator that ACTUALLY ran it,
 * proven per Contract §2's R9 route — not the one that was requested.
 * A deliberate CPU fallback says "CPU" here. Never relabel it.
 */
data class VisionFrame(
    val sample: Long,
    val faceInFrame: Boolean,
    val bbox: FloatArray?,
    val inferenceMicros: Long,
    val processor: String,
) {
    override fun equals(other: Any?): Boolean = this === other || (other is VisionFrame &&
        sample == other.sample && faceInFrame == other.faceInFrame &&
        inferenceMicros == other.inferenceMicros && processor == other.processor &&
        (bbox?.contentEquals(other.bbox ?: FloatArray(0)) ?: (other.bbox == null)))
    override fun hashCode(): Int =
        (((sample.hashCode() * 31 + faceInFrame.hashCode()) * 31 + inferenceMicros.hashCode()) * 31 +
            processor.hashCode()) * 31 + (bbox?.contentHashCode() ?: 0)
}

/**
 * The ONE place video time enters the engine, exactly once per session.
 * Under Architecture B this maps the recognizer's sample index onto the MP4's
 * own timeline; the tolerance is hundreds of milliseconds, because every cut
 * boundary lands in a speech pause. (Contract §3.)
 */
data class VideoAnchor(
    val sample: Long,
    val videoPtsNanos: Long,
    val sessionId: String,
)

/** Anything a human did. Tap, volume key or Bluetooth remote all arrive here. */
sealed interface UserAction {
    data object Advance : UserAction
    data object Scratch : UserAction
    data class Circle(val takeId: TakeId) : UserAction
    data class Undo(val eventId: Long) : UserAction
    data object Stop : UserAction
}

/**
 * The single input type. One channel, one consumer loop, total order by sample
 * index — see [CoverageEngine].
 */
sealed interface EngineInput {
    val sample: Long

    data class Audio(val frame: AudioFrame) : EngineInput {
        override val sample: Long get() = frame.startSample
    }
    data class Recognized(val result: RecognizerResult) : EngineInput {
        override val sample: Long get() = when (result) {
            is RecognizerResult.Partial -> result.startSample
            is RecognizerResult.Final -> result.endSample
        }
    }
    data class Vad(val event: VadEvent) : EngineInput {
        override val sample: Long get() = when (event) {
            is VadEvent.SpeechStart -> event.sample
            is VadEvent.SpeechEnd -> event.sample
        }
    }
    data class Vision(val frame: VisionFrame) : EngineInput {
        override val sample: Long get() = frame.sample
    }
    data class Anchor(val anchor: VideoAnchor) : EngineInput {
        override val sample: Long get() = anchor.sample
    }
    /**
     * A human action. [sample] is the sample index at which it happened, so a
     * tap is ordered against speech exactly like any other input and replay
     * reproduces it. `:app` stamps this from the capture clock, never from
     * `System.currentTimeMillis()`.
     */
    data class Action(val action: UserAction, override val sample: Long) : EngineInput

    /**
     * A mid-session audio route change (R23). The Bluetooth remote connecting
     * moves the mic, and an unexplained accuracy cliff at 03:00 is unfixable
     * without this line in the ledger.
     */
    data class AudioRoute(val device: String, override val sample: Long) : EngineInput

    /** Thermal status changed. Feeds the engine-stats overlay and R9's honesty clause. */
    data class Thermal(val status: String, override val sample: Long) : EngineInput
}
