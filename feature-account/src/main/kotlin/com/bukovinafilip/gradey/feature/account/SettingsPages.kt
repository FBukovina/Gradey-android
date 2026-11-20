package com.bukovinafilip.gradey.feature.account

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.model.AppLanguage
import com.bukovinafilip.gradey.model.GradeyAccount
import com.bukovinafilip.gradey.model.Friend
import com.bukovinafilip.gradey.model.FriendTimetableSource
import com.bukovinafilip.gradey.model.LinkedCanteenAccount
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.NotificationLockScreenDetail
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTonalButton
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SettingsActionRow
import com.bukovinafilip.gradey.ui.SettingsGroup
import com.bukovinafilip.gradey.ui.SettingsIcon
import com.bukovinafilip.gradey.ui.SettingsRowDivider
import com.bukovinafilip.gradey.ui.SettingsToggleRow
import com.bukovinafilip.gradey.ui.SettingsValueRow
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.tabular
import kotlinx.coroutines.delay

internal const val DeveloperEmailUrl = "mailto:filip@openside.tech"
private const val DesignerEmailUrl = "mailto:tom@openside.tech"
private const val DeveloperInstagramUrl = "https://www.instagram.com/bukovinafilip"
private const val OpenSideUrl = "https://openside.tech"
private const val RepositoryUrl = "https://github.com/FBukovina/Gradey-android"
private const val EduPageRepositoryUrl = "https://github.com/EdupageAPI/edupage-api"

/** Focus waits for the page's slide-in so the keyboard doesn't open mid-transition. */
private const val NameFocusDelayMillis = 350L

/** A full-width divider for groups whose rows have no icon (iOS `Divider()` / `SettingsRowDivider(leadingInset: 20)`). */
@Composable
private fun FullRowDivider() = SettingsRowDivider(leadingInset = GradeySpacing.xs)

// MARK: - Account

/**
 * Account (iOS `accountDetail`): the demo or guest explanation, the Gradey profile name with its
 * identity, then student switch, sign out and account deletion as rows.
 */
internal fun LazyListScope.accountPage(
    account: GradeyAccount?,
    isGuest: Boolean,
    isDemo: Boolean,
    isLoading: Boolean,
    liveSignInAvailable: Boolean,
    onSignOut: () -> Unit,
    onGuestSignIn: (() -> Unit)?,
    onUpdateProfile: ((String) -> Unit)?,
    focusName: Boolean,
    onNameFocusHandled: () -> Unit,
    activeStudentName: String?,
    canSwitchStudent: Boolean,
    onOpenStudents: () -> Unit,
    onConfirmSignOut: () -> Unit,
    onConfirmDelete: (() -> Unit)?,
) {
    if (isDemo) item(key = "demo", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_demo_title)) {
            Column(Modifier.surfaceContent(), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                Text(
                    stringResource(if (liveSignInAvailable) R.string.settings_demo_message_live else R.string.settings_demo_message_offline),
                    style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel,
                )
                if (liveSignInAvailable) GradeyTonalButton(
                    stringResource(R.string.settings_demo_connect), onSignOut, Modifier.fillMaxWidth(),
                    enabled = !isLoading, icon = HugeIcons.School,
                )
            }
        }
    }
    if (isGuest) item(key = "guest", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_guest_account_title)) {
            Column(Modifier.surfaceContent(), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                IconLabel(
                    stringResource(R.string.settings_guest_account_heading), IconUserAdd02,
                    MaterialTheme.colorScheme.onSurface, GradeyType.headline,
                )
                if (onGuestSignIn != null) GradeyTonalButton(
                    stringResource(R.string.settings_guest_account_action), onGuestSignIn,
                    Modifier.fillMaxWidth().testTag("settingsGuestSignIn"), enabled = !isLoading, icon = HugeIcons.UserCheck02,
                )
            }
        }
    } else if (account != null && !isDemo) item(key = "profile", contentType = "group") {
        ProfileEditor(account, isLoading, onUpdateProfile, focusName, onNameFocusHandled)
    }
    item(key = "session", contentType = "group") {
        val red = MaterialTheme.gradeyColors.red
        SettingsGroup {
            if (canSwitchStudent) {
                SettingsActionRow(
                    HugeIcons.Students, stringResource(R.string.settings_switch_student), onOpenStudents,
                    Modifier.testTag("settingsSwitchStudent"), subtitle = activeStudentName, enabled = !isLoading,
                )
                SettingsRowDivider()
            }
            SettingsActionRow(
                HugeIcons.Logout01, stringResource(if (isGuest) R.string.settings_guest_sign_out else R.string.settings_sign_out), onConfirmSignOut,
                Modifier.testTag("settingsSignOut"), titleColor = red, iconColor = red, enabled = !isLoading, trailing = null,
            )
            if (onConfirmDelete != null) {
                SettingsRowDivider()
                SettingsActionRow(
                    HugeIcons.Delete02, stringResource(R.string.settings_delete_account), onConfirmDelete,
                    Modifier.testTag("settingsDeleteAccount"), titleColor = red, iconColor = red, enabled = !isLoading, trailing = null,
                )
            }
        }
    }
}

