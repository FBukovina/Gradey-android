package com.bukovinafilip.gradey.domain

import kotlinx.serialization.Serializable
import java.net.URI
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * TŠ Vavruška (vavruska.info) youth dance course (iOS `VavruskaModels`). The site has no API: the
 * lesson plan is an ICS file behind a password from the course card, everything else is read from
 * public pages. Device-local only.
 */
object VavruskaLinks {
    const val Base = "https://vavruska.info"
    const val SchedulePage = "$Base/rozvrh-lekci-mladez/"
    const val Login = "$Base/rozvrh-lekci-mladez/?action=login"
    const val NoticeBoard = "$Base/nastenka/"
    const val Tickets = "$Base/vstupenky/"
    const val Clothing = "$Base/obleceni/"
    const val Gallery = "https://galerie.vavruska.info"
    const val Office = "mailto:kancelar@vavruska.info"

    /** Linked from the notice board every autumn; kept as a fallback for when it isn't. */
    const val FallbackEventPath = "/akce/plesy/prodlouzena-mladez"

    fun calendarURL(hash: String) = "$Base/pdf/rozpis-lekci/$hash/cal/"
    fun pdfURL(hash: String) = "$Base/pdf/rozpis-lekci/$hash/"

    /** A path or link from one of the site's pages as an absolute address; null when it cannot be one. */
    fun page(path: String): String? = runCatching { URI(Base + "/").resolve(path.trim()).toString() }.getOrNull()

    /** The client only ever fetches from the school's own host. */
    fun isAllowedFetchURL(url: String): Boolean {
        val uri = runCatching { URI(url) }.getOrNull() ?: return false
        val host = uri.host?.lowercase() ?: return false
        return uri.scheme == "https" && (host == "vavruska.info" || host == "www.vavruska.info")
    }
}

/** What survives a login: the course and its private ICS link. The card password itself is never stored. */
@Serializable
data class VavruskaStoredCourse(
    val courseCode: String? = null,
    /** For example "neděle 16.30 - 18.10". */
    val scheduleSummary: String? = null,
    val calendarURL: String,
    val pdfURL: String? = null,
    val connectedAtEpochMillis: Long,
)

@Serializable
enum class VavruskaEventKind {
    LESSON, SPECIAL_LESSON, EXTENDED_LESSON, BALL;

    /** Prodloužená and the final ball: the ones with tickets for guests. */
    val isEvent: Boolean get() = this == EXTENDED_LESSON || this == BALL

    companion object {
        fun classify(summary: String): VavruskaEventKind {
            val folded = VavruskaText.folded(summary)
            return when {
                "ples" in folded -> BALL
                "prodlouzen" in folded -> EXTENDED_LESSON
                "vyucovaci" in folded -> LESSON
                else -> SPECIAL_LESSON
            }
        }
    }
}

@Serializable
data class VavruskaEvent(
    val id: String,
    val title: String,
    val kind: VavruskaEventKind,
    val venue: String? = null,
    val address: String? = null,
    val startEpochMillis: Long,
    val endEpochMillis: Long,
)

@Serializable
data class VavruskaTextRun(val text: String, val link: String? = null, val isBold: Boolean = false)

@Serializable
data class VavruskaNoticeItem(val id: String, val title: String, val paragraphs: List<List<VavruskaTextRun>>)

@Serializable
data class VavruskaNoticeBoard(
    /** The day the board says it was last changed, `yyyy-MM-dd`. */
    val updatedOn: String? = null,
    val items: List<VavruskaNoticeItem> = emptyList(),
    /** `/akce/plesy/…` pages linked from the board; they carry ticket sale times. */
    val eventPagePaths: List<String> = emptyList(),
    /** Stable fingerprint of the board's content, used for the "new" badge. */
    val digest: String = "",
)

/** A ticket sale as written on an event page, before years are inferred. */
data class VavruskaRawTicketSale(
    val eventDay: Int, val eventMonth: Int, val startHour: Int, val startMinute: Int, val endHour: Int?, val endMinute: Int?,
    val venue: String?, val courseCodes: List<String>,
    val saleDay: Int, val saleMonth: Int, val saleYear: Int?, val saleHour: Int, val saleMinute: Int, val pageTitle: String?,
)

