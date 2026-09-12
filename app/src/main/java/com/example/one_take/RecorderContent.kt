package com.example.one_take

import com.example.one_take.captions.CaptionJobs
import com.example.one_take.engine.LiveCaptureCoordinator
import com.example.one_take.features.CaptionFeatureStore
import com.example.one_take.features.FeatureMarketplaceScreen
import android.os.SystemClock
import android.os.FileObserver
import androidx.activity.compose.BackHandler
import androidx.camera.core.CameraSelector
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class AppScreen { Home, ScriptEntry, Camera, Review, Library, Features }

@Composable
internal fun VideoRecorderApp() {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val recorder = remember { CameraRecorder(context.applicationContext) }
    val features = remember { CaptionFeatureStore.get(context) }
    val captionJobs = remember { CaptionJobs.get(context) }
    val liveEngine = remember { LiveCaptureCoordinator.get(context) }
    var featureReturn by rememberSaveable { mutableStateOf(AppScreen.Camera) }
    val store = recorder.videoStore
    val scope = rememberCoroutineScope()
    var screen by rememberSaveable { mutableStateOf(AppScreen.Home) }
    var mode by rememberSaveable { mutableStateOf(RecordingMode.Assisted) }
    val setup = remember { RecordingSetupStore(context) }
    // Keep potentially long scripts out of the saved-instance-state Binder bundle.
    var script by remember { mutableStateOf(setup.script) }
    var libraryReturn by rememberSaveable { mutableStateOf(AppScreen.Home) }
    var lensFacing by rememberSaveable { mutableIntStateOf(CameraSelector.LENS_FACING_BACK) }
    val captureState = recorder.captureState
    var cameraReady by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var retryToken by remember { mutableIntStateOf(0) }
    var elapsedMillis by remember { mutableLongStateOf(0L) }
    var reviewPath by rememberSaveable { mutableStateOf<String?>(null) }
    var reviewingSaved by rememberSaveable { mutableStateOf(false) }
    var deletePath by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var videos by remember { mutableStateOf(emptyList<File>()) }
    var libraryLoading by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val libraryError = stringResource(R.string.library_error)
    val deleteError = stringResource(R.string.delete_error)
    val captionBusyMessage = stringResource(R.string.caption_file_busy)

    fun message(text: String) { scope.launch { snackbar.showSnackbar(text) } }
    LaunchedEffect(liveEngine.error) { liveEngine.error?.let { snackbar.showSnackbar(it) } }
    fun openLibrary() {
        if (screen == AppScreen.Home || screen == AppScreen.Camera) libraryReturn = screen
        cameraReady = false; reviewPath = null; screen = AppScreen.Library; refresh++
    }

    DisposableEffect(recorder) {
        recorder.setListener(object : CameraRecorder.Listener {
            override fun onCameraReady() { cameraReady = true; cameraError = null }
            override fun onCameraError(message: String) { cameraReady = false; cameraError = message }
            override fun onRecordingFinalized(file: File) {
                reviewPath = file.absolutePath
                reviewingSaved = false
                screen = AppScreen.Review
                if (!captionJobs.finishLive(file)) {
                    if (features.installed && features.enabled) captionJobs.generate(file, autoExport = true)
                    else captionJobs.detectCuts(file)
                }
            }
            override fun onRecordingError(message: String) { captionJobs.abortLive(); scope.launch { snackbar.showSnackbar(message) } }
        })
        onDispose { captionJobs.abortUnfinalizedLive(); recorder.release() }
    }
    DisposableEffect(lifecycleOwner, recorder) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> { captionJobs.noteStopRequested(); recorder.stopRecording("background") }
                Lifecycle.Event.ON_RESUME -> refresh++
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(captureState) {
        if (captureState == CaptureUiState.Recording) {
            while (isActive) {
                elapsedMillis = SystemClock.elapsedRealtime() - recorder.recordingStartedAt
                delay(250)
            }
        } else if (captureState == CaptureUiState.Idle) elapsedMillis = 0
    }
    // A previous Activity may still be finalizing while this library is visible.
    // Observe completed writes and marker changes so its recording appears immediately.
    DisposableEffect(screen, store) {
        @Suppress("DEPRECATION")
        val observer = if (screen == AppScreen.Library) {
            object : FileObserver(store.directory.absolutePath, CLOSE_WRITE or DELETE or MOVED_TO or CREATE or ATTRIB) {
                override fun onEvent(event: Int, path: String?) {
                    // Recovery opens marker files to check their lock; ignore those closes.
                    if (event and CLOSE_WRITE != 0 && path?.endsWith(".mp4") != true) return
                    scope.launch { refresh++ }
                }
            }.also { it.startWatching() }
        } else null
        onDispose { observer?.stopWatching() }
    }
    LaunchedEffect(screen, refresh) {
        if (screen == AppScreen.Library) {
            libraryLoading = true
            try { videos = recorder.recoverVideos() }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { message(libraryError) }
            finally { libraryLoading = false }
        }
    }
    BackHandler(screen == AppScreen.Features) { screen = featureReturn }
    BackHandler(screen == AppScreen.ScriptEntry) { screen = AppScreen.Home }
    BackHandler(screen == AppScreen.Review && deletePath == null) { openLibrary() }
    BackHandler(screen == AppScreen.Library && deletePath == null) { screen = libraryReturn }
    BackHandler(screen == AppScreen.Camera && captureState == CaptureUiState.Idle) {
        cameraReady = false
        screen = AppScreen.Home
    }
    BackHandler(screen == AppScreen.Camera && captureState != CaptureUiState.Idle) {
        captionJobs.noteStopRequested()
        recorder.stopRecording()
    }

    Box(Modifier.fillMaxSize()) {
        when (screen) {
            AppScreen.Home -> HomeScreen(onSelectMode = {
                mode = it
                cameraReady = false
                screen = if (it == RecordingMode.Script) AppScreen.ScriptEntry else AppScreen.Camera
            }, onOpenProjects = ::openLibrary)
            AppScreen.ScriptEntry -> ScriptEntryScreen(setup,
                onBack = { screen = AppScreen.Home },
                onContinue = { script = it; cameraReady = false; screen = AppScreen.Camera })
            AppScreen.Camera -> CameraPermissionGate(onOpenLibrary = ::openLibrary) {
                CameraScreen(
                    recorder, lifecycleOwner, lensFacing, retryToken, captureState,
                    cameraReady, cameraError, elapsedMillis,
                    onRetryCamera = { cameraReady = false; cameraError = null; retryToken++ },
                    onFlipCamera = {
                        if (recorder.captureState == CaptureUiState.Idle) {
                            lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK)
                                CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
                            cameraReady = false
                            cameraError = null
                        }
                    },
                    onStart = {
                        if (!captionJobs.busy) {
                            captionJobs.startLive { recorder.activeEngineSessionId }
                            recorder.setFinalizationBarrier(captionJobs.captureFinalizationBarrier())
                            if (!recorder.startRecording()) captionJobs.abortLive()
                        }
                    }, onStop = {
                        captionJobs.noteStopRequested()
                        recorder.stopRecording()
                    },
                    captionStatus = if (captionJobs.liveActive && captureState != CaptureUiState.Idle)
                        captionJobs.liveText ?: stringResource(R.string.caption_live_listening)
                        else if (captionJobs.busy && captureState == CaptureUiState.Idle) stringResource(R.string.caption_other_job) else null,
                    captureBlocked = captionJobs.busy,
                    liveSegments = captionJobs.liveSegments,
                    mode = mode,
                    script = if (mode == RecordingMode.Script) script else "",
                    transcriptInstalled = features.installed,
                    transcriptEnabled = features.enabled,
                    pauseCandidateCount = liveEngine.pauseCount(recorder.activeEngineSessionId),
                    onOpenLibrary = ::openLibrary,
                    libraryVersion = refresh,
                    onOpenFeatures = { featureReturn = AppScreen.Camera; screen = AppScreen.Features }
                )
            }
            AppScreen.Features -> FeatureMarketplaceScreen(features, onBack = { screen = featureReturn })
            AppScreen.Library -> LibraryScreen(videos, libraryLoading,
                onRecord = { cameraReady = false; mode = RecordingMode.Assisted; screen = AppScreen.Camera },
                onOpen = { file -> reviewPath = file.absolutePath; reviewingSaved = true; screen = AppScreen.Review },
                onDelete = {
                    if (captionJobs.busy && captionJobs.sourcePath == it.absolutePath) message(captionBusyMessage)
                    else deletePath = it.absolutePath
                })
            AppScreen.Review -> {
                val path = reviewPath
                if (path != null) ReviewScreen(File(path), reviewingSaved,
                    onRetake = { deletePath = path }, onKeep = ::openLibrary,
                    onOpenFeatures = { featureReturn = AppScreen.Review; screen = AppScreen.Features },
                    onOpenExport = { file -> reviewPath = file.absolutePath; reviewingSaved = true })
                else LaunchedEffect(Unit) { openLibrary() }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 104.dp))
    }
    if (deletePath != null) {
        AlertDialog(
            onDismissRequest = { if (!deleting) deletePath = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_description)) },
            dismissButton = { TextButton(onClick = { deletePath = null }, enabled = !deleting) { Text(stringResource(R.string.cancel)) } },
            confirmButton = {
                TextButton(enabled = !deleting, onClick = {
                    val file = deletePath?.let(::File) ?: return@TextButton
                    deleting = true
                    scope.launch {
                        val success = withContext(Dispatchers.IO) {
                            try { store.deleteVideo(file) } catch (_: java.io.IOException) { false }
                        }
                        deleting = false
                        deletePath = null
                        if (success) {
                            captionJobs.removeMetadata(file)
                            if (screen == AppScreen.Review) {
                                reviewPath = null
                                cameraReady = false
                                screen = AppScreen.Camera
                            }
                            refresh++
                        } else message(deleteError)
                    }
                }) { Text(stringResource(R.string.delete)) }
            }
        )
    }
}
