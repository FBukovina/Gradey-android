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
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.model.AppLanguage
import com.bukovinafilip.gradey.model.GradeyAccount
import com.bukovinafilip.gradey.model.Friend
import com.bukovinafilip.gradey.model.LinkedCanteenAccount
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.model.SchoolStudentProfile
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.bukovinafilip.gradey.ui.AccountAvatar
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeyDialog
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeySheet
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.ModalCloseButton
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.ModalHeader
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.SettingsActionRow
import com.bukovinafilip.gradey.ui.SettingsBackground
import com.bukovinafilip.gradey.ui.SettingsGroup
import com.bukovinafilip.gradey.ui.SettingsRowDivider
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
        fun of(destination: SettingsDestination?): SettingsPage =
            if (destination == null) OVERVIEW else entries.firstOrNull { it.destination == destination } ?: OVERVIEW
    }
}

private val SettingsDestination.tag: String
    get() = when (this) {
        SettingsDestination.ACCOUNT -> "settingsDestination-account"
        SettingsDestination.CONNECTED_SERVICES -> "settingsDestination-connectedServices"
        SettingsDestination.NOTIFICATIONS -> "settingsDestination-notifications"
        SettingsDestination.PRIVACY_DATA -> "settingsDestination-privacyData"
        SettingsDestination.APP_PREFERENCES -> "settingsDestination-appPreferences"
        SettingsDestination.SUPPORT_ABOUT -> "settingsDestination-supportAbout"
    }

@Composable
private fun SettingsDestination.title(): String = stringResource(
    when (this) {
        SettingsDestination.ACCOUNT -> R.string.settings_destination_account_title
        SettingsDestination.CONNECTED_SERVICES -> R.string.settings_destination_connected_title
        SettingsDestination.NOTIFICATIONS -> R.string.settings_destination_notifications_title
        SettingsDestination.PRIVACY_DATA -> R.string.settings_destination_privacy_title
        SettingsDestination.APP_PREFERENCES -> R.string.settings_destination_preferences_title
        SettingsDestination.SUPPORT_ABOUT -> R.string.settings_destination_support_title
    },
)

/** Which confirmation is on screen. */
private enum class AccountConfirmation { NONE, SIGN_OUT, DELETE_FIRST, DELETE_FINAL }

/** Which quiet-hours time is being picked. */
private enum class QuietTimeEdit { NONE, START, END }

private val PageSlide = tween<IntOffset>(300, easing = FastOutSlowInEasing)

