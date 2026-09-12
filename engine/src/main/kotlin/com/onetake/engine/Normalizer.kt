package com.onetake.engine

import java.util.Locale

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
        if (text.isBlank()) return emptyList()

        var tokens = lexicalTokens(text)
        tokens = expandAliases(tokens)
        tokens = canonicalize(tokens)

        // The three modes share one canonical vocabulary. The mode is retained at
        // this boundary so callers can choose strict comparison without creating a
        // second normalizer; strictness belongs to the aligner threshold.
        return when (mode) {
            Mode.NORMAL, Mode.MUST_SAY_STRICT, Mode.CAPTION -> tokens
        }
    }

    enum class Mode { NORMAL, MUST_SAY_STRICT, CAPTION }

    private fun lexicalTokens(text: String): List<String> {
        val prepared = text.lowercase(Locale.ROOT)
            .replace("₹", " rupees ")
            .replace("$", " dollars ")
            .replace("€", " euros ")
            .replace("£", " pounds ")
            .replace(Regex("[’‘`´]"), "'")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        return prepared.split(Regex("\\s+"))
            .filter(String::isNotBlank)
            .map { token ->
                when (token) {
                    "fps" -> "frames per second"
                    "gen" -> "generation"
                    else -> token
                }
            }
            .flatMap { it.split(' ') }
    }

    private fun expandAliases(input: List<String>): List<String> {
        if (config.brandAliases.isEmpty()) return input
        val replacements = config.brandAliases.flatMap { (canonical, aliases) ->
            val canonicalTokens = lexicalTokens(canonical)
            (aliases + canonical).map { alias ->
                lexicalTokens(alias) to canonicalTokens
            }
        }.filter { it.first.isNotEmpty() }
            .sortedByDescending { it.first.size }

        if (replacements.isEmpty()) return input
        val result = ArrayList<String>(input.size)
        var index = 0
        while (index < input.size) {
            val replacement = replacements.firstOrNull { (from, _) ->
                index + from.size <= input.size && input.subList(index, index + from.size) == from
            }
            if (replacement == null) {
                result += input[index]
                index++
            } else {
                result += replacement.second
                index += replacement.first.size
            }
        }
        return result
    }

    private fun canonicalize(input: List<String>): List<String> {
        val result = ArrayList<String>(input.size)
        var index = 0
        while (index < input.size) {
            val token = input[index]
            if (token == "i" && input.getOrNull(index + 1) == "qoo") {
                result += "iqoo"
                index += 2
                continue
            }
            if (token in setOf("rupees", "rupee", "inr", "dollars", "dollar", "usd", "euros", "euro", "pounds", "pound")) {
                // Currency is a semantic qualifier, not a useful distinction for
                // matching a spoken product line. Dropping its position makes
                // "$70k", "₹70k" and "seventy thousand rupees" equivalent.
                index++
                continue
            }

            val compact = compactNumber(token)
            if (compact != null) {
                result += compact
                index++
                continue
            }

            val number = numberValue(token)
            if (number != null) {
                val next = input.getOrNull(index + 1)
                if (next == "k") {
                    val hasCurrency = input.any { it == "rupees" || it == "rupee" || it == "inr" }
                    if (next == "k" && !hasCurrency && number < 10) {
                        result += "#${number}k"
                    } else {
                        result += "#${number * 1_000}"
                    }
                    index += 2
                    continue
                }

                val parsed = parseNumberPhrase(input, index)
                if (parsed != null) {
                    result += "#${parsed.value}"
                    index = parsed.nextIndex
                    continue
                }
                result += "#$number"
                index++
                continue
            }

            // A bare suffix after a spoken resolution number is consumed above.
            // Retaining an unrelated k keeps fuzzy matching conservative.
            result += token
            index++
        }
        return result
    }

    private fun compactNumber(token: String): String? {
        val match = Regex("^(\\d+)([kmb])$").matchEntire(token) ?: return null
        val number = match.groupValues[1].toLongOrNull() ?: return null
        return when (match.groupValues[2]) {
            "k" -> if (number < 10) "#${number}k" else "#${number * 1_000}"
            "m" -> "#${number * 1_000_000}"
            "b" -> "#${number * 1_000_000_000}"
            else -> null
        }
    }

    private fun parseNumberPhrase(tokens: List<String>, start: Int): ParsedNumber? {
        numberValue(tokens[start]) ?: return null
        var index = start
        var total = 0L
        var group = 0L
        var consumed = false
        val values = ArrayList<Long>()
        while (index < tokens.size) {
            val value = numberValue(tokens[index]) ?: break
            values += value
            consumed = true
            index++
            when (tokens[index - 1]) {
                "hundred" -> group *= 100
                "thousand" -> {
                    total += group * 1_000
                    group = 0
                }
                "million" -> {
                    total += group * 1_000_000
                    group = 0
                }
                "billion" -> {
                    total += group * 1_000_000_000
                    group = 0
                }
                else -> group += value
            }
        }
        if (!consumed) return null

        // Spoken years are commonly read as two pairs ("twenty twenty six").
        // Concatenate those pairs rather than treating them as arithmetic.
        val yearLike = values.size >= 2 && values.first() in 10..99 &&
            values.all { it in 0..99 } && total == 0L
        val parsed = if (yearLike) {
            when {
                values.size >= 3 && values[0] % 10 == 0L && values[1] % 10 == 0L && values[2] < 10L ->
                    "${values[0]}${values[1] + values[2]}".toLongOrNull() ?: (total + group)
                else -> values.joinToString("").toLongOrNull() ?: (total + group)
            }
        } else {
            total + group
        }
        return ParsedNumber(parsed, index)
    }

    private fun numberValue(token: String): Long? = when (token) {
        "zero" -> 0
        "one", "a" -> 1
        "two" -> 2
        "three" -> 3
        "four" -> 4
        "five" -> 5
        "six" -> 6
        "seven" -> 7
        "eight" -> 8
        "nine" -> 9
        "ten" -> 10
        "eleven" -> 11
        "twelve" -> 12
        "thirteen" -> 13
        "fourteen" -> 14
        "fifteen" -> 15
        "sixteen" -> 16
        "seventeen" -> 17
        "eighteen" -> 18
        "nineteen" -> 19
        "twenty" -> 20
        "thirty" -> 30
        "forty" -> 40
        "fifty" -> 50
        "sixty" -> 60
        "seventy" -> 70
        "eighty" -> 80
        "ninety" -> 90
        "hundred" -> 100
        "thousand" -> 1_000
        "million" -> 1_000_000
        "billion" -> 1_000_000_000
        else -> token.toLongOrNull()
    }

    private data class ParsedNumber(val value: Long, val nextIndex: Int)

}