@Serializable
data class VavruskaTicketSale(
    /** Event start in Prague time, "yyyyMMdd-HHmm". */
    val id: String,
    /** Null when neither the lesson plan nor the page names the event; the screen then calls it a prodloužená. */
    val eventTitle: String? = null,
    val eventStartEpochMillis: Long,
    val eventEndEpochMillis: Long? = null,
    val venue: String? = null,
    val saleStartEpochMillis: Long,
    val ticketURL: String? = null,
)

/** A `/vstupenky/<slug>-D-M-YYYY-od-HMM/` link from the ticket listing. */
data class VavruskaTicketLink(val url: String, val day: Int, val month: Int, val year: Int, val hour: Int, val minute: Int)

/** Course codes look like "M04", "M18-A" or "M121". */
data class VavruskaCourseCode(val number: Int, val section: String?) {
    /** "M18" covers both "M18-A" and "M18-B"; "M18-A" and "M18-B" differ. */
    fun matches(other: VavruskaCourseCode): Boolean =
        number == other.number && (section == null || other.section == null || section == other.section)

    companion object {
        fun parse(raw: String): VavruskaCourseCode? {
            val trimmed = raw.trim().uppercase()
            if (!trimmed.startsWith("M")) return null
            val parts = trimmed.drop(1).split("-", limit = 2)
            val number = parts.firstOrNull()?.toIntOrNull() ?: return null
            return VavruskaCourseCode(number, parts.getOrNull(1))
        }
    }
}

@Serializable
data class VavruskaSnapshot(
    val events: List<VavruskaEvent> = emptyList(),
    val calendarFetchedAt: Long? = null,
    /** The ICS link stopped working (new season, new password): reconnect. */
    val calendarUnavailable: Boolean = false,
    val noticeBoard: VavruskaNoticeBoard? = null,
    val noticeBoardFetchedAt: Long? = null,
    val ticketSales: List<VavruskaTicketSale> = emptyList(),
    val ticketSalesFetchedAt: Long? = null,
    val ticketLinksFetchedAt: Long? = null,
)

/** Why the dance class could not be connected or refreshed; the screens put it into words. */
class VavruskaException(val reason: Reason, val status: Int? = null) : Exception(reason.name) {
    enum class Reason { INVALID_RESPONSE, HTTP_STATUS, INVALID_PASSWORD, UNEXPECTED_PAGE, CALENDAR_UNAVAILABLE, NOT_CONNECTED, MISSING_PASSWORD }
}

internal object VavruskaText {
    private val Marks = Regex("\\p{M}+")

    /** Lowercase without diacritics, for matching Czech words however they were typed. */
    fun folded(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFD).replace(Marks, "").lowercase()
}

/** Years and time zones for dates the site writes without them (iOS `VavruskaDateInference`). */
object VavruskaDates {
    val Prague: ZoneId = ZoneId.of("Europe/Prague")

    /** Null for a date that does not exist, such as 31. 2. */
    fun instant(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Instant? =
        runCatching { LocalDateTime.of(year, month, day, hour, minute).atZone(Prague).toInstant() }.getOrNull()

    /**
     * The start of an event the page gives only as a day and month. The lesson plan's own event on
     * that day decides the year; otherwise the nearest year that is not more than four months gone.
     */
    fun eventStart(day: Int, month: Int, hour: Int, minute: Int, now: Instant, anchors: List<VavruskaEvent>): Instant? {
        for (anchor in anchors) {
            if (!anchor.kind.isEvent) continue
            val date = Instant.ofEpochMilli(anchor.startEpochMillis).atZone(Prague).toLocalDate()
            if (date.monthValue == month && date.dayOfMonth == day) return instant(date.year, month, day, hour, minute)
        }
        val year = now.atZone(Prague).year
        val earliest = now.minusSeconds(120L * 24 * 60 * 60)
        return listOf(year - 1, year, year + 1).firstNotNullOfOrNull { candidate -> instant(candidate, month, day, hour, minute)?.takeIf { it >= earliest } }
    }

    /** When a sale opens: before its event, in the event's year or the one before; never most of a year early. */
    fun saleStart(day: Int, month: Int, year: Int?, hour: Int, minute: Int, eventStart: Instant): Instant? {
        if (year != null) return instant(year, month, day, hour, minute)
        val eventYear = eventStart.atZone(Prague).year
        var sale = instant(eventYear, month, day, hour, minute) ?: return null
        if (sale > eventStart) instant(eventYear - 1, month, day, hour, minute)?.let { sale = it }
        return sale.takeIf { eventStart.epochSecond - it.epochSecond < 330L * 24 * 60 * 60 }
    }

    fun eventKey(instant: Instant): String = instant.atZone(Prague).let { "%04d%02d%02d-%02d%02d".format(it.year, it.monthValue, it.dayOfMonth, it.hour, it.minute) }
}

/**
 * Just enough iCalendar to read the school's lesson plan (iOS `VavruskaICSParser`): VEVENTs with
 * UID, SUMMARY, DESCRIPTION, LOCATION and DTSTART/DTEND. VTIMEZONE blocks are skipped; zones come
 * from the system database by TZID.
 */
object VavruskaICSParser {
    private class Property(val parameters: Map<String, String>, val value: String)
    private val Suffix = Regex("\\s*[-–—]\\s*TŠ\\s+Vavruška\\s*$", RegexOption.IGNORE_CASE)

