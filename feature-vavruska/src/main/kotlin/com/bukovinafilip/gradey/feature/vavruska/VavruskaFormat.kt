package com.bukovinafilip.gradey.feature.vavruska

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.bukovinafilip.gradey.domain.VavruskaEvent
import com.bukovinafilip.gradey.domain.VavruskaEventKind
import com.bukovinafilip.gradey.domain.VavruskaTicketSale
import com.bukovinafilip.gradey.ui.HugeIcons
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Days and times of dance lessons in the app's language and the device's time zone (iOS
 * `VavruskaFormatting`). The patterns come from the system, so a Czech phone shows "ne 18. 10."
 * and "16:30" where an American one shows "Sun 10/18" and "4:30 PM".
 */
@Immutable
class VavruskaFormat(dayPattern: String, dayWithYearPattern: String, timePattern: String, private val locale: Locale, private val zone: ZoneId) {
    private val day = DateTimeFormatter.ofPattern(dayPattern, locale)
    private val dayWithYear = DateTimeFormatter.ofPattern(dayWithYearPattern, locale)
    private val time = DateTimeFormatter.ofPattern(timePattern, locale)
    private val weekday = DateTimeFormatter.ofPattern("EEE", locale)
    private val month = DateTimeFormatter.ofPattern("LLL", locale)

    private fun at(epochMillis: Long): ZonedDateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)

    fun time(epochMillis: Long): String = at(epochMillis).format(time)

    /** "16:30–18:10", or just the start for an event without an end. */
    fun timeRange(event: VavruskaEvent): String =
        if (event.endEpochMillis > event.startEpochMillis) time(event.startEpochMillis) + "–" + time(event.endEpochMillis) else time(event.startEpochMillis)

    /** "ne 18. 10." */
    fun weekdayShortDate(epochMillis: Long): String = at(epochMillis).let { it.format(weekday).trimEnd('.') + " " + it.format(day) }

    /** "ne 18. 10. · 16:30–18:10 · Obecní dům" */
    fun dayLine(event: VavruskaEvent): String = listOfNotNull(weekdayShortDate(event.startEpochMillis), timeRange(event), event.venue).joinToString(" · ")

    /** The day as Today says it: [today] or [tomorrow] in words, otherwise the weekday and date. */
    fun relativeDayLine(event: VavruskaEvent, now: Long, today: String, tomorrow: String): String {
        val date = at(event.startEpochMillis).toLocalDate()
        val current = at(now).toLocalDate()
        val day = when (date) {
            current -> today
            current.plusDays(1) -> tomorrow
            else -> weekdayShortDate(event.startEpochMillis)
        }
        return listOfNotNull(day, timeRange(event), event.venue).joinToString(" · ")
    }

    /** "ne 18. 10. · 16:00 · Obecní dům", the event a ticket sale is for. */
    fun saleEventLine(sale: VavruskaTicketSale): String =
        listOfNotNull(weekdayShortDate(sale.eventStartEpochMillis), time(sale.eventStartEpochMillis), sale.venue).joinToString(" · ")

    /** The day of the month and the abbreviated month for an event's date tile. */
    fun tile(epochMillis: Long): Pair<String, String> = at(epochMillis).let { it.dayOfMonth.toString() to it.format(month).trimEnd('.').uppercase(locale) }

    /** "6. 10. 2026", the day the notice board was last changed. */
    fun date(isoDay: String): String? = runCatching { LocalDate.parse(isoDay).format(dayWithYear) }.getOrNull()
}

@Composable
fun rememberVavruskaFormat(): VavruskaFormat {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, is24Hour) {
        VavruskaFormat(
            dayPattern = DateFormat.getBestDateTimePattern(locale, "Md"),
            dayWithYearPattern = DateFormat.getBestDateTimePattern(locale, "yMd"),
            timePattern = DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm"),
            locale = locale, zone = ZoneId.systemDefault(),
        )
    }
}

internal val VavruskaEventKind.icon: String
    get() = when (this) {
        VavruskaEventKind.LESSON -> HugeIcons.MusicNote03
        VavruskaEventKind.SPECIAL_LESSON -> HugeIcons.MusicNote01
        VavruskaEventKind.EXTENDED_LESSON -> HugeIcons.Party
        VavruskaEventKind.BALL -> HugeIcons.Flower
    }
