package com.example.one_take

import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Visible capture status only; this does not change recording quality or inference load. */
@Composable
internal fun DeviceStatusChip(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var label by remember { mutableStateOf("Battery unknown") }
    var hot by remember { mutableStateOf(false) }
    LaunchedEffect(context) {
        val power = context.getSystemService(PowerManager::class.java)
        while (isActive) {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val percent = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else "unknown"
            val thermal = if (Build.VERSION.SDK_INT >= 29) power?.currentThermalStatus else null
            hot = thermal != null && thermal >= PowerManager.THERMAL_STATUS_SEVERE
            val condition = when {
                thermal == null -> "Thermal: unavailable"
                thermal >= PowerManager.THERMAL_STATUS_SEVERE -> "Thermal: hot"
                thermal >= PowerManager.THERMAL_STATUS_LIGHT -> "Thermal: warm"
                else -> "Thermal: normal"
            }
            label = "Battery $percent\n$condition"
            delay(2_000)
        }
    }
    Text(label, color = if (hot) Color(0xFFFFCB87) else Color.White, fontSize = 10.sp,
        modifier = modifier.clip(RoundedCornerShape(5.dp)).background(Color.Black.copy(alpha = .65f))
            .padding(horizontal = 7.dp, vertical = 5.dp))
}
