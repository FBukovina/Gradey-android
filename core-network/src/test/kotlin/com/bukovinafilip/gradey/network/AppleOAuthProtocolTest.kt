package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.AccountFailureException
import com.bukovinafilip.gradey.domain.AccountFailureReason
import com.bukovinafilip.gradey.model.PendingAppleSignIn
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class AppleOAuthProtocolTest {
    private val pending = PendingAppleSignIn("expected-state", "verifier", 1_000L)
    private val valid = "gradey://auth?gradey_state=expected-state&code=auth-code"

    @Test fun pkceUsesRFC7636S256Example() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", AppleOAuthProtocol.codeChallenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun newAttemptsAreIndependentAndVerifierNeverEntersAuthorizationURL() {
        val one = AppleOAuthProtocol.create(1_000)
        val two = AppleOAuthProtocol.create(1_000)
        assertNotEquals(one.state, two.state)
        assertNotEquals(one.codeVerifier, two.codeVerifier)
        assertEquals(43, one.codeVerifier.length)
        val url = AppleOAuthProtocol.authorizationURL(SupabaseConfiguration("https://project.supabase.co", "public-key"), one).toHttpUrl()
        assertEquals("s256", url.queryParameter("code_challenge_method"))
        assertEquals("gradey://auth?gradey_state=${one.state}", url.queryParameter("redirect_to"))
        assertFalse(url.toString().contains(one.codeVerifier))
        assertFalse(url.toString().contains("public-key"))
        assertFalse(one.toString().contains(one.codeVerifier))
    }

    @Test fun callbackRequiresExactAddressStateAndOneCode() {
        assertEquals("auth-code", AppleOAuthProtocol.verifyCallback(valid, pending, 2_000))
        listOf(
            valid.replace("gradey:", "https:"), valid.replace("auth?", "auth.evil.test?"),
            valid.replace("auth?", "auth:42?"), valid.replace("auth?", "user@auth?"),
            valid.replace("auth?", "auth/path?"), valid.replace("expected-state", "wrong"),
            "$valid&gradey_state=expected-state", "$valid&code=other", "$valid#access_token=secret",
            "$valid&refresh_token=secret", "gradey://auth?code=auth-code",
        ).forEach { raw -> assertReason(AccountFailureReason.AUTH_CALLBACK_INVALID) { AppleOAuthProtocol.verifyCallback(raw, pending, 2_000) } }
    }

    @Test fun expiryClockRollbackAndCancellationAreTyped() {
        assertReason(AccountFailureReason.AUTH_REQUEST_EXPIRED) { AppleOAuthProtocol.verifyCallback(valid, pending, 1_000 + AppleOAuthProtocol.REQUEST_LIFETIME_MILLIS + 1) }
        assertReason(AccountFailureReason.AUTH_REQUEST_EXPIRED) { AppleOAuthProtocol.verifyCallback(valid, pending, 999) }
        assertReason(AccountFailureReason.AUTH_CANCELLED) { AppleOAuthProtocol.verifyCallback("gradey://auth?gradey_state=expected-state&error=access_denied", pending, 2_000) }
    }

    @Test fun browserRedirectMustUseExpectedServicesIDAndAppleHTTPSOrigin() {
        val url = "https://appleid.apple.com/auth/authorize?client_id=com.example.web&response_type=code"
        assertNotNull(AppleOAuthProtocol.validateAppleLocation(url, "com.example.web"))
        listOf(url.replace("https:", "http:"), url.replace("appleid.apple.com", "appleid.apple.com.evil.test"), "$url&client_id=com.example.web", url.replace("com.example.web", "wrong-id")).forEach {
            assertNull(AppleOAuthProtocol.validateAppleLocation(it, "com.example.web"))
        }
        assertNull(AppleOAuthProtocol.validateAppleLocation(url.replace("com.example.web", AppleOAuthProtocol.NATIVE_APPLE_CLIENT_ID), AppleOAuthProtocol.NATIVE_APPLE_CLIENT_ID))
    }

    private fun assertReason(expected: AccountFailureReason, block: () -> Unit) {
        val error = runCatching(block).exceptionOrNull()
        assertTrue(error is AccountFailureException)
        assertEquals(expected, (error as AccountFailureException).failureReason)
    }
}
