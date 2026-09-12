package com.example.one_take

import android.content.Context
import androidx.core.content.edit

/** Navigation input for camera/session wiring; independent of the engine event schema. */
internal enum class RecordingMode { Script, Assisted }

internal class RecordingSetupStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("recording_setup", Context.MODE_PRIVATE)

    var draft: String
        get() = preferences.getString("draft", "").orEmpty()
        set(value) { preferences.edit { putString("draft", value) } }

    val script: String get() = preferences.getString("script", "").orEmpty()

    /** Save camera input and clear the draft in one durable transaction, off the UI thread. */
    @Suppress("UseKtx") // The commit result is needed to keep entry open after a failed disk write.
    fun acceptScript(text: String): Boolean {
        require(text.isNotBlank())
        return preferences.edit().putString("script", text.trim()).remove("draft").commit()
    }
}

internal fun scriptWordCount(text: String): Int = text.splitToSequence(Regex("\\s+"))
    .count { it.isNotBlank() }
