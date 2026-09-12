package com.example.one_take

import android.os.Build
import org.junit.Assume.assumeTrue
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.onetake.engine.android.QnnRuntimeProbe
import com.onetake.engine.android.QnnRuntimeState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QnnRuntimeProbeTest {
    @Test fun qnnProbeReportsCapabilityWithoutClaimingInference() {
        assumeTrue("Requires the target iQOO 15", Build.VERSION.SDK_INT >= 31 &&
            Build.MODEL == "I2501" && Build.SOC_MODEL == "SM8850")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // SDK builds must initialize HTP on the target phone; ordinary builds
        // must explicitly report that the SDK was not configured.
        val expected = if (InstrumentationRegistry.getArguments().getString("requireQnn") == "true") {
            QnnRuntimeState.RUNTIME_READY
        } else QnnRuntimeState.SDK_NOT_CONFIGURED
        repeat(3) {
            val report = QnnRuntimeProbe.inspect(instrumentation.targetContext)
            android.util.Log.i("OneTakeInference", "QNN runtime probe: ${report.state}")
            assertFalse(report.modelExecutionVerified)
            assertEquals(expected, report.state)
        }
    }
}
