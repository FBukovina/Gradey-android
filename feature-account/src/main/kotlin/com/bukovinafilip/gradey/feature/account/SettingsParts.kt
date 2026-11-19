package com.bukovinafilip.gradey.feature.account

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SettingsBackground
import com.bukovinafilip.gradey.ui.SettingsIcon
import com.bukovinafilip.gradey.ui.SettingsIconFrame
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled

/** Side padding of every Settings page (iOS `.padding(.horizontal, 20)`). */
internal val SettingsPagePadding = 20.dp

/** Gap between the overview's groups (iOS `VStack(spacing: 28)`). */
internal val SettingsOverviewSpacing = 28.dp

/** Linked-account states that need the user, like iOS `accountNeedsAttention`. */
internal fun linkedStatusNeedsAttention(status: String): Boolean = status == "action_required" || status == "failed"

/** Whether this device may post notifications (iOS `NotificationAuthorizationStatus`). */
internal enum class NotificationPermission { NOT_DETERMINED, DENIED, AUTHORIZED }

/**
 * Reads the notification permission again whenever the window regains focus, so the row updates
 * after the system permission prompt or Android's notification settings close.
 */
@Composable
internal fun rememberNotificationPermission(): NotificationPermission {
    val context = LocalContext.current
    val focused = LocalWindowInfo.current.isWindowFocused
    return remember(context, focused) { context.notificationPermission() }
}

private fun Context.notificationPermission(): NotificationPermission {
    val enabled = getSystemService(NotificationManager::class.java)?.areNotificationsEnabled() ?: false
    return when {
        enabled -> NotificationPermission.AUTHORIZED
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED -> NotificationPermission.NOT_DETERMINED
        else -> NotificationPermission.DENIED
    }
}

/**
 * One pushed Settings page: its own background, an inline-title top bar with Back, and a lazy
 * column of sections 24 dp apart (iOS `detailPage`, max width 760).
 */
@Composable
internal fun SettingsDetailPage(
    title: String,
    onBack: () -> Unit,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    content: LazyListScope.() -> Unit,
) {
    val listState = rememberLazyListState()
    val scrolled by remember(listState) { derivedStateOf { listState.isScrolled } }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val loadingAction: (@Composable androidx.compose.foundation.layout.RowScope.() -> Unit)? =
        if (isLoading) ({ TopBarProgress() }) else null
    SettingsBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Horizontal))) {
            GradeyTopBar(
                title = title,
                scrolled = scrolled,
                onBack = onBack,
                backDescription = stringResource(R.string.settings_back),
                actions = loadingAction,
            )
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.widthIn(max = GradeyMetrics.contentMaxWidth + SettingsPagePadding * 2).fillMaxSize().testTag("settingsDetailScroll"),
                    contentPadding = PaddingValues(
                        start = SettingsPagePadding, end = SettingsPagePadding,
                        top = GradeySpacing.lg, bottom = GradeySpacing.xxl + bottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl),
                ) {
                    if (errorMessage != null) item(key = "error", contentType = "error") {
                        InlineNotice(errorMessage) {
                            PlainLink(stringResource(R.string.settings_try_again), onRetry, enabled = !isLoading, showsArrow = false)
                        }
                    }
                    content()
                }
            }
        }
    }
}

@Composable
private fun TopBarProgress() {
    Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(Modifier.size(18.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
    }
}

/** Headline title of a detail section that has no rows under it (iOS `DetailSectionHeader`). */
@Composable
internal fun DetailSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs).semantics { heading() },
        style = GradeyType.headline,
        color = MaterialTheme.colorScheme.onSurface,
    )
}

/** Secondary footnote with an info glyph (iOS `SettingsAvailabilityMessage`). */
@Composable
internal fun AvailabilityMessage(text: String, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Row(modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        GradeyIcon(HugeIcons.InformationCircle, Modifier.padding(top = 1.dp), size = 14.dp, tint = secondary)
        Text(text, Modifier.weight(1f), style = GradeyType.footnote, color = secondary)
    }
}

