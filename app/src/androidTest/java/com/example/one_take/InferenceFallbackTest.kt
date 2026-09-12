package com.example.one_take

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.audio.SileroSpeechClassifier
import com.example.one_take.captions.WhisperEngine
import com.example.one_take.features.CaptionFeatureStore
import com.example.one_take.inference.AppInferenceModel
import com.example.one_take.inference.InferenceDiagnostics
import com.onetake.engine.inference.BackendKind
import com.onetake.engine.inference.BackendPolicy
import com.onetake.engine.inference.CapabilityReason
import com.onetake.engine.inference.ExecutionStatus
import com.onetake.engine.inference.FailureReason
import com.onetake.engine.inference.InferenceUnavailableException
import com.onetake.engine.SpeechActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InferenceFallbackTest {
    @Test
    fun sileroInferenceReportsValidatedCpuFallback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnostics = InferenceDiagnostics()

        SileroSpeechClassifier.create(context, diagnostics = diagnostics).use { classifier ->
            classifier.classify(FloatArray(SileroSpeechClassifier.FRAME_SAMPLES))
        }

        val report = diagnostics.latest(AppInferenceModel.SILERO_VAD)
        assertNotNull(report)
        assertEquals(ExecutionStatus.SUCCEEDED, report?.status)
        assertEquals(BackendKind.CPU, report?.actualBackend)
        assertEquals(CapabilityReason.NPU_MODEL_UNAVAILABLE, report?.fallback?.capabilityReason)
    }

    @Test
    fun invalidSileroFrameDoesNotCreateAnExecutionReport() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnostics = InferenceDiagnostics()

        SileroSpeechClassifier.create(context, diagnostics = diagnostics).use { classifier ->
            diagnostics.clear()
            assertEquals(SpeechActivity.UNKNOWN, classifier.classify(FloatArray(320)))
        }

        assertEquals(null, diagnostics.latest(AppInferenceModel.SILERO_VAD))
    }

    @Test
    fun strictSileroPolicyRejectsBeforeCpuModelCreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val diagnostics = InferenceDiagnostics()

        try {
            SileroSpeechClassifier.create(
                context,
                policy = BackendPolicy.NPU_REQUIRED,
                diagnostics = diagnostics,
            )
            fail("NPU_REQUIRED must reject the unvalidated Silero artifact")
        } catch (error: InferenceUnavailableException) {
            assertEquals(FailureReason.NO_VALIDATED_BACKEND, error.reason)
            assertEquals(CapabilityReason.NPU_MODEL_UNAVAILABLE, error.capabilityReason)
        }
    }

    @Test
    fun whisperInferenceReportsValidatedCpuFallback() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val feature = CaptionFeatureStore.get(context)
        withTimeout(15_000L) {
            while (feature.state is com.example.one_take.features.CaptionFeatureState.Checking) delay(50L)
        }
        assertTrue("Install the offline caption model before this device suite", feature.installed && feature.modelFile.isFile)
        val diagnostics = InferenceDiagnostics()

        WhisperEngine(diagnostics = diagnostics).withSession(feature.modelFile) { session ->
            session.transcribe(FloatArray(16_000))
        }

        val report = diagnostics.latest(AppInferenceModel.WHISPER_TINY)
        assertNotNull(report)
        assertEquals(ExecutionStatus.SUCCEEDED, report?.status)
        assertEquals(BackendKind.CPU, report?.actualBackend)
        assertEquals(CapabilityReason.NPU_MODEL_UNAVAILABLE, report?.fallback?.capabilityReason)
    }
}
