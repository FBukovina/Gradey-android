package com.bukovinafilip.gradey.feature.account

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.model.AppLanguage
import com.bukovinafilip.gradey.model.GradeyAccount
import com.bukovinafilip.gradey.model.LinkedCanteenAccount
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.NotificationLockScreenDetail
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.model.SchoolStudentProfile
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.bukovinafilip.gradey.ui.AccountAvatar
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTonalButton
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.SettingsBackground
import com.bukovinafilip.gradey.ui.SettingsCloseButton
import com.bukovinafilip.gradey.ui.SettingsHeader
import com.bukovinafilip.gradey.ui.SettingsIcon
import com.bukovinafilip.gradey.ui.SettingsNote
import com.bukovinafilip.gradey.ui.SettingsRow
import com.bukovinafilip.gradey.ui.SettingsRowDivider
import com.bukovinafilip.gradey.ui.SettingsSurface
import com.bukovinafilip.gradey.ui.SettingsToggleRow
import com.bukovinafilip.gradey.ui.SettingsValueRow
import com.bukovinafilip.gradey.ui.StatusChip
import com.bukovinafilip.gradey.ui.gradeyColors

/** Overview destinations of the Settings hub, mirroring iOS `SettingsDestination`. */
enum class SettingsDestination(val icon: String) {
    ACCOUNT(HugeIcons.User),
    CONNECTED_SERVICES(HugeIcons.Link04),
    NOTIFICATIONS(HugeIcons.Notification01),
    PRIVACY_DATA(HugeIcons.SecurityLock),
    APP_PREFERENCES(HugeIcons.Settings02),
    SUPPORT_ABOUT(HugeIcons.HelpCircle),
}

@Composable
private fun SettingsDestination.title(): String = stringResource(when (this) {
    SettingsDestination.ACCOUNT -> R.string.settings_destination_account_title
    SettingsDestination.CONNECTED_SERVICES -> R.string.settings_destination_connected_title
    SettingsDestination.NOTIFICATIONS -> R.string.settings_destination_notifications_title
    SettingsDestination.PRIVACY_DATA -> R.string.settings_destination_privacy_title
    SettingsDestination.APP_PREFERENCES -> R.string.settings_destination_preferences_title
    SettingsDestination.SUPPORT_ABOUT -> R.string.settings_destination_support_title
})

@Composable
private fun SettingsDestination.defaultSubtitle(): String = stringResource(when (this) {
    SettingsDestination.ACCOUNT -> R.string.settings_destination_account_subtitle
    SettingsDestination.CONNECTED_SERVICES -> R.string.settings_destination_connected_subtitle
    SettingsDestination.NOTIFICATIONS -> R.string.settings_destination_notifications_subtitle
    SettingsDestination.PRIVACY_DATA -> R.string.settings_destination_privacy_subtitle
    SettingsDestination.APP_PREFERENCES -> R.string.settings_destination_preferences_subtitle
    SettingsDestination.SUPPORT_ABOUT -> R.string.settings_destination_support_subtitle
})

