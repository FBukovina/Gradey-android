package com.bukovinafilip.gradey.feature.absence

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import com.bukovinafilip.gradey.domain.AbsenceDaySummary
import com.bukovinafilip.gradey.domain.AbsenceMonthSummary
import com.bukovinafilip.gradey.domain.AbsenceOverrideProjection
import com.bukovinafilip.gradey.domain.AbsenceTimeline
import com.bukovinafilip.gradey.model.AbsenceCounts
import com.bukovinafilip.gradey.model.AbsenceOverrideCategory
import com.bukovinafilip.gradey.model.AbsenceOverridePauseReason
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.PlannedAbsence
import com.bukovinafilip.gradey.model.TimetableWeek
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.gradeyColors
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** The school's time zone; "today" and past plans follow it, like the domain planner. */
internal val SchoolZone: ZoneId = ZoneId.of("Europe/Prague")

/** Space between the parts of the absence screens (iOS `LazyVStack(spacing: Spacing.md)`). */
internal val AbsenceItemSpacing = GradeySpacing.md

/**
 * For a `PlainRow` that is one lazy item of a list. The screens keep [AbsenceItemSpacing] between
 * their parts, but on iOS the rows of one list touch with only a hairline between them
 * (`LazyVStack(spacing: 0)`). The row reports itself that much shorter, so its own bottom padding
 * fills the gap to the next item; it still draws and takes touches at its full height.
 */
internal fun Modifier.joinedListRow(): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val overlap = AbsenceItemSpacing.roundToPx().coerceAtMost(placeable.height)
    layout(placeable.width, placeable.height - overlap) { placeable.place(0, 0) }
}

/**
 * iOS `AbsenceCategory`: the order, the one-letter symbol on chips and the label of every
 * attendance category. Colours come from [AbsencePalette].
 */
internal enum class AbsenceCategory(val symbol: String, val label: Int, val shortLabel: Int) {
    UNSOLVED("?", R.string.absence_category_unsolved, R.string.absence_category_short_unsolved),
    OK("✓", R.string.absence_category_ok, R.string.absence_category_short_ok),
    MISSED("N", R.string.absence_category_missed, R.string.absence_category_short_missed),
    LATE("P", R.string.absence_category_late, R.string.absence_category_short_late),
    SOON("O", R.string.absence_category_soon, R.string.absence_category_short_soon),
    SCHOOL("–", R.string.absence_category_school, R.string.absence_category_short_school),
    DISTANCE_TEACHING("D", R.string.absence_category_distance_teaching, R.string.absence_category_short_distance_teaching);

    fun value(counts: AbsenceCounts): Int = when (this) {
        UNSOLVED -> counts.unsolved
        OK -> counts.ok
        MISSED -> counts.missed
        LATE -> counts.late
        SOON -> counts.soon
        SCHOOL -> counts.school
        DISTANCE_TEACHING -> counts.distanceTeaching
    }

    companion object {
        fun nonZero(counts: AbsenceCounts): List<AbsenceCategory> = entries.filter { it.value(counts) > 0 }

        fun of(category: AbsenceOverrideCategory): AbsenceCategory = when (category) {
            AbsenceOverrideCategory.UNSOLVED -> UNSOLVED
            AbsenceOverrideCategory.OK -> OK
            AbsenceOverrideCategory.MISSED -> MISSED
            AbsenceOverrideCategory.LATE -> LATE
            AbsenceOverrideCategory.SOON -> SOON
            AbsenceOverrideCategory.SCHOOL -> SCHOOL
            AbsenceOverrideCategory.DISTANCE_TEACHING -> DISTANCE_TEACHING
        }
    }
}

/**
 * Category colours (iOS `AbsenceCategory.color`): unresolved Brand.primary, excused, school events
 * and remote learning Brand.secondary, unexcused poor red, late average amber, left early orange.
 * Built once per theme by [rememberAbsencePalette].
 */
@Immutable
internal class AbsencePalette(private val colors: List<Color>) {
    fun color(category: AbsenceCategory): Color = colors[category.ordinal]
}

@Composable
internal fun rememberAbsencePalette(): AbsencePalette {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary
    val orange = MaterialTheme.gradeyColors.orange
    return remember(primary, secondary, orange) {
        AbsencePalette(
            AbsenceCategory.entries.map { category ->
                when (category) {
                    AbsenceCategory.UNSOLVED -> primary
                    AbsenceCategory.OK, AbsenceCategory.SCHOOL, AbsenceCategory.DISTANCE_TEACHING -> secondary
                    AbsenceCategory.MISSED -> GradeyColors.Poor
                    AbsenceCategory.LATE -> GradeyColors.Average
                    AbsenceCategory.SOON -> orange
                }
            },
        )
    }
}

