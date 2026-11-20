package com.bukovinafilip.gradey.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** vavruska.info, one page at a time (iOS `VavruskaClient`). */
interface VavruskaClient {
    /** Signs in with the course card password and returns the course with its private lesson plan link. */
    suspend fun login(password: String): VavruskaStoredCourse
    suspend fun fetchCalendar(url: String): List<VavruskaEvent>
    suspend fun fetchNoticeBoard(): VavruskaNoticeBoard
    suspend fun fetchTicketSales(path: String): List<VavruskaRawTicketSale>
    suspend fun fetchTicketLinks(): List<VavruskaTicketLink>
}

/** The connected course. Its ICS link works without a login, so it is a secret and storage is encrypted. */
interface VavruskaCourseStore {
    suspend fun load(): VavruskaStoredCourse?
    suspend fun save(course: VavruskaStoredCourse)
    suspend fun clear()
}

interface VavruskaCache {
    suspend fun load(): VavruskaSnapshot?
    suspend fun save(snapshot: VavruskaSnapshot)
    suspend fun clear()
}

class InMemoryVavruskaCourseStore(private var course: VavruskaStoredCourse? = null) : VavruskaCourseStore {
    override suspend fun load(): VavruskaStoredCourse? = course
    override suspend fun save(course: VavruskaStoredCourse) { this.course = course }
    override suspend fun clear() { course = null }
}

class InMemoryVavruskaCache(private var snapshot: VavruskaSnapshot? = null) : VavruskaCache {
    override suspend fun load(): VavruskaSnapshot? = snapshot
    override suspend fun save(snapshot: VavruskaSnapshot) { this.snapshot = snapshot }
    override suspend fun clear() { snapshot = null }
}

/** @param failed something could not be fetched this time; the saved copy is still shown. */
class VavruskaRefreshResult(val snapshot: VavruskaSnapshot, val failed: Boolean)

/**
 * Fetches politely (iOS `VavruskaRepository`): one request at a time, each source on its own
 * schedule, so a busy user opening the app all day costs the site a handful of GETs.
 */
