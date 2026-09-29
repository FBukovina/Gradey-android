package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

object CompanionSnapshots {
    fun widget(week: TimetableWeek, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.of("Europe/Prague")): NextLessonWidgetSnapshot =
        NextLessonWidgetSnapshot(now, week.days.flatMap { day ->
            val date = runCatching { LocalDate.parse(day.date?.take(10)) }.getOrNull() ?: return@flatMap emptyList()
            day.lessons.filter { it.changeKind != LessonChangeKind.CANCELED }.map { lesson ->
                fun time(value: String) = runCatching { date.atTime(LocalTime.parse(value)).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
                val start = time(lesson.hour.beginTime)
                val rawEnd = time(lesson.hour.endTime)
                val end = if (start != null && rawEnd != null && rawEnd < start) {
                    runCatching { date.plusDays(1).atTime(LocalTime.parse(lesson.hour.endTime)).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
                } else rawEnd
                NextLessonWidgetLesson(
                    id = lesson.id, dayStartEpochMillis = date.atStartOfDay(zone).toInstant().toEpochMilli(),
                    startEpochMillis = start, endEpochMillis = end,
                    subjectName = lesson.subjectName, subjectAbbrev = lesson.subjectAbbrev,
                    timeRange = lesson.timeRange, room = lesson.roomTitle, teacher = lesson.teacherTitle,
                    changeKind = when (lesson.changeKind) {
                        LessonChangeKind.NONE -> NextLessonWidgetChangeKind.NONE
                        LessonChangeKind.CANCELED -> NextLessonWidgetChangeKind.CANCELED
                        LessonChangeKind.SUBSTITUTION -> NextLessonWidgetChangeKind.SUBSTITUTION
                        LessonChangeKind.ROOM_CHANGED -> NextLessonWidgetChangeKind.ROOM_CHANGED
                        LessonChangeKind.ADDED -> NextLessonWidgetChangeKind.ADDED
                    },
                )
            }
        })

    fun wear(week: TimetableWeek, user: UserResponse?, now: Long = System.currentTimeMillis()): GradeyWearSyncPayload {
        val snapshot = widget(week, now)
        return GradeyWearSyncPayload(
            generatedAtEpochMillis = now, isSignedIn = true,
            user = user?.let { GradeyWearUser(it.fullName, it.schoolName, it.classAbbrev) },
            timetable = GradeyWearTimetable(week.weekStart, now, week.days.mapNotNull { day ->
                val date = runCatching { LocalDate.parse(day.date?.take(10)) }.getOrNull() ?: return@mapNotNull null
                val start = date.atStartOfDay(ZoneId.of("Europe/Prague")).toInstant().toEpochMilli()
                GradeyWearTimetableDay(day.id, date.toString(), start, date.dayOfWeek.name,
                    day.dayDescription, date == java.time.Instant.ofEpochMilli(now).atZone(ZoneId.of("Europe/Prague")).toLocalDate(), day.lessons.isNotEmpty(),
                    snapshot.lessons.filter { it.dayStartEpochMillis == start }.map {
                        GradeyWearTimetableLesson(it.id, it.dayStartEpochMillis, it.startEpochMillis, it.endEpochMillis,
                            it.subjectName, it.subjectAbbrev, it.timeRange, it.room, it.teacher, it.changeKind)
                    })
            }),
        )
    }
}
