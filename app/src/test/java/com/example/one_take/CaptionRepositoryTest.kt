package com.example.one_take

import com.example.one_take.captions.CaptionRepository
import com.example.one_take.captions.CaptionSegment
import com.example.one_take.captions.CaptionWord
import java.io.File
import java.nio.charset.StandardCharsets
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class CaptionRepositoryTest {
    private lateinit var temporaryDirectory: File
    private lateinit var source: File
    private lateinit var repository: CaptionRepository

    @Before
    fun setUp() {
        temporaryDirectory = createTemporaryDirectory()
        source = File(temporaryDirectory, "video_1.mp4").also {
            it.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        repository = CaptionRepository(File(temporaryDirectory, "metadata"))
    }

    @After
    fun tearDown() {
        temporaryDirectory.deleteRecursively()
    }

    @Test
    fun writeAndReadPreservesTimedUnicodeCaptions() {
        val expected = listOf(
            CaptionSegment(
                0L,
                1_200L,
                "Hello, नमस्ते",
                listOf(
                    CaptionWord(100L, 320L, "Hello,", 0.94f),
                    CaptionWord(340L, 1_000L, "नमस्ते", 0.87f),
                ),
            ),
            CaptionSegment(1_300L, 2_700L, "This is a Hinglish line")
        )

        repository.write(source, expected)

        assertEquals(expected, repository.read(source))
        assertEquals(
            listOf("video_1.mp4.captions.json"),
            File(temporaryDirectory, "metadata").list()?.toList()
        )
    }

    @Test
    fun legacyCaptionMetadataWithoutWordsStillReads() {
        val metadataDirectory = File(temporaryDirectory, "metadata").also { it.mkdirs() }
        File(metadataDirectory, "video_1.mp4.captions.json").writeText(
            """
            {
              "schemaVersion": 1,
              "source": {
                "name": "video_1.mp4",
                "size": ${source.length()},
                "lastModified": ${source.lastModified()}
              },
              "segments": [{"startMs": 0, "endMs": 900, "text": "legacy caption"}]
            }
            """.trimIndent(),
            StandardCharsets.UTF_8,
        )

        assertEquals(
            listOf(CaptionSegment(0L, 900L, "legacy caption")),
            repository.read(source),
        )
    }

    @Test
    fun invalidWordTimingIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.write(
                source,
                listOf(
                    CaptionSegment(
                        0L,
                        900L,
                        "caption",
                        listOf(CaptionWord(400L, 950L, "caption", 0.8f)),
                    )
                ),
            )
        }
    }

    @Test
    fun sameSizeAndTimestampReplacementRejectsNewCaptionMetadata() {
        repository.write(source, listOf(CaptionSegment(0L, 900L, "original")))
        val modified = source.lastModified()
        source.writeBytes(byteArrayOf(4, 3, 2, 1))
        check(source.setLastModified(modified))
        assertNull(repository.read(source))
    }

    @Test
    fun missingOrMalformedMetadataReturnsNull() {
        assertNull(repository.read(source))
        val metadataDirectory = File(temporaryDirectory, "metadata").also { it.mkdirs() }
        File(metadataDirectory, "video_1.mp4.captions.json").writeText("{broken", StandardCharsets.UTF_8)

        assertNull(repository.read(source))
    }

    @Test
    fun changedSourceInvalidatesPreviouslySavedCaptions() {
        val captions = listOf(CaptionSegment(0L, 900L, "original"))
        repository.write(source, captions)
        assertEquals(captions, repository.read(source))

        source.appendText("changed")

        assertNull(repository.read(source))
    }

    @Test
    fun overlappingOrEmptySegmentsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.write(
                source,
                listOf(
                    CaptionSegment(0L, 1_000L, "first"),
                    CaptionSegment(500L, 1_500L, "overlap")
                )
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            repository.write(source, listOf(CaptionSegment(0L, 100L, "   ")))
        }
    }

    private fun createTemporaryDirectory(): File {
        val marker = File.createTempFile("caption-repository-", ".tmp")
        check(marker.delete())
        check(marker.mkdirs())
        return marker
    }
}
