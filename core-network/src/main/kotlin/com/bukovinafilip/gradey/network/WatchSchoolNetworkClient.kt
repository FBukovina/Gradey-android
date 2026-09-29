package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.*
import com.bukovinafilip.gradey.model.*
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

sealed interface WatchSchoolRefresh {
    data object VerificationRequired : WatchSchoolRefresh
    class Loaded(val session: StoredSession, val user: UserResponse, val week: TimetableWeek) : WatchSchoolRefresh
}

/** Separate client instances and token families belong exclusively to this watch grant. */
class WatchSchoolNetworkClient(
    private val bakalari: BakalariClient,
    private val eduPage: EduPageClient,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var pending: WearSchoolCredentials? = null

    suspend fun refresh(credentials: WearSchoolCredentials, stored: StoredSession?): WatchSchoolRefresh = guarded {
        requireCredentials(credentials)
        val session = stored?.takeIf { it.provider == credentials.provider && it.baseURL == credentials.baseURL }
        if (session == null) return@guarded finish(credentials, login(credentials))
        if (credentials.provider == SchoolProvider.EDU_PAGE) {
            val previous = session.eduPage ?: return@guarded finish(credentials, login(credentials))
            try {
                val restored = eduPage.restore(previous, credentials.baseURL)
                return@guarded load(credentials, eduSession(credentials, restored))
            } catch (e: EduPageAuthenticationException) {
                if (e.reason == EduPageAuthFailure.TWO_FACTOR_REQUIRED) { pending = credentials; return@guarded WatchSchoolRefresh.VerificationRequired }
                if (e.reason != EduPageAuthFailure.SESSION_EXPIRED) throw e
                return@guarded finish(credentials, login(credentials))
            }
        }
        var current = session
        if (current.isExpired(now())) current = renewBakalari(credentials, current)
        try { load(credentials, current) }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            if (!isBakalariAuthFailure(e)) throw e
            load(credentials, renewBakalari(credentials, current))
        }
    }

    suspend fun completeCode(code: String): WatchSchoolRefresh = guarded {
        val credentials = pending ?: throw WearSchoolException(WearSchoolFailure.RECONNECT_REQUIRED)
        if (!Regex("[A-Za-z0-9 -]{1,32}").matches(code.trim())) throw WearSchoolException(WearSchoolFailure.INVALID_CODE)
        finish(credentials, eduPage.completeTwoFactor(code.trim()))
    }
    suspend fun checkApproval(): WatchSchoolRefresh = guarded {
        val credentials = pending ?: throw WearSchoolException(WearSchoolFailure.RECONNECT_REQUIRED)
        if (!eduPage.isTwoFactorConfirmed()) throw WearSchoolException(WearSchoolFailure.NOT_APPROVED)
        finish(credentials, eduPage.completeApprovedTwoFactor())
    }
    suspend fun resendApproval() = guarded {
        if (pending == null) throw WearSchoolException(WearSchoolFailure.RECONNECT_REQUIRED)
        eduPage.resendTwoFactorNotification()
    }

    private suspend fun login(credentials: WearSchoolCredentials): SchoolLoginStep = when (credentials.provider) {
        SchoolProvider.BAKALARI -> {
            val response = bakalari.login(credentials.baseURL, credentials.username, credentials.password)
            SchoolLoginStep.SignedIn(bakalariSession(credentials, response))
        }
        SchoolProvider.EDU_PAGE -> eduPage.beginLogin(credentials.baseURL, credentials.username, credentials.password)
    }
    private suspend fun finish(credentials: WearSchoolCredentials, step: SchoolLoginStep): WatchSchoolRefresh = when (step) {
        is SchoolLoginStep.TwoFactor -> { pending = credentials; WatchSchoolRefresh.VerificationRequired }
        is SchoolLoginStep.StudentSelection -> {
            val selected = credentials.selectedStudentID ?: throw WearSchoolException(WearSchoolFailure.IDENTITY_MISMATCH)
            if (step.students.none { it.id == selected }) throw WearSchoolException(WearSchoolFailure.IDENTITY_MISMATCH)
            load(credentials, eduSession(credentials, eduPage.selectStudent(selected)))
        }
        is SchoolLoginStep.SignedIn -> load(credentials, step.session)
    }
    private suspend fun load(credentials: WearSchoolCredentials, session: StoredSession): WatchSchoolRefresh.Loaded {
        val user = when (credentials.provider) {
            SchoolProvider.BAKALARI -> bakalari.fetchUser(credentials.baseURL, session.accessToken)
            SchoolProvider.EDU_PAGE -> eduPage.fetchUser(credentials.baseURL, session.eduPage ?: throw WearSchoolException(WearSchoolFailure.INVALID_RESPONSE))
        }
        if (user.userUID != credentials.expectedUserID) throw WearSchoolException(WearSchoolFailure.IDENTITY_MISMATCH)
        val monday = TimetableDates.monday(Instant.ofEpochMilli(now()).atZone(ZoneId.of("Europe/Prague")).toLocalDate()).toString()
        val response = when (credentials.provider) {
            SchoolProvider.BAKALARI -> bakalari.fetchTimetable(credentials.baseURL, session.accessToken, monday)
            SchoolProvider.EDU_PAGE -> eduPage.fetchTimetable(credentials.baseURL, session.eduPage!!, monday)
        }
        pending = null
        return WatchSchoolRefresh.Loaded(session, user, TimetableMapper.makeWeek(response, monday))
    }
    private suspend fun renewBakalari(credentials: WearSchoolCredentials, current: StoredSession): StoredSession {
        val response = try {
            if (current.refreshToken.isBlank()) throw WearSchoolException(WearSchoolFailure.RECONNECT_REQUIRED)
            bakalari.refreshToken(credentials.baseURL, current.refreshToken)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            if (e !is WearSchoolException && !isBakalariAuthFailure(e)) throw e
            bakalari.login(credentials.baseURL, credentials.username, credentials.password)
        }
        return bakalariSession(credentials, response)
    }
    private fun bakalariSession(credentials: WearSchoolCredentials, response: LoginResponse): StoredSession {
        if (response.accessToken.isBlank() || response.refreshToken.isBlank() || response.expiresIn <= 0) throw WearSchoolException(WearSchoolFailure.INVALID_RESPONSE)
        return StoredSession(response.accessToken, response.refreshToken, response.tokenType, now() + response.expiresIn.coerceAtMost(365L * 24 * 60 * 60) * 1000,
            credentials.baseURL, provider = SchoolProvider.BAKALARI)
    }
    private fun eduSession(credentials: WearSchoolCredentials, session: EduPageSessionData) = StoredSession(session.sessionID, "", "Cookie", Long.MAX_VALUE,
        credentials.baseURL, provider = SchoolProvider.EDU_PAGE, eduPage = session)
    private fun requireCredentials(credentials: WearSchoolCredentials) {
        if (!WearSchoolProtocol.valid(credentials)) throw WearSchoolException(WearSchoolFailure.INVALID_RESPONSE)
    }
    private fun isBakalariAuthFailure(error: Exception): Boolean = (error as? UserFacingFailure)?.failureReason in setOf(
        AccountFailureReason.SCHOOL_BAD_CREDENTIALS, AccountFailureReason.SCHOOL_SESSION_EXPIRED, AccountFailureReason.SCHOOL_ACCESS_DENIED,
    )
    private suspend fun <T> guarded(block: suspend () -> T): T = try { block() }
    catch (e: Exception) {
        if (e is CancellationException || e is WearSchoolException) throw e
        val failure = when (e) {
            is EduPageAuthenticationException -> when (e.reason) {
                EduPageAuthFailure.TWO_FACTOR_REQUIRED -> WearSchoolFailure.TWO_FACTOR
                EduPageAuthFailure.CAPTCHA_REQUIRED -> WearSchoolFailure.CAPTCHA
                EduPageAuthFailure.INVALID_TWO_FACTOR_CODE -> WearSchoolFailure.INVALID_CODE
                EduPageAuthFailure.TWO_FACTOR_NOT_CONFIRMED -> WearSchoolFailure.NOT_APPROVED
                EduPageAuthFailure.INVALID_STUDENT, EduPageAuthFailure.NO_LINKED_STUDENTS -> WearSchoolFailure.IDENTITY_MISMATCH
                EduPageAuthFailure.BAD_CREDENTIALS, EduPageAuthFailure.SESSION_EXPIRED -> WearSchoolFailure.RECONNECT_REQUIRED
                else -> WearSchoolFailure.INVALID_RESPONSE
            }
            is BakalariApiException -> if (isBakalariAuthFailure(e)) WearSchoolFailure.RECONNECT_REQUIRED else WearSchoolFailure.NETWORK
            else -> WearSchoolFailure.NETWORK
        }
        throw WearSchoolException(failure)
    }
}