    fun parse(text: String): List<VavruskaEvent> {
        val normalized = text.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n')
        if ("BEGIN:VCALENDAR" !in normalized) throw VavruskaException(VavruskaException.Reason.CALENDAR_UNAVAILABLE)
        // A line that starts with a space or a tab continues the one before it.
        val lines = ArrayList<String>()
        for (raw in normalized.split('\n')) {
            if (raw.isNotEmpty() && (raw[0] == ' ' || raw[0] == '\t') && lines.isNotEmpty()) lines[lines.lastIndex] += raw.drop(1) else lines += raw
        }
        val blocks = ArrayList<String>()
        var properties = HashMap<String, Property>()
        val events = ArrayList<VavruskaEvent>()
        for (line in lines) {
            val trimmed = line.trim()
            val upper = trimmed.uppercase()
            if (upper.startsWith("BEGIN:")) {
                val name = upper.drop(6)
                blocks += name
                if (name == "VEVENT") properties = HashMap()
                continue
            }
            if (upper.startsWith("END:")) {
                if (blocks.lastOrNull() == "VEVENT") makeEvent(properties)?.let(events::add)
                if (blocks.isNotEmpty()) blocks.removeAt(blocks.lastIndex)
                continue
            }
            if (blocks.lastOrNull() != "VEVENT") continue
            val (name, property) = parseProperty(line) ?: continue
            properties.putIfAbsent(name, property)
        }
        return events.sortedBy { it.startEpochMillis }
    }

    private fun parseProperty(line: String): Pair<String, Property>? {
        var inQuotes = false
        var colon = -1
        for ((index, character) in line.withIndex()) {
            if (character == '"') inQuotes = !inQuotes
            else if (character == ':' && !inQuotes) { colon = index; break }
        }
        if (colon < 0) return null
        val head = line.substring(0, colon).split(';')
        val name = head.first().uppercase().takeIf { it.isNotEmpty() } ?: return null
        val parameters = head.drop(1).mapNotNull { parameter ->
            val pair = parameter.split("=", limit = 2)
            if (pair.size == 2) pair[0].uppercase() to pair[1].trim('"') else null
        }.toMap()
        return name to Property(parameters, line.substring(colon + 1))
    }

    private fun unescape(value: String): String {
        val result = StringBuilder()
        var index = 0
        while (index < value.length) {
            val character = value[index]
            if (character == '\\' && index + 1 < value.length) {
                val next = value[index + 1]
                result.append(if (next == 'n' || next == 'N') '\n' else next)
                index += 2
            } else { result.append(character); index++ }
        }
        return result.toString()
    }

    private fun makeEvent(properties: Map<String, Property>): VavruskaEvent? {
        val uid = properties["UID"]?.value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val startProperty = properties["DTSTART"] ?: return null
        val start = instant(startProperty) ?: return null
        val parsedEnd = properties["DTEND"]?.let(::instant)?.takeIf { it >= start }
        val end = parsedEnd ?: if (isAllDay(startProperty)) start.plusSeconds(24 * 60 * 60) else start
        val summary = unescape(properties["SUMMARY"]?.value.orEmpty()).trim()
        return VavruskaEvent(
            id = uid, title = displayTitle(summary), kind = VavruskaEventKind.classify(summary),
            venue = properties["DESCRIPTION"]?.let { unescape(it.value).trim() }?.takeIf { it.isNotEmpty() },
            address = properties["LOCATION"]?.let { unescape(it.value).trim() }?.takeIf { it.isNotEmpty() },
            startEpochMillis = start.toEpochMilli(), endEpochMillis = end.toEpochMilli(),
        )
    }

