package com.example.one_take.inference

import com.onetake.engine.inference.BackendKind
import com.onetake.engine.inference.BackendPolicy
import com.onetake.engine.inference.ExecutionReport
import com.onetake.engine.inference.InferenceBackend
import com.onetake.engine.inference.InferenceEngine
import com.onetake.engine.inference.InferenceReportListener
import com.onetake.engine.inference.InferenceSession
import com.onetake.engine.inference.ModelSpec
import java.util.ArrayDeque
import java.util.Collections
import java.util.LinkedHashMap

/** The model identities used by the recorder's production inference adapters. */
enum class AppInferenceModel(val id: String) {
    WHISPER_TINY("whisper-tiny"),
    SILERO_VAD("silero-vad"),
    FACE_LANDMARKER("face-landmarker"),
}

/**
 * The model registry is deliberately conservative.
 *
 * The iQOO 15 QNN runtime is probed independently, but no converted artifact
 * has passed model quality and on-device validation yet.  A device capability
 * therefore cannot make one of these models an NPU model by itself.
 */
object AppInferenceModelRegistry {
    val whisperTiny: ModelSpec = spec(AppInferenceModel.WHISPER_TINY)
    val sileroVad: ModelSpec = spec(AppInferenceModel.SILERO_VAD)
    val faceLandmarker: ModelSpec = spec(AppInferenceModel.FACE_LANDMARKER)

    fun spec(model: AppInferenceModel): ModelSpec = ModelSpec(
        id = model.id,
        validatedBackends = setOf(BackendKind.CPU),
        unavailableReasons = mapOf(
            BackendKind.NPU to com.onetake.engine.inference.CapabilityReason.NPU_MODEL_UNAVAILABLE,
        ),
    )
}

/**
 * Keeps a small, thread-safe report history for each model.
 *
 * Reports contain model and backend metadata only.  This store intentionally
 * has no logging side effects, so a live face stream cannot produce log spam.
 */
class InferenceDiagnostics(
    private val maxReportsPerModel: Int = DEFAULT_MAX_REPORTS_PER_MODEL,
    private val maxTrackedModels: Int = DEFAULT_MAX_TRACKED_MODELS,
) :
    InferenceReportListener {

    private val lock = Any()
    private val reportsByModel = LinkedHashMap<String, ArrayDeque<ExecutionReport>>()

    init {
        require(maxReportsPerModel > 0) { "maxReportsPerModel must be positive" }
        require(maxTrackedModels > 0) { "maxTrackedModels must be positive" }
    }

    override fun onReport(report: ExecutionReport) {
        synchronized(lock) {
            if (!reportsByModel.containsKey(report.modelId) && reportsByModel.size >= maxTrackedModels) {
                reportsByModel.remove(reportsByModel.keys.first())
            }
            val reports = reportsByModel.getOrPut(report.modelId) { ArrayDeque() }
            reports.addLast(report)
            while (reports.size > maxReportsPerModel) {
                reports.removeFirst()
            }
        }
    }

    fun latest(model: AppInferenceModel): ExecutionReport? = latest(model.id)

    fun latest(modelId: String): ExecutionReport? = synchronized(lock) {
        reportsByModel[modelId]?.peekLast()
    }

    fun recent(model: AppInferenceModel): List<ExecutionReport> = recent(model.id)

    fun recent(modelId: String): List<ExecutionReport> = synchronized(lock) {
        reportsByModel[modelId]?.toList().orEmpty()
    }

    fun snapshot(): Map<String, List<ExecutionReport>> = synchronized(lock) {
        Collections.unmodifiableMap(
            reportsByModel.mapValues { (_, reports) -> reports.toList() },
        )
    }

    fun clear() = synchronized(lock) {
        reportsByModel.clear()
    }

    companion object {
        const val DEFAULT_MAX_REPORTS_PER_MODEL: Int = 8
        const val DEFAULT_MAX_TRACKED_MODELS: Int = 3
    }
}

/** Shared app diagnostics for the three registered model identities. */
object AppInferenceRuntime {
    val diagnostics: InferenceDiagnostics = InferenceDiagnostics()
}

/**
 * Opens a model session around one actual implementation and its lifecycle.
 *
 * Only the CPU adapter is registered until a model-specific NPU artifact has
 * been validated.  The engine still owns policy selection and reports the
 * explicit NPU_MODEL_UNAVAILABLE capability from the registry.
 */
internal object AppInferenceSessions {
    fun <I, O> open(
        model: AppInferenceModel,
        policy: BackendPolicy,
        diagnostics: InferenceDiagnostics,
        prepareSession: (ModelSpec) -> InferenceSession<I, O>,
    ): InferenceSession<I, O> {
        val cpuBackend = object : InferenceBackend<I, O> {
            override val backend: BackendKind = BackendKind.CPU

            override fun prepare(model: ModelSpec): InferenceSession<I, O> = prepareSession(model)
        }
        return InferenceEngine(
            backends = listOf(cpuBackend),
            defaultPolicy = policy,
            listener = diagnostics,
        ).open(AppInferenceModelRegistry.spec(model), policy)
    }
}
