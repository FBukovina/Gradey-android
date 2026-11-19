package com.bukovinafilip.gradey.feature.account

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.DestructiveLink
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyDialog
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.ModalHeader
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.SegmentedControl
import com.bukovinafilip.gradey.ui.SettingsActionRow
import com.bukovinafilip.gradey.ui.SettingsBackground
import com.bukovinafilip.gradey.ui.SettingsGroup
import com.bukovinafilip.gradey.ui.SettingsIcon
import com.bukovinafilip.gradey.ui.SettingsIconFrame
import com.bukovinafilip.gradey.ui.SettingsNote
import com.bukovinafilip.gradey.ui.SettingsRowDivider
import com.bukovinafilip.gradey.ui.SettingsToggleRow
import com.bukovinafilip.gradey.ui.SettingsValueRow
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.tabular
import java.time.LocalTime
import java.util.Locale

private const val DeveloperEmailUrl = "mailto:filip@openside.tech"
private const val DesignerEmailUrl = "mailto:tom@openside.tech"
private const val DeveloperInstagramUrl = "https://www.instagram.com/bukovinafilip"
private const val OpenSideUrl = "https://openside.tech"
private const val RepositoryUrl = "https://github.com/FBukovina/Gradey-android"
private const val EduPageRepositoryUrl = "https://github.com/EdupageAPI/edupage-api"
private const val MaxNameLength = 80

/** Overview destinations of the Settings hub, mirroring iOS `SettingsDestination`. */
enum class SettingsDestination(val icon: String) {
    ACCOUNT(HugeIcons.User),
    CONNECTED_SERVICES(HugeIcons.Link04),
    NOTIFICATIONS(HugeIcons.Notification01),
    PRIVACY_DATA(HugeIcons.SecurityLock),
    APP_PREFERENCES(HugeIcons.Settings02),
    SUPPORT_ABOUT(HugeIcons.HelpCircle),
}

/** Every page of Settings; Credits is pushed from Support & About. */
private enum class SettingsPage(val destination: SettingsDestination?, val depth: Int) {
    OVERVIEW(null, 0),
    ACCOUNT(SettingsDestination.ACCOUNT, 1),
    CONNECTED(SettingsDestination.CONNECTED_SERVICES, 1),
    NOTIFICATIONS(SettingsDestination.NOTIFICATIONS, 1),
    PRIVACY(SettingsDestination.PRIVACY_DATA, 1),
    PREFERENCES(SettingsDestination.APP_PREFERENCES, 1),
    SUPPORT(SettingsDestination.SUPPORT_ABOUT, 1),
    CREDITS(null, 2);

    val parent: SettingsPage get() = if (this == CREDITS) SUPPORT else OVERVIEW

    companion object {
        fun of(destination: SettingsDestination?): SettingsPage = entries.firstOrNull { it.destination == destination && destination != null } ?: OVERVIEW
    }
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

private val AppLanguage.isCzech: Boolean get() = this == AppLanguage.CZECH || this == AppLanguage.CZECH_CHRONICALLY_ONLINE
private val AppLanguage.isChronicallyOnline: Boolean get() = this == AppLanguage.ENGLISH_CHRONICALLY_ONLINE || this == AppLanguage.CZECH_CHRONICALLY_ONLINE

private fun appLanguage(czech: Boolean, chronicallyOnline: Boolean): AppLanguage = when {
    czech && chronicallyOnline -> AppLanguage.CZECH_CHRONICALLY_ONLINE
    czech -> AppLanguage.CZECH
    chronicallyOnline -> AppLanguage.ENGLISH_CHRONICALLY_ONLINE
    else -> AppLanguage.ENGLISH
}

/**
 * Settings (iOS 2.8.1 `GradeyAccountHubView`): a full-screen surface with the overview — profile,
 * Connected services, Notifications, Privacy & Data, then the App group — and one pushed page per
 * destination. It draws its own background and handles the system bars; [onClose] leaves it.
 */
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
    versionCode: Int? = null,
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
    var page by rememberSaveable { mutableStateOf(SettingsPage.of(initialDestination)) }
    var removing by remember { mutableStateOf<LinkedSchoolAccount?>(null) }
    var removingCanteen by remember { mutableStateOf<LinkedCanteenAccount?>(null) }
    var confirmingSignOut by remember { mutableStateOf(false) }
    var deleteStep by remember { mutableStateOf(0) }
    var editingQuietStart by remember { mutableStateOf<Boolean?>(null) }
    BackHandler(enabled = page != SettingsPage.OVERVIEW) { page = page.parent }

    val systemIsCzech = LocalConfiguration.current.locales[0].language == "cs"
    val czech = if (appLanguage == AppLanguage.SYSTEM) systemIsCzech else appLanguage.isCzech
    val languageLabel = stringResource(if (czech) R.string.settings_language_czech else R.string.settings_language_english)
    val back = { page = page.parent }