/**
 * The name field with its error and the Gradey ID email under it. Save appears once there is a
 * change to save (iOS `accountNameEditor`).
 */
@Composable
private fun ProfileEditor(
    account: GradeyAccount,
    isLoading: Boolean,
    onUpdateProfile: ((String) -> Unit)?,
    focusName: Boolean,
    onNameFocusHandled: () -> Unit,
) {
    var name by rememberSaveable(account.id, account.fullName) { mutableStateOf(account.fullName.orEmpty()) }
    val trimmed = name.trim()
    val tooLong = trimmed.length > MaxNameLength
    val canSave = onUpdateProfile != null && canSaveName(name, account.fullName)
    val save = { if (canSave && !isLoading) onUpdateProfile?.invoke(trimmed) }
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(focusName) {
        if (focusName) {
            delay(NameFocusDelayMillis)
            runCatching { focusRequester.requestFocus() }
            onNameFocusHandled()
        }
    }
    SettingsGroup(title = stringResource(R.string.settings_account_profile_title)) {
        Column(Modifier.surfaceContent(vertical = GradeySpacing.lg), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            Text(stringResource(R.string.settings_account_name_title), style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface)
            BrandTextField(
                value = name, onValueChange = { name = it },
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).testTag("settingsProfileName"),
                placeholder = stringResource(R.string.settings_account_name_placeholder),
                enabled = onUpdateProfile != null && !isLoading, isError = tooLong,
                imeAction = ImeAction.Done, onImeAction = save,
            )
            if (tooLong) IconLabel(
                stringResource(R.string.settings_account_name_too_long), HugeIcons.AlertCircle, MaterialTheme.gradeyColors.red, GradeyType.caption,
            )
            AnimatedVisibility(
                visible = canSave,
                enter = fadeIn(tween(200)) + expandVertically(tween(250)),
                exit = fadeOut(tween(150)) + shrinkVertically(tween(250)),
            ) {
                GradeyTonalButton(
                    stringResource(R.string.settings_account_name_save), save,
                    Modifier.fillMaxWidth().padding(top = GradeySpacing.xs).testTag("settingsProfileSave"), loading = isLoading,
                )
            }
        }
        account.email?.takeIf { it.isNotBlank() }?.let { email ->
            FullRowDivider()
            SettingsValueRow(stringResource(R.string.settings_account_email_title), email, Modifier.testTag("settingsAccountEmail"))
        }
    }
}

// MARK: - Connected services

/**
 * Connected services (iOS `connectedServicesDetail`): school accounts with their state, Strava.cz,
 * then the watch's school access.
 */
