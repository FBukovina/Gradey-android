package com.bukovinafilip.gradey

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Watch
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bukovinafilip.gradey.model.WearSchoolNodeGrant
import com.bukovinafilip.gradey.model.WearSchoolScope
import com.bukovinafilip.gradey.ui.GradeySectionCard
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private data class WatchAccessReview(val nodeID: String, val displayName: String, val scope: WearSchoolScope)
private enum class WatchAccessNotice { ENABLED, REVOKED }

/** Opening this screen discovers watches; only the explicit review confirmation grants access. */
@Composable
internal fun WatchAccessDialog(
    onDismiss: () -> Unit,
    isDemo: Boolean,
    onAccessChanged: () -> Unit,
    manager: WatchSchoolAccessApi? = null,
) {
    val context = LocalContext.current
    val access = manager ?: remember(context.applicationContext) { WearSchoolAccessManager.get(context.applicationContext) }
    key(access, isDemo) {
        WatchAccessDialogContent(access, onDismiss, isDemo, onAccessChanged)
    }
}

@Composable
private fun WatchAccessDialogContent(
    manager: WatchSchoolAccessApi,
    onDismiss: () -> Unit,
    isDemo: Boolean,
    onAccessChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val onChanged by rememberUpdatedState(onAccessChanged)
    var state by remember { mutableStateOf<WatchSchoolAccessState?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<WatchAccessNotice?>(null) }
    var review by remember { mutableStateOf<WatchAccessReview?>(null) }

    suspend fun load() {
        busy = true
        failed = false
        notice = null
        try { state = manager.load() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { failed = true }
        finally { busy = false }
    }
    fun change(message: WatchAccessNotice, action: suspend () -> WatchSchoolAccessState) {
        if (busy || isDemo) return
        busy = true
        failed = false
        notice = null
        scope.launch {
            try {
                state = action()
                notice = message
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed = true }
            finally {
                busy = false
                // A durable revoke/enable may succeed even if the following nearby-device reload
                // fails. Always ask the host to republish the authoritative access metadata.
                onChanged()
            }
        }
    }
    LaunchedEffect(manager, isDemo) { if (!isDemo) load() }
    val current = state
    val grants = current?.grants.orEmpty().filter { it.expiresAtEpochMillis > System.currentTimeMillis() }
    val nearby = current?.nearbyNodes.orEmpty().associateBy { it.id }
    val deviceIDs = (grants.map { it.nodeID } + nearby.keys).distinct()
    val genericName = stringResource(R.string.watch_access_device)
    fun deviceName(id: String): String = (nearby[id]?.displayName ?: current?.nodeNames?.get(id))
        ?.filterNot(Char::isISOControl)?.trim()?.take(80)?.takeIf(String::isNotEmpty) ?: genericName

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            Modifier.padding(horizontal = 16.dp).widthIn(max = 560.dp).fillMaxWidth()
                .heightIn(max = configuration.screenHeightDp.dp * .9f),
            shape = MaterialTheme.shapes.extraLarge,
            tonalElevation = 6.dp,
        ) {
            Column {
                Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 24.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.primaryContainer) {
                            Icon(Icons.Outlined.Watch, null, Modifier.padding(12.dp).size(28.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                        Text(stringResource(R.string.watch_access_title), style = MaterialTheme.typography.headlineSmall)
                    }
                    Text(stringResource(R.string.watch_access_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                LazyColumn(
                    Modifier.weight(1f, fill = false).testTag("watchAccessDevices"),
                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (isDemo) item {
                        GradeySectionCard { Text(stringResource(R.string.watch_access_demo)) }
                    } else {
                        if (failed) item {
                            Text(stringResource(R.string.watch_access_error), color = MaterialTheme.colorScheme.error)
                        }
                        notice?.let { result -> item {
                            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
                                Text(stringResource(if (result == WatchAccessNotice.ENABLED) R.string.watch_access_enabled_notice else R.string.watch_access_revoked_notice),
                                    Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSecondaryContainer)
                            }
                        } }
                        if (current != null && !current.available) item {
                            GradeySectionCard { Text(stringResource(R.string.watch_access_unavailable)) }
                        }
                        if (current?.available == true && deviceIDs.isEmpty()) item {
                            GradeySectionCard(title = stringResource(R.string.watch_access_no_watch)) {
                                Text(stringResource(R.string.watch_access_find_watch), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        items(deviceIDs, key = { it }) { nodeID ->
                            val grant = grants.firstOrNull { it.nodeID == nodeID }
                            val isNearby = nodeID in nearby
                            val name = deviceName(nodeID)
                            WatchAccessDeviceCard(name, isNearby, grant, busy,
                                canEnable = current?.available == true && current.scope != null,
                                onReview = { current?.scope?.let { review = WatchAccessReview(nodeID, name, it) } },
                                onRevoke = { current?.scope?.let { expected -> change(WatchAccessNotice.REVOKED) { manager.revoke(nodeID, expected) } } },
                            )
                        }
                        item {
                            Text(stringResource(R.string.watch_access_expiry_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isDemo) TextButton(onClick = { if (!busy) scope.launch { load() } }, enabled = !busy) {
                        Text(stringResource(R.string.watch_access_refresh))
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.watch_access_done)) }
                }
            }
        }
    }
    review?.let { selected -> AlertDialog(
        onDismissRequest = { review = null },
        icon = { Icon(Icons.Outlined.Watch, null) },
        title = { Text(stringResource(R.string.watch_access_review_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(selected.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.watch_access_review_credentials))
                Text(stringResource(R.string.watch_access_review_expiry))
                Text(stringResource(R.string.watch_access_review_revoke), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            Button(onClick = {
                review = null
                change(WatchAccessNotice.ENABLED) {
                    manager.enable(selected.nodeID, selected.scope).also { updated ->
                        check(updated.scope == selected.scope && updated.grants.any { it.nodeID == selected.nodeID })
                    }
                }
            }, enabled = !busy) { Text(stringResource(R.string.watch_access_confirm)) }
        },
        dismissButton = { TextButton(onClick = { review = null }) { Text(stringResource(R.string.watch_access_not_now)) } },
    ) }
}

@Composable
private fun WatchAccessDeviceCard(
    name: String,
    nearby: Boolean,
    grant: WearSchoolNodeGrant?,
    busy: Boolean,
    canEnable: Boolean,
    onReview: () -> Unit,
    onRevoke: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    GradeySectionCard {
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(stringResource(if (nearby) R.string.watch_access_nearby else R.string.watch_access_not_nearby),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (grant == null) Text(stringResource(R.string.watch_access_off), color = MaterialTheme.colorScheme.onSurfaceVariant)
        else Text(stringResource(R.string.watch_access_until,
            DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(grant.expiresAtEpochMillis))),
            color = MaterialTheme.colorScheme.primary)
        if (nearby) OutlinedButton(onClick = onReview, enabled = canEnable && !busy, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (grant == null) R.string.watch_access_review else R.string.watch_access_renew))
        }
        if (grant != null) {
            TextButton(onClick = onRevoke, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.watch_access_turn_off), color = MaterialTheme.colorScheme.error)
            }
            if (!nearby) Text(stringResource(R.string.watch_access_offline_note),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
