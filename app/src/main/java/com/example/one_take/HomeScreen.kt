package com.example.one_take

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun HomeScreen(onSelectMode: (RecordingMode) -> Unit, onOpenProjects: () -> Unit) {
    Surface(Modifier.fillMaxSize().testTag("home-screen")) {
        Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineLarge)
            Text(stringResource(R.string.home_description), style = MaterialTheme.typography.bodyLarge)
            ModeCard(R.string.script_mode, R.string.script_mode_description) { onSelectMode(RecordingMode.Script) }
            ModeCard(R.string.assisted_mode, R.string.assisted_mode_description) { onSelectMode(RecordingMode.Assisted) }
            OutlinedButton(onClick = onOpenProjects, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.projects))
            }
        }
    }
}

@Composable
private fun ModeCard(title: Int, description: Int, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp)) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.headlineSmall)
            Text(stringResource(description), style = MaterialTheme.typography.bodyLarge)
        }
    }
}
