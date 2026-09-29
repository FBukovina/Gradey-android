package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.AccountFailureException
import com.bukovinafilip.gradey.domain.AccountFailureReason
import com.bukovinafilip.gradey.domain.AccountValidationException
import com.bukovinafilip.gradey.domain.UserFacingFailure
import com.bukovinafilip.gradey.model.PendingAppleSignIn
import com.bukovinafilip.gradey.domain.DevicePushTokenClient
import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.domain.GradeySessionExpiredException
import com.bukovinafilip.gradey.model.GradeyAccount
import com.bukovinafilip.gradey.model.GradeyAuthSession
import com.bukovinafilip.gradey.model.NotificationPreferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.time.Instant
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request

data class SupabaseConfiguration(
    val url: String,
    val anonKey: String,
    val appleOAuthClientId: String = "",
) {
    val isConfigured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()
}

class SupabaseGradeyAuthRepository(
    private val configuration: SupabaseConfiguration,
    private val sessionStore: suspend (GradeyAuthSession?) -> Unit,
    private val sessionLoader: suspend () -> GradeyAuthSession?,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val pendingAppleStore: (suspend (PendingAppleSignIn?) -> Unit)? = null,
    private val pendingAppleLoader: (suspend () -> PendingAppleSignIn?)? = null,
) : GradeyAuthRepository {
    private val authHttpClient = okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false).build()
    private val refreshMutex = Mutex()
    private val authStateMutex = Mutex()
    private var authStateEpoch = 0L
    private var memoryPendingApple: PendingAppleSignIn? = null

    override suspend fun cachedSession(): GradeyAuthSession? =
        authStateMutex.withLock { sessionLoader() }

    override suspend fun bootstrapSession(): GradeyAuthSession? = try {
        validSession()
    } catch (_: GradeySessionExpiredException) { null }

    override suspend fun validSession(): GradeyAuthSession {
        val restored = authStateMutex.withLock { sessionLoader() }
            ?: throw GradeySessionExpiredException()
        if (!needsRefresh(restored)) return restored

        return refreshMutex.withLock {
            val snapshot = authStateSnapshot()
            val current = snapshot.session ?: throw GradeySessionExpiredException()
            if (!needsRefresh(current)) return@withLock current

            val refreshToken = current.refreshToken?.trim()?.takeIf(String::isNotEmpty)
                ?: expireSessionIfCurrent(snapshot)
            try {
                val response: SupabaseTokenResponse = send(
                    path = "auth/v1/token?grant_type=refresh_token",
                    method = "POST",
                    body = GradeyJson.encodeToString(RefreshTokenRequest(refreshToken)),
                )
                val refreshed = response.makeSession(
                    previous = current,
                    nowEpochMillis = nowProvider(),
                )
                persistSessionIfCurrent(snapshot, refreshed)
            } catch (error: CancellationException) {
                throw error
            } catch (error: GradeyApiException) {
                if (error.statusCode in listOf(400, 401, 403)) {
                    expireSessionIfCurrent(snapshot, error)
                }
                throw error
            }
        }
    }

    override suspend fun refreshAccount(): GradeyAccount {
        val session = validSession()
        val response: SupabaseUserResponse = send(
            path = "auth/v1/user",
            method = "GET",
            authorization = session.authorizationHeader,
        )
        return persistAccount(response, session)
    }

    override suspend fun updateFullName(fullName: String): GradeyAccount {
        val normalizedName = fullName.trim()
        if (normalizedName.length !in 1..80) throw AccountValidationException(AccountFailureReason.PROFILE_NAME_INVALID)
        val session = validSession()
        val response: SupabaseUserResponse = send(
            path = "auth/v1/user",
            method = "PUT",
            authorization = session.authorizationHeader,
            body = GradeyJson.encodeToString(
                UpdateUserRequest(UpdateUserMetadata(normalizedName)),
            ),
        )
        return persistAccount(response, session)
    }

    override suspend fun signInWithGoogle(idToken: String, accessToken: String?, fullName: String?): GradeyAuthSession {
        if (idToken.isBlank()) throw AccountValidationException(AccountFailureReason.IDENTITY_TOKEN_MISSING)
        ensureConfigured()
        return refreshMutex.withLock {
            val requestEpoch = authStateMutex.withLock { storePendingApple(null); authStateEpoch }
            val response: SupabaseTokenResponse = send(
                path = "auth/v1/token?grant_type=id_token",
                method = "POST",
                body = GradeyJson.encodeToString(SignInWithIDTokenRequest("google", idToken, accessToken)),
            )
            val session = response.makeSession(
                suppliedFullName = fullName,
                nowEpochMillis = nowProvider(),
            )
            persistSessionIfEpoch(requestEpoch, session)
        }
    }

    override suspend fun appleSignInAvailable(): Boolean {
        if (!configuration.isConfigured || configuration.appleOAuthClientId.isBlank() ||
            configuration.appleOAuthClientId == AppleOAuthProtocol.NATIVE_APPLE_CLIENT_ID) return false
        return try {
            val settings: SupabaseAuthSettings = send("auth/v1/settings", "GET")
            if (settings.external["apple"] != true) return false
            appleLocation(AppleOAuthProtocol.create(nowProvider())) != null
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    override suspend fun beginAppleSignIn(): String = refreshMutex.withLock {
        ensureConfigured()
        val epoch = authStateMutex.withLock {
            if (sessionLoader() != null) throw AccountFailureException(AccountFailureReason.REQUEST_REJECTED)
            storePendingApple(null)
            authStateEpoch
        }
        if (configuration.appleOAuthClientId.isBlank() || configuration.appleOAuthClientId == AppleOAuthProtocol.NATIVE_APPLE_CLIENT_ID)
            throw AccountFailureException(AccountFailureReason.APPLE_SIGN_IN_UNAVAILABLE)
        val settings: SupabaseAuthSettings = send("auth/v1/settings", "GET")
        if (settings.external["apple"] != true) throw AccountFailureException(AccountFailureReason.APPLE_SIGN_IN_UNAVAILABLE)
        val pending = AppleOAuthProtocol.create(nowProvider())
        val location = appleLocation(pending) ?: throw AccountFailureException(AccountFailureReason.APPLE_SIGN_IN_UNAVAILABLE)
        authStateMutex.withLock {
            if (authStateEpoch != epoch || sessionLoader() != null) throw GradeySessionExpiredException()
            storePendingApple(pending)
        }
        location
    }

    override suspend fun completeAppleSignIn(callbackURL: String): GradeyAuthSession = refreshMutex.withLock {
        val attempt = authStateMutex.withLock {
            val pending = loadPendingApple() ?: throw AccountFailureException(AccountFailureReason.AUTH_REQUEST_EXPIRED)
            val code = try { AppleOAuthProtocol.verifyCallback(callbackURL, pending, nowProvider()) }
            catch (error: AccountFailureException) {
                if (error.failureReason in setOf(AccountFailureReason.AUTH_CANCELLED, AccountFailureReason.AUTH_REQUEST_EXPIRED)) storePendingApple(null)
                throw error
            }
            if (sessionLoader() != null) throw AccountFailureException(AccountFailureReason.REQUEST_REJECTED)
            storePendingApple(null) // Each matching callback can be consumed once, including failed exchanges.
            Triple(code, pending.codeVerifier, authStateEpoch)
        }
        val response: SupabaseTokenResponse = send(
            path = "auth/v1/token?grant_type=pkce", method = "POST",
            body = GradeyJson.encodeToString(ExchangePKCERequest(attempt.first, attempt.second)),
        )
        if (response.refreshToken.isNullOrBlank()) throw SupabaseApiException(502, AccountFailureReason.MALFORMED_RESPONSE)
        persistSessionIfEpoch(attempt.third, response.makeSession(nowEpochMillis = nowProvider()))
    }

    override suspend fun cancelAppleSignIn() = authStateMutex.withLock {
        authStateEpoch += 1
        storePendingApple(null)
    }

    private suspend fun appleLocation(pending: PendingAppleSignIn): String? = AppleOAuthProtocol.validateAppleLocation(
        appleAuthorizationLocation(okHttpClient, Request.Builder()
            .url(AppleOAuthProtocol.authorizationURL(configuration, pending))
            .header("apikey", configuration.anonKey).build()), configuration.appleOAuthClientId,
    )

    private suspend fun loadPendingApple(): PendingAppleSignIn? = if (pendingAppleLoader != null) pendingAppleLoader.invoke() else memoryPendingApple
    private suspend fun storePendingApple(pending: PendingAppleSignIn?) {
        pendingAppleStore?.invoke(pending)
        memoryPendingApple = pending
    }

    private suspend fun takeLocalSessionForSignOut(): GradeyAuthSession? =
        withContext(NonCancellable) {
            authStateMutex.withLock {
                val session = sessionLoader()
                // Invalidate in-flight refresh/sign-in responses before the durable clear.
                authStateEpoch += 1
                storePendingApple(null)
                sessionStore(null)
                session
            }
        }

    private suspend fun revokeSignedOutSession(session: GradeyAuthSession) {
        if (!configuration.isConfigured) return
        refreshMutex.withLock {
            // Never let cleanup for account A revoke or otherwise delay a session
            // that account B has already adopted while local teardown was running.
            if (authStateMutex.withLock { sessionLoader() != null }) return@withLock
            try {
                val request = Request.Builder()
                    .url(configuration.url.appendPath("auth/v1/logout"))
                    .post(jsonBody("{}"))
                    .header("apikey", configuration.anonKey)
                    .header("Authorization", session.authorizationHeader)
                    .build()
                authHttpClient.executeString(request)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // The captured session is already absent locally; remote logout is best effort.
            }
        }
    }

    override suspend fun signOut() {
        takeLocalSessionForSignOut()?.let { revokeSignedOutSession(it) }
    }

    private suspend inline fun <reified T> send(
        path: String,
        method: String,
        body: String? = null,
        authorization: String? = null,
    ): T {
        ensureConfigured()
        val builder = Request.Builder()
            .url(configuration.url.appendPath(path))
            .header("Accept", "application/json")
            .header("apikey", configuration.anonKey)
        if (authorization != null) builder.header("Authorization", authorization)
        when (method) {
            "GET" -> builder.get()
            "POST" -> builder.post(jsonBody(body ?: "{}"))
            "PUT" -> builder.put(jsonBody(body ?: "{}"))
            else -> error("Unsupported Gradey ID request method: $method")
        }
        if (body != null) builder.header("Content-Type", "application/json")
        val request = builder.build()
        return decodeSupabaseResponse(safeSupabaseResponse(authHttpClient, request))
    }

    private fun needsRefresh(session: GradeyAuthSession): Boolean =
        session.expiresAtEpochMillis?.let { it <= nowProvider() + REFRESH_LEEWAY_MILLIS } ?: false

    private suspend fun authStateSnapshot(): AuthStateSnapshot =
        authStateMutex.withLock {
            AuthStateSnapshot(
                session = sessionLoader(),
                epoch = authStateEpoch,
            )
        }

    private suspend fun persistSessionIfCurrent(
        expected: AuthStateSnapshot,
        replacement: GradeyAuthSession,
    ): GradeyAuthSession = authStateMutex.withLock {
        if (authStateEpoch != expected.epoch || sessionLoader() != expected.session) {
            throw GradeySessionExpiredException()
        }
        sessionStore(replacement)
        authStateEpoch += 1
        replacement
    }

    private suspend fun persistSessionIfEpoch(
        expectedEpoch: Long,
        replacement: GradeyAuthSession,
    ): GradeyAuthSession = authStateMutex.withLock {
        if (authStateEpoch != expectedEpoch) throw GradeySessionExpiredException()
        sessionStore(replacement)
        authStateEpoch += 1
        replacement
    }

    private suspend fun expireSessionIfCurrent(
        expected: AuthStateSnapshot,
        cause: Throwable? = null,
    ): Nothing {
        withContext(NonCancellable) {
            authStateMutex.withLock {
                if (authStateEpoch == expected.epoch && sessionLoader() == expected.session) {
                    authStateEpoch += 1
                    sessionStore(null)
                }
            }
        }
        throw GradeySessionExpiredException(cause)
    }

    private suspend fun persistAccount(
        response: SupabaseUserResponse,
        sessionUsedForRequest: GradeyAuthSession,
    ): GradeyAccount = refreshMutex.withLock {
        authStateMutex.withLock {
            val current = sessionLoader()
            if (
                current == null ||
                current.account.id != sessionUsedForRequest.account.id ||
                current.accessToken != sessionUsedForRequest.accessToken
            ) {
                throw GradeySessionExpiredException()
            }
            val account = response.mergeInto(current.account, nowProvider())
            sessionStore(current.copy(account = account))
            authStateEpoch += 1
            account
        }
    }

    private fun ensureConfigured() {
        if (!configuration.isConfigured) throw AccountFailureException(AccountFailureReason.NOT_CONFIGURED)
    }

    private companion object {
        const val REFRESH_LEEWAY_MILLIS = 60_000L
    }

    private data class AuthStateSnapshot(
        val session: GradeyAuthSession?,
        val epoch: Long,
    )
}

class SupabaseDevicePushTokenClient(
    private val configuration: SupabaseConfiguration,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) : DevicePushTokenClient {
    override suspend fun registerDeviceToken(token: String, platform: String, environment: String, gradeySession: GradeyAuthSession) {
        send(
            function = "register-device",
            session = gradeySession,
            body = RegisterDeviceRequest(token, platform, environment),
        )
    }

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences, gradeySession: GradeyAuthSession) {
        send(
            function = "update-notification-preferences",
            session = gradeySession,
            body = NotificationPreferencesRequest(
                newMarksEnabled = preferences.newMarksEnabled,
                lockScreenDetail = preferences.lockScreenDetail,
                quietHoursTimeZoneIdentifier = validNotificationTimeZone(preferences.quietHoursTimeZoneIdentifier),
                quietHoursEnabled = preferences.quietHoursEnabled,
                quietHoursStartMinute = notificationTimeToMinutes(preferences.quietHoursStart),
                quietHoursEndMinute = notificationTimeToMinutes(preferences.quietHoursEnd),
            ),
        )
    }

    private suspend inline fun <reified T> send(function: String, session: GradeyAuthSession, body: T) {
        if (!configuration.isConfigured) throw AccountFailureException(AccountFailureReason.NOT_CONFIGURED)
        val request = Request.Builder()
            .url(configuration.url.appendPath("functions/v1/$function"))
            .post(jsonBody(GradeyJson.encodeToString(body)))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("apikey", configuration.anonKey)
            .header("Authorization", session.authorizationHeader)
            .build()
        safeSupabaseResponse(okHttpClient, request)
    }
}

