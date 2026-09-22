package com.bukovinafilip.gradey.updates

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
    val lastChecked = remember(state.lastCheckedAt) {
        state.lastCheckedAt.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it))
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
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("App updates", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            }
        }
        item {
            GradeySectionCard {
                Text("Installed version", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Gradey ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (directUpdates) Text(
                    lastChecked?.let { "Last checked $it" } ?: "No update check yet",
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
                            UpdatePhase.IDLE -> if (state.lastCheckedAt > 0) "You're up to date" else "Keep Gradey up to date"
                            UpdatePhase.CHECKING -> "Checking for updates"
                            UpdatePhase.AVAILABLE -> "A new version is available"
                            UpdatePhase.QUEUED -> if (state.wifiOnly && !state.manualDownload) "Waiting for Wi-Fi" else "Download queued"
                            UpdatePhase.DOWNLOADING -> "Downloading update"
                            UpdatePhase.READY -> "Your update is ready"
                            UpdatePhase.ERROR -> "Update needs another try"
                            UpdatePhase.STORE_MANAGED -> "Updates from Google Play"
                            UpdatePhase.DISABLED -> "Updates are unavailable"
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
                        UpdatePhase.IDLE -> if (state.lastCheckedAt > 0) "You're using the latest available version of Gradey." else "Get the latest improvements and new features."
                        UpdatePhase.CHECKING -> "Looking for a newer version of Gradey."
                        UpdatePhase.AVAILABLE -> "Download the update now, then install it when you're ready."
                        UpdatePhase.QUEUED -> if (state.wifiOnly && !state.manualDownload) "The download will start on Wi-Fi or another unmetered connection. You can also download now using your current connection." else "The download will start when a connection is available."
                        UpdatePhase.DOWNLOADING -> "You can keep using Gradey while the update downloads."
                        UpdatePhase.READY -> "The download is complete. Install the new version when you're ready."
                        UpdatePhase.ERROR -> "The update couldn't be completed. Check your connection and try again."
                        UpdatePhase.STORE_MANAGED -> "Google Play handles updates for this installation. Open the store to check for a new version."
                        UpdatePhase.DISABLED -> "Automatic updates haven't been set up for this build."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (state.phase == UpdatePhase.READY) Text(
                    "Android will ask you to confirm the installation. Your Gradey data will stay in place.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                when (state.phase) {
                    UpdatePhase.CHECKING -> LinearProgressIndicator(Modifier.fillMaxWidth())
                    UpdatePhase.DOWNLOADING -> DownloadProgress(state)
                    else -> Unit
                }
                when (state.phase) {
                    UpdatePhase.IDLE -> Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check for updates") }
                    UpdatePhase.AVAILABLE -> Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Download update") }
                    UpdatePhase.QUEUED -> {
                        if (!state.manualDownload) Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Download now") }
                        TextButton(onClick = onCancelDownload, modifier = Modifier.fillMaxWidth()) { Text("Cancel download") }
                    }
                    UpdatePhase.DOWNLOADING -> TextButton(onClick = onCancelDownload, modifier = Modifier.fillMaxWidth()) { Text("Cancel download") }
                    UpdatePhase.READY -> Button(onClick = onInstall, modifier = Modifier.fillMaxWidth()) { Text("Install update") }
                    UpdatePhase.ERROR -> {
                        Button(onClick = onCheck, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
                        if (release != null) OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Download again") }
                    }
                    UpdatePhase.STORE_MANAGED -> Button(onClick = onOpenStore, modifier = Modifier.fillMaxWidth()) { Text("Open Google Play") }
                    UpdatePhase.CHECKING, UpdatePhase.DISABLED -> Unit
                }
            }
        }
        if (directUpdates && !release?.releaseNotes.isNullOrBlank()) item {
            GradeySectionCard(title = "What's new") {
                Text(checkNotNull(release?.releaseNotes), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (directUpdates) item {
            GradeySectionCard(title = "Automatic updates") {
                UpdatePreference(
                    testTag = "update-auto-checks",
                    title = "Check automatically",
                    detail = "Look for updates about every 12 hours.",
                    checked = state.automaticChecks,
                    onCheckedChange = onAutoCheckChange,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                UpdatePreference(
                    testTag = "update-auto-downloads",
                    title = "Download automatically",
                    detail = "Get new versions in the background. You choose when to install.",
                    checked = state.automaticDownloads,
                    onCheckedChange = onAutoDownloadChange,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                UpdatePreference(
                    testTag = "update-wifi-only",
                    title = "Wi-Fi only",
                    detail = "Use Wi-Fi or another unmetered connection for automatic downloads.",
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
            "${(fraction * 100).toInt()}% · ${formatMegabytes(downloaded.coerceAtMost(total))} of ${formatMegabytes(total)} MB",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text("${formatMegabytes(downloaded)} MB downloaded", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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

private fun formatMegabytes(bytes: Long): String = String.format(Locale.getDefault(), "%.1f", bytes.coerceAtLeast(0) / (1024.0 * 1024.0))
