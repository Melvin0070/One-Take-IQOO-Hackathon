package com.example.one_take

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

private val requiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)

@Composable
internal fun CameraPermissionGate(onOpenLibrary: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var deniedPreviously by rememberSaveable { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refresh++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val missing = remember(refresh) {
        requiredPermissions.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        deniedPreviously = result.values.any { !it }
        refresh++
    }
    LaunchedEffect(missing) {
        if (missing.isEmpty()) deniedPreviously = false
    }
    if (missing.isEmpty()) {
        content()
    } else {
        val permanentlyDenied = deniedPreviously && missing.any {
            (context as? Activity)?.let { activity ->
                !ActivityCompat.shouldShowRequestPermissionRationale(activity, it)
            } == true
        }
        PermissionScreen(
            permanentlyDenied = permanentlyDenied,
            onRequest = { launcher.launch(missing.toTypedArray()) },
            onOpenSettings = {
                context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    "package:${context.packageName}".toUri()))
            },
            onOpenLibrary = onOpenLibrary
        )
    }
}

@Composable
internal fun PermissionScreen(
    permanentlyDenied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    Surface(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.permission_title), style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.permission_description), textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
            Button(onClick = if (permanentlyDenied) onOpenSettings else onRequest) {
                Text(stringResource(if (permanentlyDenied) R.string.open_settings else R.string.allow_access))
            }
            if (permanentlyDenied) {
                Text(stringResource(R.string.permission_settings_hint), textAlign = TextAlign.Center)
            }
            OutlinedButton(onClick = onOpenLibrary) { Text(stringResource(R.string.saved_videos)) }
        }
    }
}
