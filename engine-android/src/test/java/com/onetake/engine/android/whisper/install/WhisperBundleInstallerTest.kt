package com.onetake.engine.android.whisper.install

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assert.assertThrows

class WhisperBundleInstallerTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("onetake-whisper-install-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun installsVerifiedArchiveAndResolvesActiveDirectory() {
        val payloads = payloads()
        val archive = createArchive(payloads)
        val installer = WhisperBundleInstaller(root, specFor(archive, payloads))

        val installed = installer.install(archive)

        assertTrue(installed.isDirectory)
        assertEquals(installed.canonicalFile, installer.resolveInstalled()?.canonicalFile)
        payloads.forEach { (name, payload) ->
            assertEquals(payload.toList(), File(installed, name).readBytes().toList())
        }
        assertTrue(File(root, "active").isFile)
    }

    @Test
    fun reinstallingSameVerifiedArchiveReusesActiveDirectory() {
        val payloads = payloads()
        val archive = createArchive(payloads)
        val installer = WhisperBundleInstaller(root, specFor(archive, payloads))

        val first = installer.install(archive).canonicalFile
        val second = installer.install(archive).canonicalFile

        assertEquals(first, second)
        assertEquals(1, root.listFiles().orEmpty().count { it.name.startsWith("v-") })
    }

    @Test
    fun corruptArchiveLeavesPreviousActiveBundleUntouched() {
        val payloads = payloads()
        val archive = createArchive(payloads)
        val installer = WhisperBundleInstaller(root, specFor(archive, payloads))
        val previous = installer.install(archive).canonicalFile
        val corrupt = File(root.parentFile, "${root.name}-corrupt.zip")
        corrupt.deleteOnExit()
        archive.copyTo(corrupt, overwrite = true)
        RandomAccessFile(corrupt, "rw").use { file ->
            file.seek(file.length() - 1L)
            file.writeByte(file.readByte().toInt() xor 0x01)
        }

        assertThrows(WhisperBundleInstallException::class.java) {
            installer.install(corrupt)
        }

        assertEquals(previous, installer.resolveInstalled()?.canonicalFile)
        assertTrue(previous.isDirectory)
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".staging-") })
    }

    @Test
    fun cancellationBeforeExtractionLeavesPreviousActiveBundleUntouched() {
        val payloads = payloads()
        val archive = createArchive(payloads)
        val installer = WhisperBundleInstaller(root, specFor(archive, payloads))
        val previous = installer.install(archive).canonicalFile

        assertThrows(WhisperBundleInstallException::class.java) {
            installer.install(archive, WhisperInstallCancellation { true })
        }

        assertEquals(previous, installer.resolveInstalled()?.canonicalFile)
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".staging-") })
    }

    @Test
    fun rejectsTraversalEntryBeforePublishingFiles() {
        val payloads = payloads()
        val archive = createArchive(payloads, extraEntries = listOf("../outside.bin" to byteArrayOf(1)))
        val installer = WhisperBundleInstaller(root, specFor(archive, payloads))

        assertThrows(WhisperBundleInstallException::class.java) { installer.install(archive) }

        assertNull(installer.resolveInstalled())
        assertFalse(File(root.parentFile, "outside.bin").exists())
        assertFalse(root.listFiles().orEmpty().any { it.name.startsWith(".staging-") })
    }

    @Test
    fun rejectsUnsupportedEntryAndKeepsNoActivePointer() {
        val payloads = payloads()
        val archive = createArchive(
            payloads,
            extraEntries = listOf("${WhisperBundleSpec.ARCHIVE_ROOT}unsupported.bin" to byteArrayOf(1)),
        )
        val installer = WhisperBundleInstaller(root, specFor(archive, payloads))

        assertThrows(WhisperBundleInstallException::class.java) { installer.install(archive) }

        assertNull(installer.resolveInstalled())
    }

    @Test
    fun rejectsOversizedAndIncompleteArchives() {
        val original = payloads()
        val oversized = original.toMutableMap().apply {
            this[WhisperBundleSpec.ENCODER_NAME] = byteArrayOf(1, 2, 3, 4)
        }
        val oversizedArchive = createArchive(oversized)
        val oversizedInstaller = WhisperBundleInstaller(root, specFor(oversizedArchive, original))
        assertThrows(WhisperBundleInstallException::class.java) {
            oversizedInstaller.install(oversizedArchive)
        }

        val incomplete = original - WhisperBundleSpec.VOCABULARY_NAME
        val incompleteArchive = createArchive(incomplete)
        val incompleteInstaller = WhisperBundleInstaller(
            File(root, "incomplete"),
            specFor(incompleteArchive, original),
        )
        assertThrows(WhisperBundleInstallException::class.java) {
            incompleteInstaller.install(incompleteArchive)
        }
        assertNull(incompleteInstaller.resolveInstalled())
    }

    @Test
    fun invalidPointerDoesNotExposeUnverifiedDirectory() {
        val payloads = payloads()
        val archive = createArchive(payloads)
        val installer = WhisperBundleInstaller(root, specFor(archive, payloads))
        installer.install(archive)

        File(root, "active").writeText("../outside\n")

        assertNull(installer.resolveInstalled())
    }

    private fun payloads(): Map<String, ByteArray> = linkedMapOf(
        WhisperBundleSpec.ENCODER_NAME to byteArrayOf(1, 2, 3),
        WhisperBundleSpec.DECODER_NAME to byteArrayOf(4, 5, 6, 7),
        WhisperBundleSpec.VOCABULARY_NAME to byteArrayOf(8, 9),
        WhisperBundleSpec.METADATA_NAME to byteArrayOf(10, 11, 12, 13, 14),
        WhisperBundleSpec.CONFIG_NAME to byteArrayOf(15),
    )

    private fun createArchive(
        payloads: Map<String, ByteArray>,
        extraEntries: List<Pair<String, ByteArray>> = emptyList(),
    ): File {
        val archive = File(root, "archive-${UUID.randomUUID()}.zip")
        ZipOutputStream(FileOutputStream(archive)).use { zip ->
            zip.putNextEntry(ZipEntry(WhisperBundleSpec.ARCHIVE_ROOT))
            zip.closeEntry()
            payloads.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(WhisperBundleSpec.ARCHIVE_ROOT + name))
                zip.write(bytes)
                zip.closeEntry()
            }
            extraEntries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return archive
    }

    private fun specFor(archive: File, payloads: Map<String, ByteArray>): InternalWhisperBundleSpec {
        val artifacts = payloads.map { (name, bytes) ->
            InternalWhisperBundleSpec.Artifact(name, bytes.size.toLong(), sha256(bytes))
        }
        return InternalWhisperBundleSpec(
            archiveSizeBytes = archive.length(),
            archiveSha256 = sha256(archive),
            artifacts = artifacts,
            artifactByArchivePath = artifacts.associateBy { WhisperBundleSpec.ARCHIVE_ROOT + it.name },
        )
    }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
