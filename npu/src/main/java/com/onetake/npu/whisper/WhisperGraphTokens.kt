package com.onetake.npu.whisper

import com.onetake.npu.QnnGraphSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CancellationException

/** One utterance's autoregressive cache, owned by a serialized transcriber. */
internal class WhisperQnnGraphTokens(
    private val decoder: QnnGraphSession,
    private val crossCache: Map<String, ByteArray>,
    private val isCancelled: () -> Boolean,
) {
    private var nextPosition = 0
    var callCount: Int = 0
        private set
    private var selfCache = buildMap {
        repeat(4) { layer ->
            put("k_cache_self_${layer}_in", ByteArray(6 * 64 * 199 * 2))
            put("v_cache_self_${layer}_in", ByteArray(6 * 64 * 199 * 2))
        }
    }

    fun next(token: Int, position: Int): FloatArray {
        checkCancelled(isCancelled)
        require(position == nextPosition && position in 0 until 200) { "Invalid Whisper cache position" }
        require(token in 0 until 51865) { "Invalid Whisper token" }
        val inputs = LinkedHashMap<String, ByteArray>(19)
        inputs.putAll(crossCache)
        inputs.putAll(selfCache)
        inputs["input_ids"] = intBytes(token)
        inputs["position_ids"] = intBytes(position)
        inputs["attention_mask"] = halfBytes(FloatArray(200) { if (it >= 199 - position) 0f else -100f })
        val output = decoder.execute(
            decoder.inputs.map { spec -> inputs[spec.name] ?:
                error("Whisper decoder input is missing: ${spec.name}") },
        ).let { values ->
            decoder.outputs.mapIndexed { index, spec -> spec.name to values[index] }.toMap()
        }
        checkCancelled(isCancelled)
        val logits = output.getValue("logits")
        check(logits.size == 51865 * 2) { "Unexpected Whisper vocabulary shape" }
        val nextCache = buildMap {
            repeat(4) { layer ->
                for (kind in listOf("k", "v")) {
                    val bytes = output.getValue("${kind}_cache_self_${layer}_out")
                    check(bytes.size == 6 * 64 * 199 * 2) { "Unexpected Whisper cache shape" }
                    put("${kind}_cache_self_${layer}_in", bytes)
                }
            }
        }
        val values = halfFloats(logits)
        check(values.all { it.isFinite() }) { "Whisper produced nonfinite logits" }
        selfCache = nextCache
        nextPosition++
        callCount++
        return values
    }
}

internal fun checkCancelled(isCancelled: () -> Boolean) {
    if (isCancelled()) throw CancellationException("Whisper transcription cancelled")
}

internal fun halfBytes(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 2)
    .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach { putShort(floatToHalfBits(it)) } }.array()

internal fun halfFloats(bytes: ByteArray): FloatArray {
    require(bytes.size % 2 == 0)
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    return FloatArray(bytes.size / 2) { halfBitsToFloat(buffer.short) }
}

private fun intBytes(value: Int): ByteArray = ByteBuffer.allocate(4)
    .order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()

/** IEEE 754 binary16 conversion, with round-to-nearest ties-to-even. */
private fun floatToHalfBits(value: Float): Short {
    val bits = value.toRawBits()
    val sign = (bits ushr 16) and 0x8000
    val exponent = (bits ushr 23) and 0xff
    val fraction = bits and 0x7fffff
    if (exponent == 0xff) return (sign or 0x7c00 or if (fraction == 0) 0 else 0x200).toShort()
    val adjusted = exponent - 127 + 15
    if (adjusted >= 31) return (sign or 0x7c00).toShort()
    if (adjusted <= 0) {
        if (adjusted < -10) return sign.toShort()
        val mantissa = fraction or 0x800000
        val shift = 14 - adjusted
        val truncated = mantissa ushr shift
        val remainder = mantissa and ((1 shl shift) - 1)
        val midpoint = 1 shl (shift - 1)
        val rounded = truncated + if (remainder > midpoint ||
            (remainder == midpoint && truncated and 1 != 0)) 1 else 0
        return (sign or rounded).toShort()
    }
    val rounded = fraction + 0xfff + ((fraction ushr 13) and 1)
    return (sign or ((adjusted shl 10) + (rounded ushr 13))).toShort()
}

private fun halfBitsToFloat(value: Short): Float {
    val bits = value.toInt() and 0xffff
    val sign = (bits and 0x8000) shl 16
    var exponent = (bits ushr 10) and 0x1f
    var fraction = bits and 0x3ff
    if (exponent == 0) {
        if (fraction == 0) return Float.fromBits(sign)
        exponent = 1
        while (fraction and 0x400 == 0) {
            fraction = fraction shl 1
            exponent--
        }
        fraction = fraction and 0x3ff
    } else if (exponent == 31) {
        return Float.fromBits(sign or 0x7f800000 or (fraction shl 13))
    }
    return Float.fromBits(sign or ((exponent + 112) shl 23) or (fraction shl 13))
}