@Serializable
private data class SignInWithIDTokenRequest(
    val provider: String,
    @SerialName("id_token")
    val idToken: String,
    @SerialName("access_token")
    val accessToken: String? = null,
)

@Serializable
private data class UpdateUserRequest(
    val data: UpdateUserMetadata,
)

@Serializable
private data class UpdateUserMetadata(
    @SerialName("full_name")
    val fullName: String,
)

@Serializable
private data class SupabaseTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("token_type")
    val tokenType: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
    val user: SupabaseUserResponse,
) {
    fun makeSession(
        previous: GradeyAuthSession? = null,
        suppliedFullName: String? = null,
        nowEpochMillis: Long,
    ): GradeyAuthSession {
        if (accessToken.isBlank()) throw AccountValidationException(AccountFailureReason.MALFORMED_RESPONSE)
        if (user.id.isBlank() || (previous != null && previous.account.id != user.id))
            throw AccountValidationException(AccountFailureReason.ACCOUNT_IDENTITY_MISMATCH)
        val freshAccount = user.makeAccount(nowEpochMillis)
        val account = freshAccount.copy(
            email = freshAccount.email.nonEmptyOrNull() ?: previous?.account?.email,
            fullName = suppliedFullName.nonEmptyOrNull()
                ?: freshAccount.fullName.nonEmptyOrNull()
                ?: previous?.account?.fullName,
            avatarURL = freshAccount.avatarURL.nonEmptyOrNull() ?: previous?.account?.avatarURL,
            createdAtEpochMillis = user.createdAtEpochMillis()
                ?: previous?.account?.createdAtEpochMillis
                ?: nowEpochMillis,
        )
        return GradeyAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken.nonEmptyOrNull() ?: previous?.refreshToken,
            tokenType = tokenType ?: "Bearer",
            expiresAtEpochMillis = expiresAt?.let { Math.multiplyExact(it, 1000L) }
                ?: expiresIn?.let { Math.addExact(nowEpochMillis, Math.multiplyExact(it, 1000L)) },
            account = account,
        )
    }
}

