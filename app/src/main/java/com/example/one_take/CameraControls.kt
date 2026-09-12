package com.example.one_take

import android.graphics.Bitmap
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as ComposeSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal enum class CameraIcon { Menu, Flip, Gallery }

@Composable
internal fun CameraGlyph(icon: CameraIcon, modifier: Modifier = Modifier, color: Color = Color.White) {
    Canvas(modifier.size(24.dp)) {
        withTransform({ scale(size.width / 24f, size.height / 24f, Offset.Zero) }) {
            val stroke = Stroke(width = 1.8f)
            when (icon) {
                CameraIcon.Menu -> {
                    drawLine(color, Offset(3f, 7f), Offset(21f, 7f), 1.8f)
                    drawLine(color, Offset(3f, 16f), Offset(21f, 16f), 1.8f)
                }
                CameraIcon.Gallery -> {
                    drawRoundRect(color, Offset(3f, 3f), ComposeSize(18f, 18f), CornerRadius(2f), style = stroke)
                    drawCircle(color, 2f, Offset(8f, 8f))
                    drawPath(Path().apply {
                        moveTo(4f, 18f); lineTo(10f, 12f); lineTo(14f, 16f); lineTo(17f, 13f); lineTo(21f, 17f)
                    }, color, style = stroke)
                }
                CameraIcon.Flip -> {
                    drawArc(color, 205f, 135f, false, Offset(3f, 2f), ComposeSize(18f, 18f), style = stroke)
                    drawArc(color, 25f, 135f, false, Offset(3f, 4f), ComposeSize(18f, 18f), style = stroke)
                    drawPath(Path().apply {
                        moveTo(16f, 5f); lineTo(21f, 7f); lineTo(21f, 2f)
                        moveTo(8f, 19f); lineTo(3f, 17f); lineTo(3f, 22f)
                    }, color, style = stroke)
                    drawRoundRect(color, Offset(8f, 8f), ComposeSize(8f, 8f), CornerRadius(1.5f), style = stroke)
                }
            }
        }
    }
}

@Composable
internal fun CameraIconButton(
    icon: CameraIcon, label: String, enabled: Boolean = true,
    background: Color = Color.Transparent, onClick: () -> Unit
) {
    Box(Modifier.size(48.dp).alpha(if (enabled) 1f else .35f)
        .clip(RoundedCornerShape(12.dp)).background(background)
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        CameraGlyph(icon)
    }
}

@Composable
internal fun GalleryThumbnail(store: VideoStore, refresh: Int, enabled: Boolean, onClick: () -> Unit) {
    val bitmap by produceState<Bitmap?>(null, store, refresh) {
        value = withContext(Dispatchers.IO) { store.listVideos().firstOrNull()?.let(::loadVideoThumbnail) }
    }
    val label = stringResource(R.string.saved_videos)
    Box(Modifier.size(48.dp).alpha(if (enabled) 1f else .35f)
        .semantics { contentDescription = label }
        .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = .4f))
            .border(2.dp, Color.White, RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
            val image = bitmap
            if (image != null) Image(image.asImageBitmap(), null, Modifier.fillMaxSize().padding(2.dp), contentScale = ContentScale.Crop)
            else CameraGlyph(CameraIcon.Gallery, Modifier.size(23.dp))
        }
    }
}

@Composable
internal fun RecordButton(recording: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (recording) R.string.stop_recording else R.string.start_recording)
    val innerWidth by animateDpAsState(if (recording) 30.dp else 70.dp, label = "recordWidth")
    val innerHeight by animateDpAsState(if (recording) 30.dp else 50.dp, label = "recordHeight")
    Box(Modifier.size(100.dp, 78.dp).alpha(if (enabled) 1f else .45f)
        .semantics { contentDescription = label }
        .clip(RoundedCornerShape(40.dp))
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(86.dp, 66.dp).border(3.dp, Color.White, RoundedCornerShape(36.dp)), contentAlignment = Alignment.Center) {
            Box(Modifier.size(innerWidth, innerHeight).clip(RoundedCornerShape(if (recording) 7.dp else 28.dp))
                .background(if (recording) Color(0xFFFF453A) else Color.White))
        }
    }
}
