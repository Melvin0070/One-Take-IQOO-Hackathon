package com.onetake.engine.inference

import java.util.Collections

/**
 * Describes one model and the execution kinds that have been validated for it.
 *
 * A runtime adapter is not allowed to infer support from the device alone.
 * The app supplies an adapter only after the model artifact and its execution
 * path have been validated for the target device.
 */
class ModelSpec(
    id: String,
    validatedBackends: Set<BackendKind>,
    unavailableReasons: Map<BackendKind, CapabilityReason> = emptyMap(),
) {
    val id: String = id
    val validatedBackends: Set<BackendKind> = immutableSet(validatedBackends)
    val unavailableReasons: Map<BackendKind, CapabilityReason> =
        Collections.unmodifiableMap(unavailableReasons.toMap())

    init {
        require(id.isNotBlank()) { "Model id must not be blank" }
        require(unavailableReasons.keys.all { it !in this.validatedBackends }) {
            "A validated backend cannot also have an unavailable reason"
        }
    }

    fun isValidated(backend: BackendKind): Boolean = backend in validatedBackends

    fun capabilityReason(backend: BackendKind): CapabilityReason? =
        unavailableReasons[backend]

    fun capability(backend: BackendKind): BackendCapability = BackendCapability(
        backend = backend,
        validated = isValidated(backend),
        unavailableReason = capabilityReason(backend),
    )

    override fun equals(other: Any?): Boolean =
        other is ModelSpec &&
            id == other.id &&
            validatedBackends == other.validatedBackends &&
            unavailableReasons == other.unavailableReasons

    override fun hashCode(): Int =
        31 * (31 * id.hashCode() + validatedBackends.hashCode()) + unavailableReasons.hashCode()

    override fun toString(): String =
        "ModelSpec(id=$id, validatedBackends=$validatedBackends, unavailableReasons=$unavailableReasons)"

    companion object {
        private fun immutableSet(values: Set<BackendKind>): Set<BackendKind> =
            Collections.unmodifiableSet(values.toSet())
    }
}

/** The validated or unavailable status of one model/backend pair. */
data class BackendCapability(
    val backend: BackendKind,
    val validated: Boolean,
    val unavailableReason: CapabilityReason?,
)