/**
 * Settings (iOS 2.8.1 `GradeyAccountHubView`): a full-screen surface with the overview — profile,
 * Connected services, Notifications, Privacy & Data, then the App group — and one pushed page per
 * destination that slides in over it. It draws its own background and handles the system bars;
 * [onClose] leaves it, system Back returns from a page to the overview first.
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
    friends: List<Friend> = emptyList(),
    onAddFriend: (() -> Unit)? = null,
    onRenameFriend: (Friend, String) -> Unit = { _, _ -> },
    onRemoveFriend: (Friend) -> Unit = {},
    dance: SettingsDance? = null,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.of(initialDestination)) }
    var focusName by rememberSaveable { mutableStateOf(false) }
    var removing by remember { mutableStateOf<LinkedSchoolAccount?>(null) }
    var removingCanteen by remember { mutableStateOf<LinkedCanteenAccount?>(null) }
    var renamingFriend by remember { mutableStateOf<Friend?>(null) }
    var friendName by remember { mutableStateOf("") }
    var removingFriend by remember { mutableStateOf<Friend?>(null) }
    var disconnectingDance by remember { mutableStateOf(false) }
    var confirmation by remember { mutableStateOf(AccountConfirmation.NONE) }
    var quietTimeEdit by remember { mutableStateOf(QuietTimeEdit.NONE) }
    var pickingStudent by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = page != SettingsPage.OVERVIEW) { page = page.parent }

    val systemLanguageCode = LocalConfiguration.current.locales[0].language
    val languageLabel = stringResource(
        if (appLanguage.pickerSelection(systemLanguageCode) == AppLanguage.CZECH) R.string.settings_language_czech else R.string.settings_language_english,
    )
    val schools = remember(linkedAccounts, activeAccountID) { sortedSchoolAccounts(linkedAccounts, activeAccountID) }
    val hasFullName = !account?.fullName.isNullOrBlank()
    val back = { page = page.parent }

    AnimatedContent(
        targetState = page, label = "settings", modifier = modifier.fillMaxSize(),
        transitionSpec = {
            if (targetState.depth > initialState.depth) {
                (slideInHorizontally(PageSlide) { it } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(PageSlide) { -it / 4 } + fadeOut(tween(200)))
            } else {
                (slideInHorizontally(PageSlide) { -it / 4 } + fadeIn(tween(200))) togetherWith (slideOutHorizontally(PageSlide) { it } + fadeOut(tween(200)))
            }
        },
    ) { current ->
        when (current) {
            SettingsPage.OVERVIEW -> SettingsOverview(
                onClose = onClose,
                onSelect = { destination ->
                    // Like iOS, opening Account without a name puts the cursor in the name field.
                    if (destination == SettingsDestination.ACCOUNT && !isGuest && !isDemo && account != null && !hasFullName) focusName = true
                    page = SettingsPage.of(destination)
                },
                account = account, isGuest = isGuest, isDemo = isDemo, isLoading = isLoading, errorMessage = errorMessage, onRetry = onRetry,
                localSchoolName = localSchoolName, schools = schools, canteenAccounts = canteenAccounts, hasCanteenSession = canteenSession != null,
                preferences = preferences, languageLabel = languageLabel,
            )
            SettingsPage.ACCOUNT -> SettingsDetailPage(SettingsDestination.ACCOUNT.title(), back, isLoading, errorMessage, onRetry) {
                accountPage(
                    account = account, isGuest = isGuest, isDemo = isDemo, isLoading = isLoading, liveSignInAvailable = liveSignInAvailable,
                    onSignOut = onSignOut, onGuestSignIn = onGuestSignIn, onUpdateProfile = onUpdateProfile,
                    focusName = focusName, onNameFocusHandled = { focusName = false },
                    activeStudentName = students.firstOrNull { it.id == activeStudentID }?.fullName,
                    canSwitchStudent = students.size > 1, onOpenStudents = { pickingStudent = true },
                    onConfirmSignOut = { confirmation = AccountConfirmation.SIGN_OUT },
                    onConfirmDelete = if (onDeleteAccount != null && account != null && !isGuest && !isDemo) ({ confirmation = AccountConfirmation.DELETE_FIRST }) else null,
                )
            }
            SettingsPage.CONNECTED -> SettingsDetailPage(SettingsDestination.CONNECTED_SERVICES.title(), back, isLoading, errorMessage, onRetry) {
                connectedServicesPage(
                    isGuest = isGuest, isDemo = isDemo, isLoading = isLoading, hasAccount = account != null, localSchoolName = localSchoolName,
                    schools = schools, activeAccountID = activeAccountID, newMarksEnabled = preferences.newMarksEnabled,
                    onActivateAccount = onActivateAccount, onAddAccount = onAddAccount,
                    onReconnectAccount = onReconnectAccount, onAccountNotificationsChange = onAccountNotificationsChange, onRemove = { removing = it },
                    canteenAccounts = canteenAccounts, canteenSession = canteenSession, currentCanteenLinkID = currentCanteenLinkID,
                    onLinkCanteen = onLinkCanteen, onOpenMeals = onOpenMeals,
                    onRemoveCanteen = if (onUnlinkCanteen != null) ({ removingCanteen = it }) else null, onOpenWatch = onOpenWatch,
                    friends = friends, onAddFriend = onAddFriend,
                    onRenameFriend = { friendName = it.name; renamingFriend = it }, onRemoveFriend = { removingFriend = it },
                    dance = dance, onDisconnectDance = { disconnectingDance = true },
                )
            }
            SettingsPage.NOTIFICATIONS -> SettingsDetailPage(SettingsDestination.NOTIFICATIONS.title(), back, isLoading, errorMessage, onRetry) {
                notificationsPage(
                    preferences = preferences, onPreferencesChange = onPreferencesChange, isGuest = isGuest, isDemo = isDemo, isLoading = isLoading,
                    hasSchool = linkedAccounts.isNotEmpty(), plannerRemindersEnabled = plannerRemindersEnabled, onPlannerRemindersChange = onPlannerRemindersChange,
                    onEditQuietHours = { start -> quietTimeEdit = if (start) QuietTimeEdit.START else QuietTimeEdit.END },
                    onNotificationSettings = onNotificationSettings,
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
                    appLanguage = appLanguage, systemLanguageCode = systemLanguageCode, onLanguageChange = onLanguageChange,
                    showMealsTab = showMealsTab, onShowMealsTabChange = onShowMealsTabChange, dance = dance,
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
    renamingFriend?.let { friend ->
        GradeyDialog(
            title = stringResource(R.string.settings_friends_rename), onDismiss = { renamingFriend = null },
            confirmLabel = stringResource(R.string.settings_friends_save), confirmEnabled = friendName.isNotBlank(),
            onConfirm = { renamingFriend = null; onRenameFriend(friend, friendName) }, dismissLabel = stringResource(R.string.settings_cancel),
        ) {
            BrandTextField(friendName, { friendName = it.take(60) }, Modifier.fillMaxWidth().testTag("friendNameField"), placeholder = stringResource(R.string.settings_friends_name))
        }
    }
    removingFriend?.let { friend ->
        GradeyDialog(
            title = stringResource(R.string.settings_friends_remove_title, friend.name), text = stringResource(R.string.settings_friends_remove_message),
            onDismiss = { removingFriend = null }, confirmLabel = stringResource(R.string.settings_friends_remove), destructive = true,
            onConfirm = { removingFriend = null; onRemoveFriend(friend) }, dismissLabel = stringResource(R.string.settings_cancel),
        )
    }
    if (disconnectingDance && dance != null) GradeyDialog(
        title = stringResource(R.string.settings_dance_disconnect_title), text = stringResource(R.string.settings_dance_disconnect_message),
        onDismiss = { disconnectingDance = false }, confirmLabel = stringResource(R.string.settings_dance_disconnect_confirm), destructive = true,
        onConfirm = { disconnectingDance = false; dance.onDisconnect() }, dismissLabel = stringResource(R.string.settings_cancel),
    )
    val dismissConfirmation = { confirmation = AccountConfirmation.NONE }
    when (confirmation) {
        AccountConfirmation.NONE -> Unit
        AccountConfirmation.SIGN_OUT -> GradeyDialog(
            title = stringResource(if (isGuest) R.string.settings_guest_sign_out_title else R.string.settings_sign_out_title),
            text = stringResource(if (isGuest) R.string.settings_guest_sign_out_message else R.string.settings_sign_out_message),
            onDismiss = dismissConfirmation,
            confirmLabel = stringResource(if (isGuest) R.string.settings_guest_sign_out else R.string.settings_sign_out), destructive = true,
            onConfirm = { dismissConfirmation(); onSignOut() }, dismissLabel = stringResource(R.string.settings_cancel),
        )
        AccountConfirmation.DELETE_FIRST -> GradeyDialog(
            title = stringResource(R.string.settings_delete_first_title), text = stringResource(R.string.settings_delete_first_message),
            onDismiss = dismissConfirmation, confirmLabel = stringResource(R.string.settings_delete_first_action), destructive = true,
            onConfirm = { confirmation = AccountConfirmation.DELETE_FINAL }, dismissLabel = stringResource(R.string.settings_cancel),
        )
        AccountConfirmation.DELETE_FINAL -> GradeyDialog(
            title = stringResource(R.string.settings_delete_final_title), text = stringResource(R.string.settings_delete_final_message),
            onDismiss = dismissConfirmation, confirmLabel = stringResource(R.string.settings_delete_final_action), destructive = true,
            onConfirm = { dismissConfirmation(); onDeleteAccount?.invoke() }, dismissLabel = stringResource(R.string.settings_cancel),
        )
    }
    if (quietTimeEdit != QuietTimeEdit.NONE) {
        val start = quietTimeEdit == QuietTimeEdit.START
        QuietTimeDialog(
            title = stringResource(if (start) R.string.settings_quiet_hours_start else R.string.settings_quiet_hours_end),
            initial = if (start) preferences.quietHoursStart else preferences.quietHoursEnd,
            onDismiss = { quietTimeEdit = QuietTimeEdit.NONE },
            onConfirm = { time ->
                quietTimeEdit = QuietTimeEdit.NONE
                val changed = if (start) preferences.copy(quietHoursStart = time) else preferences.copy(quietHoursEnd = time)
                onPreferencesChange(changed.withDeviceTimeZone())
            },
        )
    }
    if (pickingStudent && students.size > 1) StudentPickerSheet(
        students = students, activeStudentID = activeStudentID, enabled = !isLoading,
        onSelect = { id -> pickingStudent = false; if (id != activeStudentID) onSelectStudent(id) },
        onDismiss = { pickingStudent = false },
    )
}

// MARK: - Overview

/**
 * The Settings overview (iOS `overview`): header and profile 24 dp apart, then the groups 28 dp
 * apart, 20 dp from the sides and at most 640 dp wide.
 */