@Serializable
private data class SupabaseUserResponse(
    val id: String,
    val email: JsonElement? = null,
    @SerialName("user_metadata")
    val userMetadata: JsonElement? = null,
    @SerialName("created_at")
    val createdAt: JsonElement? = null,
) {
    fun makeAccount(nowEpochMillis: Long): GradeyAccount =
        GradeyAccount(
            id = id,
            email = email.stringContent(),
            fullName = metadataString("full_name") ?: metadataString("name"),
            avatarURL = metadataString("avatar_url"),
            createdAtEpochMillis = createdAtEpochMillis() ?: nowEpochMillis,
        )

    fun mergeInto(account: GradeyAccount, nowEpochMillis: Long): GradeyAccount {
        if (id.isBlank() || id != account.id) throw AccountValidationException(AccountFailureReason.ACCOUNT_IDENTITY_MISMATCH)
        val responseAccount = makeAccount(nowEpochMillis)
        return account.copy(
            email = responseAccount.email ?: account.email,
            fullName = responseAccount.fullName ?: account.fullName,
            avatarURL = responseAccount.avatarURL ?: account.avatarURL,
            createdAtEpochMillis = createdAtEpochMillis() ?: account.createdAtEpochMillis,
        )
    }

    private fun metadataString(key: String): String? =
        (userMetadata as? JsonObject)?.get(key).stringContent()

    fun createdAtEpochMillis(): Long? = createdAt.stringContent()
        ?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}

