package com.example.one_take.captions

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.example.one_take.R

/**
 * A small set of caption treatments intended for talking-head footage.
 *
 * [textSizeFraction] is relative to the width of the video frame. [bottomAnchor]
 * is the normalized distance from the bottom safe area, where 0f is the bottom
 * edge and 1f is the top edge. The renderer and the Compose preview both use
 * these values so a selected style stays recognizable across the app.
 */
enum class CaptionPreset(
    @StringRes val labelResId: Int,
    val textColorArgb: Int,
    val backgroundColorArgb: Int,
    val bold: Boolean,
    val textSizeFraction: Float,
    val bottomAnchor: Float
) {
    CLEAN(
        labelResId = R.string.caption_preset_clean,
        textColorArgb = 0xFFFFFFFF.toInt(),
        backgroundColorArgb = 0xB8000000.toInt(),
        bold = false,
        textSizeFraction = 0.045f,
        bottomAnchor = 0.12f
    ),
    BOLD(
        labelResId = R.string.caption_preset_bold,
        textColorArgb = 0xFF171717.toInt(),
        backgroundColorArgb = 0xFFF4C95D.toInt(),
        bold = true,
        textSizeFraction = 0.050f,
        bottomAnchor = 0.12f
    ),
    MINIMAL(
        labelResId = R.string.caption_preset_minimal,
        textColorArgb = 0xFFFFFFFF.toInt(),
        backgroundColorArgb = 0x00000000,
        bold = false,
        textSizeFraction = 0.040f,
        bottomAnchor = 0.08f
    );

    companion object {
        /** Reads a preference value without allowing stale data to break the UI. */
        fun fromStoredName(name: String?): CaptionPreset {
            val normalized = name?.trim()
            return entries.firstOrNull { it.name.equals(normalized, ignoreCase = true) } ?: CLEAN
        }
    }
}

/** Stores the user's caption style choice for reuse on capture and review screens. */
internal class CaptionStyleStore private constructor(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    var preset by mutableStateOf(
        CaptionPreset.fromStoredName(preferences.getString(KEY_PRESET, null))
    )
        private set

    fun select(preset: CaptionPreset) {
        this.preset = preset
        preferences.edit { putString(KEY_PRESET, preset.name) }
    }

    companion object {
        @Volatile private var instance: CaptionStyleStore? = null
        fun get(context: Context): CaptionStyleStore = instance ?: synchronized(this) {
            instance ?: CaptionStyleStore(context.applicationContext).also { instance = it }
        }
        private const val PREFS_NAME = "caption_style"
        private const val KEY_PRESET = "preset"
    }
}
