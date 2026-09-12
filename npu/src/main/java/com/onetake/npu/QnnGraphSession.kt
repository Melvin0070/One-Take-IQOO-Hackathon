package com.onetake.npu

import android.content.Context
import android.os.Build
import java.io.Closeable
import java.io.File
import java.util.Collections

/**
 * Data types understood by the raw QNN graph boundary.
 *
 * The native session does not convert values. Callers must provide bytes in the
 * representation named by [QnnTensorSpec.dataType].
 */
enum class QnnDataType(
    internal val qnnCode: Int,
    internal val bitsPerElement: Int,
) {
    INT32(0x0032, 32),
    FLOAT16(0x0216, 16),
    FLOAT32(0x0232, 32),
    UNKNOWN(Int.MAX_VALUE, 0),
    ;

    companion object {
        private val byQnnCode = entries.associateBy(QnnDataType::qnnCode)

        internal fun fromQnnCode(code: Int): QnnDataType =
            byQnnCode[code] ?: UNKNOWN
    }
}

/** Immutable metadata for one graph input or output tensor. */
class QnnTensorSpec(
    val name: String,
    val dataType: QnnDataType,
    dimensions: List<Long>,
    val byteCount: Int,
) {
    val dimensions: List<Long> = Collections.unmodifiableList(dimensions.toList())

    constructor(
        name: String,
        dataType: QnnDataType,
        dimensions: List<Long>,
    ) : this(name, dataType, dimensions, checkedByteCount(dataType, dimensions))

    init {
        require(name.isNotBlank()) { "Tensor name must not be blank" }
        require(dimensions.all { it > 0L }) {
            "Tensor $name must have strictly positive dimensions"
        }
        require(dataType != QnnDataType.UNKNOWN) {
            "Tensor $name has an unknown QNN data type"
        }
        require(byteCount == checkedByteCount(dataType, dimensions)) {
            "Tensor $name byte count does not match its shape and data type"
        }
    }

    companion object {
        private fun checkedByteCount(dataType: QnnDataType, dimensions: List<Long>): Int {
            try {
                require(dataType != QnnDataType.UNKNOWN) { "Unknown QNN data type" }
                var elementCount = 1L
                for (dimension in dimensions) {
                    require(dimension > 0L) { "Tensor dimensions must be strictly positive" }
                    elementCount = Math.multiplyExact(elementCount, dimension)
                }
                val bitCount = Math.multiplyExact(elementCount, dataType.bitsPerElement.toLong())
                val byteCount = Math.addExact(bitCount, 7L) / 8L
                require(byteCount in 1L..Int.MAX_VALUE) {
                    "Tensor byte count is outside the supported raw buffer range"
                }
                return byteCount.toInt()
            } catch (error: ArithmeticException) {
                throw IllegalArgumentException("Tensor byte count overflows the supported range", error)
            }
        }
    }

    override fun equals(other: Any?): Boolean =
        other is QnnTensorSpec &&
            name == other.name &&
            dataType == other.dataType &&
            dimensions == other.dimensions &&
            byteCount == other.byteCount

    override fun hashCode(): Int =
        (((name.hashCode() * 31) + dataType.hashCode()) * 31 + dimensions.hashCode()) * 31 + byteCount

    override fun toString(): String =
        "QnnTensorSpec(name=$name, dataType=$dataType, dimensions=$dimensions, byteCount=$byteCount)"
}

/** Stable exception for unavailable, malformed, or failed strict HTP execution. */
class QnnGraphException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/** Validates a positional raw input list before crossing the JNI boundary. */
internal fun validateQnnInputs(specs: List<QnnTensorSpec>, inputBuffers: List<ByteArray>) {
    require(inputBuffers.size == specs.size) {
        "Expected ${specs.size} QNN graph inputs, got ${inputBuffers.size}"
    }
    specs.zip(inputBuffers).forEach { (spec, buffer) ->
        require(buffer.size == spec.byteCount) {
            "Input ${spec.name} requires ${spec.byteCount} bytes, got ${buffer.size}"
        }
    }
}

