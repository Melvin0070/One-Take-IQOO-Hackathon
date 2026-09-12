package com.onetake.engine.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimelineTest {
    @Test
    fun timelineCopiesTheCallerClipList() {
        val source = mutableListOf(Clip("source", 0L, 16_000L))
        val timeline = Timeline(source)

        source += Clip("later", 16_000L, 32_000L)

        assertEquals(listOf(Clip("source", 0L, 16_000L)), timeline.clips)
        assertThrows(UnsupportedOperationException::class.java) {
            @Suppress("UNCHECKED_CAST")
            (timeline.clips as MutableList<Clip>) += Clip("mutated", 32_000L, 48_000L)
        }
    }

    @Test
    fun timelineExposesTheInitialOperationSurface() {
        val timeline = Timeline(listOf(Clip("source", 0L, 16_000L)))

        assertEquals(timeline, timeline.trim("source", 0L, 16_000L))
        assertEquals(timeline, timeline.split("source", 0L))
        assertEquals(timeline, timeline.delete("source"))
        assertEquals(timeline, timeline.reorder("source", 0))
        assertEquals(timeline, timeline.restore("source"))
    }
}
