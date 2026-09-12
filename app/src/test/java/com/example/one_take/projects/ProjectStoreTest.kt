package com.example.one_take.projects

import com.example.one_take.RecordingMode
import com.example.one_take.VideoStore
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.editing.EditCut
import com.example.one_take.editing.EditDecision
import com.example.one_take.editing.EditRepository
import com.example.one_take.engine.EngineProjectStore
import java.io.File
import java.io.IOException
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProjectStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val root by lazy { temporary.newFolder() }
    private val videos by lazy { VideoStore(root) }
    private val manifests by lazy { File(root, "projects") }
    private val engine by lazy { EngineProjectStore(File(root, "ledgers"),
        CaptionRepository(root), EditRepository(root)) { 30_000L } }
    private fun store(publish: (File, ByteArray) -> Unit = ::writeProjectFile) =
        ProjectStore(manifests, videos, engine, publish = publish)

    private fun recording(): File {
        val pending = videos.createPendingRecording()
        pending.outputFile.writeText("original recording bytes ${pending.outputFile.name}")
        pending.complete()
        return pending.outputFile
    }

    @Test fun manifestRoundTripHasStableIdentityScriptSettingsAndJournal() {
        val source = recording()
        val project = store().getOrCreate(source, RecordingMode.Script, "First line\nSecond line")
        val saved = store().save(project.copy(captionSettingsJson = "{\"preset\":\"BOLD\",\"futureSetting\":7}"))
        val reopened = store().load(saved.id)!!
        assertEquals(saved.id, reopened.id)
        assertEquals("First line\nSecond line", reopened.scriptText)
        assertEquals(RecordingMode.Script, reopened.mode)
        assertEquals(source.canonicalPath, reopened.originalVideoPath)
        assertTrue(File(reopened.journalPath!!).isFile)
        assertEquals(30_000L, reopened.durationMs)
        assertEquals(7, JSONObject(reopened.captionSettingsJson).getInt("futureSetting"))
        assertEquals(listOf(reopened.id), store().list().map { it.id })
    }

    @Test fun savePreservesUnknownManifestAndNestedCaptionFields() {
        val project = store().getOrCreate(recording())
        val file = File(manifests, "${project.id}.json")
        val json = JSONObject(file.readText()).put("futureMetadata", JSONObject("{\"nested\":[1,2,3]}"))
        json.getJSONObject("captionSettings").put("newFeature", true)
        writeProjectFile(file, json.toString().toByteArray())
        val loaded = store().load(project.id)!!
        store().save(loaded.copy(mode = RecordingMode.Script, scriptText = "Preserve unknown fields"))
        val result = JSONObject(file.readText())
        assertEquals("[1,2,3]", result.getJSONObject("futureMetadata").getJSONArray("nested").toString())
        assertTrue(result.getJSONObject("captionSettings").getBoolean("newFeature"))
    }

    @Test fun migrationIsIdempotentDefaultsLegacyAndPreservesEditsAndOriginal() {
        val source = recording()
        val original = source.readBytes()
        engine.saveEdits(source, EditDecision(30_000, listOf(EditCut("pause", 5_000, 10_000, "silence"))))
        assertTrue(store().migrate(listOf(source)).isEmpty())
        val first = store().list().single()
        assertEquals(RecordingMode.Assisted, first.mode)
        assertNull(first.scriptText)
        assertNull(first.analysisPath)
        store().migrate(listOf(source))
        assertEquals(first, store().load(first.id))
        assertEquals(listOf("pause"), engine.read(source)!!.edits!!.cuts.map { it.id })
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun timelineDocumentReopensWithExactClipOrderAndAnalysisInSameRevision() {
        val source = recording()
        val original = source.readBytes()
        val project = store().getOrCreate(source)
        val timeline = "{\"version\":1,\"clips\":[{\"id\":\"second\",\"sourceStart\":160000,\"sourceEnd\":480000,\"state\":\"KEEP\",\"reason\":null},{\"id\":\"first\",\"sourceStart\":0,\"sourceEnd\":160000,\"state\":\"KEEP\",\"reason\":null}]}"
        val saved = store().saveDocuments(project, "{\"takes\":[]}", timeline)
        val bundle = store().readBundle(saved.id)!!
        assertEquals(timeline, bundle.timelineJson)
        assertEquals("{\"takes\":[]}", bundle.analysisJson)
        assertEquals(1L, bundle.project.revision)
        assertArrayEquals(original, source.readBytes())
    }

    @Test fun failedManifestPublishKeepsPreviousDocumentsAndRevision() {
        val project = store().getOrCreate(recording())
        val first = store().saveDocuments(project, "{\"version\":1}", "{\"clips\":[]}")
        val before = store().readBundle(first.id)!!
        val failing = store { file, bytes ->
            if (file.parentFile == manifests) throw IOException("Simulated manifest write failure")
            writeProjectFile(file, bytes)
        }
        try { failing.saveDocuments(first, "{\"version\":2}", "{\"clips\":[1]}"); fail("Write must fail") }
        catch (_: IOException) { }
        assertEquals(before, store().readBundle(first.id))
    }

    @Test fun staleSaveCannotReplaceANewerRevision() {
        val project = store().getOrCreate(recording())
        store().save(project.copy(captionSettingsJson = "{\"preset\":\"BOLD\"}"))
        try { store().save(project); fail("Stale save must fail") }
        catch (_: IllegalStateException) { }
        assertEquals("BOLD", JSONObject(store().load(project.id)!!.captionSettingsJson).getString("preset"))
    }

    @Test fun errorAfterManifestRenameDoesNotDeletePublishedDocuments() {
        val project = store().getOrCreate(recording())
        val failing = store { file, bytes ->
            writeProjectFile(file, bytes)
            if (file.parentFile == manifests) throw IOException("Simulated error after manifest rename")
        }
        try { failing.saveDocuments(project, timelineJson = "{\"clips\":[1,2]}"); fail("Write must report failure") }
        catch (_: IOException) { }
        assertEquals("{\"clips\":[1,2]}", store().readBundle(project.id)!!.timelineJson)
    }

    @Test fun corruptManifestIsRetainedAndDoesNotBlockOtherMigration() {
        val source = recording()
        val broken = store().getOrCreate(source)
        val manifest = File(manifests, "${broken.id}.json")
        manifest.writeText("corrupt")
        val other = recording()
        assertEquals(listOf(source), store().migrate(listOf(source, other)))
        assertEquals("corrupt", manifest.readText())
        assertNotNull(store().load(ProjectStore.idFor(other)))
    }

    @Test fun explicitCaptureHeaderWinsStartupMigrationDefaults() {
        val source = recording()
        val legacy = store().getOrCreate(source)
        val captured = store().getOrCreate(source, RecordingMode.Script, "Actual capture script")
        assertEquals(legacy.id, captured.id)
        assertEquals("Actual capture script", store().load(captured.id)!!.scriptText)
    }

    @Test fun deleteRemovesOnlyOwnedProjectAndRefusesActiveCapture() {
        val source = recording()
        val project = store().getOrCreate(source)
        val other = store().getOrCreate(recording())
        val pending = videos.createPendingRecording()
        try {
            pending.outputFile.writeText("active")
            assertFalse(store().deleteSource(pending.outputFile))
            assertTrue(pending.outputFile.exists())
            assertTrue(store().delete(project.id))
            assertFalse(source.exists())
            assertNull(store().load(project.id))
            assertTrue(File(other.originalVideoPath).exists())
            assertNotNull(store().load(other.id))
        } finally { pending.discard() }
    }

    @Test fun assetPathsCannotPointAtOriginalVideoOrAnotherProject() {
        val project = store().getOrCreate(recording())
        try { store().save(project.copy(timelinePath = project.originalVideoPath)); fail("Source must not be an asset") }
        catch (_: IllegalArgumentException) { }
        assertNull(store().load(project.id)!!.timelinePath)
    }

    @Test fun thumbnailAndCaptionSettingsArePersistedWithTheManifest() {
        val projectStore = ProjectStore(manifests, videos, engine,
            thumbnailBytes = { byteArrayOf(1, 2, 3) }, captionSettings = { "{\"preset\":\"BOLD\"}" })
        val project = projectStore.getOrCreate(recording())
        val reloaded = store().load(project.id)!!
        assertArrayEquals(byteArrayOf(1, 2, 3), File(reloaded.thumbnailPath!!).readBytes())
        assertEquals("BOLD", JSONObject(reloaded.captionSettingsJson).getString("preset"))
    }

    @Test fun unavailableJournalReferenceIsFilledWhenRecoveryBecomesReadable() {
        var readable = false
        val delayedEngine = EngineProjectStore(File(root, "delayed-ledgers"), CaptionRepository(root), EditRepository(root)) {
            if (!readable) throw IOException("Media metadata not ready")
            30_000L
        }
        val projectStore = ProjectStore(manifests, videos, delayedEngine)
        val source = recording()
        val first = projectStore.getOrCreate(source)
        assertNull(first.journalPath)
        readable = true
        val recovered = projectStore.getOrCreate(source)
        assertEquals(first.id, recovered.id)
        assertTrue(File(recovered.journalPath!!).isFile)
        assertEquals(30_000L, recovered.durationMs)
    }

    @Test fun invalidDurationCannotPublishAnUnreadableManifest() {
        val project = store().getOrCreate(recording())
        try { store().save(project.copy(durationMs = 0)); fail("Invalid duration must fail") }
        catch (_: IllegalArgumentException) { }
        assertEquals(30_000L, store().load(project.id)!!.durationMs)
    }
}
