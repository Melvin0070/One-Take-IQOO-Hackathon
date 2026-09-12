package com.example.one_take.features

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ModelPackageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun productionModelMetadataIsPinned() {
        assertEquals("ggerganov/whisper.cpp", OfflineCaptionModel.REPOSITORY)
        assertEquals(40, OfflineCaptionModel.REVISION.length)
        assertEquals("ggml-tiny.bin", OfflineCaptionModel.FILE_NAME)
        assertEquals(77_691_713L, OfflineCaptionModel.EXPECTED_BYTES)
        assertEquals(64, OfflineCaptionModel.EXPECTED_SHA256.length)
        assertTrue(OfflineCaptionModel.downloadUrl.contains(OfflineCaptionModel.REVISION))
        assertTrue(OfflineCaptionModel.downloadUrl.endsWith("/${OfflineCaptionModel.FILE_NAME}?download=true"))
    }

    @Test
    fun syntheticPayloadPassesExactVerification() {
        val payload = byteArrayOf(0x4f, 0x6e, 0x65, 0x2d, 0x54, 0x61, 0x6b, 0x65)
        val file = temporaryFolder.newFile("model.bin")
        file.writeBytes(payload)
        val digest = OfflineCaptionModel.sha256(file)

        assertEquals("4ed33843a08b5954739697dda1df4f1580ff4c340fcf8814c4bad08c496d63e7", digest)
        assertTrue(
            OfflineCaptionModel.isVerifiedArtifact(
                file,
                expectedBytes = payload.size.toLong(),
                expectedSha256 = digest
            )
        )
    }

    @Test
    fun changedBytesOrLengthAreRejected() {
        val file = temporaryFolder.newFile("model.part")
        file.writeBytes(byteArrayOf(1, 2, 3))
        val digest = OfflineCaptionModel.sha256(file)

        assertFalse(
            OfflineCaptionModel.isVerifiedArtifact(
                file,
                expectedBytes = file.length() + 1L,
                expectedSha256 = digest
            )
        )
        assertFalse(
            OfflineCaptionModel.isVerifiedArtifact(
                file,
                expectedBytes = file.length(),
                expectedSha256 = "0".repeat(64)
            )
        )
    }

    @Test
    fun partialFileNameIsNotTheInstallTarget() {
        val partial = File(temporaryFolder.root, "${OfflineCaptionModel.FILE_NAME}.part")
        partial.writeBytes(byteArrayOf(1, 2, 3))
        val installed = File(temporaryFolder.root, OfflineCaptionModel.FILE_NAME)

        assertFalse(installed.exists())
        assertTrue(partial.exists())
    }
}
