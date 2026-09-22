package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class StravaCZApiException(
    message: String,
    val authenticationRequired: Boolean = false,
) : IOException(message)

interface StravaCZClient {
    suspend fun login(canteenNumber: String, username: String, password: String): StravaCZStoredSession
    suspend fun fetchMenu(session: StravaCZStoredSession): StravaCZMenu
    suspend fun changeMeal(session: StravaCZStoredSession, mealID: Int, ordered: Boolean): String?
    suspend fun saveOrders(session: StravaCZStoredSession): String?
    suspend fun cancelChanges(session: StravaCZStoredSession): String?
    suspend fun logout(session: StravaCZStoredSession)
}

/** Provider protocol ported from Gradely/Services/StravaCZClient.swift. */
class StravaCZNetworkClient(
    private val baseURL: String = "https://app.strava.cz",
    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(StravaCookieJar())
        // Replaying saveOrders after an interrupted connection can duplicate a mutation.
        .retryOnConnectionFailure(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) : StravaCZClient {
    override suspend fun login(canteenNumber: String, username: String, password: String): StravaCZStoredSession {
        require(canteenNumber.isNotBlank() && username.isNotBlank() && password.isNotEmpty()) {
            "Enter your canteen number, username, and password."
        }
        execute(Request.Builder().url(baseURL.appendPath("en/prihlasit-se?jidelna")).build())
        return parseStravaSession(post("login", buildJsonObject {
            put("cislo", canteenNumber.trim())
            put("jmeno", username.trim())
            put("heslo", password)
            put("zustatPrihlasen", true)
            put("environment", "W")
            put("lang", "EN")
        }))
    }

    override suspend fun fetchMenu(session: StravaCZStoredSession): StravaCZMenu =
        StravaCZMenuMapper.parse(post("objednavky", buildJsonObject {
            put("cislo", session.canteenNumber)
            put("sid", session.sessionID)
            put("s5url", session.serviceURL)
            put("lang", "EN")
            put("konto", session.balance?.replace(',', '.')?.toDoubleOrNull() ?: 0.0)
            put("podminka", "")
            put("ignoreCert", false)
        }))

    override suspend fun changeMeal(session: StravaCZStoredSession, mealID: Int, ordered: Boolean): String? =
        post("pridejJidloS5", sessionPayload(session, mapOf("veta" to mealID.toString(), "pocet" to if (ordered) "1" else "0")))
            .decimal("konto")?.toString()

    override suspend fun saveOrders(session: StravaCZStoredSession): String? =
        post("saveOrders", sessionPayload(session)).decimal("konto")?.toString()

    override suspend fun cancelChanges(session: StravaCZStoredSession): String? =
        post("nactiVlastnostiPA", buildJsonObject {
            sessionPayload(session).forEach { (key, value) -> put(key, value) }
            put("getText", true)
            put("checkVersion", true)
            put("resetTables", true)
            put("frontendFunction", "refreshInformations")
        }).decimal("konto")?.toString()

    override suspend fun logout(session: StravaCZStoredSession) {
        post("logOut", sessionPayload(session), emptyResponseAllowed = true)
    }

    private fun sessionPayload(session: StravaCZStoredSession, extra: Map<String, String> = emptyMap()) = buildJsonObject {
        put("cislo", session.canteenNumber)
        put("sid", session.sessionID)
        put("url", session.serviceURL)
        put("lang", "EN")
        put("ignoreCert", "false")
        extra.forEach { (key, value) -> put(key, value) }
    }

    private suspend fun post(endpoint: String, body: JsonObject, emptyResponseAllowed: Boolean = false): JsonObject {
        val request = Request.Builder()
            .url(baseURL.appendPath("api/$endpoint"))
            .post(body.toString().toRequestBody("text/plain;charset=UTF-8".toMediaType()))
            .header("Accept", "*/*")
            .header("Accept-Language", "en-US,en;q=0.9,cs;q=0.8")
            .header("Origin", baseURL.trimEnd('/'))
            .header("Referer", baseURL.appendPath("en/prihlasit-se?jidelna"))
            .build()
        val (statusCode, raw) = execute(request)
        return run {
            val decoded = runCatching { GradeyJson.parseToJsonElement(raw) as? JsonObject }.getOrNull()
            val errorNumber = decoded?.text("number")?.toIntOrNull()
            if (statusCode !in 200..299 || (errorNumber != null && errorNumber != 0)) {
                val authentication = statusCode == 401 || statusCode == 403
                val message = when {
                    errorNumber == 35 -> "Your canteen balance is too low for this order."
                    authentication -> "Your Strava.cz session has expired. Please connect your account again."
                    else -> decoded?.text("message")?.takeIf { it.isNotBlank() && it.length <= 350 }
                        ?: "Strava.cz could not complete the request (HTTP ${statusCode}). Please try again."
                }
                throw StravaCZApiException(message, authenticationRequired = authentication)
            }
            decoded ?: if (emptyResponseAllowed && raw.isBlank()) JsonObject(emptyMap()) else {
                throw StravaCZApiException("Strava.cz returned an unreadable response. Please try again.")
            }
        }
    }

    private suspend fun execute(request: Request): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val result = response.use { it.code to it.body?.string().orEmpty() }
                    if (continuation.isActive) continuation.resume(result)
                } catch (error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }
}

private class StravaCookieJar : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { new ->
            this.cookies.removeAll { it.name == new.name && it.domain == new.domain && it.path == new.path }
            this.cookies.add(new)
        }
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        cookies.removeAll { it.expiresAt < System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }
}