internal fun LazyListScope.connectedServicesPage(
    isGuest: Boolean,
    isDemo: Boolean,
    isLoading: Boolean,
    hasAccount: Boolean,
    localSchoolName: String?,
    schools: List<LinkedSchoolAccount>,
    activeAccountID: String?,
    newMarksEnabled: Boolean,
    onActivateAccount: (LinkedSchoolAccount) -> Unit,
    onAddAccount: () -> Unit,
    onReconnectAccount: ((LinkedSchoolAccount) -> Unit)?,
    onAccountNotificationsChange: ((LinkedSchoolAccount, Boolean) -> Unit)?,
    onRemove: (LinkedSchoolAccount) -> Unit,
    canteenAccounts: List<LinkedCanteenAccount>,
    canteenSession: StravaCZStoredSession?,
    currentCanteenLinkID: String?,
    onLinkCanteen: (() -> Unit)?,
    onOpenMeals: (() -> Unit)?,
    onRemoveCanteen: ((LinkedCanteenAccount) -> Unit)?,
    onOpenWatch: (() -> Unit)?,
    friends: List<Friend> = emptyList(),
    onAddFriend: (() -> Unit)? = null,
    onRenameFriend: (Friend) -> Unit = {},
    onRemoveFriend: (Friend) -> Unit = {},
) {
    item(key = "schools", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_connected_school_title)) {
            if (isGuest) {
                LinkedServiceRow(
                    accountID = "local", icon = HugeIcons.School,
                    name = localSchoolName ?: stringResource(R.string.settings_local_school_on_device),
                    subtitle = stringResource(R.string.settings_local_school_on_device), menu = null,
                    details = {
                        Text(
                            stringResource(R.string.settings_local_school_detail), Modifier.padding(horizontal = GradeySpacing.xs),
                            style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel,
                        )
                        PlainLink(stringResource(R.string.settings_change_school), onAddAccount, enabled = !isLoading)
                    },
                )
            } else {
                schools.forEach { linked ->
                    key(linked.id) {
                        SchoolAccountRow(
                            linked = linked, inUse = linked.id == activeAccountID, showsInUse = schools.size > 1,
                            isDemo = isDemo, isLoading = isLoading, newMarksEnabled = newMarksEnabled,
                            onActivate = onActivateAccount, onReconnect = onReconnectAccount,
                            onNotificationsChange = onAccountNotificationsChange, onRemove = onRemove,
                        )
                        SettingsRowDivider()
                    }
                }
                SettingsActionRow(
                    if (schools.isEmpty()) HugeIcons.School else HugeIcons.Add01,
                    stringResource(if (schools.isEmpty()) R.string.settings_link_school else R.string.settings_link_another_school),
                    onAddAccount, Modifier.testTag("settingsLinkSchool"), enabled = !isLoading && !isDemo,
                )
            }
        }
    }
    item(key = "canteen", contentType = "group") {
        CanteenGroup(
            isGuest = isGuest, isDemo = isDemo, isLoading = isLoading, hasAccount = hasAccount,
            canteenAccounts = canteenAccounts, canteenSession = canteenSession, currentCanteenLinkID = currentCanteenLinkID,
            onLinkCanteen = onLinkCanteen, onOpenMeals = onOpenMeals, onRemoveCanteen = onRemoveCanteen,
        )
    }
    // Friends (iOS `FriendsSettingsSection`): who is there, where their timetable comes from, then the row that adds one.
    if (onAddFriend != null) item(key = "friends", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_friends_title)) {
            friends.forEach { friend ->
                key(friend.id) {
                    val source = friend.source
                    LinkedServiceRow(
                        accountID = "friend-${friend.id}",
                        icon = if (source is FriendTimetableSource.Bakalari) HugeIcons.Key01 else HugeIcons.Globe02,
                        name = friend.name,
                        subtitle = when (source) {
                            is FriendTimetableSource.Bakalari -> listOfNotNull(stringResource(R.string.settings_friends_source_bakalari), source.schoolName).joinToString(" · ")
                            is FriendTimetableSource.PublicClass -> stringResource(R.string.settings_friends_source_public) + " · " + source.className
                        },
                        menu = { close ->
                            SettingsMenuItem(stringResource(R.string.settings_friends_rename), HugeIcons.Edit02, { close(); onRenameFriend(friend) }, Modifier.testTag("friendRename-${friend.id}"))
                            SettingsMenuItem(
                                stringResource(R.string.settings_friends_remove), HugeIcons.Delete02, { close(); onRemoveFriend(friend) },
                                Modifier.testTag("friendRemove-${friend.id}"), color = MaterialTheme.gradeyColors.red,
                            )
                        },
                        details = null,
                    )
                    SettingsRowDivider()
                }
            }
            SettingsActionRow(HugeIcons.UserAdd01, stringResource(R.string.settings_friends_add), onAddFriend, Modifier.testTag("settingsAddFriendButton"), enabled = !isLoading)
        }
    }
    if (onOpenWatch != null) item(key = "watch", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_watch_title), footer = stringResource(R.string.settings_watch_description)) {
            SettingsActionRow(HugeIcons.Watch01, stringResource(R.string.settings_watch_manage), onOpenWatch, Modifier.testTag("settingsWatchAccess"), enabled = !isLoading)
        }
    }
    if (isGuest) item(key = "guestNote", contentType = "notice") { AvailabilityMessage(stringResource(R.string.settings_connected_guest_explanation)) }
}

