package com.example.one_take

import java.io.File
import java.security.MessageDigest

/** Content identity for metadata; callers perform file I/O off the UI thread. */
internal fun recordingFingerprint(source: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    source.inputStream().buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
