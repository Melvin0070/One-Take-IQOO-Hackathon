package com.example.one_take

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.onetake.engine.*

/** Rendered inside RecordingOverlay's Script Mode slot, which owns the scrim. */
@Composable
internal fun TeleprompterOverlay(
    progress: ScriptProgress,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (progress.chunks.isEmpty()) return
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = onPrevious, enabled = progress.currentIndex > 0,
                modifier = Modifier.testTag("script-previous")) {
                Text(stringResource(R.string.script_previous), color = if (progress.currentIndex > 0) Color.White else Color.Gray)
            }
            Text(stringResource(R.string.script_progress, (progress.currentIndex + 1).coerceAtMost(progress.chunks.size), progress.chunks.size),
                color = Color.White, fontSize = 14.sp, modifier = Modifier.padding(vertical = 14.dp))
        }
        progress.chunks.getOrNull(progress.currentIndex - 1)?.let {
            ScriptLine(it, progress.currentIndex - 1, current = false, previous = true)
        }
        if (progress.complete) {
            Text(stringResource(R.string.script_complete), color = Color.White, fontSize = 22.sp,
                modifier = Modifier.testTag("script-complete").padding(vertical = 8.dp))
        } else {
            ScriptLine(progress.chunks[progress.currentIndex], progress.currentIndex, current = true)
            progress.chunks.getOrNull(progress.currentIndex + 1)?.let {
                ScriptLine(it, progress.currentIndex + 1, current = false,
                    modifier = Modifier.clickable(role = Role.Button,
                        onClickLabel = stringResource(R.string.script_skip), onClick = onNext))
            } ?: TextButton(onClick = onNext, modifier = Modifier.testTag("script-next")) {
                Text(stringResource(R.string.script_skip), color = Color.White)
            }
        }
    }
}

@Composable
private fun ScriptLine(entry: ChunkCoverage, index: Int, current: Boolean,
                       modifier: Modifier = Modifier, previous: Boolean = false) {
    val status = stringResource(when (entry.state) {
        ScriptChunkState.PENDING -> R.string.script_pending
        ScriptChunkState.COVERED -> R.string.script_covered
        ScriptChunkState.SKIPPED -> R.string.script_skipped
        ScriptChunkState.MISMATCHED -> R.string.script_mismatched
        ScriptChunkState.REPEATED -> R.string.script_repeated
    })
    val marker = if (entry.state in setOf(ScriptChunkState.SKIPPED, ScriptChunkState.MISMATCHED)) "$status · " else ""
    Text(marker + entry.chunk.text,
        fontSize = if (previous) 13.sp else 22.sp,
        lineHeight = if (previous) 18.sp else 28.sp,
        fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
        color = when {
            current -> Color(0xFFFFE082)
            entry.state in setOf(ScriptChunkState.COVERED, ScriptChunkState.REPEATED) -> Color.White.copy(alpha = .55f)
            else -> Color.White
        },
        maxLines = if (previous) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
        modifier = modifier.fillMaxWidth().testTag("script-chunk-$index")
            .semantics { selected = current; stateDescription = status }
            .padding(vertical = if (previous) 4.dp else 8.dp))
}
