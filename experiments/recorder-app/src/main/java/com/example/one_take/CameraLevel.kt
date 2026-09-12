package com.example.one_take

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

private const val LEVEL_TOLERANCE_DEGREES = 1f
private const val MIN_GRAVITY_MAGNITUDE = 2f
private const val MIN_HORIZONTAL_GRAVITY_RATIO = 0.25f
private const val ACCELEROMETER_LOW_PASS_ALPHA = 0.15f

/**
 * The portrait roll calculated from a gravity vector.
 *
 * Gravity is unreliable when the phone is nearly face-up or face-down because the x and y
 * components approach zero. Those samples are marked invalid so the indicator disappears rather
 * than presenting a misleading level.
 */
internal data class CameraLevelReading(
    val rollDegrees: Float,
    val isValid: Boolean
)

internal fun cameraLevelReading(
    gravityX: Float,
    gravityY: Float,
    gravityZ: Float
): CameraLevelReading {
    val horizontalMagnitude = hypot(gravityX.toDouble(), gravityY.toDouble()).toFloat()
    val totalMagnitude = sqrt(
        gravityX * gravityX + gravityY * gravityY + gravityZ * gravityZ
    )

    if (
        totalMagnitude < MIN_GRAVITY_MAGNITUDE ||
        horizontalMagnitude < totalMagnitude * MIN_HORIZONTAL_GRAVITY_RATIO
    ) {
        return CameraLevelReading(rollDegrees = 0f, isValid = false)
    }

    return CameraLevelReading(
        rollDegrees = Math.toDegrees(atan2(gravityX.toDouble(), gravityY.toDouble())).toFloat(),
        isValid = true
    )
}

internal fun Context.levelSensorAvailable(): Boolean {
    val sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return false
    return sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY) != null ||
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null
}

@Composable
internal fun rememberLevelAvailable(): Boolean {
    val context = LocalContext.current
    return remember(context) { context.levelSensorAvailable() }
}

/**
 * Draws a small camera level indicator over the preview.
 *
 * The indicator registers only while enabled and the host lifecycle is resumed. A gravity sensor
 * is preferred; devices without one use a low-pass filtered accelerometer instead.
 */
@Composable
internal fun CameraLevel(
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val sensorManager = remember(context) {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    val sensor = remember(sensorManager) {
        sensorManager?.getDefaultSensor(Sensor.TYPE_GRAVITY)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }
    var reading by remember { mutableStateOf<CameraLevelReading?>(null) }

    DisposableEffect(lifecycleOwner, sensorManager, sensor, enabled) {
        // Avoid showing a stale angle when the overlay is enabled again.
        reading = null

        if (!enabled || sensorManager == null || sensor == null) {
            onDispose { }
        } else {
            val listener = LevelSensorListener(
                useLowPass = sensor.type == Sensor.TYPE_ACCELEROMETER,
                onReading = { newReading -> reading = newReading }
            )
            var registered = false

            fun unregister() {
                if (registered) {
                    sensorManager.unregisterListener(listener)
                    registered = false
                }
                listener.active = false
            }

            fun register() {
                if (registered || !lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                    return
                }
                listener.reset()
                listener.active = sensorManager.registerListener(
                    listener,
                    sensor,
                    SensorManager.SENSOR_DELAY_UI
                )
                registered = listener.active
            }

            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> register()
                    Lifecycle.Event.ON_PAUSE -> {
                        unregister()
                        reading = null
                    }
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            register()

            onDispose {
                unregister()
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
    }

    val currentReading = reading ?: return
    if (!enabled || !currentReading.isValid) return

    val level = abs(currentReading.rollDegrees) <= LEVEL_TOLERANCE_DEGREES
    val indicatorDescription = stringResource(R.string.camera_level_description)
    val indicatorState = stringResource(
        if (level) R.string.camera_level_state_level else R.string.camera_level_state_tilted
    )

    Canvas(
        modifier = modifier
            .defaultMinSize(minWidth = 72.dp, minHeight = 72.dp)
            .semantics {
                contentDescription = indicatorDescription
                stateDescription = indicatorState
            }
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val lineLength = 16.dp.toPx()
        val lineColor = if (level) Color(0xFFFFD54F) else Color.White

        rotate(degrees = currentReading.rollDegrees, pivot = center) {
            drawLine(
                color = lineColor,
                start = Offset(center.x - lineLength, center.y),
                end = Offset(center.x + lineLength, center.y),
                strokeWidth = 2.dp.toPx(),
                cap = StrokeCap.Round
            )
        }
        drawCircle(color = lineColor, radius = 4.dp.toPx(), center = center)
    }
}

private class LevelSensorListener(
    private val useLowPass: Boolean,
    private val onReading: (CameraLevelReading) -> Unit
) : SensorEventListener {
    var active: Boolean = false
    private val filteredValues = FloatArray(3)
    private var initialized = false
    private var reliable = true

    fun reset() {
        filteredValues.fill(0f)
        initialized = false
        reliable = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!active || !reliable || event.values.size < 3) return

        if (useLowPass) {
            if (!initialized) {
                filteredValues[0] = event.values[0]
                filteredValues[1] = event.values[1]
                filteredValues[2] = event.values[2]
                initialized = true
            } else {
                filteredValues[0] = applyLowPass(filteredValues[0], event.values[0])
                filteredValues[1] = applyLowPass(filteredValues[1], event.values[1])
                filteredValues[2] = applyLowPass(filteredValues[2], event.values[2])
            }
            onReading(
                cameraLevelReading(
                    filteredValues[0],
                    filteredValues[1],
                    filteredValues[2]
                )
            )
        } else {
            onReading(cameraLevelReading(event.values[0], event.values[1], event.values[2]))
        }
    }

    private fun applyLowPass(previous: Float, current: Float): Float =
        previous + ACCELEROMETER_LOW_PASS_ALPHA * (current - previous)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        reliable = accuracy != SensorManager.SENSOR_STATUS_UNRELIABLE
        if (active && !reliable) {
            initialized = false
            onReading(CameraLevelReading(rollDegrees = 0f, isValid = false))
        }
    }
}
