package com.onetake.engine

import kotlinx.coroutines.flow.Flow

/**
 * The interfaces the platform implements. Contract §4, frozen.
 *
 * `:engine` is PURE JVM and knows nothing about Android, sherpa-onnx, LiteRT or
 * CameraX. Each of these has at least two implementations — the real one in an
 * Android module, and a fake in `:engine-fixtures` — and the engine cannot tell
 * them apart. That is what makes the eval harness, deterministic replay and
 * replay-driven UI development the same one line of code:
 *
 *     inputs.forEach(engine::submit)
 */

/** Implemented by `:asr` (sherpa streaming zipformer) and `FakeRecognizer`. */
interface Recognizer {
    fun start(cfg: RecognizerConfig)
    fun accept(frame: AudioFrame)
    fun close()
    val results: Flow<RecognizerResult>
}

/** Implemented by `:asr` (Silero) and `FakeVad`. */
interface VoiceActivity {
    fun accept(frame: AudioFrame): VadEvent?
}

/** Implemented by `:npu` (LiteRT + Qualcomm accelerator) and `FakeVision`. */
interface VisionSignal {
    val frames: Flow<VisionFrame>
}

/** Implemented by `:asr` (keyword spotter) — commands, NOT the recognizer. */
interface CommandSpotter {
    /** Emits the matched command phrase and the sample it fired at. */
    val commands: Flow<SpottedCommand>
    fun accept(frame: AudioFrame)
    fun close()
}

data class SpottedCommand(val phrase: String, val sample: Long, val score: Float)

data class RecognizerConfig(
    val modelDir: String,
    val numThreads: Int = 1,
    val sampleRate: Int = SAMPLE_RATE,
    /**
     * True once the NPU recognizer has cleared its bar. Premise 7: the switch is
     * PER REQUIREMENT, not global — latency alone is not enough, R2/R3/R8 must
     * still pass on the held-out corpus with it.
     */
    val preferNpu: Boolean = false,
)
