package com.example.one_take

import android.content.Context
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.recordingTimeline
import com.example.one_take.projects.Project
import com.example.one_take.projects.ProjectStore
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
    val thumbnailPath: String? = null,
)

/** Joins durable project identity/header data with the engine's current edit state. Call on I/O. */
internal class ProjectCatalog(
    private val readState: (File) -> EngineState?,
    private val readProject: ((File) -> Project)?,
) {
    constructor(readState: (File) -> EngineState?) : this(readState, null)
    constructor(context: Context) : this(EngineProjectStore(context)::read,
        ProjectStore(context).let { store -> { source -> store.getOrCreate(source) } })

    fun load(sources: List<File>): List<ProjectSummary> = sources.map { source ->
        val fallback = ProjectSummary(source.absolutePath, source, source.lastModified(), detailsUnavailable = true)
        try {
            val project = readProject?.invoke(source)
            val state = try { readState(source) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (state == null && project == null) fallback else {
                val script = state?.scriptProgress
                ProjectSummary(
                    id = project?.id ?: state!!.sessionId,
                    source = source,
                    createdAt = project?.createdAt ?: source.lastModified(),
                    scriptTitle = if (project != null) project.scriptText?.lineSequence()?.map(String::trim)?.firstOrNull(String::isNotBlank)
                        else script?.chunks?.asSequence()?.flatMap { it.chunk.text.lineSequence() }
                        ?.map(String::trim)?.firstOrNull(String::isNotBlank),
                    mode = project?.mode ?: if (script != null) RecordingMode.Script else RecordingMode.Assisted,
                    durationMs = project?.durationMs ?: state?.durationSamples?.takeIf { it > 0 }?.let(recordingTimeline::msFromSamples),
                    edited = if (project?.timelinePath != null) true else state?.let { it.edits?.cuts?.any { cut -> cut.enabled } == true },
                    detailsUnavailable = state == null,
                    thumbnailPath = project?.thumbnailPath,
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
