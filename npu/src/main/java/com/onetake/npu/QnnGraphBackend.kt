package com.onetake.npu

import android.content.Context
import java.io.File
import java.security.MessageDigest

/** Opens one verified QNN context binary without retaining the source file. */
class QnnGraphBackend(
    context: Context,
    private val binary: File,
    private val sha256: String,
) {
    private val context = context.applicationContext

    init {
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Expected a lowercase SHA-256 digest" }
    }

    /**
     * Copies and hashes the source before opening it. The runtime only sees the
     * private snapshot, so an installer replacing the active bundle cannot
     * change bytes after verification.
     */
    fun open(graphName: String? = null): QnnGraphSession {
        require(binary.isFile && binary.canRead()) { "QNN context binary is missing: ${binary.path}" }
        // Load a private snapshot so an artifact update cannot replace the verified bytes.
        val snapshot = File.createTempFile("qnn-verified-", ".bin", context.cacheDir)
        return try {
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
            QnnGraphSession.open(context, snapshot, graphName)
        } finally {
            snapshot.delete()
        }
    }
}
