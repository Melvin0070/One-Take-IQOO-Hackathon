package com.example.one_take

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun GalleryVideoCard(
    file: File,
    date: String,
    fileSize: String,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    val thumbnail by produceState<Bitmap?>(
        initialValue = null,
        key1 = file.absolutePath,
        key2 = file.lastModified(),
        key3 = file.length()
    ) {
        value = try {
            withContext(Dispatchers.IO) { loadVideoThumbnail(file) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
    }
    val playLabel = stringResource(R.string.play_named_video, file.name)
    val deleteLabel = stringResource(R.string.delete_named_video, file.name)
    val cardShape = RoundedCornerShape(16.dp)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = cardShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = .12f)),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF161616),
            contentColor = Color.White
        )
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .semantics { contentDescription = playLabel }
                    .clickable(role = Role.Button, onClick = onOpen),
                contentAlignment = Alignment.Center
            ) {
                val image = thumbnail
                if (image != null) {
                    Image(
                        bitmap = image.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF272727)),
                        contentAlignment = Alignment.Center
                    ) {
                        CameraGlyph(
                            icon = CameraIcon.Gallery,
                            modifier = Modifier.size(40.dp),
                            color = Color.White.copy(alpha = .65f)
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = .48f))
                            )
                        )
                )
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = .58f)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "▶", color = Color.White, maxLines = 1)
                }
            }

            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                Text(
                    text = file.name,
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(date, color = Color.White.copy(alpha = .7f), style = MaterialTheme.typography.bodySmall)
                        Text(fileSize, color = Color.White.copy(alpha = .58f), style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onDelete,
                        modifier = Modifier.size(48.dp).semantics { contentDescription = deleteLabel }) {
                        GalleryDeleteGlyph()
                    }
                }
            }
        }
    }
}

@Composable
private fun GalleryDeleteGlyph() {
    Canvas(Modifier.size(22.dp)) {
        withTransform({ scale(size.width / 22f, size.height / 22f, Offset.Zero) }) {
            val stroke = Stroke(
                width = 1.7f,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round
            )
            drawRoundRect(
                color = Color.White,
                topLeft = androidx.compose.ui.geometry.Offset(5f, 7f),
                size = androidx.compose.ui.geometry.Size(12f, 12f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(1.5f),
                style = stroke
            )
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(4f, 5f), androidx.compose.ui.geometry.Offset(18f, 5f), stroke.width, StrokeCap.Round)
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(8f, 3f), androidx.compose.ui.geometry.Offset(14f, 3f), stroke.width, StrokeCap.Round)
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(9f, 9.5f), androidx.compose.ui.geometry.Offset(9f, 16.5f), 1.4f, StrokeCap.Round)
            drawLine(Color.White, androidx.compose.ui.geometry.Offset(13f, 9.5f), androidx.compose.ui.geometry.Offset(13f, 16.5f), 1.4f, StrokeCap.Round)
        }
    }
}
