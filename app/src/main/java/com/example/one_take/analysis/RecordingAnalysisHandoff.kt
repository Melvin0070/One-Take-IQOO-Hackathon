package com.example.one_take.analysis

import android.content.Context
import android.util.Log
import com.example.one_take.engine.EngineProjectStore
import com.onetake.engine.AnalysisResult
import com.onetake.engine.RecordingSessionSnapshot
import com.onetake.engine.SessionAnalysis
import java.io.File
import kotlinx.coroutines.*

/** Stop still opens ReviewScreen after analysis; the timeline editor (#64) replaces it when this flips. */
internal const val REVIEW_SCREEN_AFTER_RECORDING = true

/**
 * Analyses a finalized take from its project ledger and stores the result for the editor.
 * Navigation waits at most [timeoutMs]; the work itself is application-scoped and keeps running.
 */
internal class RecordingAnalysisHandoff(
    private val readSession: (File) -> RecordingSessionSnapshot?,
    private val write: (AnalysisResult) -> Unit,
    private val scope: CoroutineScope,
    private val analyze: (RecordingSessionSnapshot, Long?) -> AnalysisResult = SessionAnalysis::analyze,
    private val timeoutMs: Long = 10_000L,
    private val onFailure: (Throwable) -> Unit = { Log.e("RecordingAnalysis", "Post-recording analysis failed", it) },
) {
    /** Returns null for legacy recordings without a session header, on failure, or on timeout. */
    suspend fun run(source: File, liveToMediaOffsetSamples: Long? = null): AnalysisResult? {
        val work = scope.async {
            // Caught here so a failure is still reported after the caller stopped waiting.
            try { readSession(source)?.let { analyze(it, liveToMediaOffsetSamples).also(write) } }
            catch (exception: Exception) { onFailure(exception); null }
        }
        return withTimeoutOrNull(timeoutMs) { work.await() }
    }

    companion object {
        @Volatile private var instance: RecordingAnalysisHandoff? = null
        fun get(context: Context): RecordingAnalysisHandoff = instance ?: synchronized(this) {
            instance ?: context.applicationContext.let { app ->
                val projects = EngineProjectStore(app)
                val results = AnalysisResultStore(File(app.noBackupFilesDir, "session_analysis"))
                RecordingAnalysisHandoff(projects::session, results::write, CoroutineScope(SupervisorJob() + Dispatchers.IO))
            }.also { instance = it }
        }
    }
}
