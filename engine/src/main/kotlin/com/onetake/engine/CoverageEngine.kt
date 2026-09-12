package com.onetake.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlin.math.max

/**
 * The brain. The single entry point. Contract §4.
 *
 * [submit] is deliberately single-threaded. Inputs are held in a bounded sample
 * window so recognizer finals can arrive behind VAD events without changing the
 * order in which the ledger is written.
 */
class CoverageEngine(
    private val config: RuntimeConfig,
    private val script: Script,
    private val sessionId: String,
    private val reorderWindowSamples: Long = (250L).millisToSamples(),
) {
    private val _events = MutableSharedFlow<LedgerEvent>(replay = 0, extraBufferCapacity = 512)
    private val ledger = mutableListOf<LedgerEvent>()
    private val pending = ArrayDeque<EngineInput>()
    private val normalizer = Normalizer(config)

    private var nextEventId = 0L
    private var nextTakeSequence = 0L
    private var highWaterSample = 0L
    private var currentLineId: LineId? = script.lines.firstOrNull()?.id
    private var openTake: OpenTake? = null
    private var lastTakeId: TakeId? = null
    private var pendingVadEnd: Long? = null

    /** The ONLY output. Everything the product shows is a fold over this stream. */
    val events: Flow<LedgerEvent> = _events.asSharedFlow()

    /** A persistence seam for app recovery. The returned list cannot mutate the log. */
    fun ledgerSnapshot(): List<LedgerEvent> = ledger.toList()

    /** Push one input. Call this from one channel consumer or a replay loop only. */
    fun submit(input: EngineInput) {
        pending.addLast(input)
        val cutoff = maxOf(highWaterSample, input.sample) - reorderWindowSamples
        highWaterSample = maxOf(highWaterSample, input.sample)
        val ready = pending.filter { it.sample <= cutoff }.sortedBy { it.sample }
        ready.forEach { pending.remove(it) }
        ready.forEach(::process)
    }

    /** Flush the reorder window, then close a take whose VAD endpoint is pending. */
    fun drain() {
        val ready = pending.sortedBy { it.sample }
        pending.clear()
        ready.forEach(::process)
        if (openTake != null && pendingVadEnd != null) {
            closeOpen(CloseReason.LINE_END_PAUSE, pendingVadEnd!!)
        } else if (openTake != null) {
            // A replay can end with a final recognizer result and no VAD event.
            // Closing it here gives the same durable state as a real stop without
            // requiring a synthetic wall-clock timestamp.
            closeOpen(CloseReason.STOP, openTake!!.endSample)
        }
    }

    /** Recompute derived coverage from the append-only ledger. */
    fun snapshot(): CoverageState = fold(script, ledger)

    /** Derive selection and script-ordering separately. */
    fun editList(): EditList = deriveEditList(script, snapshot())

    private fun process(input: EngineInput) {
        val endpoint = pendingVadEnd
        val isSpeechEnd = input is EngineInput.Vad && input.event is VadEvent.SpeechEnd
        if (openTake != null && endpoint != null &&
            input.sample >= endpoint + config.lineEndPauseMillis.millisToSamples() &&
            !isSpeechEnd
        ) {
            closeOpen(CloseReason.LINE_END_PAUSE, endpoint)
        }

        when (input) {
            is EngineInput.Audio -> Unit
            is EngineInput.Anchor -> Unit
            is EngineInput.Recognized -> when (val result = input.result) {
                is RecognizerResult.Partial -> Unit
                is RecognizerResult.Final -> handleFinal(result)
            }
            is EngineInput.Vad -> when (val event = input.event) {
                is VadEvent.SpeechStart -> pendingVadEnd = null
                is VadEvent.SpeechEnd -> pendingVadEnd = event.sample
            }
            is EngineInput.Vision -> {
                val take = openTake
                if (take != null && !input.frame.faceInFrame && !take.offFrame) {
                    take.offFrame = true
                    emit(
                        LedgerEvent.TakeOffFrame(
                            nextId(), sessionId, input.frame.sample, take.takeId,
                        ),
                    )
                }
            }
            is EngineInput.Action -> handleAction(input.action, input.sample)
            is EngineInput.AudioRoute -> emit(
                LedgerEvent.AudioRouteChanged(nextId(), sessionId, input.sample, input.device),
            )
            is EngineInput.Thermal -> emit(
                LedgerEvent.ThermalChanged(nextId(), sessionId, input.sample, input.status),
            )
        }
    }

    private fun handleFinal(result: RecognizerResult.Final) {
        if (result.words.isEmpty()) return
        val text = result.words.joinToString(" ") { it.text }.trim()
        if (text.isBlank()) return
        if (isStandaloneCommand(text)) {
            scratchLatest(ScratchSource.VOICE, result.endSample)
            return
        }

        val candidate = bestCandidate(text)
        val existing = openTake
        if (existing != null && candidate != null && candidate.lineIds.contains(existing.lineId)) {
            existing.texts += text
            existing.endSample = max(existing.endSample, result.endSample)
            existing.coveredLineIds = (existing.coveredLineIds + candidate.lineIds).distinct()
            emit(
                LedgerEvent.UtteranceClosed(
                    nextId(), sessionId, result.endSample, result.startSample, text,
                ),
            )
        } else {
            if (existing != null) closeOpen(CloseReason.NEXT_LINE_MATCHED, result.startSample)
            if (candidate != null) markSkippedCurrent(candidate, result.startSample)
            emit(
                LedgerEvent.UtteranceClosed(
                    nextId(), sessionId, result.endSample, result.startSample, text,
                ),
            )
            val selected = candidate ?: Candidate(
                lineIds = listOf(currentLineId ?: script.lines.firstOrNull()?.id ?: LineId("")),
                score = 0f,
            )
            val firstLine = selected.lineIds.firstOrNull { script.line(it) != null }
                ?: return
            val lineVersion = script.line(firstLine)?.version ?: 1
            val takeId = TakeId("$sessionId-take-${nextTakeSequence++}")
            openTake = OpenTake(
                takeId = takeId,
                lineId = firstLine,
                coveredLineIds = selected.lineIds.distinct(),
                lineVersion = lineVersion,
                startSample = result.startSample,
                endSample = result.endSample,
                texts = mutableListOf(text),
            )
            lastTakeId = takeId
            emit(
                LedgerEvent.TakeOpened(
                    nextId(), sessionId, result.startSample, takeId, firstLine, lineVersion,
                    selected.lineIds.distinct(),
                ),
            )
        }

        val endpoint = pendingVadEnd
        if (endpoint != null && result.endSample <= endpoint) {
            closeOpen(CloseReason.LINE_END_PAUSE, endpoint)
        }
    }

    private fun handleAction(action: UserAction, sample: Long) {
        when (action) {
            UserAction.Advance -> {
                if (openTake != null) closeOpen(CloseReason.ADVANCE, sample)
                else currentLineId = nextLine(currentLineId)
            }
            UserAction.Scratch -> scratchLatest(ScratchSource.TAP, sample)
            is UserAction.Circle -> {
                if (snapshot().takes.containsKey(action.takeId)) {
                    emit(LedgerEvent.TakeCircled(nextId(), sessionId, sample, action.takeId))
                }
            }
            is UserAction.Undo -> emit(
                LedgerEvent.UndoApplied(nextId(), sessionId, sample, action.eventId),
            )
            UserAction.Stop -> {
                if (openTake != null) closeOpen(CloseReason.STOP, sample)
                emit(LedgerEvent.SessionStopped(nextId(), sessionId, sample))
            }
        }
    }

    private fun scratchLatest(source: ScratchSource, sample: Long) {
        if (openTake != null) closeOpen(CloseReason.ADVANCE, sample)
        val target = lastTakeId ?: snapshot().takes.values
            .maxWithOrNull(compareBy<Take> { it.endSample }.thenBy { it.id.value })?.id
        if (target != null && snapshot().takes.containsKey(target)) {
            emit(LedgerEvent.TakeScratched(nextId(), sessionId, sample, target, source))
        }
    }

    private fun isStandaloneCommand(text: String): Boolean {
        if (!config.flags.scratchThatVoice) return false
        val phrase = normalizer.normalize(text).joinToString(" ")
        return config.commandPhrases.any { normalizer.normalize(it).joinToString(" ") == phrase }
    }

    private fun markSkippedCurrent(candidate: Candidate, sample: Long) {
        val current = currentLineId ?: return
        if (current in candidate.lineIds || snapshot().lines[current] == LineState.COVERED) return
        emit(LedgerEvent.CoverageChanged(nextId(), sessionId, sample, current, LineState.NEEDED))
        emit(LedgerEvent.RetakeNeeded(nextId(), sessionId, sample, current))
    }

    private fun closeOpen(reason: CloseReason, sample: Long) {
        val take = openTake ?: return
        val actualText = take.texts.joinToString(" ")
        val expectedText = take.coveredLineIds.mapNotNull { script.line(it)?.text }
            .joinToString(" ")
        val verdict = verdictFor(take, expectedText, actualText)
        emit(LedgerEvent.TakeClosed(nextId(), sessionId, sample, take.takeId, reason))
        emit(
            LedgerEvent.VerdictReached(
                nextId(), sessionId, sample, take.takeId, verdict,
                // The pure engine has no wall clock. Platform integrations can
                // replace these with their measured stages instead of fabricating it.
                VerdictLatency(0, 0, 0),
            ),
        )

        val offFrame = take.offFrame
        val state = if (verdict is Verdict.Clean || offFrame) LineState.COVERED else LineState.NEEDED
        take.coveredLineIds.forEach { lineId ->
            emit(LedgerEvent.CoverageChanged(nextId(), sessionId, sample, lineId, state))
            if (state == LineState.NEEDED && !offFrame) {
                emit(LedgerEvent.RetakeNeeded(nextId(), sessionId, sample, lineId))
            }
        }
        openTake = null
        pendingVadEnd = null
        currentLineId = nextCurrent(take.lineId)
        if (state == LineState.COVERED && snapshot().wrapReady) {
            emit(LedgerEvent.WrapReady(nextId(), sessionId, sample))
        }
    }

    private fun verdictFor(take: OpenTake, expectedText: String, actualText: String): Verdict {
        val type = take.coveredLineIds.mapNotNull { script.line(it)?.type }
            .firstOrNull { it == LineType.MUST_SAY }
        val mode = if (type == LineType.MUST_SAY) Normalizer.Mode.MUST_SAY_STRICT else Normalizer.Mode.NORMAL
        val expected = normalizer.normalize(expectedText, mode)
        val actual = normalizer.normalize(actualText, mode)
        val accuracy = similarity(expected, actual)
        val clean = if (type == LineType.MUST_SAY) {
            expected == actual
        } else {
            accuracy >= config.cleanThreshold
        }
        if (clean) return Verdict.Clean(accuracy)

        val missing = expected.filter { it !in actual }
            .map { it.removePrefix("#") }
        if (missing.isNotEmpty()) return Verdict.MissingWords(missing)
        val repetition = actual.zipWithNext().any { it.first == it.second }
        if (repetition) return Verdict.Flubbed(MiscueType.REPETITION, "repeated a word")
        val detail = when {
            actual.size > expected.size -> "extra words"
            actual.isEmpty() -> "no words heard"
            else -> "words differed from the script"
        }
        val reason = when {
            actual.size > expected.size -> MiscueType.INSERTION
            actual.size < expected.size -> MiscueType.OMISSION
            else -> MiscueType.MISPRONUNCIATION
        }
        return Verdict.Flubbed(reason, detail)
    }

    private fun bestCandidate(text: String): Candidate? {
        if (script.lines.isEmpty()) return null
        val candidates = buildList {
            script.lines.forEach { line ->
                add(Candidate(listOf(line.id), similarityFor(line.text, text)))
            }
            script.lines.zipWithNext().forEach { (first, second) ->
                if (first.type != LineType.MUST_SAY && second.type != LineType.MUST_SAY) {
                    add(
                        Candidate(
                            listOf(first.id, second.id),
                            similarityFor("${first.text} ${second.text}", text),
                        ),
                    )
                }
            }
        }
        val global = candidates.maxWithOrNull(
            compareBy<Candidate> { it.score }
                .thenByDescending { -it.lineIds.size }
                .thenBy { it.lineIds.joinToString("|") { id -> id.value } },
        ) ?: return null
        val localIds = linkedSetOf<LineId>().apply {
            currentLineId?.let(::add)
            currentLineId?.let { id -> script.lines.getOrNull(script.indexOf(id) + 1)?.id }?.let(::add)
            snapshot().neededLines.forEach(::add)
        }
        val local = candidates.filter { candidate -> candidate.lineIds.any(localIds::contains) }
            .maxWithOrNull(compareBy<Candidate> { it.score }.thenBy { it.lineIds.joinToString("|") { id -> id.value } })
        return if (local != null && !global.lineIds.any(localIds::contains) &&
            global.score < local.score + config.lineMatchMarginBias
        ) local else global
    }

    private fun similarityFor(expected: String, actual: String): Float =
        similarity(normalizer.normalize(expected), normalizer.normalize(actual))

    private fun nextCurrent(lineId: LineId): LineId? {
        val state = snapshot()
        val start = script.indexOf(lineId).coerceAtLeast(0)
        val ordered = script.lines.drop(start + 1) + script.lines.take(start + 1)
        return ordered.firstOrNull { state.lines[it.id] != LineState.COVERED }?.id
            ?: lineId
    }

    private fun nextLine(lineId: LineId?): LineId? {
        if (script.lines.isEmpty()) return null
        val index = lineId?.let(script::indexOf) ?: -1
        return script.lines.getOrNull(index + 1)?.id ?: script.lines.first().id
    }

    private fun emit(event: LedgerEvent) {
        ledger += event
        _events.tryEmit(event)
    }

    private fun nextId(): Long = nextEventId++

    private data class Candidate(val lineIds: List<LineId>, val score: Float)

    private class OpenTake(
        val takeId: TakeId,
        val lineId: LineId,
        var coveredLineIds: List<LineId>,
        val lineVersion: Int,
        val startSample: Long,
        var endSample: Long,
        val texts: MutableList<String>,
        var offFrame: Boolean = false,
    )

    companion object {
        /**
         * Fold an append-only ledger. Undo excludes the target event from this
         * replay while retaining the original event for auditability.
         */
        fun fold(script: Script, ledger: List<LedgerEvent>): CoverageState {
            val undone = ledger.asSequence()
                .filterIsInstance<LedgerEvent.UndoApplied>()
                .map { it.undoneEventId }
                .toSet()
            val lines = LinkedHashMap<LineId, LineState>()
            script.lines.forEach { lines[it.id] = LineState.UNREAD }
            val versions = script.lines.associate { it.id to it.version }.toMutableMap()
            val takes = LinkedHashMap<TakeId, Take>()
            var openTake: TakeId? = null
            var currentLine: LineId? = script.lines.firstOrNull()?.id

            fun setState(lineId: LineId, state: LineState) {
                if (lines.containsKey(lineId)) lines[lineId] = state
            }

            fun coveredIds(take: Take): List<LineId> =
                (take.coveredLineIds.ifEmpty { listOf(take.lineId) }).distinct()

            fun hasUsable(lineId: LineId): Boolean = takes.values.any { take ->
                lineId in coveredIds(take) && take.usable &&
                    !take.videoMissing && !take.orphaned && take.lineVersion == versions[lineId]
            }

            fun retractState(lineId: LineId) {
                if (!hasUsable(lineId)) setState(lineId, LineState.NEEDED)
            }

            ledger.forEach { event ->
                if (event.id in undone || event is LedgerEvent.UndoApplied) return@forEach
                when (event) {
                    is LedgerEvent.TakeOpened -> {
                        val take = Take(
                            id = event.takeId,
                            lineId = event.lineId,
                            sessionId = event.sessionId,
                            startSample = event.sample,
                            endSample = event.sample,
                            verdict = null,
                            lineVersion = event.lineVersion,
                            coveredLineIds = event.coveredLineIds,
                        )
                        takes[event.takeId] = take
                        openTake = event.takeId
                        currentLine = event.lineId
                    }
                    is LedgerEvent.UtteranceClosed -> Unit
                    is LedgerEvent.TakeClosed -> {
                        takes[event.takeId]?.let { take ->
                            takes[event.takeId] = take.copy(endSample = max(take.endSample, event.sample))
                            if (openTake == event.takeId) openTake = null
                            if (lines[take.lineId] == LineState.UNREAD || lines[take.lineId] == LineState.NEEDED) {
                                setState(take.lineId, LineState.PENDING)
                            }
                        }
                    }
                    is LedgerEvent.VerdictReached -> {
                        val existing = takes[event.takeId]
                        if (existing != null) {
                            val updated = existing.copy(verdict = event.verdict)
                            takes[event.takeId] = updated
                            val ids = coveredIds(updated)
                            val validVersion = ids.all { updated.lineVersion == versions[it] }
                            ids.forEach { lineId ->
                                if (validVersion && !updated.videoMissing) {
                                    setState(
                                        lineId,
                                        if (event.verdict is Verdict.Clean) LineState.COVERED else LineState.NEEDED,
                                    )
                                } else {
                                    setState(lineId, LineState.NEEDED)
                                }
                            }
                        }
                    }
                    is LedgerEvent.CoverageChanged -> {
                        currentLine = event.lineId
                        setState(event.lineId, event.state)
                    }
                    is LedgerEvent.RetakeNeeded -> setState(event.lineId, LineState.NEEDED)
                    is LedgerEvent.TakeScratched -> {
                        takes[event.takeId]?.let { take ->
                            val updated = take.copy(scratched = true)
                            takes[event.takeId] = updated
                            coveredIds(updated).forEach(::retractState)
                        }
                    }
                    is LedgerEvent.TakeCircled -> {
                        takes[event.takeId]?.let { takes[event.takeId] = it.copy(circled = true) }
                    }
                    is LedgerEvent.TakeOffFrame -> {
                        takes[event.takeId]?.let { takes[event.takeId] = it.copy(offFrame = true) }
                    }
                    is LedgerEvent.TakeVideoMissing -> {
                        takes[event.takeId]?.let { take ->
                            val updated = take.copy(videoMissing = true)
                            takes[event.takeId] = updated
                            coveredIds(updated).forEach(::retractState)
                        }
                    }
                    is LedgerEvent.LineEdited -> {
                        versions[event.lineId] = event.version
                        setState(event.lineId, LineState.NEEDED)
                        currentLine = event.lineId
                    }
                    is LedgerEvent.LineDeleted -> {
                        takes.replaceAll { _, take ->
                            if (take.lineId == event.lineId || event.lineId in coveredIds(take)) {
                                take.copy(orphaned = true)
                            } else take
                        }
                        lines.remove(event.lineId)
                        versions.remove(event.lineId)
                        if (currentLine == event.lineId) currentLine = lines.keys.firstOrNull()
                    }
                    is LedgerEvent.LineReordered -> {
                        val reordered = LinkedHashMap<LineId, LineState>()
                        event.order.forEach { id -> lines[id]?.let { reordered[id] = it } }
                        lines.forEach { (id, state) -> if (id !in reordered) reordered[id] = state }
                        lines.clear()
                        lines.putAll(reordered)
                    }
                    is LedgerEvent.UndoApplied -> Unit
                    is LedgerEvent.WrapReady,
                    is LedgerEvent.SessionStopped,
                    is LedgerEvent.PlaybackReady,
                    is LedgerEvent.ExportProgress,
                    is LedgerEvent.ProjectDeleted,
                    is LedgerEvent.SyncOffset,
                    is LedgerEvent.AudioRouteChanged,
                    is LedgerEvent.ThermalChanged,
                    is LedgerEvent.VisionInference -> Unit
                }
            }

            val strictReady = script.lines.filter { it.id in lines && it.type == LineType.MUST_SAY }
                .all { line -> hasUsable(line.id) }
            val wrapReady = lines.isNotEmpty() && lines.values.all { it == LineState.COVERED } && strictReady
            return CoverageState(
                lines = lines.toMap(),
                takes = takes.toMap(),
                openTake = openTake,
                currentLine = currentLine,
                wrapReady = wrapReady,
            )
        }

        /** Select one take per line, then deduplicate multi-line takes and order by script. */
        fun deriveEditList(script: Script, state: CoverageState): EditList {
            val selected = mutableListOf<Take>()
            val seen = mutableSetOf<TakeId>()
            state.lines.keys.forEach { lineId ->
                if (state.lines[lineId] != LineState.COVERED) return@forEach
                val candidates = state.takes.values.filter { take ->
                    lineId in (take.coveredLineIds.ifEmpty { listOf(take.lineId) }) && take.usable
                }
                if (candidates.isEmpty()) return@forEach
                val visible = candidates.filterNot(Take::offFrame).ifEmpty { candidates }
                val circled = visible.filter(Take::circled)
                val chosen = (circled.ifEmpty { visible }).maxWithOrNull(
                    compareBy<Take> { it.endSample }.thenBy { it.id.value },
                ) ?: return@forEach
                if (seen.add(chosen.id)) selected += chosen
            }
            return EditList(
                selected.map { take ->
                    Segment(
                        takeId = take.id,
                        lineId = take.lineId,
                        // Take has no file path in the frozen core contract. The
                        // app resolves this opaque session key through its source registry.
                        sourceFile = "",
                        deviceId = "",
                        inSample = take.startSample,
                        outSample = take.endSample,
                    )
                },
            )
        }

        private fun similarity(expected: List<String>, actual: List<String>): Float {
            if (expected.isEmpty() && actual.isEmpty()) return 1f
            if (expected.isEmpty() || actual.isEmpty()) return 0f
            val distance = Array(expected.size + 1) { FloatArray(actual.size + 1) }
            for (i in expected.indices) distance[i + 1][0] = i + 1f
            for (j in actual.indices) distance[0][j + 1] = j + 1f
            for (i in expected.indices) {
                for (j in actual.indices) {
                    val substitution = 1f - tokenSimilarity(expected[i], actual[j])
                    distance[i + 1][j + 1] = minOf(
                        distance[i][j + 1] + 1f,
                        distance[i + 1][j] + 1f,
                        distance[i][j] + substitution,
                    )
                }
            }
            return (1f - distance[expected.size][actual.size] / max(expected.size, actual.size)).coerceIn(0f, 1f)
        }

        private fun tokenSimilarity(expected: String, actual: String): Float {
            if (expected == actual) return 1f
            if (expected.isBlank() || actual.isBlank()) return 0f
            val distance = Array(expected.length + 1) { IntArray(actual.length + 1) }
            for (i in expected.indices) distance[i + 1][0] = i + 1
            for (j in actual.indices) distance[0][j + 1] = j + 1
            for (i in expected.indices) {
                for (j in actual.indices) {
                    distance[i + 1][j + 1] = minOf(
                        distance[i][j + 1] + 1,
                        distance[i + 1][j] + 1,
                        distance[i][j] + if (expected[i] == actual[j]) 0 else 1,
                    )
                }
            }
            return 1f - distance[expected.length][actual.length].toFloat() /
                max(expected.length, actual.length)
        }
    }
}
