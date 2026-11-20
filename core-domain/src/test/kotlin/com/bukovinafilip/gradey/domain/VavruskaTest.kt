package com.bukovinafilip.gradey.domain

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.time.Instant

class VavruskaTest {
    private fun millis(text: String) = Instant.parse(text).toEpochMilli()

    @Test fun theLessonPlanIsReadFromICalendarWithItsTimeZones() {
        val ics = listOf(
            "\uFEFFBEGIN:VCALENDAR", "VERSION:2.0",
            // The zone block has a DTSTART of its own that is not an event.
            "BEGIN:VTIMEZONE", "TZID:Europe/Prague", "BEGIN:STANDARD", "DTSTART:19701025T030000", "END:STANDARD", "END:VTIMEZONE",
            "BEGIN:VEVENT", "UID:b@vavruska.info", "SUMMARY:Prodloužená lekce - TŠ Vavruška", "DESCRIPTION: Obecní dům ",
            "LOCATION:náměstí Republiky 5\\, Praha 1", "DTSTART;TZID=Europe/Prague:20261018T160000", "DTEND;TZID=Europe/Prague:20261018T190000", "END:VEVENT",
            // A folded line, a time in UTC and an escaped line break.
            "BEGIN:VEVENT", "UID:a@vavruska.info", "SUMMARY:Vyučovací lekce -", "  TŠ Vavruška", "DESCRIPTION:Sál\\nvchod B",
            "DTSTART:20260906T143000Z", "DTEND:20260906T161000Z", "END:VEVENT",
            // No zone at all means Prague; an end before the start is ignored.
            "BEGIN:VEVENT", "UID:c@vavruska.info", "SUMMARY:Latino lekce", "DTSTART:20261101T163000", "DTEND:20261101T100000", "END:VEVENT",
            // A whole day.
            "BEGIN:VEVENT", "UID:d@vavruska.info", "SUMMARY:Závěrečný Květinový ples - TŠ Vavruška", "DTSTART;VALUE=DATE:20270213", "END:VEVENT",
            // Without a UID or a start there is nothing to show.
            "BEGIN:VEVENT", "SUMMARY:Bez UID", "DTSTART:20261101T163000", "END:VEVENT",
            "BEGIN:VEVENT", "UID:e@vavruska.info", "SUMMARY:Bez začátku", "END:VEVENT",
            "END:VCALENDAR",
        ).joinToString("\r\n")
        val events = VavruskaICSParser.parse(ics)
        assertThat(events.map { it.id }).containsExactly("a@vavruska.info", "b@vavruska.info", "c@vavruska.info", "d@vavruska.info").inOrder()

        val lesson = events[0]
        assertThat(lesson.title).isEqualTo("Vyučovací lekce")
        assertThat(lesson.kind).isEqualTo(VavruskaEventKind.LESSON)
        assertThat(lesson.venue).isEqualTo("Sál\nvchod B")
        assertThat(lesson.address).isNull()
        assertThat(lesson.startEpochMillis).isEqualTo(millis("2026-09-06T14:30:00Z"))
        assertThat(lesson.endEpochMillis).isEqualTo(millis("2026-09-06T16:10:00Z"))

        val extended = events[1]
        assertThat(extended.kind).isEqualTo(VavruskaEventKind.EXTENDED_LESSON)
        assertThat(extended.venue).isEqualTo("Obecní dům")
        assertThat(extended.address).isEqualTo("náměstí Republiky 5, Praha 1")
        // 16:00 in Prague in October is 14:00 UTC.
        assertThat(extended.startEpochMillis).isEqualTo(millis("2026-10-18T14:00:00Z"))

        // November is winter time: 16:30 in Prague is 15:30 UTC. The bad end falls back to the start.
        assertThat(events[2].kind).isEqualTo(VavruskaEventKind.SPECIAL_LESSON)
        assertThat(events[2].startEpochMillis).isEqualTo(millis("2026-11-01T15:30:00Z"))
        assertThat(events[2].endEpochMillis).isEqualTo(events[2].startEpochMillis)

        assertThat(events[3].kind).isEqualTo(VavruskaEventKind.BALL)
        assertThat(events[3].title).isEqualTo("Závěrečný Květinový ples")
        assertThat(events[3].endEpochMillis - events[3].startEpochMillis).isEqualTo(24L * 60 * 60 * 1000)

        // The site answers a dead link with a web page, not a calendar.
        val failure = runCatching { VavruskaICSParser.parse("<html>Stránka nenalezena</html>") }.exceptionOrNull() as VavruskaException
        assertThat(failure.reason).isEqualTo(VavruskaException.Reason.CALENDAR_UNAVAILABLE)
        assertThat(VavruskaICSParser.displayTitle("TŠ Vavruška")).isEqualTo("TŠ Vavruška")
    }

