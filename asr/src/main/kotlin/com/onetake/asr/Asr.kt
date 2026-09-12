package com.onetake.asr

import com.onetake.engine.*
import kotlinx.coroutines.flow.Flow

/**
 * sherpa-onnx, CPU. Read asr/AGENTS.md and docs/agents/landmines.md L1-L5, L15 first —
 * this module's failures are silent, and one of them (L1) kills the product with no
 * crash, no exception and no log line.
 *
 * Threading, which is not optional:
 *   AudioRecord reader thread
 *     |- SileroVoiceActivity  synchronous, tiny
 *     |- KeywordCommandSpotter synchronous, tiny, NOT gated behind VAD
 *     \- bounded queue -> one dedicated thread -> SherpaRecognizer
 *
 * OnlineRecognizer, Vad and KeywordSpotter carry NO internal locks. One instance, one
 * thread. Never share an OnlineStream between the recognizer and the spotter.
 */

/** Silero VAD. Its sample indices are the ONLY ones allowed to cut audio. */
class SileroVoiceActivity(
    private val modelPath: String,
    /**
     * Raise this above sherpa's 5 s default. Past the limit the C++ SILENTLY overrides
     * your threshold to 0.90 and minSilenceDuration to 0.1 to force a split — and
     * creators read long unbroken lines. A silent config override mid-session is a
     * debugging nightmare at 03:00.
     */
    private val maxSpeechDurationSeconds: Float = 20f,
) : VoiceActivity {

    override fun accept(frame: AudioFrame): VadEvent? {
        // TODO(Lane C): feed the Silero VAD and POP PROMPTLY.
        //   CircularBuffer::Push EXITS THE PROCESS if the 60 s buffer fills, and the
        //   Kotlin binding gives you no way to change the capacity. This is a hard
        //   process exit, not an exception. (landmines L3)
        TODO("Lane C: Silero VAD. See asr/AGENTS.md")
    }
}

/**
 * Streaming zipformer transducer, CPU.
 *
 * The binding drops `start_time`, so after every endpoint reset its timestamps restart
 * at ~0. Count samples yourself, snapshot the counter at each reset, and add
 * `snapshot / 16000.0`. (landmines L5)
 */
class SherpaRecognizer(
    private val modelDir: String,
) : Recognizer {

    override val results: Flow<RecognizerResult>
        get() = TODO("Lane C: streaming zipformer. See asr/AGENTS.md")

    override fun start(cfg: RecognizerConfig) {
        // TODO(Lane C): assert the sherpa version is >= 1.13.8 at startup and fail LOUDLY
        //   if it is not. Below that version this recognizer returns empty output forever
        //   on SM8850 with no other symptom. (landmines L1)
        TODO("Lane C")
    }

    override fun accept(frame: AudioFrame) = TODO("Lane C")
    override fun close() = TODO("Lane C")
}

/**
 * Keyword spotting for "scratch that". A separate model from the recognizer, because a
 * transducer invents text on short clips.
 *
 * Do NOT gate this behind VAD segments — that adds the full minSilenceDuration (250 ms
 * default) before the spotter sees any audio, which is the difference between the command
 * feeling instant and feeling broken.
 */
class KeywordCommandSpotter(
    private val modelDir: String,
    private val keywordsFile: String,
    /**
     * Undocumented and very useful: createStream(keywords) takes runtime keywords with
     * '/' as the separator, APPENDED to the ones from keywordsFile. That makes the
     * command phrase list tunable from RuntimeConfig with no rebuild.
     */
    private val runtimeKeywords: List<String> = emptyList(),
) : CommandSpotter {

    override val commands: Flow<SpottedCommand>
        get() = TODO("Lane C: keyword spotting. See asr/AGENTS.md")

    override fun accept(frame: AudioFrame) = TODO("Lane C")
    override fun close() = TODO("Lane C")
}
