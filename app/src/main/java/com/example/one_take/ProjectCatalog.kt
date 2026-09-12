package com.example.one_take

import android.content.Context
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.recordingTimeline
import com.onetake.engine.EngineState
import java.io.File
import java.util.concurrent.CancellationException

/** Read model for Projects; the existing engine journal remains authoritative. */
internal data class ProjectSummary(
    val id: String,
    val source: File,
    val createdAt: Long,
    val scriptTitle: String? = null,
    val mode: RecordingMode? = null,
    val durationMs: Long? = null,
    val edited: Boolean? = null,
    val detailsUnavailable: Boolean = false,
)

/** Adapts recovered recordings until the full ProjectStore contract (#53) lands. Call on I/O. */
internal class ProjectCatalog(private val readState: (File) -> EngineState?) {
    constructor(context: Context) : this(EngineProjectStore(context)::read)

    fun load(sources: List<File>): List<ProjectSummary> = sources.map { source ->
        val fallback = ProjectSummary(source.absolutePath, source, source.lastModified(), detailsUnavailable = true)
        try {
            val state = readState(source)
            if (state == null) fallback else {
                val script = state.scriptProgress
                ProjectSummary(
                    id = state.sessionId,
                    source = source,
                    createdAt = source.lastModified(),
                    scriptTitle = script?.chunks?.asSequence()?.flatMap { it.chunk.text.lineSequence() }
                        ?.map(String::trim)?.firstOrNull(String::isNotBlank),
                    mode = if (script != null) RecordingMode.Script else RecordingMode.Assisted,
                    durationMs = state.durationSamples.takeIf { it > 0 }?.let(recordingTimeline::msFromSamples),
                    edited = state.edits?.cuts?.any { it.enabled } == true,
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A damaged ledger or undecidable recovered file must not hide a recording.
            fallback
        }
    }
}
