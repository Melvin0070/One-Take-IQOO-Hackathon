package com.onetake.engine.android

import android.content.Context
import android.os.Build
import java.io.File

/** Explicit diagnostic probe. Call off the UI thread; it does not load a model. */
object QnnRuntimeProbe {
    @Synchronized
    fun inspect(context: Context): QnnRuntimeStatus {
        if (Build.VERSION.SDK_INT < 31 || Build.SOC_MODEL != "SM8850" || Build.MODEL != "I2501") {
            return QnnRuntimeStatus(QnnRuntimeState.WRONG_TARGET)
        }
        return synchronized(QnnRuntimeAccessLock) {
            try {
                System.loadLibrary("onetake_qnn_probe")
                QnnRuntimeStatus.fromNativeCode(probeNative(
                    File(context.applicationInfo.nativeLibraryDir, "libQnnHtp.so").absolutePath
                ))
            } catch (_: UnsatisfiedLinkError) {
                QnnRuntimeStatus(QnnRuntimeState.LIBRARY_UNAVAILABLE)
            }
        }
    }

    private external fun probeNative(libraryPath: String): Int
}
