package com.example.one_take.captions

import com.example.one_take.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptionPresetTest {
    @Test
    fun storedNamesAreCaseInsensitiveAndUnknownValuesUseClean() {
        assertEquals(CaptionPreset.CLEAN, CaptionPreset.fromStoredName(null))
        assertEquals(CaptionPreset.CLEAN, CaptionPreset.fromStoredName(""))
        assertEquals(CaptionPreset.BOLD, CaptionPreset.fromStoredName("bold"))
        assertEquals(CaptionPreset.MINIMAL, CaptionPreset.fromStoredName("  MINIMAL  "))
        assertEquals(CaptionPreset.CLEAN, CaptionPreset.fromStoredName("legacy"))
    }

    @Test
    fun presetsExposeSafeReadableStyleParameters() {
        CaptionPreset.entries.forEach { preset ->
            assertTrue(preset.labelResId != R.string.caption_preset_title)
            assertTrue(preset.textSizeFraction > 0f)
            assertTrue(preset.bottomAnchor in 0f..1f)
        }
        assertTrue(CaptionPreset.BOLD.bold)
        assertEquals(0x00000000, CaptionPreset.MINIMAL.backgroundColorArgb)
    }
}
