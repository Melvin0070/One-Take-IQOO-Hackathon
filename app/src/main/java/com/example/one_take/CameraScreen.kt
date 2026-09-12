package com.example.one_take

import com.example.one_take.captions.CaptionStyleStore
import com.example.one_take.captions.CaptionSegment
import com.example.one_take.captions.CaptionText
import com.example.one_take.director.CaptureGuidance
import com.example.one_take.director.CaptureGuidancePrompt
import com.example.one_take.director.DirectorPrompt
import com.example.one_take.director.DirectorSignals

import android.app.Activity
import android.content.Context
import android.view.WindowManager
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.lifecycle.LifecycleOwner
import java.util.Locale
import kotlinx.coroutines.delay

private const val GUIDANCE_CHIP_EXPIRY_MS = 5_000L

@Composable
internal fun CameraScreen(
    recorder: CameraRecorder,
    lifecycleOwner: LifecycleOwner,
    lensFacing: Int,
    retryToken: Int,
    captureState: CaptureUiState,
    cameraReady: Boolean,
    cameraError: String?,
    elapsedMillis: Long,
    onRetryCamera: () -> Unit,
    onFlipCamera: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenLibrary: () -> Unit,
    libraryVersion: Int = 0,
    onOpenFeatures: () -> Unit = {},
    captionStatus: String? = null,
    captureBlocked: Boolean = false,
    liveSegments: List<CaptionSegment>? = null,
    pauseCandidateCount: Int = 0,
    mode: RecordingMode = RecordingMode.Assisted,
    script: String = "",
    transcriptInstalled: Boolean = true,
    transcriptEnabled: Boolean = true,
) {
    val context = LocalContext.current
    val captionStyle = remember { CaptionStyleStore.get(context) }
    val preferences = remember { context.getSharedPreferences("camera_settings", Context.MODE_PRIVATE) }
    var gridEnabled by rememberSaveable { mutableStateOf(preferences.getBoolean("grid", true)) }
    var levelEnabled by rememberSaveable { mutableStateOf(preferences.getBoolean("level", true)) }
    var preferredQuality by rememberSaveable {
        mutableStateOf(runCatching { CameraQuality.valueOf(preferences.getString("quality", "FHD") ?: "FHD") }.getOrDefault(CameraQuality.FHD))
    }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var zoomOpen by remember { mutableStateOf(false) }
    val idle = captureState == CaptureUiState.Idle
    val recording = captureState == CaptureUiState.Recording
    var setupPrompt by remember { mutableStateOf<CaptureGuidancePrompt?>(null) }
    var directorPrompt by remember { mutableStateOf<DirectorPrompt?>(null) }
    val captureGuidance = remember(recorder) { CaptureGuidance() }
    val directorSignals = remember(recorder) { DirectorSignals() }
    val canZoom = cameraReady && (idle || recording) && recorder.maxZoomRatio > recorder.minZoomRatio
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    DisposableEffect(recorder) { onDispose { recorder.unbindCamera() } }
    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        val oldKeepScreenOn = window?.let {
            it.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (oldStatus != null) controller.isAppearanceLightStatusBars = oldStatus
            if (oldNavigation != null) controller.isAppearanceLightNavigationBars = oldNavigation
            when (oldKeepScreenOn) {
                true -> window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                false -> window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                null -> Unit
            }
        }
    }
    LaunchedEffect(previewView, lifecycleOwner, lensFacing, retryToken, preferredQuality) {
        recorder.bindCamera(previewView, lifecycleOwner, lensFacing, preferredQuality)
    }
    LaunchedEffect(captureState) {
        if (!idle) settingsOpen = false
        if (!idle && !recording) zoomOpen = false
        if (!recording) {
            directorSignals.reset()
            directorPrompt = null
        }
        if (!idle) {
            captureGuidance.reset()
            setupPrompt = null
        }
    }
    LaunchedEffect(recorder.faceObservation, idle) {
        if (idle) {
            val observation = recorder.faceObservation
            val prompt = captureGuidance.observe(observation,
                observation?.timestampMs ?: android.os.SystemClock.elapsedRealtime())
            if (prompt != null) setupPrompt = prompt
        }
    }
    LaunchedEffect(recording, liveSegments, recorder.faceObservation, elapsedMillis) {
        if (recording) {
            val transcriptPrompt = liveSegments?.let {
                directorSignals.observeTranscript(it, elapsedMillis)
            }
            val gazePrompt = directorSignals.observeGaze(
                offAxis = recorder.faceObservation?.let { observation ->
                    observation.offAxis && android.os.SystemClock.elapsedRealtime() - observation.timestampMs in 0..500
                } == true,
                elapsedMs = elapsedMillis,
            )
            if (transcriptPrompt != null) directorPrompt = transcriptPrompt
            else if (gazePrompt != null) directorPrompt = gazePrompt
        }
    }
    LaunchedEffect(setupPrompt) {
        val displayed = setupPrompt ?: return@LaunchedEffect
        delay(GUIDANCE_CHIP_EXPIRY_MS)
        if (setupPrompt == displayed) setupPrompt = null
    }
    LaunchedEffect(directorPrompt) {
        val displayed = directorPrompt ?: return@LaunchedEffect
        delay(GUIDANCE_CHIP_EXPIRY_MS)
        if (directorPrompt == displayed) directorPrompt = null
    }

    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth().weight(1f).pointerInput(canZoom) {
            if (canZoom) detectTransformGestures { _, _, zoom, _ ->
                recorder.setZoomRatio(recorder.zoomRatio * zoom)
            }
        }) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            if (gridEnabled) CompositionGrid()
            CameraLevel(enabled = levelEnabled, modifier = Modifier.align(Alignment.Center).fillMaxWidth(.34f).height(48.dp))
            Box(Modifier.fillMaxWidth().height(110.dp).background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = .45f), Color.Transparent))))
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                CameraIconButton(CameraIcon.Menu, stringResource(R.string.camera_settings), enabled = idle,
                    onClick = { settingsOpen = true })
                val qualityLabel = if (cameraReady) {
                    qualityText(recorder.selectedQuality)
                } else {
                    stringResource(R.string.quality_auto)
                }
                val qualityDescription = stringResource(R.string.recording_quality)
                Box(Modifier.heightIn(min = 48.dp).semantics { contentDescription = qualityDescription }
                    .clickable(enabled = idle, role = Role.Button) { settingsOpen = true }.padding(vertical = 7.dp),
                    contentAlignment = Alignment.Center) {
                    Text(qualityLabel, color = Color.Black, fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                        modifier = Modifier.clip(RoundedCornerShape(5.dp)).background(Color.White).padding(horizontal = 12.dp, vertical = 7.dp))
                }
                GalleryThumbnail(recorder.videoStore, libraryVersion, idle, onOpenLibrary)
            }
            DeviceStatusChip(Modifier.align(Alignment.TopStart).padding(start = 12.dp, top = 68.dp))
            val status = when (captureState) {
                CaptureUiState.Starting -> stringResource(R.string.starting_recording)
                CaptureUiState.Finalizing -> stringResource(R.string.saving)
                CaptureUiState.Recording -> formatElapsedTime(elapsedMillis)
                CaptureUiState.Idle -> null
            }
            if (status != null) {
                Text(if (recording) "●  $status" else status, color = Color.White, fontSize = 14.sp,
                    fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.TopCenter).padding(top = 74.dp)
                        .clip(RoundedCornerShape(6.dp)).background(if (recording) Color(0xFFD32F2F) else Color.Black.copy(alpha = .75f))
                        .padding(horizontal = 12.dp, vertical = 6.dp))
            }
            RecordingOverlay(mode, script, liveSegments.orEmpty(), !idle,
                transcriptInstalled, transcriptEnabled, onOpenFeatures,
                Modifier.align(Alignment.TopCenter).padding(start = 16.dp, end = 16.dp, top = 112.dp))
            if (idle) setupPrompt?.let { GuidanceChip(it.message, Modifier.align(Alignment.BottomCenter).padding(bottom = 148.dp)) }
            if (recording) directorPrompt?.let {
                GuidanceChip(it.message, Modifier.align(Alignment.BottomCenter).padding(bottom = 148.dp))
            }
            if (recording && pauseCandidateCount > 0) {
                Text("$pauseCandidateCount potential pause${if (pauseCandidateCount == 1) "" else "s"}",
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = 92.dp)
                        .testTag("livePauseCount").clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = .65f)).padding(horizontal = 10.dp, vertical = 6.dp))
            }
            if (captionStatus != null && idle) {
                CaptionText(captionStatus, captionStyle.preset,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .padding(start = 20.dp, end = 76.dp, bottom = 26.dp))
            }
            Column(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (zoomOpen && canZoom) ZoomPanel(recorder.zoomRatio, recorder.minZoomRatio, recorder.maxZoomRatio, recorder::setZoomRatio)
                val zoomLabel = stringResource(R.string.camera_zoom)
                Box(Modifier.size(48.dp, 56.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .65f))
                    .semantics { contentDescription = zoomLabel }
                    .clickable(enabled = canZoom, role = Role.Button) { zoomOpen = !zoomOpen }, contentAlignment = Alignment.Center) {
                    Text(zoomText(recorder.zoomRatio), color = if (canZoom) Color.White else Color.Gray,
                        fontSize = 19.sp, fontWeight = FontWeight.Medium)
                }
                CameraIconButton(CameraIcon.Flip, stringResource(R.string.flip_camera),
                    enabled = idle && (cameraReady || cameraError != null), background = Color.Black.copy(alpha = .65f), onClick = onFlipCamera)
            }
            if (!cameraReady) {
                Column(Modifier.align(Alignment.Center).padding(28.dp).clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = .75f)).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (cameraError == null) CircularProgressIndicator(Modifier.size(24.dp), color = Color.White, strokeWidth = 2.dp)
                    Text(cameraError ?: stringResource(R.string.starting_camera), color = Color.White,
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
                    if (cameraError != null) TextButton(onClick = onRetryCamera) {
                        Text(stringResource(R.string.retry_camera), color = Color.White)
                    }
                }
            }
        }
        CameraFooter(recording, cameraReady && ((idle && !captureBlocked) || recording), onStart, onStop)
    }
    if (settingsOpen) {
        CameraSettingsSheet(
            supportedQualities = recorder.supportedQualities,
            selectedQuality = recorder.selectedQuality,
            qualityEnabled = cameraReady && idle,
            gridEnabled = gridEnabled,
            levelEnabled = levelEnabled,
            onGridChange = { gridEnabled = it; preferences.edit { putBoolean("grid", it) } },
            onLevelChange = { levelEnabled = it; preferences.edit { putBoolean("level", it) } },
            onQualityChange = { preferredQuality = it; preferences.edit { putString("quality", it.name) }; settingsOpen = false },
            onDismiss = { settingsOpen = false },
            onOpenFeatures = { settingsOpen = false; onOpenFeatures() }
        )
    }
}