@Composable
fun AccountScreen(
    account: GradeyAccount?,
    linkedAccounts: List<LinkedSchoolAccount>,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier,
    preferences: NotificationPreferences = NotificationPreferences.Default,
    onPreferencesChange: (NotificationPreferences) -> Unit = {},
    activeAccountID: String? = null,
    onActivateAccount: (LinkedSchoolAccount) -> Unit = {},
    onUnlinkAccount: (LinkedSchoolAccount) -> Unit = {},
    onAddAccount: () -> Unit = {},
    isLoading: Boolean = false,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    isDemo: Boolean = false,
    liveSignInAvailable: Boolean = false,
    onNotificationSettings: (() -> Unit)? = null,
    versionName: String? = null,
    onExportData: (() -> Unit)? = null,
    onDeleteAccount: (() -> Unit)? = null,
    students: List<SchoolStudentProfile> = emptyList(),
    activeStudentID: String? = null,
    onSelectStudent: (String) -> Unit = {},
    onOpenUpdates: (() -> Unit)? = null,
    onUpdateProfile: ((String) -> Unit)? = null,
    onReconnectAccount: ((LinkedSchoolAccount) -> Unit)? = null,
    onAccountNotificationsChange: ((LinkedSchoolAccount, Boolean) -> Unit)? = null,
    showMealsTab: Boolean = true,
    onShowMealsTabChange: ((Boolean) -> Unit)? = null,
    onOpenPrivacy: (() -> Unit)? = null,
    onOpenTerms: (() -> Unit)? = null,
    onOpenHelp: (() -> Unit)? = null,
    appLanguage: AppLanguage = AppLanguage.SYSTEM,
    onLanguageChange: ((AppLanguage) -> Unit)? = null,
    plannerRemindersEnabled: Boolean = false,
    onPlannerRemindersChange: ((Boolean) -> Unit)? = null,
    isGuest: Boolean = false,
    onGuestSignIn: (() -> Unit)? = null,
    localSchoolName: String? = null,
    canteenAccounts: List<LinkedCanteenAccount> = emptyList(),
    canteenSession: StravaCZStoredSession? = null,
    currentCanteenLinkID: String? = null,
    onLinkCanteen: (() -> Unit)? = null,
    onUnlinkCanteen: ((LinkedCanteenAccount) -> Unit)? = null,
    onOpenMeals: (() -> Unit)? = null,
    ageAttestation: String? = null,
    onOpenPrivacyChanges: (() -> Unit)? = null,
    onOpenWatch: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    onOpenSupport: (() -> Unit)? = null,
    initialDestination: SettingsDestination? = null,
) {
    var destination by rememberSaveable { mutableStateOf(initialDestination) }
    var editingProfile by rememberSaveable { mutableStateOf(false) }
    var profileName by rememberSaveable(account?.id, account?.fullName) { mutableStateOf(account?.fullName.orEmpty()) }
    var removing by remember { mutableStateOf<LinkedSchoolAccount?>(null) }
    var removingCanteen by remember { mutableStateOf<LinkedCanteenAccount?>(null) }
    var editingQuietHours by remember { mutableStateOf(false) }
    var confirmingSignOut by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    BackHandler(enabled = destination != null) { destination = null }

    val languageLabels = mapOf(
        AppLanguage.SYSTEM to stringResource(R.string.account_system_language_07eab), AppLanguage.ENGLISH to stringResource(R.string.account_english_649df),
        AppLanguage.CZECH to stringResource(R.string.account_e_tina_a56cf), AppLanguage.ENGLISH_CHRONICALLY_ONLINE to stringResource(R.string.account_english_chronically_online_be532),
        AppLanguage.CZECH_CHRONICALLY_ONLINE to stringResource(R.string.account_e_tina_chronically_online_6e945),
    )

    SettingsBackground(modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = destination, label = "settings",
            transitionSpec = {
                if (targetState != null) (slideInHorizontally { it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { -it / 4 } + fadeOut())
                else (slideInHorizontally { -it / 4 } + fadeIn()) togetherWith (slideOutHorizontally { it / 4 } + fadeOut())
            },
            modifier = Modifier.fillMaxSize(),
        ) { current ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyColumn(
                    modifier = Modifier.widthIn(max = 640.dp).fillMaxSize().statusBarsPadding().testTag(if (current == null) "accountHubScroll" else "settingsDetailScroll"),
                    contentPadding = PaddingValues(start = 20.dp, top = 20.dp, end = 20.dp, bottom = GradeySpacing.xxl),
                    verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
                ) {
                    if (current == null) overview(
                        onClose = onClose, onSelect = { destination = it },
                        account = account, isGuest = isGuest, isDemo = isDemo, localSchoolName = localSchoolName,
                        linkedAccounts = linkedAccounts, canteenSession = canteenSession, canteenAccounts = canteenAccounts,
                        preferences = preferences, showMealsTab = showMealsTab, languageLabel = languageLabels.getValue(appLanguage),
                    ) else {
                        item { DetailHeader(current.title(), onBack = { destination = null }) }
                        if (isLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                        errorMessage?.let { message -> item {
                            SettingsSurface {
                                Text(message, style = GradeyType.subheadline, color = MaterialTheme.colorScheme.error)
                                GradeyTonalButton(stringResource(R.string.account_try_again_042c8), onRetry, enabled = !isLoading, icon = HugeIcons.Refresh04)
                            }
                        } }
                        when (current) {
                            SettingsDestination.ACCOUNT -> accountPage(
                                account, isGuest, isDemo, isLoading, liveSignInAvailable, onSignOut, onGuestSignIn, onUpdateProfile,
                                onEditName = { editingProfile = true }, onConfirmSignOut = { confirmingSignOut = true },
                            )
                            SettingsDestination.CONNECTED_SERVICES -> connectedServicesPage(
                                isGuest, isDemo, isLoading, account, localSchoolName, linkedAccounts, activeAccountID, onActivateAccount, onAddAccount,
                                onReconnectAccount, onAccountNotificationsChange, onRemove = { removing = it },
                                canteenAccounts, canteenSession, currentCanteenLinkID, onLinkCanteen, onOpenMeals, canUnlinkCanteen = onUnlinkCanteen != null, onRemoveCanteen = { removingCanteen = it },
                                students, activeStudentID, onSelectStudent, onOpenWatch,
                            )
                            SettingsDestination.NOTIFICATIONS -> notificationsPage(
                                preferences, onPreferencesChange, isGuest, isDemo, isLoading, plannerRemindersEnabled, onPlannerRemindersChange,
                                onEditQuietHours = { editingQuietHours = true }, onNotificationSettings,
                            )
                            SettingsDestination.PRIVACY_DATA -> privacyPage(account, isGuest, isLoading, ageAttestation, onOpenPrivacy, onOpenTerms, onOpenPrivacyChanges, onExportData, onDeleteAccount, onConfirmDelete = { confirmingDelete = true })
                            SettingsDestination.APP_PREFERENCES -> preferencesPage(appLanguage, languageLabels, onLanguageChange, showMealsTab, onShowMealsTabChange)
                            SettingsDestination.SUPPORT_ABOUT -> supportPage(onOpenHelp, onOpenSupport, onOpenUpdates, onOpenPrivacy, onOpenTerms, versionName)
                        }
                    }
                }
            }
        }
    }

    if (editingProfile) AlertDialog(
        onDismissRequest = { editingProfile = false },
        title = { Text(stringResource(R.string.account_your_name_ab422)) },
        text = { OutlinedTextField(value = profileName, onValueChange = { profileName = it.take(80) }, label = { Text(stringResource(R.string.account_full_name_eeb69)) }, singleLine = true) },
        confirmButton = { TextButton(onClick = { editingProfile = false; onUpdateProfile?.invoke(profileName.trim()) }, enabled = profileName.trim().isNotEmpty() && !isLoading) { Text(stringResource(R.string.account_save_name_8ce68)) } },
        dismissButton = { TextButton(onClick = { editingProfile = false }) { Text(stringResource(R.string.account_cancel_77dfd)) } },
    )
    removing?.let { linked ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.account_remove_school_account_36e26)) },
            text = { Text(stringResource(R.string.account_unlink_confirmation, linked.displayName)) },
            confirmButton = { TextButton(onClick = { removing = null; onUnlinkAccount(linked) }) { Text(stringResource(R.string.account_remove_account_e556b)) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.account_keep_account_5a123)) } },
        )
    }
    removingCanteen?.let { linked -> AlertDialog(
        onDismissRequest = { removingCanteen = null },
        title = { Text(stringResource(R.string.account_remove_canteen_link)) },
        text = { Text(stringResource(R.string.account_remove_canteen_confirmation, linked.displayName)) },
        confirmButton = { TextButton(onClick = { removingCanteen = null; onUnlinkCanteen?.invoke(linked) }) { Text(stringResource(R.string.account_remove_e9639)) } },
        dismissButton = { TextButton(onClick = { removingCanteen = null }) { Text(stringResource(R.string.account_cancel_77dfd)) } },
    ) }
    if (confirmingSignOut) AlertDialog(
        onDismissRequest = { confirmingSignOut = false },
        title = { Text(if (isGuest) stringResource(R.string.account_disconnect_local_school) else stringResource(R.string.account_sign_out_of_gradey_92233)) },
        text = { Text(if (isGuest) stringResource(R.string.account_local_disconnect_detail) else stringResource(R.string.account_saved_school_sessions_will_be_removed_from_this_device_your_li_b8fef)) },
        confirmButton = { TextButton(onClick = { confirmingSignOut = false; onSignOut() }) { Text(stringResource(R.string.account_sign_out_dc164)) } },
        dismissButton = { TextButton(onClick = { confirmingSignOut = false }) { Text(stringResource(R.string.account_cancel_77dfd)) } },
    )
    if (confirmingDelete) AlertDialog(
        onDismissRequest = { confirmingDelete = false },
        title = { Text(stringResource(R.string.account_permanently_delete_your_account_35d37)) },
        text = { Text(stringResource(R.string.account_your_gradey_profile_linked_accounts_saved_grade_history_and_no_4e364)) },
        confirmButton = { TextButton(onClick = { confirmingDelete = false; onDeleteAccount?.invoke() }) { Text(stringResource(R.string.account_delete_permanently_e63b5), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.account_keep_account_5a123)) } },
    )
    if (editingQuietHours) QuietHoursDialog(
        preferences = preferences,
        onDismiss = { editingQuietHours = false },
        onSave = { editingQuietHours = false; onPreferencesChange(it) },
    )
}

