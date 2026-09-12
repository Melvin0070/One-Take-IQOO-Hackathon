package com.example.one_take

import android.app.Activity
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import java.io.File
import java.text.DateFormat
import java.util.Date

@Composable
internal fun LibraryScreen(
    videos: List<File>, loading: Boolean, onRecord: () -> Unit,
    onOpen: (File) -> Unit, onDelete: (File) -> Unit
) {
    val context = LocalContext.current

    DisposableEffect(context) {
        val window = (context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val oldStatus = controller?.isAppearanceLightStatusBars
        val oldNavigation = controller?.isAppearanceLightNavigationBars
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            if (oldStatus != null) controller?.isAppearanceLightStatusBars = oldStatus
            if (oldNavigation != null) controller?.isAppearanceLightNavigationBars = oldNavigation
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color.Black,
        contentColor = Color.White
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.saved_videos),
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 12.dp),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Color.White
                )
                OutlinedButton(
                    onClick = onRecord,
                    modifier = Modifier.heightIn(min = 48.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = .72f)),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                ) {
                    Text(stringResource(R.string.record_video))
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                when {
                    loading -> {
                        CircularProgressIndicator(
                            modifier = Modifier.align(Alignment.Center),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    }

                    videos.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .padding(horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            CameraGlyph(
                                icon = CameraIcon.Gallery,
                                modifier = Modifier.size(48.dp),
                                color = Color.White.copy(alpha = .7f)
                            )
                            Spacer(Modifier.height(16.dp))
                            Text(
                                text = stringResource(R.string.no_videos),
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                        }
                    }

                    else -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            items(videos, key = { it.absolutePath }) { file ->
                                GalleryVideoCard(
                                    file = file,
                                    date = DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(file.lastModified())),
                                    fileSize = Formatter.formatShortFileSize(context, file.length()),
                                    onOpen = { onOpen(file) },
                                    onDelete = { onDelete(file) }
                                )
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.library_storage_hint),
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = .52f),
                modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
            )
        }
    }
}
