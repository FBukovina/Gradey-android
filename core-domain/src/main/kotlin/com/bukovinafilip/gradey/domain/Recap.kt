package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.AbsenceCounts
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.RecapArchive
import com.bukovinafilip.gradey.model.Subject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** School time: every Recap boundary is a day in Prague. */
internal val RecapZone: ZoneId = ZoneId.of("Europe/Prague")

/** Dates as Bakaláři and EduPage write them: a full timestamp or just the day. */
internal object RecapDates {
    /** Seconds since the epoch; a bare day counts from its start in school time. */
    fun seconds(text: String?): Long? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { OffsetDateTime.parse(value) }.getOrNull()?.let { return it.toEpochSecond() }
        return runCatching { LocalDate.parse(value.take(10)) }.getOrNull()?.let(::seconds)
    }

    fun seconds(day: LocalDate): Long = day.atStartOfDay(RecapZone).toEpochSecond()

    /** The school day a timestamp falls on. */
    fun day(text: String?): LocalDate? {
        val value = text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        runCatching { OffsetDateTime.parse(value) }.getOrNull()?.let { return it.atZoneSameInstant(RecapZone).toLocalDate() }
        return runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
    }
}

// Periods

/** What one Gradey Recap covers. [id] matches the iOS raw value, so period ids are the same on both. */
enum class RecapKind(val id: String, val priority: Int) {
    MONTH("month", 0),
    QUARTER("quarter", 1),
    SEMESTER("semester", 2),
    CALENDAR_YEAR("calendarYear", 3),
}

/**
 * A month, quarter, semester or calendar year in school time (iOS `RecapPeriod`).
 *
 * @param number month 1–12, quarter 1 or 3, semester 1 or 2; 0 for a calendar year.
 * @param year the calendar year for months and calendar years; the school year's first year
 *   (2026 for 2026/27) for quarters and semesters.
 * @param end the day after the last one.
 */
data class RecapPeriod(val kind: RecapKind, val number: Int, val year: Int, val start: LocalDate, val end: LocalDate) {
    val id: String get() = "${kind.id}-$year-$number"

    /** The school year this period is listed under in the Recap list. */
    val schoolYearStart: Int get() = if (kind == RecapKind.MONTH && number < 9) year - 1 else year

    fun isOngoing(today: LocalDate): Boolean = today < end

    /** The semesters whose marks and absence this period reads. */
    val semesters: List<ReportCardPeriod>
        get() {
            val periods = mutableListOf<ReportCardPeriod>()
            var day = start
            while (day < end) {
                val period = ReportCardPeriod.containing(day)
                if (period !in periods) periods += period
                day = day.plusMonths(1)
            }
            return periods
        }

    companion object {
        fun month(month: Int, year: Int): RecapPeriod {
            val start = LocalDate.of(year, month, 1)
            return RecapPeriod(RecapKind.MONTH, month, year, start, start.plusMonths(1))
        }

        /** The 1st quarter runs to mid-November, the 3rd to mid-April, when schools hold their quarterly reviews. */
        fun quarter(quarter: Int, schoolYear: Int): RecapPeriod = if (quarter == 1) {
            RecapPeriod(RecapKind.QUARTER, quarter, schoolYear, LocalDate.of(schoolYear, 9, 1), LocalDate.of(schoolYear, 11, 16))
        } else {
            RecapPeriod(RecapKind.QUARTER, quarter, schoolYear, LocalDate.of(schoolYear + 1, 2, 1), LocalDate.of(schoolYear + 1, 4, 16))
        }

        /** Same boundaries as [ReportCardPeriod]: September to January is the first. */
        fun semester(semester: Int, schoolYear: Int): RecapPeriod = if (semester == 1) {
            RecapPeriod(RecapKind.SEMESTER, semester, schoolYear, LocalDate.of(schoolYear, 9, 1), LocalDate.of(schoolYear + 1, 2, 1))
        } else {
            RecapPeriod(RecapKind.SEMESTER, semester, schoolYear, LocalDate.of(schoolYear + 1, 2, 1), LocalDate.of(schoolYear + 1, 7, 1))
        }

        fun calendarYear(year: Int): RecapPeriod =
            RecapPeriod(RecapKind.CALENDAR_YEAR, 0, year, LocalDate.of(year, 1, 1), LocalDate.of(year + 1, 1, 1))
    }
}

