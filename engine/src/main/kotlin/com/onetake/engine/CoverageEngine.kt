package com.onetake.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The brain. The single entry point. Contract §4.
 *
 * THE LOAD-BEARING RULE: [submit] is SINGLE-THREADED AND TOTALLY ORDERED.
 * One channel, one consumer loop. Inputs are sorted by sample index inside a
 * bounded reorder window and processed in order.
 *
 * Everything good downstream falls out of that one rule:
 *   - R14 deterministic replay is free
 *   - the eval harness is free
 *   - replay-driven UI development is free (`:app` builds screens against a
 *     recorded ledger before the aligner exists)
 *   - the brain has no locks and no races
 *
 * All three are literally the same call:
 *
 *     inputs.forEach(engine::submit)
 *
 * IF YOU ARE ABOUT TO ADD A THREAD, A MUTEX, OR A `System.currentTimeMillis()`
 * IN HERE: don't. Those are the three ways to break replay, and replay is what
 * the CTO juror asks about.
 */
class CoverageEngine(
    private val config: RuntimeConfig,
    private val script: Script,
    private val sessionId: String,
    /**
     * How far out of order inputs may arrive before the engine gives up waiting.
     * The ASR thread runs behind the VAD thread; this absorbs that without
     * letting a late `Final` reorder history. Samples, not milliseconds.
     */
    private val reorderWindowSamples: Long = (250L).millisToSamples(),
) {
    private val _events = MutableSharedFlow<LedgerEvent>(replay = 0, extraBufferCapacity = 512)

    /** The ONLY output. Everything the product shows is a fold over this. */
    val events: Flow<LedgerEvent> = _events.asSharedFlow()

    private val ledger = mutableListOf<LedgerEvent>()
    private val pending = ArrayDeque<EngineInput>()
    private var nextEventId = 0L
    private var highWaterSample = 0L

    /**
     * Push one input. Not thread-safe by design — call it from exactly one
     * thread (the capture reader, or the replay loop). `:app` and `:capture`
     * funnel everything through a single channel to get here.
     */
    fun submit(input: EngineInput) {
        pending.addLast(input)
        // Bounded reorder: hold inputs until nothing older can still arrive.
        val cutoff = maxOf(highWaterSample, input.sample) - reorderWindowSamples
        highWaterSample = maxOf(highWaterSample, input.sample)
        val ready = pending.filter { it.sample <= cutoff }.sortedBy { it.sample }
        ready.forEach { pending.remove(it) }
        ready.forEach(::process)
    }

    /** Flush the reorder window. Called on stop, and at the end of a replay. */
    fun drain() {
        val ready = pending.sortedBy { it.sample }
        pending.clear()
        ready.forEach(::process)
    }

    /**
     * The pure fold. Recomputed from the ledger, never cached across a session —
     * that is why a crash cannot corrupt it.
     */
    fun snapshot(): CoverageState = fold(script, ledger)

    /** The cut. Selection and ordering are two separate rules — see [EditList]. */
    fun editList(): EditList = deriveEditList(script, snapshot())

    // ------------------------------------------------------------------
    // LANE A implements below this line. Everything above is frozen.
    // ------------------------------------------------------------------

    private fun process(input: EngineInput) {
        // TODO(Lane A): the aligner. Contract §7 + §10 "Aligner rules" are the spec.
        //   - join an utterance to the open take, or open one for the best-matching
        //     line ACROSS THE WHOLE SCRIPT, biased to current/next/needed by
        //     config.lineMatchMarginBias. Covered lines are candidates (R20).
        //   - one-breath split covers BOTH lines and emits ONE segment — it splits
        //     COVERAGE, not audio. A must-say line is never split.
        //   - close on line-end pause, on a next-line match, or on advance.
        //   - verdict, with a MiscueType reason and a three-part VerdictLatency.
        //   - scratches: implicit (a later clean take supersedes) and explicit
        //     (a STANDALONE command utterance, never a line that contains "cut").
        TODO("Lane A: see docs/agents/lanes.md#lane-a and Contract §7")
    }

    private fun emit(event: LedgerEvent) {
        ledger += event
        _events.tryEmit(event)
    }

    private fun nextId(): Long = nextEventId++

    companion object {
        /**
         * The fold, as a free function so `:eval`, `:engine-fixtures` and any
         * test can run it over a ledger read off disk without constructing an
         * engine. Contract §6's table is the whole specification.
         */
        fun fold(script: Script, ledger: List<LedgerEvent>): CoverageState {
            // TODO(Lane A): implement Contract §6's table exactly. Start from
            //   CoverageState.initial(script) and fold left. No I/O, no clock,
            //   no randomness — this function must be a pure function of its
            //   arguments or R14 is not claimable.
            TODO("Lane A: Contract §6 coverage state machine")
        }

        /** Selection then ordering. R21's acceptance test is two lines long. */
        fun deriveEditList(script: Script, state: CoverageState): EditList {
            // TODO(Lane A): Contract §7.2.
            TODO("Lane A: Contract §7.2 edit-list selection and ordering")
        }
    }
}
