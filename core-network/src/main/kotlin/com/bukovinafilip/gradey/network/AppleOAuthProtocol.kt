package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.AccountFailureException
import com.bukovinafilip.gradey.domain.AccountFailureReason
import com.bukovinafilip.gradey.model.PendingAppleSignIn
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/** Supabase owns provider OAuth state; gradey_state binds its app redirect to our local PKCE attempt. */
object AppleOAuthProtocol {
    const val CALLBACK_URL = "gradey://auth"
    const val REQUEST_LIFETIME_MILLIS = 10 * 60 * 1000L
    internal const val NATIVE_APPLE_CLIENT_ID = "com.bukovinafilip.BakalariMarks"

    fun create(nowEpochMillis: Long): PendingAppleSignIn {
        val random = SecureRandom()
        fun token(): String = ByteArray(32).also(random::nextBytes).let(::base64URL)
        return PendingAppleSignIn(token(), token(), nowEpochMillis)
    }

    fun authorizationURL(configuration: SupabaseConfiguration, pending: PendingAppleSignIn): String =
        configuration.url.appendPath("auth/v1/authorize").toHttpUrl().newBuilder()
            .addQueryParameter("provider", "apple")
            .addQueryParameter("redirect_to", "$CALLBACK_URL?gradey_state=${pending.state}")
            .addQueryParameter("code_challenge", codeChallenge(pending.codeVerifier))
            .addQueryParameter("code_challenge_method", "s256")
            .build().toString()

    fun codeChallenge(verifier: String): String = base64URL(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    fun isCallback(raw: String?): Boolean {
        val uri = raw?.takeIf { it.length <= 8192 }?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        return uri.scheme == "gradey" && uri.rawAuthority == "auth" && uri.rawPath.orEmpty().isEmpty()
    }

    fun verifyCallback(raw: String, pending: PendingAppleSignIn, nowEpochMillis: Long): String {
        if (!isCallback(raw)) invalid()
        val uri = URI(raw)
        if (!uri.rawFragment.isNullOrEmpty()) invalid()
        val query = try {
            uri.rawQuery.orEmpty().split('&').filter(String::isNotEmpty).map { part ->
                part.substringBefore('=') to URLDecoder.decode(part.substringAfter('=', ""), StandardCharsets.UTF_8)
            }
        } catch (_: IllegalArgumentException) { invalid() }
        if (query.any { it.first in setOf("access_token", "refresh_token", "id_token") }) invalid()
        fun one(name: String): String? {
            val values = query.filter { it.first == name }
            if (values.size > 1) invalid()
            return values.singleOrNull()?.second
        }
        val state = one("gradey_state") ?: invalid()
        if (!MessageDigest.isEqual(state.toByteArray(), pending.state.toByteArray())) invalid()
        val age = nowEpochMillis - pending.createdAtEpochMillis
        if (age < 0 || age > REQUEST_LIFETIME_MILLIS) throw AccountFailureException(AccountFailureReason.AUTH_REQUEST_EXPIRED)
        val error = one("error")
        if (error != null) {
            if (error == "access_denied") throw AccountFailureException(AccountFailureReason.AUTH_CANCELLED)
            invalid()
        }
        return one("code")?.takeIf { it.isNotBlank() && it.length <= 2048 } ?: invalid()
    }

    internal fun validateAppleLocation(raw: String?, expectedClientID: String): String? {
        if (expectedClientID.isBlank() || expectedClientID == NATIVE_APPLE_CLIENT_ID) return null
        val url = raw?.let { runCatching { it.toHttpUrl() }.getOrNull() } ?: return null
        if (url.scheme != "https" || url.host != "appleid.apple.com" || url.encodedPath != "/auth/authorize" ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.port != 443 ||
            url.queryParameterValues("client_id") != listOf(expectedClientID) ||
            url.queryParameter("response_type") != "code") return null
        return url.toString()
    }

    private fun invalid(): Nothing = throw AccountFailureException(AccountFailureReason.AUTH_CALLBACK_INVALID)
    private fun base64URL(bytes: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal suspend fun appleAuthorizationLocation(client: OkHttpClient, request: Request): String? =
    suspendCancellableCoroutine { continuation ->
        val call = client.newBuilder().followRedirects(false).followSslRedirects(false).build().newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                val location = response.use { if (it.code in setOf(302, 303)) it.header("Location") else null }
                if (continuation.isActive) continuation.resume(location)
            }
        })
    }
