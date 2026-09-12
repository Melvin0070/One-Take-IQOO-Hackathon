package com.example.one_take.editing

import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EditRepositoryTest {
    private lateinit var temporaryDirectory: File
    private lateinit var source: File
    private lateinit var repository: EditRepository

    @Before
    fun setUp() {
        temporaryDirectory = createTemporaryDirectory()
        source = File(temporaryDirectory, "video_1.mp4").also { it.writeBytes(byteArrayOf(1, 2, 3, 4)) }
        repository = EditRepository(File(temporaryDirectory, "metadata"))
    }

    @After
    fun tearDown() {
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun writeAndReadUsesHashedMetadataAndPreservesDecision() {
        val expected = EditDecision(
            durationMs = 4_000L,
            cuts = listOf(
                EditCut("pause", 1_000L, 2_000L, "silence"),
                EditCut("manual", 2_500L, 3_000L, "manual", enabled = false),
            ),
        )

        repository.write(source, expected)

        assertEquals(expected, repository.read(source))
        val metadataFiles = File(temporaryDirectory, "metadata").listFiles()!!.toList()
        assertEquals(1, metadataFiles.size)
        assertTrue(metadataFiles.single().name.endsWith(".edits.json"))
        assertFalse(metadataFiles.single().name.startsWith(source.name))
    }

    @Test
    fun replacementWithSameSizeAndTimestampRejectsOldCuts() {
        repository.write(source, EditDecision(4_000L, listOf(EditCut("pause", 1_000L, 2_000L, "silence"))))
        val modified = source.lastModified()
        source.writeBytes(byteArrayOf(4, 3, 2, 1))
        assertTrue(source.setLastModified(modified))
        assertNull(repository.read(source))
    }

    @Test
    fun malformedOrStaleMetadataReturnsNull() {
        val expected = EditDecision(4_000L, listOf(EditCut("pause", 1_000L, 2_000L, "silence")))
        repository.write(source, expected)
        val metadata = File(temporaryDirectory, "metadata").listFiles()!!.single()

        metadata.writeText("{broken", StandardCharsets.UTF_8)
        assertNull(repository.read(source))

        repository.write(source, expected)
        source.appendText("changed")
        assertNull(repository.read(source))
    }

    @Test
    fun removeAndPruneDeleteOnlyMetadataForMissingSources() {
        val decision = EditDecision(4_000L, listOf(EditCut("pause", 1_000L, 2_000L, "silence")))
        repository.write(source, decision)
        assertTrue(repository.remove(source))
        assertTrue(File(temporaryDirectory, "metadata").listFiles().orEmpty().isEmpty())

        repository.write(source, decision)
        source.delete()
        repository.pruneMissingSources(temporaryDirectory)
        assertTrue(File(temporaryDirectory, "metadata").listFiles().orEmpty().isEmpty())
    }

    private fun createTemporaryDirectory(): File {
        val marker = File.createTempFile("edit-repository-", ".tmp")
        check(marker.delete())
        check(marker.mkdirs())
        return marker
    }
}
