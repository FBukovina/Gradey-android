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

    @Test fun malformedResponseDoesNotLeakPayloadThroughSerializationError() = runTest {
        server.enqueue(MockResponse().setBody("<html>private-password server configuration</html>"))
        val failure = runCatching { BakalariNetworkClient().login(server.url("/").toString(), "student", "password") }.exceptionOrNull()
        assertTrue(failure is GradeyApiException)
        assertFalse(failure?.message.orEmpty().contains("private-password"))
    }
}
