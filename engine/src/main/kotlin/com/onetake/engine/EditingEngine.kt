package com.onetake.engine

/** Lifecycle of a single source recording session. */
enum class SessionPhase {
    RECORDING,
    FINALIZING,
    READY,
    CANCELLED,
    FAILED,
    INTERRUPTED,
    MISSING_MEDIA,
}

/**
 * Clock attached to an engine event.
 *
 * All clocks use the engine's 16 kHz sample units.  The domain records which
 * adapter produced that position; the engine deliberately does not infer an
 * offset between capture, recognition, and finalized media clocks.
 */
enum class ClockDomain {
    MEDIA,
    CAPTURE_ESTIMATE,
    RECOGNIZER,
}

/** A bounded vision observation supplied while a capture is active. */
data class VisionObservation(
    val faceInFrame: Boolean,
    val processor: String,
    val centerX: Float? = null,
    val centerY: Float? = null,
    val offAxis: Boolean = false,
) {
    init {
        require(processor in VALID_PROCESSORS) {
            "Vision processor must be CPU, GPU, NPU, or UNKNOWN"
        }
        centerX?.let { value ->
            require(value.isFinite() && value in 0f..1f) {
                "Vision centerX must be a finite normalized value"
            }
        }
        centerY?.let { value ->
            require(value.isFinite() && value in 0f..1f) {
                "Vision centerY must be a finite normalized value"
            }
        }
    }

    companion object {
        private val VALID_PROCESSORS = setOf("CPU", "GPU", "NPU", "UNKNOWN")
    }
}

/** The immutable state derived from an [EditingEngine]'s accepted events. */
data class EngineState(
    val sessionId: String,
    val phase: SessionPhase = SessionPhase.RECORDING,
    val sourceId: String? = null,
    val durationSamples: Long = 0L,
    val anchor: VideoAnchor? = null,
    val captions: List<Caption>? = null,
    val edits: EditPlan? = null,
    val revision: Long = 0L,
    val captureRequested: Boolean = false,
    val captureStarted: Boolean = false,
    val captureSourceName: String? = null,
    val provisionalText: String? = null,
    val lastVision: VisionObservation? = null,
    val failureReason: String? = null,
    val pauseCandidates: List<PauseCandidate> = emptyList(),
)

/** A state transition submitted to the engine. */
sealed interface Change {
    data class SourceFinalized(
        val sourceId: String,
        val durationSamples: Long,
        val anchor: VideoAnchor,
    ) : Change

    data class CaptionsReplaced(
        val captions: List<Caption>,
    ) : Change

    data class EditsReplaced(
        val edits: EditPlan,
    ) : Change

    data class CutToggled(
        val id: String,
    ) : Change

    data object CutsRestored : Change

    data object Cancelled : Change

    data object MediaMissing : Change

    data class CaptureRequested(
        val sourceName: String,
    ) : Change

    data object CaptureStarted : Change

    data class StopRequested(
        val reason: String,
    ) : Change

    data class CaptureFailed(
        val reason: String,
    ) : Change

    data class CaptureInterrupted(
        val reason: String,
    ) : Change

    data class ProvisionalTranscript(
        val text: String,
    ) : Change

    data class VisionObserved(
        val observation: VisionObservation,
    ) : Change

    data class PauseCandidateObserved(
        val candidate: PauseCandidate,
    ) : Change
}

/** An append-only input event.  Sequence numbers start at one per session. */
data class Event(
    val sequence: Long,
    val sessionId: String,
    val sample: Long,
    val change: Change,
    val clock: ClockDomain = ClockDomain.MEDIA,
)

fun interface EventSink {
    fun append(event: Event)
}

/**
 * Pure, synchronous reducer for the recording/editing foundation.
 *
 * [sample] is the event's source position.  It is validated for the known
 * duration, but accepted events are never re-sorted by it.  This preserves the
 * order in which late recognition and editing results actually arrived.
 */
