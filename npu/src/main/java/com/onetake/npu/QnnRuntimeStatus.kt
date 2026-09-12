package com.onetake.npu

/** Runtime initialization is capability evidence only, never model execution evidence. */
data class QnnRuntimeStatus(val state: QnnRuntimeState) {
    val modelExecutionVerified: Boolean get() = false

    companion object {
        fun fromNativeCode(code: Int): QnnRuntimeStatus = QnnRuntimeStatus(
            when (code) {
                0 -> QnnRuntimeState.RUNTIME_READY
                1 -> QnnRuntimeState.SDK_NOT_CONFIGURED
                2 -> QnnRuntimeState.LIBRARY_UNAVAILABLE
                3 -> QnnRuntimeState.INTERFACE_UNAVAILABLE
                4 -> QnnRuntimeState.API_INCOMPATIBLE
                5 -> QnnRuntimeState.BACKEND_INITIALIZATION_FAILED
                6 -> QnnRuntimeState.DEVICE_INITIALIZATION_FAILED
                7 -> QnnRuntimeState.RELEASE_FAILED
                else -> QnnRuntimeState.PROBE_FAILED
            }
        )
    }
}

enum class QnnRuntimeState {
    RUNTIME_READY, SDK_NOT_CONFIGURED, LIBRARY_UNAVAILABLE, INTERFACE_UNAVAILABLE,
    API_INCOMPATIBLE, BACKEND_INITIALIZATION_FAILED, DEVICE_INITIALIZATION_FAILED,
    RELEASE_FAILED, WRONG_TARGET, PROBE_FAILED,
}
