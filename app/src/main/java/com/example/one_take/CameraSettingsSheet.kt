package com.example.one_take

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.one_take.captions.CaptionStyleStore
import com.example.one_take.captions.CaptionPresetPicker
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CameraSettingsSheet(
    supportedQualities: List<CameraQuality>, selectedQuality: CameraQuality?, qualityEnabled: Boolean,
    gridEnabled: Boolean, levelEnabled: Boolean,
    onGridChange: (Boolean) -> Unit, onLevelChange: (Boolean) -> Unit,
    onQualityChange: (CameraQuality) -> Unit, onDismiss: () -> Unit, onOpenFeatures: () -> Unit
) {
    val levelAvailable = rememberLevelAvailable()
    val context = LocalContext.current
    val style = remember { CaptionStyleStore.get(context) }
    ModalBottomSheet(sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), onDismissRequest = onDismiss, containerColor = Color(0xFF191919), contentColor = Color.White) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.camera_settings), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.camera_settings_hint), color = Color.LightGray, modifier = Modifier.padding(top = 6.dp, bottom = 20.dp))
            Text(stringResource(R.string.recording_quality), style = MaterialTheme.typography.titleSmall)
            if (supportedQualities.isEmpty()) {
                Text(stringResource(R.string.quality_waiting), color = Color.Gray, modifier = Modifier.padding(vertical = 12.dp))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                supportedQualities.forEach { quality ->
                    val selected = quality == selectedQuality
                    Surface(modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                        .selectable(selected, enabled = qualityEnabled, role = Role.RadioButton, onClick = { onQualityChange(quality) }),
                        shape = MaterialTheme.shapes.small,
                        color = if (selected) Color.White else Color(0xFF303030),
                        contentColor = if (selected) Color.Black else Color.White) {
                        Box(contentAlignment = Alignment.Center) { Text(qualityText(quality), fontWeight = FontWeight.Medium) }
                    }
                }
            }
            SettingsToggle(stringResource(R.string.composition_grid), gridEnabled, true, onGridChange)
            SettingsToggle(stringResource(R.string.camera_level_description), levelEnabled && levelAvailable, levelAvailable, onLevelChange)
            if (!levelAvailable) Text(stringResource(R.string.level_unavailable), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            CaptionPresetPicker(style.preset, style::select, qualityEnabled)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onOpenFeatures, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.caption_marketplace), color = Color.White)
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.done), color = Color.White)
            }
        }
    }
}

@Composable
private fun SettingsToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp)
        .toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = if (enabled) Color.White else Color.Gray)
        Switch(checked = checked, onCheckedChange = null, enabled = enabled,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = Color.White))
    }
}

internal fun qualityText(quality: CameraQuality): String = quality.label
