package com.example.one_take.captions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.example.one_take.shareVideo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.one_take.R
import com.example.one_take.features.CaptionFeatureStore
import java.io.File
import kotlinx.coroutines.launch

@Composable
internal fun CaptionReviewControls(
    source: File, segments: List<CaptionSegment>?, jobs: CaptionJobs,
    feature: CaptionFeatureStore, onOpenFeatures: () -> Unit, onOpenExport: (File) -> Unit,
    hasActiveEdits: Boolean = false
) {
    val context = LocalContext.current
    val style = remember { CaptionStyleStore.get(context) }
    var choosingStyle by remember { mutableStateOf(false) }
    var shareError by remember { mutableStateOf(false) }
    var editing by remember(source.absolutePath) { mutableStateOf(false) }
    val current = jobs.sourcePath == source.absolutePath
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when {
            jobs.busy && current -> {
                Text(if (jobs.operation == "export") stringResource(R.string.caption_exporting, jobs.progress)
                    else if (jobs.operation == "analyze") "Analyzing pauses…"
                    else stringResource(R.string.caption_generating), style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.caption_keep_open), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f).padding(top = 8.dp))
                    TextButton(onClick = jobs::cancel) { Text(stringResource(R.string.cancel)) }
                }
            }
            jobs.busy -> Text(stringResource(R.string.caption_other_job))
            else -> {
                if (current && jobs.error != null) Text(jobs.error.orEmpty(), color = MaterialTheme.colorScheme.error)
                if (!segments.isNullOrEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.caption_edit))
                        }
                        Button(onClick = { jobs.export(source, segments) }, modifier = Modifier.weight(1f)) {
                            Text(stringResource(R.string.caption_save_copy))
                        }
                    }
                } else {
                    if (hasActiveEdits) {
                        Button(onClick = { jobs.export(source, segments.orEmpty()) }) {
                            Text(stringResource(R.string.save_edited_copy))
                        }
                    }
                    if (segments != null) Text(stringResource(R.string.caption_no_speech), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { if (feature.installed) jobs.generate(source) else onOpenFeatures() }) {
                        Text(stringResource(if (feature.installed) R.string.caption_generate else R.string.caption_download))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { choosingStyle = true }) { Text(stringResource(R.string.caption_preset_title)) }
                    TextButton(onClick = {
                        try { shareVideo(context, source); shareError = false }
                        catch (_: Exception) { shareError = true }
                    }) { Text(stringResource(R.string.share_original)) }
                }
                if (shareError) Text(stringResource(R.string.share_unavailable), color = MaterialTheme.colorScheme.error)
                val exported = if (current) jobs.exportedFile else null
                if (exported != null && exported.exists()) {
                    Text(stringResource(R.string.caption_saved), style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { onOpenExport(exported) }) { Text(stringResource(R.string.caption_open_copy)) }
                        TextButton(onClick = {
                            try { shareVideo(context, exported); shareError = false }
                            catch (_: Exception) { shareError = true }
                        }) { Text(stringResource(R.string.share_captioned_copy)) }
                    }
                }
            }
        }
    }
    if (choosingStyle) AlertDialog(
        onDismissRequest = { choosingStyle = false },
        title = { Text(stringResource(R.string.caption_preset_title)) },
        text = { CaptionPresetPicker(style.preset, { style.select(it); jobs.forgetExport(source) }, !jobs.busy) },
        confirmButton = { TextButton(onClick = { choosingStyle = false }) { Text(stringResource(R.string.done)) } }
    )
    if (editing && segments != null) CaptionEditor(source, segments, jobs) { editing = false }
}

@Composable
private fun CaptionEditor(source: File, segments: List<CaptionSegment>, jobs: CaptionJobs, onDismiss: () -> Unit) {
    var edited by remember { mutableStateOf(segments) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        title = { Text(stringResource(R.string.caption_edit_title)) },
        text = {
            Column {
                if (error) Text(stringResource(R.string.caption_edit_error), color = MaterialTheme.colorScheme.error)
                LazyColumn(Modifier.heightIn(max = 350.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    itemsIndexed(edited) { index, segment ->
                        OutlinedTextField(value = segment.text, onValueChange = { value ->
                            edited = edited.toMutableList().also { it[index] = segment.copy(text = value.take(500), words = emptyList()) }
                        }, enabled = !saving, label = { Text(stringResource(R.string.caption_line, index + 1)) })
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.cancel)) } },
        confirmButton = {
            TextButton(enabled = !saving, onClick = {
                saving = true
                scope.launch {
                    try { jobs.save(source, edited.filter { it.text.isNotBlank() }); onDismiss() }
                    catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
                    catch (_: Exception) { error = true }
                    finally { saving = false }
                }
            }) { Text(stringResource(R.string.caption_save_edits)) }
        }
    )
}
