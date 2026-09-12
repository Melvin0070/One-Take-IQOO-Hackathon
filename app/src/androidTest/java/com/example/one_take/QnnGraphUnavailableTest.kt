package com.example.one_take

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import com.onetake.engine.android.QnnGraphException
import com.onetake.engine.android.QnnGraphSession
import java.io.File
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class QnnGraphUnavailableTest {
    @Test fun sdkAbsentBuildRejectsGraphExecutionExplicitly() {
        assumeTrue("Requires iQOO 15", Build.VERSION.SDK_INT >= 31 &&
            Build.MODEL == "I2501" && Build.SOC_MODEL == "SM8850")
        assumeTrue("Opt in only for a build without QAIRT_SDK_ROOT",
            InstrumentationRegistry.getArguments().getString("requireNoQnn") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File.createTempFile("qnn-unavailable-", ".bin", context.cacheDir)
        try {
            file.writeBytes(byteArrayOf(1, 2, 3, 4))
            val error = assertThrows(QnnGraphException::class.java) {
                QnnGraphSession.open(context, file)
            }
            assertTrue(error.message.orEmpty().contains("SDK was not configured"))
        } finally {
            file.delete()
        }
    }
}
