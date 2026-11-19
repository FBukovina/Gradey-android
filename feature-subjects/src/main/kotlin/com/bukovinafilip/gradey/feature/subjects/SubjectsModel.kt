package com.bukovinafilip.gradey.feature.subjects

import androidx.compose.runtime.Immutable
import com.bukovinafilip.gradey.domain.AbsenceOverrideProjection
import com.bukovinafilip.gradey.domain.GradeBand
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.domain.GradeSimulation
import com.bukovinafilip.gradey.domain.MarkWeightSource
import com.bukovinafilip.gradey.domain.PreparedGradeCalculation
import com.bukovinafilip.gradey.domain.ResolvedMarkWeight
import com.bukovinafilip.gradey.domain.SchoolInsightState
import com.bukovinafilip.gradey.domain.SchoolSubjectInsights
import com.bukovinafilip.gradey.domain.SubjectDetailInsights
import com.bukovinafilip.gradey.model.AbsenceDayOverride
import com.bukovinafilip.gradey.model.AbsencePerSubject
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.Subject
import java.text.Collator
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// MARK: - Subject list

/** The Marks sort picker (iOS `SubjectSortMode`): Priority first, then Average. */
internal enum class SubjectSortMode { PRIORITY, AVERAGE }

private val CombiningMarks = Regex("\\p{M}+")
private val Whitespace = Regex("\\s+")

/**
 * Subject-name key used to pair marks with absence rows (iOS `AbsenceTimetableLessonResolver.normalized`):
 * trimmed, case- and diacritic-folded, with runs of whitespace collapsed to one space.
 */
internal fun String.subjectKey(): String =
    Normalizer.normalize(trim(), Normalizer.Form.NFD).replace(CombiningMarks, "").lowercase(Locale.ROOT).replace(Whitespace, " ")

/** iOS `SubjectsViewModel.matchingAbsence`: the single absence row named like the subject or its abbreviation. */
internal fun matchingAbsence(subject: Subject, keyedRows: List<Pair<String, AbsencePerSubject>>): AbsencePerSubject? {
    val names = setOf(subject.subjectInfo.name.subjectKey(), subject.subjectInfo.abbrev.subjectKey()) - ""
    if (names.isEmpty()) return null
    var match: AbsencePerSubject? = null
    for ((key, row) in keyedRows) {
        if (key !in names) continue
        if (match != null) return null
        match = row
    }
    return match
}

/** Missed share of lessons in percent (iOS `AbsencePerSubject.absencePercentage`). */
internal val AbsencePerSubject.absencePercentage: Double
    get() = if (lessonsCount > 0) base.toDouble() / lessonsCount.toDouble() * 100.0 else 0.0

/** Everything one Marks row and the subject page need, computed once per data change. */
@Immutable
internal class SubjectEntry(
    val subject: Subject,
    val prepared: PreparedGradeCalculation,
    val average: Double?,
    val band: GradeBand,
    val abbreviation: String,
    val latestMarkText: String?,
    val latestMarkBand: GradeBand,
    val absence: AbsencePerSubject?,
) {
    val id: String get() = subject.id
    val name: String get() = subject.subjectInfo.name.trim().ifEmpty { subject.displayName }
}

internal fun subjectEntries(subjects: List<Subject>, absence: AbsenceResponse): List<SubjectEntry> {
    val keyedRows = absence.absencesPerSubject.map { it.subjectName.subjectKey() to it }
    return subjects.distinctBy { it.id }.map { subject ->
        val prepared = GradeSimulation.prepare(subject)
        val average = prepared.displayAverage
        val latest = subject.marks.maxByOrNull { it.markDate.orEmpty() }
        val name = subject.subjectInfo.name.trim().ifEmpty { subject.displayName }
        SubjectEntry(
            subject = subject,
            prepared = prepared,
            average = average,
            band = GradeMath.band(average),
            abbreviation = subject.subjectInfo.abbrev.trim().ifEmpty { name.take(2).uppercase() },
            latestMarkText = latest?.displayText(),
            latestMarkBand = latest?.let(GradeMath::band) ?: GradeBand.NEUTRAL,
            absence = matchingAbsence(subject, keyedRows),
        )
    }
}

