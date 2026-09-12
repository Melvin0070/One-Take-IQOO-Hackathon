package com.onetake.engine

import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

enum class ScriptChunkState { PENDING, COVERED, SKIPPED, MISMATCHED, REPEATED }
enum class ScriptProgressReason { INITIAL, TRANSCRIPT, MANUAL_NEXT, MANUAL_PREVIOUS }
data class ScriptChunk(val id: String, val text: String)
data class ChunkCoverage(val chunk: ScriptChunk, val state: ScriptChunkState = ScriptChunkState.PENDING,
                         val coverage: Double = 0.0, val attempts: Int = 0) {
    init { require(coverage.isFinite() && coverage in 0.0..1.0); require(attempts >= 0) }
}
data class ScriptProgress(val chunks: List<ChunkCoverage>, val currentIndex: Int,
                          val reason: ScriptProgressReason) {
    init { require(currentIndex in 0..chunks.size); require(chunks.map { it.chunk.id }.distinct().size == chunks.size) }
    val complete: Boolean get() = currentIndex == chunks.size
    fun frozen(): ScriptProgress = copy(chunks = Collections.unmodifiableList(chunks.toList()))
}
/** A committed segment, in recognizer sample units, not finalized media time. */
data class ScriptTranscript(val id: String, val text: String, val endSample: Long, val startSample: Long = endSample) {
    init { require(id.isNotBlank()); require(startSample in 0..endSample) }
}
interface ScriptMatcher {
    val progress: ScriptProgress
    fun consume(segment: ScriptTranscript)
    fun next(sample: Long)
    fun previous(sample: Long)
}

object ScriptChunker {
    fun split(script: String): List<ScriptChunk> {
        val occurrences = mutableMapOf<String, Int>()
        val clauses = script.lineSequence().flatMap { line ->
            val result = mutableListOf<String>()
            var pending = ""
            line.trim().split(Regex("(?<=[.!?;:,])\\s+")).filter { it.isNotBlank() }.forEach { clause ->
                val combined = listOf(pending, clause).filter { it.isNotBlank() }.joinToString(" ")
                if (pending.isNotBlank() && combined.split(Regex("\\s+")).size > 15) {
                    result += pending
                    pending = clause
                } else pending = combined
                if (pending.split(Regex("\\s+")).size >= 8) { result += pending; pending = "" }
            }
            if (pending.isNotBlank()) result += pending
            result.asSequence()
        }
        return clauses.flatMap { clause ->
            val words = clause.trim().split(Regex("\\s+"))
            val groups = (words.size + 14) / 15
            val size = (words.size + groups - 1) / groups
            words.chunked(size).map { it.joinToString(" ") }
        }.map { text ->
            val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
                .take(12).joinToString("") { "%02x".format(it) }
            val occurrence = occurrences.getOrDefault(digest, 0)
            occurrences[digest] = occurrence + 1
            ScriptChunk("$digest-$occurrence", text)
        }.toList()
    }
}

