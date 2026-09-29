package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.BakalariClient
import com.bukovinafilip.gradey.domain.EduPageClient
import com.bukovinafilip.gradey.domain.EduPageURLNormalizer
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.domain.SchoolLoginStep
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.domain.SchoolURLNormalizer
import com.bukovinafilip.gradey.domain.TimetableDates
import com.bukovinafilip.gradey.domain.TimetableMapper
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.BakalariCredentials
import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.EduPageSessionData
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.TimetableKind
import com.bukovinafilip.gradey.model.TimetableWeek
import com.bukovinafilip.gradey.model.UserResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import com.bukovinafilip.gradey.network.GradeyApiException
import com.bukovinafilip.gradey.network.EduPageAuthenticationException
import com.bukovinafilip.gradey.network.EduPageAuthFailure

class AndroidSchoolRepository(
    private val bakalariClient: BakalariClient,
    private val eduPageClient: EduPageClient,
    private val sessionStore: SchoolSessionStore,
    private val cache: RoomGradeyCache,
    private val dateProvider: () -> LocalDate = LocalDate::now,
) : SchoolRepository {
    private val refreshMutex = Mutex()
    private var pendingEduPageBaseURL: String? = null

    override suspend fun bootstrapSession(): StoredSession? {
        val session = sessionStore.load() ?: return null
        if (session.provider != SchoolProvider.EDU_PAGE) return session
        return try {
            restoreEduPage(session)
        } catch (_: java.io.IOException) {
            // Offline launch can still display the cached school data.
            session
        }
    }

    override suspend fun currentStoredSession(): StoredSession? = sessionStore.load()

    override suspend fun beginLogin(provider: SchoolProvider, schoolURL: String, username: String, password: String): SchoolLoginStep {
        require(username.trim().isNotEmpty() && password.isNotEmpty()) { "Missing username or password." }
        return when (provider) {
            SchoolProvider.BAKALARI -> {
                val baseURL = SchoolURLNormalizer.normalizedBaseURL(schoolURL)
                val trimmedUsername = username.trim()
                val response = bakalariClient.login(baseURL, trimmedUsername, password)
                val session = StoredSession(
                    accessToken = response.accessToken,
                    refreshToken = response.refreshToken,
                    tokenType = response.tokenType,
                    expiresAtEpochMillis = System.currentTimeMillis() + response.expiresIn * 1000L,
                    baseURL = baseURL,
                    provider = SchoolProvider.BAKALARI,
                    bakalari = BakalariCredentials(trimmedUsername, password),
                )
                sessionStore.save(session)
                SchoolLoginStep.SignedIn(session)
            }

            SchoolProvider.EDU_PAGE -> {
                val baseURL = EduPageURLNormalizer.normalizedBaseURL(schoolURL)
                pendingEduPageBaseURL = baseURL
                persistEduPageStep(eduPageClient.beginLogin(baseURL, username.trim(), password))
            }
        }
    }

    override suspend fun completeEduPageTwoFactor(code: String): SchoolLoginStep =
        persistEduPageStep(eduPageClient.completeTwoFactor(code))

    override suspend fun completeApprovedEduPageTwoFactor(): SchoolLoginStep =
        persistEduPageStep(eduPageClient.completeApprovedTwoFactor())

    override suspend fun isEduPageTwoFactorConfirmed(): Boolean = eduPageClient.isTwoFactorConfirmed()

    override suspend fun resendEduPageTwoFactorNotification() = eduPageClient.resendTwoFactorNotification()

    override suspend fun selectEduPageStudent(studentID: String): StoredSession {
        val baseURL = pendingEduPageBaseURL ?: error("No pending EduPage login.")
        val data = eduPageClient.selectStudent(studentID)
        return persistEduPageSession(data, baseURL)
    }

    override suspend fun switchEduPageStudent(studentID: String) {
        val current = sessionStore.load() ?: error("Not signed in.")
        require(current.provider == SchoolProvider.EDU_PAGE)
        val data = current.eduPage ?: error("Missing EduPage session.")
        val updatedData = eduPageClient.switchStudent(studentID, data, current.baseURL)
        val updated = current.copy(accessToken = updatedData.sessionID, eduPage = updatedData)
        cache.clearNextLessonSnapshot()
        sessionStore.save(updated)
    }

    override suspend fun activateLinkedSchoolAccount(session: StoredSession): StoredSession {
        cache.clearNextLessonSnapshot()
        sessionStore.save(session)
        return session
    }

    override suspend fun logout() {
        try {
            sessionStore.load()?.let { cache.clearSchool(it.cacheScope) }
            cache.clearNextLessonSnapshot()
        } finally {
            sessionStore.clear()
            sessionStore.clearLinked()
        }
    }

    override suspend fun loadCachedDashboard(): DashboardData? =
        sessionStore.load()?.let { cache.loadDashboard(it.cacheScope) }

    override suspend fun loadDashboard(forceRefresh: Boolean): DashboardData = coroutineScope {
        val session = validSession()
        val previous = cache.loadDashboard(session.cacheScope)
        val marks = fetchMarks(session)
        cache.saveMarks(session.cacheScope, marks)
        val absence = async { optionalResponse { fetchAbsence(session) } }
        val user = async { optionalResponse { fetchUser(session) } }
        val absenceResult = absence.await()
        if (absenceResult != null) cache.saveAbsence(session.cacheScope, absenceResult)
        val data = DashboardData(
            marksResponse = marks,
            absencesPerSubject = absenceResult?.absencesPerSubject ?: previous?.absencesPerSubject.orEmpty(),
            user = user.await()?.resolvedFor(session) ?: previous?.user,
        )
        cache.saveDashboard(session.cacheScope, data)
        data
    }

    override suspend fun loadAbsence(forceRefresh: Boolean): AbsenceResponse {
        val session = validSession()
        if (!forceRefresh) cache.loadAbsence(session.cacheScope)?.let { return it }
        val response = fetchAbsence(session)
        cache.saveAbsence(session.cacheScope, response)
        return response
    }

    override suspend fun loadCachedTimetable(weekContaining: String): TimetableWeek? {
        val session = sessionStore.load() ?: return null
        val monday = TimetableDates.apiDateString(TimetableDates.monday(LocalDate.parse(weekContaining)))
        return cache.loadTimetable(session.cacheScope, monday)
    }

    override suspend fun loadTimetable(weekContaining: String): TimetableWeek {
        val session = validSession()
        val monday = TimetableDates.apiDateString(TimetableDates.monday(LocalDate.parse(weekContaining)))
        val response = fetchTimetable(session, monday)
        cache.saveRawTimetable(session.cacheScope, monday, response)
        val week = TimetableMapper.makeWeek(response, monday, TimetableDates.apiDateString(dateProvider()))
        cache.saveTimetable(session.cacheScope, monday, week)
        return week
    }

    override suspend fun loadCachedPermanentTimetable(): TimetableWeek? {
        val session = sessionStore.load() ?: return null
        if (session.provider != SchoolProvider.BAKALARI) return null
        return cache.loadTimetable(session.cacheScope, "permanent")
    }

    override suspend fun loadPermanentTimetable(): TimetableWeek {
        val session = validSession()
        check(session.provider == SchoolProvider.BAKALARI) { "The permanent timetable is not available for this school account." }
        val response = withBakalariRetry(session) { bakalariClient.fetchPermanentTimetable(it.baseURL, it.accessToken) }
        val week = TimetableMapper.makeWeek(response, TimetableDates.apiDateString(TimetableDates.monday(dateProvider())), kind = TimetableKind.PERMANENT)
        cache.saveRawTimetable(session.cacheScope, "permanent", response)
        cache.saveTimetable(session.cacheScope, "permanent", week)
        return week
    }

    override suspend fun predictSubjectAverage(subject: Subject, markText: String, weight: Int): Double? {
        val session = validSession()
        if (!session.provider.capabilities.supportsRemoteWhatIf) {
            val value = GradeMath.parseMarkValue(markText) ?: return null
            return GradeMath.theoreticalAverage(subject.marks, subject.averageText, value, weight)
        }
        val predicted = withBakalariRetry(session) { current ->
            bakalariClient.predictSubject(current.baseURL, current.accessToken, subject, markText, weight)
        }
        return GradeMath.parseAverageText(predicted.averageText)
    }

    private suspend fun persistEduPageStep(step: SchoolLoginStep): SchoolLoginStep = when (step) {
        is SchoolLoginStep.SignedIn -> {
            sessionStore.save(step.session)
            step
        }
        else -> step
    }

    private suspend fun persistEduPageSession(data: EduPageSessionData, baseURL: String): StoredSession {
        val session = StoredSession(
            accessToken = data.sessionID,
            refreshToken = "",
            tokenType = "Cookie",
            expiresAtEpochMillis = Long.MAX_VALUE,
            baseURL = baseURL,
            provider = SchoolProvider.EDU_PAGE,
            eduPage = data,
        )
        sessionStore.save(session)
        pendingEduPageBaseURL = null
        return session
    }

    private suspend fun validSession(): StoredSession {
        val session = sessionStore.load() ?: error("Not logged in.")
        if (session.provider != SchoolProvider.BAKALARI || !session.isExpired()) return session
        return refreshMutex.withLock {
            val latest = latestFor(session)
            if (latest.provider != SchoolProvider.BAKALARI || !latest.isExpired()) latest else refreshBakalari(latest)
        }
    }

    private suspend fun refreshBakalari(session: StoredSession): StoredSession {
        val response = try {
            bakalariClient.refreshToken(session.baseURL, session.refreshToken)
        } catch (error: GradeyApiException) {
            if (error.statusCode !in listOf(400, 401, 403)) throw error
            val credentials = session.bakalari ?: throw error
            bakalariClient.login(session.baseURL, credentials.username, credentials.password)
        }
        val updated = session.copy(
            accessToken = response.accessToken,
            refreshToken = response.refreshToken,
            tokenType = response.tokenType,
            expiresAtEpochMillis = System.currentTimeMillis() + response.expiresIn * 1000L,
        )
        sessionStore.updateExisting(updated)
        return updated
    }

    private suspend fun <T> withBakalariRetry(session: StoredSession, block: suspend (StoredSession) -> T): T =
        try { block(session) } catch (error: GradeyApiException) {
            if (session.provider != SchoolProvider.BAKALARI || error.statusCode != 401) throw error
            val refreshed = refreshMutex.withLock {
                val latest = latestFor(session)
                if (latest.accessToken != session.accessToken) latest else refreshBakalari(latest)
            }
            block(refreshed)
        }

    private suspend fun <T> optionalResponse(block: suspend () -> T): T? = try {
        block()
    } catch (error: CancellationException) { throw error }
    catch (_: Exception) { null }

    private suspend fun restoreEduPage(session: StoredSession): StoredSession {
        val data = eduPageClient.restore(session.eduPage ?: error("Missing EduPage session."), session.baseURL)
        return session.copy(accessToken = data.sessionID, eduPage = data).also { sessionStore.updateExisting(it) }
    }

    private suspend fun <T> withEduPageRetry(session: StoredSession, block: suspend (StoredSession) -> T): T = try {
        block(session)
    } catch (error: EduPageAuthenticationException) {
        if (error.reason != EduPageAuthFailure.SESSION_EXPIRED) throw error
        val restored = refreshMutex.withLock {
            val latest = latestFor(session)
            if (latest.accessToken != session.accessToken) latest else restoreEduPage(latest)
        }
        block(restored)
    }

    private fun latestFor(session: StoredSession): StoredSession =
        sessionStore.load()?.takeIf { it.cacheScope == session.cacheScope }
            ?: session.linkedAccountID?.let { sessionStore.loadLinked(it) }?.takeIf { it.cacheScope == session.cacheScope }
            ?: session

    private suspend fun fetchMarks(session: StoredSession) = when (session.provider) {
        SchoolProvider.BAKALARI -> withBakalariRetry(session) { bakalariClient.fetchMarks(it.baseURL, it.accessToken) }
        SchoolProvider.EDU_PAGE -> withEduPageRetry(session) { eduPageClient.fetchMarks(it.baseURL, it.eduPage ?: error("Missing EduPage session.")) }
    }

    private suspend fun fetchAbsence(session: StoredSession) = when (session.provider) {
        SchoolProvider.BAKALARI -> withBakalariRetry(session) { bakalariClient.fetchAbsences(it.baseURL, it.accessToken) }
        SchoolProvider.EDU_PAGE -> withEduPageRetry(session) { eduPageClient.fetchAbsences(it.baseURL, it.eduPage ?: error("Missing EduPage session.")) }
    }

    private suspend fun fetchUser(session: StoredSession) = when (session.provider) {
        SchoolProvider.BAKALARI -> withBakalariRetry(session) { bakalariClient.fetchUser(it.baseURL, it.accessToken) }
        SchoolProvider.EDU_PAGE -> withEduPageRetry(session) { eduPageClient.fetchUser(it.baseURL, it.eduPage ?: error("Missing EduPage session.")) }
    }

    private suspend fun fetchTimetable(session: StoredSession, weekStart: String) = when (session.provider) {
        SchoolProvider.BAKALARI -> withBakalariRetry(session) { bakalariClient.fetchTimetable(it.baseURL, it.accessToken, weekStart) }
        SchoolProvider.EDU_PAGE -> withEduPageRetry(session) { eduPageClient.fetchTimetable(it.baseURL, it.eduPage ?: error("Missing EduPage session."), weekStart) }
    }

    private fun UserResponse.resolvedFor(session: StoredSession): UserResponse =
        if (schoolName != null) this else copy(schoolName = session.linkedAccountSchoolName)
}