/** iOS `SubjectsViewModel.overallAverage`: the mean of every subject's displayed average. */
internal fun overallAverage(entries: List<SubjectEntry>): Double? =
    entries.mapNotNull { it.average }.takeIf { it.isNotEmpty() }?.average()

/**
 * iOS Priority score: current average, plus twice a worsening trend, plus 2 for an upcoming planner
 * event. Without a school scope there are no insights and the average alone decides.
 */
internal fun priorityScores(
    entries: List<SubjectEntry>,
    insightState: SchoolInsightState?,
    plannerItems: List<PersonalPlannerItem>,
    schoolScope: String?,
    now: Instant = Instant.now(),
): Map<String, Double> {
    if (schoolScope == null) return entries.associate { it.id to (it.average ?: 0.0) }
    val observations = insightState?.observations.orEmpty().filter { it.scope == schoolScope }.groupBy { it.subjectID }
    val plannedSubjects = plannerItems.mapNotNullTo(HashSet()) { item -> item.subject?.takeIf { it.scope == schoolScope }?.id }
    return entries.associate { entry ->
        val trend = SchoolSubjectInsights.trendDelta(observations[entry.id].orEmpty(), now.toEpochMilli())
        val hasEvents = entry.id in plannedSubjects &&
            SubjectDetailInsights.make(entry.subject, schoolScope, insightState, plannerItems, now).upcomingEvents.isNotEmpty()
        entry.id to (entry.average ?: 0.0) + max(trend ?: 0.0, 0.0) * 2.0 + (if (hasEvents) 2.0 else 0.0)
    }
}

internal fun sortedEntries(
    entries: List<SubjectEntry>,
    mode: SubjectSortMode,
    priorityScores: Map<String, Double>,
    locale: Locale,
): List<SubjectEntry> {
    val collator = Collator.getInstance(locale).apply { strength = Collator.SECONDARY }
    val byName = Comparator<SubjectEntry> { first, second -> collator.compare(first.name, second.name) }
    return when (mode) {
        SubjectSortMode.PRIORITY -> entries.sortedWith(
            compareByDescending<SubjectEntry> { priorityScores[it.id] ?: it.average ?: 0.0 }.then(byName),
        )
        SubjectSortMode.AVERAGE -> entries.sortedWith(
            compareBy<SubjectEntry> { it.average ?: Double.MAX_VALUE }.then(byName),
        )
    }
}

/** Overrides the person still applies (paused ones wait for review and do not change the totals). */
internal fun List<AbsenceDayOverride>.active(): List<AbsenceDayOverride> = filter { it.pauseReason == null }

/** iOS `SubjectDetailView.hasLocalAbsenceAdjustment`: a hidden lesson belongs to this subject's absence row. */
internal fun hasLocalAbsenceAdjustment(absence: AbsencePerSubject?, activeOverrides: List<AbsenceDayOverride>): Boolean {
    absence ?: return false
    val name = AbsenceOverrideProjection.normalizedSubjectName(absence.subjectName)
    return activeOverrides.any { saved ->
        saved.hiddenAllocations.any { AbsenceOverrideProjection.normalizedSubjectName(it.subjectName) == name }
    }
}

// MARK: - Marks

/** iOS `Mark.displayText`: points as "8/10". */
internal fun Mark.displayText(): String =
    if (isPoints && !pointsText.isNullOrBlank() && maxPoints != null) "$markText/$maxPoints" else markText

/** Same identity [GradeSimulation.prepare] gives a mark, so resolved weights line up with rows. */
internal fun Mark.identity(): String = id.ifBlank { "local-mark-${GradeSimulation.revision(this)}" }

/** A metadata chip under a mark: points out of the maximum, or a weight other than 1. */
@Immutable
internal sealed interface MarkChip {
    @Immutable data class Points(val text: String) : MarkChip
    @Immutable data class Weight(val formatted: String, val estimated: Boolean) : MarkChip
}

