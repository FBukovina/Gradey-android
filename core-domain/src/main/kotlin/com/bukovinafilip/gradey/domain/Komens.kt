package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.KomensMessageDTO
import com.bukovinafilip.gradey.model.KomensSenderDTO
import com.bukovinafilip.gradey.model.KomensSnapshot
import com.bukovinafilip.gradey.model.SchoolAttachmentDTO
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZonedDateTime

/** The two Komens lists; [path] is the last segment of the school's endpoint. */
enum class KomensBox(val path: String) {
    RECEIVED("received"),
    NOTICEBOARD("noticeboard"),
}

/** A Komens message from the school, read-only apart from being read (iOS `KomensMessage`). */
data class KomensMessage(
    val messageID: String,
    val box: KomensBox,
    val title: String?,
    /** The message as plain paragraphs and lists. */
    val body: String,
    val senderName: String?,
    val sentAt: Instant?,
    val isRead: Boolean,
    val attachments: List<SchoolAttachment>,
) {
    /** The same message can be both received and pinned to the noticeboard. */
    val id: String get() = box.name + "\u001F" + messageID

    /** The title, or the first line for messages without one (excuse notes have none); null when there is neither. */
    val heading: String? get() = title ?: body.lineSequence().firstOrNull { it.isNotBlank() }

    /** The text under the heading in a list row. */
    val preview: String get() {
        val lines = body.lines().filter { it.isNotBlank() }
        return (if (title == null) lines.drop(1) else lines).joinToString(" ")
    }

    companion object {
        fun from(dto: KomensMessageDTO, box: KomensBox): KomensMessage = KomensMessage(
            messageID = dto.id,
            box = box,
            title = dto.title?.let(KomensText::decodeEntities).trimmedOrNull(),
            body = KomensText.plainText(dto.text.orEmpty()),
            senderName = dto.sender?.name.trimmedOrNull() ?: dto.relevantName.trimmedOrNull(),
            sentAt = sentInstant(dto.sentDate),
            isRead = dto.read != false,
            attachments = dto.attachments.orEmpty().map { SchoolAttachment(it.id, it.name.trimmedOrNull() ?: it.id, it.size?.takeIf { size -> size > 0 }) },
        )

        /** "2026-11-19T08:15:00+01:00", a local time without an offset (read as Prague time), or a bare day. */
        fun sentInstant(value: String?): Instant? {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return null
            runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()?.let { return it }
            runCatching { LocalDateTime.parse(text).atZone(Homework.SchoolZone).toInstant() }.getOrNull()?.let { return it }
            return runCatching { LocalDate.parse(text.take(10)).atStartOfDay(Homework.SchoolZone).toInstant() }.getOrNull()
        }

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** What the Komens screens read from a snapshot (iOS `KomensStore`'s read side). */
object KomensList {
    /**
     * A box's messages, newest first, without the ones hidden in the official app.
     *
     * @param readIDs messages opened here; they stay read even if a list loaded meanwhile says otherwise.
     */
    fun messages(snapshot: KomensSnapshot?, box: KomensBox, readIDs: Set<String> = emptySet()): List<KomensMessage> {
        val dtos = when (box) {
            KomensBox.RECEIVED -> snapshot?.received
            KomensBox.NOTICEBOARD -> snapshot?.noticeboard
        }.orEmpty()
        return dtos.asSequence().filter { it.hidden != true }.map { KomensMessage.from(it, box) }
            .map { if (it.messageID in readIDs) it.copy(isRead = true) else it }
            .sortedWith(compareBy<KomensMessage> { it.sentAt == null }.thenByDescending { it.sentAt }.thenByDescending { it.messageID })
            .toList()
    }

    /** Messages that are unread, counting one pinned to the noticeboard only once. */
    fun unreadCount(snapshot: KomensSnapshot?, readIDs: Set<String> = emptySet()): Int {
        snapshot ?: return 0
        return (snapshot.received + snapshot.noticeboard).asSequence()
            .filter { it.hidden != true && it.read == false && it.id !in readIDs }.map { it.id }.toSet().size
    }
}

/**
 * Komens text arrives as HTML from the school's editor. Gradey shows it as plain paragraphs and
 * lists, with link addresses kept so they stay tappable (iOS `KomensText`).
 */
object KomensText {
    private val Tag = Regex("<[a-zA-Z/!]")
    private val Whitespace = Regex("\\s+")
    private val Hidden = Regex("<!--.*?-->|<(script|style)[^>]*>.*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val Link = Regex("<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>(.*?)</a\\s*>", RegexOption.IGNORE_CASE)
    private val LineBreak = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val ListItem = Regex("<li[^>]*>", RegexOption.IGNORE_CASE)
    private val Block = Regex("</?(p|div|ul|ol|table|tr|h[1-6]|blockquote|section|article)(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
    private val CellEnd = Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE)
    private val AnyTag = Regex("<[^>]+>")
    private val Entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z][a-zA-Z0-9]{1,31});")

    fun plainText(html: String): String {
        if (!Tag.containsMatchIn(html)) return tidy(decodeEntities(html))
        // In HTML, line breaks in the source are just spaces.
        var text = html.replace(Whitespace, " ")
        text = text.replace(Hidden, "")
        text = replacingLinks(text)
        text = text.replace(LineBreak, "\n")
        text = text.replace(ListItem, "\n• ")
        text = text.replace(Block, "\n")
        text = text.replace(CellEnd, " ")
        text = text.replace(AnyTag, "")
        return tidy(decodeEntities(text))
    }

    fun decodeEntities(text: String): String {
        if ('&' !in text) return text
        return Entity.replace(text) { match ->
            val name = match.groupValues[1]
            val decoded = when {
                name.startsWith("#x") || name.startsWith("#X") -> name.drop(2).toIntOrNull(16)?.let(::scalar)
                name.startsWith("#") -> name.drop(1).toIntOrNull()?.let(::scalar)
                else -> NamedEntities[name]
            }
            decoded ?: match.value
        }
    }

    private fun scalar(code: Int): String? =
        if (code in 0..0x10FFFF && code !in 0xD800..0xDFFF) String(Character.toChars(code)) else null

    /** `<a href="…">text</a>` keeps the address when the text doesn't show it. */
    private fun replacingLinks(html: String): String = Link.replace(html) { match ->
        val address = decodeEntities(match.groupValues[1]).replace("mailto:", "")
        val label = match.groupValues[2].replace(AnyTag, "").trim()
        when {
            label.isEmpty() -> address
            address in decodeEntities(label) -> label
            else -> "$label ($address)"
        }
    }

    /** Trims each line and keeps at most one empty line between paragraphs. */
    private fun tidy(text: String): String {
        val lines = ArrayList<String>()
        for (line in text.replace("\r\n", "\n").split('\n')) {
            val trimmed = line.trim { it == ' ' || it == '\t' || it == '\u00A0' }
            if (trimmed.isEmpty()) {
                if (lines.isNotEmpty() && lines.last().isNotEmpty()) lines.add("")
            } else lines.add(trimmed)
        }
        return lines.joinToString("\n").trim()
    }

    private val NamedEntities: Map<String, String> = mapOf(
        "nbsp" to " ", "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "bull" to "•", "middot" to "·",
        "bdquo" to "„", "ldquo" to "“", "rdquo" to "”", "sbquo" to "‚", "lsquo" to "‘", "rsquo" to "’",
        "laquo" to "«", "raquo" to "»", "euro" to "€", "times" to "×", "deg" to "°", "copy" to "©", "shy" to "",
        "aacute" to "á", "Aacute" to "Á", "eacute" to "é", "Eacute" to "É", "iacute" to "í", "Iacute" to "Í",
        "oacute" to "ó", "Oacute" to "Ó", "uacute" to "ú", "Uacute" to "Ú", "yacute" to "ý", "Yacute" to "Ý",
        "ecaron" to "ě", "Ecaron" to "Ě", "scaron" to "š", "Scaron" to "Š", "ccaron" to "č", "Ccaron" to "Č",
        "rcaron" to "ř", "Rcaron" to "Ř", "zcaron" to "ž", "Zcaron" to "Ž", "dcaron" to "ď", "Dcaron" to "Ď",
        "tcaron" to "ť", "Tcaron" to "Ť", "ncaron" to "ň", "Ncaron" to "Ň", "uring" to "ů", "Uring" to "Ů",
        "auml" to "ä", "ouml" to "ö", "uuml" to "ü", "Auml" to "Ä", "Ouml" to "Ö", "Uuml" to "Ü", "szlig" to "ß",
        "lcaron" to "ľ", "Lcaron" to "Ľ", "ocirc" to "ô", "Ocirc" to "Ô", "lacute" to "ĺ", "racute" to "ŕ",
    )
}

/** Sample messages and noticeboard for demo mode; the dates follow the clock (iOS `PreviewData.komensSnapshot`). */
object KomensDemoData {
    fun snapshot(now: ZonedDateTime = ZonedDateTime.now(Homework.SchoolZone)): KomensSnapshot {
        fun sent(daysAgo: Long, hour: Int): String = now.toLocalDate().minusDays(daysAgo).atTime(hour, 0).atZone(now.zone).toOffsetDateTime().toString()
        val classTeacher = KomensSenderDTO("t-svobodova", "teacher", "Eva Svobodová")
        val office = KomensSenderDTO("office", "administrator", "Ředitelství školy")
        return KomensSnapshot(
            received = listOf(
                KomensMessageDTO(
                    id = "komens-trip", title = "Exkurze do Národního technického muzea",
                    text = "<div>Dobrý den,</div><div>ve čtvrtek jedeme na exkurzi do <b>Národního technického muzea</b>. Sraz je v 7:45 před školou, návrat kolem 13:00.</div>" +
                        "<ul><li>vstupné 120 Kč</li><li>svačina a pití</li></ul><div>Program najdete v příloze.</div><div>E. Svobodová</div>",
                    sentDate = sent(0, 8), sender = classTeacher,
                    attachments = listOf(SchoolAttachmentDTO("komens-att-trip", "Program exkurze.pdf", "application/pdf", 12_288)), read = false,
                ),
                KomensMessageDTO(
                    id = "komens-meeting", title = "Třídní schůzky",
                    text = "<p>Třídní schůzky budou v úterý od 17:00 v učebně 12. Konzultace s vyučujícími od 17:30.</p>",
                    sentDate = sent(2, 15), sender = office, read = false,
                ),
                KomensMessageDTO(
                    id = "komens-math", title = "Opravná písemka z matematiky",
                    text = "Opravnou písemku z lineárních rovnic můžete psát v pondělí 7. hodinu.<br>Kdo chce, napište mi předem.",
                    sentDate = sent(6, 11), sender = KomensSenderDTO("t-novak", "teacher", "Jan Novák"), read = true,
                ),
                KomensMessageDTO(
                    id = "komens-excuse", title = null, text = "Omluvenka: nemoc 12.–13. 10. Absence byla omluvena.",
                    sentDate = sent(21, 9), sender = classTeacher, read = true,
                ),
            ),
            noticeboard = listOf(
                KomensMessageDTO(
                    id = "komens-board-holidays", title = "Podzimní prázdniny",
                    text = "<div>Podzimní prázdniny jsou ve čtvrtek a pátek 29. a 30. 10. Školní jídelna bude zavřená.</div>",
                    sentDate = sent(12, 10), sender = office, read = true,
                ),
                KomensMessageDTO(
                    id = "komens-board-library", title = "Nová otevírací doba knihovny",
                    text = "<div>Školní knihovna má nově otevřeno každý den od 7:30 do 15:00. Více na <a href=\"https://www.example.com/knihovna\">webu školy</a>.</div>",
                    sentDate = sent(30, 12), sender = office, read = true,
                ),
            ),
        )
    }
}
