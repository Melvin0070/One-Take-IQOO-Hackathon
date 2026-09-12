package com.example.one_take.editing

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.one_take.captions.CaptionJobs
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Small, reversible edit controls for the review screen.
 *
 * The control only persists edit metadata.  The source recording is never
 * changed, so a mistaken cut can always be applied again with one tap.
 */
@Composable
internal fun EditReviewControls(
    source: File,
    decision: EditDecision?,
    jobs: CaptionJobs,
    onDecisionChanged: (EditDecision?) -> Unit,
    onPreviewChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var dialogOpen by remember(source.absolutePath) { mutableStateOf(false) }

    OutlinedButton(
        onClick = { dialogOpen = true },
        enabled = !jobs.busy,
        modifier = modifier,
    ) {
        Text("Edits")
    }

    if (dialogOpen) {
        EditReviewDialog(
            source = source,
            decision = decision,
            jobs = jobs,
            onDecisionChanged = onDecisionChanged,
            onPreviewChanged = onPreviewChanged,
            onDismiss = {
                dialogOpen = false
                onPreviewChanged(false)
            },
        )
    }
}

@Composable
private fun EditReviewDialog(
    source: File,
    decision: EditDecision?,
    jobs: CaptionJobs,
    onDecisionChanged: (EditDecision?) -> Unit,
    onPreviewChanged: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var visibleDecision by remember(source.absolutePath) { mutableStateOf(decision) }
    var previewCut by remember(source.absolutePath) { mutableStateOf<EditCut?>(null) }
    var saving by remember(source.absolutePath) { mutableStateOf(false) }
    var error by remember(source.absolutePath) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(decision) {
        visibleDecision = decision
    }

    val cuts = visibleDecision?.cuts.orEmpty()
    val busy = jobs.busy || saving
    val hasEnabledCuts = cuts.any { it.enabled }

    fun save(next: EditDecision) {
        if (busy) return
        saving = true
        error = null
        visibleDecision = next
        scope.launch {
            try {
                jobs.saveEdits(source, next)
                onDecisionChanged(next)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (exception: Exception) {
                // Keep the last persisted decision visible and make the retry
                // action explicit instead of silently losing an undo.
                visibleDecision = decision
                error = exception.message ?: "Unable to save edits."
            } finally {
                saving = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Edits") },
        text = {
            Column(
                modifier = Modifier.widthIn(max = 440.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Suggested cuts are reversible. The original recording stays untouched.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (error != null) {
                    Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                if (!jobs.busy && jobs.sourcePath == source.absolutePath && jobs.error != null) {
                    Text(jobs.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                }
                when {
                    jobs.busy && jobs.sourcePath == source.absolutePath -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.height(18.dp))
                            Text("Analyzing…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    cuts.isEmpty() -> {
                        Text(
                            if (visibleDecision == null) {
                                "No edits analyzed yet."
                            } else {
                                "No cut suggestions found."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        TextButton(
                            onClick = { jobs.detectCuts(source) },
                            enabled = !busy,
                        ) {
                            Text("Analyze")
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 300.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            items(cuts, key = { it.id }) { cut ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            "${formatCutTimestamp(cut.startMs, cut.reason)} – " +
                                                formatCutTimestamp(cut.endMs, cut.reason),
                                            style = MaterialTheme.typography.labelLarge,
                                        )
                                        Text(
                                            cut.reason,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                    Row {
                                        TextButton(
                                            onClick = {
                                                previewCut = cut
                                                onPreviewChanged(true)
                                            },
                                            enabled = !busy,
                                        ) {
                                            Text("Preview")
                                        }
                                        TextButton(
                                            onClick = { save(checkNotNull(visibleDecision).toggle(cut.id)) },
                                            enabled = !busy,
                                        ) {
                                            Text(if (cut.enabled) "Undo" else "Apply")
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(2.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            TextButton(
                                onClick = { save(checkNotNull(visibleDecision).restoreAll()) },
                                enabled = !busy && hasEnabledCuts,
                            ) {
                                Text("Restore all")
                            }
                            TextButton(
                                onClick = { jobs.detectCuts(source) },
                                enabled = !busy,
                            ) {
                                Text("Analyze again")
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Close") }
        },
        confirmButton = {},
    )

    previewCut?.let { cut ->
        CutPreviewDialog(
            source = source,
            cut = cut,
            durationMs = visibleDecision?.durationMs ?: (cut.endMs + 500L),
            onDismiss = {
                previewCut = null
                onPreviewChanged(false)
            },
        )
    }
}

private fun formatCutTimestamp(milliseconds: Long, reason: String): String =
    formatTimestamp(milliseconds, showCentiseconds = !reason.contains("silence", ignoreCase = true))

private fun formatTimestamp(milliseconds: Long, showCentiseconds: Boolean): String {
    val totalSeconds = (milliseconds.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    if (!showCentiseconds) return "%d:%02d".format(minutes, seconds)
    val centiseconds = (milliseconds.coerceAtLeast(0L) % 1_000L) / 10L
    return "%d:%02d.%02d".format(minutes, seconds, centiseconds)
}