@Immutable
internal class MarkEntry(
    val key: String,
    val markText: String,
    val band: GradeBand,
    /** Caption, then topic; null means "Untitled". */
    val caption: String?,
    /** The topic, only when it adds something beyond the caption. */
    val topic: String?,
    val typeNote: String?,
    val date: LocalDate?,
    val instant: Instant?,
    val chip: MarkChip?,
)

private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

/** The teacher's mark-type note, never a provider code, a symbol or a repeat of the caption (iOS `typeLabel`). */
internal fun typeLabel(typeNote: String?, caption: String): String? {
    val note = typeNote.trimmedOrNull() ?: return null
    if (note.length <= 1 || note.none(Char::isLetter) || note.equals(caption, ignoreCase = true)) return null
    return note
}

internal fun markInstant(raw: String?, zone: ZoneId): Instant? {
    val value = raw.trimmedOrNull() ?: return null
    return runCatching { OffsetDateTime.parse(value).toInstant() }.getOrNull()
        ?: markDate(value)?.atStartOfDay(zone)?.toInstant()
}

internal fun markDate(raw: String?): LocalDate? {
    val value = raw.trimmedOrNull() ?: return null
    return runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}

/**
 * iOS `SubjectDetailViewModel.sortedMarks`: one row per mark identity, newest first, ties by id.
 * [untitled] is the localized caption fallback used to filter repeated type notes.
 */
internal fun markEntries(subject: Subject, prepared: PreparedGradeCalculation, locale: Locale, untitled: String, zone: ZoneId): List<MarkEntry> {
    val weights = prepared.resolvedWeights
    return subject.marks.groupBy { it.identity() }.map { (key, records) ->
        val mark = records.first()
        val caption = mark.caption.trimmedOrNull() ?: mark.theme.trimmedOrNull()
        val rawCaption = mark.caption.trimmedOrNull()
        val rawTheme = mark.theme.trimmedOrNull()
        val topic = if (rawCaption != null && rawTheme != null && rawCaption != rawTheme) rawTheme else null
        MarkEntry(
            key = key,
            markText = mark.markText,
            band = GradeMath.band(mark),
            caption = caption,
            topic = topic,
            typeNote = typeLabel(mark.typeNote, caption ?: untitled),
            date = markDate(mark.markDate),
            instant = markInstant(mark.markDate, zone),
            chip = markChip(mark, weights[key], locale),
        )
    }.sortedWith(compareByDescending<MarkEntry> { it.instant ?: Instant.MIN }.thenBy { it.key })
}

private fun markChip(mark: Mark, weight: ResolvedMarkWeight?, locale: Locale): MarkChip? {
    if (mark.isPoints) return mark.maxPoints?.let { MarkChip.Points("${mark.markText}/$it") }
    val resolved = weight ?: return null
    if (resolved.value == 1.0) return null
    return when (resolved.source) {
        MarkWeightSource.EXPLICIT -> MarkChip.Weight(GradeMath.formattedWeight(resolved.value, locale), estimated = false)
        MarkWeightSource.INFERRED -> MarkChip.Weight(GradeMath.formattedWeight(resolved.value, locale), estimated = true)
        MarkWeightSource.FALLBACK -> null
    }
}

// MARK: - Average chart

/** One average sample: the running average at the end of [day] (epoch day). */
@Immutable
internal data class AverageSample(val day: Long, val value: Double)

/** One recorded mark drawn behind the average line. */
@Immutable
internal data class ChartMarkDot(val day: Long, val value: Double, val weight: Double, val band: GradeBand)

/** Everything the "Average over time" chart draws, independent of size (iOS `AverageHistoryChart`). */
@Immutable
internal class AverageChartData(
    val points: List<AverageSample>,
    val marks: List<ChartMarkDot>,
    val xStart: Double,
    val xEnd: Double,
    /** Top of the reversed Y axis (best grade visible). */
    val yBest: Double,
    /** Bottom of the reversed Y axis (worst grade visible). */
    val yWorst: Double,
    val yTicks: List<Double>,
    val xTicks: List<Long>,
    /** Change across the charted period; positive means worse. */
    val delta: Double?,
)

