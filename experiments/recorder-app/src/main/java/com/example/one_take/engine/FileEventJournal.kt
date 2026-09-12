package com.example.one_take.engine

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** A record stored in [FileEventJournal]. Sequence numbers start at one. */
internal data class JournalRecord(
    val sequence: Long,
    val payload: String,
)

/**
 * A small, crash-tolerant append-only journal for the pure engine.
 *
 * The on-disk format is one UTF-8 line per record:
 *
 *     1|sequence|hex(UTF-8 payload)|sha256(sequence bytes + 0x00 + payload bytes)\n
 *
 * The newline is part of the frame. A final line without it is treated as an interrupted
 * append: [read] ignores it and the next [append] removes only that trailing fragment.
 * Complete lines are never repaired or discarded silently.
 */
internal class FileEventJournal(
    private val file: File,
) {
    private val processLockKey = canonicalPath(file)

    @Synchronized
    fun read(): List<JournalRecord> {
        return withProcessLock {
            readLocked()
        }
    }

    @Synchronized
    fun append(record: JournalRecord) {
        val encoded = encode(record)

        withProcessLock {
            file.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
                    throw IOException("Unable to create journal directory: ${parent.path}")
                }
            }

            RandomAccessFile(file, "rw").use { randomAccessFile ->
                val channel = randomAccessFile.channel
                channel.lock().use {
                    val state = readChannel(channel)
                    val expectedSequence = nextSequence(state.records)
                    if (record.sequence != expectedSequence) {
                        throw IOException(
                            "Journal sequence ${record.sequence} is invalid; expected $expectedSequence",
                        )
                    }

                    // Validate the record and the existing history before changing the file. The
                    // only mutation allowed here is removal of an incomplete final frame.
                    if (state.completeEnd < channel.size()) {
                        channel.truncate(state.completeEnd)
                    }
                    channel.position(state.completeEnd)
                    writeFully(channel, encoded)
                    channel.force(true)
                }
            }
        }
    }

    private fun readLocked(): List<JournalRecord> {
        return try {
            RandomAccessFile(file, "r").use { randomAccessFile ->
                val channel = randomAccessFile.channel
                // A shared OS lock prevents a read from observing a writer between truncate and
                // append. Some file systems do not provide shared locks, in which case the JVM
                // transparently upgrades this request to an exclusive lock.
                channel.lock(0L, Long.MAX_VALUE, true).use {
                    readChannel(channel).records
                }
            }
        } catch (error: FileNotFoundException) {
            // The file may have been removed before it could be opened. A missing journal is
            // equivalent to an empty one.
            if (!file.exists()) emptyList() else throw error
        }
    }

    private fun <T> withProcessLock(block: () -> T): T {
        val monitor = PROCESS_LOCKS.computeIfAbsent(processLockKey) { Any() }
        return synchronized(monitor, block)
    }

    private data class JournalState(
        val records: List<JournalRecord>,
        val completeEnd: Long,
    )

    /**
     * Reads complete newline-terminated frames and leaves an unterminated final frame unread.
     * Parsing while scanning avoids loading the whole journal into memory and also gives append
     * the exact byte offset at which an interrupted tail starts.
     */
    private fun readChannel(channel: FileChannel): JournalState {
        channel.position(0L)
        val records = ArrayList<JournalRecord>()
        val line = ByteArrayOutputStream()
        val buffer = ByteBuffer.allocate(8 * 1024)
        var offset = 0L
        var completeEnd = 0L
        var lineTooLong = false

        while (true) {
            val count = channel.read(buffer)
            if (count < 0) {
                break
            }
            if (count == 0) {
                continue
            }

            buffer.flip()
            while (buffer.hasRemaining()) {
                val byte = buffer.get().toInt() and 0xff
                offset += 1L
                if (byte == NEWLINE) {
                    if (lineTooLong) {
                        throw IOException("Journal frame exceeds the maximum size")
                    }
                    val record = decode(line.toByteArray())
                    val expectedSequence = nextSequence(records)
                    if (record.sequence != expectedSequence) {
                        throw IOException(
                            "Journal sequence ${record.sequence} is invalid; expected $expectedSequence",
                        )
                    }
                    records += record
                    line.reset()
                    lineTooLong = false
                    completeEnd = offset
                } else {
                    if (line.size() < MAX_FRAME_BYTES) {
                        line.write(byte)
                    } else {
                        // Keep scanning so a missing newline remains an ignorable interrupted
                        // tail, without allowing an arbitrary malformed input to consume memory.
                        lineTooLong = true
                    }
                }
            }
            buffer.clear()
        }

        // An empty line buffer means the file ends immediately after a complete frame. If it is
        // non-empty, completeEnd is the beginning of the unterminated tail.
        return JournalState(records = records, completeEnd = completeEnd)
    }

    private fun encode(record: JournalRecord): ByteArray {
        if (record.sequence <= 0L) {
            throw IOException("Journal sequence must be positive: ${record.sequence}")
        }

        val payloadBytes = record.payload.toByteArray(StandardCharsets.UTF_8)
        if (payloadBytes.size > MAX_PAYLOAD_BYTES) {
            throw IOException("Journal payload exceeds $MAX_PAYLOAD_BYTES bytes")
        }

        val payload = encodeHex(payloadBytes)
        val checksum = checksumHex(record.sequence, payloadBytes)
        return "$VERSION|${record.sequence}|$payload|$checksum\n"
            .toByteArray(StandardCharsets.US_ASCII)
    }

    private fun decode(line: ByteArray): JournalRecord {
        val frame = decodeUtf8(line, "journal frame")
        val fields = frame.split(FIELD_SEPARATOR, limit = FIELD_COUNT)
        if (fields.size != FIELD_COUNT || fields[0] != VERSION) {
            throw IOException("Corrupt journal frame")
        }

        val sequence = fields[1].toLongOrNull()
            ?: throw IOException("Corrupt journal sequence")
        if (sequence <= 0L) {
            throw IOException("Corrupt journal sequence: $sequence")
        }
        if (fields[1] != sequence.toString()) {
            throw IOException("Corrupt journal sequence")
        }

        val checksum = fields[3]
        if (!checksum.matches(CHECKSUM_PATTERN)) {
            throw IOException("Corrupt journal checksum")
        }

        if (fields[2].length > MAX_PAYLOAD_BYTES * 2) {
            throw IOException("Journal payload exceeds $MAX_PAYLOAD_BYTES bytes")
        }
        val payloadBytes = decodeHex(fields[2])

        val payload = decodeUtf8(payloadBytes, "journal payload")
        val expectedChecksum = checksumHex(sequence, payloadBytes)
        val suppliedChecksum = checksum.lowercase(Locale.ROOT)
        if (!MessageDigest.isEqual(
                expectedChecksum.toByteArray(StandardCharsets.US_ASCII),
                suppliedChecksum.toByteArray(StandardCharsets.US_ASCII),
            )
        ) {
            throw IOException("Corrupt journal checksum")
        }

        return JournalRecord(sequence = sequence, payload = payload)
    }

    private fun decodeUtf8(bytes: ByteArray, description: String): String {
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (error: CharacterCodingException) {
            throw IOException("Corrupt $description", error)
        }
    }

    private fun checksumHex(sequence: Long, payloadBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(sequence.toString().toByteArray(StandardCharsets.UTF_8))
        digest.update(0.toByte())
        digest.update(payloadBytes)
        val bytes = digest.digest()
        val chars = CharArray(bytes.size * 2)
        val alphabet = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = alphabet[value ushr 4]
            chars[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(chars)
    }

    private fun encodeHex(bytes: ByteArray): String {
        val chars = CharArray(bytes.size * 2)
        val alphabet = "0123456789abcdef"
        bytes.forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = alphabet[value ushr 4]
            chars[index * 2 + 1] = alphabet[value and 0x0f]
        }
        return String(chars)
    }

    private fun decodeHex(encoded: String): ByteArray {
        if (encoded.length % 2 != 0) {
            throw IOException("Corrupt journal payload encoding")
        }
        val bytes = ByteArray(encoded.length / 2)
        var index = 0
        while (index < encoded.length) {
            val high = hexDigit(encoded[index])
            val low = hexDigit(encoded[index + 1])
            if (high < 0 || low < 0) {
                throw IOException("Corrupt journal payload encoding")
            }
            bytes[index / 2] = ((high shl 4) or low).toByte()
            index += 2
        }
        return bytes
    }

    private fun hexDigit(value: Char): Int {
        return when (value) {
            in '0'..'9' -> value - '0'
            in 'a'..'f' -> value - 'a' + 10
            in 'A'..'F' -> value - 'A' + 10
            else -> -1
        }
    }

    private fun nextSequence(records: List<JournalRecord>): Long {
        if (records.isEmpty()) {
            return 1L
        }
        val last = records.last().sequence
        if (last == Long.MAX_VALUE) {
            throw IOException("Journal sequence exhausted")
        }
        return last + 1L
    }

    private fun writeFully(channel: FileChannel, bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            channel.write(buffer)
        }
    }

    private companion object {
        const val VERSION = "1"
        const val FIELD_SEPARATOR = '|'
        const val FIELD_COUNT = 4
        const val NEWLINE = '\n'.code
        const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
        const val MAX_FRAME_BYTES = MAX_PAYLOAD_BYTES * 2 + 256
        val CHECKSUM_PATTERN = Regex("[0-9a-fA-F]{64}")
        val PROCESS_LOCKS = ConcurrentHashMap<String, Any>()

        fun canonicalPath(file: File): String {
            return runCatching { file.canonicalPath }
                .getOrElse { file.absolutePath }
        }
    }
}