/** A linked school: who it is, what it needs and its mark alerts. Its actions are in the menu. */
@Composable
private fun SchoolAccountRow(
    linked: LinkedSchoolAccount,
    inUse: Boolean,
    showsInUse: Boolean,
    isDemo: Boolean,
    isLoading: Boolean,
    newMarksEnabled: Boolean,
    onActivate: (LinkedSchoolAccount) -> Unit,
    onReconnect: ((LinkedSchoolAccount) -> Unit)?,
    onNotificationsChange: ((LinkedSchoolAccount, Boolean) -> Unit)?,
    onRemove: (LinkedSchoolAccount) -> Unit,
) {
    val attention = linkedStatusNeedsAttention(linked.status)
    LinkedServiceRow(
        accountID = linked.id, icon = HugeIcons.School, name = linked.displayName, subtitle = linked.subtitle(),
        menu = { close ->
            if (!inUse) SettingsMenuItem(
                stringResource(R.string.settings_use_on_device), HugeIcons.Tick02, { close(); onActivate(linked) },
                Modifier.testTag("linkedAccountActivate-${linked.id}"), enabled = !isLoading && linked.status == "active",
            )
            SettingsMenuItem(
                stringResource(R.string.settings_unlink), HugeIcons.Delete02, { close(); onRemove(linked) },
                Modifier.testTag("linkedAccountUnlink-${linked.id}"), enabled = !isLoading && !isDemo, color = MaterialTheme.gradeyColors.red,
            )
        },
        details = {
            if (linked.status != "active") {
                RowStatusLabel(linkedStatusText(linked.status, linked.actionRequiredReason), HugeIcons.Alert02, MaterialTheme.gradeyColors.orange)
            } else if (inUse && showsInUse) {
                RowStatusLabel(stringResource(R.string.settings_in_use), HugeIcons.Tick02, MaterialTheme.colorScheme.primary)
            }
            if (attention && onReconnect != null && !isDemo) PlainLink(
                stringResource(R.string.settings_reconnect), { onReconnect(linked) },
                Modifier.testTag("linkedAccountReconnect-${linked.id}"), enabled = !isLoading,
            )
            if (onNotificationsChange != null && !isDemo) SettingsToggleRow(
                stringResource(R.string.settings_mark_notifications), null, linked.notificationsEnabled,
                enabled = !isLoading && newMarksEnabled, onChange = { onNotificationsChange(linked, it) },
                modifier = Modifier.testTag("linkedAccountNotifications-${linked.id}"),
            )
        },
    )
}

/**
 * Strava.cz: the canteen signed in on this device and every canteen linked to Gradey ID, then the
 * way to connect one or open Meals.
 */
@Composable
private fun CanteenGroup(
    isGuest: Boolean,
    isDemo: Boolean,
    isLoading: Boolean,
    hasAccount: Boolean,
    canteenAccounts: List<LinkedCanteenAccount>,
    canteenSession: StravaCZStoredSession?,
    currentCanteenLinkID: String?,
    onLinkCanteen: (() -> Unit)?,
    onOpenMeals: (() -> Unit)?,
    onRemoveCanteen: ((LinkedCanteenAccount) -> Unit)?,
) {
    val cloudAccounts = if (isGuest) emptyList() else canteenAccounts
    // The canteen of this device is shown once: as its Gradey ID link when there is one.
    val localIsLinked = currentCanteenLinkID != null && cloudAccounts.any { it.id == currentCanteenLinkID }
    val localSession = canteenSession?.takeIf { !localIsLinked }
    val provider = stringResource(R.string.settings_strava_provider)
    SettingsGroup(
        title = stringResource(R.string.settings_connected_canteen_title),
        footer = if (cloudAccounts.isNotEmpty() && currentCanteenLinkID == null) stringResource(R.string.settings_canteen_existing_link_note) else null,
    ) {
        if (canteenSession != null && onLinkCanteen != null && hasAccount && !isGuest && currentCanteenLinkID == null) {
            CloudLinkWarning(
                stringResource(R.string.settings_canteen_cloud_warning), stringResource(R.string.settings_link_strava), onLinkCanteen,
                enabled = !isLoading && !isDemo, modifier = Modifier.testTag("settingsCanteenCloudWarning"),
            )
            SettingsRowDivider()
        }
        if (localSession != null) {
            LinkedServiceRow(
                accountID = "canteen-local", icon = HugeIcons.Restaurant02,
                name = localSession.canteenName ?: stringResource(R.string.settings_canteen_number, localSession.canteenNumber),
                subtitle = provider, menu = null,
                details = { RowStatusLabel(stringResource(R.string.settings_canteen_on_device), HugeIcons.Tick02, MaterialTheme.colorScheme.primary) },
            )
            SettingsRowDivider()
        }
        cloudAccounts.forEach { linked ->
            key(linked.id) {
                val onThisDevice = canteenSession != null && linked.id == currentCanteenLinkID
                LinkedServiceRow(
                    accountID = linked.id, icon = HugeIcons.Restaurant02, name = linked.displayName,
                    subtitle = linked.canteenName?.takeIf { it.isNotBlank() } ?: provider,
                    menu = if (onRemoveCanteen != null) ({ close ->
                        SettingsMenuItem(
                            stringResource(R.string.settings_unlink), HugeIcons.Delete02, { close(); onRemoveCanteen(linked) },
                            Modifier.testTag("linkedAccountUnlink-${linked.id}"), enabled = !isLoading && !isDemo, color = MaterialTheme.gradeyColors.red,
                        )
                    }) else null,
                    details = when {
                        linked.status != "active" -> ({
                            RowStatusLabel(linkedStatusText(linked.status, linked.actionRequiredReason), HugeIcons.Alert02, MaterialTheme.gradeyColors.orange)
                        })
                        onThisDevice -> ({
                            RowStatusLabel(stringResource(R.string.settings_canteen_on_device), HugeIcons.Tick02, MaterialTheme.colorScheme.primary)
                        })
                        else -> null
                    },
                )
                SettingsRowDivider()
            }
        }
        SettingsActionRow(
            if (canteenSession == null) HugeIcons.Restaurant02 else HugeIcons.SpoonAndFork,
            stringResource(if (canteenSession == null) R.string.settings_link_strava else R.string.settings_open_meals),
            onOpenMeals, Modifier.testTag(if (canteenSession == null) "settingsLinkStrava" else "settingsOpenMeals"),
            enabled = onOpenMeals != null && !isLoading,
        )
    }
}