    @Test fun theLoginPageGivesTheCourseAndItsPrivateLink() {
        val hash = "0123456789abcdefABCDEF0123456789"
        val page = "<h2>Rozvrh lekcí kurzu <strong>M18-A</strong> &middot; neděle 16.30 - 18.10<br>další</h2>\n<a href=\"/pdf/rozpis-lekci/$hash/cal/\">Kalendář</a>"
        assertThat(VavruskaHTMLParser.parseLogin(page)).isEqualTo(VavruskaHTMLParser.LoginPage.Course("M18-A", "neděle 16.30 - 18.10", hash.lowercase()))
        // The link alone is enough to connect.
        assertThat(VavruskaHTMLParser.parseLogin("<a href=\"/pdf/rozpis-lekci/$hash/cal/\">x</a>")).isEqualTo(VavruskaHTMLParser.LoginPage.Course(null, null, hash.lowercase()))
        // A wrong password shows the form again.
        assertThat(VavruskaHTMLParser.parseLogin("<form><input type=\"password\" name=\"heslo\"><input name=\"cba\"></form>")).isEqualTo(VavruskaHTMLParser.LoginPage.LoginForm)
        assertThat(VavruskaHTMLParser.parseLogin("<html>Údržba</html>")).isEqualTo(VavruskaHTMLParser.LoginPage.Unknown)
        assertThat(VavruskaLinks.calendarURL(hash.lowercase())).isEqualTo("https://vavruska.info/pdf/rozpis-lekci/0123456789abcdefabcdef0123456789/cal/")
    }

    @Test fun theNoticeBoardKeepsLinksBoldTextAndLineBreaks() {
        val board = VavruskaHTMLParser.parseNoticeBoard("<header><h4>Menu</h4></header>" + VavruskaDemoData.NoticeBoardHTML + "<footer><h4>Kontakt</h4><p>pata</p></footer>")
        assertThat(board.updatedOn).isEqualTo("2026-10-06")
        assertThat(board.items.map { it.title }).containsExactly("Podzimní prodloužené", "Oblečení do tanečních").inOrder()
        assertThat(board.eventPagePaths).containsExactly("/akce/plesy/prodlouzena-mladez")

        val first = board.items[0].paragraphs.single()
        assertThat(first.map { it.text }).containsExactly(
            "První větší akcí bude \"podzimní prodloužená\". Všechny informace ", "najdete zde.", " Prodej vstupenek bude probíhat ", "on-line zde", ".",
        ).inOrder()
        assertThat(first.map { it.link }).containsExactly(
            null, "https://vavruska.info/akce/plesy/prodlouzena-mladez", null, "https://vavruska.info/vstupenky", null,
        ).inOrder()

        val second = board.items[1].paragraphs.single()
        assertThat(second.joinToString("") { it.text }).isEqualTo("Všichni naši klienti získávají slevy:\nSlečny: 10% slevu na šaty.\nVšichni: 10% slevu na obuv od Heller dance.")
        assertThat(second.filter { it.isBold }.map { it.text }).containsExactly("Slečny:", "Všichni:").inOrder()
        assertThat(second.single { it.link != null }.link).isEqualTo("https://www.hellerdance.cz")

        // The fingerprint is the same every time, and changes with the text or the date.
        assertThat(VavruskaHTMLParser.parseNoticeBoard(VavruskaDemoData.NoticeBoardHTML).digest).isEqualTo(board.digest)
        assertThat(VavruskaHTMLParser.parseNoticeBoard(VavruskaDemoData.NoticeBoardHTML.replace("10% slevu na šaty", "15% slevu na šaty")).digest).isNotEqualTo(board.digest)
        assertThat(VavruskaHTMLParser.parseNoticeBoard(VavruskaDemoData.NoticeBoardHTML.replace("6. 10. 2026", "7. 10. 2026")).digest).isNotEqualTo(board.digest)
        // Script links never become tappable.
        assertThat(VavruskaHTMLParser.runs("<a href=\"javascript:alert(1)\">klik</a> a <b><a href=\"mailto:kancelar@vavruska.info\">e-mail</a></b>").map { it.link })
            .containsExactly(null, "mailto:kancelar@vavruska.info").inOrder()
        assertThat(VavruskaHTMLParser.fnv1a("")).isEqualTo("cbf29ce484222325")
        assertThat(VavruskaHTMLParser.fnv1a("a")).isEqualTo("af63dc4c8601ec8c")
    }

