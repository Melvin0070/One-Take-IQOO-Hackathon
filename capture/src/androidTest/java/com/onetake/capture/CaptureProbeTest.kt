package com.onetake.capture

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onetake.engine.SAMPLE_RATE
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Real-device-only concurrency probe. It is opt-in because opening the camera and microphone is
 * disruptive during the normal build gate. Run with `-e requireCaptureProbe true` and grant both
 * runtime permissions to the test target first. Add `-e playTone true` when a deterministic
 * speaker signal is useful for microphone bring-up.
 */
@RunWith(AndroidJUnit4::class)
class CaptureProbeTest {
    @Test
    fun cameraAndAudioWorkInBothStartOrders() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("requireCaptureProbe") == "true")
        val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(
            targetContext.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                targetContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        )

        ActivityScenario.launch(CaptureProbeActivity::class.java).use { scenario ->
            val activity = arrayOfNulls<CaptureProbeActivity>(1)
            scenario.onActivity { activity[0] = it }
            val host = requireNotNull(activity[0])

            val tone = if (arguments.getString("playTone") == "true") {
                TestTone.start()
            } else {
                null
            }
            try {
                val first = ConcurrentCaptureProbe.run(
                    context = targetContext,
                    lifecycleOwner = host,
                    previewView = host.previewView(),
                    cameraFirst = false,
                )
                val second = ConcurrentCaptureProbe.run(
                    context = targetContext,
                    lifecycleOwner = host,
                    previewView = host.previewView(),
                    cameraFirst = true,
                )
                assertTrue("audio-first probe failed: $first", first.architectureBHolds)
                assertTrue("camera-first probe failed: $second", second.architectureBHolds)
                assertTrue(first.audioSamples >= SAMPLE_RATE)
                assertTrue(second.audioSamples >= SAMPLE_RATE)
            } finally {
                tone?.let(TestTone::stopAndRelease)
            }
        }
    }

    /** Plays an opt-in deterministic signal through the phone speaker for microphone bring-up. */
    private object TestTone {
        fun start(): AudioTrack? = runCatching {
            val sampleCount = SAMPLE_RATE * 12
            val pcm = ShortArray(sampleCount) { index ->
                (kotlin.math.sin(2.0 * Math.PI * 440.0 * index / SAMPLE_RATE) * 12_000.0).toInt().toShort()
            }
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SAMPLE_RATE)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { track ->
                    check(track.write(pcm, 0, pcm.size) == pcm.size)
                    track.setLoopPoints(0, pcm.size, -1)
                    track.play()
                }
        }.getOrNull()

        fun stopAndRelease(track: AudioTrack) {
            runCatching { track.stop() }
            track.release()
        }
    }
}
