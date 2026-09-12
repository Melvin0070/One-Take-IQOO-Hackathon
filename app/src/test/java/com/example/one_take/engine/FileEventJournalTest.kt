package com.example.one_take.engine

import java.io.File
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class FileEventJournalTest {
    private lateinit var temporaryDirectory: File
    private lateinit var journalFile: File
    private lateinit var journal: FileEventJournal

    @Before
    fun setUp() {
        temporaryDirectory = createTemporaryDirectory()
        journalFile = File(temporaryDirectory, "engine.events")
        journal = FileEventJournal(journalFile)
    }

    @After
    fun tearDown() {
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun roundTripsUnicodeAndNewlinesAndCanBeReopened() {
        val expected = listOf(
            JournalRecord(1L, "नमस्ते, creator\nline two"),
            JournalRecord(2L, "Hinglish: आज we shoot 🎥"),
            JournalRecord(3L, ""),
        )

        expected.forEach(journal::append)

        assertEquals(expected, journal.read())
        assertEquals(expected, FileEventJournal(journalFile).read())
        val frames = journalFile.readText(StandardCharsets.UTF_8).split('\n')
        assertTrue(frames.dropLast(1).all { frame ->
            val fields = frame.split('|')
            fields.size == 4 && fields[2].all { it in "0123456789abcdef" }
        })
        assertTrue(journalFile.readBytes().last().toInt() == '\n'.code)
    }

    @Test
    fun ignoresAndReplacesOnlyAnUnterminatedTrailingFrame() {
        val first = JournalRecord(1L, "first")
        val interrupted = JournalRecord(2L, "old interrupted payload")
        journal.append(first)
        journal.append(interrupted)

        val completeBytes = journalFile.readBytes()
        journalFile.writeBytes(completeBytes.copyOf(completeBytes.size - 1))

        assertEquals(listOf(first), journal.read())

        val replacement = JournalRecord(2L, "replacement")
        journal.append(replacement)

        assertEquals(listOf(first, replacement), journal.read())
        assertTrue(journalFile.readBytes().last().toInt() == '\n'.code)
    }

    @Test
    fun completeMiddleChecksumCorruptionRefusesReadAndAppend() {
        journal.append(JournalRecord(1L, "one"))
        journal.append(JournalRecord(2L, "two"))
        journal.append(JournalRecord(3L, "three"))

        val frames = journalFile.readText(StandardCharsets.UTF_8).split('\n').toMutableList()
        frames[1] = replaceChecksum(frames[1])
        journalFile.writeText(frames.dropLast(1).joinToString("\n") + "\n", StandardCharsets.UTF_8)
        val bytesBeforeFailedAppend = journalFile.readBytes()

        assertThrows(IOException::class.java) { journal.read() }
        assertThrows(IOException::class.java) {
            journal.append(JournalRecord(4L, "must not be appended"))
        }
        assertArrayEquals(bytesBeforeFailedAppend, journalFile.readBytes())
    }

    @Test
    fun invalidSequenceAndBadAppendLeaveExistingBytesUntouched() {
        journal.append(JournalRecord(1L, "one"))
        journal.append(JournalRecord(2L, "two"))
        val bytesBeforeFailedAppend = journalFile.readBytes()

        assertThrows(IOException::class.java) {
            journal.append(JournalRecord(4L, "gap"))
        }

        assertArrayEquals(bytesBeforeFailedAppend, journalFile.readBytes())
        assertEquals(
            listOf(JournalRecord(1L, "one"), JournalRecord(2L, "two")),
            journal.read(),
        )
    }

    @Test
    fun twoInstancesSerializeWritersAndRejectTheLosingSequence() {
        val left = FileEventJournal(journalFile)
        val right = FileEventJournal(journalFile)
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val successes = AtomicInteger(0)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())

        val threads = listOf(
            Thread {
                ready.countDown()
                start.await()
                try {
                    left.append(JournalRecord(1L, "left"))
                    successes.incrementAndGet()
                } catch (error: Throwable) {
                    failures += error
                }
            },
            Thread {
                ready.countDown()
                start.await()
                try {
                    right.append(JournalRecord(1L, "right"))
                    successes.incrementAndGet()
                } catch (error: Throwable) {
                    failures += error
                }
            },
        )
        threads.forEach(Thread::start)
        ready.await()
        start.countDown()
        threads.forEach(Thread::join)

        assertEquals(1, successes.get())
        assertEquals(1, failures.size)
        assertTrue(failures.single() is IOException)
        assertEquals(1, FileEventJournal(journalFile).read().size)
    }

    @Test
    fun aBadAppendDoesNotDiscardAnInterruptedTail() {
        journal.append(JournalRecord(1L, "one"))
        journalFile.appendBytes("partial tail".toByteArray(StandardCharsets.UTF_8))
        val bytesBeforeFailedAppend = journalFile.readBytes()

        assertThrows(IOException::class.java) {
            journal.append(JournalRecord(3L, "gap"))
        }

        assertArrayEquals(bytesBeforeFailedAppend, journalFile.readBytes())
        assertEquals(listOf(JournalRecord(1L, "one")), journal.read())
    }

    private fun replaceChecksum(frame: String): String {
        val separator = frame.lastIndexOf('|')
        check(separator >= 0)
        val checksum = frame.substring(separator + 1)
        val replacement = if (checksum.last() == '0') '1' else '0'
        return frame.substring(0, frame.length - 1) + replacement
    }

    private fun createTemporaryDirectory(): File {
        val marker = File.createTempFile("event-journal-", ".tmp")
        check(marker.delete())
        check(marker.mkdirs())
        return marker
    }
}
