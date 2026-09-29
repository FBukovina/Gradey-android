package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.time.*

data class PlannerReminder(val day: String, val fireAt: Instant, val expiresAt: Instant, val items: List<PersonalPlannerItem>)

object PlannerReminders {
    fun eligible(items: List<PersonalPlannerItem>, scope: String?): List<PersonalPlannerItem> = items.filter {
        val subjectScope = it.subject?.scope
        val lessonScope = it.lesson?.scope
        !it.isCompleted && it.type != PlannerItemType.NOTE && PersonalPlanner.orderingDate(it) != null &&
            (subjectScope == null || lessonScope == null || subjectScope == lessonScope) &&
            (it.subject?.scope ?: it.lesson?.scope).let { itemScope -> itemScope == null || itemScope == scope }
    }

    fun make(items: List<PersonalPlannerItem>, scope: String?, preferences: NotificationPreferences,
             now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<PlannerReminder> {
        val today = now.atZone(zone).toLocalDate()
        val candidates = eligible(items, scope)
        return (1L..7L).mapNotNull { offset ->
            val dueDay = today.plusDays(offset)
            val plannedFire = dueDay.minusDays(1).atTime(18, 0).atZone(zone).toInstant()
            if (plannedFire <= now) return@mapNotNull null // No catch-up burst after 18:00.
            val fire = endOfQuietHours(plannedFire, preferences)
            val upcoming = candidates.filter { item ->
                val hasTime = item.dueDate?.let { item.dueTime != null } ?: (item.lesson?.startEpochMillis != null)
                val due = if (hasTime) PersonalPlanner.orderingInstant(item) else PersonalPlanner.orderingDate(item)?.atStartOfDay(zone)?.toInstant()
                val day = if (hasTime) due?.atZone(zone)?.toLocalDate() else PersonalPlanner.orderingDate(item)
                day == dueDay && due != null && fire < due
            }.sortedWith(compareBy<PersonalPlannerItem> { if (it.type in setOf(PlannerItemType.TEST, PlannerItemType.PRESENTATION)) 0 else 1 }
                .thenBy { PersonalPlanner.orderingInstant(it) }.thenBy { it.id })
            if (upcoming.isEmpty() || NotificationPolicy.isQuiet(preferences, fire)) return@mapNotNull null
            val expiry = upcoming.minOf { item ->
                val timed = item.dueDate?.let { item.dueTime != null } ?: (item.lesson?.startEpochMillis != null)
                if (timed) requireNotNull(PersonalPlanner.orderingInstant(item)) else dueDay.atStartOfDay(zone).toInstant()
            }
            PlannerReminder(dueDay.toString(), fire, expiry, upcoming)
        }
    }

    private fun endOfQuietHours(time: Instant, preferences: NotificationPreferences): Instant {
        if (!NotificationPolicy.isQuiet(preferences, time)) return time
        val zone = runCatching { ZoneId.of(preferences.quietHoursTimeZoneIdentifier) }.getOrDefault(ZoneId.of("Europe/Prague"))
        val end = runCatching { LocalTime.parse(preferences.quietHoursEnd) }.getOrNull() ?: return time
        val local = time.atZone(zone)
        val candidate = local.toLocalDate().atTime(end).atZone(zone)
        return (if (candidate.toInstant() <= time) candidate.plusDays(1) else candidate).toInstant()
    }
}
