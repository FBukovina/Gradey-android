package com.bukovinafilip.gradey.feature.support

import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySheet
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.ModalCloseButton
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.gradeyColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** Merely displaying this dialog never logs into or contacts Intercom. */
@Composable
fun SupportHelpDialog(
    onDismiss: () -> Unit,
    onOpenHelpCenter: () -> Unit,
    allowed: Boolean,
    isDemo: Boolean,
    sessionKey: String?,
    messenger: SupportMessenger = GradeySupport.messenger,
) {
    // This key stays local. It is never passed to the support SDK.
    key(sessionKey, allowed, isDemo, messenger) {
        SupportHelpDialogContent(onDismiss, onOpenHelpCenter, allowed, isDemo, messenger)
    }
}

@Composable
private fun SupportHelpDialogContent(
    onDismiss: () -> Unit,
    onOpenHelpCenter: () -> Unit,
    allowed: Boolean,
    isDemo: Boolean,
    messenger: SupportMessenger,
) {
    var request by remember { mutableStateOf<SupportRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var active by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        onDispose {
            active = false
            request?.cancel()
        }
    }
    LaunchedEffect(busy) {
        if (busy) {
            delay(20_000)
            failed = true
            request?.cancel()
        }
    }
    val canChat = messenger.isConfigured && allowed && !isDemo
    val close = { request?.cancel(); onDismiss() }
    GradeySheet(onDismiss = close, modifier = Modifier.testTag("supportHelpSheet")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            Text(
                stringResource(R.string.support_help_title), Modifier.weight(1f).semantics { heading() },
                style = GradeyType.title2, color = MaterialTheme.colorScheme.onSurface,
            )
            ModalCloseButton(close, contentDescription = stringResource(R.string.support_help_close))
        }
        Text(
            stringResource(when {
                isDemo -> R.string.support_help_demo
                !allowed -> R.string.support_help_age
                !messenger.isConfigured -> R.string.support_help_unavailable
                else -> R.string.support_help_anonymous
            }),
            style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurface,
        )
        if (canChat) Text(stringResource(R.string.support_help_privacy), style = GradeyType.footnote, color = MaterialTheme.gradeyColors.secondaryLabel)
        if (failed) InlineNotice(stringResource(R.string.support_help_failed))
        if (canChat) GradeyPrimaryButton(
            stringResource(if (busy) R.string.support_help_opening else R.string.support_help_start),
            onClick = {
                busy = true
                failed = false
                request = messenger.openAnonymous { result ->
                    if (active) {
                        busy = false
                        when (result) {
                            SupportOpenResult.OPENED -> onDismiss()
                            SupportOpenResult.CANCELLED -> Unit
                            else -> failed = true
                        }
                    }
                }
            },
            enabled = !busy, loading = busy, icon = HugeIcons.Message01,
        )
        PlainLink(stringResource(R.string.support_help_center), { request?.cancel(); onDismiss(); onOpenHelpCenter() }, fillWidth = true)
    }
}
