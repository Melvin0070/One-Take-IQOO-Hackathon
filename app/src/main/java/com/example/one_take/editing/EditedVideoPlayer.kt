package com.example.one_take.editing

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * Plays the kept source ranges as a playlist of clipped media items.
 *
 * The player timeline is intentionally separate from the source timeline. The
 * callback reports source time, allowing raw caption timestamps to remain
 * unchanged while the review preview skips deleted ranges.
 */
@Composable
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
internal fun EditedVideoPlayer(
    source: File,
    ranges: List<SourceRange>,
    initialSourcePositionMs: Long,
    autoPlay: Boolean,
    onSourcePositionChanged: (Long) -> Unit,
    onAspectRatioChanged: (Float) -> Unit,
    onPlaybackError: () -> Unit,
    pauseForPreview: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sourcePath = source.absolutePath
    val rangeKey = remember(ranges) { ranges.map { it.startMs to it.endMs } }
    val player = remember(sourcePath, rangeKey) {
        ExoPlayer.Builder(context).build()
    }

    val requestedAutoPlay = rememberUpdatedState(autoPlay)
    DisposableEffect(player, lifecycleOwner) {
        var wasPlayingWhenStopped = autoPlay
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> {
                    wasPlayingWhenStopped = player.playWhenReady
                    player.pause()
                    onSourcePositionChanged(sourcePosition(player, ranges))
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (wasPlayingWhenStopped && requestedAutoPlay.value) player.play()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        val listener = object : Player.Listener {
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) {
                    onAspectRatioChanged(videoSize.width.toFloat() / videoSize.height)
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError()
            }
        }
        player.addListener(listener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.pause()
            player.release()
        }
    }

    LaunchedEffect(player, sourcePath, rangeKey) {
        if (ranges.isEmpty()) return@LaunchedEffect
        val mediaItems = ranges.map { range ->
            MediaItem.Builder()
                .setUri(Uri.fromFile(source))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(range.startMs)
                        .setEndPositionMs(range.endMs)
                        .build(),
                )
                .build()
        }
        val (itemIndex, itemPositionMs) = seekPosition(initialSourcePositionMs, ranges)
        player.setMediaItems(mediaItems, itemIndex, itemPositionMs)
        player.prepare()
        player.playWhenReady = autoPlay && !pauseForPreview &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    // Caption generation can finish after the player was created. Starting the
    // existing playlist here avoids rebuilding it or seeking back to zero.
    LaunchedEffect(player, autoPlay) {
        player.playWhenReady = autoPlay && !pauseForPreview &&
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
    }

    LaunchedEffect(player, pauseForPreview) {
        if (pauseForPreview) {
            player.playWhenReady = false
            player.pause()
        }
    }

    LaunchedEffect(player, sourcePath, rangeKey) {
        while (isActive) {
            onSourcePositionChanged(sourcePosition(player, ranges))
            delay(100)
        }
    }

    val exoPlayer = player
    AndroidView(
        factory = { viewContext ->
            PlayerView(viewContext).apply {
                this.player = exoPlayer
                setUseController(true)
                contentDescription = "Edited video playback"
            }
        },
        update = { view ->
            view.player = player
            if (pauseForPreview) player.pause()
        },
        modifier = modifier,
    )
}

private fun seekPosition(sourcePositionMs: Long, ranges: List<SourceRange>): Pair<Int, Long> {
    if (ranges.isEmpty()) return 0 to 0L
    val position = sourcePositionMs.coerceAtLeast(0L)
    ranges.forEachIndexed { index, range ->
        if (position < range.endMs) {
            return index to (position - range.startMs).coerceAtLeast(0L)
        }
    }
    val lastIndex = ranges.lastIndex
    val last = ranges[lastIndex]
    return lastIndex to (last.endMs - last.startMs).coerceAtLeast(0L)
}

private fun sourcePosition(player: ExoPlayer, ranges: List<SourceRange>): Long {
    if (ranges.isEmpty()) return 0L
    val itemIndex = player.currentMediaItemIndex
        .coerceIn(0, ranges.lastIndex)
    val range = ranges[itemIndex]
    return (range.startMs + player.currentPosition)
        .coerceIn(range.startMs, range.endMs)
}