    /** "Vyučovací lekce - TŠ Vavruška" → "Vyučovací lekce". */
    fun displayTitle(summary: String): String = summary.replace(Suffix, "").ifEmpty { summary }

    private fun isAllDay(property: Property) = property.parameters["VALUE"]?.uppercase() == "DATE" || 'T' !in property.value

    /** `20260906T163000` with TZID, `…Z` (UTC), floating (Prague) or `20260906` (all day). */
    private fun instant(property: Property): Instant? {
        val value = property.value.trim()
        val digits = value.filter(Char::isDigit)
        if (digits.length < 8) return null
        fun number(offset: Int, length: Int) = digits.substring(offset, minOf(offset + length, digits.length)).toIntOrNull()
        val timed = !isAllDay(property) && digits.length >= 12
        val zone = when {
            value.uppercase().endsWith("Z") -> ZoneOffset.UTC
            else -> property.parameters["TZID"]?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: VavruskaDates.Prague
        }
        return runCatching {
            LocalDateTime.of(
                number(0, 4)!!, number(4, 2)!!, number(6, 2)!!,
                if (timed) number(8, 2)!! else 0, if (timed) number(10, 2)!! else 0, if (timed && digits.length >= 14) number(12, 2)!! else 0,
            ).atZone(zone).toInstant()
        }.getOrNull()
    }
}

/** Readers for vavruska.info's server-rendered pages (iOS `VavruskaHTMLParser`). Each targets one stable piece of markup. */
object VavruskaHTMLParser {
    sealed interface LoginPage {
        data class Course(val code: String?, val summary: String?, val hash: String) : LoginPage
        data object LoginForm : LoginPage
        data object Unknown : LoginPage
    }

    private val Flags = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    private val Hash = Regex("/pdf/rozpis-lekci/([0-9a-fA-F]{32})/cal/")
    private val CourseLine = Regex("Rozvrh lekcí kurzu\\s+(M\\d+(?:-[A-Z])?)\\s*(?:[·•|–-]\\s*(.+))?", Flags)
    private val Tags = Regex("<[^>]+>")
    private val Space = Regex("\\s+")
    private val Meta = Regex("<p[^>]*article__meta[^>]*>(.*?)</p>", Flags)
    private val NumericDate = Regex("(\\d{1,2})\\.\\s*(\\d{1,2})\\.\\s*(\\d{4})")
    private val Heading = Regex("<h4[^>]*>(.*?)</h4>", Flags)
    private val Paragraph = Regex("<p(?![^>]*article__meta)[^>]*>(.*?)</p>", Flags)
    private val EventPage = Regex("href=\"(?:https?://(?:www\\.)?vavruska\\.info)?(/akce/plesy/[a-z0-9-]+)/?\"", Flags)
    private val Href = Regex("href\\s*=\\s*\"([^\"]*)\"", Flags)
    private val BreakSpace = Regex(" *\n *")
    private val Months = listOf("ledna", "února", "března", "dubna", "května", "června", "července", "srpna", "září", "října", "listopadu", "prosince")
    private val Code = "M\\d+(?:-[A-Z])?"
    private val EventLine = Regex(
        "(\\d{1,2})\\.\\s*(" + Months.joinToString("|") + ")\\s+od\\s+(\\d{1,2})[.:](\\d{2})(?:\\s+do\\s+(\\d{1,2})[.:](\\d{2}))?\\s+((?:v|ve)\\s+[^\\n]+?)\\s+pro\\s+kurzy?\\s+(" +
            Code + "(?:\\s*(?:,|a|i|\\+|&)\\s*" + Code + ")*)", Flags,
    )
    private val SaleLine = Regex("Prodej\\s+vstupenek\\s+(?:začíná|začne|bude\\s+zahájen)\\s+(\\d{1,2})\\.\\s*(\\d{1,2})\\.(?:\\s*(\\d{4}))?\\s*(?:v|ve|od)?\\s*(\\d{1,2})[.:](\\d{2})", Flags)
    private val CodeOnly = Regex(Code, Flags)
    private val PageTitle = Regex("<h1[^>]*>(.*?)</h1>", Flags)
    private val TicketLink = Regex("href=\"(/vstupenky/[a-z0-9-]+?-(\\d{1,2})-(\\d{1,2})-(\\d{4})-od-(\\d{3,4})/?)(?:#[^\"]*)?\"", Flags)
    private val Hidden = Regex("<(script|style)[^>]*>.*?</\\1>", Flags)
    private val BlockEnd = Regex("</(p|li|h\\d|div|tr)>|<hr[^>]*>", Flags)
    private val LineBreak = Regex("<br\\s*/?>", Flags)

