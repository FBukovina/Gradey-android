package com.bukovinafilip.gradey.feature.account

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.font.FontWeight
import com.bukovinafilip.gradey.model.AppLanguage
import com.bukovinafilip.gradey.model.GradeyAccount
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.NotificationLockScreenDetail
import com.bukovinafilip.gradey.model.LinkedCanteenAccount
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.model.SchoolStudentProfile
import com.bukovinafilip.gradey.ui.GradeyHero
import com.bukovinafilip.gradey.ui.GradeySectionCard
import com.bukovinafilip.gradey.ui.GradeySpacing

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
) {
    var editingProfile by rememberSaveable { mutableStateOf(false) }
    var profileName by rememberSaveable(account?.id, account?.fullName) { mutableStateOf(account?.fullName.orEmpty()) }
    var removing by remember { mutableStateOf<LinkedSchoolAccount?>(null) }
    var removingCanteen by remember { mutableStateOf<LinkedCanteenAccount?>(null) }
    var editingQuietHours by remember { mutableStateOf(false) }
    var confirmingSignOut by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(GradeySpacing.lg),
    ) {
        item { GradeyHero(stringResource(R.string.account_account_85dfa), account?.fullName?.takeIf { it.isNotBlank() } ?: stringResource(R.string.account_your_gradey_c56a5)) }
        if (isDemo) item {
            GradeySectionCard {
                Text(stringResource(R.string.account_demo_mode_a85b7), style = MaterialTheme.typography.titleMedium)
                Text(
                    if (liveSignInAvailable) stringResource(R.string.account_you_re_exploring_sample_data_sign_in_to_gradey_id_to_connect_y_3478a)
                    else stringResource(R.string.account_explore_gradey_with_sample_data_real_school_accounts_cannot_be_80aca),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (liveSignInAvailable) {
                    Button(onClick = onSignOut, enabled = !isLoading) { Text(stringResource(R.string.account_connect_my_school_c04e4)) }
                }
            }
        }
        if (isLoading) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        errorMessage?.let { message -> item {
            GradeySectionCard {
                Text(message, color = MaterialTheme.colorScheme.error)
                TextButton(onClick = onRetry, enabled = !isLoading) { Text(stringResource(R.string.account_try_again_042c8)) }
            }
        } }
        if (isGuest) item {
            GradeySectionCard(title = stringResource(R.string.account_local_profile)) {
                Text(stringResource(R.string.account_using_local_school), style = MaterialTheme.typography.titleLarge)
                Text(stringResource(R.string.account_local_profile_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                onGuestSignIn?.let { signIn -> Button(onClick = signIn, enabled = !isLoading) { Text(stringResource(R.string.account_sign_in_gradey_id)) } }
                OutlinedButton(onClick = { confirmingSignOut = true }, enabled = !isLoading) { Text(stringResource(R.string.account_disconnect_local_school)) }
            }
        }
        if (!isGuest && account != null) item {
            GradeySectionCard(title = stringResource(R.string.account_profile_ff4fc)) {
                Text(account?.fullName ?: stringResource(R.string.account_gradey_account_4bda8), style = MaterialTheme.typography.titleLarge)
                Text(account?.email ?: stringResource(R.string.account_signed_in_to_gradey_1a605), color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onUpdateProfile != null && !isDemo) TextButton(onClick = { editingProfile = true }, enabled = !isLoading) { Text(stringResource(R.string.account_edit_name_d3aaf)) }
                OutlinedButton(onClick = { confirmingSignOut = true }, enabled = !isLoading) { Text(stringResource(R.string.account_sign_out_dc164)) }
            }
        }
        if (isGuest) item {
            GradeySectionCard(title = stringResource(R.string.account_local_school)) {
                Text(localSchoolName ?: stringResource(R.string.account_school_on_device), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.account_local_school_detail), color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = onAddAccount, enabled = !isLoading) { Text(stringResource(R.string.account_change_local_school)) }
            }
        }
        if (!isGuest) item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.account_school_accounts_93efe), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onAddAccount, enabled = !isLoading && !isDemo) { Text(stringResource(R.string.account_add_school_a0388)) }
            }
        }
        if (!isGuest && linkedAccounts.isEmpty()) item {
            GradeySectionCard {
                Text(stringResource(R.string.account_no_linked_school_accounts_yet_b6f24), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.account_connect_your_school_to_keep_your_grades_timetable_and_attendan_8701c), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onAddAccount, enabled = !isLoading && !isDemo) { Text(stringResource(R.string.account_connect_school_85e9d)) }
            }
        }
        items(if (isGuest) emptyList() else linkedAccounts, key = { it.id }) { linked ->
            val active = linked.id == activeAccountID
            GradeySectionCard {
                Text(linked.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(linked.provider.displayName, linked.schoolName).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        linked.status != "active" -> stringResource(R.string.account_reconnect_this_school_to_continue_syncing_53f3d)
                        active -> stringResource(R.string.account_current_school_account_061f9)
                        else -> stringResource(R.string.account_ready_to_switch_fac83)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (linked.status != "active") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                )
                if (!linked.notificationsEnabled) Text(stringResource(R.string.account_cloud_mark_alerts_are_disabled_for_this_account_b0232), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onAccountNotificationsChange != null && !isDemo) PreferenceSwitch(
                    stringResource(R.string.account_mark_alerts_66f5e), stringResource(R.string.account_notifications_for_this_school_account_4f899), linked.notificationsEnabled,
                    !isLoading && linked.status == "active",
                ) { onAccountNotificationsChange(linked, it) }
                Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    if (onReconnectAccount != null && !isDemo) TextButton(onClick = { onReconnectAccount(linked) }, enabled = !isLoading) { Text(stringResource(R.string.account_reconnect_6988b)) }
                    if (!active) OutlinedButton(onClick = { onActivateAccount(linked) }, enabled = !isLoading && linked.status == "active") { Text(stringResource(R.string.account_switch_account_a28b0)) }
                    TextButton(onClick = { removing = linked }, enabled = !isLoading && !isDemo) { Text(stringResource(R.string.account_remove_e9639)) }
                }
            }
        }
        if (onOpenMeals != null || canteenAccounts.isNotEmpty()) item {
            GradeySectionCard(title = stringResource(R.string.account_canteen_accounts)) {
                if (canteenSession != null) {
                    Text(canteenSession.canteenName ?: stringResource(R.string.account_canteen_number, canteenSession.canteenNumber), style = MaterialTheme.typography.titleMedium)
                    Text(if (currentCanteenLinkID != null) stringResource(R.string.account_canteen_linked) else stringResource(R.string.account_canteen_local), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (onLinkCanteen != null && account != null && !isGuest && currentCanteenLinkID == null) {
                        Text(stringResource(R.string.account_canteen_link_description), style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = onLinkCanteen, enabled = !isLoading && !isDemo) { Text(stringResource(R.string.account_link_canteen)) }
                    }
                } else Text(stringResource(R.string.account_canteen_connect_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                onOpenMeals?.let { open -> TextButton(onClick = open) { Text(stringResource(R.string.account_open_meals)) } }
                if (!isGuest) canteenAccounts.forEach { linked ->
                    HorizontalDivider()
                    Text(linked.displayName, style = MaterialTheme.typography.titleMedium)
                    linked.canteenName?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    if (linked.status != "active") Text(stringResource(R.string.account_canteen_attention), color = MaterialTheme.colorScheme.error)
                    onUnlinkCanteen?.let {
                        TextButton(onClick = { removingCanteen = linked }, enabled = !isLoading && !isDemo) { Text(stringResource(R.string.account_remove_canteen_link)) }
                    }
                }
                if (canteenAccounts.isNotEmpty() && currentCanteenLinkID == null && !isGuest) Text(stringResource(R.string.account_canteen_existing_link_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (students.size > 1) item {
            GradeySectionCard(title = stringResource(R.string.account_student_profiles_8f99a)) {
                Text(stringResource(R.string.account_choose_whose_grades_and_timetable_to_show_2830c), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.account_background_mark_alerts_and_cloud_grade_history_are_unavailable_b1a19), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                students.forEach { student ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(student.fullName, style = MaterialTheme.typography.titleMedium)
                            student.className?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                        if (student.id == activeStudentID) Text(stringResource(R.string.account_current_4fc0e), color = MaterialTheme.colorScheme.primary)
                        else TextButton(onClick = { onSelectStudent(student.id) }, enabled = !isLoading) { Text(stringResource(R.string.account_select_85982)) }
                    }
                }
            }
        }
        item {
            GradeySectionCard(title = stringResource(R.string.account_notifications_753a2)) {
                PreferenceSwitch(stringResource(R.string.account_new_marks_67db5), if (isGuest) stringResource(R.string.account_cloud_alerts_require_id) else stringResource(R.string.account_receive_an_update_when_a_new_mark_is_available_a314b), preferences.newMarksEnabled && !isGuest, !isLoading && !isGuest) {
                    onPreferencesChange(preferences.copy(newMarksEnabled = it))
                }
                onPlannerRemindersChange?.let { change ->
                    HorizontalDivider()
                    PreferenceSwitch(
                        stringResource(R.string.account_planner_reminders),
                        stringResource(R.string.account_planner_reminders_description),
                        plannerRemindersEnabled,
                        !isLoading && !isDemo,
                        change,
                    )
                }
                HorizontalDivider()
                PreferenceSwitch(stringResource(R.string.account_quiet_hours_706b2), stringResource(R.string.account_pause_mark_alerts_during_your_chosen_hours_442f1), preferences.quietHoursEnabled, !isLoading) {
                    onPreferencesChange(preferences.copy(quietHoursEnabled = it))
                }
                if (preferences.quietHoursEnabled) {
                    TextButton(onClick = { editingQuietHours = true }, enabled = !isLoading) { Text(stringResource(R.string.account_edit_quiet_range, preferences.quietHoursStart, preferences.quietHoursEnd)) }
                    Text(stringResource(R.string.account_time_zone_value, preferences.quietHoursTimeZoneIdentifier), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                Text(stringResource(R.string.account_lock_screen_privacy_12f90), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.account_choose_how_much_a_mark_alert_can_show_fc5a7), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                NotificationLockScreenDetail.entries.forEach { detail ->
                    val label = when (detail) {
                        NotificationLockScreenDetail.PRIVATE_SUMMARY -> stringResource(R.string.account_private_summary_68d30)
                        NotificationLockScreenDetail.MARK_AND_SUBJECT -> stringResource(R.string.account_mark_and_subject_5d4da)
                        NotificationLockScreenDetail.FULL_DETAILS -> stringResource(R.string.account_full_details_a005b)
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.RadioButton(selected = detail == preferences.lockScreenDetail, onClick = { onPreferencesChange(preferences.copy(lockScreenDetail = detail)) }, enabled = !isLoading)
                        Text(label)
                    }
                }
                onNotificationSettings?.let { action -> TextButton(onClick = action) { Text(stringResource(R.string.account_android_notification_settings_b9e03)) } }
            }
        }
        if (account != null && !isGuest && (onExportData != null || onDeleteAccount != null)) item {
            GradeySectionCard(title = stringResource(R.string.account_your_data_48cd1)) {
                Text(stringResource(R.string.account_download_your_gradey_data_or_permanently_delete_your_account_6e4f4), color = MaterialTheme.colorScheme.onSurfaceVariant)
                onExportData?.let { action -> OutlinedButton(onClick = action, enabled = !isLoading) { Text(stringResource(R.string.account_export_account_data_59cf7)) } }
                onDeleteAccount?.let {
                    TextButton(onClick = { confirmingDelete = true }, enabled = !isLoading) {
                        Text(stringResource(R.string.account_delete_gradey_account_c70a9), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        onOpenUpdates?.let { action -> item {
            GradeySectionCard(title = stringResource(R.string.account_app_updates_16213)) {
                Text(stringResource(R.string.account_get_the_latest_improvements_and_manage_automatic_downloads_ff7b2), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = action) { Text(stringResource(R.string.account_open_app_updates_66f0c)) }
            }
        } }
        onOpenWatch?.let { openWatch -> item {
            GradeySectionCard(title = stringResource(R.string.account_watch_access_title)) {
                Text(stringResource(R.string.account_watch_access_description), color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedButton(onClick = openWatch, enabled = !isLoading, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.account_watch_access_manage))
                }
            }
        } }
        onShowMealsTabChange?.let { change -> item {
            GradeySectionCard(title = stringResource(R.string.account_app_preferences_8eda2)) {
                onLanguageChange?.let { changeLanguage ->
                    var expanded by remember { mutableStateOf(false) }
                    val labels = mapOf(AppLanguage.SYSTEM to stringResource(R.string.account_system_language_07eab), AppLanguage.ENGLISH to stringResource(R.string.account_english_649df), AppLanguage.CZECH to stringResource(R.string.account_e_tina_a56cf), AppLanguage.ENGLISH_CHRONICALLY_ONLINE to stringResource(R.string.account_english_chronically_online_be532), AppLanguage.CZECH_CHRONICALLY_ONLINE to stringResource(R.string.account_e_tina_chronically_online_6e945))
                    Text(stringResource(R.string.account_language_89b86), style = MaterialTheme.typography.titleMedium)
                    androidx.compose.foundation.layout.Box {
                        OutlinedButton(onClick = { expanded = true }) { Text(labels.getValue(appLanguage)) }
                        androidx.compose.material3.DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            AppLanguage.entries.forEach { language -> androidx.compose.material3.DropdownMenuItem(
                                text = { Text(labels.getValue(language)) }, onClick = { expanded = false; changeLanguage(language) },
                            ) }
                        }
                    }
                    HorizontalDivider()
                }
                PreferenceSwitch(stringResource(R.string.account_show_meals_tab_aa1ad), stringResource(R.string.account_keep_your_canteen_one_tap_away_meals_also_stays_available_in_g_551e5), showMealsTab, true, change)
            }
        } }
        item {
            GradeySectionCard(title = stringResource(R.string.account_help_privacy_d985c)) {
                onOpenHelp?.let { TextButton(onClick = it) { Text(stringResource(R.string.account_help_center_46fc1)) } }
                onOpenPrivacy?.let { TextButton(onClick = it) { Text(stringResource(R.string.account_privacy_policy_9db10)) } }
                onOpenPrivacyChanges?.let { TextButton(onClick = it) { Text(stringResource(R.string.account_privacy_changes)) } }
                HorizontalDivider()
                Text(stringResource(R.string.account_age_status_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(when (ageAttestation) {
                    "sixteenOrOlder" -> R.string.account_age_status_adult
                    "thirteenToFifteenWithParent", "underThirteen" -> R.string.account_age_status_parent
                    else -> R.string.account_age_status_unknown
                }), color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.account_age_status_local), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider()
                onOpenTerms?.let { TextButton(onClick = it) { Text(stringResource(R.string.account_terms_of_use_97738)) } }
                Text(stringResource(R.string.account_gradey_is_an_independent_app_not_an_official_bakal_i_edupage_o_af9fb), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        versionName?.let { version -> item {
            Text("Gradey $version", modifier = Modifier.padding(vertical = GradeySpacing.sm), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } }
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

@Composable
private fun PreferenceSwitch(title: String, description: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

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