/** `String(format: "%.1f", …)` on iOS: always one decimal and a dot, whatever the language. */
private val OneDecimal = DecimalFormat("0.0", DecimalFormatSymbols.getInstance(Locale.ROOT)).apply {
    roundingMode = RoundingMode.HALF_UP
}

/** "12.5" for subject percentages (iOS `%.1f`). */
internal fun formatOneDecimal(value: Double): String = synchronized(OneDecimal) { OneDecimal.format(value) }

/**
 * Date and number texts of the absence screens, built once per locale (iOS `SchoolDateFormatting`).
 * Month and weekday names come from the stand-alone forms, so Czech reads "listopad 2026".
 */
@Immutable
internal class AbsenceFormatters(locale: Locale, private val today: LocalDate) {
    private val weekdaysShort = DayOfWeek.entries.map { it.getDisplayName(TextStyle.SHORT_STANDALONE, locale).ifDigits { it.getDisplayName(TextStyle.SHORT, locale) } }
    private val weekdaysNarrow = DayOfWeek.entries.map { it.getDisplayName(TextStyle.NARROW_STANDALONE, locale).ifDigits { it.getDisplayName(TextStyle.NARROW, locale) } }
    private val weekdaysLong = DayOfWeek.entries.map { it.getDisplayName(TextStyle.FULL_STANDALONE, locale).ifDigits { it.getDisplayName(TextStyle.FULL, locale) } }
    private val monthsLong = Month.entries.map { it.getDisplayName(TextStyle.FULL_STANDALONE, locale).ifDigits { it.getDisplayName(TextStyle.FULL, locale) } }
    private val monthsNarrow = Month.entries.map { month ->
        month.getDisplayName(TextStyle.NARROW_STANDALONE, locale).ifDigits { month.getDisplayName(TextStyle.NARROW, locale) }
    }

    /** `String.localizedStringWithFormat("%.1f", …)`: one decimal with the language's separator. */
    private val localizedOneDecimal = DecimalFormat("0.0", DecimalFormatSymbols.getInstance(locale)).apply {
        roundingMode = RoundingMode.HALF_UP
    }

    /** "31. 5.", with the year when it is not the current one. */
    fun shortDate(date: LocalDate): String =
        if (date.year == today.year) "${date.dayOfMonth}. ${date.monthValue}." else "${date.dayOfMonth}. ${date.monthValue}. ${date.year}"

    /** "Tue 10. 2." (iOS `weekdayShortDate`). */
    fun dayTitle(date: LocalDate): String = "${weekdaysShort[date.dayOfWeek.ordinal]} ${shortDate(date)}"

    /** [dayTitle] for a stored `yyyy-MM-dd` key; the key itself when it cannot be read. */
    fun dayTitle(dateKey: String): String = parseDate(dateKey)?.let(::dayTitle) ?: dateKey

    /** "Monday 10. 2." for the selected planner day. */
    fun longDayTitle(date: LocalDate): String = "${weekdaysLong[date.dayOfWeek.ordinal]} ${shortDate(date)}".replaceFirstChar { it.uppercaseChar() }

    /** "Mon 10" for a planner day segment; "M 10" when the week has more than five days. */
    fun segmentTitle(date: LocalDate, compact: Boolean): String =
        "${(if (compact) weekdaysNarrow else weekdaysShort)[date.dayOfWeek.ordinal]} ${date.dayOfMonth}"

    /** "10. 11. – 14. 11." for the planner week. */
    fun range(first: LocalDate, last: LocalDate): String = "${shortDate(first)} – ${shortDate(last)}"

    /** "November 2026" (iOS `.month(.wide).year()`). */
    fun monthTitle(month: YearMonth): String = "${monthsLong[month.monthValue - 1]} ${month.year}"

    /** "November" for the chart's spoken summary. */
    fun monthName(month: YearMonth): String = monthsLong[month.monthValue - 1]

    /** "N" under a chart bar (iOS `.month(.narrow)`). */
    fun narrowMonth(month: YearMonth): String = monthsNarrow[month.monthValue - 1]

    /** "4,5" in Czech, "4.5" in English. */
    fun oneDecimal(value: Double): String = synchronized(localizedOneDecimal) { localizedOneDecimal.format(value) }

    private inline fun String.ifDigits(fallback: () -> String): String = if (isEmpty() || all(Char::isDigit)) fallback() else this
}

internal fun parseDate(value: String?): LocalDate? =
    value?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }

/** One row of the Days or Months list, prepared before composition. */
@Immutable
internal data class AbsenceCountRowModel(
    val key: String,
    val title: String,
    val counts: AbsenceCounts,
    /** `yyyy-MM-dd` of a day row; null for months. */
    val dateKey: String?,
    val categories: List<AbsenceCategory> = AbsenceCategory.nonZero(counts),
)