    /** The page after a successful login links `/pdf/rozpis-lekci/<hash>/cal/` and says "Rozvrh lekcí kurzu M04 · neděle 16.30 - 18.10". */
    fun parseLogin(html: String): LoginPage {
        val hash = Hash.find(html)?.groupValues?.get(1) ?: return if ("name=\"heslo\"" in html) LoginPage.LoginForm else LoginPage.Unknown
        var code: String? = null
        var summary: String? = null
        val start = html.indexOf("Rozvrh lekcí kurzu")
        if (start >= 0) {
            val line = html.substring(start).substringBefore('\n').substringBefore("<br")
            CourseLine.find(collapse(decode(strip(line))))?.let { match ->
                code = match.groupValues[1]
                summary = match.groupValues[2].takeIf { it.isNotEmpty() }
            }
        }
        return LoginPage.Course(code, summary, hash.lowercase())
    }

    fun parseNoticeBoard(html: String): VavruskaNoticeBoard {
        val body = slice(html, "class=\"article__body\"", listOf("<div class=\"values-strip", "</main>", "<footer"))
        val updatedText = Meta.find(body)?.groupValues?.get(1)?.let { collapse(decode(strip(it))) }
        val updatedOn = updatedText?.let(NumericDate::find)?.let { match ->
            runCatching { LocalDate.of(match.groupValues[3].toInt(), match.groupValues[2].toInt(), match.groupValues[1].toInt()).toString() }.getOrNull()
        }
        val items = body.split("<hr").mapNotNull { chunk ->
            val title = Heading.find(chunk)?.groupValues?.get(1)?.let { collapse(decode(strip(it))) }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val paragraphs = Paragraph.findAll(chunk).map { runs(it.groupValues[1]) }.filter { it.isNotEmpty() }.toList()
            VavruskaNoticeItem(fnv1a(title + "\n" + paragraphs.joinToString("") { runs -> runs.joinToString("") { it.text } }), title, paragraphs)
        }
        val paths = EventPage.findAll(body).map { it.groupValues[1] }.distinct().toList()
        return VavruskaNoticeBoard(updatedOn, items, paths, fnv1a((listOf(updatedText.orEmpty()) + items.map { it.id }).joinToString("|")))
    }

    /** Turns one `<p>` into text runs, keeping links, bold and line breaks. */
    fun runs(html: String): List<VavruskaTextRun> {
        val runs = ArrayList<VavruskaTextRun>()
        var link: String? = null
        var boldDepth = 0
        fun append(text: String) {
            if (text.isEmpty()) return
            val bold = boldDepth > 0
            val last = runs.lastOrNull()
            if (last != null && last.link == link && last.isBold == bold) runs[runs.lastIndex] = last.copy(text = last.text + text)
            else runs += VavruskaTextRun(text, link, bold)
        }
        var index = 0
        while (index < html.length) {
            val close = if (html[index] == '<') html.indexOf('>', index) else -1
            if (close >= 0) {
                val tag = html.substring(index + 1, close).trim()
                val closing = tag.startsWith("/")
                val name = tag.removePrefix("/").takeWhile(Char::isLetterOrDigit).lowercase()
                when {
                    name == "a" && !closing -> link = Href.find(tag)?.groupValues?.get(1)?.let(::resolveLink)
                    name == "a" -> link = null
                    (name == "strong" || name == "b") && !closing -> boldDepth++
                    name == "strong" || name == "b" -> boldDepth = maxOf(0, boldDepth - 1)
                    name == "br" -> append("\n")
                }
                index = close + 1
            } else {
                val next = html.indexOf('<', index + 1).let { if (it < 0) html.length else it }
                append(decode(html.substring(index, next)).replace(Space, " "))
                index = next
            }
        }
        // Tidy the whitespace that source indentation leaves around breaks and edges.
        for (i in runs.indices) runs[i] = runs[i].copy(text = runs[i].text.replace(BreakSpace, "\n"))
        if (runs.isNotEmpty()) {
            runs[0] = runs[0].copy(text = runs[0].text.trimStart())
            runs[runs.lastIndex] = runs.last().copy(text = runs.last().text.trimEnd())
        }
        return runs.filter { it.text.isNotEmpty() }
    }