    @Test fun ticketSalesPairEachEventWithTheSaleThatFollowsIt() {
        val page = """<h1 class="page-head__title">Prodloužená kurzů <em>pro mládež</em></h1>
            <p>Dne 1. ledna od 10:00 v sále pro kurz M99. Bez prodeje.</p>
            <ul>
              <li><span>v neděli 18. října od 16:00 do 19:00</span> v <a href="/saly/od">Obecním domě</a> pro kurz <span>M04</span>.<br>Prodej vstupenek začíná 12. 10. v 18.00.</li>
              <li>v pondělí 19. října od 20.00 ve velkém sále Lucerny pro kurzy M13, M14-B a M18.<br>Prodej vstupenek bude zahájen 3. 1. 2027 od 9:30.</li>
            </ul>
            <div class="values-strip">Prodej vstupenek začíná 1. 1. v 1.00.</div>"""
        val sales = VavruskaHTMLParser.parseTicketSales(page)
        assertThat(sales).hasSize(2)
        assertThat(sales[0]).isEqualTo(VavruskaRawTicketSale(
            eventDay = 18, eventMonth = 10, startHour = 16, startMinute = 0, endHour = 19, endMinute = 0, venue = "v Obecním domě", courseCodes = listOf("M04"),
            saleDay = 12, saleMonth = 10, saleYear = null, saleHour = 18, saleMinute = 0, pageTitle = "Prodloužená kurzů pro mládež",
        ))
        assertThat(sales[1].courseCodes).containsExactly("M13", "M14-B", "M18").inOrder()
        assertThat(sales[1].venue).isEqualTo("ve velkém sále Lucerny")
        assertThat(sales[1].endHour).isNull()
        assertThat(listOf(sales[1].saleDay, sales[1].saleMonth, sales[1].saleYear, sales[1].saleHour, sales[1].saleMinute)).containsExactly(3, 1, 2027, 9, 30).inOrder()

        val links = VavruskaHTMLParser.parseTicketLinks(
            """<a href="/vstupenky/prodlouzena-18-10-2026-od-1600/#obsah">Koupit</a><a href="/vstupenky/prodlouzena-18-10-2026-od-1600/">znovu</a>
               <a href="/vstupenky/ples-13-2-2027-od-930">Ples</a><a href="/vstupenky/">Vše</a><a href="https://jinde.example/vstupenky/x-1-1-2027-od-1000/">cizí</a>""",
        )
        assertThat(links).containsExactly(
            VavruskaTicketLink("https://vavruska.info/vstupenky/prodlouzena-18-10-2026-od-1600/", 18, 10, 2026, 16, 0),
            VavruskaTicketLink("https://vavruska.info/vstupenky/ples-13-2-2027-od-930", 13, 2, 2027, 9, 30),
        ).inOrder()
    }

