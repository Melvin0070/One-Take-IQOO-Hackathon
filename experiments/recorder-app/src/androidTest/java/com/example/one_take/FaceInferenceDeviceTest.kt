package com.example.one_take

import android.Manifest
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.inference.AppInferenceModel
import com.example.one_take.inference.AppInferenceRuntime
import com.onetake.engine.inference.BackendKind
import com.onetake.engine.inference.CapabilityReason
import com.onetake.engine.inference.ExecutionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FaceInferenceDeviceTest {
    @Test fun cameraPreviewExecutesFaceModelAndReportsActualBackend() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        listOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO).forEach { permission ->
            instrumentation.uiAutomation.grantRuntimePermission(context.packageName, permission)
        }
        val diagnostics = AppInferenceRuntime.diagnostics
        diagnostics.clear()
        ActivityScenario.launch(MainActivity::class.java).use {
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            var report = diagnostics.latest(AppInferenceModel.FACE_LANDMARKER)
            while (report?.status != ExecutionStatus.SUCCEEDED && SystemClock.elapsedRealtime() < deadline) {
                SystemClock.sleep(100L)
                report = diagnostics.latest(AppInferenceModel.FACE_LANDMARKER)
            }
            assertNotNull("The camera preview must run the production face model", report)
            assertEquals(ExecutionStatus.SUCCEEDED, report?.status)
            assertEquals(BackendKind.CPU, report?.actualBackend)
            assertEquals(CapabilityReason.NPU_MODEL_UNAVAILABLE, report?.fallback?.capabilityReason)
        }
    }
}
