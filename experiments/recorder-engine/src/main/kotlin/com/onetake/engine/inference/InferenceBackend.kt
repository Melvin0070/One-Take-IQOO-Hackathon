package com.onetake.engine.inference

/**
 * Adapter for one concrete model execution path.
 *
 * The adapter must report the execution kind it will prepare.
 * The engine verifies that the returned session reports the same kind before
 * making it available to a caller.
 */
interface InferenceBackend<I, O> {
    val backend: BackendKind

    fun prepare(model: ModelSpec): InferenceSession<I, O>
}
