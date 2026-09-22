package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.SchoolLoginStep
import com.bukovinafilip.gradey.model.EduPageSessionData
import com.bukovinafilip.gradey.model.EduPageSubjectProfile
import com.bukovinafilip.gradey.model.SchoolProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

class EduPageClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpEduPageClient
    private val baseURL get() = server.url("/").toString().trimEnd('/')

    @Before fun setUp() {
        server = MockWebServer().apply { start() }
        client = OkHttpEduPageClient()
    }

    @After fun tearDown() { server.shutdown() }

    @Test fun loginUsesCsrfCookieAndPreservesCredentialsAndStudentProfile() = runBlocking {
        enqueueLoginPage()
        server.enqueue(MockResponse().setBody(studentHTML))

        val result = client.beginLogin(baseURL, " student ", "p&ss=word") as SchoolLoginStep.SignedIn

        assertThat(result.session.provider).isEqualTo(SchoolProvider.EDU_PAGE)
        assertThat(result.session.eduPage?.sessionID).isEqualTo("session-123")
        assertThat(result.session.eduPage?.password).isEqualTo("p&ss=word")
        assertThat(result.session.eduPage?.activeStudent?.fullName).isEqualTo("Alex Student")
        assertThat(result.session.eduPage?.activeStudent?.className).isEqualTo("2.A")
        assertThat(server.takeRequest().path).isEqualTo("/login/?cmd=MainLogin")
        val request = server.takeRequest()
        assertThat(request.getHeader("Cookie")).isEqualTo("PHPSESSID=session-123")
        assertThat(request.body.readUtf8()).isEqualTo("csrfauth=csrf-value&username=student&password=p%26ss%3Dword")
    }

    @Test fun invalidCredentialsAreReportedWithoutReturningAnEmptyAccount() = runBlocking {
        enqueueLoginPage()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login/?bad=1"))
        server.enqueue(MockResponse().setBody("Bad credentials"))

        val failure = runCatching { client.beginLogin(baseURL, "student", "wrong") }.exceptionOrNull()

        assertThat((failure as EduPageAuthenticationException).reason).isEqualTo(EduPageAuthFailure.BAD_CREDENTIALS)
    }

    @Test fun parentLoginOnlyOffersLinkedStudentsAndSelectsOneExplicitly() = runBlocking {
        enqueueLoginPage()
        server.enqueue(MockResponse().setBody(parentHTML))
        server.enqueue(MockResponse().setBody("OK"))

        val result = client.beginLogin(baseURL, "parent", "secret") as SchoolLoginStep.StudentSelection
        assertThat(result.students.map { it.id }).containsExactly("Student1", "Student2")
        val session = client.selectStudent("Student2")

        assertThat(session.activeStudent?.fullName).isEqualTo("Jamie Student")
        server.takeRequest()
        server.takeRequest()
        assertThat(server.takeRequest().path).isEqualTo("/login/switchchild?studentid=2")
        assertThat(runCatching { client.selectStudent("Student1") }.exceptionOrNull())
            .isInstanceOf(EduPageAuthenticationException::class.java)
    }

    @Test fun twoFactorCodeCompletesThePendingLogin() = runBlocking {
        enqueueTwoFactor()
        assertThat(client.beginLogin(baseURL, "student", "secret")).isInstanceOf(SchoolLoginStep.TwoFactor::class.java)
        server.enqueue(MockResponse().setBody("<script>window.location = gu;</script>"))
        server.enqueue(MockResponse().setBody(studentHTML))

        val result = client.completeTwoFactor(" 123456 ")

        assertThat(result).isInstanceOf(SchoolLoginStep.SignedIn::class.java)
        repeat(3) { server.takeRequest() }
        val request = server.takeRequest()
        assertThat(request.body.readUtf8()).contains("t2fasec=123456")
        assertThat(server.takeRequest().path).isEqualTo("/user")
    }

    @Test fun deviceApprovalCanBeResentCheckedAndCompleted() = runBlocking {
        enqueueTwoFactor()
        client.beginLogin(baseURL, "student", "secret")
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        client.resendTwoFactorNotification()
        server.enqueue(MockResponse().setBody("""{"status":"pending"}"""))
        assertThat(client.isTwoFactorConfirmed()).isFalse()
        server.enqueue(MockResponse().setBody("""{"status":"ok","data":"approved-token"}"""))
        assertThat(client.isTwoFactorConfirmed()).isTrue()
        server.enqueue(MockResponse().setBody("<script>window.location = gu;</script>"))
        server.enqueue(MockResponse().setBody(studentHTML))

        assertThat(client.completeApprovedTwoFactor()).isInstanceOf(SchoolLoginStep.SignedIn::class.java)
        repeat(6) { server.takeRequest() }
        assertThat(server.takeRequest().body.readUtf8()).contains("t2fasec=approved-token")
    }

    @Test fun expiredSessionRestoresThroughStoredCredentials() = runBlocking {
        server.enqueue(MockResponse().setBody("<form><input name='username'><input name='password'></form>"))
        enqueueLoginPage()
        server.enqueue(MockResponse().setBody(studentHTML))

        val restored = client.restore(session(), baseURL)

        assertThat(restored.sessionID).isEqualTo("session-123")
        assertThat(server.takeRequest().getHeader("Cookie")).isEqualTo("PHPSESSID=stored-session")
        assertThat(server.takeRequest().path).isEqualTo("/login/?cmd=MainLogin")
        assertThat(server.takeRequest().body.readUtf8()).contains("password=secret")
    }

    @Test fun authenticatedRequestsUseTheProvidedAccountAndDetectExpiry() = runBlocking {
        enqueueLoginPage()
        server.enqueue(MockResponse().setBody(studentHTML))
        client.beginLogin(baseURL, "student", "secret")
        server.enqueue(MockResponse().setResponseCode(401))

        val error = runCatching { client.fetchMarks(baseURL, session()) }.exceptionOrNull()

        assertThat((error as EduPageAuthenticationException).reason).isEqualTo(EduPageAuthFailure.SESSION_EXPIRED)
        repeat(2) { server.takeRequest() }
        assertThat(server.takeRequest().getHeader("Cookie")).isEqualTo("PHPSESSID=stored-session")
    }

    @Test fun timetableLoadsTheRequestedWeekAndExtractsTokenWithoutQuerySuffix() = runBlocking {
        server.enqueue(MockResponse().setBody("gpid=2 then gpid=8&gsh=secure-token&next=1"))
        server.enqueue(MockResponse().setBody("""{"dates":{"2026-09-22":{"plan":[]}}}"""))

        val result = client.fetchTimetable(baseURL, session(), "2026-09-21")

        assertThat(result.days.single().date).isEqualTo("2026-09-22")
        assertThat(server.takeRequest().path).isEqualTo("/dashboard/eb.php?mode=ttday")
        val request = server.takeRequest()
        assertThat(request.path).isEqualTo("/gcall")
        val body = request.body.readUtf8()
        assertThat(body).contains("gpid=9&gsh=secure-token")
        assertThat(body).contains("date=2026-09-21&dateto=2026-09-27")
    }

    @Test fun foreignRedirectNeverReceivesTheSessionCookie() = runBlocking {
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "https://foreign.example/user"))

        val error = runCatching { client.fetchMarks(baseURL, session()) }.exceptionOrNull()

        assertThat((error as EduPageAuthenticationException).reason).isEqualTo(EduPageAuthFailure.MALFORMED_RESPONSE)
        assertThat(server.requestCount).isEqualTo(1)
    }

    private fun enqueueLoginPage() {
        server.enqueue(MockResponse().addHeader("Set-Cookie", "PHPSESSID=session-123; Path=/; HttpOnly")
            .setBody("<input value='csrf-value' name='csrfauth'>"))
    }

    private fun enqueueTwoFactor() {
        enqueueLoginPage()
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/login/twofactor"))
        server.enqueue(MockResponse().setBody("""
            <input name="csrfauth" value="two-csrf"><input name="au" value="auth-token"><input name="gu" value="/user">
        """.trimIndent()))
    }

    private fun session() = EduPageSessionData("stored-session", "student", "secret", "gsec", "Student1")

    private val studentHTML = """
        <script>ASC.gsechash="gsec"; init({"userid":"Student1","schoolName":"Test School","dbi":{
          "classes":{"a":{"short":"2.A"}},
          "students":{"1":{"firstname":"Alex","lastname":"Student","classid":"a"}},
          "subjects":{"math":{"name":"Mathematics","short":"M"}}
        }});</script>
    """.trimIndent()

    private val parentHTML = """
        <script>init({"userid":"Parent9","dbi":{"students":{
          "1":{"firstname":"Alex","lastname":"Student","parent1":"9"},
          "2":{"firstname":"Jamie","lastname":"Student","parent2":"9"},
          "3":{"firstname":"Other","lastname":"Student","parent1":"10"}
        }}});</script>
    """.trimIndent()
}

