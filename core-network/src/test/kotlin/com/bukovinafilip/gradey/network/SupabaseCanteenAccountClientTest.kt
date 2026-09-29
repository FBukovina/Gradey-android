package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.AccountFailureException
import com.bukovinafilip.gradey.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SupabaseCanteenAccountClientTest {
    private lateinit var server: MockWebServer
    private val auth = GradeyAuthSession("owner-token", account = GradeyAccount("owner-id"))
    private val session = StravaCZStoredSession("canteen-session", "1234", "student", serviceURL = "https://app.strava.cz/Services", canteenName = "Test canteen", fullName = "Test Student")
    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun teardown() { server.shutdown() }
    private fun client() = SupabaseCanteenAccountClient(SupabaseConfiguration(server.url("/").toString(), "public-key"))

    @Test fun linksOnlyHostedAllowlistedSessionFieldsWithoutPassword() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"canteen-link","provider":"stravaCZ","displayName":"Test Student","canteenName":"Test canteen","providerUserID":"student","status":"active"}"""))
        val result = client().link(auth, session)
        assertEquals("canteen-link", result.id)
        val request = server.takeRequest()
        assertEquals("/functions/v1/link-stravacz-account", request.path)
        assertEquals("Bearer owner-token", request.getHeader("Authorization"))
        val body = GradeyJson.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(setOf("display_name", "canteen_number", "canteen_name", "username", "service_url", "session_id"), body.keys)
        assertEquals("canteen-session", body["session_id"]?.jsonPrimitive?.content)
        assertFalse(body.keys.any { it.contains("password") })
    }
    @Test fun listIsOwnerAndCanteenScoped() = runTest {
        server.enqueue(MockResponse().setBody("""[{"id":"one","provider":"stravaCZ","display_name":"Student","canteen_name":"Canteen","provider_user_id":"student"}]"""))
        assertEquals("Canteen", client().accounts(auth).single().canteenName)
        val url = server.takeRequest().requestUrl!!
        assertEquals("eq.owner-id", url.queryParameter("user_id"))
        assertEquals("eq.stravaCZ", url.queryParameter("provider"))
    }
    @Test fun unsafeServiceURLNeverLeavesDevice() = runTest {
        listOf("http://app.strava.cz/", "https://user:password@app.strava.cz/", "https://app.strava.cz/?token=secret").forEach { url ->
            assertTrue(runCatching { client().link(auth, session.copy(serviceURL = url)) }.exceptionOrNull() is AccountFailureException)
        }
        assertEquals(0, server.requestCount)
    }
    @Test fun wrongProviderResponseIsRejectedAndRawErrorsAreHidden() = runTest {
        server.enqueue(MockResponse().setBody("""{"id":"one","provider":"bakalari","displayName":"Wrong"}"""))
        assertTrue(runCatching { client().link(auth, session) }.exceptionOrNull() is SupabaseApiException)
        server.enqueue(MockResponse().setResponseCode(400).setBody("secret internal canteen-session"))
        val error = runCatching { client().link(auth, session) }.exceptionOrNull()
        assertTrue(error is SupabaseApiException)
        assertFalse(error?.message.orEmpty().contains("canteen-session"))
    }
}
