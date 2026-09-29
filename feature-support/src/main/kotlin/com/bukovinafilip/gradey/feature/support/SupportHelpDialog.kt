package com.bukovinafilip.gradey.feature.support

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    AlertDialog(
        onDismissRequest = { request?.cancel(); onDismiss() },
        title = { Text(stringResource(R.string.support_help_title)) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(stringResource(when {
                    isDemo -> R.string.support_help_demo
                    !allowed -> R.string.support_help_age
                    !messenger.isConfigured -> R.string.support_help_unavailable
                    else -> R.string.support_help_anonymous
                }))
                if (canChat) Text(
                    stringResource(R.string.support_help_privacy),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (failed) Text(
                    stringResource(R.string.support_help_failed),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (canChat) Button(
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy,
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
                ) { Text(stringResource(if (busy) R.string.support_help_opening else R.string.support_help_start)) }
                TextButton(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = { request?.cancel(); onDismiss(); onOpenHelpCenter() },
                ) { Text(stringResource(R.string.support_help_center)) }
            }
        },
        dismissButton = {
            TextButton(onClick = { request?.cancel(); onDismiss() }) {
                Text(stringResource(R.string.support_help_close))
            }
        },
    )
}