// MARK: - Notifications

/** Notifications (iOS `notificationsDetail`): this device, mark alerts, then quiet hours. */
internal fun LazyListScope.notificationsPage(
    preferences: NotificationPreferences,
    onPreferencesChange: (NotificationPreferences) -> Unit,
    isGuest: Boolean,
    isDemo: Boolean,
    isLoading: Boolean,
    hasSchool: Boolean,
    plannerRemindersEnabled: Boolean,
    onPlannerRemindersChange: ((Boolean) -> Unit)?,
    onEditQuietHours: (start: Boolean) -> Unit,
    onNotificationSettings: (() -> Unit)?,
) {
    val available = markNotificationsAvailable(isGuest, hasSchool, isDemo)
    val controlsEnabled = !isLoading && notificationControlsEnabled(available, preferences, plannerRemindersEnabled)
    val quietControlsEnabled = controlsEnabled && preferences.quietHoursEnabled
    val update = { changed: NotificationPreferences -> onPreferencesChange(changed.withDeviceTimeZone()) }
    item(key = "device", contentType = "group") {
        val permission = rememberNotificationPermission()
        SettingsGroup(
            title = stringResource(R.string.settings_notifications_device_title),
            footer = if (onPlannerRemindersChange != null) stringResource(R.string.settings_planner_reminder_note) else null,
        ) {
            NotificationPermissionRow(permission, onNotificationSettings)
            if (permission == NotificationPermission.AUTHORIZED && onNotificationSettings != null) {
                SettingsRowDivider()
                SettingsActionRow(
                    HugeIcons.SmartPhone01, stringResource(R.string.settings_android_notification_settings), onNotificationSettings,
                    Modifier.testTag("settingsNotificationPermission"),
                )
            }
            if (onPlannerRemindersChange != null) {
                FullRowDivider()
                SettingsToggleRow(
                    stringResource(R.string.settings_planner_reminder), null, plannerRemindersEnabled, enabled = !isLoading && !isDemo,
                    onChange = onPlannerRemindersChange, modifier = Modifier.testTag("settingsPlannerReminders"),
                )
            }
        }
    }
    item(key = "marks", contentType = "group") {
        val permission = rememberNotificationPermission()
        SettingsGroup(title = stringResource(R.string.settings_mark_notifications_title)) {
            SettingsToggleRow(
                stringResource(R.string.settings_mark_notifications_toggle), null, preferences.newMarksEnabled && available,
                enabled = available && !isLoading,
                onChange = { enabled ->
                    update(preferences.copy(newMarksEnabled = enabled))
                    // Like iOS, turning alerts on asks for the permission or opens the system settings.
                    if (enabled && permission != NotificationPermission.AUTHORIZED) onNotificationSettings?.invoke()
                },
                modifier = Modifier.testTag("settingsNewMarksToggle"),
            )
            FullRowDivider()
            val details = NotificationLockScreenDetail.entries
            MenuPickerRow(
                title = stringResource(R.string.settings_lock_screen),
                options = listOf(stringResource(R.string.settings_lock_private), stringResource(R.string.settings_lock_subject), stringResource(R.string.settings_lock_full)),
                selectedIndex = details.indexOf(preferences.lockScreenDetail),
                onSelect = { update(preferences.copy(lockScreenDetail = details[it])) },
                enabled = controlsEnabled, modifier = Modifier.testTag("settingsLockScreenDetail"),
            )
        }
    }
    item(key = "quietHours", contentType = "group") {
        val formatter = rememberShortTimeFormatter()
        SettingsGroup(
            title = stringResource(R.string.settings_quiet_hours_title),
            footer = if (preferences.quietHoursEnabled) stringResource(R.string.settings_time_zone_value, preferences.quietHoursTimeZoneIdentifier) else null,
        ) {
            SettingsToggleRow(
                stringResource(R.string.settings_quiet_hours_toggle), null, preferences.quietHoursEnabled, enabled = controlsEnabled,
                onChange = { update(preferences.copy(quietHoursEnabled = it)) }, modifier = Modifier.testTag("settingsQuietHoursToggle"),
            )
            FullRowDivider()
            val dim = Modifier.alpha(if (quietControlsEnabled) 1f else 0.45f)
            SettingsValueRow(
                stringResource(R.string.settings_quiet_hours_start), formatQuietTime(preferences.quietHoursStart, formatter),
                dim.testTag("settingsQuietHoursStart"), onClick = { onEditQuietHours(true) }, enabled = quietControlsEnabled,
            )
            SettingsValueRow(
                stringResource(R.string.settings_quiet_hours_end), formatQuietTime(preferences.quietHoursEnd, formatter),
                dim.testTag("settingsQuietHoursEnd"), onClick = { onEditQuietHours(false) }, enabled = quietControlsEnabled,
            )
        }
    }
    if (isGuest) item(key = "guestNote", contentType = "notice") { AvailabilityMessage(stringResource(R.string.settings_notifications_unavailable_guest)) }
    else if (!hasSchool && !isDemo) item(key = "schoolNote", contentType = "notice") { AvailabilityMessage(stringResource(R.string.settings_notifications_unavailable_school)) }
}