@Composable
private fun SettingsOverview(
    onClose: (() -> Unit)?,
    onSelect: (SettingsDestination) -> Unit,
    account: GradeyAccount?,
    isGuest: Boolean,
    isDemo: Boolean,
    isLoading: Boolean,
    errorMessage: String?,
    onRetry: () -> Unit,
    localSchoolName: String?,
    schools: List<LinkedSchoolAccount>,
    canteenAccounts: List<LinkedCanteenAccount>,
    hasCanteenSession: Boolean,
    preferences: NotificationPreferences,
    languageLabel: String,
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val services = remember(isGuest, schools, canteenAccounts, hasCanteenSession) {
        connectedServicesSummary(isGuest, schools, canteenAccounts, hasCanteenSession)
    }
    val servicesSubtitle = listOfNotNull(
        when (services.school) {
            SchoolSummary.ON_DEVICE -> localSchoolName ?: stringResource(R.string.settings_local_school_on_device)
            SchoolSummary.NOT_CONNECTED -> stringResource(R.string.settings_overview_school_not_connected)
            SchoolSummary.ACTIVE -> stringResource(R.string.settings_overview_school_active)
            null -> null
        },
        when (services.canteen) {
            CanteenSummary.CONNECTED -> stringResource(R.string.settings_overview_canteen_connected)
            CanteenSummary.NOT_CONNECTED -> stringResource(R.string.settings_overview_canteen_not_connected)
            null -> null
        },
    ).joinToString(" · ").ifEmpty { null }
    val servicesStatus = attentionStatusLine(
        listOfNotNull(
            if (services.schoolNeedsAttention) stringResource(R.string.settings_connected_school_title) else null,
            if (services.canteenNeedsAttention) stringResource(R.string.settings_connected_canteen_title) else null,
        ),
        stringResource(R.string.settings_status_action_required),
    )
    val notificationsSubtitle = when (notificationsSummary(markNotificationsAvailable(isGuest, schools.isNotEmpty(), isDemo), preferences)) {
        NotificationsSummary.UNAVAILABLE -> stringResource(R.string.settings_overview_notifications_unavailable)
        NotificationsSummary.OFF -> stringResource(R.string.settings_overview_notifications_off)
        NotificationsSummary.QUIET -> stringResource(R.string.settings_overview_notifications_quiet_until, formatQuietTime(preferences.quietHoursEnd, rememberShortTimeFormatter()))
        NotificationsSummary.ON -> stringResource(R.string.settings_overview_notifications_on)
    }
    SettingsBackground(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.align(Alignment.TopCenter).widthIn(max = GradeyMetrics.narrowContentMaxWidth).fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .testTag("accountHubScroll"),
            contentPadding = PaddingValues(start = SettingsPagePadding, end = SettingsPagePadding, top = SettingsPagePadding, bottom = GradeySpacing.xxl + bottomInset),
            verticalArrangement = Arrangement.spacedBy(SettingsOverviewSpacing),
        ) {
            item(key = "header", contentType = "header") {
                Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl)) {
                    if (onClose != null) ModalHeader(stringResource(R.string.settings_title), onClose, closeDescription = stringResource(R.string.settings_close))
                    else Text(stringResource(R.string.settings_title), Modifier.semantics { heading() }, style = GradeyType.display(36), color = MaterialTheme.colorScheme.onSurface)
                    ProfileSummary(account, isGuest, isDemo, onClick = { onSelect(SettingsDestination.ACCOUNT) })
                }
            }
            if (errorMessage != null) item(key = "error", contentType = "error") { SettingsErrorNotice(errorMessage, isLoading, onRetry) }
            item(key = "services", contentType = "group") {
                SettingsGroup {
                    DestinationRow(SettingsDestination.CONNECTED_SERVICES, onSelect, subtitle = servicesSubtitle, status = servicesStatus)
                    SettingsRowDivider()
                    DestinationRow(SettingsDestination.NOTIFICATIONS, onSelect, subtitle = notificationsSubtitle)
                    SettingsRowDivider()
                    DestinationRow(SettingsDestination.PRIVACY_DATA, onSelect)
                }
            }
            item(key = "app", contentType = "group") {
                Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                    SectionHeader(stringResource(R.string.settings_group_app), Modifier.padding(horizontal = GradeySpacing.xs))
                    SettingsGroup {
                        DestinationRow(SettingsDestination.APP_PREFERENCES, onSelect, subtitle = languageLabel)
                        SettingsRowDivider()
                        DestinationRow(SettingsDestination.SUPPORT_ABOUT, onSelect)
                    }
                }
            }
        }
    }
}

