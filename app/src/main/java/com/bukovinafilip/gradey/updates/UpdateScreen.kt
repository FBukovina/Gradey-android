package com.bukovinafilip.gradey.updates

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.BuildConfig
import com.bukovinafilip.gradey.R
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTonalButton
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.PlainHeader
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SettingsBackground
import com.bukovinafilip.gradey.ui.SettingsGroup
import com.bukovinafilip.gradey.ui.SettingsRowDivider
import com.bukovinafilip.gradey.ui.SettingsToggleRow
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.tabular
import java.text.DateFormat
import java.util.Date

/**
 * App updates: the installed version, the state of the direct update (check, download, install) and
 * the automatic-update switches. A full-screen surface with its own background; [onBack] leaves it.
 */
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
    val locale = LocalConfiguration.current.locales[0]
    val lastChecked = remember(state.lastCheckedAt, locale) {
        state.lastCheckedAt.takeIf { it > 0 }?.let {
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(it))
        }
    }
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    SettingsBackground(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))) {
            GradeyTopBar(title = stringResource(R.string.app_updates), scrolled = scrolled, onBack = onBack, backDescription = stringResource(R.string.app_back))
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.widthIn(max = GradeyMetrics.narrowContentMaxWidth).fillMaxSize(),
                    contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = GradeySpacing.lg, bottom = GradeySpacing.xxl + bottomInset),
                    verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl),
                ) {
                    item(key = "installed", contentType = "header") {
                        PlainHeader(
                            label = stringResource(R.string.app_installed), value = BuildConfig.VERSION_NAME,
                            summary = if (directUpdates) lastChecked?.let { stringResource(R.string.app_last_checked, it) } ?: stringResource(R.string.app_never_checked) else null,
                        )
                    }
                    item(key = "status", contentType = "status") {
                        Column(Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                                GradeyIcon(
                                    when (state.phase) {
                                        UpdatePhase.READY -> HugeIcons.CheckmarkCircle02
                                        UpdatePhase.QUEUED, UpdatePhase.DOWNLOADING -> HugeIcons.CloudDownload
                                        UpdatePhase.IDLE -> if (state.lastCheckedAt > 0) HugeIcons.CheckmarkCircle02 else HugeIcons.Download04
                                        UpdatePhase.ERROR -> HugeIcons.Alert02
                                        else -> HugeIcons.Download04
                                    },
                                    size = 26.dp, tint = if (state.phase == UpdatePhase.ERROR) MaterialTheme.gradeyColors.orange else MaterialTheme.colorScheme.primary,
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
                                    modifier = Modifier.weight(1f).semantics { heading() }, style = GradeyType.title3, color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            if (directUpdates && release != null && state.phase != UpdatePhase.IDLE) {
                                Text("Gradey ${release.versionName} · ${formatMegabytes(release.sizeBytes)} MB", style = GradeyType.subheadlineSemibold.tabular(), color = MaterialTheme.colorScheme.onSurface)
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
                                style = GradeyType.subheadline, color = secondary,
                            )
                            if (state.phase == UpdatePhase.READY) Text(stringResource(R.string.app_install_description), style = GradeyType.subheadline, color = secondary)
                            when (state.phase) {
                                UpdatePhase.CHECKING -> CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
                                UpdatePhase.DOWNLOADING -> DownloadProgress(state)
                                else -> Unit
                            }
                            when (state.phase) {
                                UpdatePhase.IDLE -> GradeyPrimaryButton(stringResource(R.string.app_check_updates), onCheck)
                                UpdatePhase.AVAILABLE -> GradeyPrimaryButton(stringResource(R.string.app_download_update), onDownload, icon = HugeIcons.Download04)
                                UpdatePhase.QUEUED -> {
                                    if (!state.manualDownload) GradeyPrimaryButton(stringResource(R.string.app_download_now), onDownload, icon = HugeIcons.Download04)
                                    PlainLink(stringResource(R.string.app_cancel_download), onCancelDownload, showsArrow = false, fillWidth = true)
                                }
                                UpdatePhase.DOWNLOADING -> PlainLink(stringResource(R.string.app_cancel_download), onCancelDownload, showsArrow = false, fillWidth = true)
                                UpdatePhase.READY -> GradeyPrimaryButton(stringResource(R.string.app_install_update), onInstall)
                                UpdatePhase.ERROR -> {
                                    GradeyPrimaryButton(stringResource(R.string.app_check_again), onCheck, icon = HugeIcons.Refresh04)
                                    if (release != null) GradeyTonalButton(stringResource(R.string.app_download_again), onDownload, Modifier.fillMaxWidth())
                                }
                                UpdatePhase.STORE_MANAGED -> GradeyPrimaryButton(stringResource(R.string.app_open_play), onOpenStore)
                                UpdatePhase.CHECKING, UpdatePhase.DISABLED -> Unit
                            }
                        }
                    }
                    if (directUpdates && !release?.releaseNotes.isNullOrBlank()) item(key = "notes", contentType = "notes") {
                        Column(Modifier.padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                            Text(stringResource(R.string.app_whats_new), Modifier.semantics { heading() }, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
                            Text(checkNotNull(release?.releaseNotes), style = GradeyType.subheadline, color = secondary)
                        }
                    }
                    if (directUpdates) item(key = "automatic", contentType = "group") {
                        SettingsGroup(title = stringResource(R.string.app_auto_updates)) {
                            SettingsToggleRow(
                                stringResource(R.string.app_check_automatically), stringResource(R.string.app_check_detail), state.automaticChecks,
                                enabled = true, onChange = onAutoCheckChange, modifier = Modifier.testTag("update-auto-checks"),
                            )
                            SettingsRowDivider(leadingInset = GradeySpacing.xs)
                            SettingsToggleRow(
                                stringResource(R.string.app_download_automatically), stringResource(R.string.app_download_detail), state.automaticDownloads,
                                enabled = true, onChange = onAutoDownloadChange, modifier = Modifier.testTag("update-auto-downloads"),
                            )
                            SettingsRowDivider(leadingInset = GradeySpacing.xs)
                            SettingsToggleRow(
                                stringResource(R.string.app_wifi_only), stringResource(R.string.app_wifi_detail), state.wifiOnly,
                                enabled = true, onChange = onWifiOnlyChange, modifier = Modifier.testTag("update-wifi-only"),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgress(state: UpdateUiState) {
    val downloaded = state.downloadedBytes.coerceAtLeast(0)
    val total = state.totalBytes.takeIf { it > 0 } ?: state.release?.sizeBytes ?: 0
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    if (total > 0) {
        val fraction = (downloaded.toDouble() / total).coerceIn(0.0, 1.0).toFloat()
        val track = MaterialTheme.gradeyColors.tertiaryFill
        val fill = MaterialTheme.colorScheme.primary
        Box(
            Modifier.fillMaxWidth().height(6.dp).clearAndSetSemantics { }.drawBehind {
                val radius = CornerRadius(size.height / 2f)
                drawRoundRect(track, cornerRadius = radius)
                if (fraction > 0f) drawRoundRect(fill, size = size.copy(width = (size.width * fraction).coerceAtLeast(size.height)), cornerRadius = radius)
            },
        )
        Text(
            stringResource(R.string.app_download_progress, (fraction * 100).toInt(), formatMegabytes(downloaded.coerceAtMost(total)), formatMegabytes(total)),
            style = GradeyType.caption.tabular(), color = secondary,
        )
    } else {
        CircularProgressIndicator(Modifier.size(22.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.5.dp)
        Text(stringResource(R.string.app_downloaded, formatMegabytes(downloaded)), style = GradeyType.caption.tabular(), color = secondary)
    }
}

@Composable
private fun formatMegabytes(bytes: Long): String = String.format(LocalConfiguration.current.locales[0], "%.1f", bytes.coerceAtLeast(0) / (1024.0 * 1024.0))