private fun String?.nonEmptyOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

private fun JsonElement?.stringContent(): String? =
    (this as? JsonPrimitive)
        ?.takeIf(JsonPrimitive::isString)
        ?.contentOrNull
        .nonEmptyOrNull()

@Serializable
private data class RegisterDeviceRequest(
    val token: String,
    val platform: String,
    val environment: String,
)

@Serializable
private data class RefreshTokenRequest(@SerialName("refresh_token") val refreshToken: String)

@Serializable
private data class NotificationPreferencesRequest(
    val newMarksEnabled: Boolean,
    val lockScreenDetail: com.bukovinafilip.gradey.model.NotificationLockScreenDetail,
    val quietHoursTimeZoneIdentifier: String,
    val quietHoursEnabled: Boolean,
    val quietHoursStartMinute: Int,
    val quietHoursEndMinute: Int,
)

fun validNotificationTimeZone(value: String): String {
    if (value !in java.time.ZoneId.getAvailableZoneIds()) throw AccountValidationException(AccountFailureReason.TIME_ZONE_INVALID)
    return value
}

fun notificationTimeToMinutes(value: String): Int {
    if (!Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(value)) throw AccountValidationException(AccountFailureReason.TIME_INVALID)
    return value.substringBefore(':').toInt() * 60 + value.substringAfter(':').toInt()
}

