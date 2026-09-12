package com.onetake.engine

/** Classifies one valid PCM frame for pause detection. */
fun interface SpeechFrameClassifier {
    /** Number of 16 kHz samples supplied to [classify] for each frame. */
    val frameSamples: Int
        get() = 320

    fun classify(samples: FloatArray): SpeechActivity
}

enum class SpeechActivity {
    SPEECH,
    NON_SPEECH,
    UNKNOWN,
}
