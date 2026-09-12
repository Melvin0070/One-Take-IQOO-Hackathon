package com.example.one_take.analysis

import com.onetake.engine.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class RecordingAnalysisHandoffTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val source = File("take.mp4")
    private val session = RecordingSessionReader.read(listOf(
        Event(1, "take", 0, Change.CaptureRequested("take.mp4", SessionMode.ASSISTED), ClockDomain.CAPTURE_ESTIMATE)))
    private val written = CopyOnWriteArrayList<AnalysisResult>()
    private val failures = CopyOnWriteArrayList<Throwable>()

    @After fun cancel() = scope.cancel()

    private fun handoff(
        read: (File) -> RecordingSessionSnapshot? = { session },
        timeoutMs: Long = 5_000,
        analyze: (RecordingSessionSnapshot, Long?) -> AnalysisResult,
    ) = RecordingAnalysisHandoff(read, { written += it }, scope, analyze, timeoutMs, { failures += it })

    @Test fun analysesTheAdoptedSessionAndStoresTheResult() = runBlocking {
        var offset: Long? = -1
        val result = handoff { snapshot, liveToMedia ->
            offset = liveToMedia
            AnalysisResult(snapshot.sessionId, ClockDomain.MEDIA, emptyList(), emptyList())
        }.run(source, 800)
        assertEquals(AnalysisResult("take", ClockDomain.MEDIA, emptyList(), emptyList()), result)
        assertEquals(listOf(result), written)
        assertEquals(800L, offset)
    }

    @Test fun legacyRecordingsWithoutASessionHeaderSkipAnalysis() = runBlocking {
        assertNull(handoff(read = { null }) { _, _ -> error("not analysed") }.run(source))
        assertTrue(written.isEmpty() && failures.isEmpty())
    }

    @Test fun aFailingAnalysisIsReportedAndDoesNotBlockTheHandoff() = runBlocking {
        assertNull(handoff { _, _ -> throw IllegalStateException("analysis bug") }.run(source))
        assertEquals("analysis bug", failures.single().message)
        assertTrue(written.isEmpty())
    }

    @Test fun aSlowAnalysisReleasesTheHandoffButStillStoresItsResult() {
        val release = CountDownLatch(1)
        val stored = CountDownLatch(1)
        val slow = RecordingAnalysisHandoff({ session }, { written += it; stored.countDown() }, scope,
            { snapshot, _ -> release.await(); SessionAnalysis.analyze(snapshot) }, timeoutMs = 50, onFailure = { failures += it })
        assertNull(runBlocking { slow.run(source) })
        release.countDown()
        assertTrue(stored.await(5, TimeUnit.SECONDS))
        assertEquals("take", written.single().sessionId)
    }
}