/** One overview row: icon, title, an orange status line when the page needs the user, its state (iOS `SettingsOverviewRow`). */
@Composable
private fun DestinationRow(destination: SettingsDestination, onSelect: (SettingsDestination) -> Unit, subtitle: String? = null, status: String? = null) {
    SettingsActionRow(destination.icon, destination.title(), { onSelect(destination) }, Modifier.testTag(destination.tag), subtitle = subtitle, status = status)
}

/** Avatar, name and what kind of profile this is (iOS `profileSummary`, min height 72). */
@Composable
private fun ProfileSummary(account: GradeyAccount?, isGuest: Boolean, isDemo: Boolean, onClick: () -> Unit) {
    val name = account?.fullName?.trim()?.takeIf { it.isNotEmpty() }
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
    SettingsGroup {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 72.dp).clickable(role = Role.Button, onClick = onClick)
                .padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.md)
                .semantics(mergeDescendants = true) {}.testTag(SettingsDestination.ACCOUNT.tag),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
        ) {
            AccountAvatar(if (isGuest) null else name)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
                Text(subtitle, style = GradeyType.footnote, color = MaterialTheme.gradeyColors.secondaryLabel)
            }
            DisclosureIcon(size = 14.dp)
        }
    }
}

// MARK: - Sheets and dialogs

