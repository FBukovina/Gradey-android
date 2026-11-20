package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.VavruskaClient
import com.bukovinafilip.gradey.domain.VavruskaEvent
import com.bukovinafilip.gradey.domain.VavruskaException
import com.bukovinafilip.gradey.domain.VavruskaException.Reason
import com.bukovinafilip.gradey.domain.VavruskaHTMLParser
import com.bukovinafilip.gradey.domain.VavruskaICSParser
import com.bukovinafilip.gradey.domain.VavruskaLinks
import com.bukovinafilip.gradey.domain.VavruskaNoticeBoard
import com.bukovinafilip.gradey.domain.VavruskaRawTicketSale
import com.bukovinafilip.gradey.domain.VavruskaStoredCourse
import com.bukovinafilip.gradey.domain.VavruskaTicketLink
import kotlinx.coroutines.CancellationException
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit

/**
 * vavruska.info over HTTPS (iOS `URLSessionVavruskaClient`). Every request, and every redirect it
 * follows, must stay on the school's own host; cookies are never kept between requests.
 *
 * @param allowed which addresses may be fetched. Only tests change it, to point at a local server.
 */
class VavruskaNetworkClient(
    okHttpClient: OkHttpClient = OkHttpClient(),
    private val allowed: (HttpUrl) -> Boolean = { VavruskaLinks.isAllowedFetchURL(it.toString()) },
    private val now: () -> Long = System::currentTimeMillis,
) : VavruskaClient {
    private val client = okHttpClient.newBuilder()
        .callTimeout(20, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .cache(null)
        // Runs for each hop, so a redirect cannot lead anywhere else.
        .addNetworkInterceptor(Interceptor { chain -> if (allowed(chain.request().url)) chain.proceed(chain.request()) else throw BlockedHost() })
        .build()

    /**
     * Logs in with the course card password in a throwaway session (its cookie never leaves memory)
     * and keeps only the lesson plan links.
     */
    override suspend fun login(password: String): VavruskaStoredCourse {
        val session = client.newBuilder().cookieJar(MemoryCookieJar()).build()
        // The site starts its session on the form's page; without it the login still works on most days.
        try { text(session, request(VavruskaLinks.SchedulePage).build()) } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {}
        val login = request(VavruskaLinks.Login)
            // `cba` is the site's honeypot: it must be present and empty.
            .post(FormBody.Builder().add("heslo", password).add("cba", "").build())
            .header("Origin", VavruskaLinks.Base)
            .header("Referer", VavruskaLinks.SchedulePage)
            .build()
        return when (val page = VavruskaHTMLParser.parseLogin(text(session, login))) {
            is VavruskaHTMLParser.LoginPage.Course ->
                VavruskaStoredCourse(page.code, page.summary, VavruskaLinks.calendarURL(page.hash), VavruskaLinks.pdfURL(page.hash), now())
            VavruskaHTMLParser.LoginPage.LoginForm -> throw VavruskaException(Reason.INVALID_PASSWORD)
            VavruskaHTMLParser.LoginPage.Unknown -> throw VavruskaException(Reason.UNEXPECTED_PAGE)
        }
    }

    override suspend fun fetchCalendar(url: String): List<VavruskaEvent> {
        val body = try {
            text(client, request(url).build())
        } catch (error: VavruskaException) {
            // The school retires the link with each new season.
            if (error.reason == Reason.HTTP_STATUS && (error.status == 404 || error.status == 410)) throw VavruskaException(Reason.CALENDAR_UNAVAILABLE)
            throw error
        }
        return VavruskaICSParser.parse(body)
    }

    override suspend fun fetchNoticeBoard(): VavruskaNoticeBoard = VavruskaHTMLParser.parseNoticeBoard(text(client, request(VavruskaLinks.NoticeBoard).build()))

    override suspend fun fetchTicketSales(path: String): List<VavruskaRawTicketSale> {
        val url = VavruskaLinks.page(path) ?: throw VavruskaException(Reason.INVALID_RESPONSE)
        return VavruskaHTMLParser.parseTicketSales(text(client, request(url).build()))
    }

    override suspend fun fetchTicketLinks(): List<VavruskaTicketLink> = VavruskaHTMLParser.parseTicketLinks(text(client, request(VavruskaLinks.Tickets).build()))

    private fun request(url: String): Request.Builder {
        val parsed = url.toHttpUrlOrNull()?.takeIf(allowed) ?: throw VavruskaException(Reason.INVALID_RESPONSE)
        return Request.Builder().url(parsed)
            .header("Accept", "text/html,text/calendar;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "cs-CZ,cs;q=0.9")
            .header("User-Agent", UserAgent)
    }

    private suspend fun text(client: OkHttpClient, request: Request): String {
        val bytes = try {
            client.executeBytes(request, MaxResponseBytes)
        } catch (error: GradeyApiException) {
            throw VavruskaException(Reason.HTTP_STATUS, error.statusCode)
        } catch (error: IOException) {
            if (error is BlockedHost || error.cause is BlockedHost) throw VavruskaException(Reason.INVALID_RESPONSE)
            throw error
        }
        // The site is UTF-8; older pages of its kind are Windows-1250.
        return try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            String(bytes, Charset.forName("windows-1250"))
        }
    }

    private class BlockedHost : IOException("Refused to leave vavruska.info.")

    /** Cookies for one login, held in memory and dropped with the client. */
    private class MemoryCookieJar : CookieJar {
        private val cookies = ArrayList<Cookie>()
        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            for (cookie in cookies) {
                this.cookies.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                this.cookies += cookie
            }
        }
        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> = cookies.filter { it.matches(url) }
    }

    private companion object {
        const val MaxResponseBytes = 2L * 1024 * 1024
        const val UserAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
    }
}
