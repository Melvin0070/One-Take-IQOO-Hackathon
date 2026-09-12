package com.onetake.engine.inference

/**
 * A synchronous model session.
 *
 * Sessions returned by [InferenceEngine.open] serialize execution and close
 * the underlying adapter after an execution failure so stateful adapters are
 * never replayed automatically.
 * The engine attempts delegate close at most once, including when it throws:
 * a close exception cannot tell us whether a native handle was already freed.
 * Adapters must release partial allocations when prepare fails and make their
 * close implementation release all owned resources even if one release fails.
 * Release failures are reported and prevent preparation fallback.
 */
interface InferenceSession<I, O> : AutoCloseable {
    val model: ModelSpec
    val backend: BackendKind

    /**
     * The preparation report for sessions created by [InferenceEngine].
     *
     * Backend adapters do not need to provide this value themselves.
     */
    val preparationReport: ExecutionReport
        get() = error("Preparation report is available on an engine-created session")

    fun execute(input: I): O
}
