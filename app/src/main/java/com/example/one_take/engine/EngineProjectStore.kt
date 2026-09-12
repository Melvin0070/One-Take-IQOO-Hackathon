package com.example.one_take.engine

import android.content.Context
import android.media.MediaMetadataRetriever
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.captions.CaptionSegment
import com.example.one_take.editing.EditDecision
import com.example.one_take.editing.EditRepository
import com.example.one_take.editing.withSpeechSuggestions
import com.example.one_take.recordingFingerprint
import com.onetake.engine.*
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/** Android persistence boundary. Call from an I/O dispatcher, never the UI thread. */
internal class EngineProjectStore internal constructor(
    private val directory: File,
    private val captions: CaptionRepository,
    private val edits: EditRepository,
    private val durationMs: (File) -> Long,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "engine_ledgers"), CaptionRepository(context), EditRepository(context),
        { source ->
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(source.absolutePath)
                metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                    ?.takeIf { it > 0 } ?: throw IOException("Recording duration is unavailable")
            } finally { metadata.release() }
        },
    )

    @Synchronized fun read(source: File): EngineState? = synchronized(importLock) {
        if (source.isFile) {
            val engine = open(source)
            refreshSpeechSuggestions(engine, source)
            return engine.snapshot()
        }
        val prefix = pathKey(source) + "-"
        val ledger = directory.listFiles()?.filter {
            it.isFile && it.name.startsWith(prefix) && it.name.endsWith(".ledger")
        }?.maxWithOrNull(compareBy<File> { it.lastModified() }.thenBy { it.name }) ?: return null
        val recovered = engine(ledger.name.removeSuffix(".ledger"), ledger)
        val before = recovered.snapshot()
        if (before.phase == SessionPhase.READY || before.phase == SessionPhase.RECORDING) {
            recovered.submit(before.durationSamples, Change.MediaMissing)
        }
        return recovered.snapshot()
    }

    @Synchronized fun saveCaptions(source: File, segments: List<CaptionSegment>): Unit = synchronized(importLock) {
        val engine = open(source)
        val limit = recordingTimeline.msFromSamples(engine.snapshot().durationSamples)
        require(segments.all { it.startMs >= 0 && it.endMs > it.startMs && it.endMs <= limit }) {
            "Caption timing extends outside the recording. Regenerate captions from the saved video."
        }
        val mapped = segments.map { it.toEngine() }
        engine.submit(mapped.lastOrNull()?.endSample ?: 0, Change.CaptionsReplaced(mapped))
        // Retain readable sidecars for compatibility with existing tools and recordings.
        // The journal is authoritative after migration.
        captions.write(source, segments)
        refreshSpeechSuggestions(engine, source)
    }

    @Synchronized fun saveEdits(source: File, decision: EditDecision): Unit = synchronized(importLock) {
        val engine = open(source)
        val enriched = withSpeechSuggestions(decision, engine.snapshot().captions.orEmpty().map { it.toApp() })
        val replacement = enriched.toEngine()
        val previous = engine.snapshot().edits
        val changed = previous?.cuts?.zip(replacement.cuts)?.filter { it.first != it.second }.orEmpty()
        val change = when {
            previous != null && replacement == previous.restoreAll() -> Change.CutsRestored
            previous != null && previous.cuts.size == replacement.cuts.size && changed.size == 1 &&
                replacement == previous.toggle(changed.single().first.id) -> Change.CutToggled(changed.single().first.id)
            else -> Change.EditsReplaced(replacement)
        }
        val position = if (change is Change.CutToggled) {
            replacement.cuts.first { it.id == change.id }.startSample
        } else replacement.durationSamples
        engine.submit(position, change)
        edits.write(source, enriched)
    }

    /**
     * Appends media-confirmed signals to the adopted project history, which is authoritative after
     * finalization. Already-recorded ids and spans past the recording are skipped; returns the count saved.
     */
    @Synchronized fun saveSignals(source: File, signals: List<SessionSignal>): Int = synchronized(importLock) {
        val engine = open(source)
        signals.count { signal ->
            val state = engine.snapshot()
            val accepted = signal.key(ClockDomain.MEDIA) !in state.signalKeys && signal.endSample <= state.durationSamples
            if (accepted) engine.submit(signal.endSample, Change.SignalObserved(signal), ClockDomain.MEDIA)
            accepted
        }
    }

    /** The recording session adopted for [source], or null for media recorded without a capture header. */
    @Synchronized fun session(source: File): RecordingSessionSnapshot? = synchronized(importLock) {
        if (!source.isFile) return null
        val ledger = File(directory, "${pathKey(source)}-${recordingFingerprint(source)}.ledger")
        if (!ledger.isFile) return null
        val history = readHistory(ledger)
        if (history.firstOrNull()?.change !is Change.CaptureRequested) return null
        RecordingSessionReader.read(history)
    }

    /** Rebuild pending suggestions after caption correction, including recovery between journal writes. */
    private fun refreshSpeechSuggestions(engine: EditingEngine, source: File) {
        val state = engine.snapshot()
        val current = state.edits ?: return
        val refreshed = withSpeechSuggestions(current.toApp(), state.captions.orEmpty().map { it.toApp() })
        if (refreshed.toEngine() != current) {
            engine.submit(state.durationSamples, Change.EditsReplaced(refreshed.toEngine()))
            edits.write(source, refreshed)
        }
    }

    @Synchronized fun remove(source: File): Unit = synchronized(importLock) {
        val prefix = pathKey(source) + "-"
        directory.listFiles()?.filter { it.name.startsWith(prefix) && it.name.endsWith(".ledger") }
            ?.forEach { if (!it.delete()) throw IOException("Unable to remove engine history") }
    }

    /** Atomically adopts the capture UUID/history, preserving edits made during recovery. */
    @Synchronized fun adoptCapture(source: File, history: List<Event>): Unit = synchronized(importLock) {
        require(history.isNotEmpty()) { "Capture history is empty" }
        val identity = recordingFingerprint(source)
        val sessionId = history.first().sessionId
        val additions = mutableListOf<Event>()
        val capture = EditingEngine(sessionId, EventSink { additions += it }, history)
        val state = capture.snapshot()
        require(state.phase == SessionPhase.READY && state.sourceId == identity &&
            state.durationSamples == recordingTimeline.samplesFromMillis(durationMs(source))) {
            "Capture history does not match finalized media"
        }
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create engine history directory" }
        val target = File(directory, "${pathKey(source)}-$identity.ledger")
        if (target.exists()) {
            val existing = readHistory(target)
            require(existing.isNotEmpty()) { "Existing engine history is empty" }
            if (existing.first().sessionId == sessionId) return@synchronized
            val previous = EditingEngine(existing.first().sessionId, history = existing).snapshot()
            require(previous.sourceId == identity && previous.phase == SessionPhase.READY && !previous.captureRequested) {
                "This recording already belongs to a different capture session"
            }
            existing.filterNot { it.change is Change.SourceFinalized }.forEach {
                require(it.change is Change.CaptionsReplaced || it.change is Change.EditsReplaced ||
                    it.change is Change.CutToggled || it.change == Change.CutsRestored) { "Unexpected legacy event" }
                capture.submit(it.sample, it.change, it.clock)
            }
        }
        val temporary = File.createTempFile(".adopt-", ".tmp", directory)
        try {
            val journal = FileEventJournal(temporary)
            (history + additions).forEach { journal.append(JournalRecord(it.sequence, EngineEventCodec.encode(it))) }
            check(temporary.renameTo(target)) { "Unable to adopt capture history" }
        } finally { temporary.delete() }
    }

    private fun open(source: File): EditingEngine = synchronized(importLock) {
        require(source.isFile) { "Recording is missing" }
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create engine history directory" }
        val identity = recordingFingerprint(source)
        val session = pathKey(source) + "-" + identity
        val ledger = File(directory, "$session.ledger")
        if (!ledger.exists()) {
            // Import is published as a whole. A crash halfway through importing old
            // sidecars must not make the remaining old metadata disappear on restart.
            val temporary = File.createTempFile(".import-", ".tmp", directory)
            try {
                val fresh = engine(session, temporary)
                val duration = recordingTimeline.samplesFromMillis(durationMs(source))
                fresh.submit(duration, Change.SourceFinalized(identity, duration, VideoAnchor(0, 0)))
                captions.read(source)?.let { old ->
                    val limit = recordingTimeline.msFromSamples(fresh.snapshot().durationSamples)
                    val bounded = old.mapNotNull { caption ->
                        val end = caption.endMs.coerceAtMost(limit)
                        if (end > caption.startMs) caption.copy(endMs = end).toEngine() else null
                    }
                    fresh.submit(bounded.lastOrNull()?.endSample ?: 0, Change.CaptionsReplaced(bounded))
                }
                edits.read(source)?.let { old ->
                    val actualMs = recordingTimeline.msFromSamples(duration)
                    // Older sidecars can use a rounded duration. Preserve their cuts
                    // while adopting the finalized container boundary, never guessing
                    // across a material mismatch or silently removing a cut.
                    require(kotlin.math.abs(old.durationMs - actualMs) <= 250 &&
                        old.cuts.all { it.endMs <= actualMs }) { "Existing edit timing does not match the recording" }
                    fresh.submit(duration, Change.EditsReplaced(old.copy(durationMs = actualMs).toEngine()))
                }
                check(temporary.renameTo(ledger)) { "Unable to publish engine history" }
            } finally { temporary.delete() }
        }
        if (ledger.length() == 0L) throw IOException("Engine history is empty")
        engine(session, ledger).also {
            require(it.snapshot().sourceId == identity) { "Engine history belongs to different media" }
        }
    }

    private fun engine(session: String, file: File): EditingEngine {
        val journal = FileEventJournal(file)
        val history = readHistory(file)
        return EditingEngine(history.firstOrNull()?.sessionId ?: session, EventSink { event ->
            journal.append(JournalRecord(event.sequence, EngineEventCodec.encode(event)))
        }, history)
    }

    private fun readHistory(file: File): List<Event> = FileEventJournal(file).read().map { record ->
            EngineEventCodec.decode(record.payload).also {
                require(it.sequence == record.sequence) { "Engine ledger sequence mismatch" }
            }
    }

    private fun pathKey(source: File): String = MessageDigest.getInstance("SHA-256")
        .digest(source.canonicalPath.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object { private val importLock = Any() }
}
