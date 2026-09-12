package com.onetake.engine.whisper

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Collections

/**
 * The byte-level vocabulary shipped with Qualcomm's Voice AI Whisper bundle.
 *
 * [load] follows the writer used by qai-hub-models: every base token is written
 * as its raw tiktoken bytes followed by a NUL separator.  The separator is not
 * repeated when a token itself is the NUL byte, so that token is represented by
 * the empty entry at its position.  The final separator is not a token.
 */
class WhisperVocabulary private constructor(
    private val tokenBytes: List<ByteArray>,
) {
    init {
        require(tokenBytes.size == BASE_VOCAB_SIZE) {
            "Whisper vocabulary has ${tokenBytes.size} base tokens, expected $BASE_VOCAB_SIZE"
        }
    }

    /** Number of raw mergeable entries in multilingual.tiktoken. */
    val size: Int get() = tokenBytes.size

    /** Decodes base and special token IDs using Whisper's byte-level BPE mapping. */
    fun decode(tokenIds: Iterable<Int>, skipSpecialTokens: Boolean = true): String {
        val encoded = StringBuilder()
        for (tokenId in tokenIds) {
            require(tokenId in 0 until VOCAB_SIZE) {
                "Whisper token ID $tokenId is outside 0..${VOCAB_SIZE - 1}"
            }
            if (tokenId < BASE_VOCAB_SIZE) {
                appendMappedBytes(encoded, tokenBytes[tokenId])
            } else if (!skipSpecialTokens) {
                encoded.append(WhisperTokens.specialTokenText(tokenId))
            }
        }
        return decodeMappedBytes(encoded)
    }

    /** Returns the raw mergeable token bytes, primarily for diagnostics and tests. */
    fun rawToken(tokenId: Int): ByteArray {
        require(tokenId in 0 until BASE_VOCAB_SIZE) {
            "Base token ID $tokenId is outside 0..${BASE_VOCAB_SIZE - 1}"
        }
        return tokenBytes[tokenId].copyOf()
    }

    companion object {
        const val BASE_VOCAB_SIZE: Int = 50_257
        const val VOCAB_SIZE: Int = 51_865

        private const val MAX_FILE_BYTES: Int = 8 * 1024 * 1024
        private const val NULL_BYTE_TOKEN_ID: Int = 188
        private const val BASE_EMPTY_TOKEN_ID: Int = BASE_VOCAB_SIZE - 1

        /** Loads a Qualcomm [vocab.bin] from a file without retaining the file handle. */
        fun load(file: File): WhisperVocabulary {
            require(file.isFile && file.canRead()) {
                "Whisper vocabulary is missing or unreadable: ${file.path}"
            }
            return file.inputStream().use(::load)
        }

        /** Loads the NUL-delimited bytes emitted by qai-hub-models. */
        fun load(input: InputStream): WhisperVocabulary {
            val bytes = readBounded(input)
            val entries = ArrayList<ByteArray>(BASE_VOCAB_SIZE)
            var entryStart = 0
            for (index in bytes.indices) {
                if (bytes[index].toInt() == 0) {
                    entries += bytes.copyOfRange(entryStart, index)
                    entryStart = index + 1
                }
            }
            if (entryStart < bytes.size) {
                entries += bytes.copyOfRange(entryStart, bytes.size)
            }
            // A trailing NUL is the separator after the last token, not an
            // additional empty token.  The empty mergeable token at ID 50256
            // is the entry immediately before that separator.
            require(entries.size == BASE_VOCAB_SIZE) {
                "Whisper vocabulary contains ${entries.size} entries, expected $BASE_VOCAB_SIZE"
            }
            // multilingual.tiktoken has the raw NUL byte at rank 188.  The
            // official writer uses that byte as the entry separator, so its
            // payload is observed as an empty entry in vocab.bin.  The pinned
            // asset's following empty entry is the real base token 50256.
            if (entries[NULL_BYTE_TOKEN_ID].isEmpty() && entries[BASE_EMPTY_TOKEN_ID].isEmpty()) {
                entries[NULL_BYTE_TOKEN_ID] = byteArrayOf(0)
            }
            return WhisperVocabulary(
                Collections.unmodifiableList(entries.map(ByteArray::copyOf)),
            )
        }

        private fun readBounded(input: InputStream): ByteArray {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var total = 0
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                total = Math.addExact(total, count)
                require(total <= MAX_FILE_BYTES) {
                    "Whisper vocabulary exceeds the supported $MAX_FILE_BYTES byte limit"
                }
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }

        private fun appendMappedBytes(destination: StringBuilder, bytes: ByteArray) {
            bytes.forEach { value ->
                destination.append(BYTE_TO_UNICODE[value.toInt() and 0xff])
            }
        }

        private fun decodeMappedBytes(encoded: CharSequence): String {
            val bytes = ByteArray(encoded.length)
            encoded.forEachIndexed { index, character ->
                val value = UNICODE_TO_BYTE[character.code]
                require(value >= 0) {
                    "Whisper vocabulary produced an unknown byte-level character U+${character.code.toString(16)}"
                }
                bytes[index] = value.toByte()
            }
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString()
        }

        private fun byteToUnicode(): CharArray {
            val result = CharArray(256)
            val bytes = ArrayList<Int>(256)
            for (value in 33..126) bytes += value
            for (value in 161..172) bytes += value
            for (value in 174..255) bytes += value

            val unicode = bytes.toMutableList()
            var next = 0
            for (value in 0..255) {
                if (value !in bytes) {
                    bytes += value
                    unicode += 256 + next
                    next += 1
                }
            }
            bytes.forEachIndexed { index, value -> result[value] = unicode[index].toChar() }
            return result
        }

        private fun unicodeToByte(): IntArray {
            val result = IntArray(512) { -1 }
            BYTE_TO_UNICODE.forEachIndexed { byte, character ->
                if (character.code >= result.size) error("Invalid GPT-2 byte mapping")
                result[character.code] = byte
            }
            return result
        }

        private val BYTE_TO_UNICODE: CharArray = byteToUnicode()
        private val UNICODE_TO_BYTE: IntArray = unicodeToByte()
    }
}