// MARK: - Privacy & data

/** Privacy & data (iOS `privacyDataDetail`): policies, the age confirmation, then the data export. */
internal fun LazyListScope.privacyPage(
    signedIn: Boolean,
    isGuest: Boolean,
    isLoading: Boolean,
    ageAttestation: String?,
    onOpenPrivacy: (() -> Unit)?,
    onOpenTerms: (() -> Unit)?,
    onOpenPrivacyChanges: (() -> Unit)?,
    onExportData: (() -> Unit)?,
) {
    if (onOpenPrivacy != null || onOpenTerms != null || onOpenPrivacyChanges != null) item(key = "documents", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_privacy_documents_title)) {
            val rows = listOfNotNull(
                onOpenPrivacy?.let { Triple(HugeIcons.SecurityLock, R.string.settings_privacy_policy, it) },
                onOpenTerms?.let { Triple(HugeIcons.LeftToRightListBullet, R.string.settings_terms, it) },
                onOpenPrivacyChanges?.let { Triple(IconLegalDocument01, R.string.settings_privacy_changes, it) },
            )
            rows.forEachIndexed { index, (icon, title, action) ->
                if (index > 0) SettingsRowDivider()
                SettingsActionRow(icon, stringResource(title), action)
            }
        }
    }
    item(key = "age", contentType = "notice") {
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            DetailSectionTitle(stringResource(R.string.settings_age_title))
            AvailabilityMessage(
                stringResource(
                    when (ageAttestation) {
                        "sixteenOrOlder" -> R.string.settings_age_sixteen
                        "thirteenToFifteenWithParent", "underThirteen" -> R.string.settings_age_teen
                        else -> R.string.settings_age_unknown
                    },
                ),
                Modifier.testTag("settingsPrivacyAgeStatus"),
            )
            AvailabilityMessage(stringResource(R.string.settings_age_local))
        }
    }
    if (onExportData != null) item(key = "export", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_export_title)) {
            SettingsActionRow(
                HugeIcons.FileExport, stringResource(R.string.settings_export_prepare), onExportData, Modifier.testTag("settingsExportData"),
                enabled = signedIn && !isLoading, trailing = null,
            )
        }
    }
    if (isGuest) item(key = "guestNote", contentType = "notice") { AvailabilityMessage(stringResource(R.string.settings_privacy_guest_explanation)) }
}

// MARK: - Language and preferences

