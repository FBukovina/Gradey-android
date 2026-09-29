package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** A day strip retains every lesson; actionable rows contain only valid current/upcoming intervals. */
data class TodayTimetableOverview(
    val day: ScheduledDay,
    val lessons: List<ScheduledLesson>,
    val current: ScheduledLesson?,
    val next: ScheduledLesson?,
    val changedLessons: List<ScheduledLesson>,
    val untimedLessons: Boolean,
)

object TodayOverview {
    fun timetable(week: TimetableWeek?, today: LocalDate, now: LocalTime): TodayTimetableOverview? {
        if (week?.kind != TimetableKind.WEEKLY) return null
        val day = week.days.firstOrNull { it.date?.take(10) == today.toString() } ?: return null
        val lessons = day.lessons.sortedWith(compareBy<ScheduledLesson>(
            { PersonalPlanner.parseTime(it.hour.beginTime) ?: LocalTime.MAX },
            { PersonalPlanner.parseTime(it.hour.endTime) ?: LocalTime.MAX }, { it.hour.id }, { it.id },
        ))
        val timed = lessons.mapNotNull { lesson ->
            val start = PersonalPlanner.parseTime(lesson.hour.beginTime) ?: return@mapNotNull null
            val end = PersonalPlanner.parseTime(lesson.hour.endTime) ?: return@mapNotNull null
            if (end <= start) null else Triple(lesson, start, end)
        }
        val active = timed.filter { it.first.changeKind != LessonChangeKind.CANCELED }
        val current = active.firstOrNull { it.second <= now && now < it.third }
        val next = active.firstOrNull { if (current == null) it.second > now else it.second >= current.third }
        val changes = timed.filter { it.third > now && it.first.changeKind != LessonChangeKind.NONE }
            .map { it.first }.distinctBy { it.copy(id = "", groups = it.groups.sorted(), groupIDs = it.groupIDs.sorted(), cycles = it.cycles.sorted()) }
        return TodayTimetableOverview(day, lessons, current?.first, next?.first, changes, timed.size < lessons.size)
    }

    /** Keep unscoped personal entries, never expose another school's scoped entries in its dashboard. */
    fun planner(items: List<PersonalPlannerItem>, schoolScope: String?, now: Instant, limit: Int = 3): List<PersonalPlannerItem> =
        items.asSequence().filter { item ->
            val subjectScope = item.subject?.scope
            val lessonScope = item.lesson?.scope
            val scope = subjectScope ?: lessonScope
            !item.isCompleted && (subjectScope == null || lessonScope == null || subjectScope == lessonScope) &&
                (scope == null || scope == schoolScope)
        }.filter { item ->
            val date = PersonalPlanner.orderingDate(item)
            val instant = PersonalPlanner.orderingInstant(item)
            val timed = item.dueTime != null || (item.dueDate == null && item.lesson?.startEpochMillis != null)
            when {
                date == null -> true
                timed -> instant != null && instant > now
                else -> date >= now.atZone(PersonalPlanner.zone(item)).toLocalDate()
            }
        }.sortedWith(compareBy<PersonalPlannerItem> { PersonalPlanner.orderingInstant(it) ?: Instant.MAX }.thenBy { it.id })
            .take(limit.coerceAtLeast(0)).toList()
}
