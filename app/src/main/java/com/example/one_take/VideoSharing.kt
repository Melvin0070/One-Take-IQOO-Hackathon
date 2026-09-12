package com.example.one_take

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/** Grants read access to one explicitly selected recording for the Android share sheet. */
internal fun shareVideo(context: Context, file: File) {
    val directory = File(context.filesDir, "videos").canonicalFile
    require(file.isFile && file.canonicalFile.parentFile == directory) { "Video is unavailable" }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.videos", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "video/mp4"
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri("Video", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, context.getString(R.string.share_video)))
}