/** When each recap comes out, and when Today points at it (iOS `RecapCalendar`). */
object RecapCalendar {
    /**
     * @param listedFrom from this day the period is in the Recap list.
     * @param todayUntil Today shows it from [listedFrom] up to, but not on, this day.
     */
    data class Window(val listedFrom: LocalDate, val todayUntil: LocalDate)

    fun today(): LocalDate = LocalDate.now(RecapZone)

    fun window(period: RecapPeriod): Window {
        val y = period.year
        return when (period.kind) {
            RecapKind.MONTH -> Window(period.end, period.end.plusDays(7))
            RecapKind.QUARTER -> {
                val year = if (period.number == 1) y else y + 1
                val month = if (period.number == 1) 11 else 4
                Window(LocalDate.of(year, month, 10), LocalDate.of(year, month, 25))
            }
            RecapKind.SEMESTER ->
                if (period.number == 1) Window(LocalDate.of(y + 1, 1, 20), LocalDate.of(y + 1, 2, 4))
                else Window(LocalDate.of(y + 1, 6, 15), LocalDate.of(y + 1, 6, 30))
            RecapKind.CALENDAR_YEAR -> Window(LocalDate.of(y, 12, 15), LocalDate.of(y + 1, 1, 6))
        }
    }

    /** Every recap of this school year and the previous one, plus the last two calendar years. */
    fun candidates(today: LocalDate): List<RecapPeriod> {
        val startYear = ReportCardPeriod.containing(today).startYear
        val periods = mutableListOf<RecapPeriod>()
        for (schoolYear in listOf(startYear - 1, startYear)) {
            for (month in 9..12) periods += RecapPeriod.month(month, schoolYear)
            for (month in 1..6) periods += RecapPeriod.month(month, schoolYear + 1)
            periods += RecapPeriod.quarter(1, schoolYear)
            periods += RecapPeriod.quarter(3, schoolYear)
            periods += RecapPeriod.semester(1, schoolYear)
            periods += RecapPeriod.semester(2, schoolYear)
        }
        periods += RecapPeriod.calendarYear(today.year - 1)
        periods += RecapPeriod.calendarYear(today.year)
        return periods
    }

    /** Out by now, newest first; the bigger recap first when two came out on the same day. */
    fun listed(today: LocalDate): List<RecapPeriod> = candidates(today)
        .filter { window(it).listedFrom <= today }
        .sortedWith(compareByDescending<RecapPeriod> { window(it).listedFrom }.thenByDescending { it.kind.priority })

    /** The ones Today points at right now, biggest first. */
    fun featured(today: LocalDate): List<RecapPeriod> = candidates(today)
        .filter { window(it).let { window -> window.listedFrom <= today && today < window.todayUntil } }
        .sortedByDescending { it.kind.priority }
}

// Archive

/** Reading and updating the per-account [RecapArchive]. */
object RecapArchives {
    fun key(period: ReportCardPeriod): String = "${period.schoolYear}-${period.semester}"

    fun period(key: String): ReportCardPeriod? {
        val parts = key.split('-')
        if (parts.size != 2) return null
        val semester = parts[1].toIntOrNull() ?: return null
        val startYear = parts[0].substringBefore('/').toIntOrNull() ?: return null
        return ReportCardPeriod(startYear, semester)
    }

    /** The semester most of the marks fall in, the later one on a tie; null without dated marks. */
    fun semesterOfMarks(subjects: List<Subject>): ReportCardPeriod? = subjects.asSequence().flatMap { it.marks }
        .mapNotNull { RecapDates.day(it.markDate) }.map(ReportCardPeriod::containing)
        .groupingBy { it }.eachCount().entries
        .maxWithOrNull(compareBy<Map.Entry<ReportCardPeriod, Int>> { it.value }.thenBy { it.key.startYear }.thenBy { it.key.semester })?.key

    /** The semester of the latest absence day; null without any. */
    fun semesterOfAbsence(response: AbsenceResponse): ReportCardPeriod? =
        response.absences.mapNotNull { RecapDates.day(it.date) }.maxOrNull()?.let(ReportCardPeriod::containing)

    /** [archive] with these marks filed under their semester. A new semester with no marks yet must not replace the last one. */
    fun adding(archive: RecapArchive, marks: MarksResponse, today: LocalDate): RecapArchive {
        val semester = semesterOfMarks(marks.subjects) ?: return archive
        return pruned(archive.copy(marks = archive.marks + (key(semester) to marks)), today)
    }

    fun adding(archive: RecapArchive, absence: AbsenceResponse, today: LocalDate): RecapArchive {
        val semester = semesterOfAbsence(absence) ?: return archive
        return pruned(archive.copy(absence = archive.absence + (key(semester) to absence)), today)
    }

