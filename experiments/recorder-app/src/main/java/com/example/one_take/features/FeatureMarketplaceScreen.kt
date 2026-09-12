package com.example.one_take.features

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.one_take.R
import java.util.Locale
import kotlin.math.roundToInt

private val MarketplaceBackground = Color(0xFF090909)
private val MarketplaceSurface = Color(0xFF1A1A1A)
private val MarketplaceMuted = Color(0xFFAEAEAE)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeatureMarketplaceScreen(
    store: CaptionFeatureStore,
    onBack: () -> Unit
) {
    var showRemoveDialog by rememberSaveable { mutableStateOf(false) }
    val state = store.state
    val inUse = store.inUse
    val uriHandler = LocalUriHandler.current

    BackHandler(onBack = onBack)

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = MarketplaceBackground,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.feature_marketplace_title)) },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.semantics { role = Role.Button }
                    ) {
                        Text("‹", fontSize = 32.sp, lineHeight = 32.sp)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MarketplaceBackground,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { contentPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.feature_offline_captions),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.feature_offline_captions_description),
                color = MarketplaceMuted,
                fontSize = 16.sp,
                lineHeight = 23.sp
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MarketplaceSurface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(52.dp),
                            shape = RoundedCornerShape(16.dp),
                            color = Color.White
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text(
                                    text = "CC",
                                    color = Color.Black,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 17.sp
                                )
                            }
                        }
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.feature_offline_captions),
                                color = Color.White,
                                fontSize = 19.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = stringResource(
                                    R.string.feature_model_size,
                                    formatBytes(OfflineCaptionModel.EXPECTED_BYTES)
                                ),
                                color = MarketplaceMuted,
                                fontSize = 14.sp
                            )
                        }
                    }

                    MarketplaceStatus(state = state)

                    when (state) {
                        CaptionFeatureState.Checking -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("caption-model-checking"),
                                color = Color.White,
                                trackColor = Color(0xFF454545)
                            )
                        }

                        is CaptionFeatureState.Downloading -> {
                            val total = state.totalBytes.coerceAtLeast(1L)
                            val progress = (state.downloadedBytes.toFloat() / total)
                                .coerceIn(0f, 1f)
                            Text(
                                text = stringResource(
                                    R.string.feature_model_download_progress,
                                    (progress * 100f).roundToInt(),
                                    formatBytes(state.downloadedBytes),
                                    formatBytes(total)
                                ),
                                color = Color.White,
                                fontSize = 14.sp
                            )
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("caption-model-progress"),
                                color = Color.White,
                                trackColor = Color(0xFF454545)
                            )
                            OutlinedButton(
                                onClick = store::cancelDownload,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("caption-model-cancel"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White
                                )
                            ) {
                                Text(stringResource(R.string.feature_cancel_download))
                            }
                        }

                        CaptionFeatureState.Verifying -> {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("caption-model-verifying"),
                                color = Color.White,
                                trackColor = Color(0xFF454545)
                            )
                        }

                        CaptionFeatureState.Idle -> {
                            Button(
                                onClick = store::download,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("caption-model-download"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text(stringResource(R.string.feature_download_model))
                            }
                        }

                        is CaptionFeatureState.Error -> {
                            Button(
                                onClick = store::download,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("caption-model-retry"),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color.White,
                                    contentColor = Color.Black
                                )
                            ) {
                                Text(stringResource(R.string.feature_retry_download))
                            }
                        }

                        CaptionFeatureState.Ready -> {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .semantics(mergeDescendants = true) {},
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(R.string.feature_enable_captions),
                                    modifier = Modifier.weight(1f),
                                    color = Color.White,
                                    fontSize = 16.sp
                                )
                                Switch(
                                    checked = store.enabled,
                                    onCheckedChange = { store.setFeatureEnabled(it) },
                                    enabled = !inUse,
                                    modifier = Modifier
                                        .testTag("caption-model-enabled")
                                        .semantics { role = Role.Switch }
                                )
                            }
                            if (inUse) {
                                Text(
                                    text = stringResource(R.string.feature_model_in_use),
                                    color = MarketplaceMuted,
                                    fontSize = 14.sp
                                )
                            }
                            OutlinedButton(
                                onClick = { showRemoveDialog = true },
                                enabled = !inUse,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("caption-model-remove"),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = Color.White,
                                    disabledContentColor = Color(0xFF666666)
                                )
                            ) {
                                Text(stringResource(R.string.feature_remove_model))
                            }
                        }
                    }
                }
            }

            Text(
                text = stringResource(R.string.feature_model_source),
                color = MarketplaceMuted,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.clickable {
                    uriHandler.openUri(OfflineCaptionModel.repositoryUrl)
                }
            )
            Text(
                text = stringResource(R.string.feature_model_license),
                color = MarketplaceMuted,
                fontSize = 13.sp,
                modifier = Modifier.clickable {
                    uriHandler.openUri(OfflineCaptionModel.licenseUrl)
                }
            )
        }
    }

    if (showRemoveDialog) {
        AlertDialog(
            onDismissRequest = { showRemoveDialog = false },
            title = { Text(stringResource(R.string.feature_remove_model_title)) },
            text = { Text(stringResource(R.string.feature_remove_model_message)) },
            dismissButton = {
                TextButton(onClick = { showRemoveDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.remove()
                    showRemoveDialog = false
                }) {
                    Text(stringResource(R.string.feature_remove_model))
                }
            }
        )
    }
}

@Composable
private fun MarketplaceStatus(
    state: CaptionFeatureState
) {
    when (state) {
        CaptionFeatureState.Checking -> Text(
            stringResource(R.string.feature_model_checking),
            color = MarketplaceMuted,
            fontSize = 14.sp
        )

        CaptionFeatureState.Idle -> Text(
            stringResource(R.string.feature_download_model),
            color = MarketplaceMuted,
            fontSize = 14.sp
        )

        is CaptionFeatureState.Downloading -> Text(
            stringResource(R.string.feature_download_model),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        CaptionFeatureState.Verifying -> Text(
            stringResource(R.string.feature_model_verifying),
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        CaptionFeatureState.Ready -> Text(
            stringResource(R.string.feature_model_downloaded),
            color = Color(0xFFB8E6C1),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium
        )

        is CaptionFeatureState.Error -> Text(
            errorMessage(state.reason),
            color = Color(0xFFFFB4AB),
            fontSize = 14.sp,
            lineHeight = 20.sp
        )
    }
}

@Composable
private fun errorMessage(reason: CaptionFeatureError): String {
    return when (reason) {
        CaptionFeatureError.Network -> stringResource(R.string.feature_error_network)
        CaptionFeatureError.InvalidResponse -> stringResource(R.string.feature_error_invalid_response)
        CaptionFeatureError.DownloadTooLarge -> stringResource(R.string.feature_error_download_too_large)
        CaptionFeatureError.IncompleteDownload -> stringResource(R.string.feature_error_incomplete_download)
        CaptionFeatureError.ChecksumMismatch -> stringResource(R.string.feature_error_checksum_mismatch)
        CaptionFeatureError.StorageUnavailable -> stringResource(R.string.feature_error_storage_unavailable)
        CaptionFeatureError.InsufficientStorage -> stringResource(R.string.feature_error_insufficient_storage)
        CaptionFeatureError.InstallFailed -> stringResource(R.string.feature_error_install_failed)
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val megabytes = bytes / (1024.0 * 1024.0)
    return String.format(Locale.US, "%.1f MB", megabytes)
}
