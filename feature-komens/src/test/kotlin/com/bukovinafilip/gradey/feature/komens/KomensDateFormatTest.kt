package com.bukovinafilip.gradey.feature.komens

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

class KomensDateFormatTest {
    private val prague = ZoneId.of("Europe/Prague")
    private val now = Instant.parse("2026-11-20T14:00:00Z")
    private val czech = KomensDateFormat("d. M.", "d. M. y", "H:mm", Locale.forLanguageTag("cs"))
    private val american = KomensDateFormat("M/d", "M/d/y", "h:mm a", Locale.US)

    @Test fun todaysMessagesShowTheTimeAndOlderOnesTheDay() {
        assertThat(czech.short(Instant.parse("2026-11-20T07:15:00Z"), now, prague)).isEqualTo("8:15")
        assertThat(czech.short(Instant.parse("2026-11-18T07:15:00Z"), now, prague)).isEqualTo("18. 11.")
        // Another year names it.
        assertThat(czech.short(Instant.parse("2025-12-31T07:15:00Z"), now, prague)).isEqualTo("31. 12. 2025")
        assertThat(american.short(Instant.parse("2026-11-20T07:15:00Z"), now, prague)).isEqualTo("8:15 AM")
        assertThat(american.short(Instant.parse("2026-11-18T07:15:00Z"), now, prague)).isEqualTo("11/18")
    }

    @Test fun whatCountsAsTodayFollowsTheDevicesTimeZone() {
        // 23:30 in Prague on the 19th is already the 20th in Tokyo, where "now" is the 20th as well.
        val late = Instant.parse("2026-11-19T22:30:00Z")
        assertThat(czech.short(late, now, prague)).isEqualTo("19. 11.")
        assertThat(czech.short(late, now, ZoneId.of("Asia/Tokyo"))).isEqualTo("7:30")
    }

    @Test fun theBylineNamesTheWeekdayTheDayAndTheTime() {
        assertThat(czech.full(Instant.parse("2026-11-19T07:15:00Z"), now, prague)).isEqualTo("čt 19. 11. 8:15")
        assertThat(american.full(Instant.parse("2026-11-19T07:15:00Z"), now, prague)).isEqualTo("Thu 11/19 8:15 AM")
        assertThat(czech.full(Instant.parse("2025-06-02T12:00:00Z"), now, prague)).isEqualTo("po 2. 6. 2025 14:00")
    }
}
