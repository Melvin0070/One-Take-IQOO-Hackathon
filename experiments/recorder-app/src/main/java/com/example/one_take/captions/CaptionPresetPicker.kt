package com.example.one_take.captions

import com.example.one_take.R

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Compact, keyboard and screen-reader friendly choice of the three built-in
 * caption styles.
 */
@Composable
internal fun CaptionPresetPicker(
    selected: CaptionPreset,
    onSelect: (CaptionPreset) -> Unit,
    enabled: Boolean = true
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.caption_preset_title),
            style = MaterialTheme.typography.labelLarge
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            CaptionPreset.entries.forEach { preset ->
                CaptionPresetOption(
                    preset = preset,
                    selected = selected == preset,
                    enabled = enabled,
                    onClick = { onSelect(preset) },
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun CaptionPresetOption(
    preset: CaptionPreset,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = MaterialTheme.shapes.medium
    val borderColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }
    val contentDescription = stringResource(
        R.string.caption_preset_option_description,
        stringResource(preset.labelResId)
    )

    Surface(
        modifier = modifier
            .heightIn(min = 76.dp)
            .clip(shape)
            .border(2.dp, borderColor, shape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick
            )
            .semantics {
                this.selected = selected
                this.contentDescription = contentDescription
            },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 5.dp, vertical = 7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            CaptionText(
                text = stringResource(R.string.caption_preset_preview),
                preset = preset,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(preset.labelResId),
                style = MaterialTheme.typography.labelMedium,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/** Renders caption text with the same style parameters used by the picker. */
@Composable
internal fun CaptionText(
    text: String,
    preset: CaptionPreset,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val width = maxWidth.value.takeIf { it.isFinite() && it > 0f } ?: 360f
        val textSize = (width * preset.textSizeFraction).coerceIn(12f, 40f).sp
        Text(
            text = text,
            color = Color(preset.textColorArgb),
            fontSize = textSize,
            fontWeight = if (preset.bold) FontWeight.Bold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(MaterialTheme.shapes.small)
                .background(Color(preset.backgroundColorArgb))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}
