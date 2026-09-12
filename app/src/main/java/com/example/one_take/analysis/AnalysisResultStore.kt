package com.example.one_take.analysis

import com.onetake.engine.AnalysisResult
import java.io.File

/** Persists [AnalysisResult] beside the capture journal so the editor survives process death. Stub until #51. */
internal class AnalysisResultStore(private val directory: File) {
    fun write(result: AnalysisResult) = Unit
    fun read(sessionId: String): AnalysisResult? = null
}
