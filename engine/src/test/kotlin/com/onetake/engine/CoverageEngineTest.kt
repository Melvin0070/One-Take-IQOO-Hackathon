package com.onetake.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageEngineTest {

    private val config = RuntimeConfig(configVersion = "test")

    @Test
    fun `rereading a covered line creates a take for that line and preserves later coverage`() {
        val script = Script(
            listOf(
                ScriptLine(LineId("L1"), "First line"),
                ScriptLine(LineId("L2"), "Second line"),
                ScriptLine(LineId("L3"), "Third line"),
            ),
        )
        val engine = engine(script)

        read(engine, "First line", 0, 1_000)
        read(engine, "Second line", 2_000, 3_000)
        read(engine, "Third line", 4_000, 5_000)
        read(engine, "First flub", 6_000, 7_000)

        val state = engine.snapshot()
        assertEquals(LineState.NEEDED, state.lines[LineId("L1")])
        assertEquals(LineState.COVERED, state.lines[LineId("L2")])
        assertEquals(LineState.COVERED, state.lines[LineId("L3")])
        assertEquals(2, state.takes.values.count { it.lineId == LineId("L1") })
        assertEquals(1, state.takes.values.count { it.lineId == LineId("L3") })
        assertEquals(listOf(LineId("L1")), state.neededLines)
        assertTrue(engine.editList().segments.none { it.lineId == LineId("L1") })
    }

    @Test
    fun `one breath across adjacent lines produces one cut segment and covers both lines`() {
        val script = Script(
            listOf(
                ScriptLine(LineId("L1"), "First line"),
                ScriptLine(LineId("L2"), "Second line"),
            ),
        )
        val engine = engine(script)

        read(engine, "First line Second line", 0, 2_000)

        val state = engine.snapshot()
        assertEquals(LineState.COVERED, state.lines[LineId("L1")])
        assertEquals(LineState.COVERED, state.lines[LineId("L2")])
        assertEquals(1, engine.editList().segments.size)
        assertEquals(
            listOf(LineId("L1"), LineId("L2")),
            engine.snapshot().takes.values.single().coveredLineIds,
        )
    }

    @Test
    fun `edit list selects a late pickup but keeps script order`() {
        val script = Script(
            listOf(
                ScriptLine(LineId("L1"), "First line"),
                ScriptLine(LineId("L2"), "Second line"),
                ScriptLine(LineId("L3"), "Third line"),
            ),
        )
        val engine = engine(script)
        read(engine, "Third line", 0, 1_000)
        read(engine, "First line", 2_000, 3_000)
        read(engine, "Second line", 4_000, 5_000)

        assertEquals(
            listOf(LineId("L1"), LineId("L2"), LineId("L3")),
            engine.editList().segments.map { it.lineId },
        )
    }

    @Test
    fun `standalone scratch and undo are represented in the append only ledger`() {
        val script = Script(listOf(ScriptLine(LineId("L1"), "First line")))
        val engine = engine(script)

        read(engine, "First line", 0, 1_000)
        val firstTake = engine.snapshot().takes.values.single()
        engine.submit(EngineInput.Action(UserAction.Scratch, 2_000))

        val scratch = engine.ledgerSnapshot().filterIsInstance<LedgerEvent.TakeScratched>().single()
        assertTrue(engine.snapshot().takes.getValue(firstTake.id).scratched)
        assertEquals(LineState.NEEDED, engine.snapshot().lines.getValue(LineId("L1")))

        engine.submit(EngineInput.Action(UserAction.Undo(scratch.id), 3_000))
        assertFalse(engine.snapshot().takes.getValue(firstTake.id).scratched)
        assertEquals(LineState.COVERED, engine.snapshot().lines.getValue(LineId("L1")))
        assertNotNull(engine.ledgerSnapshot().lastOrNull { it is LedgerEvent.UndoApplied })
    }

    @Test
    fun `recorded replay produces the same ledger and derived state`() {
        val script = Script(
            listOf(
                ScriptLine(LineId("L1"), "First line"),
                ScriptLine(LineId("L2"), "Second line"),
            ),
        )
        val inputs = listOf(
            EngineInput.Recognized(final("First line", 0, 1_000)),
            EngineInput.Action(UserAction.Advance, 2_000),
            EngineInput.Recognized(final("Second line", 3_000, 4_000)),
            EngineInput.Action(UserAction.Advance, 5_000),
        )

        val first = engine(script)
        val second = engine(script)
        inputs.forEach(first::submit)
        inputs.forEach(second::submit)

        assertEquals(first.ledgerSnapshot(), second.ledgerSnapshot())
        assertEquals(first.snapshot(), second.snapshot())
        assertEquals(first.editList(), second.editList())
    }

    @Test
    fun `fold keeps old takes after edit deletes without retaining them in the cut`() {
        val line = ScriptLine(LineId("L1"), "First line")
        val script = Script(listOf(line))
        val takeId = TakeId("take-1")
        val ledger = listOf<LedgerEvent>(
            LedgerEvent.TakeOpened(0, "s", 0, takeId, line.id, 1),
            LedgerEvent.TakeClosed(1, "s", 1_000, takeId, CloseReason.ADVANCE),
            LedgerEvent.VerdictReached(
                2,
                "s",
                1_000,
                takeId,
                Verdict.Clean(1f),
                VerdictLatency(1, 2, 3),
            ),
            LedgerEvent.CoverageChanged(3, "s", 1_000, line.id, LineState.COVERED),
            LedgerEvent.LineEdited(4, "s", 2_000, line.id, 2, "Updated line"),
        )

        val edited = CoverageEngine.fold(script, ledger)
        assertEquals(LineState.NEEDED, edited.lines.getValue(line.id))
        assertEquals(1, edited.takes.size)
        assertEquals(1, edited.takes.getValue(takeId).lineVersion)

        val deleted = CoverageEngine.fold(
            script,
            ledger + LedgerEvent.LineDeleted(5, "s", 3_000, line.id),
        )
        assertFalse(deleted.lines.containsKey(line.id))
        assertTrue(deleted.takes.getValue(takeId).orphaned)
        assertTrue(CoverageEngine.deriveEditList(script, deleted).segments.isEmpty())
    }

    @Test
    fun `fold reorders state keys without changing stable line ids`() {
        val script = Script(
            listOf(
                ScriptLine(LineId("L1"), "First line"),
                ScriptLine(LineId("L2"), "Second line"),
            ),
        )
        val reordered = CoverageEngine.fold(
            script,
            listOf(LedgerEvent.LineReordered(0, "s", 10, listOf(LineId("L2"), LineId("L1")))),
        )
        assertEquals(listOf(LineId("L2"), LineId("L1")), reordered.lines.keys.toList())
    }

    @Test
    fun `VAD endpoint closes a take after the configured pause`() {
        val script = Script(listOf(ScriptLine(LineId("L1"), "First line")))
        val engine = engine(script)
        engine.submit(EngineInput.Recognized(final("First line", 0, 1_000)))
        engine.submit(EngineInput.Vad(VadEvent.SpeechEnd(1_000)))
        engine.submit(EngineInput.Audio(AudioFrame(ShortArray(1), 1_000 + config.lineEndPauseMillis.millisToSamples())))

        assertTrue(engine.snapshot().takes.values.single().verdict is Verdict.Clean)
        assertEquals(CloseReason.LINE_END_PAUSE, engine.ledgerSnapshot().filterIsInstance<LedgerEvent.TakeClosed>().single().reason)
    }

    private fun engine(script: Script): CoverageEngine =
        CoverageEngine(config, script, "session", reorderWindowSamples = 0)

    private fun read(engine: CoverageEngine, text: String, start: Long, end: Long) {
        engine.submit(EngineInput.Recognized(final(text, start, end)))
        engine.submit(EngineInput.Action(UserAction.Advance, end + 100))
    }

    private fun final(text: String, start: Long, end: Long): RecognizerResult.Final =
        RecognizerResult.Final(
            words = text.split(" ").mapIndexed { index, word ->
                val wordStart = start + (end - start) * index / text.split(" ").size
                Word(word, wordStart, wordStart + 1, 1f)
            },
            startSample = start,
            endSample = end,
        )
}
