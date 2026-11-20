package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.TimetableWeek
import java.text.Collator
import java.time.LocalDate
import java.util.Locale
import kotlin.math.sqrt

/**
 * One semester of school data as the device has it.
 *
 * @param prepared by subject ID; worked out once per semester, not per recap.
 */
data class RecapSemester(
    val period: ReportCardPeriod,
    val subjects: List<Subject>,
    val absence: AbsenceResponse?,
    val prepared: Map<String, PreparedGradeCalculation> = emptyMap(),
) {
    fun prepared(subject: Subject): PreparedGradeCalculation = prepared[subject.id] ?: GradeSimulation.prepare(subject)
}

/**
 * @param timetableWeeks null until the period's timetable weeks were loaded.
 * @param absenceLessons the missed lessons of each absent day by date key, for the absence slide; null when not known.
 */
data class RecapInput(
    val semesters: List<RecapSemester> = emptyList(),
    val reportCards: List<ReportCardTerm> = emptyList(),
    val timetableWeeks: List<TimetableWeek>? = null,
    val today: LocalDate,
    val absenceLessons: Map<String, AbsenceDayLessons>? = null,
)

/** Turns a period's marks, absence and timetable into a [Recap]. Everything is computed on the device (iOS `RecapBuilder`). */
object RecapBuilder {
    fun build(period: RecapPeriod, input: RecapInput): Recap {
        val end = minOf(period.end, input.today.plusDays(1))
        val periodSemesters = period.semesters
        val semesters = input.semesters.filter { it.period in periodSemesters }
        val entries = markEntries(semesters, period.start, end)
        val numeric = entries.filter { it.value != null }

        val distribution = IntArray(5)
        var otherMarks = 0
        for (entry in entries) if (entry.bucket != null) distribution[entry.bucket - 1] += 1 else otherMarks += 1

        val subjects = subjectStats(numeric, semesters, period.start, end)
        val average = mean(subjects.map { it.average })
        // Outside a month a subject needs three marks to rank, so a single lucky 1 in a rarely
        // marked subject doesn't top a whole semester.
        val minimumMarks = if (period.kind == RecapKind.MONTH) 2 else 3
        val ranked = subjects.filter { it.markCount >= minimumMarks }
            .sortedWith(compareBy<RecapSubjectStat> { it.average }.thenByDescending { it.markCount })
        val top = ranked.firstOrNull()
        val toughest = ranked.lastOrNull()?.takeIf { ranked.size >= 2 && it.average - (top?.average ?: 0.0) >= 0.01 }
        val startSeconds = RecapDates.seconds(period.start)
        val endSeconds = RecapDates.seconds(end)
        val midpoint = (startSeconds + endSeconds) / 2.0
        val isOngoing = period.isOngoing(input.today)

        val card = if (period.kind == RecapKind.SEMESTER) reportCard(period, semesters, input.reportCards) else null
        val lookBack = if (period.kind == RecapKind.CALENDAR_YEAR) lastYear(period, input, end) else null
        val springSemester = ReportCardPeriod(period.year - 1, 2)

        return Recap(
            period = period,
            isOngoing = isOngoing,
            markCount = entries.size,
            distribution = distribution.toList(),
            otherMarks = otherMarks,
            average = average,
            comparison = comparison(period, input),
            topSubject = top,
            toughestSubject = toughest,
            moments = moments(entries, numeric, midpoint),
            absence = absence(period, semesters, input.absenceLessons, end),
            timetable = input.timetableWeeks?.let { timetable(it, period.start, end) },
            personality = personality(numeric, ranked, average, period, midpoint, startSeconds, endSeconds, isOngoing),
            reportCard = card,
            lastYear = lookBack,
            springFromReportCard = period.kind == RecapKind.CALENDAR_YEAR && lookBack != null &&
                input.semesters.none { semester -> semester.period == springSemester && semester.subjects.any { it.marks.isNotEmpty() } },
        )
    }

    // Marks

    /**
     * @param value null for marks that don't count towards the average.
     * @param bucket 1–5 for "1"–"5" with or without ±; null for points and verbal marks.
     */
    data class MarkEntry(
        val id: String,
        val seconds: Long,
        val day: LocalDate,
        val subjectID: String,
        val subjectName: String,
        val markText: String,
        val value: Double?,
        val weight: Double,
        val bucket: Int?,
    )

