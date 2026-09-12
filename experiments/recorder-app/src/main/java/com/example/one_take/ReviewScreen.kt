package com.example.one_take

import android.app.Activity
import android.net.Uri
import android.view.WindowManager
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.one_take.captions.CaptionJobs
import com.example.one_take.captions.CaptionReviewControls
import com.example.one_take.captions.CaptionSegment
import com.example.one_take.captions.CaptionStyleStore
import com.example.one_take.captions.CaptionText
import com.example.one_take.editing.EditDecision
import com.example.one_take.editing.EditReviewControls
import com.example.one_take.editing.EditedVideoPlayer
import java.io.File
import kotlinx.coroutines.delay

@Composable
internal fun ReviewScreen(
    file: File,
    isSaved: Boolean,
    onRetake: () -> Unit,
    onKeep: () -> Unit,
    onOpenFeatures: () -> Unit = {},
    onOpenExport: (File) -> Unit = {},
) {
    val context = LocalContext.current
    val jobs = remember { CaptionJobs.get(context) }
    val feature = remember { com.example.one_take.features.CaptionFeatureStore.get(context) }
    val captionStyles = remember { CaptionStyleStore.get(context) }
    var captions by remember(file.absolutePath) { mutableStateOf<List<CaptionSegment>?>(null) }
    var edits by remember(file.absolutePath) { mutableStateOf<EditDecision?>(null) }
    var awaitingInitialAnalysis by remember(file.absolutePath) {
        mutableStateOf(jobs.busy && jobs.sourcePath == file.absolutePath && jobs.operation in setOf("transcribe", "analyze"))
    }
    var currentPosition by remember(file.absolutePath) { mutableLongStateOf(0L) }
    var videoAspect by remember(file.absolutePath) { mutableStateOf(9f / 16f) }
    var playbackError by remember(file.absolutePath) { mutableStateOf(false) }
    var metadataError by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    var videoView by remember(file.absolutePath) { mutableStateOf<VideoView?>(null) }
    var cutPreviewing by remember(file.absolutePath) { mutableStateOf(false) }
    var position by rememberSaveable(file.absolutePath) { mutableStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    LaunchedEffect(file.absolutePath, jobs.revision) {
        try {
            captions = jobs.read(file)
            edits = jobs.readEdits(file)
            metadataError = null
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (exception: Exception) {
            android.util.Log.e("EngineHistory", "Unable to load recording history", exception)
            captions = null
            edits = null
            metadataError = "Editing history could not be loaded. Your original recording is safe."
        }
    }

    val hasEnabledCuts = edits?.cuts?.any { it.enabled } == true
    val editTimelineKey = remember(edits) {
        edits?.cuts?.joinToString(separator = ";") { cut ->
            "${cut.id}:${cut.startMs}:${cut.endMs}:${cut.enabled}"
        }.orEmpty()
    }
    val keptRanges = remember(edits) {
        edits?.takeIf { decision -> decision.cuts.any { it.enabled } }
            ?.keptRanges()
            .orEmpty()
    }

    DisposableEffect(jobs.busy, context) {
        val window = (context as? Activity)?.window
        val wasAwake = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) != 0
        if (jobs.busy) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (!wasAwake) window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // The raw VideoView remains the default path. When edits become active,
    // this effect stops and releases it before the clipped ExoPlayer takes over.
    DisposableEffect(file.absolutePath, lifecycleOwner, hasEnabledCuts) {
        val observer = if (!hasEnabledCuts) {
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    position = videoView?.currentPosition ?: position
                    videoView?.pause()
                }
            }.also { lifecycleOwner.lifecycle.addObserver(it) }
        } else {
            null
        }
        onDispose {
            observer?.let(lifecycleOwner.lifecycle::removeObserver)
            videoView?.stopPlayback()
            videoView = null
        }
    }

    LaunchedEffect(captions, jobs.busy, hasEnabledCuts) {
        if (awaitingInitialAnalysis && (captions != null || !jobs.busy)) {
            awaitingInitialAnalysis = false
            if (!hasEnabledCuts && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                videoView?.seekTo(0)
                videoView?.start()
            }
        }
    }

    LaunchedEffect(videoView, hasEnabledCuts) {
        while (videoView != null && !hasEnabledCuts) {
            currentPosition = videoView?.currentPosition?.toLong() ?: currentPosition
            delay(100)
        }
    }

    LaunchedEffect(editTimelineKey) {
        playbackError = false
    }

    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp)) {
            Text(stringResource(R.string.review_video), style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                val previewHeight = maxHeight
                val fitted = if (maxWidth / maxHeight > videoAspect) {
                    Modifier.fillMaxHeight().aspectRatio(videoAspect)
                } else {
                    Modifier.fillMaxWidth().aspectRatio(videoAspect)
                }
                Box(fitted) {
                    key(file.absolutePath, editTimelineKey) {
                        if (hasEnabledCuts) {
                            EditedVideoPlayer(
                                source = file,
                                ranges = keptRanges,
                                initialSourcePositionMs = currentPosition,
                                autoPlay = !awaitingInitialAnalysis,
                                onSourcePositionChanged = { currentPosition = it },
                                onAspectRatioChanged = { aspect -> videoAspect = aspect },
                                onPlaybackError = { playbackError = true },
                                pauseForPreview = cutPreviewing,
                                modifier = Modifier.fillMaxSize(),
                            )
                        } else {
                            AndroidView(
                                factory = { viewContext ->
                                    VideoView(viewContext).apply {
                                        videoView = this
                                        contentDescription = viewContext.getString(R.string.video_playback)
                                        setMediaController(MediaController(viewContext).also { it.setAnchorView(this) })
                                        setOnPreparedListener { player ->
                                            if (player.videoWidth > 0 && player.videoHeight > 0) {
                                                videoAspect = player.videoWidth.toFloat() / player.videoHeight
                                            }
                                            seekTo(position)
                                            if (!awaitingInitialAnalysis && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                                                start()
                                            }
                                        }
                                        setOnErrorListener { _, _, _ ->
                                            playbackError = true
                                            true
                                        }
                                        setVideoURI(Uri.fromFile(file))
                                    }
                                },
                                update = { view ->
                                    videoView = view
                                    if (cutPreviewing) view.pause()
                                },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    val activeCaption = captions?.firstOrNull {
                        currentPosition >= it.startMs && currentPosition < it.endMs
                    }
                    if (activeCaption != null) {
                        CaptionText(
                            text = activeCaption.text,
                            preset = captionStyles.preset,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(
                                    start = 12.dp,
                                    end = 12.dp,
                                    bottom = previewHeight * captionStyles.preset.bottomAnchor,
                                ),
                        )
                    }
                    if (playbackError) {
                        Text(
                            stringResource(R.string.playback_error),
                            color = Color.White,
                            modifier = Modifier.padding(20.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            metadataError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            EditReviewControls(
                source = file,
                decision = edits,
                jobs = jobs,
                onDecisionChanged = { edits = it },
                onPreviewChanged = { cutPreviewing = it },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            CaptionReviewControls(file, captions, jobs, feature, onOpenFeatures, onOpenExport,
                hasActiveEdits = edits?.cuts?.any { it.enabled } == true)
            Text(stringResource(R.string.review_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isSaved) {
                    OutlinedButton(
                        onClick = onRetake,
                        enabled = !(jobs.busy && jobs.sourcePath == file.absolutePath),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(stringResource(R.string.retake))
                    }
                }
                Button(onClick = onKeep, modifier = Modifier.weight(1f)) {
                    Text(stringResource(if (isSaved) R.string.done else R.string.keep_video))
                }
            }
        }
    }
}