    /** Keeps this school year and the two before it. */
    fun pruned(archive: RecapArchive, today: LocalDate): RecapArchive {
        val oldest = ReportCardPeriod.containing(today).startYear - (RecapArchive.KeptSchoolYears - 1)
        val kept: (String) -> Boolean = { key -> period(key)?.let { it.startYear >= oldest } ?: false }
        return RecapArchive(marks = archive.marks.filterKeys(kept), absence = archive.absence.filterKeys(kept))
    }
}

// Result

data class RecapSubjectStat(val id: String, val name: String, val average: Double, val markCount: Int)

sealed interface RecapMoment {
    data class OnesStreak(val count: Int) : RecapMoment
    data class Comeback(val subject: String, val from: Double, val to: Double) : RecapMoment
    data class HeaviestMark(val markText: String, val subject: String, val weight: Double) : RecapMoment
    data class BusiestDay(val date: LocalDate, val count: Int) : RecapMoment
}

data class RecapAbsence(val counts: AbsenceCounts, val topSubject: String?, val topSubjectHours: Int)

data class RecapTimetable(val lessons: Int, val canceled: Int, val substitutions: Int, val topCanceledSubject: String?)

/** @param isEstimate false once the teachers closed the report card. */
data class RecapReportCard(val average: Double, val isEstimate: Boolean, val ones: Int, val borderline: Int)

/** The calendar-year recap's look back at last school year's spring. */
data class RecapLastYear(
    val spring: ReportCardPeriod,
    val springAverage: Double?,
    val springAbsentHours: Double?,
    val autumnAverage: Double?,
    val autumnAbsentHours: Int?,
)

data class RecapComparison(val basis: Basis, val average: Double) {
    enum class Basis { PREVIOUS_MONTH, PREVIOUS_REPORT_CARD }
}

/** The Wrapped-style type, picked by the first rule that fits. */
sealed interface RecapPersonality {
    data object StraightAs : RecapPersonality
    data object Comeback : RecapPersonality
    data object Rollercoaster : RecapPersonality
    data object LastMinute : RecapPersonality
    data class Specialty(val subject: String) : RecapPersonality
    data object Steady : RecapPersonality
}

enum class RecapSlide { INTRO, MARKS, AVERAGE, TOP_SUBJECT, TOUGHEST_SUBJECT, MOMENTS, ABSENCE, TIMETABLE, PERSONALITY, REPORT_CARD, LAST_YEAR, SUMMARY }

/**
 * One period's recap (iOS `Recap`).
 *
 * @param distribution marks 1 to 5 with or without ±; index 0 holds the ones.
 * @param otherMarks points and verbal marks such as N or X.
 * @param springFromReportCard a calendar year whose spring marks are no longer on this device.
 */
data class Recap(
    val period: RecapPeriod,
    val isOngoing: Boolean,
    val markCount: Int,
    val distribution: List<Int>,
    val otherMarks: Int,
    val average: Double?,
    val comparison: RecapComparison?,
    val topSubject: RecapSubjectStat?,
    val toughestSubject: RecapSubjectStat?,
    val moments: List<RecapMoment>,
    val absence: RecapAbsence?,
    val timetable: RecapTimetable?,
    val personality: RecapPersonality?,
    val reportCard: RecapReportCard?,
    val lastYear: RecapLastYear?,
    val springFromReportCard: Boolean,
) {
    val hasContent: Boolean get() = markCount > 0 || (absence?.counts?.total ?: 0) > 0

    val slides: List<RecapSlide>
        get() {
            val isMonth = period.kind == RecapKind.MONTH
            return RecapSlide.entries.filter { slide ->
                when (slide) {
                    RecapSlide.INTRO, RecapSlide.SUMMARY -> true
                    RecapSlide.MARKS -> markCount > 0
                    RecapSlide.AVERAGE -> average != null
                    RecapSlide.TOP_SUBJECT -> topSubject != null
                    RecapSlide.TOUGHEST_SUBJECT -> !isMonth && toughestSubject != null
                    RecapSlide.MOMENTS -> !isMonth && moments.isNotEmpty()
                    RecapSlide.ABSENCE -> absence != null
                    RecapSlide.TIMETABLE -> (timetable?.lessons ?: 0) > 0
                    RecapSlide.PERSONALITY -> !isMonth && personality != null
                    RecapSlide.REPORT_CARD -> reportCard != null
                    RecapSlide.LAST_YEAR -> lastYear != null
                }
            }
        }
}
