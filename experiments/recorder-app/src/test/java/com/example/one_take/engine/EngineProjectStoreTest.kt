package com.example.one_take.engine

import com.example.one_take.captions.CaptionRepository
import com.example.one_take.captions.CaptionSegment
import com.example.one_take.editing.EditCut
import com.example.one_take.editing.EditDecision
import com.example.one_take.editing.EditRepository
import com.example.one_take.recordingFingerprint
import com.onetake.engine.Change
import com.onetake.engine.ClockDomain
import com.onetake.engine.EditingEngine
import com.onetake.engine.Event
import com.onetake.engine.EventSink
import com.onetake.engine.VideoAnchor
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class EngineProjectStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private fun fixture(): Fixture {
        val source = folder.newFile("source.mp4").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        val captions = CaptionRepository(folder.newFolder("captions"))
        val edits = EditRepository(folder.newFolder("edits"))
        val ledgers = folder.newFolder("ledgers")
        return Fixture(source, captions, edits, ledgers)
    }

    private data class Fixture(val source: File, val captions: CaptionRepository,
        val edits: EditRepository, val ledgers: File) {
        fun store() = EngineProjectStore(ledgers, captions, edits) { 10_000 }
    }

    @Test fun wordEvidenceAndDisabledSuggestionsSurviveReopenApplyUndoAndCaptionCorrection() {
        val f = fixture()
        val raw = f.source.readBytes()
        val segments = listOf(CaptionSegment(0, 2_000, "um hello", listOf(
            com.example.one_take.captions.CaptionWord(200, 500, "um", 0.9f),
            com.example.one_take.captions.CaptionWord(600, 1_500, "hello", 0.95f),
        )))
        f.store().saveCaptions(f.source, segments)
        f.store().saveEdits(f.source, EditDecision(10_000, emptyList()))
        val state = f.store().read(f.source)!!
        assertEquals(segments, state.captions!!.map { it.toApp() })
        val suggestion = state.edits!!.cuts.single()
        assertFalse(suggestion.enabled)
        assertTrue(suggestion.reason.contains("filler", ignoreCase = true))
        f.store().saveEdits(f.source, state.edits!!.toApp().toggle(suggestion.id))
        assertTrue(f.store().read(f.source)!!.edits!!.cuts.single().enabled)
        f.store().saveEdits(f.source, f.store().read(f.source)!!.edits!!.toApp().toggle(suggestion.id))
        assertFalse(f.store().read(f.source)!!.edits!!.cuts.single().enabled)
        f.store().saveCaptions(f.source, listOf(CaptionSegment(0, 2_000, "hello")))
        assertTrue(f.store().read(f.source)!!.edits!!.cuts.isEmpty())
        assertArrayEquals(raw, f.source.readBytes())
    }

    @Test fun importsExistingMetadataAndReplaysUndoWithoutChangingOriginal() {
        val f = fixture()
        val original = f.source.readBytes()
        val captions = listOf(CaptionSegment(0, 1_000, "नमस्ते"), CaptionSegment(4_000, 5_000, "Hello"))
        val edits = EditDecision(10_000, listOf(EditCut("pause", 2_000, 3_000, "silence")))
        f.captions.write(f.source, captions)
        f.edits.write(f.source, edits)
        val first = f.store().read(f.source)!!
        assertEquals(captions, first.captions!!.map { it.toApp() })
        assertEquals(edits, first.edits!!.toApp())
        f.store().saveEdits(f.source, edits.toggle("pause"))
        val reopened = f.store().read(f.source)!!
        assertEquals(edits.toggle("pause"), reopened.edits!!.toApp())
        assertEquals(first.revision + 1, reopened.revision)
        assertArrayEquals(original, f.source.readBytes())
        assertEquals(1, f.ledgers.listFiles()!!.size)
    }

    @Test fun journalRemainsAuthoritativeIfLegacySidecarsDisappear() {
        val f = fixture()
        val expected = listOf(CaptionSegment(0, 1_000, "Saved in the engine"))
        f.store().saveCaptions(f.source, expected)
        f.captions.remove(f.source)
        assertEquals(expected, f.store().read(f.source)!!.captions!!.map { it.toApp() })
    }

    @Test fun replacementWithSamePathLengthAndTimeGetsNewSession() {
        val f = fixture()
        f.store().saveCaptions(f.source, listOf(CaptionSegment(0, 1_000, "old")))
        val old = f.store().read(f.source)!!
        val modified = f.source.lastModified()
        f.source.writeBytes(byteArrayOf(4, 3, 2, 1))
        check(f.source.setLastModified(modified))
        val replacement = f.store().read(f.source)!!
        assertNotEquals(old.sessionId, replacement.sessionId)
        assertNull(replacement.captions)
    }

    @Test fun badCompletedJournalFailsWithoutChangingMediaOrReplacingHistory() {
        val f = fixture()
        f.store().read(f.source)
        val ledger = f.ledgers.listFiles()!!.single()
        ledger.appendText("corrupt completed record\n")
        val before = ledger.readBytes()
        assertThrows(Exception::class.java) { f.store().read(f.source) }
        assertArrayEquals(before, ledger.readBytes())
        assertTrue(f.source.exists())
    }

    @Test fun deletingMetadataOnlyRemovesThatSourcesLedgers() {
        val f = fixture()
        f.store().read(f.source)
        val other = folder.newFile("another.mp4").apply { writeBytes(byteArrayOf(8, 9)) }
        f.store().read(other)
        f.store().remove(f.source)
        assertEquals(1, f.ledgers.listFiles()!!.size)
        assertTrue(f.source.exists())
        assertNotNull(f.store().read(other))
    }

    @Test fun invalidCaptionTimingIsRejectedWithoutReplacingExistingCaptions() {
        val f = fixture()
        val valid = listOf(CaptionSegment(0, 1_000, "keep this"))
        f.store().saveCaptions(f.source, valid)
        val before = f.store().read(f.source)
        assertThrows(IllegalArgumentException::class.java) {
            f.store().saveCaptions(f.source, listOf(CaptionSegment(10_000, 11_000, "invalid timing")))
        }
        assertEquals(before, f.store().read(f.source))
        assertEquals(valid, f.captions.read(f.source))
    }

    @Test fun missingRecordingIsPersistedAsMissingRatherThanForgotten() {
        val f = fixture()
        val before = f.store().read(f.source)!!
        check(f.source.delete())
        val missing = f.store().read(f.source)!!
        assertEquals(com.onetake.engine.SessionPhase.MISSING_MEDIA, missing.phase)
        assertEquals(before.revision + 1, missing.revision)
        assertEquals(missing, f.store().read(f.source))
    }

    @Test fun roundedLegacyDurationPreservesCutsDuringImport() {
        val f = fixture()
        val old = EditDecision(9_980, listOf(EditCut("pause", 2_000, 3_000, "silence")))
        f.edits.write(f.source, old)
        assertEquals(old.copy(durationMs = 10_000), f.store().read(f.source)!!.edits!!.toApp())
        assertEquals(old, f.edits.read(f.source))
    }

    @Test fun adoptingCaptureRetainsItsSessionUuid() {
        val f = fixture()
        val history = captureHistory(f, "00000000-0000-4000-8000-000000000101")

        f.store().adoptCapture(f.source, history)

        val adopted = f.store().read(f.source)!!
        assertEquals(history.first().sessionId, adopted.sessionId)
        assertEquals(1, f.ledgers.listFiles()!!.size)
    }

    @Test fun adoptingCapturePreservesImportedCaptionsEditsAndToggles() {
        val f = fixture()
        val captions = listOf(CaptionSegment(0, 1_000, "Imported caption"))
        val edits = EditDecision(10_000, listOf(EditCut("pause", 2_000, 3_000, "silence")))
        f.captions.write(f.source, captions)
        f.edits.write(f.source, edits)
        f.store().read(f.source)
        val toggled = edits.toggle("pause")
        f.store().saveEdits(f.source, toggled)

        val history = captureHistory(f, "00000000-0000-4000-8000-000000000102")
        f.store().adoptCapture(f.source, history)

        val adopted = f.store().read(f.source)!!
        assertEquals(history.first().sessionId, adopted.sessionId)
        assertEquals(captions, adopted.captions!!.map { it.toApp() })
        assertEquals(toggled, adopted.edits!!.toApp())
    }

    @Test fun repeatedAdoptionDoesNotOverwriteLaterEdits() {
        val f = fixture()
        val history = captureHistory(f, "00000000-0000-4000-8000-000000000103")
        f.store().adoptCapture(f.source, history)

        val later = EditDecision(10_000, listOf(EditCut("later", 4_000, 5_000, "manual")))
        f.store().saveEdits(f.source, later)
        val ledger = f.ledgers.listFiles()!!.single()
        val beforeRepeat = ledger.readBytes()

        f.store().adoptCapture(f.source, history)

        assertEquals(later, f.store().read(f.source)!!.edits!!.toApp())
        assertArrayEquals(beforeRepeat, ledger.readBytes())
    }

    @Test fun mismatchedMediaIsRejectedWithoutChangingExistingHistory() {
        val f = fixture()
        val history = captureHistory(f, "00000000-0000-4000-8000-000000000104")
        f.store().adoptCapture(f.source, history)
        val ledger = f.ledgers.listFiles()!!.single()
        val ledgerBefore = ledger.readBytes()
        val replacement = byteArrayOf(9, 8, 7, 6)
        f.source.writeBytes(replacement)

        assertThrows(IllegalArgumentException::class.java) {
            f.store().adoptCapture(f.source, history)
        }

        assertArrayEquals(ledgerBefore, ledger.readBytes())
        assertArrayEquals(replacement, f.source.readBytes())
        assertEquals(1, f.ledgers.listFiles()!!.size)
    }

    @Test fun adoptsPauseCandidatesWithoutTurningThemIntoCuts() {
        val f = fixture()
        val events = mutableListOf<Event>()
        val engine = EditingEngine("pause-session", EventSink { events += it })
        val candidate = com.onetake.engine.PauseCandidate("pause", 16_000, 48_000)
        engine.submit(0, Change.CaptureRequested(f.source.name), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(candidate.endSample, Change.PauseCandidateObserved(candidate), ClockDomain.RECOGNIZER)
        engine.submit(160_000, Change.SourceFinalized(recordingFingerprint(f.source), 160_000, VideoAnchor(0, 0)))
        f.store().adoptCapture(f.source, events)
        val adopted = f.store().read(f.source)!!
        assertEquals(listOf(candidate), adopted.pauseCandidates)
        assertNull(adopted.edits)
        assertNull(adopted.captions)
        val confirmed = EditDecision(10_000, listOf(EditCut("saved", 2_000, 4_000, "silence")))
        val decision = com.example.one_take.editing.LivePauseReconciler.reconcile(adopted.pauseCandidates, 0, confirmed)
        f.store().saveEdits(f.source, decision)
        f.store().saveEdits(f.source, decision.toggle("pause"))
        assertFalse(f.store().read(f.source)!!.edits!!.cuts.single().enabled)
        assertEquals(listOf(candidate), f.store().read(f.source)!!.pauseCandidates)
    }

    private fun captureHistory(f: Fixture, sessionId: String): List<Event> {
        val events = mutableListOf<Event>()
        val engine = EditingEngine(sessionId, EventSink { events += it })
        engine.submit(0L, Change.CaptureRequested(f.source.name), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0L, Change.CaptureStarted, ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(128_000L, Change.StopRequested("user"), ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(
            160_000L,
            Change.SourceFinalized(
                recordingFingerprint(f.source),
                160_000L,
                VideoAnchor(0L, 0L),
            ),
            ClockDomain.MEDIA,
        )
        return events.toList()
    }
}