    @Test fun yearsAreWorkedOutFromTheLessonPlanAndTheClock() {
        val now = Instant.parse("2026-12-20T10:00:00Z")
        val ball = VavruskaEvent("ball", "Ples", VavruskaEventKind.BALL, startEpochMillis = millis("2027-02-13T13:30:00Z"), endEpochMillis = millis("2027-02-13T17:00:00Z"))
        val lesson = ball.copy(id = "lesson", kind = VavruskaEventKind.LESSON, startEpochMillis = millis("2025-02-13T13:30:00Z"))
        // The plan's own ball on that day decides the year; an ordinary lesson does not.
        assertThat(VavruskaDates.eventStart(13, 2, 14, 30, now, listOf(lesson, ball))).isEqualTo(Instant.parse("2027-02-13T13:30:00Z"))
        // Without the plan: a date two months gone is still this season, one six months gone is next year's.
        assertThat(VavruskaDates.eventStart(18, 10, 16, 0, now, emptyList())).isEqualTo(Instant.parse("2026-10-18T14:00:00Z"))
        assertThat(VavruskaDates.eventStart(1, 6, 16, 0, now, emptyList())).isEqualTo(Instant.parse("2027-06-01T14:00:00Z"))
        assertThat(VavruskaDates.eventStart(31, 2, 16, 0, now, emptyList())).isNull()

        val event = Instant.parse("2027-01-10T15:00:00Z")
        // A sale in December for a January event is in the year before.
        assertThat(VavruskaDates.saleStart(15, 12, null, 18, 0, event)).isEqualTo(Instant.parse("2026-12-15T17:00:00Z"))
        assertThat(VavruskaDates.saleStart(5, 1, null, 18, 0, event)).isEqualTo(Instant.parse("2027-01-05T17:00:00Z"))
        assertThat(VavruskaDates.saleStart(5, 1, 2026, 18, 0, event)).isEqualTo(Instant.parse("2026-01-05T17:00:00Z"))
        // Nothing goes on sale most of a year ahead: the page must mean something else.
        assertThat(VavruskaDates.saleStart(20, 1, null, 18, 0, event)).isNull()
        assertThat(VavruskaDates.eventKey(Instant.parse("2026-10-18T14:00:00Z"))).isEqualTo("20261018-1600")

        val whole = VavruskaCourseCode.parse(" m18 ")!!
        val a = VavruskaCourseCode.parse("M18-A")!!
        assertThat(whole.matches(a)).isTrue()
        assertThat(a.matches(whole)).isTrue()
        assertThat(a.matches(VavruskaCourseCode.parse("M18-B")!!)).isFalse()
        assertThat(a.matches(VavruskaCourseCode.parse("M19-A")!!)).isFalse()
        assertThat(VavruskaCourseCode.parse("D04")).isNull()
        assertThat(VavruskaCourseCode.parse("Mx")).isNull()

        assertThat(VavruskaLinks.isAllowedFetchURL("https://vavruska.info/nastenka/")).isTrue()
        assertThat(VavruskaLinks.isAllowedFetchURL("https://www.vavruska.info/pdf/rozpis-lekci/x/cal/")).isTrue()
        assertThat(VavruskaLinks.isAllowedFetchURL("http://vavruska.info/nastenka/")).isFalse()
        assertThat(VavruskaLinks.isAllowedFetchURL("https://vavruska.info.example.com/")).isFalse()
        assertThat(VavruskaLinks.isAllowedFetchURL("https://galerie.vavruska.info/")).isFalse()
        assertThat(VavruskaLinks.isAllowedFetchURL("not a url")).isFalse()
    }

    /**
     * Counts what is asked of the site, and can be told to fail. Its pages are the sample ones as
     * they were written at [written]; they do not change as the test's clock moves on.
     */
    private class CountingClient(written: Instant) : VavruskaClient {
        private val demo = DemoVavruskaClient(now = { written })
        val calls = mutableListOf<String>()
        var calendarUnavailable = false
        var failing = emptySet<String>()
        var board = VavruskaDemoData.NoticeBoardHTML
        var logins = 0

        private fun record(name: String) { calls += name; if (name.substringBefore(':') in failing) throw java.io.IOException("offline") }
        override suspend fun login(password: String): VavruskaStoredCourse { logins++; return demo.login(password) }
        override suspend fun fetchCalendar(url: String): List<VavruskaEvent> {
            record("calendar")
            if (calendarUnavailable) throw VavruskaException(VavruskaException.Reason.CALENDAR_UNAVAILABLE)
            return demo.fetchCalendar(url)
        }
        override suspend fun fetchNoticeBoard(): VavruskaNoticeBoard { record("board"); return VavruskaHTMLParser.parseNoticeBoard(board) }
        override suspend fun fetchTicketSales(path: String): List<VavruskaRawTicketSale> {
            record("sales:$path")
            if (path != VavruskaLinks.FallbackEventPath) throw VavruskaException(VavruskaException.Reason.HTTP_STATUS, 404)
            return demo.fetchTicketSales(path)
        }
        override suspend fun fetchTicketLinks(): List<VavruskaTicketLink> { record("links"); return demo.fetchTicketLinks() }
    }

