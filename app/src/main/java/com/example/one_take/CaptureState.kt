package com.example.one_take

/**
 * The states that drive the capture controls and recording timer.
 */
internal enum class CaptureUiState {
    Idle,
    Starting,
    Recording,
    Finalizing
}