    fun markEntries(semesters: List<RecapSemester>, start: LocalDate, end: LocalDate): List<MarkEntry> {
        val startSeconds = RecapDates.seconds(start)
        val endSeconds = RecapDates.seconds(end)
        val seen = HashSet<String>()
        val entries = mutableListOf<MarkEntry>()
        for (semester in semesters) {
            for (subject in semester.subjects) {
                val prepared = semester.prepared(subject).marks.associateBy { it.id }
                val name = subject.subjectInfo.name.trim().ifEmpty { subject.displayName }
                for (mark in subject.marks) {
                    val seconds = RecapDates.seconds(mark.markDate) ?: continue
                    if (seconds < startSeconds || seconds >= endSeconds || !seen.add(mark.id)) continue
                    val text = mark.markText.trim()
                    entries += MarkEntry(
                        id = mark.id,
                        seconds = seconds,
                        day = RecapDates.day(mark.markDate) ?: start,
                        subjectID = subject.id,
                        subjectName = name,
                        markText = text,
                        value = prepared[mark.id]?.value,
                        weight = prepared[mark.id]?.weight?.value ?: 1.0,
                        bucket = if (mark.isPoints) null else bucket(text),
                    )
                }
            }
        }
        return entries.sortedWith(compareBy<MarkEntry> { it.seconds }.thenBy { it.id })
    }

    fun bucket(markText: String): Int? {
        val digit = markText.firstOrNull()?.digitToIntOrNull()?.takeIf { it in 1..5 } ?: return null
        val rest = markText.drop(1)
        return if (rest.isEmpty() || rest == "+" || rest == "-") digit else null
    }

    private val nameOrder = Collator.getInstance(Locale.forLanguageTag("cs")).apply { strength = Collator.SECONDARY }

    /**
     * Weighted per subject; a subject wholly inside the period keeps its official school average,
     * so a semester recap matches the Marks screen.
     */
    private fun subjectStats(numeric: List<MarkEntry>, semesters: List<RecapSemester>, start: LocalDate, end: LocalDate): List<RecapSubjectStat> =
        numeric.groupBy { it.subjectID }.mapNotNull { (subjectID, marks) ->
            var average = weightedMean(marks)
            val sources = semesters.mapNotNull { semester -> semester.subjects.firstOrNull { it.id == subjectID }?.let(semester::prepared) }
            sources.singleOrNull()?.let { prepared ->
                val inside = prepared.marks.all { mark -> mark.date?.let { it >= start && it < end } ?: false }
                if (inside) prepared.displayAverage?.let { average = it }
            }
            average?.let { RecapSubjectStat(subjectID, marks.last().subjectName, it, marks.size) }
        }.sortedWith { left, right -> nameOrder.compare(left.name, right.name) }

    fun weightedMean(entries: List<MarkEntry>): Double? {
        val totalWeight = entries.sumOf { it.weight }
        if (totalWeight <= 0) return null
        return (entries.sumOf { (it.value ?: 0.0) * it.weight } / totalWeight).takeIf { it.isFinite() }
    }

