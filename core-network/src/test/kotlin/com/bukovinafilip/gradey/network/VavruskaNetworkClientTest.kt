package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.VavruskaDemoData
import com.bukovinafilip.gradey.domain.VavruskaException
import com.bukovinafilip.gradey.domain.VavruskaException.Reason
import com.bukovinafilip.gradey.domain.VavruskaLinks
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.Instant

class VavruskaNetworkClientTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun teardown() { server.shutdown() }

    /** The real client, with https://vavruska.info answered by the local server. */
    private fun client(): VavruskaNetworkClient {
        val local = server.url("/")
        val toLocal = Interceptor { chain ->
            val url = chain.request().url
            chain.proceed(if (url.host == "vavruska.info") chain.request().newBuilder().url(url.newBuilder().scheme(local.scheme).host(local.host).port(local.port).build()).build() else chain.request())
        }
        return VavruskaNetworkClient(OkHttpClient.Builder().addInterceptor(toLocal).build(), allowed = { it.host == "vavruska.info" || it.host == local.host }, now = { 1_790_000_000_000 })
    }

    private fun reason(block: suspend () -> Unit) = (runBlocking { runCatching { block() }.exceptionOrNull() } as VavruskaException)

    @Test fun loginSendsThePasswordOnceInAThrowawaySessionAndKeepsOnlyTheLinks() = runTest {
        val hash = "0123456789abcdef0123456789abcdef"
        server.enqueue(MockResponse().setBody("<form><input name=\"heslo\"></form>").addHeader("Set-Cookie", "PHPSESSID=abc123; path=/"))
        server.enqueue(MockResponse().setBody("<h2>Rozvrh lekcí kurzu M04 · neděle 16.30 - 18.10</h2>\n<a href=\"/pdf/rozpis-lekci/$hash/cal/\">Kalendář</a>"))
        val course = client().login("Ab c+1&x=č")
        assertEquals("M04", course.courseCode)
        assertEquals("neděle 16.30 - 18.10", course.scheduleSummary)
        assertEquals("https://vavruska.info/pdf/rozpis-lekci/$hash/cal/", course.calendarURL)
        assertEquals("https://vavruska.info/pdf/rozpis-lekci/$hash/", course.pdfURL)
        assertEquals(1_790_000_000_000, course.connectedAtEpochMillis)

        val form = server.takeRequest()
        assertEquals("GET", form.method)
        assertEquals("/rozvrh-lekci-mladez/", form.path)
        assertNull(form.getHeader("Cookie"))
        val login = server.takeRequest()
        assertEquals("POST", login.method)
        assertEquals("/rozvrh-lekci-mladez/?action=login", login.path)
        // The password is form-encoded, and the honeypot field is there and empty.
        assertEquals("heslo=Ab%20c%2B1%26x%3D%C4%8D&cba=", login.body.readUtf8())
        assertEquals("application/x-www-form-urlencoded", login.getHeader("Content-Type"))
        assertEquals("PHPSESSID=abc123", login.getHeader("Cookie"))
        assertEquals("https://vavruska.info", login.getHeader("Origin"))
        assertEquals(VavruskaLinks.SchedulePage, login.getHeader("Referer"))

        // The session's cookie is gone with the login: later requests carry none.
        server.enqueue(MockResponse().setBody(VavruskaDemoData.NoticeBoardHTML))
        assertEquals(2, client().fetchNoticeBoard().items.size)
        val board = server.takeRequest()
        assertEquals("/nastenka/", board.path)
        assertNull(board.getHeader("Cookie"))
        assertTrue(board.getHeader("User-Agent")!!.startsWith("Mozilla/5.0"))
    }

    @Test fun aWrongPasswordAndAChangedSiteAreToldApart() {
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody("<form><input type=\"password\" name=\"heslo\"><input name=\"cba\"></form>"))
        // The first page failing does not stop the login.
        assertEquals(Reason.INVALID_PASSWORD, reason { client().login("spatne") }.reason)
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setBody("<html>Probíhá údržba</html>"))
        assertEquals(Reason.UNEXPECTED_PAGE, reason { client().login("Abc123") }.reason)
        server.enqueue(MockResponse().setBody("ok"))
        server.enqueue(MockResponse().setResponseCode(503))
        val failure = reason { client().login("Abc123") }
        assertEquals(Reason.HTTP_STATUS, failure.reason)
        assertEquals(503, failure.status)
    }

    @Test fun theLessonPlanTicketPagesAndListingAreReadFromTheirPages() = runTest {
        val written = Instant.parse("2026-09-30T13:00:00Z")
        server.enqueue(MockResponse().setBody(VavruskaDemoData.ics(written)).addHeader("Content-Type", "text/calendar; charset=utf-8"))
        val events = client().fetchCalendar(VavruskaDemoData.course.calendarURL)
        assertEquals(7, events.size)
        assertEquals("/pdf/rozpis-lekci/00000000000000000000000000000000/cal/", server.takeRequest().path)

        server.enqueue(MockResponse().setBody(VavruskaDemoData.eventPageHTML(written)))
        assertEquals(listOf(listOf("M04"), listOf("M13", "M14")), client().fetchTicketSales("/akce/plesy/prodlouzena-mladez").map { it.courseCodes })
        assertEquals("/akce/plesy/prodlouzena-mladez", server.takeRequest().path)

        server.enqueue(MockResponse().setBody(VavruskaDemoData.ticketListingHTML(written)))
        assertEquals("https://vavruska.info/vstupenky/prodlouzena-18-10-2026-od-1600/", client().fetchTicketLinks().single().url)
        assertEquals("/vstupenky/", server.takeRequest().path)

        // A page in the old Czech encoding is still read.
        server.enqueue(MockResponse().setBody(Buffer().write("<div class=\"article__body\"><h4>Prodloužená</h4><p>Těšíme se</p></div>".toByteArray(charset("windows-1250")))))
        assertEquals("Prodloužená", client().fetchNoticeBoard().items.single().title)
    }

    @Test fun aRetiredLessonPlanLinkAsksToSignInAgain() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(Reason.CALENDAR_UNAVAILABLE, reason { client().fetchCalendar(VavruskaDemoData.course.calendarURL) }.reason)
        server.enqueue(MockResponse().setResponseCode(410))
        assertEquals(Reason.CALENDAR_UNAVAILABLE, reason { client().fetchCalendar(VavruskaDemoData.course.calendarURL) }.reason)
        // The site answers with its ordinary "not found" page and a 200.
        server.enqueue(MockResponse().setBody("<html>Stránka nenalezena</html>"))
        assertEquals(Reason.CALENDAR_UNAVAILABLE, reason { client().fetchCalendar(VavruskaDemoData.course.calendarURL) }.reason)
        // Any other failure is not the link's fault.
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(Reason.HTTP_STATUS, reason { client().fetchCalendar(VavruskaDemoData.course.calendarURL) }.reason)
        // An event page that is gone is reported with its status, which the repository treats as "no sales".
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(404, reason { client().fetchTicketSales("/akce/plesy/venecek") }.status)
    }

    @Test fun nothingIsEverFetchedFromAnotherHost() {
        // The real client, with no test seam: only https://vavruska.info is allowed.
        val real = VavruskaNetworkClient()
        assertEquals(Reason.INVALID_RESPONSE, reason { real.fetchCalendar(server.url("/cal/").toString()) }.reason)
        assertEquals(Reason.INVALID_RESPONSE, reason { real.fetchCalendar("http://vavruska.info/pdf/rozpis-lekci/x/cal/") }.reason)
        assertEquals(Reason.INVALID_RESPONSE, reason { real.fetchCalendar("https://vavruska.info.example.com/cal/") }.reason)
        assertEquals(Reason.INVALID_RESPONSE, reason { real.fetchTicketSales("https://jinde.example/akce/plesy/x") }.reason)
        assertEquals(0, server.requestCount)

        // A redirect that leaves the site is refused before the other host is asked.
        val other = MockWebServer().also { it.start() }
        try {
            val local = server.url("/")
            val guarded = VavruskaNetworkClient(
                OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
                    val url = chain.request().url
                    chain.proceed(if (url.host == "vavruska.info") chain.request().newBuilder().url(url.newBuilder().scheme(local.scheme).host(local.host).port(local.port).build()).build() else chain.request())
                }).build(),
                allowed = { it.host == "vavruska.info" || (it.host == local.host && it.port == local.port) },
            )
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/nastenka/").toString()))
            other.enqueue(MockResponse().setBody(VavruskaDemoData.NoticeBoardHTML))
            assertEquals(Reason.INVALID_RESPONSE, reason { guarded.fetchNoticeBoard() }.reason)
            assertEquals(0, other.requestCount)
        } finally { other.shutdown() }

        // A page far larger than any of the site's is not read into memory.
        server.enqueue(MockResponse().setBody(Buffer().write(ByteArray(2 * 1024 * 1024 + 1))))
        assertTrue(runBlocking { runCatching { client().fetchNoticeBoard() }.exceptionOrNull() } is java.io.IOException)
    }
}