    private val hour = 60L * 60 * 1000
    // A Wednesday afternoon in Prague.
    private var clock = millis("2026-09-30T13:00:00Z")
    private fun fixture(): Triple<VavruskaRepository, CountingClient, InMemoryVavruskaCourseStore> {
        val client = CountingClient(Instant.ofEpochMilli(clock))
        val store = InMemoryVavruskaCourseStore()
        return Triple(VavruskaRepository(client, store, InMemoryVavruskaCache(), now = { clock }), client, store)
    }

    @Test fun connectingKeepsOnlyTheCourseAndTheFirstRefreshLoadsEverything() = runBlocking {
        val (repository, client, store) = fixture()
        assertThat((runCatching { repository.refresh(force = false, includeTicketLinks = false) }.exceptionOrNull() as VavruskaException).reason)
            .isEqualTo(VavruskaException.Reason.NOT_CONNECTED)
        assertThat((runCatching { repository.connect("   ") }.exceptionOrNull() as VavruskaException).reason).isEqualTo(VavruskaException.Reason.MISSING_PASSWORD)
        assertThat((runCatching { repository.connect("wrong") }.exceptionOrNull() as VavruskaException).reason).isEqualTo(VavruskaException.Reason.INVALID_PASSWORD)
        assertThat(store.load()).isNull()

        val course = repository.connect("  Abc123 ")
        assertThat(course.courseCode).isEqualTo("M04")
        // What is stored has the lesson plan link and nothing of the password.
        assertThat(store.load()).isEqualTo(course)
        assertThat(course.toString()).doesNotContain("Abc123")

        val result = repository.refresh(force = false, includeTicketLinks = true)
        assertThat(result.failed).isFalse()
        assertThat(client.calls).containsExactly("calendar", "board", "sales:/akce/plesy/prodlouzena-mladez").inOrder()
        val snapshot = result.snapshot
        assertThat(snapshot.events).hasSize(7)
        assertThat(snapshot.noticeBoard!!.items).hasSize(2)
        // Only the sale for course M04; the other row on the page is for M13 and M14.
        val sale = snapshot.ticketSales.single()
        assertThat(sale.eventTitle).isEqualTo("Prodloužená lekce")
        assertThat(sale.venue).isEqualTo("Obecní dům")
        // Monday 28 September + 20 days, 16:00 to 19:00 in Prague; the sale opens the day after tomorrow at 18:00.
        assertThat(sale.id).isEqualTo("20261018-1600")
        assertThat(sale.eventEndEpochMillis).isEqualTo(millis("2026-10-18T17:00:00Z"))
        assertThat(sale.saleStartEpochMillis).isEqualTo(millis("2026-10-02T16:00:00Z"))
        assertThat(sale.ticketURL).isNull()
        assertThat(repository.cachedSnapshot()).isEqualTo(snapshot)

        assertThat(VavruskaSchedule.upcoming(snapshot, clock).map { it.id }).containsExactly(
            "preview-2@vavruska.info", "preview-3@vavruska.info", "preview-4@vavruska.info", "preview-5@vavruska.info", "preview-6@vavruska.info", "preview-7@vavruska.info",
        ).inOrder()
        assertThat(VavruskaSchedule.past(snapshot, clock).single().id).isEqualTo("preview-1@vavruska.info")
        assertThat(VavruskaSchedule.on(snapshot, java.time.LocalDate.parse("2026-09-30")).single().title).isEqualTo("Vyučovací lekce")
        assertThat(VavruskaSchedule.relevantTicketSales(snapshot, clock)).containsExactly(sale)
        assertThat(VavruskaSchedule.isCourseEnded(snapshot, clock)).isFalse()
        assertThat(VavruskaSchedule.isCourseEnded(VavruskaSnapshot(), clock)).isFalse()

        // Today: Wednesday's lesson is tonight, and the sale opens in two days.
        val rows = VavruskaSchedule.todayRows(snapshot, hasUnreadNotices = false, now = clock, zone = VavruskaDates.Prague)!!
        assertThat(rows.nextEvent!!.id).isEqualTo("preview-2@vavruska.info")
        assertThat(rows.isDanceDay).isTrue()
        assertThat(rows.sale).isEqualTo(sale)
        assertThat(rows.needsReconnect).isFalse()
        // On a phone set to Tokyo it is 22:00 and the lesson starts at 2:00 the next morning: not "today".
        assertThat(VavruskaSchedule.todayRows(snapshot, false, clock, java.time.ZoneId.of("Asia/Tokyo"))!!.isDanceDay).isFalse()
        // A course with nothing in the next two weeks, no sale and no news has no place on Today…
        val quiet = snapshot.copy(events = snapshot.events.filter { it.kind == VavruskaEventKind.BALL }, ticketSales = emptyList())
        assertThat(VavruskaSchedule.todayRows(quiet, false, clock, VavruskaDates.Prague)).isNull()
        // …unless the board has news or the link needs a new sign-in.
        assertThat(VavruskaSchedule.todayRows(quiet, true, clock, VavruskaDates.Prague)!!.nextEvent).isNull()
        assertThat(VavruskaSchedule.todayRows(quiet.copy(calendarUnavailable = true), false, clock, VavruskaDates.Prague)!!.needsReconnect).isTrue()
    }

