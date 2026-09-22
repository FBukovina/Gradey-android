package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.EduPageClient
import com.bukovinafilip.gradey.domain.SchoolLoginStep
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.EduPageSessionData
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.TimetableResponse
import com.bukovinafilip.gradey.model.UserResponse
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup

class OkHttpEduPageClient(okHttpClient: OkHttpClient = OkHttpClient()) : EduPageClient {
    private data class PendingLogin(
        val baseURL: String,
        val username: String,
        val password: String,
        val authenticationEndpoint: String? = null,
        val authenticationToken: String? = null,
        val csrfToken: String? = null,
        val approvedCode: String? = null,
    )

    private data class TextResponse(val text: String, val url: HttpUrl)
    private val client = okHttpClient.newBuilder()
        .cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false)
        .followSslRedirects(false)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()
    private val loginMutex = Mutex()
    private val cookies = mutableListOf<Cookie>()
    private var pendingLogin: PendingLogin? = null
    private var pendingSession: EduPageSessionData? = null

    override suspend fun beginLogin(baseURL: String, username: String, password: String): SchoolLoginStep = loginMutex.withLock {
        if (username.isBlank() || password.isEmpty()) fail(EduPageAuthFailure.MISSING_FIELDS, "Enter your EduPage username and password.")
        synchronized(cookies) { cookies.clear() }
        pendingLogin = null
        pendingSession = null
        var pending = PendingLogin(baseURL, username.trim(), password)
        val loginPage = send(baseURL, "login/", query = mapOf("cmd" to "MainLogin"))
        val csrf = inputValue(loginPage.text, "csrfauth")
            ?: EduPageHtmlParser.firstCapture(loginPage.text, listOf("\"csrftoken\":\""))
            ?: fail(EduPageAuthFailure.MALFORMED_RESPONSE, "Missing EduPage login token. Try again.")
        val response = send(baseURL, "login/edubarLogin.php", fields = mapOf(
            "csrfauth" to csrf, "username" to username.trim(), "password" to password,
        ))
        if (response.url.queryParameter("cap") == "1" || response.url.queryParameter("lerr") == "b43b43") {
            fail(EduPageAuthFailure.CAPTCHA_REQUIRED, "EduPage requires a CAPTCHA. Sign in on your school's website, then try again.")
        }
        if (response.url.queryParameter("bad") == "1") fail(EduPageAuthFailure.BAD_CREDENTIALS, "Incorrect EduPage username or password.")
        if (response.url.encodedPath.contains("twofactor", true) || response.text.contains("/login/twofactor")) {
            val twoFactorPage = if (response.url.encodedPath.contains("twofactor", true)) response
                else send(baseURL, "login/twofactor", query = mapOf("sn" to "1"))
            pending = pending.copy(
                csrfToken = inputValue(twoFactorPage.text, "csrfauth"),
                authenticationToken = inputValue(twoFactorPage.text, "au"),
                authenticationEndpoint = inputValue(twoFactorPage.text, "gu"),
            )
            if (listOf(pending.csrfToken, pending.authenticationToken, pending.authenticationEndpoint).any { it.isNullOrBlank() }) {
                fail(EduPageAuthFailure.MALFORMED_RESPONSE, "Missing EduPage two-factor fields. Start sign-in again.")
            }
            pendingLogin = pending
            SchoolLoginStep.TwoFactor()
        } else {
            pendingLogin = pending
            finishLogin(response.text)
        }
    }

    override suspend fun completeTwoFactor(code: String): SchoolLoginStep = loginMutex.withLock {
        if (code.isBlank()) fail(EduPageAuthFailure.INVALID_TWO_FACTOR_CODE, "Enter your verification code.")
        finishTwoFactor(code.trim())
    }

    override suspend fun completeApprovedTwoFactor(): SchoolLoginStep = loginMutex.withLock {
        val code = pendingLogin?.approvedCode
            ?: fail(EduPageAuthFailure.TWO_FACTOR_NOT_CONFIRMED, "Approve the sign-in on your trusted device first.")
        finishTwoFactor(code)
    }

    override suspend fun isTwoFactorConfirmed(): Boolean = loginMutex.withLock {
        val pending = requirePending()
        val response = send(pending.baseURL, "login/twofactor", query = mapOf("akcia" to "checkIfConfirmed"), fields = emptyMap())
        val status = parseStatus(response.text)
        if (status["status"].text() != "ok") return@withLock false
        val code = status["data"].text()?.takeIf { it.isNotBlank() }
        pendingLogin = pending.copy(approvedCode = code)
        code != null
    }

    override suspend fun resendTwoFactorNotification() = loginMutex.withLock {
        val pending = requirePending()
        val response = send(pending.baseURL, "login/twofactor", query = mapOf("akcia" to "resendNotifs"), fields = emptyMap())
        if (parseStatus(response.text)["status"].text() != "ok") {
            fail(EduPageAuthFailure.MALFORMED_RESPONSE, "EduPage could not resend the approval notification. Try again.")
        }
    }

    override suspend fun selectStudent(studentID: String): EduPageSessionData = loginMutex.withLock {
        val pending = requirePending()
        val session = pendingSession ?: fail(EduPageAuthFailure.INVALID_STUDENT, "Start sign-in before choosing a student.")
        val student = session.linkedStudents.firstOrNull { it.id == studentID }
            ?: fail(EduPageAuthFailure.INVALID_STUDENT, "This student is not linked to the EduPage account.")
        val response = send(pending.baseURL, "login/switchchild", query = mapOf("studentid" to EduPageResponseParser.numericID(studentID)))
        if (response.text.trim() != "OK") fail(EduPageAuthFailure.INVALID_STUDENT, "EduPage could not select this student.")
        pendingLogin = null
        pendingSession = null
        session.copy(activeStudent = student)
    }

    override suspend fun switchStudent(studentID: String, session: EduPageSessionData, baseURL: String): EduPageSessionData {
        val student = session.linkedStudents.firstOrNull { it.id == studentID }
            ?: fail(EduPageAuthFailure.INVALID_STUDENT, "This student is not linked to the EduPage account.")
        val response = authenticated(baseURL, session, "login/switchchild",
            query = mapOf("studentid" to EduPageResponseParser.numericID(studentID)), allowLoginPath = true)
        if (response.text.trim() != "OK") fail(EduPageAuthFailure.INVALID_STUDENT, "EduPage could not select this student.")
        return session.copy(activeStudent = student)
    }

    override suspend fun restore(stored: EduPageSessionData, baseURL: String): EduPageSessionData {
        try {
            val page = authenticated(baseURL, stored, "user")
            val refreshed = EduPageResponseParser.session(page.text, stored.username, stored.password, stored.sessionID)
            val selected = stored.activeStudent ?: return refreshed
            return switchStudent(selected.id, refreshed, baseURL)
        } catch (error: EduPageAuthenticationException) {
            if (error.reason != EduPageAuthFailure.SESSION_EXPIRED) throw error
            return when (val step = beginLogin(baseURL, stored.username, stored.password)) {
                is SchoolLoginStep.SignedIn -> step.session.eduPage
                    ?: fail(EduPageAuthFailure.MALFORMED_RESPONSE, "Missing restored EduPage session.")
                is SchoolLoginStep.StudentSelection -> selectStudent(stored.activeStudent?.id
                    ?: fail(EduPageAuthFailure.INVALID_STUDENT, "Sign in again to choose a student."))
                is SchoolLoginStep.TwoFactor -> fail(EduPageAuthFailure.TWO_FACTOR_REQUIRED, "Your EduPage session expired. Sign in again to approve verification.")
            }
        }
    }

    override suspend fun fetchMarks(baseURL: String, session: EduPageSessionData): MarksResponse =
        EduPageResponseParser.marks(authenticated(baseURL, session, "znamky/", query = mapOf("barNoSkin" to "1")).text, session.subjects)

    override suspend fun fetchAbsences(baseURL: String, session: EduPageSessionData): AbsenceResponse {
        val studentID = session.activeStudent?.id ?: session.userID
        return EduPageResponseParser.absences(authenticated(baseURL, session, "dashboard/eb.php",
            query = mapOf("ebuid" to studentID, "mode" to "attendance")).text, studentID)
    }

    override suspend fun fetchUser(baseURL: String, session: EduPageSessionData): UserResponse = UserResponse(
        fullName = session.activeStudent?.fullName ?: session.username,
        schoolName = session.schoolName,
        classAbbrev = session.activeStudent?.className,
        userUID = session.activeStudent?.id ?: session.userID,
    )

    override suspend fun fetchTimetable(baseURL: String, session: EduPageSessionData, weekStart: String): TimetableResponse {
        val start = LocalDate.parse(weekStart)
        val dashboard = authenticated(baseURL, session, "dashboard/eb.php", query = mapOf("mode" to "ttday"))
        val gpid = Regex("gpid=\"?(\\d+)").findAll(dashboard.text).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()?.plus(1)?.toString()
            ?: fail(EduPageAuthFailure.MALFORMED_RESPONSE, "Missing timetable request token. Refresh and try again.")
        val gsh = EduPageHtmlParser.firstCapture(dashboard.text, listOf("gsh=", "gsechash=\""))
            ?: session.gsecHash
        if (gsh.isBlank()) fail(EduPageAuthFailure.MALFORMED_RESPONSE, "Missing timetable security token. Sign in again.")
        val response = authenticated(baseURL, session, "gcall", fields = mapOf(
            "gpid" to gpid, "gsh" to gsh, "action" to "loadData",
            "user" to (session.activeStudent?.id ?: session.userID), "changes" to "{}",
            "date" to start.toString(), "dateto" to start.plusDays(6).toString(), "_LJSL" to "4096",
        ))
        return EduPageResponseParser.timetable(response.text, session.subjects)
    }

    private suspend fun finishTwoFactor(code: String): SchoolLoginStep {
        val pending = requirePending()
        val csrf = pending.csrfToken ?: fail(EduPageAuthFailure.TWO_FACTOR_REQUIRED, "Start EduPage sign-in again.")
        val response = send(pending.baseURL, "login/edubarLogin.php", fields = mapOf(
            "csrfauth" to csrf, "t2fasec" to code, "2fNoSave" to "y", "2fform" to "1",
            "gu" to pending.authenticationEndpoint.orEmpty(), "au" to pending.authenticationToken.orEmpty(),
        ))
        if ((response.url.encodedPath.contains("twofactor", true) || response.text.contains("name=\"t2fasec\"")) &&
            !response.text.contains("window.location = gu;")) {
            fail(EduPageAuthFailure.INVALID_TWO_FACTOR_CODE, "That verification code was not accepted. Try again.")
        }
        return finishLogin(send(pending.baseURL, "user").text)
    }

    private suspend fun finishLogin(html: String): SchoolLoginStep {
        val pending = requirePending()
        val sessionID = synchronized(cookies) {
            cookies.firstOrNull { it.name == "PHPSESSID" && it.matches(pending.baseURL.toHttpUrl()) }?.value
        } ?: fail(EduPageAuthFailure.MALFORMED_RESPONSE, "EduPage did not return a session cookie.")
        val userHTML = if (EduPageHtmlParser.firstJSONObject(html, "userid") != null) html else send(pending.baseURL, "user").text
        val session = EduPageResponseParser.session(userHTML, pending.username, pending.password, sessionID)
        if (EduPageResponseParser.isParent(session.userID)) {
            if (session.linkedStudents.isEmpty()) fail(EduPageAuthFailure.NO_LINKED_STUDENTS, "This parent account has no linked students.")
            pendingSession = session
            return SchoolLoginStep.StudentSelection(session.linkedStudents)
        }
        pendingLogin = null
        pendingSession = null
        return SchoolLoginStep.SignedIn(StoredSession(
            accessToken = session.sessionID, refreshToken = "", tokenType = "Cookie",
            expiresAtEpochMillis = Long.MAX_VALUE, baseURL = pending.baseURL,
            provider = SchoolProvider.EDU_PAGE, eduPage = session,
        ))
    }

    private suspend fun authenticated(
        baseURL: String,
        session: EduPageSessionData,
        path: String,
        query: Map<String, String> = emptyMap(),
        fields: Map<String, String>? = null,
        allowLoginPath: Boolean = false,
    ): TextResponse {
        val response = send(baseURL, path, query, fields, session.sessionID)
        val switched = allowLoginPath && response.url.encodedPath.endsWith("/login/switchchild")
        if ((!switched && response.url.encodedPath.contains("/login", true)) || EduPageResponseParser.looksLikeLoginPage(response.text)) {
            fail(EduPageAuthFailure.SESSION_EXPIRED, "Your EduPage session has expired. Sign in again.")
        }
        return response
    }

    private suspend fun send(
        baseURL: String,
        path: String,
        query: Map<String, String> = emptyMap(),
        fields: Map<String, String>? = null,
        sessionID: String? = null,
    ): TextResponse {
        val origin = baseURL.toHttpUrl()
        var url = baseURL.appendPath(path).toHttpUrl().newBuilder().apply {
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build()
        var form = fields
        repeat(8) {
            val request = Request.Builder().url(url)
            if (form == null) request.get() else request.post(formBody(form!!))
            val cookie = sessionID?.let { "PHPSESSID=$it" } ?: synchronized(cookies) {
                cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
                cookies.filter { it.matches(url) }.joinToString("; ") { "${it.name}=${it.value}" }
            }
            if (cookie.isNotBlank()) request.header("Cookie", cookie)
            val response = execute(request.build())
            response.use {
                if (sessionID == null) synchronized(cookies) {
                    Cookie.parseAll(url, response.headers).forEach { incoming ->
                        cookies.removeAll { it.name == incoming.name && it.domain == incoming.domain && it.path == incoming.path }
                        cookies.add(incoming)
                    }
                }
                if (response.code in listOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location")?.let(url::resolve)
                        ?: fail(EduPageAuthFailure.MALFORMED_RESPONSE, "Invalid EduPage redirect.")
                    // Prevent credentials and the explicit session cookie from following a foreign redirect.
                    if (location.host != origin.host || location.scheme != origin.scheme || location.port != origin.port) {
                        fail(EduPageAuthFailure.MALFORMED_RESPONSE, "EduPage redirected to another server. Sign in on your school's website and try again.")
                    }
                    url = location
                    if (response.code in listOf(301, 302, 303)) form = null
                } else {
                    if (sessionID != null && response.code in listOf(401, 403)) {
                        fail(EduPageAuthFailure.SESSION_EXPIRED, "Your EduPage session has expired. Sign in again.")
                    }
                    if (!response.isSuccessful) throw GradeyApiException(response.code, "EduPage request failed (HTTP ${response.code}). Try again.")
                    val text = withContext(Dispatchers.IO) { response.body?.string().orEmpty() }
                    return TextResponse(text, url)
                }
            }
        }
        fail(EduPageAuthFailure.MALFORMED_RESPONSE, "EduPage redirected too many times. Try again.")
    }

    private suspend fun execute(request: Request): Response = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) continuation.resumeWithException(e)
            }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }

    private fun requirePending(): PendingLogin = pendingLogin
        ?: fail(EduPageAuthFailure.TWO_FACTOR_REQUIRED, "Start EduPage sign-in again.")
    private fun inputValue(html: String, name: String): String? =
        Jsoup.parse(html).selectFirst("input[name=$name]")?.attr("value")?.takeIf { it.isNotBlank() }
    private fun parseStatus(text: String) = runCatching { GradeyJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonObject }.getOrNull()
        ?: fail(EduPageAuthFailure.MALFORMED_RESPONSE, "Invalid EduPage verification response.")
    private fun fail(reason: EduPageAuthFailure, message: String): Nothing = eduPageFailure(reason, message)
}
