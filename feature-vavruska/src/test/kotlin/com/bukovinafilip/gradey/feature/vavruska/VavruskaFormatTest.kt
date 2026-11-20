package com.bukovinafilip.gradey.feature.vavruska

import com.bukovinafilip.gradey.domain.VavruskaEvent
import com.bukovinafilip.gradey.domain.VavruskaEventKind
import com.bukovinafilip.gradey.domain.VavruskaTicketSale
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class VavruskaFormatTest {
    private val prague = ZoneId.of("Europe/Prague")
    private val czech = VavruskaFormat("d. M.", "d. M. y", "H:mm", Locale.forLanguageTag("cs"), prague)
    private val american = VavruskaFormat("M/d", "M/d/y", "h:mm a", Locale.US, prague)
    private fun millis(text: String) = Instant.parse(text).toEpochMilli()
    private val lesson = VavruskaEvent(
        "1", "Vyučovací lekce", VavruskaEventKind.LESSON, venue = "Obecní dům", address = "náměstí Republiky 5",
        startEpochMillis = millis("2026-10-18T14:30:00Z"), endEpochMillis = millis("2026-10-18T16:10:00Z"),
    )

    @Test fun aLessonIsDescribedByItsDayTimeAndHall() {
        assertThat(czech.dayLine(lesson)).isEqualTo("ne 18. 10. · 16:30–18:10 · Obecní dům")
        assertThat(american.dayLine(lesson)).isEqualTo("Sun 10/18 · 4:30 PM–6:10 PM · Obecní dům")
        // No hall and no end: only what is known.
        assertThat(czech.dayLine(lesson.copy(venue = null, endEpochMillis = lesson.startEpochMillis))).isEqualTo("ne 18. 10. · 16:30")
        assertThat(czech.tile(lesson.startEpochMillis)).isEqualTo("18" to "ŘÍJ")
        assertThat(american.tile(lesson.startEpochMillis)).isEqualTo("18" to "OCT")
        assertThat(czech.date("2026-10-06")).isEqualTo("6. 10. 2026")
        assertThat(american.date("2026-10-06")).isEqualTo("10/6/2026")
        assertThat(czech.date("not a day")).isNull()
    }

    @Test fun todaySaysTodayAndTomorrowInWords() {
        assertThat(czech.relativeDayLine(lesson, millis("2026-10-18T06:00:00Z"), "Dnes", "Zítra")).isEqualTo("Dnes · 16:30–18:10 · Obecní dům")
        assertThat(czech.relativeDayLine(lesson, millis("2026-10-17T20:00:00Z"), "Dnes", "Zítra")).isEqualTo("Zítra · 16:30–18:10 · Obecní dům")
        assertThat(czech.relativeDayLine(lesson, millis("2026-10-12T06:00:00Z"), "Dnes", "Zítra")).isEqualTo("ne 18. 10. · 16:30–18:10 · Obecní dům")
        // The day is the device's: late on Saturday evening in Prague it is already Sunday in Tokyo.
        val tokyo = VavruskaFormat("d. M.", "d. M. y", "H:mm", Locale.forLanguageTag("cs"), ZoneId.of("Asia/Tokyo"))
        assertThat(tokyo.relativeDayLine(lesson, millis("2026-10-17T20:00:00Z"), "Dnes", "Zítra")).startsWith("Dnes · 23:30")
    }

    @Test fun aTicketSaleNamesItsEventsDayAndStart() {
        val sale = VavruskaTicketSale("20261018-1600", "Prodloužená lekce", millis("2026-10-18T14:00:00Z"), venue = "Obecní dům", saleStartEpochMillis = millis("2026-10-12T16:00:00Z"))
        assertThat(czech.saleEventLine(sale)).isEqualTo("ne 18. 10. · 16:00 · Obecní dům")
        assertThat(czech.saleEventLine(sale.copy(venue = null))).isEqualTo("ne 18. 10. · 16:00")
        assertThat(czech.weekdayShortDate(sale.saleStartEpochMillis) + " " + czech.time(sale.saleStartEpochMillis)).isEqualTo("po 12. 10. 18:00")
    }
}