/** EduPage parent profiles: pick the student whose school data Gradey shows (iOS `studentPicker`). */
@Composable
private fun StudentPickerSheet(
    students: List<SchoolStudentProfile>,
    activeStudentID: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    GradeySheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            Text(
                stringResource(R.string.settings_choose_student), Modifier.weight(1f).padding(start = GradeySpacing.xs).semantics { heading() },
                style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface,
            )
            ModalCloseButton(onDismiss, contentDescription = stringResource(R.string.settings_cancel))
        }
        SettingsGroup(Modifier.testTag("settingsStudentPicker"), footer = stringResource(R.string.settings_student_profiles_note)) {
            students.forEachIndexed { index, student ->
                if (index > 0) SettingsRowDivider(leadingInset = GradeySpacing.xs)
                SelectableOptionRow(
                    student.fullName, student.id == activeStudentID, { onSelect(student.id) },
                    Modifier.testTag("settingsStudent-${student.id}"), subtitle = student.className, enabled = enabled,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuietTimeDialog(title: String, initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    val context = LocalContext.current
    val time = remember(initial) { parseQuietTime(initial) }
    val state = rememberTimePickerState(time.hour, time.minute, is24Hour = DateFormat.is24HourFormat(context))
    GradeyDialog(
        title = title, onDismiss = onDismiss, confirmLabel = stringResource(R.string.settings_save),
        onConfirm = { onConfirm(quietTimeValue(state.hour, state.minute)) },
        dismissLabel = stringResource(R.string.settings_cancel),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { TimePicker(state) }
    }
}
