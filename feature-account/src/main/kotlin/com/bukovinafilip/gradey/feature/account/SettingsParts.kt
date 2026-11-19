package com.bukovinafilip.gradey.feature.account

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTonalButton
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
import java.time.format.DateTimeFormatter

/** Side padding of every Settings page (iOS `.padding(.horizontal, 20)`). */
internal val SettingsPagePadding = 20.dp

/** Gap between the overview's groups (iOS `VStack(spacing: 28)`). */
internal val SettingsOverviewSpacing = 28.dp

/** Vertical padding of a group whose content isn't rows (the iOS settings surface with its default padding of 20). */
internal val GroupContentPadding = 20.dp

// Hugeicons glyphs the iOS Settings uses that `HugeIcons` doesn't name yet (same font file).
internal const val IconUserAdd02 = "user-add-02"
internal const val IconBellDot = "bell-dot"
internal const val IconNotificationOff01 = "notification-off-01"
internal const val IconLegalDocument01 = "legal-document-01"
internal const val IconGithub01 = "github-01"

/** Whether this device may post notifications (iOS `NotificationAuthorizationStatus`). */
internal enum class NotificationPermission { NOT_DETERMINED, DENIED, AUTHORIZED }

/**
 * Reads the notification permission again whenever the window regains focus, so the row updates
 * after the system permission prompt or Android's notification settings close (iOS re-reads it
 * when the scene becomes active).
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

/** Short time in the user's 12/24-hour setting (iOS `.formatted(date: .omitted, time: .shortened)`). */
@Composable
internal fun rememberShortTimeFormatter(): DateTimeFormatter {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(context, locale) {
        val skeleton = if (DateFormat.is24HourFormat(context)) "Hm" else "hma"
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
}

/** A stored "HH:mm" quiet-hours time as the user reads times. */
internal fun formatQuietTime(value: String, formatter: DateTimeFormatter): String =
    runCatching { parseQuietTime(value).format(formatter) }.getOrDefault(value)

/**
 * One pushed Settings page: its own background, an inline-title top bar with Back, and a lazy
 * column of sections 24 dp apart (iOS `detailPage`: 20 dp sides, 24 dp top, max width 760).
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
    val loadingAction: (@Composable RowScope.() -> Unit)? = if (isLoading) ({ TopBarProgress() }) else null
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
                    modifier = Modifier.widthIn(max = GradeyMetrics.contentMaxWidth).fillMaxSize().testTag("settingsDetailScroll"),
                    contentPadding = PaddingValues(
                        start = SettingsPagePadding, end = SettingsPagePadding,
                        top = GradeySpacing.xl, bottom = GradeySpacing.xxl + bottomInset,
                    ),
                    verticalArrangement = Arrangement.spacedBy(GradeyMetrics.sectionSpacing),
                ) {
                    if (errorMessage != null) item(key = "error", contentType = "error") { SettingsErrorNotice(errorMessage, isLoading, onRetry) }
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

/** The last request's error with a retry (iOS shows it as an alert). */
@Composable
internal fun SettingsErrorNotice(message: String, isLoading: Boolean, onRetry: () -> Unit) {
    InlineNotice(message) {
        PlainLink(stringResource(R.string.settings_try_again), onRetry, enabled = !isLoading, showsArrow = false)
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

/** Icon and text in one colour, for the guest heading and the name error (iOS `SettingsHugeiconLabel`). */
@Composable
internal fun IconLabel(text: String, icon: String, color: Color, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier, iconColor: Color = color) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) { GradeyIcon(icon, size = 15.dp, tint = iconColor) }
        Text(text, Modifier.weight(1f, fill = false), style = style, color = color)
    }
}

/** One line of state under a connected service's name (iOS `SettingsRowStatusLabel`). */
@Composable
internal fun RowStatusLabel(text: String, icon: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier.padding(horizontal = GradeySpacing.xs), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        GradeyIcon(icon, size = 13.dp, tint = color)
        Text(text, style = GradeyType.captionSemibold, color = color)
    }
}