    AnimatedContent(
        targetState = page, label = "settings", modifier = modifier.fillMaxSize(),
        transitionSpec = {
            val spec = tween<androidx.compose.ui.unit.IntOffset>(300, easing = FastOutSlowInEasing)
            if (targetState.depth > initialState.depth) (slideInHorizontally(spec) { it } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(200)))
            else (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(spec) { it } + fadeOut(tween(200)))
        },
    ) { current ->
        when (current) {
            SettingsPage.OVERVIEW -> SettingsOverview(
                onClose = onClose, onSelect = { page = SettingsPage.of(it) },
                account = account, isGuest = isGuest, isDemo = isDemo, localSchoolName = localSchoolName,
                linkedAccounts = linkedAccounts, canteenConnected = canteenSession != null || canteenAccounts.isNotEmpty(),
                preferences = preferences, languageLabel = languageLabel,
            )
            SettingsPage.ACCOUNT -> SettingsDetailPage(SettingsDestination.ACCOUNT.title(), back, isLoading, errorMessage, onRetry) {
                accountPage(
                    account = account, isGuest = isGuest, isDemo = isDemo, isLoading = isLoading, liveSignInAvailable = liveSignInAvailable,
                    onSignOut = onSignOut, onGuestSignIn = onGuestSignIn, onUpdateProfile = onUpdateProfile,
                    students = students, activeStudentID = activeStudentID, onSelectStudent = onSelectStudent,
                    onConfirmSignOut = { confirmingSignOut = true }, onConfirmDelete = if (onDeleteAccount != null) ({ deleteStep = 1 }) else null,
                )
            }
            SettingsPage.CONNECTED -> SettingsDetailPage(SettingsDestination.CONNECTED_SERVICES.title(), back, isLoading, errorMessage, onRetry) {
                connectedServicesPage(
                    isGuest = isGuest, isDemo = isDemo, isLoading = isLoading, hasAccount = account != null, localSchoolName = localSchoolName,
                    linkedAccounts = linkedAccounts, activeAccountID = activeAccountID, onActivateAccount = onActivateAccount, onAddAccount = onAddAccount,
                    onReconnectAccount = onReconnectAccount, onAccountNotificationsChange = onAccountNotificationsChange, onRemove = { removing = it },
                    canteenAccounts = canteenAccounts, canteenSession = canteenSession, currentCanteenLinkID = currentCanteenLinkID,
                    onLinkCanteen = onLinkCanteen, onOpenMeals = onOpenMeals,
                    onRemoveCanteen = if (onUnlinkCanteen != null) ({ removingCanteen = it }) else null, onOpenWatch = onOpenWatch,
                )
            }
            SettingsPage.NOTIFICATIONS -> SettingsDetailPage(SettingsDestination.NOTIFICATIONS.title(), back, isLoading, errorMessage, onRetry) {
                notificationsPage(
                    preferences = preferences, onPreferencesChange = onPreferencesChange, isGuest = isGuest, isDemo = isDemo, isLoading = isLoading,
                    hasSchool = linkedAccounts.isNotEmpty(), plannerRemindersEnabled = plannerRemindersEnabled, onPlannerRemindersChange = onPlannerRemindersChange,
                    onEditQuietHours = { editingQuietStart = it }, onNotificationSettings = onNotificationSettings,
                )
            }
            SettingsPage.PRIVACY -> SettingsDetailPage(SettingsDestination.PRIVACY_DATA.title(), back, isLoading, errorMessage, onRetry) {
                privacyPage(
                    signedIn = account != null && !isGuest, isGuest = isGuest, isLoading = isLoading, ageAttestation = ageAttestation,
                    onOpenPrivacy = onOpenPrivacy, onOpenTerms = onOpenTerms, onOpenPrivacyChanges = onOpenPrivacyChanges, onExportData = onExportData,
                )
            }
            SettingsPage.PREFERENCES -> SettingsDetailPage(SettingsDestination.APP_PREFERENCES.title(), back, isLoading, errorMessage, onRetry) {
                preferencesPage(
                    czech = czech, chronicallyOnline = appLanguage.isChronicallyOnline, onLanguageChange = onLanguageChange,
                    showMealsTab = showMealsTab, onShowMealsTabChange = onShowMealsTabChange,
                )
            }
            SettingsPage.SUPPORT -> SettingsDetailPage(SettingsDestination.SUPPORT_ABOUT.title(), back, isLoading, errorMessage, onRetry) {
                supportPage(
                    onOpenHelp = onOpenHelp, onOpenSupport = onOpenSupport, onOpenUpdates = onOpenUpdates,
                    onOpenCredits = { page = SettingsPage.CREDITS }, versionName = versionName, versionCode = versionCode,
                )
            }
            SettingsPage.CREDITS -> SettingsDetailPage(stringResource(R.string.settings_credits_title), back, false, null, onRetry) { creditsPage() }
        }
    }

    removing?.let { linked ->
        GradeyDialog(
            title = stringResource(R.string.settings_unlink_title), text = stringResource(R.string.settings_unlink_school_message, linked.displayName),
            onDismiss = { removing = null }, confirmLabel = stringResource(R.string.settings_unlink_confirm), destructive = true,
            onConfirm = { removing = null; onUnlinkAccount(linked) }, dismissLabel = stringResource(R.string.settings_cancel),
        )
    }
    removingCanteen?.let { linked ->
        GradeyDialog(
            title = stringResource(R.string.settings_unlink_title), text = stringResource(R.string.settings_unlink_canteen_message, linked.displayName),
            onDismiss = { removingCanteen = null }, confirmLabel = stringResource(R.string.settings_unlink_confirm), destructive = true,
            onConfirm = { removingCanteen = null; onUnlinkCanteen?.invoke(linked) }, dismissLabel = stringResource(R.string.settings_cancel),
        )
    }
    if (confirmingSignOut) GradeyDialog(
        title = stringResource(if (isGuest) R.string.settings_guest_sign_out_title else R.string.settings_sign_out_title),
        text = stringResource(if (isGuest) R.string.settings_guest_sign_out_message else R.string.settings_sign_out_message),
        onDismiss = { confirmingSignOut = false }, confirmLabel = stringResource(R.string.settings_sign_out), destructive = true,
        onConfirm = { confirmingSignOut = false; onSignOut() }, dismissLabel = stringResource(R.string.settings_cancel),
    )
    when (deleteStep) {
        1 -> GradeyDialog(
            title = stringResource(R.string.settings_delete_first_title), text = stringResource(R.string.settings_delete_first_message),
            onDismiss = { deleteStep = 0 }, confirmLabel = stringResource(R.string.settings_delete_first_action), destructive = true,
            onConfirm = { deleteStep = 2 }, dismissLabel = stringResource(R.string.settings_cancel),
        )
        2 -> GradeyDialog(
            title = stringResource(R.string.settings_delete_final_title), text = stringResource(R.string.settings_delete_final_message),
            onDismiss = { deleteStep = 0 }, confirmLabel = stringResource(R.string.settings_delete_final_action), destructive = true,
            onConfirm = { deleteStep = 0; onDeleteAccount?.invoke() }, dismissLabel = stringResource(R.string.settings_cancel),
        )
    }
    editingQuietStart?.let { start ->
        QuietTimeDialog(
            title = stringResource(if (start) R.string.settings_quiet_hours_start else R.string.settings_quiet_hours_end),
            initial = if (start) preferences.quietHoursStart else preferences.quietHoursEnd,
            onDismiss = { editingQuietStart = null },
            onConfirm = { time ->
                editingQuietStart = null
                onPreferencesChange(if (start) preferences.copy(quietHoursStart = time) else preferences.copy(quietHoursEnd = time))
            },
        )
    }
}

