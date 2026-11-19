package com.bukovinafilip.gradey.feature.recap

import android.content.res.Resources
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import com.bukovinafilip.gradey.domain.GradeBand
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.domain.Recap
import com.bukovinafilip.gradey.domain.RecapComparison
import com.bukovinafilip.gradey.domain.RecapKind
import com.bukovinafilip.gradey.domain.RecapPeriod
import com.bukovinafilip.gradey.domain.RecapPersonality
import com.bukovinafilip.gradey.domain.RecapSlide
import com.bukovinafilip.gradey.domain.ReportCardPeriod
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.RecapColors
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** A recap's own colour: its tile in the list and its first slide. Never sky, which is the slide that follows the first one. */
internal fun RecapPeriod.tileColor(): Color = when (kind) {
    RecapKind.MONTH -> listOf(RecapColors.Teal, RecapColors.Lime, RecapColors.Rose, RecapColors.Amber, RecapColors.Lilac)[number % 5]
    RecapKind.QUARTER -> RecapColors.Emerald
    RecapKind.SEMESTER -> RecapColors.Lilac
    RecapKind.CALENDAR_YEAR -> RecapColors.Amber
}

internal fun RecapSlide.backgroundColor(recap: Recap): Color = when (this) {
    RecapSlide.INTRO -> recap.period.tileColor()
    RecapSlide.REPORT_CARD -> RecapColors.Emerald
    RecapSlide.MARKS, RecapSlide.SUMMARY, RecapSlide.LAST_YEAR -> RecapColors.Sky
    RecapSlide.AVERAGE -> when (GradeMath.band(recap.average)) {
        GradeBand.EXCELLENT -> RecapColors.Lime
        GradeBand.GOOD, GradeBand.NEUTRAL -> RecapColors.Teal
        GradeBand.AVERAGE -> RecapColors.Amber
        GradeBand.POOR -> RecapColors.Rose
    }
    RecapSlide.TOP_SUBJECT, RecapSlide.PERSONALITY -> RecapColors.Lilac
    RecapSlide.TOUGHEST_SUBJECT -> RecapColors.Amber
    RecapSlide.MOMENTS -> RecapColors.Teal
    RecapSlide.ABSENCE -> RecapColors.Rose
    RecapSlide.TIMETABLE -> RecapColors.Lime
}

internal fun RecapPersonality.icon(): String = when (this) {
    RecapPersonality.StraightAs -> HugeIcons.Crown
    RecapPersonality.Comeback -> HugeIcons.Rocket01
    RecapPersonality.Rollercoaster -> HugeIcons.RollerCoaster
    RecapPersonality.LastMinute -> HugeIcons.Clock01
    is RecapPersonality.Specialty -> HugeIcons.Target02
    RecapPersonality.Steady -> HugeIcons.Mountain
}

/** Every Recap text in the app's language (iOS `RecapText`). Built once per configuration. */
internal class RecapTexts(private val resources: Resources) {
    val locale: Locale = resources.configuration.locales[0]

    /** The lowercase "chronically online" variants are the en-CO and cs-US resource folders. */
    private val isChronicallyOnline = (locale.language == "en" && locale.country == "CO") || (locale.language == "cs" && locale.country == "US")
    private val integers: NumberFormat = NumberFormat.getIntegerInstance(locale)
    private val dayFormat: DateTimeFormatter = DateTimeFormatter.ofPattern(if (locale.language == "cs") "EEE d. M." else "EEE, MMM d", locale)

    fun string(id: Int, vararg arguments: Any): String = resources.getString(id, *arguments)

    fun integer(value: Int): String = integers.format(value)

    fun average(value: Double?): String = GradeMath.formattedAverage(value, locale)

    /** "October" on its own, the way a title or a list names the month. */
    private fun monthName(period: RecapPeriod): String {
        val month = period.start.month
        return month.getDisplayName(TextStyle.FULL_STANDALONE, locale).takeUnless { name -> name.isEmpty() || name.all(Char::isDigit) }
            ?: month.getDisplayName(TextStyle.FULL, locale)
    }

    private fun cased(text: String): String = if (isChronicallyOnline) text.lowercase(locale) else text.replaceFirstChar { it.titlecase(locale) }

    fun schoolYear(startYear: Int): String = ReportCardPeriod(startYear, 1).schoolYear

    /** "October 2026", "Q1 2026/27", "Semester 1, 2026/27", "Year 2026". */
    fun title(period: RecapPeriod): String = when (period.kind) {
        RecapKind.MONTH -> cased("${monthName(period)} ${period.year}")
        RecapKind.QUARTER -> string(if (period.number == 1) R.string.recap_period_quarter_1 else R.string.recap_period_quarter_3, schoolYear(period.year))
        RecapKind.SEMESTER -> string(R.string.recap_term, period.number.toString(), schoolYear(period.year))
        RecapKind.CALENDAR_YEAR -> string(R.string.recap_period_year, period.year.toString())
    }

