package com.onetake.engine.android

import android.content.Context
import com.onetake.engine.inference.BackendKind
import com.onetake.engine.inference.InferenceBackend
import com.onetake.engine.inference.InferenceSession
import com.onetake.engine.inference.ModelSpec
import java.io.File
import java.security.MessageDigest

/** A pinned context binary behind the engine's selection, reporting and failure lifecycle. */
class QnnGraphBackend(
    context: Context,
    private val expectedModel: ModelSpec,
    private val binary: File,
    private val sha256: String,
) : InferenceBackend<Map<String, ByteArray>, Map<String, ByteArray>> {
    private val context = context.applicationContext
    override val backend = BackendKind.NPU

    init {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Expected a lowercase SHA-256 digest" }
    }

    override fun prepare(model: ModelSpec): InferenceSession<Map<String, ByteArray>, Map<String, ByteArray>> {
        require(model == expectedModel && model.isValidated(BackendKind.NPU)) {
            "The graph artifact is not registered for this validated model"
        }
        // Load a private snapshot so an artifact update cannot replace the verified bytes.
        val snapshot = File.createTempFile("qnn-verified-", ".bin", context.cacheDir)
        val graph = try {
            val digest = MessageDigest.getInstance("SHA-256")
            binary.inputStream().use { input ->
                snapshot.outputStream().use { output ->
                    val buffer = ByteArray(1024 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            require(actual == sha256) { "QNN context binary digest mismatch" }
            QnnGraphSession.open(context, snapshot)
        } finally {
            snapshot.delete()
        }
        return object : InferenceSession<Map<String, ByteArray>, Map<String, ByteArray>> {
            override val model = expectedModel
            override val backend = BackendKind.NPU
            override fun execute(input: Map<String, ByteArray>): Map<String, ByteArray> {
                require(input.keys == graph.inputs.map { it.name }.toSet()) { "QNN input names do not match the graph" }
                val output = graph.execute(graph.inputs.map { input.getValue(it.name) })
                return graph.outputs.mapIndexed { index, spec -> spec.name to output[index] }.toMap()
            }
            override fun close() = graph.close()
        }
    }
}
