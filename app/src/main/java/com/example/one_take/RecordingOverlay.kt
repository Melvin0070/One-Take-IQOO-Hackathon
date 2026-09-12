package com.example.one_take

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.one_take.captions.CaptionSegment

/** One shared preview slot. The matcher-driven teleprompter can replace the script branch (#58). */
@Composable
internal fun RecordingOverlay(
    mode: RecordingMode,
    script: String,
    segments: List<CaptionSegment>,
    recording: Boolean,
    installed: Boolean,
    enabled: Boolean,
    onOpenFeatures: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrim = modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
        .background(Color.Black.copy(alpha = .78f)).padding(12.dp)
    if (mode == RecordingMode.Script) {
        // Manual reading remains useful before the matcher/session wiring lands.
        val scroll = rememberScrollState()
        Box(scrim.testTag("teleprompter-overlay").heightIn(max = 104.dp)) {
            Text(script, color = Color.White, fontSize = 22.sp, lineHeight = 30.sp,
                modifier = Modifier.verticalScroll(scroll))
        }
    } else if (!installed || !enabled) {
        Column(scrim.testTag("transcript-unavailable")) {
            Text(stringResource(R.string.transcript_optional), color = Color.White, fontSize = 14.sp)
            // Opening another screen during capture would unbind CameraX. Keep this action idle-only.
            if (!recording) TextButton(onClick = onOpenFeatures) {
                Text(stringResource(if (installed) R.string.transcript_enable else R.string.transcript_install), color = Color.White)
            }
        }
    } else {
        val latest = if (recording) segments.filter { it.text.isNotBlank() }.takeLast(3) else emptyList()
        if (latest.isEmpty()) {
            Text(stringResource(if (recording) R.string.caption_live_listening else R.string.transcript_ready),
                color = Color.White, fontSize = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis,
                modifier = scrim.testTag("live-transcript"))
        } else {
            val text = latest.joinToString("\n") { it.text.replace(Regex("\\s+"), " ").trim() }
            val measurer = rememberTextMeasurer()
            val style = LocalTextStyle.current.copy(color = Color.White, fontSize = 18.sp, lineHeight = 24.sp)
            BoxWithConstraints(scrim.testTag("live-transcript")) {
                // Follow the newest actual wrapped lines, including the end of a long segment.
                // The timestamped source list remains complete in CaptionJobs/the engine journal.
                val layout = measurer.measure(AnnotatedString(text), style,
                    constraints = Constraints(maxWidth = constraints.maxWidth))
                val start = layout.getLineStart((layout.lineCount - 3).coerceAtLeast(0))
                Text(text.substring(start), style = style, maxLines = 3,
                    modifier = Modifier.testTag("transcript-lines"))
            }
        }
    }
}
