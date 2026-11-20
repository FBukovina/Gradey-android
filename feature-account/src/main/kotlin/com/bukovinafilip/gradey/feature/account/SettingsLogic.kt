package com.bukovinafilip.gradey.feature.account

import androidx.compose.runtime.Immutable
import com.bukovinafilip.gradey.model.AppLanguage
import com.bukovinafilip.gradey.model.LinkedCanteenAccount
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.NotificationPreferences
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

/** Longest name a Gradey profile accepts (iOS `trimmedFullName.count > 80`). */
internal const val MaxNameLength = 80

/** Linked-account states that need the user, like iOS `accountNeedsAttention`. */
internal fun linkedStatusNeedsAttention(status: String): Boolean = status == "action_required" || status == "failed"

/** School accounts with the one used on this device first, then by name (iOS `schoolAccounts`). */
internal fun sortedSchoolAccounts(accounts: List<LinkedSchoolAccount>, activeID: String?): List<LinkedSchoolAccount> =
    accounts.sortedWith(compareBy<LinkedSchoolAccount> { it.id != activeID }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.displayName })

/** Second line of a linked school row: the school, or the provider when the school has no name (iOS `LinkedAccount.subtitle`). */
internal fun LinkedSchoolAccount.subtitle(): String = schoolName?.takeIf { it.isNotBlank() } ?: provider.displayName

/** What the overview says about the school. */
internal enum class SchoolSummary { ACTIVE, NOT_CONNECTED, ON_DEVICE }

/** What the overview says about the canteen. */
internal enum class CanteenSummary { CONNECTED, NOT_CONNECTED }

/**
 * The Connected services row of the overview (iOS `overviewSubtitle` + `overviewStatus`). A service
 * that waits for the user is named in the status line instead of the subtitle, so it isn't said twice.
 */
@Immutable
internal data class ConnectedServicesSummary(
    val school: SchoolSummary?,
    val canteen: CanteenSummary?,
    val schoolNeedsAttention: Boolean,
    val canteenNeedsAttention: Boolean,
)

internal fun connectedServicesSummary(
    isGuest: Boolean,
    schools: List<LinkedSchoolAccount>,
    canteens: List<LinkedCanteenAccount>,
    hasCanteenSession: Boolean,
): ConnectedServicesSummary {
    val schoolAttention = !isGuest && schools.any { linkedStatusNeedsAttention(it.status) }
    val cloudCanteens = if (isGuest) emptyList() else canteens
    val canteenAttention = cloudCanteens.any { linkedStatusNeedsAttention(it.status) }
    val school = when {
        isGuest -> SchoolSummary.ON_DEVICE
        schools.isEmpty() -> SchoolSummary.NOT_CONNECTED
        schoolAttention -> null
        else -> SchoolSummary.ACTIVE
    }
    val canteen = when {
        cloudCanteens.isEmpty() -> if (hasCanteenSession) CanteenSummary.CONNECTED else CanteenSummary.NOT_CONNECTED
        canteenAttention -> null
        else -> CanteenSummary.CONNECTED
    }
    return ConnectedServicesSummary(school, canteen, schoolAttention, canteenAttention)
}

/** "School accounts, canteen: Action required" (iOS `overviewStatus`), or null when nothing waits. */
internal fun attentionStatusLine(serviceTitles: List<String>, actionRequired: String): String? {
    if (serviceTitles.isEmpty()) return null
    val names = (listOf(serviceTitles.first()) + serviceTitles.drop(1).map { it.lowercase() }).joinToString(", ")
    return "$names: $actionRequired"
}

/** What the Notifications row of the overview says. */
internal enum class NotificationsSummary { UNAVAILABLE, OFF, QUIET, ON }

/** Mark alerts need a Gradey account and a school (the demo has sample ones). */
internal fun markNotificationsAvailable(isGuest: Boolean, hasSchool: Boolean, isDemo: Boolean): Boolean = !isGuest && (hasSchool || isDemo)

internal fun notificationsSummary(available: Boolean, preferences: NotificationPreferences): NotificationsSummary = when {
    !available -> NotificationsSummary.UNAVAILABLE
    !preferences.newMarksEnabled -> NotificationsSummary.OFF
    preferences.quietHoursEnabled -> NotificationsSummary.QUIET
    else -> NotificationsSummary.ON
}

/** Lock screen detail and quiet hours apply to mark alerts and Planner reminders (iOS `notificationControlsAreEnabled`). */
internal fun notificationControlsEnabled(available: Boolean, preferences: NotificationPreferences, plannerRemindersEnabled: Boolean): Boolean =
    (available && preferences.newMarksEnabled) || plannerRemindersEnabled

/** Every change made in Settings carries the device time zone, like iOS `updateNotificationPreferences`. */
internal fun NotificationPreferences.withDeviceTimeZone(zone: ZoneId = ZoneId.systemDefault()): NotificationPreferences =
    copy(quietHoursTimeZoneIdentifier = zone.id)

/** Save appears only once the field holds a valid name that differs from the saved one (iOS `canSaveFullName`). */
internal fun canSaveName(draft: String, current: String?): Boolean {
    val trimmed = draft.trim()
    return trimmed.isNotEmpty() && trimmed.length <= MaxNameLength && trimmed != current.orEmpty().trim()
}

/** "22:00" from the preferences, or [fallback] when it can't be read. */
internal fun parseQuietTime(value: String, fallback: LocalTime = LocalTime.of(22, 0)): LocalTime =
    runCatching { LocalTime.parse(value) }.getOrDefault(fallback)

/** The stored "HH:mm" form of a picked time. */
internal fun quietTimeValue(hour: Int, minute: Int): String = String.format(Locale.ROOT, "%02d:%02d", hour, minute)

/** English or Czech as the picker shows it; following the system resolves to the system language. */
internal fun AppLanguage.pickerSelection(systemLanguageCode: String): AppLanguage =
    if (this == AppLanguage.SYSTEM) AppLanguage.resolvedFromLanguageCode(systemLanguageCode) else pickerLanguage

/** Picking a language keeps the Chronically online switch as it was (iOS `selectPickerLanguage`). */
internal fun AppLanguage.selectingPickerLanguage(language: AppLanguage, systemLanguageCode: String): AppLanguage =
    language.withChronicallyOnline(isChronicallyOnline, systemLanguageCode)

/**
 * The TŠ Vavruška dance class as Settings shows it (iOS `vavruskaSection` and the `showVavruska` switch).
 *
 * @param enabled the dance class is shown on Today and in the Timetable.
 * @param connected a course is connected; the remaining values describe it.
 * @param needsReconnect its lesson plan link stopped working.
 * @param ended every lesson and event of the course is over.
 */
data class SettingsDance(
    val enabled: Boolean,
    val connected: Boolean,
    val courseCode: String? = null,
    val scheduleSummary: String? = null,
    val needsReconnect: Boolean = false,
    val ended: Boolean = false,
    val ticketAlerts: Boolean = false,
    val onEnabledChange: (Boolean) -> Unit = {},
    /** Opens the dance class screen, which connects a course when there is none. */
    val onOpen: () -> Unit = {},
    val onDisconnect: () -> Unit = {},
    val onTicketAlertsChange: (Boolean) -> Unit = {},
)
