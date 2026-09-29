package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.GradeySessionExpiredException
import com.bukovinafilip.gradey.model.GradeyAccount
import com.bukovinafilip.gradey.model.GradeyAuthSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SupabaseAuthLifecycleTest {
    private lateinit var server: MockWebServer
    private var stored: GradeyAuthSession? = null
    private val now = 1_800_000_000_000L

    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun teardown() { server.shutdown() }
    private fun repository() = SupabaseGradeyAuthRepository(
        SupabaseConfiguration(server.url("/").toString(), "public-key"),
        sessionStore = { stored = it }, sessionLoader = { stored }, nowProvider = { now },
    )
    private fun session(expired: Boolean = true) = GradeyAuthSession(
        "old-token", "old-refresh", expiresAtEpochMillis = if (expired) now else now + 3_600_000,
        account = GradeyAccount("owner", "student@example.com", "Stored Name", "https://example.com/avatar.png", 1_000),
    )
    private fun token() = """{"access_token":"fresh-token","expires_in":3600,"user":{"id":"owner","user_metadata":{"full_name":{"unexpected":true}}}}"""

    @Test fun refreshPreservesOmittedIdentityAndRefreshTokenAndOnlyRunsOnce() = runTest {
        stored = session()
        server.enqueue(MockResponse().setBody(token()))
        val auth = repository()
        val sessions = List(8) { async { auth.validSession() } }.awaitAll()
        assertEquals(1, server.requestCount)
        assertEquals(listOf("fresh-token"), sessions.map { it.accessToken }.distinct())
        assertEquals("old-refresh", stored?.refreshToken)
        assertEquals("Stored Name", stored?.account?.fullName)
        assertEquals("https://example.com/avatar.png", stored?.account?.avatarURL)
        assertEquals(1_000L, stored?.account?.createdAtEpochMillis)
    }

    @Test fun blankRefreshTokenExpiresLocallyWithoutNetwork() = runTest {
        stored = session().copy(refreshToken = "  ")
        assertNull(repository().bootstrapSession())
        assertNull(stored)
        assertEquals(0, server.requestCount)
    }

    @Test fun unavailableAuthServerPreservesSessionForRetry() = runTest {
        val original = session()
        stored = original
        server.enqueue(MockResponse().setResponseCode(503).setBody("private-internal-error"))
        val failure = runCatching { repository().validSession() }.exceptionOrNull()
        assertTrue(failure is GradeyApiException)
        assertEquals(original, stored)
        assertFalse(failure?.message.orEmpty().contains("private-internal-error"))
    }

    @Test fun profileRefreshAndNameUpdateUseUserAuthorizationAndPersistCanonicalMetadata() = runTest {
        stored = session(expired = false)
        server.enqueue(MockResponse().setBody("""{"id":"owner","email":" updated@example.com ","user_metadata":{"full_name":"Server Name"}}"""))
        server.enqueue(MockResponse().setBody("""{"id":"owner","user_metadata":{"full_name":"New Name"}}"""))
        val auth = repository()
        assertEquals("Server Name", auth.refreshAccount().fullName)
        val refresh = server.takeRequest()
        assertEquals("GET", refresh.method)
        assertEquals("/auth/v1/user", refresh.path)
        assertEquals("Bearer old-token", refresh.getHeader("Authorization"))
        assertEquals("New Name", auth.updateFullName("  New Name  ").fullName)
        val update = server.takeRequest()
        assertEquals("PUT", update.method)
        assertEquals("""{"data":{"full_name":"New Name"}}""", update.body.readUtf8())
        assertEquals("updated@example.com", stored?.account?.email)
        assertEquals("old-refresh", stored?.refreshToken)
    }

    @Test fun invalidNamesNeverReachTheServer() = runTest {
        stored = session(expired = false)
        val auth = repository()
        listOf("   ", "n".repeat(81)).forEach { name ->
            assertTrue(runCatching { auth.updateFullName(name) }.exceptionOrNull() is IllegalArgumentException)
        }
        assertEquals(0, server.requestCount)
    }

    @Test fun anotherUsersProfileCannotReplaceTheCurrentIdentity() = runTest {
        val original = session(expired = false)
        stored = original
        server.enqueue(MockResponse().setBody("""{"id":"another-owner","email":"another@example.com"}"""))
        assertTrue(runCatching { repository().refreshAccount() }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(original, stored)
    }

    @Test fun signOutClearsImmediatelyAndLateRefreshCannotSignUserBackIn() = runTest {
        stored = session()
        val requestStarted = CompletableDeferred<Unit>()
        val finishResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path?.startsWith("/auth/v1/token") == true) {
                    requestStarted.complete(Unit)
                    finishResponse.await(5, TimeUnit.SECONDS)
                    return MockResponse().setBody(token())
                }
                return MockResponse().setResponseCode(204)
            }
        }
        val auth = repository()
        val refresh = async { runCatching { auth.validSession() } }
        requestStarted.await()
        val signOut = launch { auth.signOut() }
        runCurrent()
        assertNull(stored)
        finishResponse.countDown()
        assertTrue(refresh.await().exceptionOrNull() is GradeySessionExpiredException)
        signOut.join()
        assertNull(stored)
    }
}
