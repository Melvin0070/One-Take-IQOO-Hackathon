package com.onetake.engine.inference

import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class InferenceFrameworkTest {
    @Test
    fun preferredPolicyChoosesValidatedNpuAndReportsPreparationBeforeExecution() {
        val reports = mutableListOf<ExecutionReport>()
        val clock = FakeClock(10L)
        val session = InferenceEngine<Int, Int>(
            backends = listOf(
                backend(BackendKind.CPU) { input -> input + 1 },
                backend(BackendKind.NPU) { input ->
                    clock.advanceBy(25L)
                    input + 2
                },
            ),
            listener = reports::add,
            clock = clock,
        ).open(model(BackendKind.CPU, BackendKind.NPU))

        assertEquals(BackendKind.NPU, session.backend)
        assertEquals(ExecutionStatus.PREPARED, reports.single().status)
        assertEquals(ExecutionPhase.PREPARATION, reports.single().phase)
        assertEquals(BackendKind.NPU, reports.single().actualBackend)

        assertEquals(5, session.execute(3))
        assertEquals(
            listOf(ExecutionStatus.PREPARED, ExecutionStatus.SUCCEEDED),
            reports.map { it.status },
        )
        assertEquals(ExecutionPhase.EXECUTION, reports[1].phase)
        assertEquals(25L, reports[1].durationNanos)
    }

    @Test
    fun preferredPolicyFallsBackToCpuOnlyDuringPreparationAndReportsReason() {
        val reports = mutableListOf<ExecutionReport>()
        val npuPrepared = AtomicInteger()
        val session = InferenceEngine<Int, Int>(
            backends = listOf(
                backend(BackendKind.NPU, prepare = {
                    npuPrepared.incrementAndGet()
                    error("runtime unavailable")
                }),
                backend(BackendKind.CPU) { input -> input * 2 },
            ),
            listener = reports::add,
        ).open(model(BackendKind.CPU, BackendKind.NPU))

        assertEquals(BackendKind.CPU, session.backend)
        assertEquals(
            FailureReason.BACKEND_PREPARATION_FAILED,
            session.preparationReport.fallback?.reason,
        )
        assertEquals(1, npuPrepared.get())
        assertEquals(
            listOf(ExecutionStatus.FAILED, ExecutionStatus.PREPARED),
            reports.map { it.status },
        )
        assertEquals(BackendKind.NPU, reports[0].actualBackend)
        assertEquals(FailureReason.BACKEND_PREPARATION_FAILED, reports[0].failure?.reason)
        assertEquals(
            FailureReason.BACKEND_PREPARATION_FAILED,
            session.preparationReport.fallback?.reason,
        )
        assertEquals(6, session.execute(3))
        assertEquals(
            FailureReason.BACKEND_PREPARATION_FAILED,
            reports.last().fallback?.reason,
        )
    }

    @Test
    fun preferredPolicyReportsMissingNpuCapabilityOnCpuFallback() {
        val reports = mutableListOf<ExecutionReport>()
        val spec = ModelSpec(
            id = "whisper-tiny",
            validatedBackends = setOf(BackendKind.CPU),
            unavailableReasons = mapOf(
                BackendKind.NPU to CapabilityReason.NPU_MODEL_UNAVAILABLE,
            ),
        )
        val session = InferenceEngine<Int, Int>(
            backends = listOf(backend(BackendKind.CPU) { it * 2 }),
            listener = reports::add,
        ).open(spec)

        assertEquals(BackendKind.CPU, session.backend)
        assertEquals(
            CapabilityReason.NPU_MODEL_UNAVAILABLE,
            session.preparationReport.fallback?.capabilityReason,
        )
        assertEquals(
            CapabilityReason.NPU_MODEL_UNAVAILABLE,
            reports.single().fallback?.capabilityReason,
        )
        assertEquals(6, session.execute(3))
        assertEquals(
            CapabilityReason.NPU_MODEL_UNAVAILABLE,
            reports.last().fallback?.capabilityReason,
        )
    }

    @Test
    fun requiredPolicyRejectsCpuAndMixedAndDoesNotPrepareThem() {
        val prepared = mutableListOf<BackendKind>()
        val reports = mutableListOf<ExecutionReport>()
        val engine = InferenceEngine<Int, Int>(
            backends = listOf(
                backend(BackendKind.MIXED, prepare = { prepared += BackendKind.MIXED }),
                backend(BackendKind.CPU, prepare = { prepared += BackendKind.CPU }),
            ),
            listener = reports::add,
        )

        try {
            engine.open(
                model(BackendKind.CPU, BackendKind.MIXED),
                policy = BackendPolicy.NPU_REQUIRED,
            )
            fail("NPU_REQUIRED must reject non-NPU coverage")
        } catch (error: InferenceUnavailableException) {
            assertEquals(FailureReason.NO_VALIDATED_BACKEND, error.reason)
        }

        assertTrue(prepared.isEmpty())
        assertEquals(ExecutionStatus.FAILED, reports.single().status)
        assertEquals(null, reports.single().actualBackend)
    }

    @Test
    fun missingNpuArtifactPreservesCapabilityReason() {
        val reports = mutableListOf<ExecutionReport>()
        val spec = ModelSpec(
            id = "whisper-tiny",
            validatedBackends = setOf(BackendKind.CPU),
            unavailableReasons = mapOf(
                BackendKind.NPU to CapabilityReason.NPU_MODEL_UNAVAILABLE,
            ),
        )

        try {
            InferenceEngine<Int, Int>(
                backends = emptyList(),
                listener = reports::add,
            ).open(spec, BackendPolicy.NPU_REQUIRED)
            fail("NPU_REQUIRED must fail without a validated artifact")
        } catch (error: InferenceUnavailableException) {
            assertEquals(CapabilityReason.NPU_MODEL_UNAVAILABLE, error.capabilityReason)
        }

        assertEquals(
            CapabilityReason.NPU_MODEL_UNAVAILABLE,
            reports.single().failure?.capabilityReason,
        )
        assertEquals(FailureReason.NO_VALIDATED_BACKEND, reports.single().failure?.reason)
    }

    @Test
    fun cpuOnlyPolicyRejectsMixedEvenWhenMixedIsTheOnlyBackend() {
        val prepared = AtomicInteger()
        try {
            InferenceEngine<Int, Int>(
                backends = listOf(backend(BackendKind.MIXED, prepare = {
                    prepared.incrementAndGet()
                })),
            ).open(model(BackendKind.MIXED), BackendPolicy.CPU_ONLY)
            fail("CPU_ONLY must reject mixed execution")
        } catch (error: InferenceUnavailableException) {
            assertEquals(FailureReason.NO_VALIDATED_BACKEND, error.reason)
        }
        assertEquals(0, prepared.get())
    }

    @Test
    fun actualMixedBackendIsReportedAndAvailableToCaller() {
        val reports = mutableListOf<ExecutionReport>()
        val session = InferenceEngine<Int, Int>(
            backends = listOf(backend(BackendKind.MIXED) { it + 10 }),
            listener = reports::add,
        ).open(model(BackendKind.MIXED))

        assertEquals(BackendKind.MIXED, session.backend)
        assertEquals(BackendKind.MIXED, session.preparationReport.actualBackend)
        assertEquals(11, session.execute(1))
        assertEquals(BackendKind.MIXED, reports.last().actualBackend)
    }

    @Test
    fun backendCoverageMustBeDeclaredBeforePreparation() {
        val prepared = AtomicInteger()
        try {
            InferenceEngine<Int, Int>(
                backends = listOf(backend(BackendKind.NPU, prepare = {
                    prepared.incrementAndGet()
                })),
            ).open(model(BackendKind.CPU))
            fail("undeclared NPU coverage must not be used")
        } catch (error: InferenceUnavailableException) {
            assertEquals(FailureReason.NO_VALIDATED_BACKEND, error.reason)
        }
        assertEquals(0, prepared.get())
    }

    @Test
    fun preparedSessionMustReturnTheExactRequestedModelSpec() {
        val prepared = AtomicInteger()
        val requested = model(BackendKind.CPU, BackendKind.NPU)
        val differentCoverage = model(BackendKind.CPU)
        val backend = object : InferenceBackend<Int, Int> {
            override val backend = BackendKind.NPU

            override fun prepare(model: ModelSpec): InferenceSession<Int, Int> {
                prepared.incrementAndGet()
                return LambdaInferenceSession(differentCoverage, backend, null) { it }
            }
        }

        try {
            InferenceEngine<Int, Int>(listOf(backend)).open(requested)
            fail("a session with different model coverage must be rejected")
        } catch (error: InferenceUnavailableException) {
            assertEquals(FailureReason.BACKEND_MISMATCH, error.reason)
        }
        assertEquals(1, prepared.get())
    }

    @Test
    fun executionFailureIsReportedAndSessionIsPoisonedWithoutCpuReplay() {
        val reports = mutableListOf<ExecutionReport>()
        val npuCalls = AtomicInteger()
        val cpuCalls = AtomicInteger()
        val session = InferenceEngine<Int, Int>(
            backends = listOf(
                backend(BackendKind.NPU) {
                    npuCalls.incrementAndGet()
                    error("stateful failure")
                },
                backend(BackendKind.CPU) {
                    cpuCalls.incrementAndGet()
                    it
                },
            ),
            listener = reports::add,
        ).open(model(BackendKind.CPU, BackendKind.NPU))

        try {
            session.execute(8)
            fail("execution failure must be propagated")
        } catch (error: IllegalStateException) {
            assertEquals("stateful failure", error.message)
        }

        assertEquals(1, npuCalls.get())
        assertEquals(0, cpuCalls.get())
        assertEquals(ExecutionStatus.FAILED, reports.last().status)
        assertEquals(FailureReason.EXECUTION_FAILED, reports.last().failure?.reason)
        assertFalse(reports.last().toString().contains("stateful failure"))

        try {
            session.execute(9)
            fail("failed session must reject subsequent execution")
        } catch (error: IllegalStateException) {
            assertNotNull(error.message)
        }
        assertEquals(1, npuCalls.get())
    }

    @Test
    fun cancellationAndErrorsAreReportedThenRethrown() {
        val cancellation = CancellationException("cancelled")
        val cancellationReports = mutableListOf<ExecutionReport>()
        val cancellationSession = InferenceEngine<Int, Int>(
            backends = listOf(backend(BackendKind.NPU) { throw cancellation }),
            listener = cancellationReports::add,
        ).open(model(BackendKind.NPU))
        try {
            cancellationSession.execute(1)
            fail("cancellation must be propagated")
        } catch (error: CancellationException) {
            assertSame(cancellation, error)
        }
        assertEquals(FailureReason.EXECUTION_FAILED, cancellationReports.last().failure?.reason)

        val fatal = AssertionError("fatal")
        val fatalReports = mutableListOf<ExecutionReport>()
        val fatalSession = InferenceEngine<Int, Int>(
            backends = listOf(backend(BackendKind.NPU) { throw fatal }),
            listener = fatalReports::add,
        ).open(model(BackendKind.NPU))
        try {
            fatalSession.execute(1)
            fail("Error must be propagated")
        } catch (error: AssertionError) {
            assertSame(fatal, error)
        }
        assertEquals(FailureReason.EXECUTION_FAILED, fatalReports.last().failure?.reason)
    }

    @Test
    fun executionIsSerializedAndCloseWaitsForAnInFlightCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val calls = AtomicInteger()
        val session = InferenceEngine<Int, Int>(
            backends = listOf(backend(BackendKind.NPU) {
                calls.incrementAndGet()
                entered.countDown()
                check(release.await(2, TimeUnit.SECONDS))
                it
            }),
        ).open(model(BackendKind.NPU))

        val first = thread(start = true) { session.execute(1) }
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        val closer = thread(start = true) { session.close() }
        Thread.sleep(30L)
        assertTrue(closer.isAlive)
        release.countDown()
        first.join(2_000L)
        closer.join(2_000L)
        assertFalse(first.isAlive)
        assertFalse(closer.isAlive)

        try {
            session.execute(2)
            fail("closed session must reject execution")
        } catch (error: IllegalStateException) {
            assertNotNull(error.message)
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun closeIsIdempotentAndUseAfterCloseIsReported() {
        val reports = mutableListOf<ExecutionReport>()
        val closeCalls = AtomicInteger()
        val session = InferenceEngine<Int, Int>(
            backends = listOf(backend(BackendKind.NPU, close = { closeCalls.incrementAndGet() }) { it }),
            listener = reports::add,
        ).open(model(BackendKind.NPU))

        session.close()
        session.close()
        assertEquals(1, closeCalls.get())
        try {
            session.execute(1)
            fail("closed session must reject execution")
        } catch (error: IllegalStateException) {
            assertNotNull(error.message)
        }
        assertEquals(FailureReason.SESSION_CLOSED, reports.last().failure?.reason)
    }

    @Test
    fun preparationObserverFailureClosesThePreparedSessionBeforeRethrowing() {
        val closeCalls = AtomicInteger()
        val observerFailure = AssertionError("observer")
        try {
            InferenceEngine<Int, Int>(
                backends = listOf(
                    backend(BackendKind.NPU, close = { closeCalls.incrementAndGet() }) { it },
                ),
                listener = InferenceReportListener { throw observerFailure },
            ).open(model(BackendKind.NPU))
            fail("observer failure must be propagated")
        } catch (error: AssertionError) {
            assertSame(observerFailure, error)
        }
        assertEquals(1, closeCalls.get())
    }

    @Test
    fun failedExecutionKeepsTheBackendFailurePrimaryWhenObserverAlsoFails() {
        val backendFailure = IllegalStateException("backend")
        val observerFailure = AssertionError("observer")
        val reports = mutableListOf<ExecutionReport>()
        val session = InferenceEngine<Int, Int>(
            backends = listOf(backend(BackendKind.NPU) { throw backendFailure }),
            listener = InferenceReportListener { report ->
                reports += report
                if (report.status == ExecutionStatus.FAILED) throw observerFailure
            },
        ).open(model(BackendKind.NPU))

        try {
            session.execute(1)
            fail("backend failure must be propagated")
        } catch (error: IllegalStateException) {
            assertSame(backendFailure, error)
            assertTrue(error.suppressed.any { it === observerFailure })
        }
        assertEquals(ExecutionStatus.FAILED, reports.last().status)
    }

    @Test
    fun observerFailureAfterExecutionRetiresSessionWithoutReplayingState() {
        val calls = AtomicInteger()
        val closes = AtomicInteger()
        val observerFailure = AssertionError("observer")
        val session = InferenceEngine<Int, Int>(
            backends = listOf(backend(BackendKind.CPU, close = { closes.incrementAndGet() }) {
                calls.incrementAndGet()
                it + 1
            }),
            listener = InferenceReportListener { report ->
                if (report.status == ExecutionStatus.SUCCEEDED) throw observerFailure
            },
        ).open(model(BackendKind.CPU))
        try {
            session.execute(1)
            fail("observer error must propagate")
        } catch (failure: AssertionError) {
            assertSame(observerFailure, failure)
        }
        assertEquals(1, closes.get())
        try {
            session.execute(1)
            fail("session must not replay an input after the caller observed failure")
        } catch (failure: InferenceSessionStateException) {
            assertEquals(FailureReason.SESSION_FAILED, failure.reason)
        }
        session.close()
        assertEquals(1, calls.get())
        assertEquals(1, closes.get())
    }

    @Test
    fun rejectedBackendCleanupFailureStopsFallback() {
        val releaseFailure = IllegalStateException("release failed")
        val cpuPreparations = AtomicInteger()
        val reports = mutableListOf<ExecutionReport>()
        val wrongBackend = object : InferenceBackend<Int, Int> {
            override val backend = BackendKind.NPU
            override fun prepare(model: ModelSpec): InferenceSession<Int, Int> =
                LambdaInferenceSession(
                    ModelSpec("wrong-model", setOf(BackendKind.NPU)),
                    BackendKind.NPU, { throw releaseFailure }, { it },
                )
        }
        try {
            InferenceEngine(
                backends = listOf(wrongBackend, backend(BackendKind.CPU,
                    prepare = { cpuPreparations.incrementAndGet() })),
                listener = reports::add,
            ).open(model(BackendKind.CPU, BackendKind.NPU))
            fail("Unreleased backend resources must stop fallback")
        } catch (failure: IllegalStateException) {
            assertSame(releaseFailure, failure)
        }
        assertEquals(0, cpuPreparations.get())
        assertEquals("BACKEND_RELEASE_FAILED", reports.last().failure?.reason?.name)
    }

    @Test
    fun strictSelectionDerivesMissingValidationAndMissingAdapterReasons() {
        listOf(
            model(BackendKind.CPU) to CapabilityReason.NOT_VALIDATED,
            model(BackendKind.NPU) to CapabilityReason.BACKEND_UNAVAILABLE,
        ).forEach { (spec, reason) ->
            val reports = mutableListOf<ExecutionReport>()
            try {
                InferenceEngine<Int, Int>(emptyList(), listener = reports::add)
                    .open(spec, BackendPolicy.NPU_REQUIRED)
                fail("Missing NPU support must fail")
            } catch (failure: InferenceUnavailableException) {
                assertEquals(reason, failure.capabilityReason)
                assertEquals(reason, reports.single().failure?.capabilityReason)
            }
        }
    }

    @Test
    fun executionCleanupFailureIsReportedWithoutRiskingASecondNativeFree() {
        val closes = AtomicInteger()
        val original = IllegalStateException("inference failed")
        val cleanup = IllegalStateException("close may have already freed the handle")
        val reports = mutableListOf<ExecutionReport>()
        val session = InferenceEngine<Int, Int>(
            listOf(backend(BackendKind.CPU, close = { closes.incrementAndGet(); throw cleanup }) {
                throw original
            }), listener = reports::add,
        ).open(model(BackendKind.CPU))
        try {
            session.execute(1)
            fail("Inference failed")
        } catch (failure: IllegalStateException) {
            assertSame(original, failure)
            assertTrue(failure.suppressed.contains(cleanup))
        }
        assertEquals("RELEASE", reports.last().phase.name)
        assertEquals("BACKEND_RELEASE_FAILED", reports.last().failure?.reason?.name)
        session.close()
        assertEquals(1, closes.get())
    }

    private fun model(vararg backends: BackendKind): ModelSpec = ModelSpec(
        id = "test-model",
        validatedBackends = backends.toSet(),
    )

    private fun backend(
        kind: BackendKind,
        prepare: (() -> Unit)? = null,
        close: (() -> Unit)? = null,
        operation: (Int) -> Int = { it },
    ): InferenceBackend<Int, Int> = LambdaInferenceBackend(
        backend = kind,
        prepareAction = prepare,
        closeAction = close,
        operation = operation,
    )

    private class LambdaInferenceBackend(
        override val backend: BackendKind,
        private val prepareAction: (() -> Unit)?,
        private val closeAction: (() -> Unit)?,
        private val operation: (Int) -> Int,
    ) : InferenceBackend<Int, Int> {
        override fun prepare(model: ModelSpec): InferenceSession<Int, Int> {
            prepareAction?.invoke()
            return LambdaInferenceSession(model, backend, closeAction, operation)
        }
    }

    private class LambdaInferenceSession(
        override val model: ModelSpec,
        override val backend: BackendKind,
        private val closeAction: (() -> Unit)?,
        private val operation: (Int) -> Int,
    ) : InferenceSession<Int, Int> {
        override lateinit var preparationReport: ExecutionReport

        override fun execute(input: Int): Int = operation(input)

        override fun close() {
            closeAction?.invoke()
        }
    }

    private class FakeClock(initial: Long) : MonotonicClock {
        private var current = initial

        override fun nowNanos(): Long = current

        fun advanceBy(delta: Long) {
            current += delta
        }
    }
}
