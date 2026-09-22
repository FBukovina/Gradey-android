package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.DevicePushTokenClient
import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.model.GradeyAccount
import com.bukovinafilip.gradey.model.GradeyAuthSession
import com.bukovinafilip.gradey.model.NotificationPreferences
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
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
) {
    val isConfigured: Boolean get() = url.isNotBlank() && anonKey.isNotBlank()
}

class SupabaseGradeyAuthRepository(
    private val configuration: SupabaseConfiguration,
    private val sessionStore: suspend (GradeyAuthSession?) -> Unit,
    private val sessionLoader: suspend () -> GradeyAuthSession?,
    private val okHttpClient: OkHttpClient = OkHttpClient(),
) : GradeyAuthRepository {
    private val sessionMutex = Mutex()

    override suspend fun cachedSession(): GradeyAuthSession? = sessionLoader()

    override suspend fun bootstrapSession(): GradeyAuthSession? = sessionMutex.withLock {
        val stored = sessionLoader() ?: return@withLock null
        if (stored.expiresAtEpochMillis?.let { it > System.currentTimeMillis() + 60_000 } != false) {
            return@withLock stored
        }
        val refreshToken = stored.refreshToken ?: run {
            sessionStore(null)
            return@withLock null
        }
        ensureConfigured()
        try {
            val response: SupabaseTokenResponse = send(
                "auth/v1/token?grant_type=refresh_token",
                GradeyJson.encodeToString(RefreshTokenRequest(refreshToken)),
            )
            response.makeSession(stored.account.fullName).also { sessionStore(it) }
        } catch (error: GradeyApiException) {
            if (error.statusCode == 400 || error.statusCode == 401 || error.statusCode == 403) {
                sessionStore(null)
                null
            } else throw error
        }
    }

    override suspend fun signInWithGoogle(idToken: String, accessToken: String?, fullName: String?): GradeyAuthSession = sessionMutex.withLock {
        require(idToken.isNotBlank()) { "Missing Google identity token." }
        ensureConfigured()
        val response: SupabaseTokenResponse = send(
            path = "auth/v1/token?grant_type=id_token",
            body = GradeyJson.encodeToString(SignInWithIDTokenRequest("google", idToken, accessToken)),
        )
        val session = response.makeSession(fullName)
        sessionStore(session)
        session
    }

    override suspend fun signOut() = sessionMutex.withLock {
        val session = sessionLoader()
        sessionStore(null)
        if (session != null && configuration.isConfigured) {
            try {
                val request = Request.Builder()
                    .url(configuration.url.appendPath("auth/v1/logout"))
                    .post(jsonBody("{}"))
                    .header("apikey", configuration.anonKey)
                    .header("Authorization", session.authorizationHeader)
                    .build()
                okHttpClient.executeString(request)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Local sign-out must also work offline. */ }
        }
    }

    private suspend inline fun <reified T> send(path: String, body: String): T {
        val request = Request.Builder()
            .url(configuration.url.appendPath(path))
            .post(jsonBody(body))
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .header("apikey", configuration.anonKey)
            .build()
        return GradeyJson.decodeFromString(safeSupabaseResponse(okHttpClient, request))
    }

    private fun ensureConfigured() {
        if (!configuration.isConfigured) throw IllegalStateException("Supabase is not configured.")
    }
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
                quietHoursEnabled = preferences.quietHoursEnabled,
                quietHoursStartMinute = notificationTimeToMinutes(preferences.quietHoursStart),
                quietHoursEndMinute = notificationTimeToMinutes(preferences.quietHoursEnd),
            ),
        )
    }

    private suspend inline fun <reified T> send(function: String, session: GradeyAuthSession, body: T) {
        if (!configuration.isConfigured) throw IllegalStateException("Supabase is not configured.")
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
private data class SupabaseTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("token_type")
    val tokenType: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long? = null,
    val user: SupabaseUserResponse,
) {
    fun makeSession(fullName: String?): GradeyAuthSession =
        GradeyAuthSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            tokenType = tokenType ?: "Bearer",
            expiresAtEpochMillis = expiresIn?.let { System.currentTimeMillis() + it * 1000L },
            account = user.makeAccount(fullName),
        )
}

@Serializable
private data class SupabaseUserResponse(
    val id: String,
    val email: String? = null,
    @SerialName("user_metadata")
    val userMetadata: JsonObject = JsonObject(emptyMap()),
    @SerialName("created_at")
    val createdAt: String? = null,
) {
    fun makeAccount(fullName: String?): GradeyAccount =
        GradeyAccount(
            id = id,
            email = email,
            fullName = fullName?.takeIf { it.isNotBlank() } ?: userMetadata["full_name"]?.jsonPrimitive?.contentOrNull ?: userMetadata["name"]?.jsonPrimitive?.contentOrNull,
            avatarURL = userMetadata["avatar_url"]?.jsonPrimitive?.contentOrNull,
        )
}

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
    val quietHoursEnabled: Boolean,
    val quietHoursStartMinute: Int,
    val quietHoursEndMinute: Int,
)

fun notificationTimeToMinutes(value: String): Int {
    require(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(value)) { "Use a valid time in HH:mm format." }
    return value.substringBefore(':').toInt() * 60 + value.substringAfter(':').toInt()
}

internal suspend fun safeSupabaseResponse(client: OkHttpClient, request: Request): String = try {
    client.executeString(request)
} catch (error: GradeyApiException) {
    // Provider responses can contain credentials or implementation details; keep UI errors safe.
    val message = when (error.statusCode) {
        400, 422 -> "The account request was rejected. Check your details and try again."
        401, 403 -> "Your Gradey session has expired. Please sign in again."
        404 -> "This account is no longer available. Refresh your accounts."
        409 -> "This school account needs to be connected again."
        429 -> "Too many requests. Please try again in a moment."
        else -> "Gradey could not complete the request. Please try again."
    }
    throw GradeyApiException(error.statusCode, message)
}