/**
 * iOS `AverageTimeline.entries` plus the chart domain: a running weighted average over the dated marks,
 * the Y axis padded by a quarter grade and reversed, the X axis padded by 4 % (±3 days for one day).
 */
internal fun averageChartData(prepared: PreparedGradeCalculation): AverageChartData? {
    val dated = prepared.marks.mapNotNull { mark -> mark.date?.let { mark to it.toEpochDay() } }
        .sortedWith(compareBy<Pair<com.bukovinafilip.gradey.domain.PreparedGradeMark, Long>> { it.second }.thenBy { it.first.id })
    if (dated.isEmpty()) return null
    var totalWeight = 0.0
    var weightedSum = 0.0
    val running = dated.map { (mark, day) ->
        totalWeight += mark.weight.value
        weightedSum += mark.value * mark.weight.value
        AverageSample(day, weightedSum / totalWeight)
    }
    val points = running.asReversed().distinctBy { it.day }.asReversed()
    val marks = dated.map { (mark, day) -> ChartMarkDot(day, mark.value, mark.weight.value, GradeMath.band(mark.value)) }
    val values = running.map { it.value } + marks.map { it.value }
    val best = max(values.min() - 0.25, 0.7)
    val worst = min(values.max() + 0.25, 5.9)
    val first = dated.first().second.toDouble()
    val last = dated.last().second.toDouble()
    val (xStart, xEnd) = if (first == last) {
        (first - 3.0) to (last + 3.0)
    } else {
        val padding = max((last - first) * 0.04, 1.0 / 24.0)
        (first - padding) to (last + padding)
    }
    return AverageChartData(
        points = points,
        marks = marks,
        xStart = xStart,
        xEnd = xEnd,
        yBest = best,
        yWorst = worst,
        yTicks = averageTicks(best, worst),
        xTicks = dateTicks(xStart, xEnd),
        delta = if (running.size > 1) running.last().value - running.first().value else null,
    )
}

/** Whole grades inside the range, or half grades when fewer than two whole ones fit (iOS `yTicks`). */
internal fun averageTicks(best: Double, worst: Double): List<Double> {
    val whole = generateSequence(ceil(best)) { it + 1.0 }.takeWhile { it <= worst + 1e-9 }.toList()
    if (whole.size >= 2) return whole
    return generateSequence(ceil(best * 2.0) / 2.0) { it + 0.5 }.takeWhile { it <= worst + 1e-9 }.toList()
}

/** Up to four calendar-aligned date ticks (days, weeks from Monday, then whole months). */
internal fun dateTicks(start: Double, end: Double, maximum: Int = 4): List<Long> {
    val first = ceil(start).toLong()
    val last = floor(end).toLong()
    if (last < first) return emptyList()
    for (step in longArrayOf(1, 2, 7, 14)) {
        val anchor = if (step >= 7) {
            LocalDate.ofEpochDay(first).with(TemporalAdjusters.nextOrSame(java.time.DayOfWeek.MONDAY)).toEpochDay()
        } else first
        val ticks = generateSequence(anchor) { it + step }.takeWhile { it <= last }.toList()
        if (ticks.size in 1..maximum) return ticks
    }
    for (months in intArrayOf(1, 2, 3, 6, 12)) {
        var month = LocalDate.ofEpochDay(first).withDayOfMonth(1)
        if (month.toEpochDay() < first) month = month.plusMonths(1)
        while ((month.monthValue - 1) % months != 0) month = month.plusMonths(1)
        val ticks = generateSequence(month) { it.plusMonths(months.toLong()) }
            .map { it.toEpochDay() }.takeWhile { it <= last }.toList()
        if (ticks.size in 1..maximum) return ticks
    }
    return listOf(first, last).distinct()
}

/** Index of the sample closest to [day] (iOS `selectedPoint`). */
internal fun nearestSample(points: List<AverageSample>, day: Double): Int {
    var best = -1
    var distance = Double.MAX_VALUE
    points.forEachIndexed { index, point ->
        val next = abs(point.day - day)
        if (next < distance) {
            distance = next
            best = index
        }
    }
    return best
}
