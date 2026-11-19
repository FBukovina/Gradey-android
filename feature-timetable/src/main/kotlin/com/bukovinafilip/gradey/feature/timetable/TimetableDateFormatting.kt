package com.bukovinafilip.gradey.feature.timetable

import android.content.res.Resources
import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Numeric day and month ("19. 10."), with the year when it isn't the current one (iOS
 * `SchoolDateFormatting.shortDate`). Like iOS it follows the device region rather than the app
 * language; Czech and Slovak regions use the native "d. M." pattern.
 */
@Immutable
internal class TimetableDateFormat(
    private val dayMonth: DateTimeFormatter,
    private val dayMonthYear: DateTimeFormatter,
    private val weekday: DateTimeFormatter,
    private val time: DateTimeFormatter,
    val locale: Locale,
) {
    fun shortDate(date: LocalDate, currentYear: Int): String =
        (if (date.year == currentYear) dayMonth else dayMonthYear).format(date)

    /** "5. 10. – 9. 10." (iOS `TimetableDates.weekRangeTitle`). */
    fun weekTitle(monday: LocalDate, currentYear: Int): String =
        shortDate(monday, currentYear) + " – " + shortDate(monday.plusDays(4), currentYear)

    /** Abbreviated weekday in the app language ("Mon", "po"). */
    fun weekday(date: LocalDate): String = weekday.format(date)

    /** Short time in the app language ("14:30", "2:30 PM"). */
    fun time(value: LocalTime): String = time.format(value)
}

@Composable
internal fun rememberTimetableDateFormat(): TimetableDateFormat {
    val appLocale = LocalConfiguration.current.locales[0]
    return remember(appLocale) {
        val region = Resources.getSystem().configuration.locales[0]
        val native = region.country == "CZ" || region.country == "SK"
        fun pattern(skeleton: String, nativePattern: String) =
            if (native) nativePattern else DateFormat.getBestDateTimePattern(region, skeleton)
        TimetableDateFormat(
            dayMonth = DateTimeFormatter.ofPattern(pattern("dM", "d. M."), region),
            dayMonthYear = DateTimeFormatter.ofPattern(pattern("dMy", "d. M. y"), region),
            weekday = DateTimeFormatter.ofPattern("EEE", appLocale),
            time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(appLocale),
            locale = appLocale,
        )
    }
}