class SchoolSessionStore(
    private val secureJsonStore: JsonStore,
) {
    fun load(): StoredSession? = secureJsonStore.load(KEY, StoredSession.serializer())
    fun save(session: StoredSession) {
        secureJsonStore.save(KEY, session, StoredSession.serializer())
        session.linkedAccountID?.let { secureJsonStore.save("$LINKED_PREFIX$it", session, StoredSession.serializer()) }
    }
    fun clear() = secureJsonStore.clear(KEY)
    fun updateExisting(session: StoredSession) {
        if (load()?.cacheScope == session.cacheScope) save(session)
        else session.linkedAccountID?.let {
            if (loadLinked(it) != null) secureJsonStore.save("$LINKED_PREFIX$it", session, StoredSession.serializer())
        }
    }
    fun loadLinked(accountID: String): StoredSession? = secureJsonStore.load("$LINKED_PREFIX$accountID", StoredSession.serializer())
    fun removeLinked(accountID: String) {
        secureJsonStore.clear("$LINKED_PREFIX$accountID")
        if (load()?.linkedAccountID == accountID) clear()
    }
    fun clearLinked() = secureJsonStore.clearPrefix(LINKED_PREFIX)

    private companion object {
        const val KEY = "school.session.v1"
        const val LINKED_PREFIX = "school.linked.session.v1."
    }
}
