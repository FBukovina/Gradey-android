package com.bukovinafilip.gradey.network

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BakalariNetworkClientTest {
    private lateinit var server: MockWebServer
    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun teardown() { server.shutdown() }

    @Test fun permanentTimetableUsesDistinctAuthenticatedEndpointAndAcceptsUndatedDays() = runTest {
        server.enqueue(MockResponse().setBody("""{"Days":[{"DayOfWeek":1,"Atoms":[{"HourId":"1","CycleIds":["odd"]}]}]}"""))
        val response = BakalariNetworkClient().fetchPermanentTimetable(server.url("/bakalari/").toString(), "session-access")
        val request = server.takeRequest()
        assertEquals("/bakalari/api/3/timetable/permanent", request.path)
        assertEquals("Bearer session-access", request.getHeader("Authorization"))
        assertEquals("", response.days.single().date)
        assertEquals(listOf("odd"), response.days.single().atoms.single().cycleIDs)
    }

    @Test fun passwordAndRefreshGrantsAreFormEncodedAndUseTheSchoolPath() = runTest {
        repeat(2) { server.enqueue(MockResponse().setBody("""{"access_token":"access","refresh_token":"refresh","token_type":"Bearer","expires_in":3600}""")) }
        val client = BakalariNetworkClient()
        client.login(server.url("/bakalari/").toString(), "name+space", "pass&word=+")
        val login = server.takeRequest()
        assertEquals("/bakalari/api/login", login.path)
        assertEquals("POST", login.method)
        assertTrue(login.body.readUtf8().contains("password=pass%26word%3D%2B"))
        client.refreshToken(server.url("/bakalari/").toString(), "refresh&value")
        assertTrue(server.takeRequest().body.readUtf8().contains("refresh_token=refresh%26value"))
    }

    @Test fun loginFailureIsActionableAndNeverExposesProviderResponse() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive password=private-password"))
        val failure = runCatching { BakalariNetworkClient().login(server.url("/").toString(), "student", "password") }.exceptionOrNull()
        assertTrue(failure is GradeyApiException)
        assertTrue(failure?.message.orEmpty().contains("username and password"))
        assertFalse(failure?.message.orEmpty().contains("private-password"))
    }

    @Test fun schoolRedirectNeverReceivesPostedCredentials() = runTest {
        val foreign = MockWebServer().also { it.start() }
        try {
            server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", foreign.url("/capture")))
            val failure = runCatching { BakalariNetworkClient().login(server.url("/").toString(), "student", "private-password") }.exceptionOrNull()
            assertTrue(failure is GradeyApiException)
            assertEquals(307, (failure as GradeyApiException).statusCode)
            assertEquals(0, foreign.requestCount)
        } finally { foreign.shutdown() }
    }

    @Test fun homeworkIsReadForADayRangeAndToleratesMissingAndNullFields() = runTest {
        server.enqueue(MockResponse().setBody("""{"Homeworks":[
            {"ID":"K0;AB/1","DateStart":"2026-11-10T00:00:00+01:00","DateEnd":"2026-11-17T00:00:00+01:00","Content":"Workbook p. 34","Notice":null,
             "Done":true,"Closed":false,"Electronic":null,"Hour":3,"Subject":{"Id":"7","Abbrev":"AJ","Name":"Angličtina"},"Teacher":{"Id":"U1","Name":"Eva S."},
             "Attachments":[{"Id":"att/1","Name":"zadani.pdf","Type":"application/pdf","Size":2048}],"Class":{"Id":"ZV"}},
            {"ID":"second","Content":null,"Attachments":null}
        ]}"""))
        val response = BakalariNetworkClient().fetchHomeworks(server.url("/bakalari/").toString(), "session-access", "2026-10-20", "2027-02-17")
        val request = server.takeRequest()
        assertEquals("/bakalari/api/3/homeworks?from=2026-10-20&to=2027-02-17", request.path)
        assertEquals("Bearer session-access", request.getHeader("Authorization"))
        val first = response.homeworks!!.first()
        assertEquals("K0;AB/1", first.id)
        assertEquals(true, first.done)
        assertNull(first.electronic)
        assertEquals("Angličtina", first.subject?.name)
        assertEquals(2048L, first.attachments!!.single().size)
        assertNull(response.homeworks!![1].content)
        // A school without the module answers with no list at all.
        server.enqueue(MockResponse().setBody("{}"))
        assertNull(BakalariNetworkClient().fetchHomeworks(server.url("/").toString(), "t", "2026-10-20", "2027-02-17").homeworks)
    }

    @Test fun homeworkTickIsAnEmptyPutWithTheIdKeptAsOnePathSegment() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        BakalariNetworkClient().setHomeworkDone(server.url("/bakalari/").toString(), "session-access", "K0;AB/1", true)
        val done = server.takeRequest()
        assertEquals("PUT", done.method)
        assertEquals("/bakalari/api/3/homeworks/K0;AB%2F1/student-done/true", done.path)
        assertEquals("0", done.getHeader("Content-Length"))
        assertEquals("Bearer session-access", done.getHeader("Authorization"))

        server.enqueue(MockResponse().setResponseCode(204))
        BakalariNetworkClient().setHomeworkDone(server.url("/").toString(), "t", "plain", false)
        assertEquals("/api/3/homeworks/plain/student-done/false", server.takeRequest().path)

        // An expired session is reported as one, so the repository refreshes the token and tries again.
        server.enqueue(MockResponse().setResponseCode(401).setBody("private"))
        val failure = runCatching { BakalariNetworkClient().setHomeworkDone(server.url("/").toString(), "t", "plain", true) }.exceptionOrNull()
        assertEquals(401, (failure as BakalariApiException).statusCode)
        assertFalse(failure.message.orEmpty().contains("private"))
    }

    @Test fun attachmentsComeFromTheKomensEndpointAsBytes() = runTest {
        val bytes = ByteArray(4096) { (it % 251).toByte() }
        server.enqueue(MockResponse().setBody(okio.Buffer().write(bytes)))
        val downloaded = BakalariNetworkClient().downloadAttachment(server.url("/bakalari/").toString(), "session-access", "att/1")
        val request = server.takeRequest()
        assertEquals("/bakalari/api/3/komens/attachment/att%2F1", request.path)
        assertEquals("Bearer session-access", request.getHeader("Authorization"))
        assertArrayEquals(bytes, downloaded)

        server.enqueue(MockResponse().setResponseCode(404))
        assertEquals(404, (runCatching { BakalariNetworkClient().downloadAttachment(server.url("/").toString(), "t", "gone") }.exceptionOrNull() as BakalariApiException).statusCode)
    }

    @Test fun downloadsOverTheLimitAreRefusedWithOrWithoutADeclaredLength() = runTest {
        val client = okhttp3.OkHttpClient()
        server.enqueue(MockResponse().setBody(okio.Buffer().write(ByteArray(2048))))
        val declared = runCatching { client.executeBytes(okhttp3.Request.Builder().url(server.url("/file")).build(), maxBytes = 1024) }.exceptionOrNull()
        assertTrue(declared is java.io.IOException)
        server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(ByteArray(2048)), 256))
        val chunked = runCatching { client.executeBytes(okhttp3.Request.Builder().url(server.url("/file")).build(), maxBytes = 1024) }.exceptionOrNull()
        assertTrue(chunked is java.io.IOException)
        server.enqueue(MockResponse().setChunkedBody(okio.Buffer().write(ByteArray(1024)), 256))
        assertEquals(1024, client.executeBytes(okhttp3.Request.Builder().url(server.url("/file")).build(), maxBytes = 1024).size)
    }

    @Test fun malformedResponseDoesNotLeakPayloadThroughSerializationError() = runTest {
        server.enqueue(MockResponse().setBody("<html>private-password server configuration</html>"))
        val failure = runCatching { BakalariNetworkClient().login(server.url("/").toString(), "student", "password") }.exceptionOrNull()
        assertTrue(failure is GradeyApiException)
        assertFalse(failure?.message.orEmpty().contains("private-password"))
    }
}