    /** Pairs "18. října od 16:00 do 19:00 v Obecním domě pro kurz M04" with the "Prodej vstupenek začíná 12. 10. v 18.00" that follows it. */
    fun parseTicketSales(html: String): List<VavruskaRawTicketSale> {
        val pageTitle = PageTitle.find(html)?.groupValues?.get(1)?.let { collapse(decode(strip(it))) }
        val text = visibleText(slice(html, "</h1>", listOf("<div class=\"values-strip", "<footer")))
        val events = EventLine.findAll(text).toList()
        val sales = SaleLine.findAll(text).toList()
        return events.mapIndexedNotNull { index, event ->
            val nextStart = events.getOrNull(index + 1)?.range?.first ?: text.length
            val sale = sales.firstOrNull { it.range.first > event.range.last && it.range.first < nextStart } ?: return@mapIndexedNotNull null
            val group = event.groupValues
            VavruskaRawTicketSale(
                eventDay = group[1].toInt(), eventMonth = Months.indexOf(group[2].lowercase()) + 1, startHour = group[3].toInt(), startMinute = group[4].toInt(),
                endHour = group[5].toIntOrNull(), endMinute = group[6].toIntOrNull(), venue = collapse(group[7]).takeIf { it.isNotEmpty() },
                courseCodes = CodeOnly.findAll(group[8]).map { it.value }.toList(),
                saleDay = sale.groupValues[1].toInt(), saleMonth = sale.groupValues[2].toInt(), saleYear = sale.groupValues[3].toIntOrNull(),
                saleHour = sale.groupValues[4].toInt(), saleMinute = sale.groupValues[5].toInt(), pageTitle = pageTitle,
            ).takeIf { it.eventMonth in 1..12 }
        }
    }

    /** `/vstupenky/prodlouzena-19-10-2026-od-2000/` links on the ticket listing. */
    fun parseTicketLinks(html: String): List<VavruskaTicketLink> = TicketLink.findAll(html).mapNotNull { match ->
        val url = VavruskaLinks.page(match.groupValues[1]) ?: return@mapNotNull null
        val time = match.groupValues[5].toInt()
        VavruskaTicketLink(url, match.groupValues[2].toInt(), match.groupValues[3].toInt(), match.groupValues[4].toInt(), time / 100, time % 100)
    }.distinct().toList()

    fun visibleText(html: String): String = decode(strip(html.replace(Hidden, " ").replace(BlockEnd, "\n").replace(LineBreak, " ")))
        .split('\n').map(::collapse).filter { it.isNotEmpty() }.joinToString("\n")

    private fun strip(html: String) = html.replace(Tags, "")
    private fun collapse(text: String) = text.replace(Space, " ").trim()
    private fun decode(text: String) = Normalizer.normalize(KomensText.decodeEntities(text), Normalizer.Form.NFC)

    private fun resolveLink(href: String): String? {
        val url = VavruskaLinks.page(decode(href).trim()) ?: return null
        return url.takeIf { it.startsWith("https://") || it.startsWith("http://") || it.startsWith("mailto:") }
    }

    private fun slice(html: String, marker: String, endMarkers: List<String>): String {
        val start = html.indexOf(marker).takeIf { it >= 0 } ?: return html
        val tail = html.substring(start + marker.length)
        val end = endMarkers.mapNotNull { tail.indexOf(it).takeIf { index -> index >= 0 } }.minOrNull() ?: tail.length
        return tail.substring(0, end)
    }

    /** FNV-1a 64-bit: the same fingerprint on every launch and on iOS. */
    fun fnv1a(text: String): String {
        var hash = -0x340d631b7bdddcdbL
        for (byte in text.toByteArray(Charsets.UTF_8)) {
            hash = hash xor (byte.toLong() and 0xff)
            hash *= 0x100000001b3L
        }
        return java.lang.Long.toUnsignedString(hash, 16)
    }
}
