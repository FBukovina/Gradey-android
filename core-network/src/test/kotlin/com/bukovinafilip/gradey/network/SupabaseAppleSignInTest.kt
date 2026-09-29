package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.AccountFailureException
import com.bukovinafilip.gradey.domain.AccountFailureReason
import com.bukovinafilip.gradey.domain.GradeySessionExpiredException
import com.bukovinafilip.gradey.model.GradeyAuthSession
import com.bukovinafilip.gradey.model.PendingAppleSignIn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
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

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SupabaseAppleSignInTest {
    private lateinit var server: MockWebServer
    private var stored: GradeyAuthSession? = null
    private var pending: PendingAppleSignIn? = null
    private val now = 1_800_000_000_000L
    private val servicesID = "com.gradey.web"
    @Before fun setup() { server = MockWebServer().also { it.start() } }
    @After fun teardown() { server.shutdown() }
    private fun repository(clientID: String = servicesID) = SupabaseGradeyAuthRepository(
        SupabaseConfiguration(server.url("/").toString(), "public-key", clientID),
        sessionStore = { stored = it }, sessionLoader = { stored }, nowProvider = { now },
        pendingAppleStore = { pending = it }, pendingAppleLoader = { pending },
    )
    private fun settings() = MockResponse().setBody("""{"external":{"apple":true,"google":true}}""")
    private fun redirect(clientID: String = servicesID) = MockResponse().setResponseCode(302)
        .setHeader("Location", "https://appleid.apple.com/auth/authorize?client_id=$clientID&response_type=code&state=provider-state")
    private fun token() = MockResponse().setBody("""{"access_token":"apple-access","refresh_token":"apple-refresh","expires_in":3600,"user":{"id":"existing-ios-owner","email":"hidden@privaterelay.appleid.com","user_metadata":{"full_name":"Existing Name"}}}""")
    private fun callback(state: String = pending!!.state) = "gradey://auth?gradey_state=$state&code=single-use-code"

    @Test fun nativeOnlyAndDisabledConfigurationNeverOfferWebSignIn() = runTest {
        assertFalse(repository("").appleSignInAvailable())
        assertFalse(repository(AppleOAuthProtocol.NATIVE_APPLE_CLIENT_ID).appleSignInAvailable())
        assertEquals(0, server.requestCount)
        server.enqueue(settings()); server.enqueue(redirect(AppleOAuthProtocol.NATIVE_APPLE_CLIENT_ID))
        assertFalse(repository().appleSignInAvailable())
        assertNull(pending)
    }

    @Test fun encryptedPendingAttemptCanCompleteAfterProcessRecreationAndKeepsSupabaseOwner() = runTest {
        server.enqueue(settings()); server.enqueue(redirect()); server.enqueue(token())
        assertTrue(repository().beginAppleSignIn().startsWith("https://appleid.apple.com/"))
        val verifier = pending!!.codeVerifier
        val rawCallback = callback()
        val restoredRepo = repository()
        val result = restoredRepo.completeAppleSignIn(rawCallback)
        assertEquals("existing-ios-owner", result.account.id)
        assertEquals("Existing Name", result.account.fullName)
        assertEquals("hidden@privaterelay.appleid.com", result.account.email)
        assertEquals(result, stored)
        assertNull(pending)
        server.takeRequest(); val authorize = server.takeRequest()
        assertFalse(authorize.path.orEmpty().contains(verifier))
        val exchange = server.takeRequest()
        assertEquals("/auth/v1/token?grant_type=pkce", exchange.path)
        assertEquals("public-key", exchange.getHeader("apikey"))
        assertNull(exchange.getHeader("Authorization"))
        assertEquals("""{"auth_code":"single-use-code","code_verifier":"$verifier"}""", exchange.body.readUtf8())
        assertEquals(AccountFailureReason.AUTH_REQUEST_EXPIRED, (runCatching { restoredRepo.completeAppleSignIn(rawCallback) }.exceptionOrNull() as AccountFailureException).failureReason)
        assertEquals(3, server.requestCount)
    }

    @Test fun unsolicitedOrWrongStateCallbackMakesNoTokenRequestAndPreservesAttempt() = runTest {
        val auth = repository()
        assertTrue(runCatching { auth.completeAppleSignIn(callback("unknown")) }.exceptionOrNull() is AccountFailureException)
        assertEquals(0, server.requestCount)
        pending = AppleOAuthProtocol.create(now)
        val before = pending
        assertEquals(AccountFailureReason.AUTH_CALLBACK_INVALID, (runCatching { auth.completeAppleSignIn(callback("wrong")) }.exceptionOrNull() as AccountFailureException).failureReason)
        assertSame(before, pending)
        assertEquals(0, server.requestCount)
    }

    @Test fun userCancellationConsumesPendingAttemptWithoutNetwork() = runTest {
        pending = AppleOAuthProtocol.create(now)
        val callback = "gradey://auth?gradey_state=${pending!!.state}&error=access_denied&error_description=never-display"
        val error = runCatching { repository().completeAppleSignIn(callback) }.exceptionOrNull() as AccountFailureException
        assertEquals(AccountFailureReason.AUTH_CANCELLED, error.failureReason)
        assertFalse(error.message.orEmpty().contains("never-display"))
        assertNull(pending)
        assertEquals(0, server.requestCount)
    }

    @Test fun failedTokenExchangeCannotBeReplayedOrRevealResponse() = runTest {
        pending = AppleOAuthProtocol.create(now)
        val callback = callback()
        server.enqueue(MockResponse().setResponseCode(400).setBody("secret-token-response"))
        val auth = repository()
        val error = runCatching { auth.completeAppleSignIn(callback) }.exceptionOrNull()
        assertTrue(error is SupabaseApiException)
        assertFalse(error?.message.orEmpty().contains("secret-token-response"))
        assertNull(stored); assertNull(pending)
        assertTrue(runCatching { auth.completeAppleSignIn(callback) }.exceptionOrNull() is AccountFailureException)
        assertEquals(1, server.requestCount)
    }

    @Test fun signOutInvalidatesInFlightExchangeBeforeItCanRestoreSession() = runTest {
        pending = AppleOAuthProtocol.create(now)
        val callback = callback()
        val started = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                started.complete(Unit)
                release.await(5, TimeUnit.SECONDS)
                return token()
            }
        }
        val auth = repository()
        val exchange = async { runCatching { auth.completeAppleSignIn(callback) } }
        started.await()
        auth.signOut()
        assertNull(stored); assertNull(pending)
        release.countDown()
        assertTrue(exchange.await().exceptionOrNull() is GradeySessionExpiredException)
        assertNull(stored)
    }
}
