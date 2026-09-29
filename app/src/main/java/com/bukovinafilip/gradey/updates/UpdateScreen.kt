package com.bukovinafilip.gradey.updates

import androidx.compose.ui.res.stringResource
import com.bukovinafilip.gradey.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.BuildConfig
import com.bukovinafilip.gradey.ui.GradeySectionCard
import com.bukovinafilip.gradey.ui.GradeySpacing
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun UpdateScreen(
    state: UpdateUiState,
    onCheck: () -> Unit,
    onDownload: () -> Unit,
    onInstall: () -> Unit,
    onCancelDownload: () -> Unit,
    onAutoCheckChange: (Boolean) -> Unit,
    onAutoDownloadChange: (Boolean) -> Unit,
    onWifiOnlyChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onOpenStore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val directUpdates = state.phase != UpdatePhase.STORE_MANAGED && state.phase != UpdatePhase.DISABLED
    val release = state.release
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val lastChecked = remember(state.lastCheckedAt, locale) {
        state.lastCheckedAt.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it))
        }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(GradeySpacing.lg),
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.app_back))
                }
                Text(stringResource(R.string.app_updates), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        item {
            GradeySectionCard {
                Text(stringResource(R.string.app_installed), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Gradey ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (directUpdates) Text(
                    lastChecked?.let { stringResource(R.string.app_last_checked, it) } ?: stringResource(R.string.app_never_checked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            GradeySectionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(
                        imageVector = when (state.phase) {
                            UpdatePhase.READY -> Icons.Default.CheckCircleOutline
                            UpdatePhase.QUEUED, UpdatePhase.DOWNLOADING -> Icons.Default.CloudDownload
                            UpdatePhase.IDLE -> if (state.lastCheckedAt > 0) Icons.Default.CheckCircleOutline else Icons.Default.SystemUpdate
                            else -> Icons.Default.SystemUpdate
                        },
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = when (state.phase) {
                            UpdatePhase.IDLE -> if (state.lastCheckedAt > 0) stringResource(R.string.app_up_to_date) else stringResource(R.string.app_keep_updated)
                            UpdatePhase.CHECKING -> stringResource(R.string.app_checking_updates)
                            UpdatePhase.AVAILABLE -> stringResource(R.string.app_update_available)
                            UpdatePhase.QUEUED -> if (state.wifiOnly && !state.manualDownload) stringResource(R.string.app_waiting_wifi) else stringResource(R.string.app_download_queued)
                            UpdatePhase.DOWNLOADING -> stringResource(R.string.app_downloading)
                            UpdatePhase.READY -> stringResource(R.string.app_update_ready)
                            UpdatePhase.ERROR -> stringResource(R.string.app_update_retry)
                            UpdatePhase.STORE_MANAGED -> stringResource(R.string.app_store_updates)
                            UpdatePhase.DISABLED -> stringResource(R.string.app_updates_unavailable)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (directUpdates && release != null && state.phase != UpdatePhase.IDLE) {
                    Text("Gradey ${release.versionName} · ${formatMegabytes(release.sizeBytes)} MB", style = MaterialTheme.typography.labelLarge)
                }
                Text(
                    text = state.message ?: when (state.phase) {
                        UpdatePhase.IDLE -> if (state.lastCheckedAt > 0) stringResource(R.string.app_latest_description) else stringResource(R.string.app_update_description)
                        UpdatePhase.CHECKING -> stringResource(R.string.app_checking_description)
                        UpdatePhase.AVAILABLE -> stringResource(R.string.app_available_description)
                        UpdatePhase.QUEUED -> if (state.wifiOnly && !state.manualDownload) stringResource(R.string.app_wifi_description) else stringResource(R.string.app_queued_description)
                        UpdatePhase.DOWNLOADING -> stringResource(R.string.app_downloading_description)
                        UpdatePhase.READY -> stringResource(R.string.app_ready_description)
                        UpdatePhase.ERROR -> stringResource(R.string.app_error_description)
                        UpdatePhase.STORE_MANAGED -> stringResource(R.string.app_store_description)
                        UpdatePhase.DISABLED -> stringResource(R.string.app_disabled_description)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.phase == UpdatePhase.READY) Text(
                    stringResource(R.string.app_install_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state.phase) {
                    UpdatePhase.CHECKING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    UpdatePhase.DOWNLOADING -> DownloadProgress(state)
                    else -> Unit
                }
                when (state.phase) {
                    UpdatePhase.IDLE -> Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_check_updates)) }
                    UpdatePhase.AVAILABLE -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_download_update)) }
                    UpdatePhase.QUEUED -> {
                        if (!state.manualDownload) Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_download_now)) }
                        TextButton(onClick = onCancelDownload, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_cancel_download)) }
                    }
                    UpdatePhase.DOWNLOADING -> TextButton(onClick = onCancelDownload, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_cancel_download)) }
                    UpdatePhase.READY -> Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_install_update)) }
                    UpdatePhase.ERROR -> {
                        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_check_again)) }
                        if (release != null) OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_download_again)) }
                    }
                    UpdatePhase.STORE_MANAGED -> Button(onClick = onOpenStore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.app_open_play)) }
                    UpdatePhase.CHECKING, UpdatePhase.DISABLED -> Unit
                }
            }
        }
        if (directUpdates && !release?.releaseNotes.isNullOrBlank()) item {
            GradeySectionCard(title = stringResource(R.string.app_whats_new)) {
                Text(checkNotNull(release?.releaseNotes), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (directUpdates) item {
            GradeySectionCard(title = stringResource(R.string.app_auto_updates)) {
                UpdatePreference(
                    testTag = "update-auto-checks",
                    title = stringResource(R.string.app_check_automatically),
                    detail = stringResource(R.string.app_check_detail),
                    checked = state.automaticChecks,
                    onCheckedChange = onAutoCheckChange,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                UpdatePreference(
                    testTag = "update-auto-downloads",
                    title = stringResource(R.string.app_download_automatically),
                    detail = stringResource(R.string.app_download_detail),
                    checked = state.automaticDownloads,
                    onCheckedChange = onAutoDownloadChange,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                UpdatePreference(
                    testTag = "update-wifi-only",
                    title = stringResource(R.string.app_wifi_only),
                    detail = stringResource(R.string.app_wifi_detail),
                    checked = state.wifiOnly,
                    onCheckedChange = onWifiOnlyChange,
                )
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: UpdateUiState) {
    val downloaded = state.downloadedBytes.coerceAtLeast(0)
    val total = state.totalBytes.takeIf { it > 0 } ?: state.release?.sizeBytes ?: 0
    if (total > 0) {
        val fraction = (downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            stringResource(R.string.app_download_progress, (fraction * 100).toInt(), formatMegabytes(downloaded.coerceAtMost(total)), formatMegabytes(total)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(stringResource(R.string.app_downloaded, formatMegabytes(downloaded)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun UpdatePreference(testTag: String, title: String, detail: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().testTag(testTag).toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun formatMegabytes(bytes: Long): String = String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%.1f", bytes.coerceAtLeast(0) / (1024.0 * 1024.0))
