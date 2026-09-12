package com.example.one_take

import androidx.camera.video.Quality

/**
 * Video quality choices that can be presented by the camera screen.
 *
 * CameraX reports only the standard quality constants. Keeping the app-facing
 * values here means the UI does not need to depend on CameraX's video package
 * or guess at a resolution that a device may not support.
 */
internal enum class CameraQuality(
    val cameraXQuality: Quality,
    val label: String,
    internal val rank: Int
) {
    UHD(Quality.UHD, "4K", 2160),
    FHD(Quality.FHD, "1080p", 1080),
    HD(Quality.HD, "720p", 720),
    SD(Quality.SD, "480p", 480);

    companion object {
        fun fromCameraXQuality(quality: Quality): CameraQuality? =
            entries.firstOrNull { it.cameraXQuality == quality }
    }
}
