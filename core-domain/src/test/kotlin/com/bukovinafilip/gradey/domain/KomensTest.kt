package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.KomensMessageDTO
import com.bukovinafilip.gradey.model.KomensSenderDTO
import com.bukovinafilip.gradey.model.KomensSnapshot
import com.bukovinafilip.gradey.model.SchoolAttachmentDTO
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class KomensTest {
    @Test fun schoolHtmlBecomesPlainParagraphsAndLists() {
        val html = "<div>Dobrý den,</div><div>ve čtvrtek jedeme na exkurzi do <b>Národního technického muzea</b>.\n   Sraz je v 7:45.</div>" +
            "<ul><li>vstupné 120&nbsp;Kč</li><li>svačina &amp; pití</li></ul><div>Program najdete v příloze.</div><div>E. Svobodová</div>"
        assertThat(KomensText.plainText(html)).isEqualTo(
            "Dobrý den,\n\nve čtvrtek jedeme na exkurzi do Národního technického muzea. Sraz je v 7:45.\n\n• vstupné 120 Kč\n• svačina & pití\n\nProgram najdete v příloze.\n\nE. Svobodová",
        )
        assertThat(KomensText.plainText("První řádek<br>druhý<BR/>třetí")).isEqualTo("První řádek\ndruhý\ntřetí")
        // Text without tags keeps its own line breaks and only has its entities decoded.
        assertThat(KomensText.plainText("  Omluvenka:\r\nnemoc 12.&ndash;13. 10.\n\n\n\nDěkuji  ")).isEqualTo("Omluvenka:\nnemoc 12.–13. 10.\n\nDěkuji")
        assertThat(KomensText.plainText("<p>a</p><script>alert('x')</script><style>p { color: red }</style><!-- note --><p>b</p>")).isEqualTo("a\n\nb")
        assertThat(KomensText.plainText("<table><tr><td>Po</td><td>8:00</td></tr><tr><th>Út</th><td>9:00</td></tr></table>")).isEqualTo("Po 8:00\n\nÚt 9:00")
        assertThat(KomensText.plainText("")).isEmpty()
        // "2 < 3" is not a tag.
        assertThat(KomensText.plainText("2 < 3 a 5 > 4")).isEqualTo("2 < 3 a 5 > 4")
    }

    @Test fun linksKeepTheirAddressWhenTheTextDoesNotShowIt() {
        assertThat(KomensText.plainText("Více na <a href=\"https://www.example.com/knihovna\">webu školy</a>."))
            .isEqualTo("Více na webu školy (https://www.example.com/knihovna).")
        assertThat(KomensText.plainText("<a href='https://example.com/a?b=1&amp;c=2'>https://example.com/a?b=1&amp;c=2</a>")).isEqualTo("https://example.com/a?b=1&c=2")
        assertThat(KomensText.plainText("<a class=\"x\" href=\"mailto:skola@example.com\"><b>Napište nám</b></a>")).isEqualTo("Napište nám (skola@example.com)")
        assertThat(KomensText.plainText("<a href=\"https://example.com\"><img src=\"x.png\"></a>")).isEqualTo("https://example.com")
    }

    @Test fun entitiesAreDecodedAndUnknownOnesAreLeftAlone() {
        assertThat(KomensText.decodeEntities("T&#345;&iacute;dn&iacute; sch&#x16F;zky &ndash; &bdquo;&uacute;ter&yacute;&ldquo;")).isEqualTo("Třídní schůzky – „úterý“")
        assertThat(KomensText.decodeEntities("R&D &unknown; &#0; &#xD800; &#99999999; AT&T;")).isEqualTo("R&D &unknown; \u0000 &#xD800; &#99999999; AT&T;")
        assertThat(KomensText.decodeEntities("&#128075; &amp;amp;")).isEqualTo("\uD83D\uDC4B &amp;")
        assertThat(KomensText.decodeEntities("bez entit")).isEqualTo("bez entit")
    }

    @Test fun aMessageNamesItselfBySubjectOrFirstLine() {
        val titled = KomensMessage.from(KomensMessageDTO(
            id = "m1", title = " T&#345;&iacute;dn&iacute; sch&uring;zky ", text = "<p>V úterý od 17:00.</p><p>Učebna 12.</p>", sentDate = "2026-11-19T08:15:00+01:00",
            sender = KomensSenderDTO(name = "  "), relevantName = " Ředitelství ", read = false,
            attachments = listOf(SchoolAttachmentDTO("a1", " program.pdf ", size = 0)),
        ), KomensBox.RECEIVED)
        assertThat(titled.heading).isEqualTo("Třídní schůzky")
        assertThat(titled.preview).isEqualTo("V úterý od 17:00. Učebna 12.")
        // A blank sender name falls back to the name the school says the message concerns.
        assertThat(titled.senderName).isEqualTo("Ředitelství")
        assertThat(titled.sentAt).isEqualTo(Instant.parse("2026-11-19T07:15:00Z"))
        assertThat(titled.isRead).isFalse()
        assertThat(titled.attachments).containsExactly(SchoolAttachment("a1", "program.pdf", null))
        assertThat(titled.id).isNotEqualTo(titled.copy(box = KomensBox.NOTICEBOARD).id)

        // Excuse notes have no subject: the first line is the heading and the rest the preview.
        val untitled = KomensMessage.from(KomensMessageDTO(id = "m2", title = "  ", text = "Omluvenka: nemoc<br>12.–13. 10.<br>Děkuji"), KomensBox.RECEIVED)
        assertThat(untitled.title).isNull()
        assertThat(untitled.heading).isEqualTo("Omluvenka: nemoc")
        assertThat(untitled.preview).isEqualTo("12.–13. 10. Děkuji")
        // Missing Read means read; no text and no subject leaves the heading to the screen.
        assertThat(untitled.isRead).isTrue()
        val empty = KomensMessage.from(KomensMessageDTO(id = "m3"), KomensBox.NOTICEBOARD)
        assertThat(empty.heading).isNull()
        assertThat(empty.preview).isEmpty()
        assertThat(empty.sentAt).isNull()
    }

    @Test fun sentTimesAreReadWithAnOffsetWithoutOneOrAsABareDay() {
        assertThat(KomensMessage.sentInstant("2026-11-19T08:15:00+01:00")).isEqualTo(Instant.parse("2026-11-19T07:15:00Z"))
        assertThat(KomensMessage.sentInstant("2026-11-19T08:15:00.123Z")).isEqualTo(Instant.parse("2026-11-19T08:15:00.123Z"))
        // Without an offset the time is the school's, in Prague.
        assertThat(KomensMessage.sentInstant("2026-06-19T08:15:00")).isEqualTo(Instant.parse("2026-06-19T06:15:00Z"))
        assertThat(KomensMessage.sentInstant("2026-11-19")).isEqualTo(Instant.parse("2026-11-18T23:00:00Z"))
        assertThat(KomensMessage.sentInstant("yesterday")).isNull()
        assertThat(KomensMessage.sentInstant(null)).isNull()
    }

    @Test fun listsAreNewestFirstWithoutHiddenMessagesAndCountEachUnreadMessageOnce() {
        fun message(id: String, sent: String?, read: Boolean? = true, hidden: Boolean? = null) = KomensMessageDTO(id = id, title = id, sentDate = sent, read = read, hidden = hidden)
        val snapshot = KomensSnapshot(
            received = listOf(
                message("old", "2026-11-01T09:00:00+01:00", read = false), message("undated-b", null), message("new", "2026-11-19T08:00:00+01:00", read = false),
                message("hidden", "2026-11-20T08:00:00+01:00", read = false, hidden = true), message("undated-a", null), message("pinned", "2026-11-10T08:00:00+01:00", read = false),
            ),
            noticeboard = listOf(message("pinned", "2026-11-10T08:00:00+01:00", read = false), message("board", "2026-11-05T08:00:00+01:00")),
        )
        assertThat(KomensList.messages(snapshot, KomensBox.RECEIVED).map { it.messageID }).containsExactly("new", "pinned", "old", "undated-b", "undated-a").inOrder()
        assertThat(KomensList.messages(snapshot, KomensBox.NOTICEBOARD).map { it.messageID }).containsExactly("pinned", "board").inOrder()
        // "pinned" is in both boxes but is one unread message; the hidden one does not count.
        assertThat(KomensList.unreadCount(snapshot)).isEqualTo(3)
        assertThat(KomensList.unreadCount(null)).isEqualTo(0)
        assertThat(KomensList.messages(null, KomensBox.RECEIVED)).isEmpty()

        // A message opened here stays read even when a list loaded meanwhile still says unread.
        assertThat(KomensList.unreadCount(snapshot, readIDs = setOf("pinned"))).isEqualTo(2)
        assertThat(KomensList.messages(snapshot, KomensBox.NOTICEBOARD, readIDs = setOf("pinned")).first().isRead).isTrue()
        val marked = snapshot.markingRead("pinned")
        assertThat(marked.received.first { it.id == "pinned" }.read).isTrue()
        assertThat(marked.noticeboard.first { it.id == "pinned" }.read).isTrue()
        assertThat(KomensList.unreadCount(marked)).isEqualTo(2)
    }

    @Test fun demoMessagesFollowTheClockAndSurviveTheCache() {
        val now = ZonedDateTime.of(2026, 11, 20, 15, 0, 0, 0, ZoneId.of("Europe/Prague"))
        val snapshot = KomensDemoData.snapshot(now)
        val received = KomensList.messages(snapshot, KomensBox.RECEIVED)
        assertThat(received.map { it.messageID }).containsExactly("komens-trip", "komens-meeting", "komens-math", "komens-excuse").inOrder()
        assertThat(received.first().sentAt).isEqualTo(Instant.parse("2026-11-20T07:00:00Z"))
        assertThat(received.first().body).contains("• vstupné 120 Kč")
        assertThat(received.last().heading).isEqualTo("Omluvenka: nemoc 12.–13. 10. Absence byla omluvena.")
        assertThat(KomensList.unreadCount(snapshot)).isEqualTo(2)
        assertThat(KomensList.messages(snapshot, KomensBox.NOTICEBOARD).last().body).isEqualTo(
            "Školní knihovna má nově otevřeno každý den od 7:30 do 15:00. Více na webu školy (https://www.example.com/knihovna).",
        )
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = true }
        assertThat(json.decodeFromString(KomensSnapshot.serializer(), json.encodeToString(KomensSnapshot.serializer(), snapshot))).isEqualTo(snapshot)
    }
}