/** A saved local correction as the corrections section lists it (iOS `HiddenAbsencesView.dayRow`). */
@Immutable
internal data class SavedCorrectionModel(
    val key: String,
    val dateKey: String,
    val title: String,
    val pauseReason: AbsenceOverridePauseReason?,
    val hidden: List<HiddenUnitModel>,
    val editable: Boolean,
)

/** One hidden attendance unit: its subject and category. */
@Immutable
internal data class HiddenUnitModel(val key: String, val subjectName: String, val category: AbsenceCategory)

/**
 * "Unresolved 6 · Excused 15 · Late 1 · Limit 25 %" (iOS `AbsenceHeader.summary`). Each item keeps
 * its words together, so lines only break between items.
 */
internal fun absenceSummaryLine(counts: AbsenceCounts, shortLabels: List<String>, thresholdCaption: String?): String {
    val parts = AbsenceCategory.entries.mapNotNull { category ->
        val value = category.value(counts)
        if (value > 0) "${shortLabels[category.ordinal]} $value" else null
    } + listOfNotNull(thresholdCaption)
    return parts.joinToString(" · ") { it.replace(' ', ' ') }
}

/**
 * Every day the school recorded, with its counts after local corrections. A day hidden entirely
 * stays in the list with zero hours so it can still be opened and put back (iOS `displayedDays`).
 */
internal fun displayedAbsenceDays(response: AbsenceResponse, rawResponse: AbsenceResponse): List<AbsenceDaySummary> {
    if (rawResponse === response) return AbsenceTimeline.make(response).days
    val present = response.absences.mapTo(HashSet()) { AbsenceOverrideProjection.dateKey(it.date) }
    val hidden = rawResponse.absences
        .filter { AbsenceOverrideProjection.dateKey(it.date) !in present }
        .distinctBy { AbsenceOverrideProjection.dateKey(it.date) }
        .map { it.copy(unsolved = 0, ok = 0, missed = 0, late = 0, soon = 0, school = 0, distanceTeaching = 0) }
    return AbsenceTimeline.make(response.copy(absences = response.absences + hidden)).days
}

/**
 * Every month from the first to the last recorded one, empty months included, in time order: the
 * chart's x axis strides by month (Swift Charts `AxisMarks(values: .stride(by: .month))`).
 */
internal fun continuousMonths(months: List<AbsenceMonthSummary>): List<AbsenceMonthSummary> {
    if (months.isEmpty()) return months
    val byMonth = months.associateBy { it.month }
    val last = months.maxOf { it.month }
    return generateSequence(months.minOf { it.month }) { it.plusMonths(1) }
        .takeWhile { !it.isAfter(last) }
        .map { byMonth[it] ?: AbsenceMonthSummary(it, AbsenceCounts()) }
        .toList()
}

/**
 * Y-axis values of the months chart, about [desiredCount] steps of 1, 2 or 5 × 10ⁿ from zero to
 * the first step at or above [maxValue] (Swift Charts `.automatic(desiredCount: 3)`).
 */
internal fun absenceAxisTicks(maxValue: Int, desiredCount: Int = 3): List<Int> {
    if (maxValue <= 0) return listOf(0, 1)
    val rough = maxValue.toDouble() / desiredCount
    val magnitude = 10.0.pow(floor(log10(rough)))
    val nice = when (rough / magnitude) {
        in 0.0..1.0 -> 1.0
        in 1.0..2.0 -> 2.0
        in 2.0..5.0 -> 5.0
        else -> 10.0
    }
    val step = max(1, (nice * magnitude).roundToInt())
    val top = ceil(maxValue.toDouble() / step).toInt() * step
    return (0..top step step).toList()
}

/** Monday to Friday, plus a weekend day when it has lessons or a saved plan. */
internal fun plannerDays(weekStart: LocalDate, timetable: TimetableWeek?, plans: List<PlannedAbsence>): List<LocalDate> {
    val lessonDates = timetable?.days.orEmpty().filter { it.lessons.isNotEmpty() }.mapNotNullTo(HashSet()) { it.date?.take(10) }
    val planDates = plans.mapTo(HashSet()) { it.date }
    return (0L..6L).map(weekStart::plusDays).filter { date ->
        date.dayOfWeek <= DayOfWeek.FRIDAY || date.toString() in lessonDates || date.toString() in planDates
    }
}

/** The first day that has not passed yet, else the last day of the week. */
internal fun initialPlannerDate(days: List<LocalDate>, today: LocalDate): LocalDate =
    days.firstOrNull { !it.isBefore(today) } ?: days.lastOrNull() ?: today

/** Lazy-list keys that stay unique when the data repeats an id. */
internal fun uniqueKeys(ids: List<String>): List<String> {
    val used = HashSet<String>(ids.size * 2)
    return ids.map { id ->
        var key = id
        var suffix = 1
        while (!used.add(key)) key = "$id#${suffix++}"
        key
    }
}
