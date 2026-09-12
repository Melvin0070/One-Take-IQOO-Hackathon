package com.example.one_take.captions

internal data class CaptionSegment(
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val words: List<CaptionWord> = emptyList(),
)
