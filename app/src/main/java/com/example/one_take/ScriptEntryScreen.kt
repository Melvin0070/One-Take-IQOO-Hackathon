package com.example.one_take

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ScriptEntryScreen(store: RecordingSetupStore, onBack: () -> Unit, onContinue: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(store.draft) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    BackHandler(enabled = saving) { /* Finish the durable handoff before leaving. */ }
    Surface(Modifier.fillMaxSize().testTag("script-entry")) {
        Column(Modifier.safeDrawingPadding().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack, enabled = !saving) { Text(stringResource(R.string.mode_back)) }
            Text(stringResource(R.string.script_entry_title), style = MaterialTheme.typography.headlineMedium)
            OutlinedTextField(value = text, onValueChange = { text = it; store.draft = it; saveError = false },
                label = { Text(stringResource(R.string.script_input)) }, enabled = !saving,
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("script-input"))
            val words = scriptWordCount(text)
            Text(pluralStringResource(R.plurals.script_word_count, words, words))
            if (saveError) Text(stringResource(R.string.script_save_error), color = MaterialTheme.colorScheme.error)
            OutlinedButton(enabled = !saving, onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.let {
                    text = it.toString()
                    store.draft = text
                    saveError = false
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.paste_script)) }
            Button(enabled = text.isNotBlank() && !saving, onClick = {
                saving = true
                scope.launch {
                    val accepted = withContext(Dispatchers.IO) { store.acceptScript(text) }
                    saving = false
                    if (accepted) onContinue(store.script) else saveError = true
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.continue_to_camera)) }
        }
    }
}
