package com.onetake.engine

/**
 * THE MASTER CLOCK IS THE AUDIO SAMPLE INDEX. There is no second clock.
 *
 * 16 kHz mono PCM, so one sample is 62.5 µs. Every ledger event, take boundary,
 * word span and edit-list in/out point is a [Long] sample index.
 *
 * Video enters through exactly ONE [VideoAnchor] per session, emitted by
 * `:capture`. Wall-clock time appears in log lines and nowhere else — never in
 * the ledger.
 *
 * This is not stylistic. It is what makes R14 (deterministic replay) free: a
 * stray `System.currentTimeMillis()` in the fold is the one thing that can break
 * replay, and keeping it out of the type system is cheaper than finding it at
 * 03:00. It also makes "close times are unique inside a session" true by
 * construction.
 *
 * Two independent reasons the sample index is mandatory rather than merely
 * elegant, both from Contract §2:
 *  - sherpa's Kotlin binding DROPS `start_time` on the floor, so after every
 *    endpoint reset the recognizer's own timestamps restart at ~0. You recover
 *    absolute time only by counting the samples you fed it.
 *  - VAD hands you absolute sample indices for free (`SpeechSegment.start` is a
 *    linear, never-wrapping index), which is why VAD — not ASR — cuts audio.
 */
const val SAMPLE_RATE = 16_000

/** Sample index → seconds. For log lines and the eval card, never for the ledger. */
fun Long.samplesToSeconds(): Double = this / SAMPLE_RATE.toDouble()

/** Sample index → milliseconds, rounded. Use at the UI edge only. */
fun Long.samplesToMillis(): Long = (this * 1000L) / SAMPLE_RATE

/** Milliseconds → sample index. Use when reading a config threshold. */
fun Long.millisToSamples(): Long = (this * SAMPLE_RATE) / 1000L

/** Seconds → sample index. */
fun Double.secondsToSamples(): Long = (this * SAMPLE_RATE).toLong()
