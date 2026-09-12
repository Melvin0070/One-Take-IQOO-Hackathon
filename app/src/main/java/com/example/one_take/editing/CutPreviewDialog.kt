package com.example.one_take.editing

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File

private const val PREVIEW_CONTEXT_MS = 500L

/** Plays a short raw-source window around a suggested cut. */
@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun CutPreviewDialog(
    source: File,
    cut: EditCut,
    durationMs: Long,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val player = remember(source.absolutePath, cut.id, cut.startMs, cut.endMs) {
        ExoPlayer.Builder(context).build()
    }
    val latestDismiss by rememberUpdatedState(onDismiss)
    val clipStartMs = (cut.startMs - PREVIEW_CONTEXT_MS).coerceAtLeast(0L)
    val clipEndMs = (cut.endMs + PREVIEW_CONTEXT_MS)
        .coerceAtMost(durationMs)
        .coerceAtLeast(clipStartMs + 1L)

    fun dismissPreview() {
        // Stop this player before the parent removes the dialog. This prevents
        // a resumed review player from overlapping the preview audio.
        player.playWhenReady = false
        player.pause()
        latestDismiss()
    }

    DisposableEffect(player, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) dismissPreview()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            player.playWhenReady = false
            player.pause()
            player.release()
        }
    }

    LaunchedEffect(player, clipStartMs, clipEndMs) {
        val item = MediaItem.Builder()
            .setUri(Uri.fromFile(source))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(clipStartMs)
                    .setEndPositionMs(clipEndMs)
                    .build(),
            )
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.playWhenReady = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    Dialog(onDismissRequest = ::dismissPreview) {
        Surface(
            shape = MaterialTheme.shapes.large,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 440.dp)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Preview original", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Raw recording · ${formatPreviewTimestamp(clipStartMs)} – " +
                        formatPreviewTimestamp(clipEndMs),
                    style = MaterialTheme.typography.bodySmall,
                )
                AndroidView(
                    factory = { viewContext ->
                        PlayerView(viewContext).apply {
                            this.player = player
                            setUseController(true)
                            contentDescription = "Cut preview playback"
                        }
                    },
                    update = { view -> view.player = player },
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f),
                )
                Text(
                    "This preview always uses the original recording, even when the cut is applied.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = ::dismissPreview) { Text("Close preview") }
                }
            }
        }
    }
}

private fun formatPreviewTimestamp(milliseconds: Long): String {
    val safe = milliseconds.coerceAtLeast(0L)
    val totalSeconds = safe / 1_000L
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    val centiseconds = (safe % 1_000L) / 10L
    return "%d:%02d.%02d".format(minutes, seconds, centiseconds)
}