    /** "October", "Q1", "Semester 1", "Year 2026": the title without its school year, for the list that is grouped by it. */
    fun shortTitle(period: RecapPeriod): String = when (period.kind) {
        RecapKind.MONTH -> cased(monthName(period))
        RecapKind.QUARTER, RecapKind.SEMESTER -> keepingOrdinal(title(period).replace(schoolYear(period.year), "").trim().trimEnd(',').trim())
        RecapKind.CALENDAR_YEAR -> title(period)
    }

    /** "Your October", "Your first quarter", "Your 2026". */
    fun headline(period: RecapPeriod): String = when (period.kind) {
        RecapKind.MONTH -> string(R.string.recap_intro_month, monthName(period))
        RecapKind.QUARTER -> keepingOrdinal(string(if (period.number == 1) R.string.recap_intro_quarter_1 else R.string.recap_intro_quarter_3))
        RecapKind.SEMESTER -> keepingOrdinal(string(if (period.number == 1) R.string.recap_intro_semester_1 else R.string.recap_intro_semester_2))
        RecapKind.CALENDAR_YEAR -> string(R.string.recap_intro_year, period.year.toString())
    }

    /** "1. pololetí" never breaks after the "1.". */
    private fun keepingOrdinal(text: String): String = text.replace(". ", ".\u00A0")

    fun plural(id: Int, count: Int): String = resources.getQuantityString(id, count, count)

    /** The word under a big number: "marks" for 47. Plural strings contain the number, so it is taken out again. */
    fun noun(id: Int, count: Int): String = plural(id, count).replaceFirst(count.toString(), "").trim()

    /** "23 marks". */
    fun marks(count: Int): String = plural(R.plurals.recap_marks_count, count)

    /** "22 h". */
    fun hours(value: Int): String = string(R.string.recap_hours, integer(value))

    /** "23 marks · average 1.62", for tiles and list rows. */
    fun summary(recap: Recap): String = buildList {
        if (recap.markCount > 0) add(marks(recap.markCount))
        recap.average?.let { add(string(R.string.recap_row_average, average(it))) }
        val absence = recap.absence
        if (recap.markCount == 0 && absence != null) add(hours(absence.counts.total))
        if (recap.isOngoing) add(string(R.string.recap_ongoing))
    }.joinToString(" · ")

    fun personalityTitle(personality: RecapPersonality): String = when (personality) {
        RecapPersonality.StraightAs -> string(R.string.recap_personality_straight_as_title)
        RecapPersonality.Comeback -> string(R.string.recap_personality_comeback_title)
        RecapPersonality.Rollercoaster -> string(R.string.recap_personality_rollercoaster_title)
        RecapPersonality.LastMinute -> string(R.string.recap_personality_last_minute_title)
        is RecapPersonality.Specialty -> string(R.string.recap_personality_specialty_title, personality.subject)
        RecapPersonality.Steady -> string(R.string.recap_personality_steady_title)
    }

    fun personalityDetail(personality: RecapPersonality): String = string(
        when (personality) {
            RecapPersonality.StraightAs -> R.string.recap_personality_straight_as_detail
            RecapPersonality.Comeback -> R.string.recap_personality_comeback_detail
            RecapPersonality.Rollercoaster -> R.string.recap_personality_rollercoaster_detail
            RecapPersonality.LastMinute -> R.string.recap_personality_last_minute_detail
            is RecapPersonality.Specialty -> R.string.recap_personality_specialty_detail
            RecapPersonality.Steady -> R.string.recap_personality_steady_detail
        },
    )

    fun bandLine(average: Double): String = string(
        when (GradeMath.band(average)) {
            GradeBand.EXCELLENT -> R.string.recap_average_band_excellent
            GradeBand.GOOD, GradeBand.NEUTRAL -> R.string.recap_average_band_good
            GradeBand.AVERAGE -> R.string.recap_average_band_average
            GradeBand.POOR -> R.string.recap_average_band_poor
        },
    )

    /** "Last month 1.74 · 0.12 better". */
    fun comparison(comparison: RecapComparison, average: Double): String {
        val basis = if (comparison.basis == RecapComparison.Basis.PREVIOUS_MONTH) R.string.recap_average_previous_month else R.string.recap_average_previous_report_card
        val line = string(basis, average(comparison.average))
        val difference = comparison.average - average
        if (abs(difference) < 0.005) return line
        return line + " · " + string(if (difference > 0) R.string.recap_average_delta_better else R.string.recap_average_delta_worse, average(abs(difference)))
    }

    /** "Tue, Oct 20". */
    fun weekdayShortDate(date: LocalDate): String = date.format(dayFormat)

    fun weight(value: Double): String = GradeMath.formattedWeight(value, locale)
}

@Composable
internal fun rememberRecapTexts(): RecapTexts {
    val resources = LocalResources.current
    val configuration = LocalConfiguration.current
    return remember(resources, configuration) { RecapTexts(resources) }
}
