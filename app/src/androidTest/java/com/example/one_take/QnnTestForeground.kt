package com.example.one_take

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry

/** I2501 can freeze background instrumentation; these checks target foreground use. */
internal fun keepQnnTestInForeground() {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.startActivitySync(
        Intent(instrumentation.targetContext, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
    )
}
