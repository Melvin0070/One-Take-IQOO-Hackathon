package com.example.one_take

import android.Manifest
import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.audio.SpeechPauseDetector
import com.example.one_take.captions.AudioAlignment
import com.example.one_take.captions.AudioDecoder
import com.example.one_take.captions.CaptionJobs
import com.example.one_take.captions.CaptionRepository
import com.example.one_take.editing.EditRepository
import com.example.one_take.editing.LivePauseReconciler
import com.example.one_take.editing.analyzePauseActivity
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.LiveCaptureCoordinator
import com.example.one_take.engine.LiveCaptureStore
import com.example.one_take.engine.recordingTimeline
import com.example.one_take.features.CaptionFeatureState
import com.example.one_take.features.CaptionFeatureStore
import com.onetake.engine.ClockDomain
import com.onetake.engine.PauseCandidate
import com.onetake.engine.SessionPhase
import com.onetake.engine.Silence
import com.onetake.engine.VoiceActivitySource
import java.io.File
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A take with one known 3 s pause yields VAD-sourced Silence signals: a provisional RECOGNIZER copy
 * while recording and one MEDIA copy after finalization, near the pause's true boundaries.
 */
@RunWith(AndroidJUnit4::class)
class LiveSilenceSignalTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context by lazy { instrumentation.targetContext }
    private val videoStore by lazy { VideoStore(context.filesDir) }
    private val captureStore by lazy { LiveCaptureStore(File(context.noBackupFilesDir, "capture_ledgers")) }
    private val coordinator by lazy { LiveCaptureCoordinator.get(context) }
    private val projects by lazy { EngineProjectStore(context) }
    private val jobs by lazy { CaptionJobs.get(context) }
    private val features by lazy { CaptionFeatureStore.get(context) }

    private var originalCaptionEnabled = false
    private var hadInstalledCaptionModel = false
    private val ownedSessions = linkedSetOf<String>()
    private val ownedSources = linkedSetOf<File>()

    @Before
    fun setUp() {
        grantRuntimePermission(Manifest.permission.CAMERA)
        grantRuntimePermission(Manifest.permission.RECORD_AUDIO)
        compose.waitUntil(UI_WAIT_TIMEOUT) { features.state !is CaptionFeatureState.Checking }
        hadInstalledCaptionModel = features.installed
        originalCaptionEnabled = features.enabled
        // Captions off keeps Whisper out of the measurement; pause detection runs either way.
        if (hadInstalledCaptionModel) compose.runOnIdle { features.setFeatureEnabled(false) }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Assisted Mode").performClick()
        compose.waitUntil(UI_WAIT_TIMEOUT) {
            runCatching { compose.onNodeWithContentDescription(START_RECORDING).assertIsEnabled() }.isSuccess
        }
    }

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED) }
        runCatching { instrumentation.runOnMainSync { jobs.cancel() } }
        runCatching { compose.waitUntil(UI_WAIT_TIMEOUT) { !jobs.busy } }
        runCatching { runBlocking { coordinator.flush() } }
        ownedSessions.forEach { session ->
            val deadline = SystemClock.uptimeMillis() + FILE_WAIT_TIMEOUT
            while (SystemClock.uptimeMillis() < deadline && runCatching {
                    captureStore.snapshot(session).phase in setOf(SessionPhase.RECORDING, SessionPhase.FINALIZING)
                }.getOrDefault(false)
            ) SystemClock.sleep(100L)
            runCatching { captureStore.snapshot(session).captureSourceName }.getOrNull()
                ?.let { ownedSources += File(videoStore.directory, it) }
        }
        ownedSources.forEach { source ->
            runCatching { CaptionRepository(context).remove(source) }
            runCatching { EditRepository(context).remove(source) }
            runCatching { com.example.one_take.vision.FaceTrackRepository(context).remove(source) }
            runCatching { projects.remove(source) }
            runCatching { runBlocking { coordinator.remove(source) } }
            runCatching { videoStore.deleteVideo(source) }
            if (source.parentFile == context.cacheDir) source.delete()
        }
        ownedSessions.forEach { File(File(context.noBackupFilesDir, "capture_ledgers"), "$it.ledger").delete() }
        if (hadInstalledCaptionModel) {
            instrumentation.runOnMainSync { features.setFeatureEnabled(originalCaptionEnabled) }
        }
    }

    /** Deterministic PCM through the live detector, coordinator, finalization and confirmation. */
    @Test
    fun fixturePauseProducesOneConfirmedSilenceWithinToleranceOfTrueBoundaries() {
        val pending = videoStore.createPendingRecording()
        val source = pending.outputFile
        ownedSources += source
        instrumentation.context.assets.open(PAUSE_FIXTURE).use { input ->
            source.outputStream().use { input.copyTo(it) }
        }
        pending.complete()
        val session = runBlocking { coordinator.begin(source) }
        ownedSessions += session
        coordinator.started(session)
        runBlocking { coordinator.flush() }

        val pcm = runBlocking { AudioDecoder.decodeMono16k(source) }
        val detected = ArrayList<PauseCandidate>()
        val detector = SpeechPauseDetector.create(context)
        assertEquals("The bundled neural VAD must lead the live chain", VoiceActivitySource.SILERO, detector.source)
        detector.use {
            // AudioRecord hands the microphone 1,024-sample reads; mirror its per-read callback.
            var offset = 0
            while (offset < pcm.size) {
                val end = min(pcm.size, offset + READ_SAMPLES)
                val closed = it.append(pcm.copyOfRange(offset, end))
                if (closed.isNotEmpty()) {
                    detected += closed
                    runBlocking { coordinator.drainPauseCandidates(session, detected.toList(), it.source) }
                }
                offset = end
            }
        }
        // The stop barrier replays the whole snapshot; nothing may be journaled twice.
        coordinator.stop(session, "test_stop")
        runBlocking {
            coordinator.flush()
            coordinator.drainPauseCandidates(session, detected.toList(), VoiceActivitySource.SILERO)
        }

        val live = runBlocking { coordinator.session(session) }.signals<Silence>(ClockDomain.RECOGNIZER)
        assertEquals("One live Silence per pause: $live", 1, live.size)
        assertEquals(detected.single().id, live.single().id)
        assertEquals(VoiceActivitySource.SILERO, live.single().source)

        runBlocking { coordinator.finalized(session, source) }
        val analysis = analyzePauseActivity(source, pcm, context)
        val project = checkNotNull(projects.read(source))
        val offsetMs = AudioAlignment.offsetMs(pcm, pcm)
        assertEquals(0L, offsetMs)
        val edits = LivePauseReconciler.reconcile(project.pauseCandidates, offsetMs, analysis.decision)
        val confirmed = LivePauseReconciler.mediaSilences(edits, project.durationSamples, analysis.source)
        val saved = projects.saveSignals(source, confirmed)
        assertEquals("Replaying confirmation must not duplicate signals", 0, projects.saveSignals(source, confirmed))

        val recorded = checkNotNull(projects.session(source))
        assertEquals(session, recorded.sessionId)
        assertEquals("The live copy survives adoption", live, recorded.signals<Silence>(ClockDomain.RECOGNIZER))
        val media = recorded.signals<Silence>(ClockDomain.MEDIA)
        report("fixture", "live=$live\nmedia=$media\nanalysis=${analysis.decision}\n")
        assertEquals("Exactly one confirmed Silence: $media", 1, media.size)
        assertEquals(1, saved)
        assertEquals(live.single().id, media.single().id)
        assertNearBoundaries(media.single(), PAUSE_START_MS, PAUSE_END_MS)
    }

    /** The same pause played into the real microphone while CameraX records, through CaptionJobs. */
    @Test
    fun realCaptureRecordsLiveAndConfirmedSilenceForAThreeSecondPause() {
        val fixture = File(context.cacheDir, "live-silence-fixture-${SystemClock.elapsedRealtime()}.mp4")
        ownedSources += fixture
        instrumentation.context.assets.open(PAUSE_FIXTURE).use { input ->
            fixture.outputStream().use { input.copyTo(it) }
        }
        val player = MediaPlayer()
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val previousVolume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        try {
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 3 / 4, 0)
            player.setDataSource(fixture.absolutePath)
            player.prepare()

            val before = captureStore.sessions().toSet()
            compose.onNodeWithContentDescription(START_RECORDING).performClick()
            val session = awaitNewStartedSession(before)
            val source = File(videoStore.directory, checkNotNull(captureStore.snapshot(session).captureSourceName))
            ownedSources += source
            compose.waitUntil(UI_WAIT_TIMEOUT) {
                compose.onAllNodesWithContentDescription(STOP_RECORDING).fetchSemanticsNodes().isNotEmpty()
            }
            SystemClock.sleep(500L)
            player.start()
            val deadline = SystemClock.uptimeMillis() + PLAYER_WAIT_TIMEOUT
            while (SystemClock.uptimeMillis() < deadline && player.isPlaying) SystemClock.sleep(100L)
            SystemClock.sleep(600L)
            compose.onNodeWithContentDescription(STOP_RECORDING).performClick()
            compose.waitUntil(UI_WAIT_TIMEOUT) { compose.onAllNodesWithText(REVIEW_VIDEO).fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(EDIT_WAIT_TIMEOUT) {
                !jobs.busy && runCatching { runBlocking { jobs.readEdits(source) } != null }.getOrDefault(false)
            }
            assertNull("Pause job must complete", jobs.error)

            val recorded = checkNotNull(projects.session(source)) { "Capture was not adopted" }
            assertEquals(session, recorded.sessionId)
            val live = recorded.signals<Silence>(ClockDomain.RECOGNIZER)
            val media = recorded.signals<Silence>(ClockDomain.MEDIA)

            val recordedPcm = runBlocking { AudioDecoder.decodeMono16k(source) }
            val fixturePcm = runBlocking { AudioDecoder.decodeMono16k(fixture) }
            val (lagMs, correlation) = envelopeLagMs(recordedPcm, fixturePcm)
            report(
                "real",
                "live=$live\nmedia=$media\nfixtureLagMs=$lagMs\ncorrelation=$correlation\n" +
                    "candidates=${projects.read(source)?.pauseCandidates}\nedits=${projects.read(source)?.edits}\n",
            )
            assertTrue("The live path must journal a VAD Silence: $live", live.isNotEmpty())
            assertTrue("Live Silence must come from Silero: $live", live.all { it.source == VoiceActivitySource.SILERO })
            assertEquals("Exactly one confirmed Silence: $media", 1, media.size)
            assertTrue("Fixture must be locatable in the recording: $correlation", correlation >= MIN_FIXTURE_CORRELATION)
            assertNearBoundaries(media.single(), PAUSE_START_MS + lagMs, PAUSE_END_MS + lagMs)
        } finally {
            player.release()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, previousVolume, 0)
        }
    }

    private fun assertNearBoundaries(silence: Silence, startMs: Long, endMs: Long) {
        val actualStart = recordingTimeline.msFromSamples(silence.startSample)
        val actualEnd = recordingTimeline.msFromSamples(silence.endSample)
        val message = "Silence $actualStart-$actualEnd ms vs true pause $startMs-$endMs ms"
        println("LIVE_SILENCE_BOUNDARIES $message")
        assertTrue(message, abs(actualStart - startMs) <= TOLERANCE_MS)
        assertTrue(message, abs(actualEnd - endMs) <= TOLERANCE_MS)
    }

    /**
     * Where the fixture starts inside the recording, found from 10 ms loudness envelopes. Independent
     * of the production alignment so the ground truth does not share its failure modes.
     */
    private fun envelopeLagMs(recording: FloatArray, fixture: FloatArray): Pair<Long, Double> {
        val rec = envelope(recording)
        val fix = envelope(fixture)
        var bestLag = 0
        var best = Double.NEGATIVE_INFINITY
        for (lag in 0..(rec.size - fix.size).coerceAtLeast(0)) {
            val count = min(fix.size, rec.size - lag)
            val score = correlation(rec, lag, fix, count)
            if (score > best) {
                best = score
                bestLag = lag
            }
        }
        return bestLag * ENVELOPE_MS to best
    }

    private fun envelope(pcm: FloatArray): DoubleArray = DoubleArray(pcm.size / ENVELOPE_SAMPLES) { frame ->
        var sum = 0.0
        for (i in frame * ENVELOPE_SAMPLES until (frame + 1) * ENVELOPE_SAMPLES) sum += pcm[i].toDouble() * pcm[i]
        sqrt(sum / ENVELOPE_SAMPLES)
    }

    private fun correlation(a: DoubleArray, offset: Int, b: DoubleArray, count: Int): Double {
        if (count < 2) return Double.NEGATIVE_INFINITY
        var meanA = 0.0
        var meanB = 0.0
        for (i in 0 until count) {
            meanA += a[offset + i]
            meanB += b[i]
        }
        meanA /= count
        meanB /= count
        var numerator = 0.0
        var energyA = 0.0
        var energyB = 0.0
        for (i in 0 until count) {
            val da = a[offset + i] - meanA
            val db = b[i] - meanB
            numerator += da * db
            energyA += da * da
            energyB += db * db
        }
        val denominator = sqrt(energyA * energyB)
        return if (denominator <= 1e-12) Double.NEGATIVE_INFINITY else numerator / denominator
    }

    private fun awaitNewStartedSession(before: Set<String>): String {
        var result: String? = null
        compose.waitUntil(UI_WAIT_TIMEOUT) {
            result = runCatching {
                captureStore.sessions().firstOrNull { it !in before && captureStore.snapshot(it).captureStarted }
            }.getOrNull()
            result?.let { ownedSessions += it }
            result != null
        }
        return checkNotNull(result) { "Camera did not create a durable capture session" }
    }

    private fun report(name: String, text: String) {
        File(context.cacheDir, "live-silence-$name.txt").writeText(text)
        println("LIVE_SILENCE_$name ${text.replace('\n', ' ')}")
    }

    private fun grantRuntimePermission(permission: String) {
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("pm grant --user 0 ${context.packageName} $permission"),
        ).use { it.readBytes() }
    }

    private companion object {
        const val PAUSE_FIXTURE = "room-pause-test.mp4"
        /** The fixture's documented pause; see VAD_FIXTURES.md. */
        const val PAUSE_START_MS = 2_400L
        const val PAUSE_END_MS = 5_400L
        const val TOLERANCE_MS = 300L
        const val READ_SAMPLES = 1_024
        const val ENVELOPE_MS = 10L
        const val ENVELOPE_SAMPLES = 160
        const val MIN_FIXTURE_CORRELATION = 0.5
        const val START_RECORDING = "Start recording"
        const val STOP_RECORDING = "Stop recording"
        const val REVIEW_VIDEO = "Review video"
        const val UI_WAIT_TIMEOUT = 30_000L
        const val FILE_WAIT_TIMEOUT = 60_000L
        const val EDIT_WAIT_TIMEOUT = 120_000L
        const val PLAYER_WAIT_TIMEOUT = 20_000L
    }
}
