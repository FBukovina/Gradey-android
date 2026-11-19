package com.bukovinafilip.gradey.feature.timetable

import androidx.compose.runtime.Immutable
import com.bukovinafilip.gradey.domain.TimetableDates
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.TimetableWeek
import java.time.LocalDate
import java.time.LocalTime

/** "8:00" or "08:00"; anything else is not a usable school time. */
private val SchoolTimePattern = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$")

private val PunctuationCategories = setOf(
    CharCategory.CONNECTOR_PUNCTUATION, CharCategory.DASH_PUNCTUATION, CharCategory.START_PUNCTUATION,
    CharCategory.END_PUNCTUATION, CharCategory.INITIAL_QUOTE_PUNCTUATION, CharCategory.FINAL_QUOTE_PUNCTUATION,
    CharCategory.OTHER_PUNCTUATION,
)

/** One column of the week row: a date of the displayed week and the provider day on it, if any. */
@Immutable
internal data class TimetableDaySlot(
    val date: LocalDate,
    val day: ScheduledDay?,
)

/** Which empty-day copy and icon to show (iOS `EmptyDayView`). */
internal enum class EmptyDayKind { NO_SCHOOL, WEEKEND, NO_LESSONS }

internal fun String.asSchoolTime(): LocalTime? {
    val parts = SchoolTimePattern.matchEntire(trim()) ?: return null
    return LocalTime.of(parts.groupValues[1].toInt(), parts.groupValues[2].toInt())
}

internal val ScheduledLesson.isCanceled: Boolean get() = changeKind == LessonChangeKind.CANCELED

/** A cancelled hour is not "happening now", so it never takes the live highlight (iOS `LessonClock`). */
internal fun ScheduledLesson.isCurrentAt(time: LocalTime): Boolean {
    if (isCanceled) return false
    val start = hour.beginTime.asSchoolTime() ?: return false
    val end = hour.endTime.asSchoolTime() ?: return false
    return !time.isBefore(start) && !time.isAfter(end)
}

/** Lessons in time order; lessons without a usable time keep their provider order at the end. */
internal fun ScheduledDay?.sortedLessons(): List<ScheduledLesson> =
    this?.lessons.orEmpty().sortedBy { it.hour.beginTime.asSchoolTime() ?: LocalTime.MAX }

/** Subject name, then abbreviation, then [unknown] (iOS `LessonText.subject`). */
internal fun ScheduledLesson.subjectTitle(unknown: String): String =
    subjectName?.takeIf { it.isNotBlank() } ?: subjectAbbrev?.takeIf { it.isNotBlank() } ?: unknown

/** Text of the subject tile: abbreviation, falling back to the full name; null draws an icon instead. */
internal val ScheduledLesson.tileText: String?
    get() = subjectAbbrev?.takeIf { it.isNotBlank() } ?: subjectName?.takeIf { it.isNotBlank() }

internal val ScheduledLesson.teacherDisplay: String?
    get() = teacherName?.takeIf { it.isNotBlank() } ?: teacherAbbrev?.takeIf { it.isNotBlank() }

internal val ScheduledLesson.roomDisplay: String?
    get() = roomAbbrev?.takeIf { it.isNotBlank() } ?: roomName?.takeIf { it.isNotBlank() }

/** "8:00 – 8:45", or the hour caption when the school sends no times. */
internal val ScheduledLesson.timeText: String
    get() = if (hour.beginTime.isBlank()) hour.caption else "${hour.beginTime.trim()} – ${hour.endTime.trim()}"

/** The school's note on a changed lesson, if it wrote one. */
internal fun ScheduledLesson.changeNote(): String? =
    if (changeKind == LessonChangeKind.NONE) null else changeDescription?.trim()?.takeIf { it.isNotEmpty() }

/**
 * The school's note, unless it only restates the change chip and the room or teacher the row already
 * shows ("Změna učebny: Aula." next to room Aula). A cancelled lesson always keeps its note, because it
 * replaces the room and teacher line (iOS `LessonText.distinctChangeNote`).
 */
internal fun ScheduledLesson.distinctChangeNote(changeLabel: String?): String? {
    val note = changeNote() ?: return null
    if (isCanceled) return note
    val shown = listOf(roomAbbrev, roomName, teacherName, teacherAbbrev, changeLabel).mapNotNull(::normalizedNoteText).toSet()
    if (normalizedNoteText(note)?.let { it in shown } == true) return null
    val detail = note.split(":", limit = 2).lastOrNull { it.isNotEmpty() }
    if (note.contains(':') && (normalizedNoteText(detail)?.let { it in shown } ?: true)) return null
    return note
}

private fun normalizedNoteText(text: String?): String? =
    text?.trim { it.isWhitespace() || it.category in PunctuationCategories }?.lowercase()?.takeIf { it.isNotEmpty() }

internal val ScheduledDay.hasChanges: Boolean get() = lessons.any { it.changeKind != LessonChangeKind.NONE }

internal val ScheduledDay.isSchoolDay: Boolean get() = dayType.equals("WorkDay", ignoreCase = true)

internal fun ScheduledDay?.emptyDayKind(): EmptyDayKind = when (this?.dayType?.lowercase()) {
    "holiday", "celebration", "directorday" -> EmptyDayKind.NO_SCHOOL
    "weekend" -> EmptyDayKind.WEEKEND
    else -> EmptyDayKind.NO_LESSONS
}

/**
 * Monday to Friday of the displayed week (to Sunday when the provider sends weekend days). Dated
 * days match by date; the undated permanent timetable matches by weekday.
 */
internal fun timetableDaySlots(week: TimetableWeek?, monday: LocalDate): List<TimetableDaySlot> {
    val days = week?.days.orEmpty()
    val byDate = days.mapNotNull { day ->
        day.date?.let { date -> runCatching { LocalDate.parse(date.take(10)) }.getOrNull()?.let { it to day } }
    }.toMap()
    val byWeekday = days.filter { it.date.isNullOrBlank() }.associateBy { it.dayOfWeek }
    val lastDay = if (days.any { it.dayOfWeek in 6..7 }) 6L else 4L
    return (0L..lastDay).map { offset ->
        val date = monday.plusDays(offset)
        TimetableDaySlot(date = date, day = byDate[date] ?: byWeekday[date.dayOfWeek.value])
    }
}

internal fun weekMonday(week: TimetableWeek?): LocalDate {
    val parsed = week?.weekStart?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    return parsed?.let(TimetableDates::monday) ?: TimetableDates.monday()
}

/** Today when it is in the week, else the first school day, else the first day (iOS `apply`). */
internal fun initialDayDate(slots: List<TimetableDaySlot>, today: LocalDate, monday: LocalDate, isPermanent: Boolean): LocalDate =
    slots.firstOrNull { !isPermanent && it.date == today }?.date
        ?: slots.firstOrNull { it.day?.isSchoolDay == true }?.date
        ?: slots.firstOrNull()?.date
        ?: monday
