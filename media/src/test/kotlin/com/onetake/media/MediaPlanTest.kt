package com.onetake.media

import com.onetake.engine.EditList
import com.onetake.engine.LineId
import com.onetake.engine.Segment
import com.onetake.engine.TakeId
import com.onetake.engine.VideoAnchor
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MediaPlanTest {

    @Test
    fun planUsesTheAnchorForEachSourceAndBuildsOneEditedTimeline() {
        val first = sourceFile("first")
        val second = sourceFile("second")
        try {
            val firstSegment = segment("source-first", first, inSample = 16_000L, outSample = 32_000L)
            val secondSegment = segment(
                sourceKey = "source-second",
                source = second,
                inSample = 8_000L,
                outSample = 16_000L,
                line = 2,
            )
            val plans = MediaClipPlanner.plan(
                EditList(listOf(firstSegment, secondSegment)),
                listOf(
                    FinalizedVideo("source-first", first, VideoAnchor(0L, 0L, "session-first")),
                    FinalizedVideo("source-second", second, VideoAnchor(8_000L, 3_000_000_000L, "session-second")),
                ),
            )

            assertEquals(2, plans.size)
            assertEquals("session-first", plans[0].source.anchor.sessionId)
            assertEquals(1_000L, plans[0].sourceStartMs)
            assertEquals(2_000L, plans[0].sourceEndMs)
            assertEquals(0L, plans[0].editedStartMs)
            assertEquals("session-second", plans[1].source.anchor.sessionId)
            assertEquals(3_000L, plans[1].sourceStartMs)
            assertEquals(3_500L, plans[1].sourceEndMs)
            assertEquals(1_000L, plans[1].editedStartMs)
        } finally {
            first.delete()
            second.delete()
        }
    }

    @Test
    fun planRejectsAFileThatHasNotBeenFinalized() {
        val missing = File.createTempFile("onetake-missing", ".mp4")
        check(missing.delete())
        try {
            val error = assertThrows(IllegalStateException::class.java) {
                MediaClipPlanner.plan(
                    EditList(listOf(segment("missing", missing, inSample = 0L, outSample = 16_000L))),
                    listOf(FinalizedVideo("missing", missing, VideoAnchor(0L, 0L, "session"))),
                )
            }
            assertEquals(true, error.message?.contains("finalized") == true)
        } finally {
            missing.delete()
        }
    }

    @Test
    fun captionsAreSplitAndRemappedAcrossRetainedClips() {
        val source = sourceFile("captions")
        try {
            val segments = EditList(
                listOf(
                    segment("captions", source, inSample = 0L, outSample = 16_000L),
                    segment("captions", source, inSample = 32_000L, outSample = 48_000L, line = 2),
                ),
            )
            val plans = MediaClipPlanner.plan(
                segments,
                listOf(FinalizedVideo("captions", source, VideoAnchor(0L, 0L, "session"))),
            )
            val mapped = CaptionTimelineMapper.map(
                listOf(CaptionCue("captions", 8_000L, 40_000L, "one caption")),
                plans,
            )

            assertEquals(
                listOf(
                    MappedCaption(500L, 1_000L, "one caption"),
                    MappedCaption(1_000L, 1_500L, "one caption"),
                ),
                mapped,
            )
        } finally {
            source.delete()
        }
    }

    @Test
    fun captionsForAnUnknownSourceAreRejected() {
        val source = sourceFile("video")
        val other = sourceFile("other")
        try {
            val plans = MediaClipPlanner.plan(
                EditList(listOf(segment("video", source, inSample = 0L, outSample = 16_000L))),
                listOf(FinalizedVideo("video", source, VideoAnchor(0L, 0L, "session"))),
            )
            assertThrows(IllegalArgumentException::class.java) {
                CaptionTimelineMapper.map(
                    listOf(CaptionCue("other", 0L, 1_000L, "unknown")),
                    plans,
                )
            }
        } finally {
            source.delete()
            other.delete()
        }
    }

    @Test
    fun overlappingCaptionsForOneSourceAreRejected() {
        val source = sourceFile("overlap")
        try {
            val plans = MediaClipPlanner.plan(
                EditList(listOf(segment("overlap", source, inSample = 0L, outSample = 32_000L))),
                listOf(FinalizedVideo("overlap", source, VideoAnchor(0L, 0L, "session"))),
            )
            assertThrows(IllegalArgumentException::class.java) {
                CaptionTimelineMapper.map(
                    listOf(
                        CaptionCue("overlap", 0L, 16_000L, "first"),
                        CaptionCue("overlap", 8_000L, 24_000L, "overlap"),
                    ),
                    plans,
                )
            }
        } finally {
            source.delete()
        }
    }

    private fun segment(sourceKey: String, source: File, inSample: Long, outSample: Long, line: Int = 1) =
        Segment(
            takeId = TakeId("take-$line"),
            lineId = LineId("line-$line"),
            sourceFile = sourceKey,
            deviceId = "iQOO-15",
            inSample = inSample,
            outSample = outSample,
        )

    private fun sourceFile(label: String): File = Files.createTempFile("onetake-$label", ".mp4").toFile()
        .also { it.writeText("finalized") }
}