class EduPageResponseParserTest {
    @Test fun marksKeepFractionalWeightsPointsAndUnsupportedTypesDistinct() {
        val html = """<script>grades({
          "vsetkyZnamky":[
            {"udalostid":"1","data":"2 (Great effort)","datum":"2026-09-22"},
            {"udalostid":"2","data":"18"},
            {"udalostid":"3","data":"A"}
          ],
          "vsetkyUdalosti":{"edupage":{
            "1":{"PredmetID":"math","p_typ_udalosti":"1","p_vaha":"5","p_meno":"Quiz"},
            "2":{"PredmetID":"math","p_typ_udalosti":"2","p_vaha":"20"},
            "3":{"PredmetID":"art","p_typ_udalosti":"4"}
          }}
        });</script>"""

        val result = EduPageResponseParser.marks(html, listOf(EduPageSubjectProfile("math", "Mathematics", "M")))
        val math = result.subjects.first { it.id == "math" }

        assertThat(math.marks.first().weight).isEqualTo(0.25)
        assertThat(math.marks.first().theme).isEqualTo("Great effort")
        assertThat(math.marks[1].isPoints).isTrue()
        assertThat(math.marks[1].maxPoints).isEqualTo(20)
        assertThat(result.subjects.first { it.id == "art" }.marks.single().type).isEqualTo("unsupported")
        assertThat(math.markPredictionEnabled).isFalse()
    }

