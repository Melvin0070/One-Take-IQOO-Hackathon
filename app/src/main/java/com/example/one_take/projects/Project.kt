package com.example.one_take.projects

import com.example.one_take.RecordingMode
import org.json.JSONObject

/** Versioned project metadata. Media and engine history remain owned by their existing stores. */
internal data class Project(
    val id: String,
    val createdAt: Long,
    val mode: RecordingMode,
    val scriptText: String?,
    val originalVideoPath: String,
    val journalPath: String?,
    val durationMs: Long?,
    val analysisPath: String? = null,
    val timelinePath: String? = null,
    val captionSettingsJson: String = "{}",
    val thumbnailPath: String? = null,
    val revision: Long = 0,
    val schemaVersion: Int = 1,
    val captureHeaderExplicit: Boolean = false,
    internal val preservedJson: String = "{}",
) {
    init {
        require(schemaVersion > 0 && revision >= 0 && createdAt >= 0) { "Invalid project header" }
        require(durationMs == null || durationMs > 0) { "Invalid project duration" }
        require(scriptText == null || mode == RecordingMode.Script) { "Assisted project cannot have a script" }
    }
}

/** Documents are kept verbatim; their codecs belong to the analysis/timeline modules. */
internal data class ProjectBundle(val project: Project, val analysisJson: String?, val timelineJson: String?)

internal object ProjectCodec {
    fun encode(project: Project): String = JSONObject(project.preservedJson).apply {
        put("schemaVersion", project.schemaVersion)
        put("revision", project.revision)
        put("captureHeaderExplicit", project.captureHeaderExplicit)
        put("id", project.id)
        put("createdAt", project.createdAt)
        put("mode", project.mode.name)
        put("scriptText", project.scriptText ?: JSONObject.NULL)
        put("originalVideoPath", project.originalVideoPath)
        put("journalPath", project.journalPath ?: JSONObject.NULL)
        put("durationMs", project.durationMs ?: JSONObject.NULL)
        put("analysisPath", project.analysisPath ?: JSONObject.NULL)
        put("timelinePath", project.timelinePath ?: JSONObject.NULL)
        put("captionSettings", JSONObject(project.captionSettingsJson))
        put("thumbnailPath", project.thumbnailPath ?: JSONObject.NULL)
    }.toString()

    fun decode(text: String): Project {
        val json = JSONObject(text)
        fun optionalString(name: String): String? = if (json.isNull(name)) null else json.getString(name)
        return Project(
            id = json.getString("id"), createdAt = json.getLong("createdAt"),
            mode = RecordingMode.valueOf(json.getString("mode")), scriptText = optionalString("scriptText"),
            originalVideoPath = json.getString("originalVideoPath"), journalPath = optionalString("journalPath"),
            durationMs = if (json.isNull("durationMs")) null else json.getLong("durationMs"),
            analysisPath = optionalString("analysisPath"), timelinePath = optionalString("timelinePath"),
            captionSettingsJson = json.getJSONObject("captionSettings").toString(),
            thumbnailPath = optionalString("thumbnailPath"), revision = json.getLong("revision"),
            schemaVersion = json.getInt("schemaVersion"), preservedJson = text,
            captureHeaderExplicit = json.optBoolean("captureHeaderExplicit", false),
        ).also {
            require(it.schemaVersion > 0 && it.revision >= 0 && it.createdAt >= 0) { "Invalid project header" }
            require(it.durationMs == null || it.durationMs > 0) { "Invalid project duration" }
        }
    }
}