// MARK: - Overview

private fun LazyListScope.overview(
    onClose: (() -> Unit)?,
    onSelect: (SettingsDestination) -> Unit,
    account: GradeyAccount?,
    isGuest: Boolean,
    isDemo: Boolean,
    localSchoolName: String?,
    linkedAccounts: List<LinkedSchoolAccount>,
    canteenSession: StravaCZStoredSession?,
    canteenAccounts: List<LinkedCanteenAccount>,
    preferences: NotificationPreferences,
    showMealsTab: Boolean,
    languageLabel: String,
) {
    item {
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl)) {
            if (onClose != null) SettingsHeader(stringResource(R.string.settings_title), onDismiss = onClose, dismissDescription = stringResource(R.string.settings_close))
            else Text(stringResource(R.string.settings_title), Modifier.semantics { heading() }, style = GradeyType.display(36), color = MaterialTheme.colorScheme.onSurface)
            ProfileSummary(account, isGuest, isDemo, localSchoolName, onClick = { onSelect(SettingsDestination.ACCOUNT) })
        }
    }
    item {
        val needsAttention = linkedAccounts.any { it.status != "active" }
        val school = when {
            isGuest -> localSchoolName ?: stringResource(R.string.account_school_on_device)
            linkedAccounts.isEmpty() -> stringResource(R.string.settings_overview_school_not_connected)
            needsAttention -> stringResource(R.string.settings_overview_school_action_required)
            else -> stringResource(R.string.settings_overview_school_active)
        }
        val canteen = stringResource(if (canteenSession != null || canteenAccounts.isNotEmpty()) R.string.settings_overview_canteen_connected else R.string.settings_overview_canteen_not_connected)
        val notifications = when {
            isGuest -> stringResource(R.string.settings_overview_notifications_unavailable)
            !preferences.newMarksEnabled -> stringResource(R.string.settings_overview_notifications_off)
            preferences.quietHoursEnabled -> stringResource(R.string.settings_overview_notifications_quiet_until, preferences.quietHoursEnd)
            else -> stringResource(R.string.settings_overview_notifications_on)
        }
        SettingsSurface(padding = PaddingValues(0.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            SettingsRow(
                SettingsDestination.CONNECTED_SERVICES.icon, SettingsDestination.CONNECTED_SERVICES.title(), "$school · $canteen",
                onClick = { onSelect(SettingsDestination.CONNECTED_SERVICES) }, modifier = Modifier.testTag("settingsDestination-connectedServices"),
                status = if (needsAttention && !isGuest) stringResource(R.string.settings_profile_action_required) else null,
                statusIcon = HugeIcons.AlertCircle, statusColor = MaterialTheme.gradeyColors.orange,
            )
            SettingsRowDivider()
            SettingsRow(SettingsDestination.NOTIFICATIONS.icon, SettingsDestination.NOTIFICATIONS.title(), notifications,
                onClick = { onSelect(SettingsDestination.NOTIFICATIONS) }, modifier = Modifier.testTag("settingsDestination-notifications"))
            SettingsRowDivider()
            SettingsRow(SettingsDestination.PRIVACY_DATA.icon, SettingsDestination.PRIVACY_DATA.title(), SettingsDestination.PRIVACY_DATA.defaultSubtitle(),
                onClick = { onSelect(SettingsDestination.PRIVACY_DATA) }, modifier = Modifier.testTag("settingsDestination-privacyData"))
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            SectionHeader(stringResource(R.string.settings_group_app), Modifier.padding(horizontal = GradeySpacing.xs))
            SettingsSurface(padding = PaddingValues(0.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                SettingsRow(SettingsDestination.APP_PREFERENCES.icon, SettingsDestination.APP_PREFERENCES.title(),
                    listOf(languageLabel, stringResource(if (showMealsTab) R.string.settings_overview_meals_shown else R.string.settings_overview_meals_hidden)).joinToString(" · "),
                    onClick = { onSelect(SettingsDestination.APP_PREFERENCES) }, modifier = Modifier.testTag("settingsDestination-appPreferences"))
                SettingsRowDivider()
                SettingsRow(SettingsDestination.SUPPORT_ABOUT.icon, SettingsDestination.SUPPORT_ABOUT.title(), SettingsDestination.SUPPORT_ABOUT.defaultSubtitle(),
                    onClick = { onSelect(SettingsDestination.SUPPORT_ABOUT) }, modifier = Modifier.testTag("settingsDestination-supportAbout"))
            }
        }
    }
}

@Composable
private fun ProfileSummary(account: GradeyAccount?, isGuest: Boolean, isDemo: Boolean, localSchoolName: String?, onClick: () -> Unit) {
    val name = account?.fullName?.takeIf { it.isNotBlank() }
    val title = when {
        isGuest -> localSchoolName ?: stringResource(R.string.account_school_on_device)
        name != null -> name
        isDemo -> stringResource(R.string.account_demo_mode_a85b7)
        else -> stringResource(R.string.settings_profile_add_name_title)
    }
    val subtitle = when {
        isGuest -> stringResource(R.string.settings_profile_local)
        isDemo -> stringResource(R.string.settings_profile_demo)
        name != null -> stringResource(R.string.settings_profile_gradey_id)
        else -> stringResource(R.string.settings_profile_add_name_message)
    }
    SettingsSurface(padding = PaddingValues(0.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Surface(onClick = onClick, color = Color.Transparent, modifier = Modifier.fillMaxWidth().testTag("settingsDestination-account")) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = GradeySpacing.md).heightIn(min = 80.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.lg)) {
                AccountAvatar(if (isGuest) null else name)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                    Text(title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
                    Text(subtitle, style = GradeyType.subheadline, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f))
                }
                DisclosureIcon(size = 14.dp)
            }
        }
    }
}

