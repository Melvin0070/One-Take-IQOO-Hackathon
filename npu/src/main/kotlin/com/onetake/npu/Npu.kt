package com.onetake.npu

import com.onetake.engine.VisionFrame
import com.onetake.engine.VisionSignal
import kotlinx.coroutines.flow.Flow

/**
 * LiteRT + the Qualcomm accelerator. The only module in the repo that links QNN.
 * Read npu/AGENTS.md and docs/agents/landmines.md L6-L8 first.
 */

/**
 * Face-Det-Lite behind `VisionSignal`.
 *
 * Input is [1, 480, 640, 1] uint8 GRAYSCALE, not RGB. The AI Hub page says "480x640";
 * the export's metadata.json says color_format: "grayscale". That mistake costs a
 * morning and presents as a model that runs fine and detects nothing.
 */
class NpuVisionSignal(
    private val modelPath: String,
) : VisionSignal {

    override val frames: Flow<VisionFrame>
        get() = TODO("Lane D: Face-Det-Lite through LiteRT. See npu/AGENTS.md")
}

/**
 * R9's only honest route.
 *
 * Asking for NPU alone SILENTLY appends CPU — LiteRT's own Kotlin source does this when
 * `accelerators.size == 1`. Every other check lies too: getAvailableAccelerators()
 * reports registration rather than plugin health (it returned [NPU, GPU, CPU] on a device
 * where the Qualcomm plugin had failed to dlopen), isDeviceSupported() is a
 * Build.SOC_MANUFACTURER string comparison, and no accelerator getter exists in Kotlin.
 *
 * This is the exact mechanism that made earlier teams in this hackathon series ship on
 * CPU while believing otherwise. So: force the set to reach native as NPU-only, and let
 * `create` throw.
 *
 *     CompiledModel.Options(setOf(Accelerator.NPU, Accelerator.NONE))
 *
 * size == 2, so the auto-CPU-append does not fire; NONE maps to a JNI no-op, so native
 * receives {NPU} alone; create() then throws when delegation is not total.
 *
 * THE KOTLIN ROUTE TO THIS IS UNDOCUMENTED. Confirm it on-device before relying on it,
 * and corroborate with `adb logcat -s litert:V tflite:V` — the string
 * "TfLiteXNNPackDelegate" in the log means you are on CPU.
 */
object NpuProof {

    /**
     * Runs at app start and is allowed to fail loudly to a red banner. Failing visibly
     * on a laptop tonight beats failing silently at 0.195 ms -> 146 ms on stage.
     */
    fun selfTest(modelPath: String): Result = TODO("Lane D: see npu/AGENTS.md")

    /**
     * A deliberate CPU fallback uses a DIFFERENT CompiledModel instance and a VISIBLY
     * DIFFERENT label. Never a fallback that reuses the "NPU" label — that single rule
     * is what this module exists to protect.
     */
    data class Result(
        val delegatedToNpu: Boolean,
        val processor: String,
        val socModel: String,
        val perInferenceMicros: Long,
        val message: String,
    )
}

/**
 * What the on-stage engine-stats overlay reads. R9 reports a per-inference time and a
 * COUNT, never a latency SLA — and if the count is zero, [thermalStatus] names why.
 * "Never zero" was a promise about hardware we do not control.
 */
data class EngineStats(
    val model: String,
    val processor: String,
    val perInferenceMicros: Long,
    val inferenceCount: Long,
    val thermalStatus: String,
    val configVersion: String,
    val flagVectorChecksum: String,
)