/** Language and preferences (iOS `appPreferencesDetail` + `AppLanguageOptionsList`). */
internal fun LazyListScope.preferencesPage(
    appLanguage: AppLanguage,
    systemLanguageCode: String,
    onLanguageChange: ((AppLanguage) -> Unit)?,
    showMealsTab: Boolean,
    onShowMealsTabChange: ((Boolean) -> Unit)?,
) {
    if (onLanguageChange != null) {
        item(key = "language", contentType = "group") {
            val selected = appLanguage.pickerSelection(systemLanguageCode)
            SettingsGroup {
                listOf(AppLanguage.ENGLISH to R.string.settings_language_english, AppLanguage.CZECH to R.string.settings_language_czech)
                    .forEachIndexed { index, (language, label) ->
                        if (index > 0) FullRowDivider()
                        SelectableOptionRow(
                            stringResource(label), selected == language,
                            { if (selected != language) onLanguageChange(appLanguage.selectingPickerLanguage(language, systemLanguageCode)) },
                            Modifier.testTag(if (language == AppLanguage.CZECH) "settingsLanguageCzech" else "settingsLanguageEnglish"),
                        )
                    }
            }
        }
        item(key = "chronicallyOnline", contentType = "group") {
            SettingsGroup {
                SettingsToggleRow(
                    stringResource(R.string.settings_chronically_online_title), stringResource(R.string.settings_chronically_online_subtitle),
                    appLanguage.isChronicallyOnline, enabled = true,
                    onChange = { onLanguageChange(appLanguage.withChronicallyOnline(it, systemLanguageCode)) },
                    modifier = Modifier.testTag("settingsChronicallyOnline"),
                )
            }
        }
    }
    if (onShowMealsTabChange != null) item(key = "meals", contentType = "group") {
        SettingsGroup {
            SettingsToggleRow(
                stringResource(R.string.settings_show_meals_tab), null, showMealsTab, enabled = true, onChange = onShowMealsTabChange,
                modifier = Modifier.testTag("settingsShowMealsTab"),
            )
        }
    }
}

// MARK: - Support & about

/** Support & about (iOS `supportAboutDetail`): help and feedback, then credits, updates and the version. */
internal fun LazyListScope.supportPage(
    onOpenHelp: (() -> Unit)?,
    onOpenSupport: (() -> Unit)?,
    onOpenUpdates: (() -> Unit)?,
    onOpenCredits: () -> Unit,
    versionName: String?,
    versionCode: Int?,
) {
    item(key = "help", contentType = "group") {
        val uriHandler = LocalUriHandler.current
        SettingsGroup(title = stringResource(R.string.settings_support_title)) {
            if (onOpenHelp != null) {
                SettingsActionRow(HugeIcons.MessageMultiple01, stringResource(R.string.settings_support_chat), onOpenHelp, Modifier.testTag("settingsSupportChat"))
                SettingsRowDivider()
            }
            if (onOpenSupport != null) {
                SettingsActionRow(HugeIcons.Favourite, stringResource(R.string.settings_support_tips), onOpenSupport, Modifier.testTag("settingsSupportGradey"))
                SettingsRowDivider()
            }
            SettingsActionRow(
                HugeIcons.Mail01, stringResource(R.string.settings_support_contact), { runCatching { uriHandler.openUri(DeveloperEmailUrl) } },
                Modifier.testTag("settingsDeveloperContact"), subtitle = DeveloperEmailUrl.removePrefix("mailto:"),
            )
            SettingsRowDivider()
            SettingsActionRow(
                IconGithub01, stringResource(R.string.settings_github), { runCatching { uriHandler.openUri(RepositoryUrl) } },
                Modifier.testTag("settingsGithubRepository"),
            )
        }
    }
    item(key = "about", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_about_title), footer = stringResource(R.string.settings_independent_note)) {
            SettingsActionRow(HugeIcons.UserGroup, stringResource(R.string.settings_credits_title), onOpenCredits, Modifier.testTag("settingsCredits"))
            if (onOpenUpdates != null) {
                SettingsRowDivider()
                SettingsActionRow(
                    HugeIcons.Download04, stringResource(R.string.settings_app_updates), onOpenUpdates, Modifier.testTag("settingsAppUpdates"),
                    subtitle = stringResource(R.string.settings_app_updates_subtitle),
                )
            }
            FullRowDivider()
            SettingsValueRow(
                stringResource(R.string.settings_version),
                when {
                    versionName == null -> stringResource(R.string.settings_version_unknown)
                    versionCode != null -> stringResource(R.string.settings_version_value, versionName, versionCode)
                    else -> versionName
                },
                Modifier.heightIn(min = 52.dp).testTag("settingsVersionRow"),
            )
        }
    }
}

