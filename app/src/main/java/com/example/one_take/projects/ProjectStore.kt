package com.example.one_take.projects

import android.content.Context
import androidx.core.util.AtomicFile
import com.example.one_take.RecordingMode
import com.example.one_take.VideoStore
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.editing.EditRepository
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.recordingTimeline
import com.example.one_take.loadVideoThumbnail
import com.example.one_take.captions.CaptionStyleStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.CancellationException
import org.json.JSONObject

/**
 * Manifest persistence over EngineProjectStore. All operations belong on an I/O dispatcher.
 * Writes are serialized across instances/processes; revision checks reject stale editors.
 * Immutable document files are published before the manifest switches references atomically.
 */
internal class ProjectStore internal constructor(
    private val directory: File,
    private val videos: VideoStore,
    private val engine: EngineProjectStore,
    private val removeSidecars: (File) -> Unit = {},
    private val thumbnailBytes: (File) -> ByteArray? = { null },
    private val captionSettings: () -> String = { "{}" },
    private val publish: (File, ByteArray) -> Unit = ::writeProjectFile,
) {
    constructor(context: Context) : this(
        File(context.noBackupFilesDir, "projects"), VideoStore(context.filesDir), EngineProjectStore(context),
        { source -> CaptionRepository(context).remove(source); EditRepository(context).remove(source) },
        thumbnailBytes = { source -> loadVideoThumbnail(source)?.let { bitmap ->
            try {
                ByteArrayOutputStream().use { output ->
                    if (bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) output.toByteArray() else null
                }
            } finally { bitmap.recycle() }
        } },
        captionSettings = { JSONObject().put("preset", CaptionStyleStore.get(context).preset.name).toString() },
    )

    fun list(): List<Project> = locked {
        directory.listFiles().orEmpty().filter { it.isFile && it.extension == "json" }
            .map { readManifest(it.nameWithoutExtension) ?: throw IOException("Project disappeared") }
            .filter { File(it.originalVideoPath).isFile }
            .sortedByDescending { it.createdAt }
    }

    fun load(id: String): Project? = locked { readManifest(id) }

    /** Each bad recording is isolated by the caller's per-card fallback. Never reset a bad manifest. */
    fun getOrCreate(source: File, mode: RecordingMode? = null, scriptText: String? = null): Project = locked {
        val owned = ownedSource(source)
        val id = idFor(owned)
        readManifest(id)?.let { existing ->
            // Finalization can race startup discovery. Explicit capture inputs win over legacy defaults.
            if (mode != null) {
                val capturedScript = scriptText?.takeIf { mode == RecordingMode.Script && it.isNotBlank() }
                if (!existing.captureHeaderExplicit || existing.mode != mode || existing.scriptText != capturedScript) {
                    return@locked saveManifest(existing.copy(mode = mode, scriptText = capturedScript, captureHeaderExplicit = true))
                }
            }
            // Engine capture adoption can finish after first discovery imported legacy metadata.
            if ((!existing.captureHeaderExplicit && existing.scriptText == null) || existing.journalPath == null || existing.durationMs == null) {
                val recovered = try { engine.read(owned) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { null }
                val recoveredScript = recovered?.scriptProgress?.chunks?.joinToString("\n") { it.chunk.text }
                    ?.takeIf { it.isNotBlank() && !existing.captureHeaderExplicit && existing.scriptText == null }
                val repaired = existing.copy(
                    mode = if (recoveredScript != null) RecordingMode.Script else existing.mode,
                    scriptText = recoveredScript ?: existing.scriptText,
                    journalPath = existing.journalPath ?: recovered?.let { engine.journalFile(owned).canonicalPath },
                    durationMs = existing.durationMs ?: recovered?.durationSamples?.takeIf { it > 0 }?.let(recordingTimeline::msFromSamples),
                )
                if (repaired != existing && existing.schemaVersion == 1) {
                    // Only this engine-backed repair may fill an initially unavailable journal reference.
                    val next = repaired.copy(revision = Math.addExact(existing.revision, 1))
                    publish(manifest(id), ProjectCodec.encode(next).toByteArray(Charsets.UTF_8))
                    return@locked next
                }
            }
            return@locked existing
        }
        require(owned.isFile) { "Recording is missing" }
        require(videos.listVideos().any { it.canonicalFile == owned }) { "Recording is still active" }
        val state = try { engine.read(owned) } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        val savedScript = scriptText?.takeIf { it.isNotBlank() }
            ?: state?.scriptProgress?.chunks?.joinToString("\n") { it.chunk.text }
        val selectedMode = mode ?: if (savedScript != null) RecordingMode.Script else RecordingMode.Assisted
        val thumbnail = thumbnailBytes(owned)?.let { bytes ->
            File(assetDirectory(id), "thumbnail.png").also { publish(it, bytes) }.canonicalPath
        }
        val project = Project(
            id = id, createdAt = owned.lastModified().coerceAtLeast(0), mode = selectedMode,
            scriptText = savedScript.takeIf { selectedMode == RecordingMode.Script },
            originalVideoPath = owned.path,
            journalPath = state?.let { engine.journalFile(owned).canonicalPath },
            durationMs = state?.durationSamples?.takeIf { it > 0 }?.let(recordingTimeline::msFromSamples),
            captureHeaderExplicit = mode != null,
            thumbnailPath = thumbnail, captionSettingsJson = captionSettings(),
        )
        publish(manifest(id), ProjectCodec.encode(project).toByteArray(Charsets.UTF_8))
        project
    }

    /** Idempotent startup migration; one unreadable manifest does not prevent the rest. */
    fun migrate(sources: List<File>): List<File> = sources.mapNotNull { source ->
        try { getOrCreate(source); null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { source }
    }

    fun save(project: Project): Project = locked { saveManifest(project) }

    /** Null means retain the existing document. New bytes and the manifest publish as one revision. */
    fun saveDocuments(project: Project, analysisJson: String? = null, timelineJson: String? = null): Project = locked {
        checkCurrent(project)
        analysisJson?.let { JSONObject(it) }
        timelineJson?.let { JSONObject(it) }
        val created = mutableListOf<File>()
        fun document(kind: String, text: String?, previous: String?): String? {
            if (text == null) return previous
            val file = File(assetDirectory(project.id), "$kind-${UUID.randomUUID()}.json")
            created += file
            publish(file, text.toByteArray(Charsets.UTF_8))
            return file.canonicalPath
        }
        try {
            saveManifest(project.copy(
                analysisPath = document("analysis", analysisJson, project.analysisPath),
                timelinePath = document("timeline", timelineJson, project.timelinePath),
            ))
        } catch (error: Exception) {
            // A write can fail after rename. Never remove assets a published manifest references.
            val referenced = runCatching { readManifest(project.id)?.let {
                listOfNotNull(it.analysisPath, it.timelinePath, it.thumbnailPath).toSet()
            } }.getOrNull()
            if (referenced != null) created.filter { it.canonicalPath !in referenced }.forEach { AtomicFile(it).delete() }
            throw error
        }
    }

    fun readBundle(id: String): ProjectBundle? = locked {
        val project = readManifest(id) ?: return@locked null
        ProjectBundle(project, readDocument(project, project.analysisPath), readDocument(project, project.timelinePath))
    }

    fun delete(id: String): Boolean = locked {
        val project = readManifest(id) ?: return@locked true
        deleteSourceLocked(ownedSource(File(project.originalVideoPath)))
    }

    /** Also lets the UI delete an owned recording whose manifest cannot be decoded. */
    fun deleteSource(source: File): Boolean = locked { deleteSourceLocked(ownedSource(source)) }

    private fun deleteSourceLocked(source: File): Boolean {
        if (!videos.deleteVideo(source)) return false
        // The ownership check above must succeed before removing any associated metadata.
        val id = idFor(source)
        AtomicFile(manifest(id)).delete()
        assetDirectory(id).listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() }
        assetDirectory(id).delete()
        engine.remove(source)
        removeSidecars(source)
        return true
    }

    private fun saveManifest(project: Project): Project {
        val current = checkCurrent(project)
        validateReferences(project)
        listOfNotNull(project.analysisPath, project.timelinePath, project.thumbnailPath).forEach {
            require(File(it).isFile) { "Project asset is missing" }
        }
        val next = project.copy(revision = Math.addExact(project.revision, 1), preservedJson = current.preservedJson)
        publish(manifest(project.id), ProjectCodec.encode(next).toByteArray(Charsets.UTF_8))
        return next
    }

    private fun checkCurrent(project: Project): Project {
        val current = readManifest(project.id) ?: throw IOException("Project is missing")
        require(current.schemaVersion == 1 && project.schemaVersion == current.schemaVersion) { "Unsupported project version" }
        check(current.revision == project.revision) { "Project changed; reload before saving" }
        require(current.originalVideoPath == project.originalVideoPath && current.createdAt == project.createdAt &&
            current.journalPath == project.journalPath) { "Project source identity is immutable" }
        return current
    }

    private fun readManifest(id: String): Project? {
        val file = manifest(id)
        if (!file.exists() && !File(file.path + ".bak").exists()) return null
        val project = ProjectCodec.decode(AtomicFile(file).readFully().toString(Charsets.UTF_8))
        require(project.id == id && idFor(ownedSource(File(project.originalVideoPath))) == id) { "Project identity mismatch" }
        validateReferences(project)
        return project
    }

    private fun validateReferences(project: Project) {
        require(project.scriptText == null || project.mode == RecordingMode.Script) { "Assisted project cannot have a script" }
        JSONObject(project.captionSettingsJson)
        listOfNotNull(project.analysisPath, project.timelinePath, project.thumbnailPath).forEach { path ->
            require(File(path).canonicalFile.parentFile == assetDirectory(project.id).canonicalFile) { "Project asset escapes its directory" }
        }
    }

    private fun readDocument(project: Project, path: String?): String? = path?.let {
        validateReferences(project)
        File(it).readText(Charsets.UTF_8)
    }

    private fun ownedSource(source: File): File = source.canonicalFile.also {
        require(it.parentFile == videos.directory.canonicalFile && it.name.startsWith("video_") &&
            it.extension.equals("mp4", ignoreCase = true)) { "Recording is outside VideoStore" }
    }

    private fun manifest(id: String): File {
        require(UUID.fromString(id).toString() == id) { "Invalid project ID" }
        return File(directory, "$id.json").also {
            require(it.canonicalFile.parentFile == directory.canonicalFile) { "Manifest escapes project directory" }
        }
    }

    private fun assetDirectory(id: String): File {
        manifest(id)
        return File(directory, id).also {
            require(it.canonicalFile.parentFile == directory.canonicalFile) { "Assets escape project directory" }
        }
    }

    private fun <T> locked(action: () -> T): T = synchronized(processLock) {
        check(directory.isDirectory || directory.mkdirs()) { "Unable to create project directory" }
        RandomAccessFile(File(directory, ".lock"), "rw").use { file ->
            file.channel.lock().use { action() }
        }
    }

    companion object {
        private val processLock = Any()
        fun idFor(source: File): String = UUID.nameUUIDFromBytes(source.canonicalPath.toByteArray(Charsets.UTF_8)).toString()
    }
}

internal fun writeProjectFile(file: File, bytes: ByteArray) {
    check(file.parentFile!!.isDirectory || file.parentFile!!.mkdirs()) { "Unable to create project asset directory" }
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try {
        stream.write(bytes)
        stream.fd.sync()
        atomic.finishWrite(stream)
        check(file.readBytes().contentEquals(bytes)) { "Unable to publish project file" }
    }
    catch (error: Exception) { atomic.failWrite(stream); throw error }
}
