package com.onetake.engine.timeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TimelineCodecTest {
    @Test fun roundTripPreservesOrderStatesReasonsAndExactLongs() {
        val timeline = Timeline(listOf(
            Clip("third", 9_007_199_254_740_993L, Long.MAX_VALUE, ClipState.KEEP, null),
            Clip("quote\"\\\n😀", 1, 2, ClipState.RECOMMENDED_REMOVE, "pause\t\u0000नमस्ते"),
            Clip("removed", 2, 3, ClipState.REMOVED, "reason"),
        ))
        assertEquals(timeline, TimelineCodec.decode(TimelineCodec.encode(timeline)))
    }

    @Test fun emptyTimelineRoundTrips() {
        assertEquals(Timeline(emptyList()), TimelineCodec.decode("{\"version\":1,\"clips\":[]}"))
    }

    @Test fun acceptsWhitespaceFieldOrderAndUnknownFields() {
        assertEquals(Timeline(emptyList()), TimelineCodec.decode(
            " { \"extra\": {\"flag\":true,\"values\":[null,false,1.2e+3]}, \"clips\": [], \"version\": 1 } \n"
        ))
    }

    @Test fun rejectsMalformedAndUnsupportedDocuments() {
        listOf(
            "", "[]", "{}", "{\"version\":2,\"clips\":[]}",
            "{\"version\":1,\"clips\":[]} trailing",
            "{\"version\":1,\"version\":1,\"clips\":[]}",
            "{\"version\":01,\"clips\":[]}", "{\"version\":1,\"clips\":[,]}",
            "{\"version\":1,\"clips\":[],}",
        ).forEach { payload ->
            assertThrows(payload, IllegalArgumentException::class.java) { TimelineCodec.decode(payload) }
        }
    }

    @Test fun rejectsInvalidClipFieldsAndLossyNumbers() {
        val base = """{"version":1,"clips":[{"id":"a","sourceStart":0,"sourceEnd":10,"state":"KEEP","reason":null}]}"""
        listOf(
            base.replace(":10", ":1.0"), base.replace(":10", ":1e2"),
            base.replace(":10", ":9223372036854775808"),
            base.replace(":10", ":0"), base.replace(":0", ":-1"),
            base.replace("KEEP", "UNKNOWN"), base.replace("null", "42"),
            base.replace(",\"reason\":null", ""), base.replace("\"a\"", "\"\\x\""),
            base.replace("\"a\"", "\"line\nfeed\""),
        ).forEach { payload ->
            assertThrows(payload, IllegalArgumentException::class.java) { TimelineCodec.decode(payload) }
        }
    }

    @Test fun rejectsExcessiveNesting() {
        assertThrows(IllegalArgumentException::class.java) {
            TimelineCodec.decode("[".repeat(100) + "]".repeat(100))
        }
    }
}
