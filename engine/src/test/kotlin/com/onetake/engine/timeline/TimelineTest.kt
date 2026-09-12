package com.onetake.engine.timeline

import com.onetake.engine.Cut
import com.onetake.engine.EditPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineTest {
    @Test
    fun timelineCopiesTheCallerClipListAndRejectsDuplicateIds() {
        val source = mutableListOf(Clip("source", 0L, 16_000L))
        val timeline = Timeline(source)

        source += Clip("later", 16_000L, 32_000L)

        assertEquals(listOf(Clip("source", 0L, 16_000L)), timeline.clips)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (timeline.clips as MutableList<Clip>) += Clip("mutated", 32_000L, 48_000L)
        }

        assertThrows(IllegalArgumentException::class.java) {
            Timeline(listOf(Clip("duplicate", 0L, 10L), Clip("duplicate", 10L, 20L)))
        }
    }

    @Test
    fun keptClipsAreReturnedAsAnImmutableSnapshot() {
        val timeline = Timeline(listOf(
            Clip("kept", 0L, 16_000L),
            Clip("recommended", 16_000L, 20_000L, ClipState.RECOMMENDED_REMOVE, "pause"),
        ))

        val kept = timeline.keptClips()

        assertEquals(listOf(Clip("kept", 0L, 16_000L)), kept)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (kept as MutableList<Clip>) += Clip("mutated", 20_000L, 24_000L)
        }
        assertEquals(16_000L, timeline.exportedDurationSamples())
    }

    @Test
    fun trimChangesOnlyAnIntervalWithinTheCurrentClipBounds() {
        val timeline = Timeline(listOf(
            Clip("source", 0L, 16_000L, ClipState.RECOMMENDED_REMOVE, "pause"),
            Clip("later", 16_000L, 20_000L),
        ))

        val trimmed = timeline.trim("source", 2_000L, 14_000L)

        assertEquals(
            listOf(
                Clip("source", 2_000L, 14_000L, ClipState.RECOMMENDED_REMOVE, "pause"),
                Clip("later", 16_000L, 20_000L),
            ),
            trimmed.clips,
        )
        assertNotSame(timeline, trimmed)
        assertEquals(timeline, timeline.trim("source", -1L, 14_000L))
        assertEquals(timeline, timeline.trim("source", 2_000L, 16_001L))
        assertEquals(timeline, timeline.trim("source", 8_000L, 8_000L))
        assertEquals(timeline, timeline.trim("unknown", 2_000L, 14_000L))
    }

    @Test
    fun splitAtAnInteriorPointMakesDeterministicUniqueChildren() {
        val timeline = Timeline(listOf(Clip("source", 0L, 16_000L, ClipState.RECOMMENDED_REMOVE, "pause")))

        val split = timeline.split("source", 6_000L)

        assertEquals(
            listOf(
                Clip("source:left@6000", 0L, 6_000L, ClipState.RECOMMENDED_REMOVE, "pause"),
                Clip("source:right@6000", 6_000L, 16_000L, ClipState.RECOMMENDED_REMOVE, "pause"),
            ),
            split.clips,
        )
        assertEquals(split, timeline.split("source", 6_000L))
        assertEquals(2, split.clips.map { it.id }.toSet().size)
        assertEquals(timeline, timeline.split("source", 0L))
        assertEquals(timeline, timeline.split("source", 16_000L))
        assertEquals(timeline, timeline.split("source", 16_001L))
        assertEquals(timeline, timeline.split("unknown", 6_000L))
    }

    @Test
    fun splitAvoidsExistingGeneratedIds() {
        val timeline = Timeline(listOf(
            Clip("source", 0L, 16_000L),
            Clip("source:left@6000", 16_000L, 20_000L),
            Clip("source:right@6000", 20_000L, 24_000L),
        ))

        val split = timeline.split("source", 6_000L)

        assertEquals("source:left@6000#1", split.clips[0].id)
        assertEquals("source:right@6000#1", split.clips[1].id)
    }

    @Test
    fun deleteMarksClipsRemovedAndRestoreKeepsTheirReason() {
        val timeline = Timeline(listOf(
            Clip("source", 0L, 16_000L, ClipState.RECOMMENDED_REMOVE, "pause"),
            Clip("later", 16_000L, 20_000L),
        ))

        val deleted = timeline.delete("source")
        val restored = deleted.restore("source")

        assertEquals(ClipState.REMOVED, deleted.clips.first().state)
        assertEquals("pause", deleted.clips.first().reason)
        assertEquals(Clip("source", 0L, 16_000L, ClipState.KEEP, "pause"), restored.clips.first())
        assertEquals(timeline, timeline.delete("unknown"))
        assertEquals(timeline, timeline.restore("later"))
    }

    @Test
    fun reorderUsesTheRequestedFinalIndexAndRejectsOutOfRangeIndices() {
        val timeline = Timeline(listOf(
            Clip("first", 0L, 10L),
            Clip("second", 10L, 20L),
            Clip("third", 20L, 30L),
        ))

        assertEquals(
            listOf("second", "third", "first"),
            timeline.reorder("first", 2).clips.map { it.id },
        )
        assertEquals(
            listOf("second", "first", "third"),
            timeline.reorder("second", 0).clips.map { it.id },
        )
        assertEquals(timeline, timeline.reorder("first", -1))
        assertEquals(timeline, timeline.reorder("first", 3))
        assertEquals(timeline, timeline.reorder("unknown", 1))
    }

    @Test
    fun fromAnalysisSortsCandidatesAndBuildsKeepGaps() {
        val timeline = Timeline.fromAnalysis(
            duration = 100L,
            candidates = listOf(
                RemovalCandidate(60L, 70L, "late pause"),
                RemovalCandidate(20L, 30L, "early pause"),
            ),
        )

        assertEquals(
            listOf(
                Clip("gap-0-20", 0L, 20L),
                Clip("candidate-20-30", 20L, 30L, ClipState.RECOMMENDED_REMOVE, "early pause"),
                Clip("gap-30-60", 30L, 60L),
                Clip("candidate-60-70", 60L, 70L, ClipState.RECOMMENDED_REMOVE, "late pause"),
                Clip("gap-70-100", 70L, 100L),
            ),
            timeline.clips,
        )
        assertEquals(80L, timeline.exportedDurationSamples())
        assertEquals(3, timeline.keptClips().size)
    }

    @Test
    fun fromAnalysisKeepsTheWholeSourceWhenThereAreNoCandidates() {
        val timeline = Timeline.fromAnalysis(100L, emptyList())

        assertEquals(listOf(Clip("gap-0-100", 0L, 100L)), timeline.clips)
        assertEquals(100L, timeline.exportedDurationSamples())
    }

    @Test
    fun fromAnalysisHandlesAdjacentAndFullSourceCandidates() {
        val adjacent = Timeline.fromAnalysis(
            100L,
            listOf(
                RemovalCandidate(20L, 40L, "first"),
                RemovalCandidate(40L, 60L, "second"),
            ),
        )
        val full = Timeline.fromAnalysis(100L, listOf(RemovalCandidate(0L, 100L, "all")))

        assertEquals(
            listOf("gap-0-20", "candidate-20-40", "candidate-40-60", "gap-60-100"),
            adjacent.clips.map { it.id },
        )
        assertEquals(60L, adjacent.exportedDurationSamples())
        assertEquals(0L, full.exportedDurationSamples())
        assertEquals(1, full.clips.size)
    }

    @Test
    fun fromAnalysisValidatesDurationCandidatesAndOverlap() {
        assertThrows(IllegalArgumentException::class.java) {
            Timeline.fromAnalysis(-1L, emptyList())
        }
        assertThrows(IllegalArgumentException::class.java) {
            Timeline.fromAnalysis(100L, listOf(RemovalCandidate(90L, 101L, "outside")))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Timeline.fromAnalysis(
                100L,
                listOf(RemovalCandidate(10L, 30L, "first"), RemovalCandidate(29L, 40L, "overlap")),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemovalCandidate(10L, 10L, "empty")
        }
        assertThrows(IllegalArgumentException::class.java) {
            RemovalCandidate(10L, 20L, " ")
        }
    }

    @Test
    fun fromEditPlanPreservesCutIdsAndAvoidsGapIdCollisions() {
        val plan = EditPlan(
            durationSamples = 20_000L,
            cuts = listOf(
                Cut("gap-0-2000", 2_000L, 4_000L, "pause", enabled = true),
                Cut("second-cut", 6_000L, 8_000L, "manual", enabled = false),
            ),
        )

        val timeline = Timeline.fromEditPlan(plan)

        assertEquals(
            listOf(
                Clip("gap-0-2000#1", 0L, 2_000L),
                Clip("gap-0-2000", 2_000L, 4_000L, ClipState.RECOMMENDED_REMOVE, "pause"),
                Clip("gap-4000-6000", 4_000L, 6_000L),
                Clip("second-cut", 6_000L, 8_000L, ClipState.KEEP, "manual"),
                Clip("gap-8000-20000", 8_000L, 20_000L),
            ),
            timeline.clips,
        )
        assertEquals(18_000L, timeline.exportedDurationSamples())
        assertTrue(timeline.keptClips().none { it.id == "gap-0-2000" })
    }

    @Test
    fun fromEditPlanHandlesAnEmptyPlanAndZeroDuration() {
        val plan = EditPlan(durationSamples = 20_000L, cuts = emptyList())

        assertEquals(
            listOf(Clip("gap-0-20000", 0L, 20_000L)),
            Timeline.fromEditPlan(plan).clips,
        )
        assertEquals(emptyList<Clip>(), Timeline.fromEditPlan(EditPlan(0L, emptyList())).clips)
    }

    @Test
    fun exportedDurationUsesExactLongArithmetic() {
        val timeline = Timeline(listOf(
            Clip("first", 0L, Long.MAX_VALUE, ClipState.KEEP),
            Clip("second", 1L, Long.MAX_VALUE, ClipState.KEEP),
        ))

        assertThrows(ArithmeticException::class.java) {
            timeline.exportedDurationSamples()
        }
    }

    @Test
    fun removedAndRecommendedClipsAreExcludedFromExport() {
        val timeline = Timeline(listOf(
            Clip("keep", 0L, 10L, ClipState.KEEP),
            Clip("recommend", 10L, 20L, ClipState.RECOMMENDED_REMOVE),
            Clip("removed", 20L, 30L, ClipState.REMOVED),
        ))

        assertEquals(listOf(Clip("keep", 0L, 10L)), timeline.keptClips())
        assertEquals(10L, timeline.exportedDurationSamples())
    }

}