    @Test fun eachSourceIsAskedOnlyWhenItIsDueSoTheSiteSeesAHandfulOfRequests() = runBlocking {
        val (repository, client, _) = fixture()
        repository.connect("Abc123")
        repository.refresh(force = false, includeTicketLinks = true)
        client.calls.clear()

        // Opening the app again and again asks for nothing.
        clock += hour
        repository.refresh(force = false, includeTicketLinks = true)
        assertThat(client.calls).isEmpty()
        // Pull to refresh does, but not twice within two minutes.
        repository.refresh(force = true, includeTicketLinks = false)
        assertThat(client.calls).containsExactly("calendar", "board", "sales:/akce/plesy/prodlouzena-mladez").inOrder()
        client.calls.clear()
        clock += 60_000
        repository.refresh(force = true, includeTicketLinks = false)
        assertThat(client.calls).isEmpty()

        // After six hours the plan and the board are due; the sales only after twelve, unless the board changed.
        clock += 6 * hour
        repository.refresh(force = false, includeTicketLinks = false)
        assertThat(client.calls).containsExactly("calendar", "board").inOrder()
        client.calls.clear()
        clock += 6 * hour + 60_000
        client.board = VavruskaDemoData.NoticeBoardHTML.replace("</h4>\n        <p>První", "</h4>\n        <p>Viz <a href=\"https://vavruska.info/akce/plesy/venecek/\">věneček</a>. První")
        val changed = repository.refresh(force = false, includeTicketLinks = false)
        // The new page the board links to is asked for too; that it is not there yet is not a failure.
        assertThat(client.calls).containsExactly("calendar", "board", "sales:/akce/plesy/venecek", "sales:/akce/plesy/prodlouzena-mladez").inOrder()
        assertThat(changed.failed).isFalse()
        assertThat(changed.snapshot.ticketSales).hasSize(1)
    }

    @Test fun directTicketLinksAreLookedUpOnlyOnceASaleIsAboutToOpen() = runBlocking {
        val (repository, client, _) = fixture()
        repository.connect("Abc123")
        // The sale opens in two days: nothing to look up yet.
        val saleStart = repository.refresh(force = false, includeTicketLinks = true).snapshot.ticketSales.single().saleStartEpochMillis
        assertThat(client.calls).doesNotContain("links")

        // An hour before it opens the listing is checked, but only where the caller wants it (the dance screen, not Today).
        clock = saleStart - hour
        client.calls.clear()
        assertThat(repository.refresh(force = true, includeTicketLinks = false).snapshot.ticketSales.single().ticketURL).isNull()
        assertThat(client.calls).doesNotContain("links")
        clock += 3 * 60_000
        val linked = repository.refresh(force = true, includeTicketLinks = true).snapshot.ticketSales.single()
        assertThat(linked.ticketURL).isEqualTo("https://vavruska.info/vstupenky/prodlouzena-18-10-2026-od-1600/")
        // Once found, the link is kept through later refreshes and not looked up again.
        client.calls.clear()
        clock += 13 * hour
        val later = repository.refresh(force = false, includeTicketLinks = true).snapshot.ticketSales.single()
        assertThat(later.ticketURL).isEqualTo(linked.ticketURL)
        assertThat(client.calls).doesNotContain("links")

        // A link is matched by day and time; a day with one event takes its only link.
        val start = millis("2026-10-18T14:00:00Z")
        val evening = VavruskaTicketLink("https://vavruska.info/vstupenky/b-18-10-2026-od-2000/", 18, 10, 2026, 20, 0)
        val afternoon = VavruskaTicketLink("https://vavruska.info/vstupenky/a-18-10-2026-od-1600/", 18, 10, 2026, 16, 0)
        assertThat(VavruskaRepository.ticketLink(start, listOf(evening, afternoon))).isEqualTo(afternoon.url)
        assertThat(VavruskaRepository.ticketLink(start, listOf(evening))).isEqualTo(evening.url)
        assertThat(VavruskaRepository.ticketLink(start, listOf(evening, evening.copy(hour = 21)))).isNull()
        assertThat(VavruskaRepository.ticketLink(start, listOf(afternoon.copy(day = 19)))).isNull()
    }

