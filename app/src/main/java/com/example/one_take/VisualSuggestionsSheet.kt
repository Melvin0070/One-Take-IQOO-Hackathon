package com.example.one_take

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.one_take.vision.FaceObservation
import com.onetake.engine.android.vision.VisualAnalyzer
import com.onetake.engine.android.vision.VisualSuggestion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal val LocalVisualAnalyzer = staticCompositionLocalOf<
    (Bitmap, FaceObservation?) -> List<VisualSuggestion>
> {
    VisualAnalyzer()::analyze
}

private sealed interface VisualSuggestionsResult {
    data class Suggestions(val values: List<VisualSuggestion>) : VisualSuggestionsResult

    data class Message(val resourceId: Int) : VisualSuggestionsResult
}

@Composable
internal fun VisualSuggestionsButton(
    previewView: PreviewView,
    face: FaceObservation?,
    enabled: Boolean,
) {
    val analyzer = LocalVisualAnalyzer.current
    val scope = rememberCoroutineScope()
    val buttonLabel = stringResource(R.string.visual_suggestions)
    var analyzing by remember { mutableStateOf(false) }
    var sheetVisible by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<VisualSuggestionsResult?>(null) }

    val dismissSheet = {
        sheetVisible = false
        result = null
    }

    Button(
        onClick = {
            if (analyzing) return@Button
            analyzing = true
            result = null
            sheetVisible = false

            scope.launch(Dispatchers.Main.immediate) {
                // PreviewView.bitmap is a UI property and this coroutine starts on the main thread.
                var frame: Bitmap? = null
                try {
                    var captureFailed = false
                    frame = try {
                        previewView.bitmap
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        captureFailed = true
                        null
                    }
                    val faceSnapshot = face
                    result = when {
                        captureFailed -> VisualSuggestionsResult.Message(
                            R.string.visual_suggestions_capture_failed,
                        )
                        frame == null -> VisualSuggestionsResult.Message(
                            R.string.visual_suggestions_preview_unavailable,
                        )
                        else -> VisualSuggestionsResult.Suggestions(
                            withContext(Dispatchers.Default) { analyzer(frame, faceSnapshot) },
                        )
                    }
                    sheetVisible = true
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    result = VisualSuggestionsResult.Message(
                        R.string.visual_suggestions_analysis_failed,
                    )
                    sheetVisible = true
                } finally {
                    if (frame != null && !frame.isRecycled) frame.recycle()
                    analyzing = false
                }
            }
        },
        enabled = enabled && !analyzing,
        modifier = Modifier
            .testTag("visual-suggestions-button")
            .semantics {
                contentDescription = buttonLabel
            },
    ) {
        if (analyzing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
        } else {
            Text(stringResource(R.string.visual_suggestions))
        }
    }

    if (sheetVisible) {
        VisualSuggestionsSheet(result = checkNotNull(result), onDismiss = dismissSheet)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VisualSuggestionsSheet(
    result: VisualSuggestionsResult,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Color(0xFF191919),
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.visual_suggestions_title),
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.size(4.dp))
            when (result) {
                is VisualSuggestionsResult.Suggestions -> {
                    if (result.values.isEmpty()) {
                        Text(
                            text = stringResource(R.string.visual_suggestions_good),
                            modifier = Modifier.testTag("visual-suggestions-good"),
                        )
                    } else {
                        result.values.forEach { suggestion ->
                            Text(
                                text = suggestion.message,
                                color = when (suggestion.severity) {
                                    VisualSuggestion.Severity.INFO -> Color.White
                                    VisualSuggestion.Severity.WARNING -> Color(0xFFFFCC80)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                            )
                        }
                    }
                }
                is VisualSuggestionsResult.Message -> Text(
                    text = stringResource(result.resourceId),
                    color = Color.LightGray,
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.visual_suggestions_dismiss), color = Color.White)
            }
        }
    }
}
