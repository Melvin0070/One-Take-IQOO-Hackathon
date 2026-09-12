package com.example.one_take.engine

import com.onetake.engine.Change
import com.onetake.engine.ClockDomain
import com.onetake.engine.EditingEngine
import com.onetake.engine.EngineState
import com.onetake.engine.Event
import com.onetake.engine.EventSink
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Durable storage for a capture while its media file is still being written.
 *
 * The store deliberately has no Android dependency.  A recording adapter can append lifecycle,
 * recognizer, and vision observations before CameraX has produced a finalized source.  State is
 * always rebuilt from the journal, so an interrupted process does not leave a second in-memory
 * state machine to reconcile with disk.
 */
internal class LiveCaptureStore(
    private val directory: File,
) {
    /** Creates a session and durably records its source name before returning the id. */
    fun begin(sourceName: String): String = withDirectoryLock {
        validateSourceName(sourceName)
        ensureDirectory()

        var attempts = 0
        while (attempts++ < MAX_SESSION_ALLOCATION_ATTEMPTS) {
            val session = UUID.randomUUID().toString()
            val ledger = ledgerFile(session)
            if (!ledger.createNewFile()) {
                continue
            }

            try {
                val journal = FileEventJournal(ledger)
                val engine = EditingEngine(session, durableEventSink(journal))
                engine.submit(
                    sample = 0L,
                    change = Change.CaptureRequested(sourceName),
                    clock = ClockDomain.CAPTURE_ESTIMATE,
                )
                return@withDirectoryLock session
            } catch (error: Throwable) {
                // The empty/reserved ledger belongs to this failed begin.  Remove only that
                // newly allocated path, leaving the caller's source and every other session
                // untouched.  A failed cleanup is useful evidence and must not be hidden.
                if (!ledger.delete() && ledger.exists()) {
                    error.addSuppressed(IOException("Unable to remove failed capture ledger"))
                }
                throw error
            }
        }

        error("Unable to allocate a unique capture session id")
    }

    /** Returns the complete durable event history for a known session. */
    fun events(session: String): List<Event> = withDirectoryLock {
        readHistory(session)
    }

    /** Replays a known session without repairing or modifying its ledger. */
    fun snapshot(session: String): EngineState = withDirectoryLock {
        val history = readHistory(session)
        EditingEngine(session, history = history).snapshot()
    }

    /**
     * Replays the latest history and submits one event through a durable sink.
     *
     * Replay and append share the directory lock.  This is required because a FileEventJournal
     * can protect one append at a time, but cannot by itself serialize the read/reduce/append
     * transaction performed by two LiveCaptureStore instances in this process.
     */
    fun append(
        session: String,
        sample: Long,
        change: Change,
        clock: ClockDomain,
    ): Event = withDirectoryLock {
        val ledger = requireLedger(session)
        val journal = FileEventJournal(ledger)
        val history = decodeHistory(journal.read(), session)
        val engine = EditingEngine(session, durableEventSink(journal), history)
        engine.submit(sample = sample, change = change, clock = clock)
    }

    /** Lists allocated session ids in deterministic order without opening their journals. */
    fun sessions(): List<String> = withDirectoryLock {
        if (!directory.isDirectory) {
            return@withDirectoryLock emptyList()
        }
        directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(LEDGER_SUFFIX) }
            .map { it.name.removeSuffix(LEDGER_SUFFIX) }
            .filter(::isCanonicalSession)
            .sorted()
            .toList()
    }

    /** Removes only ledgers whose durable first event names [sourceName]. */
    fun removeForSource(sourceName: String) = withDirectoryLock {
        validateSourceName(sourceName)
        if (!directory.isDirectory) {
            return@withDirectoryLock
        }

        // Read every candidate before deleting anything.  A corrupt ledger therefore cannot
        // cause a partial removal of otherwise valid sessions.
        val matches = directory.listFiles()
            .orEmpty()
            .asSequence()
            .filter { it.isFile && it.name.endsWith(LEDGER_SUFFIX) }
            .mapNotNull { ledger ->
                val session = ledger.name.removeSuffix(LEDGER_SUFFIX)
                if (!isCanonicalSession(session)) {
                    null
                } else {
                    val history = decodeHistory(FileEventJournal(ledger).read(), session)
                    EditingEngine(session, history = history)
                    val requested = history.firstOrNull()?.change as? Change.CaptureRequested
                    ledger.takeIf { requested?.sourceName == sourceName }
                }
            }
            .toList()

        matches.forEach { ledger ->
            if (!ledger.delete() && ledger.exists()) {
                throw IOException("Unable to remove capture ledger: ${ledger.path}")
            }
        }
    }

    private fun durableEventSink(journal: FileEventJournal): EventSink = EventSink { event ->
        journal.append(JournalRecord(event.sequence, EngineEventCodec.encode(event)))
    }

    private fun readHistory(session: String): List<Event> {
        val ledger = requireLedger(session)
        val history = decodeHistory(FileEventJournal(ledger).read(), session)
        EditingEngine(session, history = history)
        return history
    }

    private fun decodeHistory(records: List<JournalRecord>, session: String): List<Event> {
        if (records.isEmpty()) {
            throw IOException("Capture session journal is empty: ${ledgerFile(session).path}")
        }
        val history = records.map { record ->
            EngineEventCodec.decode(record.payload).also { event ->
                require(event.sequence == record.sequence) {
                    "Capture ledger sequence mismatch for session $session"
                }
                require(event.sessionId == session) {
                    "Capture ledger event belongs to another session"
                }
            }
        }
        if (history.first().change !is Change.CaptureRequested) {
            throw IOException("Capture session journal does not begin with a capture request: $session")
        }
        return history
    }

    private fun requireLedger(session: String): File {
        requireCanonicalSession(session)
        val ledger = ledgerFile(session)
        if (!ledger.isFile) {
            throw IOException("Capture session journal is missing: ${ledger.path}")
        }
        return ledger
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory) {
            throw IOException("Unable to create capture session directory: ${directory.path}")
        }
    }

    private fun ledgerFile(session: String): File = File(directory, "$session$LEDGER_SUFFIX")

    private fun <T> withDirectoryLock(block: () -> T): T {
        val key = canonicalPath(directory)
        val lock = DIRECTORY_LOCKS.computeIfAbsent(key) { Any() }
        return synchronized(lock) { block() }
    }

    private fun validateSourceName(sourceName: String) {
        require(sourceName.isNotBlank()) { "Capture source name must not be blank" }
        require('\u0000' !in sourceName) { "Capture source name must not contain NUL" }
        require('/' !in sourceName && '\\' !in sourceName) {
            "Capture source name must be a basename"
        }
        require(sourceName != "." && sourceName != ".." && ".." !in sourceName) {
            "Capture source name must not contain a parent traversal"
        }
    }

    private fun requireCanonicalSession(session: String) {
        require(isCanonicalSession(session)) { "Invalid capture session id" }
    }

    private fun isCanonicalSession(session: String): Boolean {
        if (!UUID_PATTERN.matches(session)) {
            return false
        }
        return runCatching { UUID.fromString(session).toString() == session }.getOrDefault(false)
    }

    private fun canonicalPath(file: File): String = runCatching { file.canonicalPath }
        .getOrElse { file.absolutePath }

    private companion object {
        const val LEDGER_SUFFIX = ".ledger"
        const val MAX_SESSION_ALLOCATION_ATTEMPTS = 8
        val UUID_PATTERN = Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
        )
        val DIRECTORY_LOCKS = ConcurrentHashMap<String, Any>()
    }
}