class VavruskaRepository(
    private val client: VavruskaClient,
    private val courseStore: VavruskaCourseStore,
    private val cache: VavruskaCache,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val refreshing = Mutex()

    suspend fun storedCourse(): VavruskaStoredCourse? = runCatching { courseStore.load() }.getOrNull()
    suspend fun cachedSnapshot(): VavruskaSnapshot? = runCatching { cache.load() }.getOrNull()

    suspend fun connect(password: String): VavruskaStoredCourse {
        val trimmed = password.trim()
        if (trimmed.isEmpty()) throw VavruskaException(VavruskaException.Reason.MISSING_PASSWORD)
        val course = client.login(trimmed)
        runCatching { cache.clear() }
        courseStore.save(course)
        return course
    }

    suspend fun refresh(force: Boolean, includeTicketLinks: Boolean): VavruskaRefreshResult = refreshing.withLock {
        val course = storedCourse() ?: throw VavruskaException(VavruskaException.Reason.NOT_CONNECTED)
        val now = now()
        var snapshot = cachedSnapshot() ?: VavruskaSnapshot()
        var failed = false
        fun isDue(fetchedAt: Long?, every: Long): Boolean {
            fetchedAt ?: return true
            val age = now - fetchedAt
            // Pull to refresh never refetches anything younger than the floor.
            return if (force) age > ForcedFloor else age > every
        }

        // 1. Lesson plan.
        val courseEnded = snapshot.events.isNotEmpty() && snapshot.events.all { it.endEpochMillis < now }
        if (isDue(snapshot.calendarFetchedAt, if (courseEnded) EndedCalendar else Calendar)) {
            try {
                snapshot = snapshot.copy(events = client.fetchCalendar(course.calendarURL), calendarUnavailable = false, calendarFetchedAt = now)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (error: Exception) {
                if ((error as? VavruskaException)?.reason == VavruskaException.Reason.CALENDAR_UNAVAILABLE) {
                    snapshot = snapshot.copy(calendarUnavailable = true, calendarFetchedAt = now)
                } else failed = true
            }
        }

        // 2. Notice board.
        var noticeBoardChanged = false
        if (isDue(snapshot.noticeBoardFetchedAt, NoticeBoard)) {
            try {
                val board = client.fetchNoticeBoard()
                noticeBoardChanged = board.digest != snapshot.noticeBoard?.digest
                snapshot = snapshot.copy(noticeBoard = board, noticeBoardFetchedAt = now)
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failed = true }
        }

        // 3. Ticket sale times from the event pages the board links to.
        if (noticeBoardChanged || isDue(snapshot.ticketSalesFetchedAt, TicketSales)) {
            val paths = (snapshot.noticeBoard?.eventPagePaths.orEmpty() + VavruskaLinks.FallbackEventPath).distinct().take(MaxEventPages)
            val sales = ArrayList<VavruskaTicketSale>()
            var fetchedAny = false
            for (path in paths) {
                try {
                    val raws = client.fetchTicketSales(path)
                    fetchedAny = true
                    raws.mapNotNullTo(sales) { resolve(it, course, snapshot.events, now) }
                } catch (cancelled: CancellationException) { throw cancelled
                } catch (error: Exception) {
                    // A page that is gone has no sales; that is an answer, not a failure.
                    val status = (error as? VavruskaException)?.takeIf { it.reason == VavruskaException.Reason.HTTP_STATUS }?.status
                    if (status == 404 || status == 410) fetchedAny = true else failed = true
                }
            }
            if (fetchedAny) {
                val previousLinks = snapshot.ticketSales.associate { it.id to it.ticketURL }
                snapshot = snapshot.copy(
                    ticketSales = sales.distinctBy { it.id }.map { it.copy(ticketURL = previousLinks[it.id]) }.sortedBy { it.saleStartEpochMillis },
                    ticketSalesFetchedAt = now,
                )
            }
        }

        // 4. Direct ticket links appear on /vstupenky/ only once a sale is live.
        val needsLinks = snapshot.ticketSales.any { it.ticketURL == null && it.eventStartEpochMillis > now && it.saleStartEpochMillis <= now + 2 * Hour }
        if (includeTicketLinks && needsLinks && isDue(snapshot.ticketLinksFetchedAt, TicketLinks)) {
            try {
                val links = client.fetchTicketLinks()
                snapshot = snapshot.copy(
                    ticketSales = snapshot.ticketSales.map { if (it.ticketURL == null) it.copy(ticketURL = ticketLink(it.eventStartEpochMillis, links)) else it },
                    ticketLinksFetchedAt = now,
                )
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (_: Exception) { failed = true }
        }

        // A disconnect while requests were in flight must not bring the saved copy back.
        if (storedCourse()?.calendarURL != course.calendarURL) throw VavruskaException(VavruskaException.Reason.NOT_CONNECTED)
        runCatching { cache.save(snapshot) }
        VavruskaRefreshResult(snapshot, failed)
    }

    suspend fun disconnect() {
        runCatching { courseStore.clear() }
        runCatching { cache.clear() }
    }

    suspend fun clearCache() { runCatching { cache.clear() } }

    /** A sale from an event page, kept only when it is for this course, with its years worked out. */
    private fun resolve(raw: VavruskaRawTicketSale, course: VavruskaStoredCourse, events: List<VavruskaEvent>, now: Long): VavruskaTicketSale? {
        val anchors = events.filter { it.kind.isEvent }
        val eventStart = VavruskaDates.eventStart(raw.eventDay, raw.eventMonth, raw.startHour, raw.startMinute, Instant.ofEpochMilli(now), anchors) ?: return null
        val eventDay = eventStart.atZone(VavruskaDates.Prague).toLocalDate()
        val sameDayEvent = anchors.firstOrNull { Instant.ofEpochMilli(it.startEpochMillis).atZone(VavruskaDates.Prague).toLocalDate() == eventDay }
        val ownCode = course.courseCode?.let(VavruskaCourseCode::parse)
        if (ownCode != null) {
            if (raw.courseCodes.mapNotNull(VavruskaCourseCode::parse).none { it.matches(ownCode) }) return null
        } else if (sameDayEvent == null) return null
        val saleStart = VavruskaDates.saleStart(raw.saleDay, raw.saleMonth, raw.saleYear, raw.saleHour, raw.saleMinute, eventStart) ?: return null
        val eventEnd = if (raw.endHour != null && raw.endMinute != null) {
            VavruskaDates.instant(eventDay.year, eventDay.monthValue, eventDay.dayOfMonth, raw.endHour, raw.endMinute)
                // An end before the start is after midnight.
                ?.let { if (it <= eventStart) it.plusSeconds(24 * 60 * 60) else it }
        } else null
        return VavruskaTicketSale(
            id = VavruskaDates.eventKey(eventStart),
            eventTitle = sameDayEvent?.title ?: raw.pageTitle,
            eventStartEpochMillis = eventStart.toEpochMilli(), eventEndEpochMillis = eventEnd?.toEpochMilli(),
            // The lesson plan names the hall ("Obecní dům"); the event page only in a sentence.
            venue = sameDayEvent?.venue ?: raw.venue,
            saleStartEpochMillis = saleStart.toEpochMilli(),
        )
    }

    companion object {
        private const val Hour = 60L * 60 * 1000
        private const val Calendar = 6 * Hour
        private const val EndedCalendar = 7 * 24 * Hour
        private const val NoticeBoard = 6 * Hour
        private const val TicketSales = 12 * Hour
        private const val TicketLinks = Hour / 2
        private const val ForcedFloor = 2L * 60 * 1000
        private const val MaxEventPages = 5

        /** The link for the event that starts then: the same day and time, or the day's only one. */
        internal fun ticketLink(eventStartEpochMillis: Long, links: List<VavruskaTicketLink>): String? {
            val start = Instant.ofEpochMilli(eventStartEpochMillis).atZone(VavruskaDates.Prague)
            val sameDay = links.filter { it.year == start.year && it.month == start.monthValue && it.day == start.dayOfMonth }
            return sameDay.firstOrNull { it.hour == start.hour && it.minute == start.minute }?.url ?: sameDay.singleOrNull()?.url
        }
    }
}

/** What the dance class screens read from a snapshot (iOS `VavruskaViewModel`'s derived values). */
object VavruskaSchedule {
    fun upcoming(snapshot: VavruskaSnapshot, now: Long): List<VavruskaEvent> = snapshot.events.filter { it.endEpochMillis > now }
    fun past(snapshot: VavruskaSnapshot, now: Long): List<VavruskaEvent> = snapshot.events.filter { it.endEpochMillis <= now }.asReversed()

    /** Lessons on a civil day in Prague, for the Timetable's "after school" block. */
    fun on(snapshot: VavruskaSnapshot, day: LocalDate): List<VavruskaEvent> =
        snapshot.events.filter { Instant.ofEpochMilli(it.startEpochMillis).atZone(VavruskaDates.Prague).toLocalDate() == day }

    /** Sales opening within three weeks, or open now for an event still ahead. */
    fun relevantTicketSales(snapshot: VavruskaSnapshot, now: Long): List<VavruskaTicketSale> =
        snapshot.ticketSales.filter { it.eventStartEpochMillis > now && it.saleStartEpochMillis < now + 21L * 24 * 60 * 60 * 1000 }

    fun isCourseEnded(snapshot: VavruskaSnapshot, now: Long): Boolean = snapshot.events.maxOfOrNull { it.endEpochMillis }?.let { it < now } ?: false

    /**
     * What Today says about the dance class (iOS `TodayView.danceRows`): the next lesson within two
     * weeks, a ticket sale worth knowing about, news on the board, or that the link needs a new
     * sign-in. Null hides the section: nothing is worth a row.
     *
     * @param zone the device's, which decides whether the next lesson is today.
     */
    fun todayRows(snapshot: VavruskaSnapshot, hasUnreadNotices: Boolean, now: Long, zone: java.time.ZoneId): VavruskaTodayRows? {
        val next = upcoming(snapshot, now).firstOrNull { it.startEpochMillis < now + 14L * 24 * 60 * 60 * 1000 }
        val sale = relevantTicketSales(snapshot, now).firstOrNull()
        if (!snapshot.calendarUnavailable && next == null && sale == null && !hasUnreadNotices) return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return VavruskaTodayRows(
            needsReconnect = snapshot.calendarUnavailable, nextEvent = next, sale = sale, hasUnreadNotices = hasUnreadNotices,
            isDanceDay = next != null && Instant.ofEpochMilli(next.startEpochMillis).atZone(zone).toLocalDate() == today,
        )
    }
}

/** @param isDanceDay the next lesson is today, which moves the section up next to the school day. */
data class VavruskaTodayRows(
    val needsReconnect: Boolean,
    val nextEvent: VavruskaEvent?,
    val sale: VavruskaTicketSale?,
    val hasUnreadNotices: Boolean,
    val isDanceDay: Boolean,
)

/**
 * Sample pages shaped like vavruska.info's real output, dated relative to now so the demo always has
 * a lesson this week, a prodloužená soon and a sale about to open (iOS `PreviewData+Vavruska`).
 */
object VavruskaDemoData {
    private const val Hash = "00000000000000000000000000000000"
    private val Months = listOf("ledna", "února", "března", "dubna", "května", "června", "července", "srpna", "září", "října", "listopadu", "prosince")

    val course = VavruskaStoredCourse("M04", "neděle 16.30 - 18.10", VavruskaLinks.calendarURL(Hash), VavruskaLinks.pdfURL(Hash), 1_780_000_000_000)

    /** The Monday of this week in Prague, offset by [days]. */
    private fun day(days: Long, now: Instant): LocalDate =
        now.atZone(VavruskaDates.Prague).toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).plusDays(days)

    private fun stamp(day: LocalDate, hour: Int, minute: Int) = "%04d%02d%02dT%02d%02d00".format(day.year, day.monthValue, day.dayOfMonth, hour, minute)

    fun ics(now: Instant = Instant.now()): String {
        class Row(val uid: Int, val day: Long, val start: Pair<Int, Int>, val end: Pair<Int, Int>, val summary: String, val venue: String, val address: String)
        val rows = listOf(
            Row(1, -7, 16 to 30, 18 to 10, "Vyučovací lekce - TŠ Vavruška", "Obecní dům", "náměstí Republiky 5, Praha 1"),
            Row(2, 2, 19 to 0, 20 to 40, "Vyučovací lekce - TŠ Vavruška", "Obecní dům", "náměstí Republiky 5, Praha 1"),
            Row(3, 6, 16 to 30, 18 to 10, "Vyučovací lekce - TŠ Vavruška", "Obecní dům", "náměstí Republiky 5, Praha 1"),
            Row(4, 13, 11 to 30, 14 to 0, "Lekce stolování - TŠ Vavruška", "Hotel Pyramida", "Bělohorská 24, Praha 6"),
            Row(5, 20, 16 to 0, 19 to 0, "Prodloužená lekce - TŠ Vavruška", "Obecní dům", "náměstí Republiky 5, Praha 1"),
            Row(6, 27, 16 to 30, 18 to 10, "Latino lekce - TŠ Vavruška", "Obecní dům", "náměstí Republiky 5, Praha 1"),
            Row(7, 111, 14 to 30, 18 to 0, "Závěrečný Květinový ples - TŠ Vavruška", "Lucerna", "Štěpánská 61, Praha 1"),
        )
        val lines = mutableListOf(
            "BEGIN:VCALENDAR", "PRODID:-//Vavruska.info//Kalendář//CS", "VERSION:2.0",
            "BEGIN:VTIMEZONE", "TZID:Europe/Prague", "BEGIN:STANDARD", "DTSTART:19701025T030000", "END:STANDARD", "END:VTIMEZONE",
        )
        for (row in rows) {
            val date = day(row.day, now)
            lines += listOf(
                "BEGIN:VEVENT", "UID:preview-${row.uid}@vavruska.info", "SUMMARY:${row.summary}", "DESCRIPTION: ${row.venue}", "LOCATION:${row.address}",
                "DTSTART;TZID=Europe/Prague:${stamp(date, row.start.first, row.start.second)}", "DTEND;TZID=Europe/Prague:${stamp(date, row.end.first, row.end.second)}",
                "END:VEVENT",
            )
        }
        lines += "END:VCALENDAR"
        return lines.joinToString("\n")
    }

    const val NoticeBoardHTML = """
    <div class="article__body">
        <p class="article__meta">Aktualizováno: 6. 10. 2026</p>
        <h4>Podzimní prodloužené</h4>
        <p>První větší akcí bude "podzimní prodloužená". Všechny informace <a href="/akce/plesy/prodlouzena-mladez">najdete zde.</a>
        Prodej vstupenek bude probíhat <a href="/vstupenky">on-line zde</a>.</p>
        <hr class="article-divider">
        <h4>Oblečení do tanečních</h4>
        <p>Všichni naši klienti získávají slevy:<br>
        <strong>Slečny:</strong> 10% slevu na šaty.<br>
        <strong>Všichni:</strong> 10% slevu na obuv od <a href="https://www.hellerdance.cz" target="_blank">Heller dance</a>.</p>
    </div>
    <div class="values-strip"></div>
    """

    fun eventPageHTML(now: Instant = Instant.now()): String {
        fun text(date: LocalDate) = "${date.dayOfMonth}. ${Months[date.monthValue - 1]}"
        val sale = now.plusSeconds(2L * 24 * 60 * 60).atZone(VavruskaDates.Prague)
        return """
        <h1 class="page-head__title">Prodloužená kurzů pro mládež</h1>
        <ul>
            <li><span>v neděli ${text(day(20, now))} od 16:00 do 19:00</span> v <a href="/tanecni-saly/obecni-dum">Obecním domě</a> pro kurz <span>M04</span>.<br>Prodej vstupenek začíná ${sale.dayOfMonth}. ${sale.monthValue}. v 18.00.</li>
            <li><span>v pondělí ${text(day(21, now))} od 16:30 do 19:30</span> v <a href="/tanecni-saly/lucerna">paláci Lucerna</a> pro kurzy <span>M13 a M14</span>.<br>Prodej vstupenek začíná 12. 10. ve 12.00.</li>
        </ul>
        <div class="values-strip"></div>
        """
    }

    fun ticketListingHTML(now: Instant = Instant.now()): String {
        val event = day(20, now)
        return """<a href="/vstupenky/prodlouzena-${event.dayOfMonth}-${event.monthValue}-${event.year}-od-1600/#obsah">Přejít k zakoupení vstupenky</a>"""
    }
}

/** Runs the real parsers on the sample pages (iOS `MockVavruskaClient`). The password "WRONG" is refused. */
class DemoVavruskaClient(var calendarUnavailable: Boolean = false, private val now: () -> Instant = Instant::now) : VavruskaClient {
    override suspend fun login(password: String): VavruskaStoredCourse {
        if (password.uppercase() == "WRONG") throw VavruskaException(VavruskaException.Reason.INVALID_PASSWORD)
        // A fresh login hands out a working link again.
        calendarUnavailable = false
        return VavruskaDemoData.course
    }

    override suspend fun fetchCalendar(url: String): List<VavruskaEvent> {
        if (calendarUnavailable) throw VavruskaException(VavruskaException.Reason.CALENDAR_UNAVAILABLE)
        return VavruskaICSParser.parse(VavruskaDemoData.ics(now()))
    }

    override suspend fun fetchNoticeBoard(): VavruskaNoticeBoard = VavruskaHTMLParser.parseNoticeBoard(VavruskaDemoData.NoticeBoardHTML)

    override suspend fun fetchTicketSales(path: String): List<VavruskaRawTicketSale> =
        if (path == VavruskaLinks.FallbackEventPath) VavruskaHTMLParser.parseTicketSales(VavruskaDemoData.eventPageHTML(now())) else emptyList()

    override suspend fun fetchTicketLinks(): List<VavruskaTicketLink> = VavruskaHTMLParser.parseTicketLinks(VavruskaDemoData.ticketListingHTML(now()))
}