/** Single-owner matcher. Inputs are committed segments; duplicate delivery is idempotent. */
class FuzzyScriptMatcher(
    script: String,
    private val session: RecordingSession = RecordingSession { _, _, _ -> },
    private val threshold: Double = AUTO_ADVANCE_COVERAGE,
    private val lookAhead: Int = LOOK_AHEAD,
) : ScriptMatcher {
    init { require(threshold > 0 && threshold <= 1); require(lookAhead >= 0) }
    private val chunks = ScriptChunker.split(script)
    private val expected = chunks.map { tokens(it.text) }
    private val seen = mutableSetOf<String>()
    private var pending = emptyList<String>()
    override var progress = ScriptProgress(chunks.map { ChunkCoverage(it) }, 0, ScriptProgressReason.INITIAL).frozen()
        private set
    init { session.record(0, Change.ScriptProgressObserved(progress), ClockDomain.CAPTURE_ESTIMATE) }

    override fun consume(segment: ScriptTranscript) {
        if (!seen.add(segment.id)) return
        var spoken = tokens(segment.text)
        if (spoken.isEmpty()) return
        var allowRepeat = true
        while (spoken.isNotEmpty()) {
            val current = progress.currentIndex
            val window = (current until minOf(chunks.size, current + lookAhead + 1))
            val combined = (pending + spoken).takeLast(60)
            val forward = window.map { index -> index to match(expected[index], if (index == current) combined else spoken) }
                .maxByOrNull { it.second.score }
            val repeat = if (allowRepeat && (forward?.second?.score ?: 0.0) < 1.0) progress.chunks.indices.asSequence()
                .filter { progress.chunks[it].attempts > 0 && it != current }
                .map { it to match(expected[it], spoken) }.maxByOrNull { it.second.score } else null
            if (repeat != null && repeat.second.score >= threshold && repeat.second.score > (forward?.second?.score ?: 0.0)) {
                val entries = progress.chunks.toMutableList()
                val old = entries[repeat.first]
                entries[repeat.first] = old.copy(state = ScriptChunkState.REPEATED, coverage = repeat.second.score, attempts = old.attempts + 1)
                publish(entries, current, ScriptProgressReason.TRANSCRIPT, segment.endSample)
                session.signal(attempt(old, segment))
                pending = emptyList()
                spoken = spoken.drop(repeat.second.last + 1)
                allowRepeat = false
                continue
            }
            if (forward != null && forward.second.score >= threshold) {
                val index = forward.first
                val entries = progress.chunks.toMutableList()
                for (skipped in current until index) if (entries[skipped].attempts == 0) {
                    entries[skipped] = entries[skipped].copy(state = ScriptChunkState.SKIPPED)
                }
                val old = entries[index]
                entries[index] = old.copy(state = if (old.attempts > 0) ScriptChunkState.REPEATED else ScriptChunkState.COVERED,
                    coverage = forward.second.score, attempts = old.attempts + 1)
                publish(entries, index + 1, ScriptProgressReason.TRANSCRIPT, segment.endSample)
                if (old.attempts > 0) session.signal(attempt(old, segment))
                val consumed = forward.second.last + 1 - if (index == current) combined.size - spoken.size else 0
                spoken = spoken.drop(consumed.coerceAtLeast(1))
                pending = emptyList()
                allowRepeat = false
            } else {
                if (current < chunks.size && progress.chunks[current].attempts == 0) {
                    val score = match(expected[current], combined).score
                    val entries = progress.chunks.toMutableList()
                    // No lexical evidence is not a mismatch: unrelated rambling stays pending.
                    entries[current] = entries[current].copy(coverage = score,
                        state = if (score >= 0.25) ScriptChunkState.MISMATCHED else ScriptChunkState.PENDING)
                    publish(entries, current, ScriptProgressReason.TRANSCRIPT, segment.endSample)
                    pending = if (score >= 0.25) combined else emptyList()
                }
                break
            }
        }
    }

    override fun next(sample: Long) {
        require(sample >= 0)
        val index = progress.currentIndex
        if (index >= chunks.size) return
        val entries = progress.chunks.toMutableList()
        if (entries[index].attempts == 0) entries[index] = entries[index].copy(state = ScriptChunkState.SKIPPED)
        pending = emptyList()
        publish(entries, index + 1, ScriptProgressReason.MANUAL_NEXT, sample)
    }
    override fun previous(sample: Long) {
        require(sample >= 0)
        if (progress.currentIndex == 0) return
        pending = emptyList()
        publish(progress.chunks, progress.currentIndex - 1, ScriptProgressReason.MANUAL_PREVIOUS, sample)
    }
    private fun publish(entries: List<ChunkCoverage>, index: Int, reason: ScriptProgressReason, sample: Long) {
        if (entries == progress.chunks && index == progress.currentIndex) return
        val updated = ScriptProgress(entries, index, reason).frozen()
        session.record(sample, Change.ScriptProgressObserved(updated),
            if (reason == ScriptProgressReason.TRANSCRIPT) ClockDomain.RECOGNIZER else ClockDomain.CAPTURE_ESTIMATE)
        progress = updated
    }

    private fun attempt(chunk: ChunkCoverage, segment: ScriptTranscript) = TakeAttempt(
        "${chunk.chunk.id}#${chunk.attempts + 1}", chunk.chunk.id, chunk.attempts + 1, segment.startSample, segment.endSample)

    private data class Match(val score: Double, val last: Int)
    private fun match(script: List<String>, spoken: List<String>): Match {
        if (script.isEmpty()) return Match(0.0, -1)
        val used = BooleanArray(spoken.size)
        var count = 0
        var last = -1
        script.forEach { word ->
            var found = spoken.indices.firstOrNull { !used[it] && spoken[it] == word }
            if (found == null && word.length >= 5) found = spoken.indices.firstOrNull {
                !used[it] && spoken[it].length >= 5 && oneEdit(word, spoken[it])
            }
            if (found != null) { used[found] = true; count++; last = maxOf(last, found) }
        }
        return Match(count.toDouble() / script.size, last)
    }
    private fun oneEdit(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0; var j = 0; var errors = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++ } else {
                if (++errors > 1) return false
                if (a.length >= b.length) i++
                if (b.length >= a.length) j++
            }
        }
        return errors + (a.length - i) + (b.length - j) <= 1
    }
    companion object {
        const val AUTO_ADVANCE_COVERAGE = 0.6
        const val LOOK_AHEAD = 3
        private val words = Regex("[a-z0-9]+(?:'[a-z]+)?")
        private val contractions = mapOf("i'm" to "i am", "you're" to "you are", "we're" to "we are", "they're" to "they are",
            "it's" to "it is", "that's" to "that is", "don't" to "do not", "can't" to "can not", "won't" to "will not",
            "isn't" to "is not", "i'll" to "i will", "i've" to "i have", "we've" to "we have")
        // Keep negation tokens in the overlap evidence rather than treating them as stop words.
        private val stop = setOf("a", "an", "the", "i", "you", "we", "they", "it", "am", "is", "are", "to", "of", "and", "how")
        private val synonyms = mapOf("demonstrate" to "show", "application" to "app", "begin" to "start", "purchase" to "buy")
        private fun tokens(text: String): List<String> = words.findAll(text.lowercase(Locale.ROOT).replace('’', '\''))
            .flatMap { (contractions[it.value] ?: it.value).split(' ').asSequence() }
            .filter { it !in stop }.map { synonyms[it] ?: it }.toList()
    }
}
