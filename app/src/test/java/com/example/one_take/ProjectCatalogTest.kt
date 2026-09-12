package com.example.one_take

import com.onetake.engine.*
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test

class ProjectCatalogTest {
    private val source = File("video_1750000000000.mp4")

    @Test fun savedScriptAndEnabledEditsBecomeProjectDetails() {
        val state = EngineState("session-1", phase = SessionPhase.READY, durationSamples = 480_000,
            edits = EditPlan(480_000, listOf(Cut("pause", 80_000, 160_000, "silence"))),
            scriptProgress = ScriptProgress(listOf(ChunkCoverage(ScriptChunk("intro", "  My introduction\nSecond line  "))),
                0, ScriptProgressReason.INITIAL))
        val project = ProjectCatalog { state }.load(listOf(source)).single()
        assertEquals("session-1", project.id)
        assertEquals(source, project.source)
        assertEquals("My introduction", project.scriptTitle)
        assertEquals(RecordingMode.Script, project.mode)
        assertEquals(30_000L, project.durationMs)
        assertEquals(true, project.edited)
        assertFalse(project.detailsUnavailable)
    }

    @Test fun legacyRecordingDefaultsToAssistedAndRestoredCutsAreNotEdited() {
        val state = EngineState("legacy", phase = SessionPhase.READY, durationSamples = 480_000,
            edits = EditPlan(480_000, listOf(Cut("pause", 80_000, 160_000, "silence", enabled = false))))
        val project = ProjectCatalog { state }.load(listOf(source)).single()
        assertEquals(RecordingMode.Assisted, project.mode)
        assertNull(project.scriptTitle)
        assertEquals(false, project.edited)
    }

    @Test fun unreadableHistoryKeepsRecordingVisibleWithoutInventingDetails() {
        val good = File("video_good.mp4")
        val projects = ProjectCatalog { file ->
            if (file == source) throw IOException("Damaged history")
            EngineState("good", phase = SessionPhase.READY, durationSamples = 160_000)
        }.load(listOf(source, good))
        assertEquals(listOf(source, good), projects.map { it.source })
        assertNull(projects[0].durationMs)
        assertNull(projects[0].mode)
        assertNull(projects[0].edited)
        assertTrue(projects[0].detailsUnavailable)
        assertEquals(10_000L, projects[1].durationMs)
    }

    @Test fun missingStateIsUnavailableAndDoesNotPretendToBeZeroDuration() {
        val project = ProjectCatalog { null }.load(listOf(source)).single()
        assertTrue(project.detailsUnavailable)
        assertNull(project.durationMs)
    }

    @Test(expected = CancellationException::class)
    fun cancellationStopsCatalogLoading() {
        ProjectCatalog { throw CancellationException() }.load(listOf(source))
    }
}