/**
 * A linked school or canteen account: icon, name and subtitle, the "…" menu with its actions, and
 * the state lines under the name, aligned with the name (iOS `connectedAccountRow`).
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
            // Links and switches carry 4 dp of their own inset; the column starts 4 dp early so
            // every line under the name starts where the name does.
            Column(
                Modifier.fillMaxWidth().padding(start = SettingsIconFrame + GradeySpacing.md - GradeySpacing.xs),
                verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
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
                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.settings_account_actions)) { expanded = true }
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

/**
 * A label with its value in a menu on the right (iOS `Picker` with `.pickerStyle(.menu)`): the
 * value in the brand colour with a chevron, the options in a dropdown with a tick on the current one.
 */
@Composable
internal fun MenuPickerRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        Text(title, Modifier.weight(1f), style = GradeyType.bodyMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f))
        Box {
            Row(
                Modifier.heightIn(min = GradeyMetrics.minTouch).clip(RoundedCornerShape(GradeyRadius.xs))
                    .clickable(enabled = enabled, role = Role.DropdownList, onClickLabel = title) { expanded = true }
                    .padding(horizontal = GradeySpacing.sm)
                    .alpha(if (enabled) 1f else 0.45f),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
            ) {
                Text(options.getOrElse(selectedIndex) { "" }, style = GradeyType.body, color = primary, maxLines = 1)
                GradeyIcon(HugeIcons.ArrowDown01, size = 13.dp, tint = primary)
            }
            SettingsDropdownMenu(expanded = expanded, onDismiss = { expanded = false }) {
                options.forEachIndexed { index, option ->
                    SettingsMenuItem(option, if (index == selectedIndex) HugeIcons.Tick02 else null, { expanded = false; if (index != selectedIndex) onSelect(index) })
                }
            }
        }
    }
}

/** One choice in a list of options: medium title and a check on the selected one (iOS `AppLanguageOptionsList` row). */
@Composable
internal fun SelectableOptionRow(title: String, selected: Boolean, onSelect: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, enabled: Boolean = true) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onSelect)
            .padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = GradeyType.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
        }
        if (selected) Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            GradeyIcon(HugeIcons.CheckmarkCircle02, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
        }
    }
}

/**
 * The notification permission of this device: state icon, title, the state in its colour and the
 * bordered action that resolves it (iOS "This device" first row).
 */
@Composable
internal fun NotificationPermissionRow(permission: NotificationPermission, onAction: (() -> Unit)?, modifier: Modifier = Modifier) {
    val tokens = MaterialTheme.gradeyColors
    val (icon, color, label) = when (permission) {
        NotificationPermission.NOT_DETERMINED -> Triple(IconBellDot, tokens.secondaryLabel, R.string.settings_permission_not_determined)
        NotificationPermission.DENIED -> Triple(IconNotificationOff01, tokens.orange, R.string.settings_permission_denied)
        NotificationPermission.AUTHORIZED -> Triple(HugeIcons.CheckmarkCircle02, MaterialTheme.colorScheme.primary, R.string.settings_permission_authorized)
    }
    Row(
        modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.md),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        SettingsIcon(icon)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.settings_permission_title), style = GradeyType.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(label), style = GradeyType.caption, color = color)
        }
        if (permission != NotificationPermission.AUTHORIZED && onAction != null) GradeyTonalButton(
            stringResource(if (permission == NotificationPermission.DENIED) R.string.settings_permission_open_settings else R.string.settings_permission_enable),
            onAction, Modifier.testTag("settingsNotificationPermission"),
        )
    }
}

/** The first row of a service's list when its cloud link is missing: what's wrong, and the one thing to do (iOS `cloudLinkWarning`). */
@Composable
internal fun CloudLinkWarning(message: String, actionLabel: String, onAction: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(start = GradeySpacing.xs, end = GradeySpacing.xs, top = GradeySpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            SettingsIcon(HugeIcons.Alert02, color = MaterialTheme.gradeyColors.orange)
            Text(message, Modifier.weight(1f), style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurface)
        }
        PlainLink(actionLabel, onAction, Modifier.padding(start = SettingsIconFrame + GradeySpacing.md - GradeySpacing.xs), enabled = enabled)
    }
}

/** Display text of a linked account's state: the provider's reason when it gave one. */
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

/** Content inset of a group whose body isn't rows (the iOS settings surface with its default padding). */
internal fun Modifier.surfaceContent(vertical: Dp = GroupContentPadding): Modifier =
    fillMaxWidth().padding(horizontal = GradeySpacing.xs, vertical = vertical)
