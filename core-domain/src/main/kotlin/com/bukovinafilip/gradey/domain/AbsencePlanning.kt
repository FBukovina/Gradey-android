package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.PlannedAbsence
import com.bukovinafilip.gradey.model.PlannedAbsenceLesson
import com.bukovinafilip.gradey.model.TimetableWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object AbsencePlanning {
    private val SchoolZone = ZoneId.of("Europe/Prague")
    private val HourFormatter = DateTimeFormatter.ofPattern("H:mm")

    fun availableLessons(
        timetable: TimetableWeek?,
        date: String,
        now: ZonedDateTime = ZonedDateTime.now(SchoolZone),
    ): List<PlannedAbsenceLesson> {
        if (!isUpcoming(date, "", now)) return emptyList()
        return timetable?.days.orEmpty()
            .filter { it.date?.take(10) == date }
            .flatMap { it.lessons }
            .filter { it.changeKind != LessonChangeKind.CANCELED && isUpcoming(date, it.hour.beginTime, now) }
            .map { lesson ->
                val name = lesson.subjectName?.takeIf(String::isNotBlank)
                    ?: lesson.subjectAbbrev?.takeIf(String::isNotBlank) ?: "Unknown subject"
                PlannedAbsenceLesson(
                    id = "$date|${lesson.id}",
                    hourID = lesson.hour.id,
                    subjectKey = name.trim().lowercase(Locale.ROOT).replace(Regex("\\s+"), " "),
                    subjectName = name,
                    timeRange = lesson.timeRange,
                    startTime = lesson.hour.beginTime,
                )
            }
            .distinctBy { it.id }
    }

    fun upcomingLessons(
        plans: List<PlannedAbsence>,
        now: ZonedDateTime = ZonedDateTime.now(SchoolZone),
    ): List<AbsenceLessonCandidate> = plans.flatMap { plan ->
        plan.lessons.filter { isUpcoming(plan.date, it.startTime, now) }.map { lesson ->
            AbsenceLessonCandidate(
                id = "${plan.date}|${lesson.id}",
                dateKey = plan.date,
                hourID = lesson.hourID,
                subjectKey = lesson.subjectKey,
                subjectName = lesson.subjectName,
            )
        }
    }.let(AbsencePrediction::selectedLessonsByID)

    fun upsert(plans: List<PlannedAbsence>, plan: PlannedAbsence): List<PlannedAbsence> {
        if (runCatching { LocalDate.parse(plan.date) }.isFailure) return plans
        val lessons = plan.lessons.distinctBy { it.id }
        return (plans.filterNot { it.date == plan.date } +
            if (lessons.isEmpty()) emptyList() else listOf(plan.copy(lessons = lessons)))
            .sortedBy { it.date }
    }

    private fun isUpcoming(date: String, startTime: String, now: ZonedDateTime): Boolean {
        val localDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return false
        val schoolNow = now.withZoneSameInstant(SchoolZone)
        if (localDate.isBefore(schoolNow.toLocalDate())) return false
        if (localDate.isAfter(schoolNow.toLocalDate())) return true
        val start = runCatching { LocalTime.parse(startTime.trim(), HourFormatter) }.getOrNull()
        return start == null || start.isAfter(schoolNow.toLocalTime())
    }
}
