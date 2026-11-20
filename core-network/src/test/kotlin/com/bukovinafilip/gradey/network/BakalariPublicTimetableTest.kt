package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.FriendTimetableException
import com.bukovinafilip.gradey.domain.PublicTimetableTerm
import com.bukovinafilip.gradey.model.PublicTimetableClass
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class BakalariPublicTimetableTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun teardown() { server.shutdown() }

    private val monday = LocalDate.parse("2026-11-16")
    private val classesPage = """<html><select id="other"><option value="X">no</option></select>
        <select class="form" id="selectedClass" name="c"><option value="">Vyberte</option>
        <option value="ZV">1.A</option><option value=" 2B " selected>  2.B
        (dívky) </option><option value="Q&amp;A">3.&nbsp;C</option></select></html>"""

    private fun page(data: String) = "<html><script>var x = 1; const timetableData = $data; const other = {\"a\": 1};</script></html>"

    private val timetableData = """{"Hours":[{"Caption":"1","BeginTime":"8:00","EndTime":"8:45"},{"Caption":"2","BeginTime":"8:55","EndTime":"9:40"}],
      "Days":[
        {"DayAbbrev":"po","Hours":[
          {"Begin":"08:00","End":"08:45","BeginTimeValue":480,"Atoms":[
            {"Type":"atom","SubjectText":"Matematika","SubjectAbbrev":"M","Teacher":"No","TeacherFullname":"Jan Novák","Room":"12","RoomFullName":"Učebna 12","Theme":"Rovnice {x};"},
            {"Type":"atom","SubjectText":"Angličtina","SubjectAbbrev":"AJ","Teacher":"Sv","GroupsNames":"AJ1, NJ","CycleText":" Lichý ","HasChanged":true,"InfoChangeCode":4,"ChangeInfo":" Změna místnosti "}]},
          {"Begin":"7:00","End":"7:45","Atoms":[
            {"Type":"Removed","ChangeInfo":"Vyjmuto z rozvrhu (ČJL, Svobodová Eva)","HasChanged":true}]},
          {"Begin":"8:55","End":"9:40","Atoms":[
            {"Type":"atom","SubjectAbbrev":"TV","HasChanged":true,"InfoChangeCode":1,"TooltipDetails":"{\"infoChangeCode\":\"Added\",\"x\":\"}\"}"}]}]},
        {"DayAbbrev":"čt","Hours":[{"Begin":"8:00","End":"8:45","HourText":" Exkurze ","Atoms":[]}]},
        {"DayAbbrev":"Út","DayOff":true,"DayOffName":"Státní svátek"},
        {"Hours":[]}
      ]}"""

    @Test fun classesAreReadFromTheClassPickerOnly() {
        assertEquals(
            listOf(PublicTimetableClass("ZV", "1.A"), PublicTimetableClass("2B", "2.B (dívky)"), PublicTimetableClass("Q&A", "3. C")),
            BakalariPublicTimetableParser.classes(classesPage),
        )
        assertTrue(BakalariPublicTimetableParser.classes("<html>Přihlášení</html>").isEmpty())
    }

    @Test fun thePagesEmbeddedDataBecomesTheSameTimetableTheApiSends() {
        val response = BakalariPublicTimetableParser.timetable(page(timetableData), PublicTimetableTerm.ACTUAL, monday)!!
        // The header's hours, plus the zero lesson the header does not list.
        assertEquals(listOf("1" to "8:00", "2" to "8:55", "3" to "7:00"), response.hours.map { it.id to it.beginTime })
        assertEquals("", response.hours[2].caption)

        val mondayAtoms = response.days[0].atoms
        assertEquals("2026-11-16", response.days[0].date)
        assertEquals(listOf("1", "1", "3", "2"), mondayAtoms.map { it.hourID })
        val maths = mondayAtoms[0]
        assertEquals("Matematika", response.subjects.first { it.id == maths.subjectID }.name)
        assertEquals("Jan Novák", response.teachers.first { it.id == maths.teacherID }.name)
        assertEquals("Učebna 12", response.rooms.first { it.id == maths.roomID }.name)
        assertEquals("Rovnice {x};", maths.theme)
        assertNull(maths.change)

        val english = mondayAtoms[1]
        assertEquals(listOf("AJ1", "NJ"), english.groupIDs.map { id -> response.groups.first { it.id == id }.abbrev })
        assertEquals("Lichý", response.cycles.first { it.id == english.cycleIDs.single() }.name)
        assertEquals("RoomChanged", english.change?.changeType)
        assertEquals("Změna místnosti", english.change?.description)

        // A removed lesson names its subject and teacher only in the change text.
        val removed = mondayAtoms[2]
        assertEquals("Removed", removed.change?.changeType)
        assertEquals("ČJL", response.subjects.first { it.id == removed.subjectID }.abbrev)
        assertEquals("Svobodová Eva", response.teachers.first { it.id == removed.teacherID }.name)
        // The tooltip's own code wins over the number.
        assertEquals("Added", mondayAtoms[3].change?.changeType)

        // Thursday is placed by its name, not its position; an hour with only a text is a class-wide event.
        assertEquals(4, response.days[1].dayOfWeek)
        assertEquals("2026-11-19", response.days[1].date)
        assertEquals("Event", response.days[1].atoms.single().change?.changeType)
        assertEquals("Exkurze", response.subjects.first { it.id == response.days[1].atoms.single().subjectID }.name)
        assertEquals("Holiday", response.days[2].dayType)
        assertEquals("Státní svátek", response.days[2].dayDescription)
        assertEquals(2, response.days[2].dayOfWeek)
        // A day without a name falls back to its position.
        assertEquals(4, response.days[3].dayOfWeek)

        // An empty Saturday row, as real pages have, is left out; a Saturday with lessons stays.
        val withSaturday = timetableData.replace("{\"Hours\":[]}", "{\"DayAbbrev\":\"so\",\"Hours\":[]}")
        assertEquals(listOf(1, 4, 2), BakalariPublicTimetableParser.timetable(page(withSaturday), PublicTimetableTerm.ACTUAL, monday)!!.days.map { it.dayOfWeek })
        val saturdaySchool = timetableData.replace("{\"Hours\":[]}", "{\"DayAbbrev\":\"so\",\"Hours\":[{\"Begin\":\"8:00\",\"End\":\"8:45\",\"Atoms\":[{\"SubjectAbbrev\":\"TV\"}]}]}")
        assertEquals(listOf(1, 4, 2, 6), BakalariPublicTimetableParser.timetable(page(saturdaySchool), PublicTimetableTerm.ACTUAL, monday)!!.days.map { it.dayOfWeek })

        // The permanent timetable has no dates.
        assertTrue(BakalariPublicTimetableParser.timetable(page(timetableData), PublicTimetableTerm.PERMANENT, monday)!!.days.all { it.date.isEmpty() })
        assertNull(BakalariPublicTimetableParser.timetable("<html>no data</html>", PublicTimetableTerm.ACTUAL, monday))
        assertNull(BakalariPublicTimetableParser.timetable(page("{\"Days\": \"broken\"}"), PublicTimetableTerm.ACTUAL, monday))
    }

    @Test fun embeddedJsonEndsAtItsOwnClosingBraceEvenWithBracesInText() {
        assertEquals("""{"a":"}\"{","b":{"c":1}}""", BakalariPublicTimetableParser.embeddedJSON("""x = {"a":"}\"{","b":{"c":1}}; y = {}""", "x ="))
        assertNull(BakalariPublicTimetableParser.embeddedJSON("""x = {"a": 1""", "x ="))
        assertNull(BakalariPublicTimetableParser.embeddedJSON("nothing here", "x ="))
        assertEquals(listOf(1, 2, 4, 5, 7), listOf("Po", "úterý", "Čt.", "pá 20.11.", "Sunday").map(BakalariPublicTimetableParser::weekday))
        assertNull(BakalariPublicTimetableParser.weekday("zítra"))
    }

    @Test fun pagesAreFetchedWithoutCredentialsFromThePublicPaths() = runTest {
        val client = BakalariPublicTimetableNetworkClient()
        server.enqueue(MockResponse().setBody(classesPage))
        assertEquals(3, client.fetchClasses(server.url("/bakalari/").toString()).size)
        val classes = server.takeRequest()
        assertEquals("/bakalari/Timetable/Public", classes.path)
        assertNull(classes.getHeader("Authorization"))

        server.enqueue(MockResponse().setBody(page(timetableData)))
        val response = client.fetchTimetable(server.url("/bakalari").toString(), "Q&A/1", PublicTimetableTerm.NEXT, monday)
        assertEquals("/bakalari/Timetable/Public/Next/Class/Q&A%2F1", server.takeRequest().path)
        assertEquals(4, response.days.size)
    }

    @Test fun aSchoolThatDoesNotPublishItsTimetableSaysSo() = runTest {
        val client = BakalariPublicTimetableNetworkClient()
        fun reason(block: suspend () -> Unit) = (kotlinx.coroutines.runBlocking { runCatching { block() }.exceptionOrNull() } as FriendTimetableException)

        // Bakaláři sends visitors to its login page.
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/bakalari/Login?ReturnUrl=x"))
        server.enqueue(MockResponse().setBody("<html>Přihlášení</html>"))
        assertEquals(FriendTimetableException.Reason.NOT_PUBLISHED, reason { client.fetchClasses(server.url("/bakalari/").toString()) }.reason)
        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(FriendTimetableException.Reason.NOT_PUBLISHED, reason { client.fetchClasses(server.url("/").toString()) }.reason)
        server.enqueue(MockResponse().setBody("<html>no picker</html>"))
        assertEquals(FriendTimetableException.Reason.NOT_PUBLISHED, reason { client.fetchClasses(server.url("/").toString()) }.reason)
        server.enqueue(MockResponse().setResponseCode(503))
        val failure = reason { client.fetchTimetable(server.url("/").toString(), "ZV", PublicTimetableTerm.ACTUAL, monday) }
        assertEquals(FriendTimetableException.Reason.HTTP_STATUS, failure.reason)
        assertEquals(503, failure.status)
        server.enqueue(MockResponse().setBody("<html>redesigned</html>"))
        assertEquals(FriendTimetableException.Reason.UNEXPECTED_PAGE, reason { client.fetchTimetable(server.url("/").toString(), "ZV", PublicTimetableTerm.ACTUAL, monday) }.reason)
    }
}
