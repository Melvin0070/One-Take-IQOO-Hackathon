package com.example.one_take.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Size
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Owns the camera analysis use case and the on-device face landmarker.
 *
 * The model is initialized on the analysis executor. Camera binding can
 * therefore continue while the model is loading, and frames received during
 * that interval are closed and ignored. A single executor also keeps
 * detectForVideo timestamps ordered, which is required by VIDEO mode.
 */
internal class FaceTracker(
    context: Context,
    private val onObservation: (FaceObservation?) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val MODEL_ASSET = "face_landmarker.task"
        private const val ANALYSIS_WIDTH = 640
        private const val ANALYSIS_HEIGHT = 360
        private const val MAX_ANALYSIS_INTERVAL_MS = 100L
        private const val ERROR_MESSAGE = "Face tracking unavailable"
        private const val MAX_CONSECUTIVE_DETECTION_ERRORS = 3

        // MediaPipe's canonical face mesh indexes. They are used only for a
        // coarse orientation hint, not as an angular measurement.
        private const val NOSE_TIP_INDEX = 1
        private const val LEFT_EYE_INDEX = 33
        private const val RIGHT_EYE_INDEX = 263
    }

    private val applicationContext = context.applicationContext
    private val mainExecutor: Executor = ContextCompat.getMainExecutor(applicationContext)
    private val analysisExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "face-analysis").apply { isDaemon = true }
    }
    private val stateLock = Any()
    @Volatile private var released = false
    private var landmarker: FaceLandmarker? = null
    private var lastAnalyzedAt = 0L
    private var analysisGeneration = 0L
    private var consecutiveDetectionErrors = 0
    private var errorReported = false

    init {
        analysisExecutor.execute(::initializeLandmarker)
    }

    /** Builds a fresh CameraX analysis use case for the requested display rotation. */
    fun createAnalysis(targetRotation: Int): ImageAnalysis {
        val generation = synchronized(stateLock) {
            check(!released) { "FaceTracker is released" }
            analysisGeneration++
            analysisGeneration
        }
        return ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setTargetResolution(Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT))
            .setTargetRotation(targetRotation)
            .build()
            .also { analysis -> analysis.setAnalyzer(analysisExecutor) { image ->
                analyze(image, generation)
            } }
    }

    /** Invalidates queued frames from an old CameraX binding. */
    fun invalidateAnalysis() {
        synchronized(stateLock) {
            if (!released) analysisGeneration++
        }
    }

    fun release() {
        synchronized(stateLock) {
            if (released) return
            released = true
            analysisGeneration++
            runCatching { landmarker?.close() }
            landmarker = null
        }
        analysisExecutor.shutdownNow()
    }

    private fun initializeLandmarker() {
        try {
            val baseOptions = BaseOptions.builder()
                .setModelAssetPath(MODEL_ASSET)
                .setDelegate(Delegate.CPU)
                .build()
            val options = FaceLandmarker.FaceLandmarkerOptions.builder()
                .setBaseOptions(baseOptions)
                .setRunningMode(RunningMode.VIDEO)
                .setNumFaces(1)
                .setMinFaceDetectionConfidence(0.5f)
                .setMinFacePresenceConfidence(0.5f)
                .setMinTrackingConfidence(0.5f)
                .build()
            val created = FaceLandmarker.createFromOptions(applicationContext, options)
            synchronized(stateLock) {
                if (released) {
                    created.close()
                } else {
                    landmarker = created
                }
            }
        } catch (_: Throwable) {
            reportError()
        }
    }

    private fun analyze(image: ImageProxy, generation: Long) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalyzedAt < MAX_ANALYSIS_INTERVAL_MS) return
            lastAnalyzedAt = now

            val bitmap = imageToBitmap(image) ?: return
            try {
                val mpImage = BitmapImageBuilder(bitmap).build()
                val result = try {
                    synchronized(stateLock) {
                        if (released || generation != analysisGeneration) return@synchronized null
                        val currentLandmarker = landmarker ?: return@synchronized null
                        currentLandmarker.detectForVideo(mpImage, now)
                    }
                } finally {
                    mpImage.close()
                }
                synchronized(stateLock) { consecutiveDetectionErrors = 0 }
                val observation = result?.let { toObservation(it, bitmap, now) }
                if (!released && isCurrentGeneration(generation)) postObservation(observation, generation)
            } finally {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        } catch (_: Exception) {
            // A malformed frame should not tear down capture. Initialization
            // failures are reported separately and disable this use case in
            // CameraRecorder.
            val shouldReport = synchronized(stateLock) {
                consecutiveDetectionErrors++
                consecutiveDetectionErrors >= MAX_CONSECUTIVE_DETECTION_ERRORS
            }
            if (shouldReport) reportError()
        } finally {
            image.close()
        }
    }

    private fun isCurrentGeneration(generation: Long): Boolean {
        return synchronized(stateLock) { !released && generation == analysisGeneration }
    }

    private fun postObservation(observation: FaceObservation?, generation: Long) {
        mainExecutor.execute {
            if (isCurrentGeneration(generation)) onObservation(observation)
        }
    }

    private fun reportError() {
        synchronized(stateLock) {
            if (released || errorReported) return
            errorReported = true
        }
        mainExecutor.execute {
            if (!released) onError(ERROR_MESSAGE)
        }
    }

    private fun toObservation(
        result: com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult,
        bitmap: Bitmap,
        timestampMs: Long
    ): FaceObservation? {
        val landmarks = result.faceLandmarks().firstOrNull() ?: return null
        if (landmarks.isEmpty()) return null

        var minX = 1f
        var minY = 1f
        var maxX = 0f
        var maxY = 0f
        landmarks.forEach { landmark ->
            val x = landmark.x().coerceIn(0f, 1f)
            val y = landmark.y().coerceIn(0f, 1f)
            minX = min(minX, x)
            minY = min(minY, y)
            maxX = max(maxX, x)
            maxY = max(maxY, y)
        }
        val width = (maxX - minX).coerceIn(0f, 1f)
        val height = (maxY - minY).coerceIn(0f, 1f)
        val faceRect = Rect(
            (minX * bitmap.width).toInt().coerceIn(0, bitmap.width),
            (minY * bitmap.height).toInt().coerceIn(0, bitmap.height),
            (maxX * bitmap.width).toInt().coerceIn(0, bitmap.width),
            (maxY * bitmap.height).toInt().coerceIn(0, bitmap.height)
        )
        val faceLuminance = luminance(bitmap, faceRect)
        val backgroundLuminance = backgroundLuminance(bitmap)
        val offAxis = isOffAxis(landmarks)
        return FaceObservation(
            timestampMs = timestampMs,
            centerX = ((minX + maxX) / 2f).coerceIn(0f, 1f),
            centerY = ((minY + maxY) / 2f).coerceIn(0f, 1f),
            width = width,
            height = height,
            faceLuminance = faceLuminance,
            backgroundLuminance = backgroundLuminance,
            offAxis = offAxis
        )
    }

    private fun isOffAxis(
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>
    ): Boolean {
        if (landmarks.size <= NOSE_TIP_INDEX ||
            landmarks.size <= LEFT_EYE_INDEX ||
            landmarks.size <= RIGHT_EYE_INDEX
        ) {
            return false
        }
        val nose = landmarks[NOSE_TIP_INDEX]
        val leftEye = landmarks[LEFT_EYE_INDEX]
        val rightEye = landmarks[RIGHT_EYE_INDEX]
        val eyeCenterX = (leftEye.x() + rightEye.x()) / 2f
        val eyeDistance = abs(rightEye.x() - leftEye.x())
        if (eyeDistance < 0.01f) return false
        // A normalized deviation is stable enough for a coach hint and avoids
        // claiming that this lightweight heuristic measures degrees.
        return abs(nose.x() - eyeCenterX) / eyeDistance > 0.28f
    }

    private fun luminance(bitmap: Bitmap, rect: Rect): Float {
        return averageLuminance(bitmap, rect, sampleStep = 8)
    }

    private fun backgroundLuminance(bitmap: Bitmap): Float {
        val samples = ArrayList<Float>(4)
        val edge = max(1, min(bitmap.width, bitmap.height) / 8)
        samples += averageLuminance(bitmap, Rect(0, 0, bitmap.width, edge), 8)
        samples += averageLuminance(
            bitmap,
            Rect(0, bitmap.height - edge, bitmap.width, bitmap.height),
            8
        )
        samples += averageLuminance(bitmap, Rect(0, edge, edge, bitmap.height - edge), 8)
        samples += averageLuminance(
            bitmap,
            Rect(bitmap.width - edge, edge, bitmap.width, bitmap.height - edge),
            8
        )
        return samples.average().toFloat().coerceIn(0f, 1f)
    }

    private fun averageLuminance(bitmap: Bitmap, requested: Rect, sampleStep: Int): Float {
        val left = requested.left.coerceIn(0, bitmap.width)
        val top = requested.top.coerceIn(0, bitmap.height)
        val right = requested.right.coerceIn(left, bitmap.width)
        val bottom = requested.bottom.coerceIn(top, bitmap.height)
        if (left >= right || top >= bottom) return 0f

        var total = 0.0
        var count = 0
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                val color = bitmap.getPixel(x, y)
                val red = (color shr 16) and 0xff
                val green = (color shr 8) and 0xff
                val blue = color and 0xff
                total += (0.2126 * red + 0.7152 * green + 0.0722 * blue) / 255.0
                count++
                x += sampleStep
            }
            y += sampleStep
        }
        return if (count == 0) 0f else (total / count).toFloat().coerceIn(0f, 1f)
    }

    private fun imageToBitmap(image: ImageProxy): Bitmap? {
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val plane = image.planes.firstOrNull() ?: return null
        val source = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) return null

        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowStart = y * rowStride
            for (x in 0 until width) {
                val offset = rowStart + x * pixelStride
                if (offset + 3 >= source.limit()) return null
                val red = source.get(offset).toInt() and 0xff
                val green = source.get(offset + 1).toInt() and 0xff
                val blue = source.get(offset + 2).toInt() and 0xff
                val alpha = source.get(offset + 3).toInt() and 0xff
                pixels[y * width + x] =
                    (alpha shl 24) or (red shl 16) or (green shl 8) or blue
            }
        }

        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        val rotation = image.imageInfo.rotationDegrees
        if (rotation == 0) return bitmap

        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }
}
