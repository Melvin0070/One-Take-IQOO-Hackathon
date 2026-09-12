package com.onetake.engine.timeline

/**
 * Versioned JSON adapter, following the journal's versioned object convention.
 * No Android JSON classes or additional engine dependencies are required.
 * Sample positions are integer JSON numbers, never floating-point values.
 */
object TimelineCodec {
    fun encode(timeline: Timeline): String = buildString {
        append("{\"version\":1,\"clips\":[")
        timeline.clips.forEachIndexed { index, clip ->
            if (index > 0) append(',')
            append("{\"id\":").append(quote(clip.id))
            append(",\"sourceStart\":").append(clip.sourceStart)
            append(",\"sourceEnd\":").append(clip.sourceEnd)
            append(",\"state\":").append(quote(clip.state.name))
            append(",\"reason\":").append(clip.reason?.let(::quote) ?: "null")
            append('}')
        }
        append("]}")
    }

    fun decode(payload: String): Timeline {
        val root = Parser(payload).parse().objectValue()
        require(root["version"] == NumberToken("1")) { "Unsupported timeline version" }
        val clips = root["clips"] as? List<*> ?: errorValue("clips must be an array")
        return Timeline(clips.map { value ->
            val clip = value.objectValue()
            require(clip.containsKey("reason")) { "Missing clip reason" }
            val reason = clip["reason"]
            require(reason == null || reason is String) { "reason must be a string or null" }
            Clip(
                id = clip.string("id"),
                sourceStart = clip.long("sourceStart"),
                sourceEnd = clip.long("sourceEnd"),
                state = ClipState.valueOf(clip.string("state")),
                reason = reason as String?,
            )
        })
    }

    private fun quote(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> if (char.code < 0x20 || char.isSurrogate()) {
                    append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else append(char)
            }
        }
        append('"')
    }

    private fun Any?.objectValue(): Map<*, *> =
        this as? Map<*, *> ?: errorValue("Expected JSON object")

    private fun Map<*, *>.string(key: String): String =
        this[key] as? String ?: errorValue("$key must be a string")

    private fun Map<*, *>.long(key: String): Long =
        (this[key] as? NumberToken)?.text?.toLongOrNull()
            ?: errorValue("$key must be an exact 64-bit integer")

    private fun errorValue(message: String): Nothing = throw IllegalArgumentException(message)
    private data class NumberToken(val text: String)

    /** Strict JSON parser with a depth bound, also allowing unknown future object fields. */
    private class Parser(private val text: String) {
        private var index = 0

        fun parse(): Any? {
            val result = value(0)
            whitespace()
            require(index == text.length) { "Trailing JSON content" }
            return result
        }

        private fun value(depth: Int): Any? {
            require(depth <= 64) { "JSON nesting is too deep" }
            whitespace()
            return when (peek()) {
                '{' -> objectValue(depth + 1)
                '[' -> array(depth + 1)
                '"' -> string()
                'n' -> literal("null", null)
                't' -> literal("true", true)
                'f' -> literal("false", false)
                '-', in '0'..'9' -> number()
                else -> errorValue("Invalid JSON value at $index")
            }
        }

        private fun objectValue(depth: Int): Map<String, Any?> {
            index++
            val result = linkedMapOf<String, Any?>()
            whitespace()
            if (take('}')) return result
            do {
                whitespace()
                val key = string()
                require(!result.containsKey(key)) { "Duplicate JSON field: $key" }
                whitespace()
                expect(':')
                result[key] = value(depth)
                whitespace()
                if (take('}')) return result
                expect(',')
            } while (true)
        }

        private fun array(depth: Int): List<Any?> {
            index++
            val result = mutableListOf<Any?>()
            whitespace()
            if (take(']')) return result
            do {
                result += value(depth)
                whitespace()
                if (take(']')) return result
                expect(',')
            } while (true)
        }

        private fun string(): String {
            expect('"')
            return buildString {
                while (true) {
                    require(index < text.length) { "Unterminated JSON string" }
                    val char = text[index++]
                    if (char == '"') break
                    require(char.code >= 0x20) { "Unescaped control character" }
                    if (char != '\\') {
                        append(char)
                        continue
                    }
                    require(index < text.length) { "Incomplete JSON escape" }
                    when (val escaped = text[index++]) {
                        '"', '\\', '/' -> append(escaped)
                        'b' -> append('\b')
                        'f' -> append('\u000c')
                        'n' -> append('\n')
                        'r' -> append('\r')
                        't' -> append('\t')
                        'u' -> {
                            require(index + 4 <= text.length) { "Incomplete Unicode escape" }
                            val digits = text.substring(index, index + 4)
                            require(digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) {
                                "Invalid Unicode escape"
                            }
                            append(digits.toInt(16).toChar())
                            index += 4
                        }
                        else -> errorValue("Invalid JSON escape")
                    }
                }
            }
        }

        private fun number(): NumberToken {
            val start = index
            take('-')
            if (!take('0')) digits()
            if (take('.')) digits()
            if (take('e') || take('E')) {
                if (!take('+')) take('-')
                digits()
            }
            return NumberToken(text.substring(start, index))
        }

        private fun digits() {
            val start = index
            while (peek() in '0'..'9') index++
            require(index > start) { "Expected JSON digits" }
        }

        private fun literal(token: String, result: Any?): Any? {
            require(text.startsWith(token, index)) { "Invalid JSON literal" }
            index += token.length
            return result
        }

        private fun whitespace() {
            while (peek() in listOf(' ', '\t', '\r', '\n')) index++
        }
        private fun peek(): Char? = text.getOrNull(index)
        private fun take(char: Char): Boolean = if (peek() == char) { index++; true } else false
        private fun expect(char: Char) { require(take(char)) { "Expected '$char' at $index" } }
    }
}