// MARK: - Overview

@Composable
private fun SettingsOverview(
    onClose: (() -> Unit)?,
    onSelect: (SettingsDestination) -> Unit,
    account: GradeyAccount?,
    isGuest: Boolean,
    isDemo: Boolean,
    localSchoolName: String?,
    linkedAccounts: List<LinkedSchoolAccount>,
    canteenConnected: Boolean,
    preferences: NotificationPreferences,
    languageLabel: String,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val needsAttention = !isGuest && linkedAccounts.any { linkedStatusNeedsAttention(it.status) }
    val school = when {
        isGuest -> localSchoolName ?: stringResource(R.string.settings_local_school_on_device)
        linkedAccounts.isEmpty() -> stringResource(R.string.settings_overview_school_not_connected)
        else -> stringResource(R.string.settings_overview_school_active)
    }
    val canteen = stringResource(if (canteenConnected) R.string.settings_overview_canteen_connected else R.string.settings_overview_canteen_not_connected)
    val notifications = when {
        isGuest || (linkedAccounts.isEmpty() && !isDemo) -> stringResource(R.string.settings_overview_notifications_unavailable)
        !preferences.newMarksEnabled -> stringResource(R.string.settings_overview_notifications_off)
        preferences.quietHoursEnabled -> stringResource(R.string.settings_overview_notifications_quiet_until, preferences.quietHoursEnd)
        else -> stringResource(R.string.settings_overview_notifications_on)
    }
    SettingsBackground(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = GradeyMetrics.contentMaxWidth + SettingsPagePadding * 2).fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars).testTag("accountHubScroll"),
            contentPadding = PaddingValues(start = SettingsPagePadding, end = SettingsPagePadding, top = GradeySpacing.lg, bottom = GradeySpacing.xxl + bottomInset),
            verticalArrangement = Arrangement.spacedBy(SettingsOverviewSpacing),
        ) {
            item(key = "header", contentType = "header") {
                if (onClose != null) ModalHeader(stringResource(R.string.settings_title), onClose, closeDescription = stringResource(R.string.settings_close))
                else Text(stringResource(R.string.settings_title), Modifier.semantics { heading() }, style = GradeyType.display(36), color = MaterialTheme.colorScheme.onSurface)
            }
            item(key = "profile", contentType = "profile") {
                ProfileSummary(account, isGuest, isDemo, onClick = { onSelect(SettingsDestination.ACCOUNT) })
            }
            item(key = "services", contentType = "group") {
                SettingsGroup {
                    DestinationRow(
                        SettingsDestination.CONNECTED_SERVICES, onSelect, "settingsDestination-connectedServices", subtitle = "$school · $canteen",
                        status = if (needsAttention) stringResource(R.string.settings_status_action_required) else null,
                    )
                    SettingsRowDivider()
                    DestinationRow(SettingsDestination.NOTIFICATIONS, onSelect, "settingsDestination-notifications", subtitle = notifications)
                    SettingsRowDivider()
                    DestinationRow(SettingsDestination.PRIVACY_DATA, onSelect, "settingsDestination-privacyData")
                }
            }
            item(key = "app", contentType = "group") {
                Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                    SectionHeader(stringResource(R.string.settings_group_app), Modifier.padding(horizontal = GradeySpacing.xs))
                    SettingsGroup {
                        DestinationRow(SettingsDestination.APP_PREFERENCES, onSelect, "settingsDestination-appPreferences", subtitle = languageLabel)
                        SettingsRowDivider()
                        DestinationRow(SettingsDestination.SUPPORT_ABOUT, onSelect, "settingsDestination-supportAbout")
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationRow(destination: SettingsDestination, onSelect: (SettingsDestination) -> Unit, tag: String, subtitle: String? = null, status: String? = null) {
    SettingsActionRow(destination.icon, destination.title(), { onSelect(destination) }, Modifier.testTag(tag), subtitle = subtitle, status = status)
}

/** Avatar, name and what kind of profile this is (iOS profile row of the overview). */
@Composable
private fun ProfileSummary(account: GradeyAccount?, isGuest: Boolean, isDemo: Boolean, onClick: () -> Unit) {
    val name = account?.fullName?.takeIf { it.isNotBlank() }
    val title = when {
        isGuest -> stringResource(R.string.settings_profile_guest_title)
        name != null -> name
        isDemo -> stringResource(R.string.settings_demo_title)
        else -> stringResource(R.string.settings_profile_add_name_title)
    }
    val subtitle = stringResource(
        when {
            isGuest -> R.string.settings_profile_guest_message
            isDemo -> R.string.settings_profile_demo
            name != null -> R.string.settings_profile_gradey_id
            else -> R.string.settings_profile_add_name_message
        },
    )
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.sm).testTag("settingsDestination-account"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
    ) {
        AccountAvatar(if (isGuest) null else name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = GradeyType.subheadline, color = MaterialTheme.colorScheme.primary.copy(alpha = 0.78f))
        }
        DisclosureIcon(size = 14.dp)
    }
}

// MARK: - Account

private fun LazyListScope.accountPage(
    account: GradeyAccount?, isGuest: Boolean, isDemo: Boolean, isLoading: Boolean, liveSignInAvailable: Boolean,
    onSignOut: () -> Unit, onGuestSignIn: (() -> Unit)?, onUpdateProfile: ((String) -> Unit)?,
    students: List<SchoolStudentProfile>, activeStudentID: String?, onSelectStudent: (String) -> Unit,
    onConfirmSignOut: () -> Unit, onConfirmDelete: (() -> Unit)?,
) {
    if (isDemo) item(key = "demo", contentType = "notice") {
        Column(Modifier.padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            DetailSectionTitle(stringResource(R.string.settings_demo_title), Modifier.padding(horizontal = 0.dp))
            Text(
                stringResource(if (liveSignInAvailable) R.string.settings_demo_message_live else R.string.settings_demo_message_offline),
                style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel,
            )
            if (liveSignInAvailable) GradeyPrimaryButton(stringResource(R.string.settings_demo_connect), onSignOut, enabled = !isLoading)
        }
    }
    if (isGuest) item(key = "guest", contentType = "group") {
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            DetailSectionTitle(stringResource(R.string.settings_guest_account_title))
            Text(
                stringResource(R.string.settings_guest_account_heading), Modifier.padding(horizontal = GradeySpacing.xs),
                style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel,
            )
            onGuestSignIn?.let { signIn -> GradeyPrimaryButton(stringResource(R.string.settings_guest_account_action), signIn, enabled = !isLoading, icon = HugeIcons.User) }
            DestructiveLink(stringResource(R.string.settings_guest_sign_out), onConfirmSignOut, enabled = !isLoading)
        }
    } else if (account != null && !isDemo) {
        item(key = "profile", contentType = "group") {
            var name by rememberSaveable(account.id, account.fullName) { mutableStateOf(account.fullName.orEmpty()) }
            val trimmed = name.trim()
            val tooLong = trimmed.length > MaxNameLength
            SettingsGroup(title = stringResource(R.string.settings_account_profile_title)) {
                Column(Modifier.padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.md), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    BrandTextField(
                        value = name, onValueChange = { name = it }, modifier = Modifier.fillMaxWidth().testTag("settingsProfileName"),
                        label = stringResource(R.string.settings_account_name_title), placeholder = stringResource(R.string.settings_account_name_placeholder),
                        enabled = onUpdateProfile != null && !isLoading, isError = tooLong,
                    )
                    if (tooLong) Text(stringResource(R.string.settings_account_name_too_long), style = GradeyType.caption, color = GradeyColors.Poor)
                    if (onUpdateProfile != null) PlainLink(
                        stringResource(R.string.settings_account_name_save), { onUpdateProfile(trimmed) },
                        enabled = !isLoading && trimmed.isNotEmpty() && !tooLong && trimmed != account.fullName.orEmpty().trim(), showsArrow = false,
                    )
                }
                account.email?.takeIf { it.isNotBlank() }?.let { email ->
                    SettingsRowDivider(leadingInset = GradeySpacing.xs)
                    SettingsValueRow(stringResource(R.string.settings_account_email_title), email)
                }
            }
        }
    }
    if (students.size > 1) item(key = "students", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_switch_student), footer = stringResource(R.string.settings_student_profiles_note)) {
            students.forEachIndexed { index, student ->
                val active = student.id == activeStudentID
                if (index > 0) SettingsRowDivider()
                SettingsActionRow(
                    HugeIcons.Students, student.fullName, if (active) null else ({ onSelectStudent(student.id) }),
                    subtitle = student.className, enabled = !isLoading, selected = active,
                    trailing = { if (active) GradeyIcon(HugeIcons.Tick02, size = 16.dp, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
    if (!isGuest && account != null && !isDemo) item(key = "session", contentType = "actions") {
        Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            DestructiveLink(stringResource(R.string.settings_sign_out), onConfirmSignOut, Modifier.testTag("settingsSignOut"), enabled = !isLoading)
            if (onConfirmDelete != null) DestructiveLink(stringResource(R.string.settings_delete_account), onConfirmDelete, enabled = !isLoading)
        }
    }
}

// MARK: - Connected services

private fun LazyListScope.connectedServicesPage(
    isGuest: Boolean, isDemo: Boolean, isLoading: Boolean, hasAccount: Boolean, localSchoolName: String?,
    linkedAccounts: List<LinkedSchoolAccount>, activeAccountID: String?, onActivateAccount: (LinkedSchoolAccount) -> Unit, onAddAccount: () -> Unit,
    onReconnectAccount: ((LinkedSchoolAccount) -> Unit)?, onAccountNotificationsChange: ((LinkedSchoolAccount, Boolean) -> Unit)?, onRemove: (LinkedSchoolAccount) -> Unit,
    canteenAccounts: List<LinkedCanteenAccount>, canteenSession: StravaCZStoredSession?, currentCanteenLinkID: String?,
    onLinkCanteen: (() -> Unit)?, onOpenMeals: (() -> Unit)?, onRemoveCanteen: ((LinkedCanteenAccount) -> Unit)?, onOpenWatch: (() -> Unit)?,
) {
    if (isGuest) item(key = "guestNote", contentType = "notice") { AvailabilityMessage(stringResource(R.string.settings_connected_guest_explanation)) }
    item(key = "schools", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_connected_school_title)) {
            if (isGuest) {
                LinkedServiceRow(
                    accountID = "local", icon = HugeIcons.University,
                    name = localSchoolName ?: stringResource(R.string.settings_local_school_on_device),
                    subtitle = stringResource(R.string.settings_local_school_on_device), menu = null,
                    details = {
                        Text(stringResource(R.string.settings_local_school_detail), style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
                        PlainLink(stringResource(R.string.settings_change_school), onAddAccount, enabled = !isLoading)
                    },
                )
            } else {
                linkedAccounts.forEachIndexed { index, linked ->
                    val active = linked.id == activeAccountID
                    val attention = linkedStatusNeedsAttention(linked.status)
                    androidx.compose.runtime.key(linked.id) {
                        if (index > 0) SettingsRowDivider()
                        LinkedServiceRow(
                            accountID = linked.id, icon = HugeIcons.University, name = linked.displayName,
                            subtitle = listOfNotNull(linked.provider.displayName, linked.schoolName).joinToString(" · "),
                            menu = { close ->
                                if (!active) SettingsMenuItem(
                                    stringResource(R.string.settings_use_on_device), HugeIcons.ArrowDataTransferVertical,
                                    { close(); onActivateAccount(linked) }, enabled = !isLoading && linked.status == "active",
                                )
                                if (onReconnectAccount != null && !isDemo) SettingsMenuItem(
                                    stringResource(R.string.settings_reconnect), HugeIcons.Refresh04, { close(); onReconnectAccount(linked) }, enabled = !isLoading,
                                )
                                SettingsMenuItem(
                                    stringResource(R.string.settings_unlink), HugeIcons.Delete02, { close(); onRemove(linked) },
                                    enabled = !isLoading && !isDemo, color = GradeyColors.Poor,
                                )
                            },
                            details = {
                                if (active) RowStatusLabel(stringResource(R.string.settings_in_use), HugeIcons.Tick02, MaterialTheme.colorScheme.primary)
                                if (attention) {
                                    RowStatusLabel(linkedStatusText(linked.status, linked.actionRequiredReason), HugeIcons.AlertCircle, MaterialTheme.gradeyColors.orange)
                                    if (onReconnectAccount != null && !isDemo) PlainLink(stringResource(R.string.settings_reconnect), { onReconnectAccount(linked) }, enabled = !isLoading)
                                } else if (linked.status != "active") {
                                    RowStatusLabel(linkedStatusText(linked.status, null), HugeIcons.InformationCircle, MaterialTheme.gradeyColors.secondaryLabel)
                                }
                                if (onAccountNotificationsChange != null && !isDemo) SettingsToggleRow(
                                    stringResource(R.string.settings_mark_notifications), null, linked.notificationsEnabled,
                                    enabled = !isLoading && linked.status == "active", onChange = { onAccountNotificationsChange(linked, it) },
                                )
                            },
                        )
                    }
                }
                if (linkedAccounts.isNotEmpty()) SettingsRowDivider(leadingInset = GradeySpacing.xs)
                PlainLink(
                    stringResource(if (linkedAccounts.isEmpty()) R.string.settings_link_school else R.string.settings_link_another_school), onAddAccount,
                    Modifier.padding(vertical = GradeySpacing.xs).testTag("settingsLinkSchool"), enabled = !isLoading && !isDemo,
                )
            }
        }
    }
    if (onOpenMeals != null || canteenSession != null || canteenAccounts.isNotEmpty()) item(key = "canteen", contentType = "group") {
        SettingsGroup(
            title = stringResource(R.string.settings_connected_canteen_title),
            footer = if (canteenAccounts.isNotEmpty() && currentCanteenLinkID == null && !isGuest) stringResource(R.string.settings_canteen_existing_link_note) else null,
        ) {
            val provider = stringResource(R.string.settings_strava_provider)
            if (canteenSession != null) LinkedServiceRow(
                accountID = "canteen-local", icon = HugeIcons.Restaurant02,
                name = canteenSession.canteenName ?: stringResource(R.string.settings_canteen_number, canteenSession.canteenNumber),
                subtitle = provider, menu = null,
                details = { RowStatusLabel(stringResource(R.string.settings_canteen_on_device), HugeIcons.Tick02, MaterialTheme.colorScheme.primary) },
            )
            if (canteenSession != null && onLinkCanteen != null && hasAccount && !isGuest && currentCanteenLinkID == null) CloudLinkWarning(
                stringResource(R.string.settings_canteen_cloud_warning), stringResource(R.string.settings_link_strava), onLinkCanteen, enabled = !isLoading && !isDemo,
            )
            if (!isGuest) canteenAccounts.forEach { linked ->
                androidx.compose.runtime.key(linked.id) {
                    SettingsRowDivider()
                    LinkedServiceRow(
                        accountID = linked.id, icon = HugeIcons.LinkCircle02, name = linked.displayName, subtitle = linked.canteenName ?: provider,
                        menu = if (onRemoveCanteen != null) ({ close ->
                            SettingsMenuItem(
                                stringResource(R.string.settings_unlink), HugeIcons.Delete02, { close(); onRemoveCanteen(linked) },
                                enabled = !isLoading && !isDemo, color = GradeyColors.Poor,
                            )
                        }) else null,
                        details = if (linkedStatusNeedsAttention(linked.status)) ({
                            RowStatusLabel(linkedStatusText(linked.status, null), HugeIcons.AlertCircle, MaterialTheme.gradeyColors.orange)
                        }) else null,
                    )
                }
            }
            if (onOpenMeals != null) {
                if (canteenSession != null || canteenAccounts.isNotEmpty()) SettingsRowDivider(leadingInset = GradeySpacing.xs)
                PlainLink(
                    stringResource(if (canteenSession == null) R.string.settings_link_strava else R.string.settings_open_meals), onOpenMeals,
                    Modifier.padding(vertical = GradeySpacing.xs),
                )
            }
        }
    }
    if (onOpenWatch != null) item(key = "watch", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_watch_title), footer = stringResource(R.string.settings_watch_description)) {
            SettingsActionRow(HugeIcons.Watch01, stringResource(R.string.settings_watch_manage), onOpenWatch, enabled = !isLoading)
        }
    }
}

