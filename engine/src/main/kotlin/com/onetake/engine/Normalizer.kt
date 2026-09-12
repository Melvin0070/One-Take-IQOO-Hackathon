package com.onetake.engine

/**
 * ONE normalizer, three thresholds. Not three implementations.
 *
 * R2, §10's aligner rules and §7.3's eval card currently describe the same thing
 * three times in the design doc. In code it is this class, and `:app` never has
 * its own copy.
 *
 * What it has to survive, from R2's acceptance test — strangers reading these
 * lines cleanly, with ZERO flags:
 *     "iQOO 15"   "Snapdragon 8 Elite Gen 5"   "₹70k"   "2026"   "4K at 60 fps"
 *
 * So: case, punctuation, numbers, currency and dates ("70k" == "seventy
 * thousand rupees"), a per-project alias list for brand names, and per-token
 * fuzzy matching. Precision first (Premise 10) — when in doubt, do not flag.
 */
class Normalizer(private val config: RuntimeConfig) {

    fun normalize(text: String, mode: Mode = Mode.NORMAL): List<String> {
        // TODO(Lane A): Contract §5 "DRY" + R2's test lines.
        TODO("Lane A: normalization")
    }

    enum class Mode { NORMAL, MUST_SAY_STRICT, CAPTION }
}
