package com.onetake.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageGuardTest {
    @Test
    fun aReserveLargerThanFreeSpaceStopsBeforeWriting() {
        val guard = StorageGuard(
            directory = File(System.getProperty("java.io.tmpdir").orEmpty()),
            videoBitRateBitsPerSecond = 8_000_000L,
            reserveBytes = Long.MAX_VALUE,
        )

        assertTrue(guard.shouldStop(0L))
        assertEquals(0, guard.estimatedRecordableMinutes())
    }

    @Test
    fun unknownBitrateDoesNotInventRecordableMinutes() {
        val guard = StorageGuard(
            directory = File(System.getProperty("java.io.tmpdir").orEmpty()),
            videoBitRateBitsPerSecond = null,
            reserveBytes = 0L,
        )

        assertEquals(0, guard.estimatedRecordableMinutes())
    }
}