/** A serialized strict HTP session backed by one QNN context binary graph. */
class QnnGraphSession private constructor(
    private var nativeHandle: Long,
    val graphName: String,
    inputs: List<QnnTensorSpec>,
    outputs: List<QnnTensorSpec>,
) : Closeable {
    val inputs: List<QnnTensorSpec> = Collections.unmodifiableList(inputs.toList())
    val outputs: List<QnnTensorSpec> = Collections.unmodifiableList(outputs.toList())

    /** Executes one graph invocation with inputs in [inputs] order. */
    @Synchronized
    fun execute(inputBuffers: List<ByteArray>): List<ByteArray> {
        val handle = nativeHandle.takeIf { it != 0L }
            ?: throw QnnGraphException("QNN graph session is closed")
        validateQnnInputs(inputs, inputBuffers)
        return try {
            val nativeOutputs = QnnGraphNative.execute(handle, inputBuffers.toTypedArray())
            require(nativeOutputs.size == outputs.size) {
                "QNN graph returned ${nativeOutputs.size} outputs, expected ${outputs.size}"
            }
            nativeOutputs.forEachIndexed { index, buffer ->
                require(buffer.size == outputs[index].byteCount) {
                    "Output ${outputs[index].name} returned ${buffer.size} bytes, " +
                        "expected ${outputs[index].byteCount}"
                }
            }
            nativeOutputs.toList()
        } catch (error: Throwable) {
            try {
                close()
            } catch (cleanup: Throwable) {
                if (cleanup !== error) error.addSuppressed(cleanup)
            }
            throw error
        }
    }

    /**
     * Releases native resources at most once.
     *
     * The handle is invalidated before entering JNI so a release exception cannot
     * cause a second native free on a later close call.
     */
    @Synchronized
    override fun close() {
        val handle = nativeHandle
        if (handle == 0L) return
        nativeHandle = 0L
        QnnGraphNative.close(handle)
    }

    companion object {
        /** Opens one graph from a QNN HTP context binary. */
        fun open(context: Context, binaryFile: File, graphName: String? = null): QnnGraphSession {
            if (Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "SM8850" || Build.MODEL != "I2501") {
                throw QnnGraphException("QNN graph runtime is restricted to iQOO 15 I2501/SM8850")
            }
            require(binaryFile.isFile) { "QNN context binary does not exist: ${binaryFile.path}" }
            require(binaryFile.canRead()) { "QNN context binary is not readable: ${binaryFile.path}" }
            NativeLoader.ensureLoaded()
            val nativeDirectory = context.applicationInfo.nativeLibraryDir
            val canonicalBinary = try {
                binaryFile.canonicalFile
            } catch (error: Exception) {
                throw QnnGraphException("Unable to resolve QNN context binary path", error)
            }
            val handle = synchronized(QnnRuntimeAccessLock) {
                QnnGraphNative.open(nativeDirectory, canonicalBinary.path, graphName)
            }
            if (handle == 0L) {
                throw QnnGraphException("QNN graph runtime returned an invalid session handle")
            }
            return try {
                val metadata = QnnGraphNative.metadata(handle)
                QnnGraphSession(
                    nativeHandle = handle,
                    graphName = metadata.graphName,
                    inputs = metadata.inputs.map { it.toPublicSpec() },
                    outputs = metadata.outputs.map { it.toPublicSpec() },
                )
            } catch (error: Throwable) {
                try {
                    QnnGraphNative.close(handle)
                } catch (cleanup: Throwable) {
                    error.addSuppressed(cleanup)
                }
                throw error
            }
        }
    }
}

private object NativeLoader {
    @Volatile
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        try {
            System.loadLibrary("onetake_qnn_graph")
        } catch (error: UnsatisfiedLinkError) {
            throw QnnGraphException("QNN graph native runtime is unavailable", error)
        }
        loaded = true
    }
}

/** Serializes native calls that temporarily configure the process-wide DSP search path. */
internal object QnnRuntimeAccessLock

internal data class QnnNativeTensorSpec(
    val name: String,
    val dataTypeCode: Int,
    val dimensions: LongArray,
    val byteCount: Int,
) {
    fun toPublicSpec(): QnnTensorSpec = QnnTensorSpec(
        name = name,
        dataType = QnnDataType.fromQnnCode(dataTypeCode).also {
            if (it == QnnDataType.UNKNOWN) {
                throw QnnGraphException("QNN graph returned unsupported tensor data type $dataTypeCode")
            }
        },
        dimensions = dimensions.toList(),
        byteCount = byteCount,
    )
}

internal data class QnnNativeGraphMetadata(
    val graphName: String,
    val inputs: Array<QnnNativeTensorSpec>,
    val outputs: Array<QnnNativeTensorSpec>,
)

private object QnnGraphNative {
    external fun open(nativeDirectory: String, binaryPath: String, graphName: String?): Long

    external fun metadata(handle: Long): QnnNativeGraphMetadata

    external fun execute(handle: Long, inputs: Array<ByteArray>): Array<ByteArray>

    external fun close(handle: Long)
}
