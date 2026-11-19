package com.bukovinafilip.gradey.feature.today

import androidx.compose.runtime.Immutable
import com.bukovinafilip.gradey.domain.GradeBand
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.StravaCZMeal
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.TimetableKind
import com.bukovinafilip.gradey.model.TimetableWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.ceil

// Pure Today logic (iOS `TodayLessonCopy`, `TodaySnapshot`, `TodayViewModel`), kept free of Compose so it is unit-testable.

private val TimePattern = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$")

internal fun parseTime(raw: String): LocalTime? {
    val parts = TimePattern.matchEntire(raw.trim()) ?: return null
    return LocalTime.of(parts.groupValues[1].toInt(), parts.groupValues[2].toInt())
}

/** Whole minutes from [from] to [to], rounded up and never negative (iOS `positiveMinutes`). */
internal fun minutesBetween(from: LocalTime, to: LocalTime): Int =
    ceil(Duration.between(from, to).seconds / 60.0).toInt().coerceAtLeast(0)

/** Countdowns only help for the next couple of hours; further out the start time is easier to read. */
internal const val CountdownLimitMinutes = 120

/** How the "next lesson" row describes its start (iOS `TodayLessonCopy.startsIn`). */
internal sealed interface LessonStart {
    data class InMinutes(val minutes: Int) : LessonStart
    data class AtTime(val time: String) : LessonStart
}

internal fun lessonStart(lesson: ScheduledLesson, minutes: Int?): LessonStart? = when {
    minutes != null && minutes <= CountdownLimitMinutes -> LessonStart.InMinutes(minutes)
    lesson.hour.beginTime.isBlank() -> minutes?.let { LessonStart.InMinutes(it) }
    else -> LessonStart.AtTime(lesson.hour.beginTime)
}

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** Full subject for rows; cancelled atoms often carry it only in the change payload. */
internal fun optionalLessonSubject(lesson: ScheduledLesson): String? =
    lesson.subjectName.trimmedOrNull() ?: lesson.subjectAbbrev.trimmedOrNull() ?: lesson.changeDescription.trimmedOrNull()

/** Abbreviation for a strip chip. */
internal fun shortLessonSubject(lesson: ScheduledLesson): String =
    lesson.subjectAbbrev.trimmedOrNull() ?: lesson.subjectName.trimmedOrNull() ?: lesson.changeDescription.trimmedOrNull()?.take(6) ?: "–"

/** "08:00–08:45", or null when either end is missing. */
internal fun lessonTimeRange(lesson: ScheduledLesson): String? {
    val begin = lesson.hour.beginTime.trim()
    val end = lesson.hour.endTime.trim()
    return if (begin.isEmpty() || end.isEmpty()) null else "$begin–$end"
}

internal fun lessonRoom(lesson: ScheduledLesson): String? = (lesson.roomAbbrev ?: lesson.roomName).trimmedOrNull()

internal fun lessonTeacher(lesson: ScheduledLesson): String? = (lesson.teacherName ?: lesson.teacherAbbrev).trimmedOrNull()

/** "Maths · 3 · 10:00–10:45 · 214" under a timetable change. */
internal fun changeSubtitle(lesson: ScheduledLesson): String = listOfNotNull(
    optionalLessonSubject(lesson),
    listOfNotNull(lesson.hour.caption.trimmedOrNull(), lessonTimeRange(lesson)).joinToString(" · ").ifEmpty { null },
    lessonRoom(lesson),
).joinToString(" · ")

internal enum class LessonChipState { PAST, CURRENT, UPCOMING, CANCELLED }

internal fun lessonChipState(lesson: ScheduledLesson, currentID: String?, now: LocalTime): LessonChipState = when {
    lesson.changeKind == LessonChangeKind.CANCELED -> LessonChipState.CANCELLED
    lesson.id == currentID -> LessonChipState.CURRENT
    parseTime(lesson.hour.endTime)?.let { it <= now } == true -> LessonChipState.PAST
    else -> LessonChipState.UPCOMING
}