    @Test fun attendanceKeepsUnknownThresholdAndSeparatesUnresolvedHours() {
        val html = """{"dateStats":{"1":{"2026-09-22":{"absent":6,"excused":3,"unexcused":1,"late":2},"2026-09-23":{"absent":0}}}}"""

        val result = EduPageResponseParser.absences(html, "Student1")

        assertThat(result.percentageThreshold).isNull()
        assertThat(result.absencesPerSubject).isEmpty()
        assertThat(result.absences.single().unsolved).isEqualTo(2)
        assertThat(result.absences.single().ok).isEqualTo(3)
        assertThat(result.absences.single().missed).isEqualTo(1)
        assertThat(result.absences.single().late).isEqualTo(2)
    }

    @Test fun timetableRetainsGroupsCanceledLessonsAndDifferentTimesForSamePeriod() {
        val html = """{"dates":{
          "2026-09-21":{"plan":[{"type":"lesson","uniperiod":1,"starttime":"8:00","endtime":"8:45","subjectid":"m","groupnames":["Group A"],"teacherids":[4],"teachernames":["Teacher"],"classroomnames":["Room 1"]}]},
          "2026-09-22":{"plan":[{"type":"absent","uniperiod":1,"starttime":"9:00","endtime":"9:45","subjectid":"m"}]}
        }}"""

        val result = EduPageResponseParser.timetable(html, listOf(EduPageSubjectProfile("m", "Mathematics", "M")))

        assertThat(result.hours).hasSize(2)
        assertThat(result.days.first().atoms.single().groupIDs).containsExactly("Group A")
        assertThat(result.groups.single().abbrev).isEqualTo("Group A")
        assertThat(result.days.last().atoms.single().change?.changeType).isEqualTo("Canceled")
        assertThat(result.subjects.single().name).isEqualTo("Mathematics")
        assertThat(result.teachers.single().name).isEqualTo("Teacher")
    }

    @Test fun parserHandlesBracesAndEscapedQuotesInsideEmbeddedJsonStrings() {
        val html = """function(){broken();} init({"userid":"Student1","schoolName":"School {with} \"quotes\"","dbi":{}});"""

        assertThat(EduPageResponseParser.session(html, "student", "secret", "session").schoolName)
            .isEqualTo("School {with} \"quotes\"")
    }
}