    @Test fun aDeadLinkAsksToSignInAgainAndFailuresKeepWhatWasSaved() = runBlocking {
        val (repository, client, store) = fixture()
        repository.connect("Abc123")
        val first = repository.refresh(force = false, includeTicketLinks = false).snapshot

        // Offline: nothing new, nothing lost, and the caller is told.
        clock += 7 * hour
        client.failing = setOf("calendar", "board")
        val offline = repository.refresh(force = false, includeTicketLinks = false)
        assertThat(offline.failed).isTrue()
        assertThat(offline.snapshot.events).isEqualTo(first.events)
        assertThat(offline.snapshot.noticeBoard).isEqualTo(first.noticeBoard)
        // What failed is tried again next time rather than waiting six hours.
        assertThat(offline.snapshot.calendarFetchedAt).isEqualTo(first.calendarFetchedAt)

        // A new season: the school retired the link. The last plan stays on screen.
        client.failing = emptySet()
        client.calendarUnavailable = true
        val retired = repository.refresh(force = false, includeTicketLinks = false)
        assertThat(retired.failed).isFalse()
        assertThat(retired.snapshot.calendarUnavailable).isTrue()
        assertThat(retired.snapshot.events).isEqualTo(first.events)

        // Signing in again starts from a clean slate.
        client.calendarUnavailable = false
        repository.connect("Abc123")
        assertThat(repository.cachedSnapshot()).isNull()
        assertThat(repository.refresh(force = false, includeTicketLinks = false).snapshot.calendarUnavailable).isFalse()

        repository.disconnect()
        assertThat(store.load()).isNull()
        assertThat(repository.cachedSnapshot()).isNull()
    }

    @Test fun aFinishedCourseIsCheckedOnceAWeekAndDisconnectingMidRefreshSavesNothing() = runBlocking {
        val (repository, client, _) = fixture()
        repository.connect("Abc123")
        repository.refresh(force = false, includeTicketLinks = false)
        // Half a year on, every event is over.
        clock += 200L * 24 * hour
        val snapshot = repository.refresh(force = false, includeTicketLinks = false).snapshot
        assertThat(VavruskaSchedule.isCourseEnded(snapshot, clock)).isTrue()
        assertThat(VavruskaSchedule.upcoming(snapshot, clock)).isEmpty()
        assertThat(VavruskaSchedule.past(snapshot, clock).first().kind).isEqualTo(VavruskaEventKind.BALL)
        client.calls.clear()
        clock += 3 * 24 * hour
        repository.refresh(force = false, includeTicketLinks = false)
        assertThat(client.calls).doesNotContain("calendar")
        clock += 5 * 24 * hour
        repository.refresh(force = false, includeTicketLinks = false)
        assertThat(client.calls).contains("calendar")

        // The user disconnects while the board is loading: the refresh must not bring the data back.
        val store = InMemoryVavruskaCourseStore(VavruskaDemoData.course)
        val cache = InMemoryVavruskaCache()
        val racing = VavruskaRepository(object : VavruskaClient by client {
            override suspend fun fetchNoticeBoard(): VavruskaNoticeBoard { store.clear(); cache.clear(); return client.fetchNoticeBoard() }
        }, store, cache, now = { clock })
        val failure = runCatching { racing.refresh(force = true, includeTicketLinks = false) }.exceptionOrNull() as VavruskaException
        assertThat(failure.reason).isEqualTo(VavruskaException.Reason.NOT_CONNECTED)
        assertThat(cache.load()).isNull()
    }
}
