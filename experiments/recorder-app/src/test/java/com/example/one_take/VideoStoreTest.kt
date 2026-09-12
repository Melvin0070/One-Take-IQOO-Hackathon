package com.example.one_take

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VideoStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun completedRecordingIsListedAndCanBeDeleted() {
        val store = VideoStore(temporaryFolder.root)
        val pending = store.createPendingRecording()
        val output = pending.outputFile
        output.writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(store.listVideos().isEmpty())
        pending.complete()

        assertEquals(listOf(output), store.listVideos())
        assertTrue(store.deleteVideo(output))
        assertFalse(output.exists())
        assertTrue(store.listVideos().isEmpty())
    }

    @Test
    fun recoveryRetainsPlayableOutputAndRemovesInvalidPartial() {
        val store = VideoStore(temporaryFolder.root)
        val playable = createInterruptedOutput(store, "video_playable.mp4")
        val invalid = createInterruptedOutput(store, "video_invalid.mp4")

        val recovered = store.recoverPendingFiles { file -> file == playable }

        assertEquals(listOf(playable), recovered)
        assertTrue(playable.exists())
        assertFalse(File(store.directory, ".video_playable.mp4.pending").exists())
        assertFalse(invalid.exists())
        assertFalse(File(store.directory, ".video_invalid.mp4.pending").exists())
        assertEquals(listOf(playable), store.listVideos())
    }

    @Test
    fun activeRecordingIsSkippedWhileItsMarkerIsLocked() {
        val store = VideoStore(temporaryFolder.root)
        val pending = store.createPendingRecording()
        pending.outputFile.writeBytes(byteArrayOf(1, 2, 3))

        assertTrue(store.recoverPendingFiles { true }.isEmpty())
        assertTrue(pending.outputFile.exists())
        assertTrue(store.listVideos().isEmpty())

        pending.complete()
        assertEquals(listOf(pending.outputFile), store.listVideos())
    }

    @Test
    fun existingFinalizedMp4WithoutMarkerIsListed() {
        val store = VideoStore(temporaryFolder.root)
        assertTrue(store.directory.mkdirs() || store.directory.isDirectory)
        val legacyOutput = File(store.directory, "video_legacy.mp4")
        legacyOutput.writeBytes(byteArrayOf(1, 2, 3))

        assertEquals(listOf(legacyOutput), store.listVideos())
    }

    @Test
    fun deleteRejectsFileOutsideStoreDirectory() {
        val store = VideoStore(temporaryFolder.root)
        val outsideOutput = temporaryFolder.newFile("video_outside.mp4")

        assertFalse(store.deleteVideo(outsideOutput))
        assertTrue(outsideOutput.exists())
    }

    @Test
    fun recoveryPreservesPendingOutputWhenValidationThrows() {
        val store = VideoStore(temporaryFolder.root)
        val output = createInterruptedOutput(store, "video_unknown.mp4")

        store.recoverPendingFiles { throw IllegalStateException("metadata unavailable") }

        assertTrue(output.exists())
        assertTrue(File(store.directory, ".video_unknown.mp4.pending").exists())
        assertEquals(listOf(output), store.listVideos())
    }

    @Test
    fun freshlyInterruptedVideoIsRecoveredWithoutWaiting() {
        val store = VideoStore(temporaryFolder.root)
        val file = createInterruptedOutput(store, "video_fresh.mp4")
        File(store.directory, ".video_fresh.mp4.pending").setLastModified(System.currentTimeMillis())
        assertEquals(listOf(file), store.recoverPendingFiles { true })
    }

    @Test
    fun undecidableInterruptedVideoRemainsVisibleAndCanBeDeleted() {
        val store = VideoStore(temporaryFolder.root)
        val file = createInterruptedOutput(store, "video_unknown.mp4")
        store.recoverPendingFiles { null }
        assertEquals(listOf(file), store.listVideos())
        assertTrue(store.deleteVideo(file))
        assertFalse(file.exists())
        assertFalse(File(store.directory, ".video_unknown.mp4.pending").exists())
    }

    @Test
    fun activeRecordingCannotBeDeleted() {
        val store = VideoStore(temporaryFolder.root)
        val pending = store.createPendingRecording()
        pending.outputFile.writeBytes(byteArrayOf(1, 2, 3))
        assertFalse(store.deleteVideo(pending.outputFile))
        assertTrue(pending.outputFile.exists())
        pending.discard()
    }

    private fun createInterruptedOutput(store: VideoStore, name: String): File {
        val output = File(store.directory, name)
        assertTrue(store.directory.mkdirs() || store.directory.isDirectory)
        output.writeBytes(byteArrayOf(1, 2, 3))
        val marker = File(store.directory, ".$name.pending")
        assertTrue(marker.createNewFile())
        assertTrue(marker.setLastModified(System.currentTimeMillis() - 10_000L))
        return output
    }
}
