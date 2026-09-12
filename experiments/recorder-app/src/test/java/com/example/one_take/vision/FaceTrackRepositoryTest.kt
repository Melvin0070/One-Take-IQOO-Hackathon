package com.example.one_take.vision

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FaceTrackRepositoryTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun readRejectsSidecarForReplacedSource() {
        val source = sourceFile("video_1.mp4", byteArrayOf(1, 2, 3))
        sidecar(source).writeText(
            """
            {"sourceLength":3,"sourceModified":${source.lastModified()},"samples":[{"timeMs":25,"centerX":0.5,"centerY":0.5,"faceWidth":0.3}]}
            """.trimIndent()
        )

        source.writeBytes(byteArrayOf(1, 2, 3, 4))

        assertNull(FaceTrackRepository(temporaryFolder.root).read(source))
    }

    @Test
    fun pruneMissingSourcesRemovesOnlyOrphanedSidecars() {
        val existing = sourceFile("video_existing.mp4", byteArrayOf(1))
        val existingSidecar = sidecar(existing).apply { writeText("{}") }
        val orphan = File(temporaryFolder.root, ".video_missing.mp4.faces.json")
            .apply { writeText("{}") }

        val removed = FaceTrackRepository(temporaryFolder.root).pruneMissingSources()

        assertEquals(1, removed)
        assertTrue(existingSidecar.exists())
        assertTrue(!orphan.exists())
    }

    private fun sourceFile(name: String, bytes: ByteArray): File {
        val directory = temporaryFolder.root
        assertTrue(directory.isDirectory)
        return File(directory, name).apply { writeBytes(bytes) }
    }

    private fun sidecar(source: File): File {
        return File(source.parentFile, ".${source.name}.faces.json")
    }
}