class EditingEngine(
    sessionId: String,
    private val sink: EventSink = EventSink { },
    history: List<Event> = emptyList(),
) {
    private val sessionId: String
    private var state: EngineState
    private var nextSequence: Long

    init {
        require(sessionId.isNotBlank()) { "Session id must not be blank" }
        this.sessionId = sessionId
        val replayed = replay(history)
        state = replayed.first
        nextSequence = replayed.second
    }

    /** Returns a defensive immutable snapshot of the current state. */
    @Synchronized
    fun snapshot(): EngineState = copyState(state)

    /**
     * Validates and appends one event, then publishes its resulting state.
     * The sink is called before publication so a failed durable append leaves
     * both the state and sequence unchanged.
     */
    @Synchronized
    fun submit(
        sample: Long,
        change: Change,
        clock: ClockDomain = ClockDomain.MEDIA,
    ): Event {
        val normalizedChange = copyChange(change)
        validateClock(normalizedChange, clock)
        val current = state
        validateSample(current, sample, clock)
        val resultingState = reduce(current, sample, normalizedChange)
        val sequence = Math.addExact(nextSequence, 1L)
        val event = Event(sequence, sessionId, sample, normalizedChange, clock)

        sink.append(event)

        state = resultingState.copy(revision = sequence)
        nextSequence = sequence
        return event
    }

    private fun validateSample(current: EngineState, sample: Long, clock: ClockDomain) {
        require(sample >= 0L) { "Event sample must be non-negative" }
        if (current.phase == SessionPhase.READY && clock == ClockDomain.MEDIA) {
            require(sample <= current.durationSamples) {
                "Event sample must be within the finalized duration"
            }
        }
    }

    private fun validateClock(change: Change, clock: ClockDomain) {
        val required = when (change) {
            is Change.CaptureRequested,
            Change.CaptureStarted,
            is Change.StopRequested,
            is Change.CaptureFailed,
            is Change.CaptureInterrupted,
            is Change.VisionObserved,
            -> ClockDomain.CAPTURE_ESTIMATE

            is Change.ProvisionalTranscript,
            is Change.PauseCandidateObserved,
            -> ClockDomain.RECOGNIZER

            is Change.SourceFinalized,
            is Change.CaptionsReplaced,
            is Change.EditsReplaced,
            is Change.CutToggled,
            Change.CutsRestored,
            Change.Cancelled,
            Change.MediaMissing,
            -> ClockDomain.MEDIA
        }
        require(clock == required) {
            "${change::class.simpleName} requires $required clock"
        }
    }

    private fun reduce(current: EngineState, sample: Long, change: Change): EngineState {
        return when (change) {
            is Change.SourceFinalized -> {
                require(
                    current.phase == SessionPhase.RECORDING ||
                        current.phase == SessionPhase.FINALIZING ||
                        current.phase == SessionPhase.INTERRUPTED,
                ) {
                    "Source can only be finalized while recording, finalizing, or recovering an interruption"
                }
                require(change.sourceId.isNotBlank()) { "Source id must not be blank" }
                require(change.durationSamples >= 0L) {
                    "Finalized duration must be non-negative"
                }
                require(sample <= change.durationSamples) {
                    "Finalize sample must be within the finalized duration"
                }
                require(change.anchor.sampleIndex <= change.durationSamples) {
                    "Anchor sample must be within the finalized duration"
                }
                EngineState(
                    sessionId = sessionId,
                    phase = SessionPhase.READY,
                    sourceId = change.sourceId,
                    durationSamples = change.durationSamples,
                    anchor = change.anchor,
                    captions = null,
                    edits = null,
                    revision = current.revision,
                    captureRequested = current.captureRequested,
                    captureStarted = current.captureStarted,
                    captureSourceName = current.captureSourceName,
                    provisionalText = null,
                    lastVision = current.lastVision,
                    failureReason = current.failureReason,
                    pauseCandidates = current.pauseCandidates,
                )
            }

            is Change.CaptionsReplaced -> {
                requireMediaReady(current, "Captions")
                val captions = immutableCopy(change.captions.map(::copyCaption))
                validateCaptions(current.durationSamples, captions)
                current.copy(captions = captions, revision = current.revision)
            }

            is Change.EditsReplaced -> {
                requireMediaReady(current, "Edits")
                require(change.edits.durationSamples == current.durationSamples) {
                    "Edit plan duration must match the finalized media"
                }
                current.copy(edits = change.edits, revision = current.revision)
            }

            is Change.CutToggled -> {
                requireMediaReady(current, "Cut toggle")
                val edits = current.edits ?: error("No edit plan is available")
                current.copy(edits = edits.toggle(change.id), revision = current.revision)
            }

            Change.CutsRestored -> {
                requireMediaReady(current, "Restore cuts")
                val edits = current.edits ?: error("No edit plan is available")
                current.copy(edits = edits.restoreAll(), revision = current.revision)
            }

            is Change.CaptureRequested -> {
                require(current.phase == SessionPhase.RECORDING) {
                    "Capture can only be requested while recording"
                }
                require(current.revision == 0L && !current.captureRequested) {
                    "Capture request must be the first event"
                }
                require(change.sourceName.isNotBlank()) {
                    "Capture source name must not be blank"
                }
                current.copy(
                    captureRequested = true,
                    captureSourceName = change.sourceName,
                )
            }

            Change.CaptureStarted -> {
                require(
                    current.phase == SessionPhase.RECORDING ||
                        current.phase == SessionPhase.FINALIZING,
                ) {
                    "Capture can only start while recording or finalizing"
                }
                require(current.captureRequested) {
                    "Capture must be requested before it starts"
                }
                require(!current.captureStarted) {
                    "Capture has already started"
                }
                current.copy(captureStarted = true)
            }

            is Change.StopRequested -> {
                require(current.phase == SessionPhase.RECORDING) {
                    "Stop can only be requested while recording"
                }
                require(change.reason.isNotBlank()) {
                    "Stop reason must not be blank"
                }
                current.copy(phase = SessionPhase.FINALIZING)
            }

            is Change.CaptureFailed -> {
                require(
                    current.phase == SessionPhase.RECORDING ||
                        current.phase == SessionPhase.FINALIZING,
                ) {
                    "Capture can only fail while recording or finalizing"
                }
                require(change.reason.isNotBlank()) {
                    "Failure reason must not be blank"
                }
                current.copy(
                    phase = SessionPhase.FAILED,
                    failureReason = change.reason,
                )
            }

            is Change.CaptureInterrupted -> {
                require(
                    current.phase == SessionPhase.RECORDING ||
                        current.phase == SessionPhase.FINALIZING,
                ) {
                    "Capture can only be interrupted while recording or finalizing"
                }
                require(change.reason.isNotBlank()) {
                    "Interruption reason must not be blank"
                }
                current.copy(
                    phase = SessionPhase.INTERRUPTED,
                    failureReason = change.reason,
                )
            }

            is Change.ProvisionalTranscript -> {
                requireActiveCapture(current, "Provisional transcript")
                current.copy(provisionalText = change.text)
            }

            is Change.VisionObserved -> {
                requireActiveCapture(current, "Vision observation")
                current.copy(lastVision = change.observation)
            }

            is Change.PauseCandidateObserved -> {
                requireActiveCapture(current, "Pause candidate")
                require(current.pauseCandidates.none { it.id == change.candidate.id }) {
                    "Duplicate pause candidate id: ${change.candidate.id}"
                }
                val previous = current.pauseCandidates.lastOrNull()
                require(previous == null || change.candidate.startSample >= previous.endSample) {
                    "Pause candidates must be sorted and non-overlapping"
                }
                current.copy(
                    pauseCandidates = immutableCopy(current.pauseCandidates + change.candidate),
                )
            }

            Change.Cancelled -> {
                require(
                    current.phase == SessionPhase.RECORDING ||
                        current.phase == SessionPhase.FINALIZING ||
                        current.phase == SessionPhase.READY,
                ) {
                    "Session is already terminal"
                }
                current.copy(phase = SessionPhase.CANCELLED, revision = current.revision)
            }

            Change.MediaMissing -> {
                require(current.phase == SessionPhase.RECORDING || current.phase == SessionPhase.READY) {
                    "Session is already terminal"
                }
                current.copy(phase = SessionPhase.MISSING_MEDIA, revision = current.revision)
            }
        }
    }

    private fun requireMediaReady(current: EngineState, operation: String) {
        require(current.phase == SessionPhase.READY) {
            "$operation requires finalized media"
        }
        require(current.sourceId != null && current.anchor != null) {
            "$operation requires a finalized source"
        }
    }

    private fun requireActiveCapture(current: EngineState, operation: String) {
        require(
            current.captureStarted &&
                (current.phase == SessionPhase.RECORDING || current.phase == SessionPhase.FINALIZING),
        ) {
            "$operation requires an active capture"
        }
    }

    private fun validateCaptions(durationSamples: Long, captions: List<Caption>) {
        var previousEnd = 0L
        captions.forEachIndexed { index, caption ->
            require(caption.startSample >= previousEnd) {
                "Captions must be sorted and non-overlapping (index $index)"
            }
            require(caption.endSample <= durationSamples) {
                "Caption must be within the finalized duration (index $index)"
            }
            previousEnd = caption.endSample
        }
    }

    private fun copyState(value: EngineState): EngineState = value.copy(
        captions = value.captions?.map(::copyCaption)?.let(::immutableCopy),
        edits = value.edits?.let { EditPlan(it.durationSamples, it.cuts) },
        pauseCandidates = immutableCopy(value.pauseCandidates),
    )

    private fun copyChange(change: Change): Change = when (change) {
        is Change.SourceFinalized -> change.copy()
        is Change.CaptionsReplaced -> Change.CaptionsReplaced(
            immutableCopy(change.captions.map(::copyCaption)),
        )
        is Change.EditsReplaced -> Change.EditsReplaced(
            EditPlan(change.edits.durationSamples, change.edits.cuts),
        )
        is Change.CutToggled -> change.copy()
        Change.CutsRestored -> Change.CutsRestored
        Change.Cancelled -> Change.Cancelled
        Change.MediaMissing -> Change.MediaMissing
        is Change.CaptureRequested -> change.copy()
        Change.CaptureStarted -> Change.CaptureStarted
        is Change.StopRequested -> change.copy()
        is Change.CaptureFailed -> change.copy()
        is Change.CaptureInterrupted -> change.copy()
        is Change.ProvisionalTranscript -> change.copy()
        is Change.VisionObserved -> Change.VisionObserved(change.observation.copy())
        is Change.PauseCandidateObserved -> Change.PauseCandidateObserved(change.candidate.copy())
    }

    private fun replay(history: List<Event>): Pair<EngineState, Long> {
        var replayed = EngineState(sessionId = sessionId)
        var expectedSequence = 1L
        history.toList().forEach { event ->
            require(event.sequence == expectedSequence) {
                "History sequence must be contiguous starting at one"
            }
            require(event.sessionId == sessionId) {
                "History event belongs to a different session"
            }
            val normalizedChange = copyChange(event.change)
            validateClock(normalizedChange, event.clock)
            validateSample(replayed, event.sample, event.clock)
            replayed = reduce(replayed, event.sample, normalizedChange)
                .copy(revision = event.sequence)
            expectedSequence = Math.addExact(expectedSequence, 1L)
        }
        return replayed to (expectedSequence - 1L)
    }

    private fun copyCaption(caption: Caption): Caption = Caption(
        startSample = caption.startSample,
        endSample = caption.endSample,
        text = caption.text,
        words = immutableCopy(caption.words),
    )

    companion object {
        private fun <T> immutableCopy(values: List<T>): List<T> =
            java.util.Collections.unmodifiableList(values.toList())
    }
}