internal inline fun <reified T> decodeSupabaseResponse(body: String): T = try {
    GradeyJson.decodeFromString(body)
} catch (_: kotlinx.serialization.SerializationException) {
    // Serialization exceptions may embed the input, including access and refresh tokens.
    throw SupabaseApiException(502, AccountFailureReason.MALFORMED_RESPONSE)
}

internal suspend fun safeSupabaseResponse(client: OkHttpClient, request: Request): String = try {
    client.executeString(request)
} catch (error: GradeyApiException) {
    // Provider responses can contain credentials or implementation details; keep UI errors safe.
    val reason = when (error.statusCode) {
        400, 422 -> AccountFailureReason.REQUEST_REJECTED
        401, 403 -> AccountFailureReason.SESSION_EXPIRED
        404 -> AccountFailureReason.ACCOUNT_NOT_FOUND
        409 -> AccountFailureReason.SCHOOL_RECONNECT_REQUIRED
        429 -> AccountFailureReason.RATE_LIMITED
        else -> AccountFailureReason.SERVICE_UNAVAILABLE
    }
    throw SupabaseApiException(error.statusCode, reason)
}

class SupabaseApiException(statusCode: Int, override val failureReason: AccountFailureReason) :
    GradeyApiException(statusCode, failureReason.defaultMessage), UserFacingFailure

@Serializable
private data class SupabaseAuthSettings(val external: Map<String, Boolean> = emptyMap())

@Serializable
private data class ExchangePKCERequest(
    @SerialName("auth_code") val authCode: String,
    @SerialName("code_verifier") val codeVerifier: String,
)
