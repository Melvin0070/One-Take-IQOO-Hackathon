package com.example.one_take

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso.pressBack
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.editing.EditCut
import com.example.one_take.editing.EditDecision
import com.example.one_take.engine.EngineProjectStore
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the production Projects -> persisted review route with owned media only. */
class ProjectsFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = VideoStore(context.filesDir)
    private val owned = mutableListOf<File>()

    @After fun cleanup() {
        compose.activityRule.scenario.close()
        owned.forEach { file ->
            com.example.one_take.projects.ProjectStore(context).deleteSource(file)
            EngineProjectStore(context).remove(file)
            com.example.one_take.editing.EditRepository(context).remove(file)
            com.example.one_take.captions.CaptionRepository(context).remove(file)
        }
    }

    @Test fun projectsReopenPersistedEditsAfterRecreationWithoutChangingOriginal() {
        val file = fixture()
        val hash = hash(file)
        val projects = EngineProjectStore(context)
        projects.saveEdits(file, EditDecision(30_000, listOf(
            EditCut("first", 5_000, 10_000, "silence"),
            EditCut("second", 15_000, 20_000, "silence", enabled = false),
        )))
        openProjects(file)
        compose.onNode(hasText("Edited") and hasAnyAncestor(hasTestTag(cardTag(file))), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("00:30") and hasAnyAncestor(hasTestTag(cardTag(file))), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag(cardTag(file)).performClick()
        waitForText("Edits")
        compose.activityRule.scenario.recreate()
        waitForText("Edits")
        compose.onNodeWithText("Edits").performClick()
        waitForText("Undo")
        compose.onNodeWithText("Apply").assertIsDisplayed()
        compose.onNodeWithText("Undo").performClick()
        compose.waitUntil(10_000) { projects.read(file)?.edits?.cuts?.all { !it.enabled } == true }
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Done").performClick()
        waitForCard(file)
        compose.onNode(hasText("Edited") and hasAnyAncestor(hasTestTag(cardTag(file))), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(cardTag(file)).performClick()
        waitForText("Edits")
        compose.onNodeWithText("Edits").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Apply").fetchSemanticsNodes().size == 2 }
        assertEquals(listOf("first", "second"), EngineProjectStore(context).read(file)!!.edits!!.cuts.map { it.id })
        assertEquals(hash, hash(file))
    }

    @Test fun projectsDeleteRequiresConfirmationAndBackReturnsHome() {
        val file = fixture()
        val other = fixture()
        openProjects(file)
        compose.onNodeWithContentDescription("Delete " + file.name).performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(file.isFile)
        compose.onNodeWithContentDescription("Delete " + file.name).performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { !file.exists() }
        compose.onNodeWithTag(cardTag(file)).assertDoesNotExist()
        assertTrue(other.isFile)
        pressBack()
        compose.onNodeWithTag("home-screen").assertIsDisplayed()
    }

    @Test fun scriptProjectUsesItsJournalTitleAndModeAfterRecreation() {
        val file = fixture()
        val history = mutableListOf<com.onetake.engine.Event>()
        val engine = com.onetake.engine.EditingEngine("projects-script-test-${file.name}",
            com.onetake.engine.EventSink { history += it })
        engine.submit(0, com.onetake.engine.Change.CaptureRequested(file.name), com.onetake.engine.ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0, com.onetake.engine.Change.CaptureStarted, com.onetake.engine.ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(0, com.onetake.engine.Change.ScriptProgressObserved(com.onetake.engine.ScriptProgress(
            listOf(com.onetake.engine.ChunkCoverage(com.onetake.engine.ScriptChunk("intro", "Welcome to my project"))),
            0, com.onetake.engine.ScriptProgressReason.INITIAL)), com.onetake.engine.ClockDomain.CAPTURE_ESTIMATE)
        engine.submit(480_000, com.onetake.engine.Change.SourceFinalized(
            recordingFingerprint(file), 480_000, com.onetake.engine.VideoAnchor(0, 0)))
        EngineProjectStore(context).adoptCapture(file, history)
        openProjects(file)
        compose.activityRule.scenario.recreate()
        waitForCard(file)
        compose.onNode(hasText("Welcome to my project") and hasAnyAncestor(hasTestTag(cardTag(file))), useUnmergedTree = true).assertIsDisplayed()
        compose.onNode(hasText("Script Mode") and hasAnyAncestor(hasTestTag(cardTag(file))), useUnmergedTree = true).assertIsDisplayed()
        val screenshot = instrumentation.uiAutomation.takeScreenshot()
        try {
            File(context.getExternalFilesDir(null), "projects-63.png").outputStream().use {
                screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        } finally { screenshot.recycle() }
    }

    @Test fun unavailableDetailsKeepRecordingVisibleAndDeletable() {
        val pending = store.createPendingRecording()
        val file = pending.outputFile
        owned += file
        file.writeText("undecidable test recording")
        pending.complete()
        openProjects(file)
        compose.onNode(hasText("Details unavailable") and hasAnyAncestor(hasTestTag(cardTag(file))), useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Delete " + file.name).performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog()), useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { !file.exists() }
    }

    @Test fun savedTimelineRemainsInItsOriginalOrderWhenOpeningRecordingFallback() {
        val file = fixture()
        val projects = com.example.one_take.projects.ProjectStore(context)
        val project = projects.getOrCreate(file)
        val timeline = "{\"version\":1,\"clips\":[{\"id\":\"second\",\"sourceStart\":160000,\"sourceEnd\":480000,\"state\":\"KEEP\",\"reason\":null},{\"id\":\"first\",\"sourceStart\":0,\"sourceEnd\":160000,\"state\":\"KEEP\",\"reason\":null}]}"
        projects.saveDocuments(project, timelineJson = timeline)
        openProjects(file)
        compose.onNodeWithTag(cardTag(file)).performClick()
        waitForText("Open recording")
        compose.activityRule.scenario.recreate()
        waitForText("Open recording")
        assertEquals(timeline, projects.readBundle(project.id)!!.timelineJson)
        compose.onNodeWithText("Open recording").performClick()
        waitForText("Edits")
        assertEquals(timeline, projects.readBundle(project.id)!!.timelineJson)
    }

    private fun fixture(): File {
        val pending = store.createPendingRecording()
        owned += pending.outputFile
        try {
            instrumentation.context.assets.open("caption-test.mp4").use { input ->
                pending.outputFile.outputStream().use { input.copyTo(it) }
            }
            pending.complete()
            return pending.outputFile
        } catch (error: Exception) { pending.discard(); throw error }
    }

    private fun openProjects(file: File) {
        compose.onNodeWithText("Projects").performClick()
        waitForCard(file)
        compose.onNodeWithTag("projects-screen").assertIsDisplayed()
    }

    private fun waitForCard(file: File) {
        compose.waitUntil(20_000) { compose.onAllNodesWithTag(cardTag(file)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun waitForText(text: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun cardTag(file: File) = "project-${file.name}"
    private fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).toList()
}