// MARK: - Notifications

private fun LazyListScope.notificationsPage(
    preferences: NotificationPreferences, onPreferencesChange: (NotificationPreferences) -> Unit, isGuest: Boolean, isDemo: Boolean, isLoading: Boolean,
    hasSchool: Boolean, plannerRemindersEnabled: Boolean, onPlannerRemindersChange: ((Boolean) -> Unit)?,
    onEditQuietHours: (start: Boolean) -> Unit, onNotificationSettings: (() -> Unit)?,
) {
    val marksEnabled = !isLoading && !isGuest && (hasSchool || isDemo)
    if (isGuest) item(key = "guestNote", contentType = "notice") { AvailabilityMessage(stringResource(R.string.settings_notifications_unavailable_guest)) }
    else if (!hasSchool && !isDemo) item(key = "schoolNote", contentType = "notice") { AvailabilityMessage(stringResource(R.string.settings_notifications_unavailable_school)) }
    item(key = "device", contentType = "group") {
        val permission = rememberNotificationPermission()
        SettingsGroup(
            title = stringResource(R.string.settings_notifications_device_title),
            footer = if (onPlannerRemindersChange != null) stringResource(R.string.settings_planner_reminder_note) else null,
        ) {
            SettingsValueRow(
                stringResource(R.string.settings_permission_title),
                stringResource(
                    when (permission) {
                        NotificationPermission.NOT_DETERMINED -> R.string.settings_permission_not_determined
                        NotificationPermission.DENIED -> R.string.settings_permission_denied
                        NotificationPermission.AUTHORIZED -> R.string.settings_permission_authorized
                    },
                ),
                icon = HugeIcons.Notification01,
                valueColor = if (permission == NotificationPermission.DENIED) MaterialTheme.gradeyColors.orange else MaterialTheme.gradeyColors.secondaryLabel,
            )
            if (onNotificationSettings != null) {
                SettingsRowDivider()
                SettingsActionRow(
                    HugeIcons.SmartPhone01,
                    stringResource(
                        when (permission) {
                            NotificationPermission.NOT_DETERMINED -> R.string.settings_permission_enable
                            NotificationPermission.DENIED -> R.string.settings_permission_open_settings
                            NotificationPermission.AUTHORIZED -> R.string.settings_android_notification_settings
                        },
                    ),
                    onNotificationSettings, Modifier.testTag("settingsNotificationPermission"),
                )
            }
            if (onPlannerRemindersChange != null) {
                SettingsRowDivider()
                SettingsToggleRow(
                    stringResource(R.string.settings_planner_reminder), null, plannerRemindersEnabled, enabled = !isLoading && !isDemo,
                    onChange = onPlannerRemindersChange, icon = HugeIcons.CheckList,
                )
            }
        }
    }
    item(key = "marks", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_mark_notifications_title)) {
            SettingsToggleRow(
                stringResource(R.string.settings_mark_notifications_toggle), null, preferences.newMarksEnabled && !isGuest, enabled = marksEnabled,
                onChange = { onPreferencesChange(preferences.copy(newMarksEnabled = it)) }, icon = HugeIcons.Notification02,
            )
            SettingsRowDivider()
            Column(Modifier.padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.md), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                    SettingsIcon(HugeIcons.ViewOff)
                    Text(stringResource(R.string.settings_lock_screen), style = GradeyType.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                }
                val details = NotificationLockScreenDetail.entries
                SegmentedControl(
                    options = listOf(stringResource(R.string.settings_lock_private), stringResource(R.string.settings_lock_subject), stringResource(R.string.settings_lock_full)),
                    selectedIndex = details.indexOf(preferences.lockScreenDetail),
                    onSelect = { if (marksEnabled) onPreferencesChange(preferences.copy(lockScreenDetail = details[it])) },
                    modifier = Modifier.fillMaxWidth().padding(start = SettingsIconFrame + GradeySpacing.md),
                )
            }
        }
    }
    item(key = "quietHours", contentType = "group") {
        SettingsGroup(
            title = stringResource(R.string.settings_quiet_hours_title),
            footer = if (preferences.quietHoursEnabled) stringResource(R.string.settings_time_zone_value, preferences.quietHoursTimeZoneIdentifier) else null,
        ) {
            SettingsToggleRow(
                stringResource(R.string.settings_quiet_hours_toggle), null, preferences.quietHoursEnabled, enabled = marksEnabled,
                onChange = { onPreferencesChange(preferences.copy(quietHoursEnabled = it)) }, icon = HugeIcons.Moon02,
            )
            if (preferences.quietHoursEnabled) {
                SettingsRowDivider()
                SettingsValueRow(stringResource(R.string.settings_quiet_hours_start), preferences.quietHoursStart, icon = HugeIcons.Time04, onClick = { onEditQuietHours(true) }, enabled = marksEnabled)
                SettingsRowDivider()
                SettingsValueRow(stringResource(R.string.settings_quiet_hours_end), preferences.quietHoursEnd, icon = HugeIcons.Sunrise, onClick = { onEditQuietHours(false) }, enabled = marksEnabled)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val context = LocalContext.current
    val time = remember(initial) { runCatching { LocalTime.parse(initial) }.getOrDefault(LocalTime.of(22, 0)) }
    val state = rememberTimePickerState(time.hour, time.minute, is24Hour = DateFormat.is24HourFormat(context))
    GradeyDialog(
        title = title, onDismiss = onDismiss, confirmLabel = stringResource(R.string.settings_save),
        onConfirm = { onConfirm(String.format(Locale.ROOT, "%02d:%02d", state.hour, state.minute)) },
        dismissLabel = stringResource(R.string.settings_cancel),
    ) { TimePicker(state, Modifier.align(Alignment.CenterHorizontally)) }
}

// MARK: - Privacy & data

private fun LazyListScope.privacyPage(
    signedIn: Boolean, isGuest: Boolean, isLoading: Boolean, ageAttestation: String?,
    onOpenPrivacy: (() -> Unit)?, onOpenTerms: (() -> Unit)?, onOpenPrivacyChanges: (() -> Unit)?, onExportData: (() -> Unit)?,
) {
    if (onOpenPrivacy != null || onOpenTerms != null || onOpenPrivacyChanges != null) item(key = "documents", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_privacy_documents_title)) {
            val rows = listOfNotNull(
                onOpenPrivacy?.let { Triple(HugeIcons.SecurityCheck, R.string.settings_privacy_policy, it) },
                onOpenTerms?.let { Triple(HugeIcons.Book02, R.string.settings_terms, it) },
                onOpenPrivacyChanges?.let { Triple(HugeIcons.InformationCircle, R.string.settings_privacy_changes, it) },
            )
            rows.forEachIndexed { index, (icon, title, action) ->
                if (index > 0) SettingsRowDivider()
                SettingsActionRow(icon, stringResource(title), action)
            }
        }
    }
    item(key = "age", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_age_title), footer = stringResource(R.string.settings_age_local)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.md),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
            ) {
                SettingsIcon(HugeIcons.UserCheck02)
                Text(
                    stringResource(
                        when (ageAttestation) {
                            "sixteenOrOlder" -> R.string.settings_age_sixteen
                            "thirteenToFifteenWithParent", "underThirteen" -> R.string.settings_age_teen
                            else -> R.string.settings_age_unknown
                        },
                    ),
                    Modifier.weight(1f), style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
    if (signedIn && onExportData != null) item(key = "export", contentType = "group") {
        SettingsGroup(title = stringResource(R.string.settings_export_title)) {
            SettingsActionRow(HugeIcons.FileExport, stringResource(R.string.settings_export_prepare), onExportData, Modifier.testTag("settingsExportData"), enabled = !isLoading)
        }
    }
    if (isGuest) item(key = "guestNote", contentType = "notice") { AvailabilityMessage(stringResource(R.string.settings_privacy_guest_explanation)) }
}

// MARK: - Language and preferences

private fun LazyListScope.preferencesPage(
    czech: Boolean, chronicallyOnline: Boolean, onLanguageChange: ((AppLanguage) -> Unit)?,
    showMealsTab: Boolean, onShowMealsTabChange: ((Boolean) -> Unit)?,
) {
    if (onLanguageChange != null) item(key = "language", contentType = "group") {
        SettingsGroup {
            listOf(false to R.string.settings_language_english, true to R.string.settings_language_czech).forEachIndexed { index, (isCzech, label) ->
                val selected = czech == isCzech
                if (index > 0) SettingsRowDivider()
                SettingsActionRow(
                    if (index == 0) HugeIcons.LanguageSkill else null, stringResource(label),
                    { if (!selected) onLanguageChange(appLanguage(isCzech, chronicallyOnline)) },
                    Modifier.padding(start = if (index == 0) 0.dp else SettingsIconFrame + GradeySpacing.md).testTag(if (isCzech) "settingsLanguageCzech" else "settingsLanguageEnglish"),
                    trailing = { if (selected) GradeyIcon(HugeIcons.Tick02, size = 16.dp, tint = MaterialTheme.colorScheme.primary) },
                )
            }
            SettingsRowDivider()
            SettingsToggleRow(
                stringResource(R.string.settings_chronically_online_title), stringResource(R.string.settings_chronically_online_subtitle), chronicallyOnline,
                enabled = true, onChange = { onLanguageChange(appLanguage(czech, it)) }, icon = HugeIcons.Happy,
            )
        }
    }
    if (onShowMealsTabChange != null) item(key = "meals", contentType = "group") {
        SettingsGroup {
            SettingsToggleRow(stringResource(R.string.settings_show_meals_tab), null, showMealsTab, enabled = true, onChange = onShowMealsTabChange, icon = HugeIcons.Restaurant02)
        }
    }
}

// MARK: - Support & about

private fun LazyListScope.supportPage(
    onOpenHelp: (() -> Unit)?, onOpenSupport: (() -> Unit)?, onOpenUpdates: (() -> Unit)?, onOpenCredits: () -> Unit,
    versionName: String?, versionCode: Int?,
) {
    item(key = "help", contentType = "group") {
        val uriHandler = LocalUriHandler.current
        SettingsGroup(title = stringResource(R.string.settings_support_title)) {
            val rows = listOfNotNull(
                onOpenHelp?.let { Triple(HugeIcons.Message01, R.string.settings_support_chat, it) },
                onOpenSupport?.let { Triple(HugeIcons.Favourite, R.string.settings_support_tips, it) },
                Triple(HugeIcons.Mail01, R.string.settings_support_contact, { runCatching { uriHandler.openUri(DeveloperEmailUrl) }; Unit }),
                Triple(HugeIcons.Github, R.string.settings_github, { runCatching { uriHandler.openUri(RepositoryUrl) }; Unit }),
            )
            rows.forEachIndexed { index, (icon, title, action) ->
                if (index > 0) SettingsRowDivider()
                SettingsActionRow(icon, stringResource(title), action)
            }
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
            SettingsRowDivider()
            SettingsValueRow(
                stringResource(R.string.settings_version),
                when {
                    versionName == null -> stringResource(R.string.settings_version_unknown)
                    versionCode != null -> stringResource(R.string.settings_version_value, versionName, versionCode)
                    else -> versionName
                },
                icon = HugeIcons.InformationCircle,
            )
        }
    }
}

// MARK: - Credits

private fun LazyListScope.creditsPage() {
    item(key = "openSide", contentType = "header") {
        val uriHandler = LocalUriHandler.current
        Column(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { runCatching { uriHandler.openUri(OpenSideUrl) } }
                .padding(horizontal = GradeySpacing.xs).testTag("creditsOpenSideLink"),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
        ) {
            SectionHeader(stringResource(R.string.settings_credits_made_by))
            Row {
                Text(stringResource(R.string.settings_credits_openside_open), style = GradeyType.display(32), color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.settings_credits_openside_side), style = GradeyType.display(32), color = MaterialTheme.gradeyColors.purple)
            }
            CreditLink(stringResource(R.string.settings_credits_openside_domain), icon = null, GradeyType.subheadlineSemibold)
        }
    }
    item(key = "team", contentType = "group") {
        SettingsGroup(Modifier.testTag("creditsTeam")) {
            CreditPerson(
                stringResource(R.string.settings_credits_lead_developer), stringResource(R.string.settings_credits_filip_name),
                DeveloperEmailUrl, DeveloperInstagramUrl,
            )
            SettingsRowDivider()
            CreditPerson(stringResource(R.string.settings_credits_lead_graphics), stringResource(R.string.settings_credits_tomas_name), DesignerEmailUrl, null)
        }
    }
    item(key = "bakalari", contentType = "attribution") {
        Attribution(stringResource(R.string.settings_credits_bakalari_title), stringResource(R.string.settings_credits_bakalari_message), null, Modifier.testTag("creditsBakalariAttribution"))
    }
    item(key = "edupage", contentType = "attribution") {
        Attribution(
            stringResource(R.string.settings_credits_edupage_title), stringResource(R.string.settings_credits_edupage_message),
            stringResource(R.string.settings_credits_edupage_repository) to EduPageRepositoryUrl, Modifier.testTag("creditsEduPageAttribution"),
        )
    }
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
                SectionHeader(role)
                Text(name, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            }
            CreditLink(emailUrl.removePrefix("mailto:"), HugeIcons.Mail01, GradeyType.footnoteBold, Modifier.clickable(role = Role.Button) { runCatching { uriHandler.openUri(emailUrl) } })
            if (instagramUrl != null) CreditLink(
                stringResource(R.string.settings_credits_instagram), HugeIcons.Camera01, GradeyType.footnoteBold,
                Modifier.clickable(role = Role.Button) { runCatching { uriHandler.openUri(instagramUrl) } },
            )
        }
    }
}

@Composable
private fun CreditLink(title: String, icon: String?, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    Row(modifier.heightIn(min = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        if (icon != null) GradeyIcon(icon, size = 14.dp, tint = primary)
        Text(title, style = style.tabular(), color = primary, maxLines = 1)
        GradeyIcon(HugeIcons.ArrowUpRight01, size = 11.dp, tint = primary)
    }
}

@Composable
private fun Attribution(title: String, message: String, link: Pair<String, String>?, modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    Column(modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        Text(title, Modifier.semantics { heading() }, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
        Text(message, style = GradeyType.footnote, color = MaterialTheme.gradeyColors.secondaryLabel)
        if (link != null) CreditLink(link.first, null, GradeyType.footnoteBold, Modifier.clickable(role = Role.Button) { runCatching { uriHandler.openUri(link.second) } })
    }
}
