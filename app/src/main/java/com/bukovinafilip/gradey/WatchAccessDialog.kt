package com.bukovinafilip.gradey

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import com.bukovinafilip.gradey.model.WearSchoolNodeGrant
import com.bukovinafilip.gradey.model.WearSchoolScope
import com.bukovinafilip.gradey.ui.DestructiveLink
import com.bukovinafilip.gradey.ui.EmptyState
import com.bukovinafilip.gradey.ui.GradeyDialog
import com.bukovinafilip.gradey.ui.GradeySheet
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.ModalCloseButton
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SettingsIcon
import com.bukovinafilip.gradey.ui.ToolbarIconButton
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.tabular
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

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

    GradeySheet(onDismiss = onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            Text(
                stringResource(R.string.watch_access_title), Modifier.weight(1f).semantics { heading() },
                style = GradeyType.title2, color = MaterialTheme.colorScheme.onSurface,
            )
            if (!isDemo) ToolbarIconButton(HugeIcons.Refresh04, stringResource(R.string.watch_access_refresh), { if (!busy) scope.launch { load() } }, loading = busy)
            ModalCloseButton(onDismiss, contentDescription = stringResource(R.string.watch_access_done))
        }
        Text(stringResource(R.string.watch_access_subtitle), style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false).testTag("watchAccessDevices"), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            if (isDemo) item(key = "demo", contentType = "notice") {
                InlineNotice(stringResource(R.string.watch_access_demo), color = MaterialTheme.gradeyColors.secondaryLabel, icon = HugeIcons.InformationCircle)
            } else {
                if (failed) item(key = "failed", contentType = "notice") { InlineNotice(stringResource(R.string.watch_access_error)) }
                notice?.let { result -> item(key = "notice", contentType = "notice") {
                    InlineNotice(
                        stringResource(if (result == WatchAccessNotice.ENABLED) R.string.watch_access_enabled_notice else R.string.watch_access_revoked_notice),
                        color = MaterialTheme.colorScheme.primary, icon = HugeIcons.CheckmarkCircle02,
                    )
                } }
                if (current != null && !current.available) item(key = "unavailable", contentType = "notice") {
                    InlineNotice(stringResource(R.string.watch_access_unavailable), color = MaterialTheme.gradeyColors.orange)
                }
                if (current?.available == true && deviceIDs.isEmpty()) item(key = "empty", contentType = "state") {
                    EmptyState(stringResource(R.string.watch_access_no_watch), message = stringResource(R.string.watch_access_find_watch), icon = HugeIcons.Watch01)
                }
                items(deviceIDs, key = { it }, contentType = { "device" }) { nodeID ->
                    val grant = grants.firstOrNull { it.nodeID == nodeID }
                    val isNearby = nodeID in nearby
                    val name = deviceName(nodeID)
                    WatchAccessDeviceRow(name, isNearby, grant, busy,
                        canEnable = current?.available == true && current.scope != null,
                        onReview = { current?.scope?.let { review = WatchAccessReview(nodeID, name, it) } },
                        onRevoke = { current?.scope?.let { expected -> change(WatchAccessNotice.REVOKED) { manager.revoke(nodeID, expected) } } },
                    )
                }
                item(key = "expiry", contentType = "note") {
                    Text(stringResource(R.string.watch_access_expiry_note), style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
                }
            }
        }
    }
    review?.let { selected ->
        GradeyDialog(
            title = stringResource(R.string.watch_access_review_title), onDismiss = { review = null },
            confirmLabel = stringResource(R.string.watch_access_confirm), confirmEnabled = !busy,
            onConfirm = {
                review = null
                change(WatchAccessNotice.ENABLED) {
                    manager.enable(selected.nodeID, selected.scope).also { updated ->
                        check(updated.scope == selected.scope && updated.grants.any { it.nodeID == selected.nodeID })
                    }
                }
            },
            dismissLabel = stringResource(R.string.watch_access_not_now),
        ) {
            Text(selected.displayName, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.watch_access_review_credentials), style = GradeyType.subheadline)
            Text(stringResource(R.string.watch_access_review_expiry), style = GradeyType.subheadline)
            Text(stringResource(R.string.watch_access_review_revoke), style = GradeyType.caption)
        }
    }
}

/** One watch: its name, whether it is nearby, the access state and the actions that apply. */
@Composable
private fun WatchAccessDeviceRow(
    name: String,
    nearby: Boolean,
    grant: WearSchoolNodeGrant?,
    busy: Boolean,
    canEnable: Boolean,
    onReview: () -> Unit,
    onRevoke: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Column(Modifier.fillMaxWidth()) {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(top = GradeySpacing.md), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            SettingsIcon(HugeIcons.Watch01)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                Text(name, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(if (nearby) R.string.watch_access_nearby else R.string.watch_access_not_nearby), style = GradeyType.caption, color = secondary)
                if (grant == null) Text(stringResource(R.string.watch_access_off), style = GradeyType.subheadline, color = secondary)
                else Text(
                    stringResource(R.string.watch_access_until, DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale).format(Date(grant.expiresAtEpochMillis))),
                    style = GradeyType.subheadline.tabular(), color = MaterialTheme.colorScheme.primary,
                )
                if (nearby) PlainLink(stringResource(if (grant == null) R.string.watch_access_review else R.string.watch_access_renew), onReview, enabled = canEnable && !busy)
                if (grant != null) {
                    DestructiveLink(stringResource(R.string.watch_access_turn_off), onRevoke, enabled = !busy)
                    if (!nearby) Text(stringResource(R.string.watch_access_offline_note), style = GradeyType.caption, color = secondary)
                }
            }
        }
    }
}
