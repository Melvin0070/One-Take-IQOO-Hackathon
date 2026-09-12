package com.onetake.engine.inference

/** The part of the lifecycle represented by an [ExecutionReport]. */
enum class ExecutionPhase {
    PREPARATION,
    EXECUTION,
    SELECTION,
    RELEASE,
}

/** The outcome represented by an [ExecutionReport]. */
enum class ExecutionStatus {
    PREPARED,
    SUCCEEDED,
    FAILED,
}

/** A report-safe failure payload with no exception or media contents. */
data class ExecutionFailure(
    val reason: FailureReason,
    val capabilityReason: CapabilityReason? = null,
)

/**
 * An observable preparation or execution event.
 *
 * Only stable identifiers and timing metadata are retained.
 * Raw input, model data, exception messages, and exception objects are never
 * placed in a report.
 */
data class ExecutionReport(
    val modelId: String,
    val policy: BackendPolicy,
    val actualBackend: BackendKind?,
    val phase: ExecutionPhase,
    val status: ExecutionStatus,
    val startedAtNanos: Long,
    val finishedAtNanos: Long,
    val failure: ExecutionFailure? = null,
    val fallback: ExecutionFailure? = null,
) {
    init {
        require(modelId.isNotBlank()) { "Report model id must not be blank" }
        when (status) {
            ExecutionStatus.FAILED -> require(failure != null) {
                "Failed reports must include a stable failure reason"
            }
            ExecutionStatus.PREPARED,
            ExecutionStatus.SUCCEEDED,
            -> require(failure == null) {
                "Successful reports must not include a failure reason"
            }
        }
    }

    val durationNanos: Long
        get() = (finishedAtNanos - startedAtNanos).coerceAtLeast(0L)
}

/** A monotonic time source injected for deterministic timing tests. */
fun interface MonotonicClock {
    fun nowNanos(): Long

    companion object {
        val SYSTEM: MonotonicClock = MonotonicClock(System::nanoTime)
    }
}

/** Receives immutable preparation and execution reports. */
fun interface InferenceReportListener {
    fun onReport(report: ExecutionReport)
}

/** Thrown when no backend can satisfy a model and policy before execution. */
class InferenceUnavailableException(
    val modelId: String,
    val policy: BackendPolicy,
    val reason: FailureReason,
    val capabilityReason: CapabilityReason? = null,
) : IllegalStateException("No inference backend available for model $modelId under $policy")

/** Thrown when a caller uses a closed or failed session. */
class InferenceSessionStateException(
    val reason: FailureReason,
) : IllegalStateException("Inference session is unavailable: $reason")
