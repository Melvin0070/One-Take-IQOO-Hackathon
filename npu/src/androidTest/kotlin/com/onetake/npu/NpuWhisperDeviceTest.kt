package com.onetake.npu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Target-phone smoke test for the installed Tiny bundle.
 *
 * The test is opt-in because a normal build does not contain model weights or
 * a recording. The harness places a raw PCM file in app-private storage and
 * passes its path with `-e npuWhisperPcm`; `-e requireNpuWhisper true` turns a
 * missing fixture into a failure instead of a skip.
 */
@RunWith(AndroidJUnit4::class)
class NpuWhisperDeviceTest {
    @Test
    fun installedTinyBundleExecutesOnHtp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val required = arguments.getString("requireNpuWhisper") == "true"
        val path = arguments.getString("npuWhisperPcm")
        if (path == null) {
            if (required) {
                throw AssertionError("Pass -e npuWhisperPcm <app-private PCM path> when requireNpuWhisper=true")
            }
            assumeTrue("Pass -e npuWhisperPcm <app-private PCM path> to run the device check", false)
            return
        }
        val pcm = File(path)
        if (!pcm.isFile || !pcm.canRead()) {
            if (required) throw AssertionError("PCM fixture is not readable: ${pcm.path}")
            assumeTrue("PCM fixture is not readable: ${pcm.path}", false)
            return
        }

        val context = instrumentation.targetContext
        val transcriber = try {
            NpuWhisperTranscriber.openInstalled(context)
        } catch (failure: Throwable) {
            if (!required) throw failure
            throw AssertionError("No verified Tiny bundle is available to the target app", failure)
        }
        transcriber.use { session ->
            val result = session.transcribeFile(pcm)
            assertEquals(NpuProcessor.NPU, result.diagnostics.processor)
            assertTrue(result.diagnostics.encoderCalls >= 1)
            assertTrue(result.diagnostics.decoderCalls >= 1)
            assertFalse(result.segments.any { it.endSample <= it.startSample })
        }
    }
}