// MARK: - Credits

/** Credits (iOS `CreditsView`): the studio, the team, then the school-system attributions. */
internal fun LazyListScope.creditsPage() {
    item(key = "openSide", contentType = "group") {
        val uriHandler = LocalUriHandler.current
        SettingsGroup {
            Column(
                Modifier.fillMaxWidth().clickable(role = Role.Button) { runCatching { uriHandler.openUri(OpenSideUrl) } }
                    .surfaceContent().testTag("creditsOpenSideLink"),
                verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
            ) {
                CreditsRoleLabel(stringResource(R.string.settings_credits_made_by))
                Row {
                    Text(stringResource(R.string.settings_credits_openside_open), style = GradeyType.display(32), color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.settings_credits_openside_side), style = GradeyType.display(32), color = MaterialTheme.gradeyColors.purple)
                }
                CreditLink(stringResource(R.string.settings_credits_openside_domain), icon = null, GradeyType.subheadlineSemibold)
            }
        }
    }
    item(key = "team", contentType = "group") {
        SettingsGroup(Modifier.testTag("creditsTeam")) {
            CreditPerson(
                stringResource(R.string.settings_credits_lead_developer), stringResource(R.string.settings_credits_filip_name),
                DeveloperEmailUrl, DeveloperInstagramUrl,
            )
            FullRowDivider()
            CreditPerson(stringResource(R.string.settings_credits_lead_graphics), stringResource(R.string.settings_credits_tomas_name), DesignerEmailUrl, null)
        }
    }
    item(key = "bakalari", contentType = "group") {
        Attribution(
            stringResource(R.string.settings_credits_bakalari_title), stringResource(R.string.settings_credits_bakalari_message), null,
            Modifier.testTag("creditsBakalariAttribution"),
        )
    }
    item(key = "edupage", contentType = "group") {
        Attribution(
            stringResource(R.string.settings_credits_edupage_title), stringResource(R.string.settings_credits_edupage_message),
            stringResource(R.string.settings_credits_edupage_repository) to EduPageRepositoryUrl, Modifier.testTag("creditsEduPageAttribution"),
        )
    }
}

/** Bold uppercase caption over a credit (iOS `.font(.gradely(.caption).weight(.bold)).textCase(.uppercase)`). */
@Composable
private fun CreditsRoleLabel(text: String) {
    Text(text.uppercase(), style = GradeyType.captionBold, color = MaterialTheme.gradeyColors.secondaryLabel)
}

@Composable
private fun CreditPerson(role: String, name: String, emailUrl: String, instagramUrl: String?) {
    val uriHandler = LocalUriHandler.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 84.dp).padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        SettingsIcon(HugeIcons.User)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                CreditsRoleLabel(role)
                Text(name, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            }
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                CreditLink(
                    emailUrl.removePrefix("mailto:"), HugeIcons.Mail01, GradeyType.footnoteBold,
                    Modifier.clickable(role = Role.Button) { runCatching { uriHandler.openUri(emailUrl) } },
                )
                if (instagramUrl != null) CreditLink(
                    stringResource(R.string.settings_credits_instagram), HugeIcons.Camera01, GradeyType.footnoteBold,
                    Modifier.clickable(role = Role.Button) { runCatching { uriHandler.openUri(instagramUrl) } },
                )
            }
        }
    }
}

@Composable
private fun CreditLink(title: String, icon: String?, style: TextStyle, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier.heightIn(min = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        if (icon != null) GradeyIcon(icon, size = 14.dp, tint = primary)
        Text(title, style = style.tabular(), color = primary, maxLines = 1)
        GradeyIcon(HugeIcons.ArrowUpRight01, size = 11.dp, tint = primary)
    }
}

/** A school system's attribution in its own group (iOS `bakalariAttribution`, `eduPageAttribution`). */
@Composable
private fun Attribution(title: String, message: String, link: Pair<String, String>?, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    SettingsGroup(modifier) {
        Column(Modifier.surfaceContent(), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            Text(title, Modifier.semantics { heading() }, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            Text(message, style = GradeyType.footnote, color = MaterialTheme.gradeyColors.secondaryLabel)
            if (link != null) CreditLink(
                link.first, null, GradeyType.footnoteBold,
                Modifier.clickable(role = Role.Button) { runCatching { uriHandler.openUri(link.second) } },
            )
        }
    }
}
