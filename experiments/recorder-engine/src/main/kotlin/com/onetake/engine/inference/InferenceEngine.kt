package com.onetake.engine.inference

import java.util.concurrent.CancellationException

/**
 * Selects validated model adapters and wraps them in serialized sessions.
 *
 * The default policy prefers NPU execution and falls back to CPU only while a
 * session is being prepared.
 */
class InferenceEngine<I, O>(
    backends: Iterable<InferenceBackend<I, O>>,
    val defaultPolicy: BackendPolicy = BackendPolicy.NPU_PREFERRED,
    private val listener: InferenceReportListener? = null,
    private val clock: MonotonicClock = MonotonicClock.SYSTEM,
) {
    private val backends: List<InferenceBackend<I, O>> = backends.toList()

    fun open(
        model: ModelSpec,
        policy: BackendPolicy = defaultPolicy,
    ): InferenceSession<I, O> {
        var attempted = false
        var preparationFailure: FailureReason? = null
        var preferredFallback: ExecutionFailure? = null

        candidateBackends(policy).forEach { candidateKind ->
            if (!model.isValidated(candidateKind)) {
                if (policy == BackendPolicy.NPU_PREFERRED &&
                    candidateKind == BackendKind.NPU &&
                    preferredFallback == null
                ) {
                    preferredFallback = unavailableNpuFallback(model)
                }
                return@forEach
            }

            val matchingBackends = backends
                .asSequence()
                .filter { it.backend == candidateKind }
                .toList()
            if (matchingBackends.isEmpty() &&
                policy == BackendPolicy.NPU_PREFERRED &&
                candidateKind == BackendKind.NPU &&
                preferredFallback == null
            ) {
                preferredFallback = unavailableNpuFallback(model)
            }

            matchingBackends.forEach backendLoop@{ candidate ->
                attempted = true
                val startedAt = clock.nowNanos()
                val prepared = try {
                    candidate.prepare(model)
                } catch (failure: Throwable) {
                    preparationFailure = FailureReason.BACKEND_PREPARATION_FAILED
                    val preparationReport = report(
                        modelId = model.id,
                        policy = policy,
                        actualBackend = candidate.backend,
                        phase = ExecutionPhase.PREPARATION,
                        status = ExecutionStatus.FAILED,
                        startedAtNanos = startedAt,
                        failure = ExecutionFailure(
                            reason = FailureReason.BACKEND_PREPARATION_FAILED,
                        ),
                    )
                    if (candidateKind != BackendKind.CPU && preferredFallback == null) {
                        preferredFallback = preparationReport.failure
                    }
                    val observerFailure = emit(preparationReport)
                    if (observerFailure != null) {
                        if (failure is CancellationException || failure is Error) {
                            if (observerFailure !== failure) failure.addSuppressed(observerFailure)
                            throw failure
                        }
                        throw observerFailure
                    }
                    if (failure is CancellationException || failure is Error) throw failure
                    return@backendLoop
                }

                val mismatchReason = validatePreparedSession(
                    model = model,
                    policy = policy,
                    candidate = candidate,
                    prepared = prepared,
                )
                if (mismatchReason != null) {
                    closeRejectedSession(prepared, model, policy)
                    preparationFailure = mismatchReason
                    val mismatchReport = report(
                        modelId = model.id,
                        policy = policy,
                        actualBackend = prepared.backend,
                        phase = ExecutionPhase.PREPARATION,
                        status = ExecutionStatus.FAILED,
                        startedAtNanos = startedAt,
                        failure = ExecutionFailure(reason = mismatchReason),
                    )
                    if (candidateKind != BackendKind.CPU && preferredFallback == null) {
                        preferredFallback = mismatchReport.failure
                    }
                    val observerFailure = emit(mismatchReport)
                    if (observerFailure != null) throw observerFailure
                    return@backendLoop
                }

                val preparationReport = report(
                    modelId = model.id,
                    policy = policy,
                    actualBackend = prepared.backend,
                    phase = ExecutionPhase.PREPARATION,
                    status = ExecutionStatus.PREPARED,
                    startedAtNanos = startedAt,
                    fallback = if (prepared.backend == BackendKind.NPU) {
                        null
                    } else {
                        preferredFallback
                    },
                )
                val observerFailure = emit(preparationReport)
                if (observerFailure != null) {
                    closePreparedSession(prepared, model, policy, observerFailure)
                    throw observerFailure
                }
                return ManagedInferenceSession(
                    model = model,
                    backend = prepared.backend,
                    preparationReport = preparationReport,
                    delegate = prepared,
                    fallback = preparationReport.fallback,
                    listener = listener,
                    clock = clock,
                )
            }
        }

        if (attempted && preparationFailure != null) {
            throw InferenceUnavailableException(
                modelId = model.id,
                policy = policy,
                reason = preparationFailure!!,
            )
        }

        val capabilityReason = selectionCapabilityReason(model, policy)
        val selectionReport = report(
            modelId = model.id,
            policy = policy,
            actualBackend = null,
            phase = ExecutionPhase.SELECTION,
            status = ExecutionStatus.FAILED,
            startedAtNanos = clock.nowNanos(),
            failure = ExecutionFailure(
                reason = FailureReason.NO_VALIDATED_BACKEND,
                capabilityReason = capabilityReason,
            ),
        )
        val observerFailure = emit(selectionReport)
        if (observerFailure != null) throw observerFailure
        throw InferenceUnavailableException(
            modelId = model.id,
            policy = policy,
            reason = FailureReason.NO_VALIDATED_BACKEND,
            capabilityReason = capabilityReason,
        )
    }

    private fun candidateBackends(policy: BackendPolicy): List<BackendKind> = when (policy) {
        BackendPolicy.NPU_PREFERRED -> listOf(
            BackendKind.NPU,
            BackendKind.MIXED,
            BackendKind.CPU,
        )
        BackendPolicy.NPU_REQUIRED -> listOf(BackendKind.NPU)
        BackendPolicy.CPU_ONLY -> listOf(BackendKind.CPU)
    }

    private fun validatePreparedSession(
        model: ModelSpec,
        policy: BackendPolicy,
        candidate: InferenceBackend<I, O>,
        prepared: InferenceSession<I, O>,
    ): FailureReason? {
        if (prepared.model != model || prepared.backend != candidate.backend) {
            return FailureReason.BACKEND_MISMATCH
        }
        if (!model.isValidated(prepared.backend) || !policy.accepts(prepared.backend)) {
            return FailureReason.BACKEND_MISMATCH
        }
        return null
    }

    private fun selectionCapabilityReason(
        model: ModelSpec,
        policy: BackendPolicy,
    ): CapabilityReason {
        val requested = candidateBackends(policy).first()
        return model.capabilityReason(requested) ?: if (model.isValidated(requested)) {
            CapabilityReason.BACKEND_UNAVAILABLE
        } else CapabilityReason.NOT_VALIDATED
    }

    private fun unavailableNpuFallback(model: ModelSpec): ExecutionFailure = ExecutionFailure(
        reason = FailureReason.NO_VALIDATED_BACKEND,
        capabilityReason = model.capabilityReason(BackendKind.NPU)
            ?: if (model.isValidated(BackendKind.NPU)) {
                CapabilityReason.BACKEND_UNAVAILABLE
            } else {
                CapabilityReason.NOT_VALIDATED
            },
    )

    private fun report(
        modelId: String,
        policy: BackendPolicy,
        actualBackend: BackendKind?,
        phase: ExecutionPhase,
        status: ExecutionStatus,
        startedAtNanos: Long,
        failure: ExecutionFailure? = null,
        fallback: ExecutionFailure? = null,
    ): ExecutionReport = ExecutionReport(
        modelId = modelId,
        policy = policy,
        actualBackend = actualBackend,
        phase = phase,
        status = status,
        startedAtNanos = startedAtNanos,
        finishedAtNanos = clock.nowNanos(),
        failure = failure,
        fallback = fallback,
    )

    private fun emit(report: ExecutionReport): Throwable? = try {
        listener?.onReport(report)
        null
    } catch (failure: Throwable) {
        failure
    }

    private fun closePreparedSession(
        session: InferenceSession<I, O>,
        model: ModelSpec,
        policy: BackendPolicy,
        primary: Throwable,
    ) {
        try {
            closeRejectedSession(session, model, policy)
        } catch (closeFailure: Throwable) {
            if (closeFailure !== primary) primary.addSuppressed(closeFailure)
        }
    }

    private fun closeRejectedSession(
        session: InferenceSession<I, O>,
        model: ModelSpec,
        policy: BackendPolicy,
    ) {
        val startedAt = clock.nowNanos()
        try {
            session.close()
        } catch (failure: Throwable) {
            val observerFailure = emit(report(
                modelId = model.id,
                policy = policy,
                actualBackend = session.backend,
                phase = ExecutionPhase.RELEASE,
                status = ExecutionStatus.FAILED,
                startedAtNanos = startedAt,
                failure = ExecutionFailure(FailureReason.BACKEND_RELEASE_FAILED),
            ))
            if (observerFailure != null && observerFailure !== failure) failure.addSuppressed(observerFailure)
            throw failure
        }
    }
}

