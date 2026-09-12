package com.onetake.engine.android

import org.junit.Assert.*
import org.junit.Test

class QnnRuntimeStatusTest {
    @Test fun initializationNeverClaimsModelExecution() {
        val report = QnnRuntimeStatus.fromNativeCode(0)
        assertEquals(QnnRuntimeState.RUNTIME_READY, report.state)
        assertFalse(report.modelExecutionVerified)
    }
    @Test fun missingBuildAndDriverHaveDifferentReasons() {
        assertEquals(QnnRuntimeState.SDK_NOT_CONFIGURED, QnnRuntimeStatus.fromNativeCode(1).state)
        assertEquals(QnnRuntimeState.DEVICE_INITIALIZATION_FAILED, QnnRuntimeStatus.fromNativeCode(6).state)
    }
    @Test fun unknownNativeResultFailsClosed() {
        assertEquals(QnnRuntimeState.PROBE_FAILED, QnnRuntimeStatus.fromNativeCode(999).state)
        assertFalse(QnnRuntimeStatus.fromNativeCode(999).modelExecutionVerified)
    }
}
