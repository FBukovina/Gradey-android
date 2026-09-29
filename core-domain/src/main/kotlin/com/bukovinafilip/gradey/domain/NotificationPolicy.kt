package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.NotificationLockScreenDetail
import com.bukovinafilip.gradey.model.NotificationPreferences
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

object NotificationPolicy {
    fun isQuiet(preferences: NotificationPreferences, now: Instant = Instant.now()): Boolean {
        if (!preferences.quietHoursEnabled) return false
        val start = runCatching { LocalTime.parse(preferences.quietHoursStart) }.getOrNull() ?: return false
        val end = runCatching { LocalTime.parse(preferences.quietHoursEnd) }.getOrNull() ?: return false
        if (start == end) return false
        val zone = runCatching { ZoneId.of(preferences.quietHoursTimeZoneIdentifier) }.getOrDefault(ZoneId.of("Europe/Prague"))
        val time = now.atZone(zone).toLocalTime()
        return if (start < end) time >= start && time < end else time >= start || time < end
    }

    /** Missing structured mark fields must never expose a stale full-detail payload. */
    fun lockScreenBody(detail: NotificationLockScreenDetail, fullBody: String, subject: String?, mark: String?,
                       privateSummary: String = "New school marks are available. Open Gradey to view them."): String = when (detail) {
        NotificationLockScreenDetail.PRIVATE_SUMMARY -> privateSummary
        NotificationLockScreenDetail.MARK_AND_SUBJECT ->
            if (!subject.isNullOrBlank() && !mark.isNullOrBlank()) "$subject · $mark"
            else privateSummary
        NotificationLockScreenDetail.FULL_DETAILS -> fullBody
    }
}