private class ManagedInferenceSession<I, O>(
    override val model: ModelSpec,
    override val backend: BackendKind,
    override val preparationReport: ExecutionReport,
    private val delegate: InferenceSession<I, O>,
    private val fallback: ExecutionFailure?,
    private val listener: InferenceReportListener?,
    private val clock: MonotonicClock,
) : InferenceSession<I, O> {
    private enum class State {
        OPEN,
        FAILED,
        CLOSED,
    }

    private val lock = Any()
    private var state = State.OPEN

    override fun execute(input: I): O = synchronized(lock) {
        when (state) {
            State.OPEN -> executeOpen(input)
            State.FAILED -> reject(FailureReason.SESSION_FAILED)
            State.CLOSED -> reject(FailureReason.SESSION_CLOSED)
        }
    }

    private fun executeOpen(input: I): O {
        val startedAt = clock.nowNanos()
        val output = try {
            delegate.execute(input)
        } catch (failure: Throwable) {
            state = State.FAILED
            val observerFailure = emit(
                report(
                    phase = ExecutionPhase.EXECUTION,
                    status = ExecutionStatus.FAILED,
                    startedAtNanos = startedAt,
                    failure = ExecutionFailure(reason = FailureReason.EXECUTION_FAILED),
                ),
            )
            closeAfterFailure(failure)
            if (observerFailure != null && observerFailure !== failure) {
                failure.addSuppressed(observerFailure)
            }
            throw failure
        }

        val observerFailure = emit(
            report(
                phase = ExecutionPhase.EXECUTION,
                status = ExecutionStatus.SUCCEEDED,
                startedAtNanos = startedAt,
            ),
        )
        if (observerFailure != null) {
            // The model already consumed this input, but its caller received
            // an exception. Retire the session so retrying cannot replay state.
            state = State.FAILED
            closeAfterFailure(observerFailure)
            throw observerFailure
        }
        return output
    }

    private fun reject(reason: FailureReason): Nothing {
        val observerFailure = emit(
            report(
                phase = ExecutionPhase.EXECUTION,
                status = ExecutionStatus.FAILED,
                startedAtNanos = clock.nowNanos(),
                failure = ExecutionFailure(reason = reason),
            ),
        )
        val stateFailure = InferenceSessionStateException(reason)
        if (observerFailure != null && observerFailure !== stateFailure) {
            stateFailure.addSuppressed(observerFailure)
        }
        throw stateFailure
    }

    override fun close() = synchronized(lock) {
        if (state == State.OPEN) {
            state = State.CLOSED
            val startedAt = clock.nowNanos()
            try {
                delegate.close()
            } catch (failure: Throwable) {
                reportReleaseFailure(failure, startedAt)
                throw failure
            }
        }
    }

    private fun closeAfterFailure(original: Throwable) {
        val startedAt = clock.nowNanos()
        try {
            delegate.close()
        } catch (closeFailure: Throwable) {
            reportReleaseFailure(closeFailure, startedAt)
            if (closeFailure !== original) original.addSuppressed(closeFailure)
        }
    }

    private fun reportReleaseFailure(failure: Throwable, startedAt: Long) {
        val observerFailure = emit(report(
            phase = ExecutionPhase.RELEASE,
            status = ExecutionStatus.FAILED,
            startedAtNanos = startedAt,
            failure = ExecutionFailure(FailureReason.BACKEND_RELEASE_FAILED),
        ))
        if (observerFailure != null && observerFailure !== failure) failure.addSuppressed(observerFailure)
    }

    private fun report(
        phase: ExecutionPhase,
        status: ExecutionStatus,
        startedAtNanos: Long,
        failure: ExecutionFailure? = null,
    ): ExecutionReport = ExecutionReport(
        modelId = model.id,
        policy = preparationReport.policy,
        actualBackend = backend,
        phase = phase,
        status = status,
        startedAtNanos = startedAtNanos,
        finishedAtNanos = clock.nowNanos(),
        failure = failure,
        fallback = fallback,
    )

    private fun emit(report: ExecutionReport): Throwable? = try {
        listener?.onReport(report)
        null
    } catch (failure: Throwable) {
        failure
    }
}

private fun BackendPolicy.accepts(backend: BackendKind): Boolean = when (this) {
    BackendPolicy.NPU_PREFERRED -> true
    BackendPolicy.NPU_REQUIRED -> backend == BackendKind.NPU
    BackendPolicy.CPU_ONLY -> backend == BackendKind.CPU
}