    fun mean(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.average()

    /** Mean of the subject averages, like the overall average on Marks. */
    private fun average(semesters: List<RecapSemester>, start: LocalDate, end: LocalDate): Double? {
        val numeric = markEntries(semesters, start, end).filter { it.value != null }
        return mean(subjectStats(numeric, semesters, start, end).map { it.average })
    }

    private fun comparison(period: RecapPeriod, input: RecapInput): RecapComparison? = when (period.kind) {
        RecapKind.MONTH -> if (period.number == 9) null else {
            val previous = if (period.number == 1) RecapPeriod.month(12, period.year - 1) else RecapPeriod.month(period.number - 1, period.year)
            val previousSemesters = previous.semesters
            average(input.semesters.filter { it.period in previousSemesters }, previous.start, previous.end)
                ?.let { RecapComparison(RecapComparison.Basis.PREVIOUS_MONTH, it) }
        }
        RecapKind.QUARTER, RecapKind.SEMESTER -> {
            val previous = if (period.number == 1) ReportCardPeriod(period.year - 1, 2) else ReportCardPeriod(period.year, 1)
            input.reportCards.firstOrNull { it.matches(previous) }?.average?.let { RecapComparison(RecapComparison.Basis.PREVIOUS_REPORT_CARD, it) }
        }
        RecapKind.CALENDAR_YEAR -> null
    }

    // Moments

    private fun moments(entries: List<MarkEntry>, numeric: List<MarkEntry>, midpoint: Double): List<RecapMoment> {
        val moments = mutableListOf<RecapMoment>()

        var longest = 0
        var current = 0
        for (entry in entries) {
            if (entry.bucket == null) continue
            current = if (entry.bucket == 1) current + 1 else 0
            longest = maxOf(longest, current)
        }
        if (longest >= 3) moments += RecapMoment.OnesStreak(longest)

        numeric.groupBy { it.subjectID }.values.mapNotNull { marks ->
            val first = marks.filter { it.seconds < midpoint }
            val second = marks.filter { it.seconds >= midpoint }
            if (first.size < 2 || second.size < 2) return@mapNotNull null
            val before = weightedMean(first) ?: return@mapNotNull null
            val after = weightedMean(second) ?: return@mapNotNull null
            if (before - after >= 0.3) RecapMoment.Comeback(marks.last().subjectName, before, after) else null
        }.maxByOrNull { it.from - it.to }?.let { moments += it }

        // The heaviest mark, the earliest one when several share the weight.
        val heaviest = numeric.maxWithOrNull(compareBy<MarkEntry> { it.weight }.thenByDescending { it.seconds })
        val lightest = numeric.minOfOrNull { it.weight }
        if (heaviest != null && lightest != null && heaviest.weight > lightest) {
            moments += RecapMoment.HeaviestMark(heaviest.markText, heaviest.subjectName, heaviest.weight)
        }

        // The day with the most marks, the earliest one on a tie.
        entries.groupBy { it.day }.entries
            .maxWithOrNull(compareBy<Map.Entry<LocalDate, List<MarkEntry>>> { it.value.size }.thenByDescending { it.key })
            ?.takeIf { it.value.size >= 2 }?.let { moments += RecapMoment.BusiestDay(it.key, it.value.size) }
        return moments.take(3)
    }

    // Personality

    private fun personality(
        numeric: List<MarkEntry>, subjects: List<RecapSubjectStat>, average: Double?, period: RecapPeriod,
        midpoint: Double, startSeconds: Long, endSeconds: Long, isOngoing: Boolean,
    ): RecapPersonality? {
        if (period.kind == RecapKind.MONTH || numeric.size < 5) return null
        val values = numeric.mapNotNull { it.value }
        val ones = numeric.count { it.bucket == 1 }

        if (average != null && average <= 1.5 && ones.toDouble() / numeric.size >= 0.5) return RecapPersonality.StraightAs

        val first = numeric.filter { it.seconds < midpoint }.mapNotNull { it.value }
        val second = numeric.filter { it.seconds >= midpoint }.mapNotNull { it.value }
        val before = mean(first)
        val after = mean(second)
        if (first.size >= 3 && second.size >= 3 && before != null && after != null && before - after >= 0.4) return RecapPersonality.Comeback

        mean(values)?.let { mean ->
            val variance = values.sumOf { (it - mean) * (it - mean) } / values.size
            if (sqrt(variance) >= 1.0) return RecapPersonality.Rollercoaster
        }

        if (!isOngoing) {
            val lastStretch = endSeconds - (endSeconds - startSeconds) * 0.2
            if (numeric.count { it.seconds >= lastStretch }.toDouble() / numeric.size >= 0.4) return RecapPersonality.LastMinute
        }

        val best = subjects.firstOrNull()
        val worst = subjects.lastOrNull()
        if (best != null && worst != null && worst.average - best.average >= 1.5) return RecapPersonality.Specialty(best.name)
        return RecapPersonality.Steady
    }

    // Absence

    private fun absence(period: RecapPeriod, semesters: List<RecapSemester>, lessons: Map<String, AbsenceDayLessons>?, end: LocalDate): RecapAbsence? {
        val responses = semesters.mapNotNull { it.absence }
        if (responses.isEmpty()) return null
        val days = responses.flatMap { it.absences }.filter { day -> RecapDates.day(day.date)?.let { it >= period.start && it < end } ?: false }
        val counts = days.map { it.toCounts() }.sumCounts()
        // A whole semester names the subject from the school's own per-subject absence.
        val most = if (period.kind == RecapKind.SEMESTER) responses.first().absencesPerSubject.maxByOrNull { it.base }?.takeIf { it.base > 0 } else null
        if (most != null) return RecapAbsence(counts, most.subjectName.trim(), most.base)
        // Shorter periods count the lessons missed on their days, leaving out the ones a correction removed.
        if (lessons == null) return RecapAbsence(counts, null, 0)
        val dateKeys = days.mapTo(HashSet()) { AbsenceOverrideProjection.dateKey(it.date) }
        val top = lessons.filterKeys { it in dateKeys }.values.asSequence().flatMap { it.lessons }.filter { !it.isHidden }
            .groupingBy { it.subjectName }.eachCount().entries
            .maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })
        return RecapAbsence(counts, top?.key, top?.value ?: 0)
    }

    // Timetable

    private fun timetable(weeks: List<TimetableWeek>, start: LocalDate, end: LocalDate): RecapTimetable? {
        val seenDays = HashSet<String>()
        var lessons = 0
        var canceled = 0
        var substitutions = 0
        val canceledBySubject = LinkedHashMap<String, Int>()
        for (week in weeks) {
            for (day in week.days) {
                if (!day.dayType.equals("WorkDay", ignoreCase = true)) continue
                val date = RecapDates.day(day.date) ?: continue
                if (date < start || date >= end || !seenDays.add(day.id)) continue
                for (lesson in day.lessons) {
                    if (lesson.subjectName == null && lesson.changeKind == LessonChangeKind.NONE) continue
                    lessons += 1
                    when (lesson.changeKind) {
                        LessonChangeKind.CANCELED -> {
                            canceled += 1
                            lesson.subjectName?.let { canceledBySubject[it] = (canceledBySubject[it] ?: 0) + 1 }
                        }
                        LessonChangeKind.SUBSTITUTION -> substitutions += 1
                        LessonChangeKind.NONE, LessonChangeKind.ROOM_CHANGED, LessonChangeKind.ADDED -> Unit
                    }
                }
            }
        }
        if (lessons == 0) return null
        val top = canceledBySubject.entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key })?.key
        return RecapTimetable(lessons, canceled, substitutions, top)
    }

    // Report cards

    private fun reportCard(period: RecapPeriod, semesters: List<RecapSemester>, terms: List<ReportCardTerm>): RecapReportCard? {
        val semester = ReportCardPeriod(period.year, period.number)
        val term = terms.firstOrNull { it.matches(semester) }
        if (term != null && term.isClosed && term.marks.isNotEmpty()) {
            val average = term.average ?: mean(term.marks.mapNotNull { GradeMath.parseMarkValue(it.markText) }) ?: return null
            return RecapReportCard(average, isEstimate = false, ones = term.marks.count { it.markText == "1" }, borderline = 0)
        }
        val data = semesters.firstOrNull { it.period == semester }?.takeIf { it.subjects.isNotEmpty() } ?: return null
        val estimates = ReportCardEstimate.estimates(data.subjects, data.subjects.associate { it.id to data.prepared(it) }, term)
        val average = ReportCardEstimate.average(estimates) ?: return null
        return RecapReportCard(average, isEstimate = true, ones = estimates.count { it.markValue == 1.0 }, borderline = estimates.count { it.borderline != null })
    }

    private fun lastYear(period: RecapPeriod, input: RecapInput, end: LocalDate): RecapLastYear? {
        val spring = ReportCardPeriod(period.year - 1, 2)
        val term = input.reportCards.firstOrNull { it.matches(spring) }?.takeIf { it.average != null || it.absentHours != null } ?: return null
        val autumnStart = LocalDate.of(period.year, 9, 1)
        val autumn = input.semesters.filter { it.period == ReportCardPeriod(period.year, 1) }
        val autumnDays = autumn.mapNotNull { it.absence }.flatMap { it.absences }
            .filter { day -> RecapDates.day(day.date)?.let { it >= autumnStart && it < end } ?: false }
        return RecapLastYear(
            spring = spring,
            springAverage = term.average,
            springAbsentHours = term.absentHours,
            autumnAverage = average(autumn, autumnStart, end),
            autumnAbsentHours = if (autumn.any { it.absence != null }) autumnDays.map { it.toCounts() }.sumCounts().total else null,
        )
    }
}