@Composable
private fun GuidanceChip(message: String, modifier: Modifier = Modifier) {
    Text(
        text = message,
        color = Color.White,
        fontSize = 13.sp,
        fontWeight = FontWeight.Medium,
        modifier = modifier
            .testTag("director-prompt")
            .semantics { contentDescription = message }
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = .78f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun CompositionGrid() {
    Canvas(Modifier.fillMaxSize().testTag("composition-grid")) {
        for (fraction in listOf(1f / 3f, 2f / 3f)) {
            drawLine(Color.White.copy(alpha = .55f), Offset(size.width * fraction, 0f), Offset(size.width * fraction, size.height), 1.dp.toPx())
            drawLine(Color.White.copy(alpha = .55f), Offset(0f, size.height * fraction), Offset(size.width, size.height * fraction), 1.dp.toPx())
        }
    }
}

@Composable
private fun CameraFooter(recording: Boolean, recordEnabled: Boolean,
                         onStart: () -> Unit, onStop: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(108.dp).padding(horizontal = 12.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center) {
        RecordButton(recording, recordEnabled, if (recording) onStop else onStart)
    }
}

@Composable
private fun ZoomPanel(ratio: Float, minimum: Float, maximum: Float, onChange: (Float) -> Unit) {
    val label = stringResource(R.string.camera_zoom)
    Column(Modifier.width(200.dp).clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = .85f)).padding(12.dp)) {
        Text(zoomText(ratio), color = Color.White, fontSize = 13.sp)
        Slider(value = ratio.coerceIn(minimum, maximum), onValueChange = onChange, valueRange = minimum..maximum,
            modifier = Modifier.testTag("zoom-slider").semantics { contentDescription = label },
            colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White, inactiveTrackColor = Color.DarkGray))
    }
}

internal fun zoomText(ratio: Float): String = if (ratio % 1f == 0f) "${ratio.toInt()}×" else String.format(Locale.US, "%.1f×", ratio)