/** Non-cancelled lessons today; null when no weekly timetable is loaded (shown as "–"). */
internal fun activeLessonCount(week: TimetableWeek?, today: LocalDate): Int? {
    if (week == null || week.kind != TimetableKind.WEEKLY) return null
    val day = week.days.firstOrNull { it.date?.take(10) == today.toString() } ?: return 0
    return day.lessons.count { it.changeKind != LessonChangeKind.CANCELED }
}

/** Source key the insight store uses for the week containing [date]. */
internal fun timetableSourceKey(date: LocalDate): String = "timetable-" + date.minusDays((date.dayOfWeek.value - 1).toLong())

internal enum class GreetingSlot { MORNING, AFTERNOON, EVENING }

internal fun greetingSlot(hour: Int): GreetingSlot = when (hour) {
    in 5..11 -> GreetingSlot.MORNING
    in 12..17 -> GreetingSlot.AFTERNOON
    else -> GreetingSlot.EVENING
}

/** The student's name for the greeting, falling back to the active linked account. */
internal fun greetingName(fullName: String?, classAbbrev: String?, accountName: String?): String? =
    fullName?.let { nameWithoutClass(it, classAbbrev) }?.takeIf { it.isNotBlank() }
        ?: accountName?.let { nameWithoutClass(it, classAbbrev) }?.takeIf { it.isNotBlank() }

/** Bakaláři appends the class to `FullName` ("Novák Jan, 3.A"), which reads oddly in a greeting. */
internal fun nameWithoutClass(fullName: String, classAbbrev: String?): String {
    val trimmed = fullName.trim()
    val abbrev = classAbbrev?.trim().orEmpty()
    if (abbrev.isNotEmpty()) {
        val compact = trimmed.replace(", ", ",")
        if (compact.endsWith(",$abbrev") && compact.length > abbrev.length + 1) return trimmed.substringBeforeLast(',').trim()
    }
    val comma = trimmed.lastIndexOf(',')
    if (comma < 0) return trimmed
    val candidate = trimmed.substring(comma + 1).trim()
    val looksLikeClass = candidate.isNotEmpty() && candidate.length <= 6 && candidate.any(Char::isDigit) && ' ' !in candidate
    if (!looksLikeClass) return trimmed
    return trimmed.substring(0, comma).trim().ifEmpty { trimmed }
}

/** A linked school account that is not active, preferring the one on screen (iOS `accountRequiringReconnect`). */
internal fun accountRequiringReconnect(accounts: List<LinkedSchoolAccount>, activeAccountID: String?): LinkedSchoolAccount? {
    val candidates = accounts.filter { it.status != "active" }
    return candidates.firstOrNull { it.id == activeAccountID } ?: candidates.firstOrNull()
}

@Immutable
internal data class TodayRecentMark(
    val id: String,
    val subjectID: String,
    val subjectTitle: String,
    val markText: String,
    val band: GradeBand,
    val detail: String,
    val date: LocalDate?,
)

/** The newest recorded marks across all subjects, one entry per mark (iOS `recentMarks(limit:)`). */
internal fun recentMarks(subjects: List<Subject>, limit: Int = 3): List<TodayRecentMark> =
    subjects.asSequence()
        .flatMap { subject -> subject.marks.asSequence().map { subject to it } }
        .sortedWith(compareByDescending<Pair<Subject, com.bukovinafilip.gradey.model.Mark>> { it.second.markDate.orEmpty() }.thenBy { it.second.id })
        .distinctBy { it.second.id }
        .take(limit.coerceAtLeast(0))
        .map { (subject, mark) ->
            TodayRecentMark(
                id = mark.id,
                subjectID = subject.id,
                subjectTitle = subject.displayName,
                markText = mark.markText,
                band = GradeMath.band(mark),
                detail = mark.caption.trimmedOrNull() ?: mark.theme.trimmedOrNull() ?: subject.subjectInfo.abbrev.trimmedOrNull() ?: subject.displayName,
                date = mark.markDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            )
        }
        .toList()

/** Today's ordered meal (iOS `preferredMeal`). */
internal fun todayMeal(menu: StravaCZMenu?, today: LocalDate): StravaCZMeal? =
    menu?.days?.firstOrNull { it.date.take(10) == today.toString() }?.meals?.firstOrNull { it.ordered }
