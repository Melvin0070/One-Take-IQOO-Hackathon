package com.example.one_take

import androidx.test.platform.app.InstrumentationRegistry
import com.onetake.engine.android.whisper.install.WhisperBundleInstaller
import com.onetake.engine.android.whisper.install.WhisperBundleSpec
import java.io.File
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Before

/** The public archive is staged explicitly; it is never packaged in the APK. */
class QnnWhisperInstallTest {
    @Before fun foregroundOptInRun() {
        if (InstrumentationRegistry.getArguments().getString("requireWhisperInstall") == "true") {
            keepQnnTestInForeground()
        }
    }

    @Test fun officialBundleInstallsAtomicallyAndResolvesForInference() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("requireWhisperInstall") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val archive = File(context.cacheDir, "qnn-whisper-install-test.zip")
        check(archive.isFile) { "Stage the pinned public bundle before opting into installation validation" }
        val installer = WhisperBundleInstaller(context)
        val installed = installer.install(archive)
        assertEquals(installed, installer.resolveInstalled())
        assertEquals(WhisperBundleSpec.ENCODER_SIZE_BYTES, File(installed, "encoder.bin").length())
        assertEquals(WhisperBundleSpec.DECODER_SIZE_BYTES, File(installed, "decoder.bin").length())
        assertEquals(WhisperBundleSpec.VOCABULARY_SIZE_BYTES, File(installed, "vocab.bin").length())
        assertEquals("Reinstall must reuse the same verified release", installed, installer.install(archive))
    }
}
