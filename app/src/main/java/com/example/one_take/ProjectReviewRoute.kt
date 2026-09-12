package com.example.one_take

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.one_take.projects.ProjectBundle
import com.example.one_take.projects.ProjectStore
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Resolves navigation through persistent project identity, never a copied edit snapshot. */
@Composable
internal fun ProjectReviewRoute(
    projectId: String?, source: File, store: ProjectStore, isSaved: Boolean,
    mode: RecordingMode, script: String,
    onRetake: () -> Unit, onKeep: () -> Unit, onOpenFeatures: () -> Unit, onOpenExport: (File) -> Unit,
) {
    var bundle by remember(projectId, source.path) { mutableStateOf<ProjectBundle?>(null) }
    var failed by remember(projectId, source.path) { mutableStateOf(false) }
    var retry by remember(projectId, source.path) { mutableIntStateOf(0) }
    var openRecording by remember(projectId, source.path) { mutableStateOf(false) }
    LaunchedEffect(projectId, source.path, retry) {
        failed = false
        try {
            bundle = withContext(Dispatchers.IO) {
                val id = projectId ?: store.getOrCreate(source,
                    mode = mode.takeUnless { isSaved }, scriptText = script.takeUnless { isSaved }).id
                store.readBundle(id) ?: error("Project is missing")
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
    }
    val loaded = bundle
    if ((loaded != null && loaded.timelineJson == null) || openRecording) {
        ReviewScreen(loaded?.project?.originalVideoPath?.let(::File) ?: source, isSaved, onRetake, onKeep, onOpenFeatures, onOpenExport)
    } else {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (failed || loaded?.timelineJson != null) {
                    Text(stringResource(if (failed) R.string.project_open_error else R.string.project_timeline_unavailable))
                    if (failed) Button(onClick = { retry++ }) { Text(stringResource(R.string.retry_projects)) }
                    // A malformed manifest must never make the user's original inaccessible.
                    OutlinedButton(onClick = { openRecording = true }) { Text(stringResource(R.string.project_open_original)) }
                } else CircularProgressIndicator()
                TextButton(onClick = onKeep) { Text(stringResource(R.string.project_back)) }
            }
        }
    }
}
