package com.onetake.engine.inference

/** The execution kind that an adapter actually performs. */
enum class BackendKind {
    CPU,
    NPU,
    MIXED,
}

/** The backend selection policy for a model session. */
enum class BackendPolicy {
    /** Prefer an NPU or mixed adapter and recover to a validated CPU adapter. */
    NPU_PREFERRED,

    /** Require a pure NPU adapter.  CPU and mixed adapters are rejected. */
    NPU_REQUIRED,

    /** Require a pure CPU adapter. */
    CPU_ONLY,
}

/** A stable explanation for a backend capability that has not been supplied. */
enum class CapabilityReason {
    NPU_MODEL_UNAVAILABLE,
    CPU_MODEL_UNAVAILABLE,
    BACKEND_UNAVAILABLE,
    NOT_VALIDATED,
}

/** A stable explanation recorded in an execution report. */
enum class FailureReason {
    NO_VALIDATED_BACKEND,
    BACKEND_PREPARATION_FAILED,
    BACKEND_RELEASE_FAILED,
    BACKEND_MISMATCH,
    EXECUTION_FAILED,
    SESSION_CLOSED,
    SESSION_FAILED,
}