/** One line of state under a connected service's name (iOS `SettingsRowStatusLabel`). */
@Composable
internal fun RowStatusLabel(text: String, icon: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        GradeyIcon(icon, size = 13.dp, tint = color)
        Text(text, style = GradeyType.captionSemibold, color = color)
    }
}

/**
 * A linked school or canteen account: icon, name and subtitle, the "…" menu with its actions, and
 * the state lines under the name (iOS `connectedAccountRow`).
 */
@Composable
internal fun LinkedServiceRow(
    accountID: String,
    icon: String,
    name: String,
    subtitle: String,
    menu: (@Composable ColumnScope.(close: () -> Unit) -> Unit)?,
    details: (@Composable ColumnScope.() -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.md),
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            SettingsIcon(icon)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(name, Modifier.testTag("linkedAccountRow-$accountID"), style = GradeyType.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
            }
            if (menu != null) RowMenu(accountID, menu)
        }
        if (details != null) {
            Column(
                Modifier.fillMaxWidth().padding(start = SettingsIconFrame + GradeySpacing.md),
                verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
                content = details,
            )
        }
    }
}

/** The "…" button that opens a row's actions (iOS `SettingsRowMenuLabel` + `Menu`). */
@Composable
private fun RowMenu(accountID: String, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Box(
            Modifier.size(GradeyMetrics.minTouch).clip(CircleShape)
                .clickable(role = Role.Button) { expanded = true }
                .testTag("linkedAccountMenu-$accountID"),
            contentAlignment = Alignment.Center,
        ) {
            GradeyIcon(
                HugeIcons.MoreHorizontalCircle01, size = 20.dp, tint = MaterialTheme.gradeyColors.secondaryLabel,
                contentDescription = stringResource(R.string.settings_account_actions),
            )
        }
        SettingsDropdownMenu(expanded = expanded, onDismiss = { expanded = false }) { content { expanded = false } }
    }
}

/** Material dropdown in the grouped-surface colour and Gradey radius. */
@Composable
internal fun SettingsDropdownMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(GradeyRadius.sm),
        containerColor = MaterialTheme.gradeyColors.secondaryGroupedBackground,
        tonalElevation = 0.dp,
        content = content,
    )
}

/** One dropdown action: glyph and body text, red for destructive actions. */
@Composable
internal fun SettingsMenuItem(
    text: String,
    icon: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    color: Color = MaterialTheme.colorScheme.onSurface,
) {
    val tint = color.copy(alpha = if (enabled) 1f else 0.45f)
    DropdownMenuItem(
        text = { Text(text, style = GradeyType.body, color = tint) },
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        leadingIcon = { if (icon != null) GradeyIcon(icon, size = 18.dp, tint = tint) else Spacer(Modifier.size(18.dp)) },
    )
}

/** A row of the canteen or school group that offers to retry a cloud link (iOS `cloudLinkWarning`). */
@Composable
internal fun CloudLinkWarning(message: String, actionLabel: String, onAction: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val orange = MaterialTheme.gradeyColors.orange
    Column(modifier.fillMaxWidth().padding(start = GradeySpacing.xs, end = GradeySpacing.xs, top = GradeySpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            SettingsIcon(HugeIcons.Alert02, color = orange)
            Text(message, Modifier.weight(1f), style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurface)
        }
        PlainLink(actionLabel, onAction, Modifier.padding(start = SettingsIconFrame + GradeySpacing.md), enabled = enabled)
    }
}

/** Display state of a linked account, for rows and the overview. */
@Immutable
internal data class LinkedStatus(val needsAttention: Boolean, val isActive: Boolean)

@Composable
internal fun linkedStatusText(status: String, reason: String?): String =
    reason?.takeIf { it.isNotBlank() } ?: stringResource(
        when (status) {
            "active" -> R.string.settings_status_active
            "paused" -> R.string.settings_status_paused
            "linking" -> R.string.settings_status_linking
            "failed" -> R.string.settings_status_failed
            else -> R.string.settings_status_action_required
        },
    )
