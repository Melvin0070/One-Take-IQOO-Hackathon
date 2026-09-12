package com.example.one_take.engine

import com.onetake.engine.Change
import com.onetake.engine.ClockDomain
import com.onetake.engine.SessionPhase
import java.io.File
import java.io.IOException
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class LiveCaptureStoreTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        directory = createTemporaryDirectory()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun durableStartIsRecordedBeforeMediaExists() {
        val session = LiveCaptureStore(directory).begin("take.mp4")

        assertFalse(File(directory, "take.mp4").exists())
        val events = LiveCaptureStore(directory).events(session)
        assertEquals(1, events.size)
        assertEquals(0L, events.single().sample)
        assertEquals(ClockDomain.CAPTURE_ESTIMATE, events.single().clock)
        assertEquals(Change.CaptureRequested("take.mp4"), events.single().change)
        assertEquals(SessionPhase.RECORDING, LiveCaptureStore(directory).snapshot(session).phase)
    }

    @Test
    fun reopeningRetainsUnfinishedPhaseAndCanAppendInterruption() {
        val first = LiveCaptureStore(directory)
        val session = first.begin("take.mp4")
        first.append(session, 0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        first.append(session, 32_000L, Change.StopRequested("user"), ClockDomain.CAPTURE_ESTIMATE)

        val reopened = LiveCaptureStore(directory)
        assertEquals(SessionPhase.FINALIZING, reopened.snapshot(session).phase)
        reopened.append(
            session,
            32_000L,
            Change.CaptureInterrupted("process terminated"),
            ClockDomain.CAPTURE_ESTIMATE,
        )

        assertEquals(SessionPhase.INTERRUPTED, LiveCaptureStore(directory).snapshot(session).phase)
        assertEquals(4, LiveCaptureStore(directory).events(session).size)
    }

    @Test
    fun provisionalTranscriptDoesNotFinalizeMedia() {
        val store = LiveCaptureStore(directory)
        val session = store.begin("take.mp4")
        store.append(session, 0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        store.append(
            session,
            16_000L,
            Change.ProvisionalTranscript("hello creator"),
            ClockDomain.RECOGNIZER,
        )

        val state = store.snapshot(session)
        assertEquals(SessionPhase.RECORDING, state.phase)
        assertEquals("hello creator", state.provisionalText)
        assertTrue(state.captureRequested)
        assertEquals(null, state.sourceId)
    }

    @Test
    fun duplicateStopIsRejectedWithoutAnExtraDurableEvent() {
        val store = LiveCaptureStore(directory)
        val session = store.begin("take.mp4")
        store.append(session, 0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        store.append(session, 16_000L, Change.StopRequested("user"), ClockDomain.CAPTURE_ESTIMATE)
        val before = store.events(session)

        assertThrows(IllegalArgumentException::class.java) {
            store.append(session, 16_001L, Change.StopRequested("again"), ClockDomain.CAPTURE_ESTIMATE)
        }

        assertEquals(before, LiveCaptureStore(directory).events(session))
        assertEquals(SessionPhase.FINALIZING, store.snapshot(session).phase)
    }

    @Test
    fun missingJournalIsAnExplicitErrorAndDoesNotCreateASession() {
        val session = "00000000-0000-4000-8000-000000000001"
        val store = LiveCaptureStore(directory)

        assertThrows(IOException::class.java) { store.events(session) }
        assertThrows(IOException::class.java) { store.snapshot(session) }
        assertTrue(store.sessions().isEmpty())
        assertFalse(File(directory, "$session.ledger").exists())
    }

    @Test
    fun emptyJournalIsRejectedWithoutDeletingTheSource() {
        val source = File(directory, "take.mp4").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val session = "00000000-0000-4000-8000-000000000002"
        val ledger = File(directory, "$session.ledger").apply { createNewFile() }
        val sourceBefore = source.readBytes()
        val store = LiveCaptureStore(directory)

        assertThrows(IOException::class.java) { store.events(session) }
        assertThrows(IOException::class.java) { store.snapshot(session) }
        assertThrows(IOException::class.java) {
            store.append(session, 0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        }

        assertTrue(ledger.isFile)
        assertArrayEquals(sourceBefore, source.readBytes())
    }

    @Test
    fun corruptCompletedHistoryFailsWithoutOverwritingTheLedger() {
        val store = LiveCaptureStore(directory)
        val session = store.begin("take.mp4")
        store.append(session, 0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        val ledger = File(directory, "$session.ledger")
        val before = ledger.readBytes()
        val corrupted = before.copyOf()
        val index = corrupted.indexOfFirst { it != '\n'.code.toByte() }
        corrupted[index] = (corrupted[index].toInt() xor 0x01).toByte()
        ledger.writeBytes(corrupted)

        assertThrows(IOException::class.java) {
            store.append(session, 1L, Change.ProvisionalTranscript("must not append"), ClockDomain.RECOGNIZER)
        }
        assertArrayEquals(corrupted, ledger.readBytes())
        assertNotEquals(before.toList(), ledger.readBytes().toList())
    }

    @Test
    fun removeForSourceRemovesOnlyMatchingSessions() {
        val store = LiveCaptureStore(directory)
        val first = store.begin("first.mp4")
        val second = store.begin("second.mp4")

        store.removeForSource("first.mp4")

        assertEquals(listOf(second), store.sessions())
        assertThrows(IOException::class.java) { store.events(first) }
        assertEquals("second.mp4", (store.events(second).single().change as Change.CaptureRequested).sourceName)
        assertTrue(File(directory, "$second.ledger").isFile)
    }

    private fun createTemporaryDirectory(): File {
        val marker = File.createTempFile("live-capture-store-", ".tmp")
        check(marker.delete())
        check(marker.mkdirs())
        return marker
    }
}