@Composable
private fun DetailHeader(title: String, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        SettingsCloseButton(onBack, stringResource(R.string.settings_back), size = 44.dp, icon = HugeIcons.ArrowLeft01)
        Text(title, Modifier.weight(1f).semantics { heading() }, style = GradeyType.title3, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.size(44.dp))
    }
}

// MARK: - Account

private fun LazyListScope.accountPage(
    account: GradeyAccount?, isGuest: Boolean, isDemo: Boolean, isLoading: Boolean, liveSignInAvailable: Boolean,
    onSignOut: () -> Unit, onGuestSignIn: (() -> Unit)?, onUpdateProfile: ((String) -> Unit)?, onEditName: () -> Unit, onConfirmSignOut: () -> Unit,
) {
    if (isDemo) item {
        SettingsSurface {
            Text(stringResource(R.string.account_demo_mode_a85b7), style = GradeyType.headline)
            Text(
                if (liveSignInAvailable) stringResource(R.string.account_you_re_exploring_sample_data_sign_in_to_gradey_id_to_connect_y_3478a)
                else stringResource(R.string.account_explore_gradey_with_sample_data_real_school_accounts_cannot_be_80aca),
                style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (liveSignInAvailable) GradeyPrimaryButton(stringResource(R.string.account_connect_my_school_c04e4), onSignOut, enabled = !isLoading)
        }
    }
    if (isGuest) item {
        SettingsSurface {
            SectionHeader(stringResource(R.string.account_local_profile))
            Text(stringResource(R.string.account_using_local_school), style = GradeyType.title3)
            Text(stringResource(R.string.account_local_profile_description), style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onGuestSignIn?.let { signIn -> GradeyPrimaryButton(stringResource(R.string.account_sign_in_gradey_id), signIn, enabled = !isLoading, icon = HugeIcons.User) }
            GradeyTonalButton(stringResource(R.string.account_disconnect_local_school), onConfirmSignOut, enabled = !isLoading, icon = HugeIcons.Logout01, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
        }
    } else if (account != null) item {
        SettingsSurface {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.lg)) {
                AccountAvatar(account.fullName, size = 52.dp)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(account.fullName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_gradey_account_4bda8), style = GradeyType.title3)
                    Text(account.email ?: stringResource(R.string.account_signed_in_to_gradey_1a605), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (onUpdateProfile != null && !isDemo) SettingsValueRow(stringResource(R.string.account_edit_name_d3aaf), null, icon = HugeIcons.Edit02, onClick = onEditName, enabled = !isLoading)
            GradeyTonalButton(stringResource(R.string.account_sign_out_dc164), onConfirmSignOut, enabled = !isLoading, icon = HugeIcons.Logout01, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
        }
    }
}

// MARK: - Connected services

private fun LazyListScope.connectedServicesPage(
    isGuest: Boolean, isDemo: Boolean, isLoading: Boolean, account: GradeyAccount?, localSchoolName: String?,
    linkedAccounts: List<LinkedSchoolAccount>, activeAccountID: String?, onActivateAccount: (LinkedSchoolAccount) -> Unit, onAddAccount: () -> Unit,
    onReconnectAccount: ((LinkedSchoolAccount) -> Unit)?, onAccountNotificationsChange: ((LinkedSchoolAccount, Boolean) -> Unit)?, onRemove: (LinkedSchoolAccount) -> Unit,
    canteenAccounts: List<LinkedCanteenAccount>, canteenSession: StravaCZStoredSession?, currentCanteenLinkID: String?,
    onLinkCanteen: (() -> Unit)?, onOpenMeals: (() -> Unit)?, canUnlinkCanteen: Boolean, onRemoveCanteen: (LinkedCanteenAccount) -> Unit,
    students: List<SchoolStudentProfile>, activeStudentID: String?, onSelectStudent: (String) -> Unit, onOpenWatch: (() -> Unit)?,
) {
    item { SectionHeader(stringResource(R.string.settings_school_connections), Modifier.padding(horizontal = GradeySpacing.xs)) }
    if (isGuest) item {
        SettingsSurface {
            Text(localSchoolName ?: stringResource(R.string.account_school_on_device), style = GradeyType.headline)
            Text(stringResource(R.string.account_local_school_detail), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
            GradeyTonalButton(stringResource(R.string.account_change_local_school), onAddAccount, enabled = !isLoading, icon = HugeIcons.University)
        }
    } else {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.account_school_accounts_93efe), style = GradeyType.headline)
                GradeyTonalButton(stringResource(R.string.account_add_school_a0388), onAddAccount, enabled = !isLoading && !isDemo, icon = HugeIcons.Add01)
            }
        }
        if (linkedAccounts.isEmpty()) item {
            SettingsSurface {
                Text(stringResource(R.string.account_no_linked_school_accounts_yet_b6f24), style = GradeyType.headline)
                Text(stringResource(R.string.account_connect_your_school_to_keep_your_grades_timetable_and_attendan_8701c), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                GradeyPrimaryButton(stringResource(R.string.account_connect_school_85e9d), onAddAccount, enabled = !isLoading && !isDemo, icon = HugeIcons.University)
            }
        }
        items(linkedAccounts, key = { it.id }) { linked ->
            val active = linked.id == activeAccountID
            val needsAttention = linked.status != "active"
            SettingsSurface {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                    SettingsIcon(HugeIcons.University, color = if (needsAttention) MaterialTheme.gradeyColors.orange else MaterialTheme.colorScheme.primary.copy(alpha = 0.88f))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(linked.displayName, style = GradeyType.headline)
                        Text(listOfNotNull(linked.provider.displayName, linked.schoolName).joinToString(" · "), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusChip(
                        when { needsAttention -> stringResource(R.string.settings_profile_action_required); active -> stringResource(R.string.account_current_4fc0e); else -> stringResource(R.string.account_ready_to_switch_fac83) },
                        color = when { needsAttention -> MaterialTheme.gradeyColors.orange; active -> MaterialTheme.colorScheme.primary; else -> MaterialTheme.colorScheme.onSurfaceVariant },
                        icon = when { needsAttention -> HugeIcons.AlertCircle; active -> HugeIcons.Tick02; else -> null },
                    )
                }
                if (needsAttention) Text(stringResource(R.string.account_reconnect_this_school_to_continue_syncing_53f3d), style = GradeyType.footnote, color = MaterialTheme.gradeyColors.orange)
                if (!linked.notificationsEnabled) Text(stringResource(R.string.account_cloud_mark_alerts_are_disabled_for_this_account_b0232), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onAccountNotificationsChange != null && !isDemo) SettingsToggleRow(
                    stringResource(R.string.account_mark_alerts_66f5e), stringResource(R.string.account_notifications_for_this_school_account_4f899), linked.notificationsEnabled,
                    !isLoading && linked.status == "active", { onAccountNotificationsChange(linked, it) }, icon = HugeIcons.Notification02,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    if (onReconnectAccount != null && !isDemo) GradeyTonalButton(stringResource(R.string.account_reconnect_6988b), { onReconnectAccount(linked) }, enabled = !isLoading, icon = HugeIcons.Refresh04)
                    if (!active) GradeyTonalButton(stringResource(R.string.account_switch_account_a28b0), { onActivateAccount(linked) }, enabled = !isLoading && linked.status == "active", icon = HugeIcons.ArrowDataTransferVertical)
                    GradeyTonalButton(stringResource(R.string.account_remove_e9639), { onRemove(linked) }, enabled = !isLoading && !isDemo, icon = HugeIcons.Delete02, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
    if (students.size > 1) item {
        SettingsSurface {
            SectionHeader(stringResource(R.string.account_student_profiles_8f99a))
            Text(stringResource(R.string.account_choose_whose_grades_and_timetable_to_show_2830c), style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(stringResource(R.string.account_background_mark_alerts_and_cloud_grade_history_are_unavailable_b1a19), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
            students.forEach { student ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    SettingsIcon(HugeIcons.Students)
                    Column(Modifier.weight(1f)) {
                        Text(student.fullName, style = GradeyType.subheadlineBold)
                        student.className?.let { Text(it, style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    if (student.id == activeStudentID) StatusChip(stringResource(R.string.account_current_4fc0e), color = MaterialTheme.colorScheme.primary, icon = HugeIcons.Tick02)
                    else GradeyTonalButton(stringResource(R.string.account_select_85982), { onSelectStudent(student.id) }, enabled = !isLoading)
                }
            }
        }
    }
    if (onOpenMeals != null || canteenAccounts.isNotEmpty()) {
        item { SectionHeader(stringResource(R.string.settings_canteen_connections), Modifier.padding(horizontal = GradeySpacing.xs)) }
        item {
            SettingsSurface {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                    SettingsIcon(HugeIcons.Restaurant02)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        if (canteenSession != null) {
                            Text(canteenSession.canteenName ?: stringResource(R.string.account_canteen_number, canteenSession.canteenNumber), style = GradeyType.headline)
                            Text(if (currentCanteenLinkID != null) stringResource(R.string.account_canteen_linked) else stringResource(R.string.account_canteen_local), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            Text(stringResource(R.string.account_canteen_accounts), style = GradeyType.headline)
                            Text(stringResource(R.string.account_canteen_connect_description), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (canteenSession != null && onLinkCanteen != null && account != null && !isGuest && currentCanteenLinkID == null) {
                    Text(stringResource(R.string.account_canteen_link_description), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    GradeyTonalButton(stringResource(R.string.account_link_canteen), onLinkCanteen, enabled = !isLoading && !isDemo, icon = HugeIcons.LinkCircle02)
                }
                onOpenMeals?.let { open -> GradeyTonalButton(stringResource(R.string.account_open_meals), open, icon = HugeIcons.Restaurant02) }
                if (!isGuest) canteenAccounts.forEach { linked ->
                    SettingsRowDivider(leadingInset = 0.dp)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(linked.displayName, style = GradeyType.subheadlineBold)
                            linked.canteenName?.let { Text(it, style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (linked.status != "active") Text(stringResource(R.string.account_canteen_attention), style = GradeyType.footnote, color = MaterialTheme.colorScheme.error)
                        }
                        if (canUnlinkCanteen) GradeyTonalButton(stringResource(R.string.account_remove_e9639), { onRemoveCanteen(linked) }, enabled = !isLoading && !isDemo, icon = HugeIcons.Delete02, color = MaterialTheme.colorScheme.error)
                    }
                }
                if (canteenAccounts.isNotEmpty() && currentCanteenLinkID == null && !isGuest) Text(stringResource(R.string.account_canteen_existing_link_note), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    onOpenWatch?.let { openWatch -> item {
        SettingsSurface {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                SettingsIcon(HugeIcons.Watch01)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.account_watch_access_title), style = GradeyType.headline)
                    Text(stringResource(R.string.account_watch_access_description), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            GradeyTonalButton(stringResource(R.string.account_watch_access_manage), openWatch, enabled = !isLoading, modifier = Modifier.fillMaxWidth(), icon = HugeIcons.Watch01)
        }
    } }
}

// MARK: - Notifications

private fun LazyListScope.notificationsPage(
    preferences: NotificationPreferences, onPreferencesChange: (NotificationPreferences) -> Unit, isGuest: Boolean, isDemo: Boolean, isLoading: Boolean,
    plannerRemindersEnabled: Boolean, onPlannerRemindersChange: ((Boolean) -> Unit)?, onEditQuietHours: () -> Unit, onNotificationSettings: (() -> Unit)?,
) {
    item {
        SettingsSurface {
            SettingsToggleRow(
                stringResource(R.string.account_new_marks_67db5),
                if (isGuest) stringResource(R.string.account_cloud_alerts_require_id) else stringResource(R.string.account_receive_an_update_when_a_new_mark_is_available_a314b),
                preferences.newMarksEnabled && !isGuest, !isLoading && !isGuest, { onPreferencesChange(preferences.copy(newMarksEnabled = it)) }, icon = HugeIcons.Notification02,
            )
            onPlannerRemindersChange?.let { change ->
                SettingsRowDivider(leadingInset = 0.dp)
                SettingsToggleRow(stringResource(R.string.account_planner_reminders), stringResource(R.string.account_planner_reminders_description), plannerRemindersEnabled, !isLoading && !isDemo, change, icon = HugeIcons.CheckList)
            }
            onNotificationSettings?.let { action ->
                SettingsRowDivider(leadingInset = 0.dp)
                SettingsValueRow(stringResource(R.string.account_android_notification_settings_b9e03), null, icon = HugeIcons.SmartPhone01, onClick = action)
            }
        }
    }
    item {
        SettingsSurface {
            SettingsToggleRow(stringResource(R.string.account_quiet_hours_706b2), stringResource(R.string.account_pause_mark_alerts_during_your_chosen_hours_442f1), preferences.quietHoursEnabled, !isLoading,
                { onPreferencesChange(preferences.copy(quietHoursEnabled = it)) }, icon = HugeIcons.Moon02)
            if (preferences.quietHoursEnabled) {
                SettingsRowDivider(leadingInset = 0.dp)
                SettingsValueRow(stringResource(R.string.account_edit_quiet_range, preferences.quietHoursStart, preferences.quietHoursEnd), null, icon = HugeIcons.Time04, onClick = onEditQuietHours, enabled = !isLoading)
                Text(stringResource(R.string.account_time_zone_value, preferences.quietHoursTimeZoneIdentifier), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    item {
        SettingsSurface {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                SettingsIcon(HugeIcons.ViewOff)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.account_lock_screen_privacy_12f90), style = GradeyType.headline)
                    Text(stringResource(R.string.account_choose_how_much_a_mark_alert_can_show_fc5a7), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            NotificationLockScreenDetail.entries.forEach { detail ->
                val label = when (detail) {
                    NotificationLockScreenDetail.PRIVATE_SUMMARY -> stringResource(R.string.account_private_summary_68d30)
                    NotificationLockScreenDetail.MARK_AND_SUBJECT -> stringResource(R.string.account_mark_and_subject_5d4da)
                    NotificationLockScreenDetail.FULL_DETAILS -> stringResource(R.string.account_full_details_a005b)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = detail == preferences.lockScreenDetail, onClick = { onPreferencesChange(preferences.copy(lockScreenDetail = detail)) }, enabled = !isLoading)
                    Text(label, style = GradeyType.subheadline)
                }
            }
        }
    }
}

// MARK: - Privacy & data

private fun LazyListScope.privacyPage(
    account: GradeyAccount?, isGuest: Boolean, isLoading: Boolean, ageAttestation: String?,
    onOpenPrivacy: (() -> Unit)?, onOpenTerms: (() -> Unit)?, onOpenPrivacyChanges: (() -> Unit)?,
    onExportData: (() -> Unit)?, onDeleteAccount: (() -> Unit)?, onConfirmDelete: () -> Unit,
) {
    if (onOpenPrivacy != null || onOpenTerms != null || onOpenPrivacyChanges != null) item {
        SettingsSurface(padding = PaddingValues(vertical = GradeySpacing.sm), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            val rows = listOfNotNull(
                onOpenPrivacy?.let { Triple(HugeIcons.SecurityCheck, stringResource(R.string.account_privacy_policy_9db10), it) },
                onOpenTerms?.let { Triple(HugeIcons.Book02, stringResource(R.string.account_terms_of_use_97738), it) },
                onOpenPrivacyChanges?.let { Triple(HugeIcons.InformationCircle, stringResource(R.string.account_privacy_changes), it) },
            )
            rows.forEachIndexed { index, (icon, title, action) ->
                if (index > 0) SettingsRowDivider()
                SettingsValueRow(title, null, Modifier.padding(horizontal = 20.dp), icon = icon, onClick = action)
            }
        }
    }
    item {
        SettingsSurface {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                SettingsIcon(HugeIcons.UserCheck02)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.account_age_status_title), style = GradeyType.headline)
                    Text(stringResource(when (ageAttestation) {
                        "sixteenOrOlder" -> R.string.account_age_status_adult
                        "thirteenToFifteenWithParent", "underThirteen" -> R.string.account_age_status_parent
                        else -> R.string.account_age_status_unknown
                    }), style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(stringResource(R.string.account_age_status_local), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (account != null && !isGuest && (onExportData != null || onDeleteAccount != null)) item {
        SettingsSurface {
            Text(stringResource(R.string.account_your_data_48cd1), style = GradeyType.headline)
            Text(stringResource(R.string.account_download_your_gradey_data_or_permanently_delete_your_account_6e4f4), style = GradeyType.footnote, color = MaterialTheme.colorScheme.onSurfaceVariant)
            onExportData?.let { action -> GradeyTonalButton(stringResource(R.string.account_export_account_data_59cf7), action, enabled = !isLoading, icon = HugeIcons.FileExport, modifier = Modifier.fillMaxWidth()) }
            onDeleteAccount?.let { GradeyTonalButton(stringResource(R.string.account_delete_gradey_account_c70a9), onConfirmDelete, enabled = !isLoading, icon = HugeIcons.Delete02, color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

// MARK: - Language and preferences

private fun LazyListScope.preferencesPage(
    appLanguage: AppLanguage, labels: Map<AppLanguage, String>, onLanguageChange: ((AppLanguage) -> Unit)?,
    showMealsTab: Boolean, onShowMealsTabChange: ((Boolean) -> Unit)?,
) {
    onLanguageChange?.let { changeLanguage -> item {
        var expanded by remember { mutableStateOf(false) }
        SettingsSurface {
            Box {
                SettingsValueRow(stringResource(R.string.account_language_89b86), labels.getValue(appLanguage), icon = HugeIcons.LanguageSkill, onClick = { expanded = true }, valueColor = MaterialTheme.colorScheme.primary)
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    AppLanguage.entries.forEach { language -> DropdownMenuItem(
                        text = { Text(labels.getValue(language)) },
                        leadingIcon = { if (language == appLanguage) GradeyIcon(HugeIcons.Tick02, size = 16.dp) else Spacer(Modifier.size(16.dp)) },
                        onClick = { expanded = false; changeLanguage(language) },
                    ) }
                }
            }
        }
    } }
    onShowMealsTabChange?.let { change -> item {
        SettingsSurface {
            SettingsToggleRow(stringResource(R.string.account_show_meals_tab_aa1ad), stringResource(R.string.account_keep_your_canteen_one_tap_away_meals_also_stays_available_in_g_551e5), showMealsTab, true, change, icon = HugeIcons.Restaurant02)
        }
    } }
}

// MARK: - Support & about

private fun LazyListScope.supportPage(
    onOpenHelp: (() -> Unit)?, onOpenSupport: (() -> Unit)?, onOpenUpdates: (() -> Unit)?, onOpenPrivacy: (() -> Unit)?, onOpenTerms: (() -> Unit)?, versionName: String?,
) {
    item {
        SettingsSurface(padding = PaddingValues(vertical = GradeySpacing.sm), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            val rows = listOfNotNull(
                onOpenHelp?.let { Triple(HugeIcons.HelpCircle, stringResource(R.string.account_help_center_46fc1) to null, it) },
                onOpenSupport?.let { Triple(HugeIcons.Favourite, stringResource(R.string.settings_support_tips) to stringResource(R.string.settings_support_tips_subtitle), it) },
                onOpenUpdates?.let { Triple(HugeIcons.Download04, stringResource(R.string.account_app_updates_16213) to stringResource(R.string.account_get_the_latest_improvements_and_manage_automatic_downloads_ff7b2), it) },
            )
            rows.forEachIndexed { index, (icon, titles, action) ->
                if (index > 0) SettingsRowDivider()
                SettingsRow(icon, titles.first, titles.second, onClick = action, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
    }
    if (onOpenPrivacy != null || onOpenTerms != null) item {
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            SectionHeader(stringResource(R.string.settings_documents), Modifier.padding(horizontal = GradeySpacing.xs))
            SettingsSurface(padding = PaddingValues(vertical = GradeySpacing.sm), verticalArrangement = Arrangement.spacedBy(0.dp)) {
                val rows = listOfNotNull(
                    onOpenPrivacy?.let { Triple(HugeIcons.SecurityCheck, stringResource(R.string.account_privacy_policy_9db10), it) },
                    onOpenTerms?.let { Triple(HugeIcons.Book02, stringResource(R.string.account_terms_of_use_97738), it) },
                )
                rows.forEachIndexed { index, (icon, title, action) ->
                    if (index > 0) SettingsRowDivider()
                    SettingsValueRow(title, null, Modifier.padding(horizontal = 20.dp), icon = icon, onClick = action)
                }
            }
        }
    }
    item {
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            versionName?.let { version -> Text(stringResource(R.string.settings_version, version), Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.footnoteBold, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            SettingsNote(stringResource(R.string.account_gradey_is_an_independent_app_not_an_official_bakal_i_edupage_o_af9fb))
        }
    }
}

// MARK: - Dialogs

@Composable
private fun QuietHoursDialog(preferences: NotificationPreferences, onDismiss: () -> Unit, onSave: (NotificationPreferences) -> Unit) {
    var start by remember { mutableStateOf(preferences.quietHoursStart) }
    var end by remember { mutableStateOf(preferences.quietHoursEnd) }
    var zone by remember { mutableStateOf(preferences.quietHoursTimeZoneIdentifier) }
    val validZone = zone.trim() in java.time.ZoneId.getAvailableZoneIds()
    val timePattern = remember { Regex("(?:[01]\\d|2[0-3]):[0-5]\\d") }
    val valid = timePattern.matches(start) && timePattern.matches(end) && start != end && validZone
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.account_quiet_hours_706b2)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                Text(stringResource(R.string.account_use_24_hour_times_for_example_22_00_and_06_00_overnight_ranges_d47d0))
                OutlinedTextField(value = start, onValueChange = { start = it }, label = { Text(stringResource(R.string.account_start_hh_mm_55e44)) }, singleLine = true, isError = !timePattern.matches(start))
                OutlinedTextField(value = zone, onValueChange = { zone = it }, label = { Text(stringResource(R.string.account_time_zone_eea79)) }, placeholder = { Text("Europe/Prague") }, singleLine = true, isError = !validZone)
                TextButton(onClick = { zone = java.time.ZoneId.systemDefault().id }) { Text(stringResource(R.string.account_use_device_time_zone_9b240)) }
                OutlinedTextField(value = end, onValueChange = { end = it }, label = { Text(stringResource(R.string.account_end_hh_mm_60d67)) }, singleLine = true, isError = !timePattern.matches(end) || start == end)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(preferences.copy(quietHoursStart = start, quietHoursEnd = end, quietHoursTimeZoneIdentifier = zone.trim())) }, enabled = valid) { Text(stringResource(R.string.account_save_efc00)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.account_cancel_77dfd)) } },
    )
}
