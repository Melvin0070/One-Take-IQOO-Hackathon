package com.example.one_take

import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.viewinterop.AndroidView
import androidx.test.platform.app.InstrumentationRegistry
import com.example.one_take.engine.EngineProjectStore
import com.example.one_take.engine.LiveCaptureCoordinator
import com.example.one_take.vision.FaceTrackRepository
import com.onetake.engine.Change
import com.onetake.engine.SessionPhase
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CameraRecorderStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun startupIsSynchronousAndDuplicateStartIsRejected() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "pm grant --user 0 ${instrumentation.targetContext.packageName} $permission")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val ready = AtomicBoolean(false)
        val finalized = AtomicBoolean(false)
        val recordingError = AtomicReference<String?>(null)
        val cleanupError = AtomicReference<String?>(null)
        val pendingSource = AtomicReference<File?>(null)
        val appContext = instrumentation.targetContext.applicationContext
        lateinit var recorder: CameraRecorder

        fun cleanup(source: File?) {
            if (source == null) {
                cleanupError.set("The test could not identify its pending source")
                return
            }
            try {
                runBlocking {
                    LiveCaptureCoordinator.get(appContext).remove(source)
                    EngineProjectStore(appContext).remove(source)
                }
                check(FaceTrackRepository(appContext).remove(source)) {
                    "Unable to remove face metadata for ${source.name}"
                }
                check(recorder.videoStore.deleteVideo(source)) {
                    "Unable to remove test recording ${source.name}"
                }
            } catch (exception: Exception) {
                cleanupError.set(exception.message ?: exception::class.java.simpleName)
            }
        }

        compose.setContent {
            val context = LocalContext.current
            val owner = LocalLifecycleOwner.current
            recorder = remember { CameraRecorder(context.applicationContext) }
            val preview = remember { PreviewView(context) }
            DisposableEffect(recorder) {
                recorder.setListener(object : CameraRecorder.Listener {
                    override fun onCameraReady() { ready.set(true) }
                    override fun onCameraError(message: String) { error(message) }
                    override fun onRecordingError(message: String) {
                        recordingError.set(message)
                        cleanup(pendingSource.get())
                    }
                    override fun onRecordingFinalized(file: File) {
                        cleanup(file)
                        finalized.set(true)
                    }
                })
                recorder.bindCamera(preview, owner, CameraSelector.LENS_FACING_BACK)
                onDispose { recorder.release() }
            }
            AndroidView(factory = { preview })
        }
        compose.waitUntil(20_000) { ready.get() }
        compose.runOnIdle {
            assertTrue(recorder.supportedQualities.isNotEmpty())
            assertTrue(recorder.selectedQuality in recorder.supportedQualities)
            assertTrue(recorder.minZoomRatio > 0f)
            assertTrue(recorder.maxZoomRatio >= recorder.minZoomRatio)
            assertTrue(recorder.setZoomRatio(recorder.maxZoomRatio + 1f))
            assertEquals(recorder.maxZoomRatio, recorder.zoomRatio)
            assertFalse(recorder.setZoomRatio(Float.NaN))

            val existingEntries = recorder.videoStore.directory.listFiles()
                .orEmpty()
                .map { file -> file.name }
                .toSet()
            assertTrue(recorder.startRecording())
            assertEquals(CaptureUiState.Starting, recorder.captureState)
            assertFalse(recorder.startRecording())
            val pendingMarker = recorder.videoStore.directory.listFiles()
                .orEmpty()
                .firstOrNull { file ->
                    file.name !in existingEntries &&
                    file.name.startsWith(".video_") &&
                        file.name.endsWith(".mp4.pending")
                }
            pendingSource.set(
                pendingMarker?.let { marker ->
                    File(
                        recorder.videoStore.directory,
                        marker.name.removePrefix(".").removeSuffix(".pending")
                    )
                }
            )
        }
        compose.waitUntil(20_000) { recorder.captureState == CaptureUiState.Recording }
        Thread.sleep(1_000)
        compose.runOnIdle {
            recorder.stopRecording()
            assertEquals(CaptureUiState.Finalizing, recorder.captureState)
        }
        compose.waitUntil(20_000) { finalized.get() || recordingError.get() != null }
        compose.runOnIdle {
            assertNull("A normal capture should not report an error", recordingError.get())
            assertNull("Test-owned metadata cleanup failed", cleanupError.get())
            assertEquals(CaptureUiState.Idle, recorder.captureState)
        }
    }

    @Test fun immediateStopBeforeCameraStartCancelsCapture() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        for (permission in listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO")) {
            val descriptor = instrumentation.uiAutomation.executeShellCommand(
                "pm grant --user 0 ${instrumentation.targetContext.packageName} $permission")
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes() }
        }
        val ready = AtomicBoolean(false)
        val finalized = AtomicBoolean(false)
        val recordingError = AtomicReference<String?>(null)
        val cleanupError = AtomicReference<String?>(null)
        val pendingSource = AtomicReference<File?>(null)
        val captureLedger = AtomicReference<File?>(null)
        val existingLedgers = AtomicReference<Set<String>>(emptySet())
        val appContext = instrumentation.targetContext.applicationContext
        lateinit var recorder: CameraRecorder

        fun cleanup(source: File?) {
            if (source == null) {
                cleanupError.set("The test could not identify its pending source")
                return
            }
            try {
                runBlocking {
                    LiveCaptureCoordinator.get(appContext).remove(source)
                    EngineProjectStore(appContext).remove(source)
                }
                check(FaceTrackRepository(appContext).remove(source)) {
                    "Unable to remove face metadata for ${source.name}"
                }
                check(recorder.videoStore.deleteVideo(source)) {
                    "Unable to remove test recording ${source.name}"
                }
            } catch (exception: Exception) {
                cleanupError.set(exception.message ?: exception::class.java.simpleName)
            }
        }

        compose.setContent {
            val context = LocalContext.current
            val owner = LocalLifecycleOwner.current
            recorder = remember { CameraRecorder(context.applicationContext) }
            val preview = remember { PreviewView(context) }
            DisposableEffect(recorder) {
                recorder.setListener(object : CameraRecorder.Listener {
                    override fun onCameraReady() { ready.set(true) }
                    override fun onCameraError(message: String) { error(message) }
                    override fun onRecordingError(message: String) { recordingError.set(message) }
                    override fun onRecordingFinalized(file: File) {
                        finalized.set(true)
                        cleanup(file)
                    }
                })
                recorder.bindCamera(preview, owner, CameraSelector.LENS_FACING_BACK)
                onDispose { recorder.release() }
            }
            AndroidView(factory = { preview })
        }

        compose.waitUntil(20_000) { ready.get() }
        compose.runOnIdle {
            val existingEntries = recorder.videoStore.directory.listFiles()
                .orEmpty()
                .map { file -> file.name }
                .toSet()
            val ledgerDirectory = File(appContext.noBackupFilesDir, "capture_ledgers")
            existingLedgers.set(ledgerDirectory.listFiles().orEmpty().map { file -> file.name }.toSet())
            assertTrue(recorder.startRecording())
            assertEquals(CaptureUiState.Starting, recorder.captureState)
            // Keep start and stop in one main-thread turn. The coordinator's
            // durable begin must finish before a later turn can start CameraX.
            recorder.stopRecording()
            assertEquals(CaptureUiState.Finalizing, recorder.captureState)
            val pendingMarker = recorder.videoStore.directory.listFiles()
                .orEmpty()
                .firstOrNull { file ->
                    file.name !in existingEntries &&
                        file.name.startsWith(".video_") &&
                        file.name.endsWith(".mp4.pending")
                }
            pendingSource.set(
                pendingMarker?.let { marker ->
                    File(
                        recorder.videoStore.directory,
                        marker.name.removePrefix(".").removeSuffix(".pending")
                    )
                }
            )
        }

        compose.waitUntil(20_000) {
            val ledgerDirectory = File(appContext.noBackupFilesDir, "capture_ledgers")
            val ledger = ledgerDirectory.listFiles()
                .orEmpty()
                .firstOrNull { file ->
                    file.extension == "ledger" && file.name !in existingLedgers.get()
                }
            if (ledger != null) {
                captureLedger.compareAndSet(null, ledger)
            }
            recorder.captureState == CaptureUiState.Idle && recordingError.get() != null
        }

        val source = pendingSource.get()
        try {
            val ledger = captureLedger.get()
            assertNotNull("The immediate-stop test did not create a capture ledger", ledger)
            assertNotNull("The immediate-stop test did not identify its pending source", source)
            val session = ledger!!.name.removeSuffix(".ledger")
            runBlocking {
                LiveCaptureCoordinator.get(appContext).flush()
                val history = LiveCaptureCoordinator.get(appContext).history(session)
                val snapshot = LiveCaptureCoordinator.get(appContext).snapshot(session)
                assertEquals(SessionPhase.CANCELLED, snapshot.phase)
                assertFalse(history.any { it.change is Change.CaptureStarted })
            }
            assertFalse("Immediate stop must not leave a raw output", source!!.exists())
            assertFalse("Immediate stop must not leave a pending marker", File(
                recorder.videoStore.directory,
                ".${source.name}.pending"
            ).exists())
            assertFalse("Immediate stop must not finalize a recording", finalized.get())
            assertNull("Test-owned cleanup failed", cleanupError.get())
        } finally {
            cleanup(source)
        }
    }
}
