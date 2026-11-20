package com.bukovinafilip.gradey.feature.komens

import android.text.format.DateFormat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * When a message was sent, in the device's time zone and the app's language (iOS `KomensDates`).
 * The patterns come from the system, so a Czech phone shows "19. 11." and an American one "11/19".
 */
@Immutable
internal class KomensDateFormat(dayPattern: String, dayWithYearPattern: String, timePattern: String, locale: Locale) {
    private val day = DateTimeFormatter.ofPattern(dayPattern, locale)
    private val dayWithYear = DateTimeFormatter.ofPattern(dayWithYearPattern, locale)
    private val time = DateTimeFormatter.ofPattern(timePattern, locale)
    private val weekday = DateTimeFormatter.ofPattern("EEE", locale)

    /** Today's messages show the time, older ones the day; the year only when it is not this one. */
    fun short(sentAt: Instant, now: Instant, zone: ZoneId): String {
        val sent = sentAt.atZone(zone)
        val today = now.atZone(zone).toLocalDate()
        return when {
            sent.toLocalDate() == today -> sent.format(time)
            sent.year == today.year -> sent.format(day)
            else -> sent.format(dayWithYear)
        }
    }

    /** "Thu 19. 11. 8:15", for the line under a message's heading. */
    fun full(sentAt: Instant, now: Instant, zone: ZoneId): String {
        val sent = sentAt.atZone(zone)
        val date = sent.format(if (sent.year == now.atZone(zone).year) day else dayWithYear)
        return sent.format(weekday).trimEnd('.') + " " + date + " " + sent.format(time)
    }
}

@Composable
internal fun rememberKomensDateFormat(): KomensDateFormat {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    return remember(locale, is24Hour) {
        KomensDateFormat(
            dayPattern = DateFormat.getBestDateTimePattern(locale, "Md"),
            dayWithYearPattern = DateFormat.getBestDateTimePattern(locale, "yMd"),
            timePattern = DateFormat.getBestDateTimePattern(locale, if (is24Hour) "Hm" else "hm"),
            locale = locale,
        )
    }
}
