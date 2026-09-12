package com.example.one_take

import com.example.one_take.inference.AppInferenceModel
import com.example.one_take.inference.AppInferenceModelRegistry
import com.example.one_take.inference.AppInferenceSessions
import com.example.one_take.inference.InferenceDiagnostics
import com.onetake.engine.inference.BackendKind
import com.onetake.engine.inference.BackendPolicy
import com.onetake.engine.inference.CapabilityReason
import com.onetake.engine.inference.ExecutionPhase
import com.onetake.engine.inference.ExecutionReport
import com.onetake.engine.inference.ExecutionStatus
import com.onetake.engine.inference.FailureReason
import com.onetake.engine.inference.InferenceSession
import com.onetake.engine.inference.InferenceUnavailableException
import com.onetake.engine.inference.ModelSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AppInferenceIntegrationTest {
    @Test
    fun preferredProductionAdapterReportsCpuFallbackAfterExecution() {
        val diagnostics = InferenceDiagnostics(maxReportsPerModel = 4)
        var prepared = false
        var closed = false
        val session = AppInferenceSessions.open<Int, Int>(
            model = AppInferenceModel.WHISPER_TINY,
            policy = BackendPolicy.NPU_PREFERRED,
            diagnostics = diagnostics,
        ) { model ->
            prepared = true
            object : InferenceSession<Int, Int> {
                override val model: ModelSpec = model
                override val backend: BackendKind = BackendKind.CPU

                override fun execute(input: Int): Int = input + 1

                override fun close() {
                    closed = true
                }
            }
        }

        assertTrue("The CPU adapter must be prepared before execution", prepared)
        assertEquals(4, session.execute(3))
        session.close()

        val reports = diagnostics.recent(AppInferenceModel.WHISPER_TINY)
        assertEquals(listOf(ExecutionStatus.PREPARED, ExecutionStatus.SUCCEEDED), reports.map { it.status })
        assertEquals(BackendKind.CPU, reports.last().actualBackend)
        assertEquals(ExecutionPhase.EXECUTION, reports.last().phase)
        assertTrue("The prepared CPU session must be closed", closed)
        assertEquals(
            CapabilityReason.NPU_MODEL_UNAVAILABLE,
            AppInferenceModelRegistry.whisperTiny.capabilityReason(BackendKind.NPU),
        )
    }

    @Test
    fun requiredNpuRejectsBeforeProductionCpuPreparation() {
        val diagnostics = InferenceDiagnostics()
        var prepared = false

        try {
            AppInferenceSessions.open<Int, Int>(
                model = AppInferenceModel.SILERO_VAD,
                policy = BackendPolicy.NPU_REQUIRED,
                diagnostics = diagnostics,
            ) { model ->
                prepared = true
                object : InferenceSession<Int, Int> {
                    override val model: ModelSpec = model
                    override val backend: BackendKind = BackendKind.CPU
                    override fun execute(input: Int): Int = input
                    override fun close() = Unit
                }
            }
            fail("NPU_REQUIRED must reject before a CPU model is prepared")
        } catch (error: InferenceUnavailableException) {
            assertEquals(FailureReason.NO_VALIDATED_BACKEND, error.reason)
            assertEquals(CapabilityReason.NPU_MODEL_UNAVAILABLE, error.capabilityReason)
        }

        assertFalse(prepared)
        val report = diagnostics.latest(AppInferenceModel.SILERO_VAD)
        assertEquals(ExecutionStatus.FAILED, report?.status)
        assertEquals(CapabilityReason.NPU_MODEL_UNAVAILABLE, report?.failure?.capabilityReason)
    }

    @Test
    fun diagnosticsRemainBoundedWhileRetainingLatestPerModel() {
        val diagnostics = InferenceDiagnostics(maxReportsPerModel = 2, maxTrackedModels = 3)
        diagnostics.onReport(report("model-0"))
        diagnostics.onReport(report("model-1"))
        diagnostics.onReport(report("model-2"))
        diagnostics.onReport(report("model-3"))
        diagnostics.onReport(report("model-3"))
        diagnostics.onReport(report("model-4"))

        assertEquals(3, diagnostics.snapshot().size)
        assertEquals(listOf("model-2", "model-3", "model-4"), diagnostics.snapshot().keys.toList())
        assertEquals(2, diagnostics.recent("model-3").size)
    }

    private fun report(modelId: String): ExecutionReport = ExecutionReport(
        modelId = modelId,
        policy = BackendPolicy.NPU_PREFERRED,
        actualBackend = BackendKind.CPU,
        phase = ExecutionPhase.EXECUTION,
        status = ExecutionStatus.SUCCEEDED,
        startedAtNanos = 1L,
        finishedAtNanos = 2L,
    )
}
