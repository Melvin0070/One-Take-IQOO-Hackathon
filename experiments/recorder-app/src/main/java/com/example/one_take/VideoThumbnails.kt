package com.example.one_take

import android.graphics.Bitmap
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.io.File

@Suppress("DEPRECATION")
internal fun loadVideoThumbnail(file: File): Bitmap? = try {
    if (Build.VERSION.SDK_INT >= 29) ThumbnailUtils.createVideoThumbnail(file, Size(480, 480), null)
    else ThumbnailUtils.createVideoThumbnail(file.absolutePath, MediaStore.Video.Thumbnails.MINI_KIND)
} catch (_: Exception) { null }

