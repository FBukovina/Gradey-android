package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.DevicePushTokenClient
import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.domain.GradeyHistoryRepository
import com.bukovinafilip.gradey.domain.GradeHistoryTrend
import com.bukovinafilip.gradey.domain.LinkedAccountRepository
import com.bukovinafilip.gradey.domain.SchoolLoginStep
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.domain.StravaCZRepository
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.GradeyAccount
import com.bukovinafilip.gradey.model.GradeyAuthSession
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.StravaCZMeal
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.TimetableWeek
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class MockSchoolRepository : SchoolRepository {
    private var session: StoredSession? = null

    override suspend fun bootstrapSession(): StoredSession? = session
    override suspend fun currentStoredSession(): StoredSession? = session

    override suspend fun beginLogin(provider: SchoolProvider, schoolURL: String, username: String, password: String): SchoolLoginStep {
        session = StoredSession(
            accessToken = "demo-access",
            refreshToken = "demo-refresh",
            tokenType = "Bearer",
            expiresAtEpochMillis = System.currentTimeMillis() + 86_400_000,
            baseURL = schoolURL.ifBlank { "https://demo.gradey.app" },
            provider = provider,
        )
        return SchoolLoginStep.SignedIn(session!!)
    }

    override suspend fun completeEduPageTwoFactor(code: String): SchoolLoginStep = SchoolLoginStep.SignedIn(session!!)
    override suspend fun completeApprovedEduPageTwoFactor(): SchoolLoginStep = SchoolLoginStep.SignedIn(session!!)
    override suspend fun selectEduPageStudent(studentID: String): StoredSession = session ?: error("Not signed in")
    override suspend fun switchEduPageStudent(studentID: String) = Unit
    override suspend fun activateLinkedSchoolAccount(session: StoredSession): StoredSession = session.also { this.session = it }
    override suspend fun logout() { session = null }
    override suspend fun loadCachedDashboard(): DashboardData = com.bukovinafilip.gradey.domain.DemoData.fixture.dashboard
    override suspend fun loadDashboard(forceRefresh: Boolean): DashboardData = com.bukovinafilip.gradey.domain.DemoData.fixture.dashboard
    override suspend fun loadAbsence(forceRefresh: Boolean): AbsenceResponse = com.bukovinafilip.gradey.domain.DemoData.absenceResponse
    override suspend fun loadCachedTimetable(weekContaining: String): TimetableWeek =
        com.bukovinafilip.gradey.domain.DemoData.timetableFor(weekContaining)
    override suspend fun loadTimetable(weekContaining: String): TimetableWeek =
        com.bukovinafilip.gradey.domain.DemoData.timetableFor(weekContaining)
    override suspend fun loadCachedPermanentTimetable(): TimetableWeek = loadPermanentTimetable()
    override suspend fun loadPermanentTimetable(): TimetableWeek {
        check(session?.provider != SchoolProvider.EDU_PAGE) { "The permanent timetable is not available for this school account." }
        val weekStart = com.bukovinafilip.gradey.domain.TimetableDates.monday().toString()
        return com.bukovinafilip.gradey.domain.TimetableMapper.makeWeek(
            com.bukovinafilip.gradey.domain.DemoData.permanentTimetableResponse(), weekStart,
            kind = com.bukovinafilip.gradey.model.TimetableKind.PERMANENT,
        )
    }
    override suspend fun predictSubjectAverage(subject: Subject, markText: String, weight: Int): Double? =
        com.bukovinafilip.gradey.domain.GradeMath.parseMarkValue(markText)?.let {
            com.bukovinafilip.gradey.domain.GradeMath.theoreticalAverage(subject.marks, subject.averageText, it, weight)
        }
}

class MockGradeyAuthRepository : GradeyAuthRepository {
    private var session: GradeyAuthSession? = null

    override suspend fun bootstrapSession(): GradeyAuthSession? = session
    override suspend fun signInWithGoogle(idToken: String, accessToken: String?, fullName: String?): GradeyAuthSession =
        GradeyAuthSession("demo-gradey-access", account = GradeyAccount("demo-account", fullName = fullName ?: "Demo Student")).also { session = it }

    override suspend fun refreshAccount(): GradeyAccount = checkNotNull(session) { "Sign in to Gradey ID." }.account
    override suspend fun updateFullName(fullName: String): GradeyAccount {
        val normalized = fullName.trim()
        require(normalized.length in 1..80) { "Your name must be between 1 and 80 characters." }
        val current = checkNotNull(session) { "Sign in to Gradey ID." }
        return current.account.copy(fullName = normalized).also { session = current.copy(account = it) }
    }

    override suspend fun signOut() {
        session = null
    }
}

class MockLinkedAccountRepository : LinkedAccountRepository {
    private val sessions = mutableMapOf<String, StoredSession>()
    override suspend fun storedSession(accountID: String): StoredSession? = sessions[accountID]

    private val accounts = MutableStateFlow(
        emptyList<LinkedSchoolAccount>(),
    )

    override suspend fun localAccounts(): List<LinkedSchoolAccount> = accounts.value
    override suspend fun linkSchoolAccount(session: StoredSession, displayName: String, user: com.bukovinafilip.gradey.model.UserResponse?): LinkedSchoolAccount {
        val account = LinkedSchoolAccount("demo-linked-${session.cacheScope.takeLast(16)}", session.provider, displayName, session.linkedAccountSchoolName)
        sessions[account.id] = session.copy(linkedAccountID = account.id)
        accounts.update { it.filterNot { existing -> existing.id == account.id } + account }
        return account
    }

    override suspend fun reconnectSchoolAccount(accountID: String, session: StoredSession, displayName: String, user: com.bukovinafilip.gradey.model.UserResponse?): LinkedSchoolAccount {
        val previous = accounts.value.firstOrNull { it.id == accountID } ?: error("This school account is no longer available.")
        require(previous.provider == session.provider) { "Choose the same school provider." }
        val account = previous.copy(displayName = displayName, status = "active")
        sessions[accountID] = session.copy(linkedAccountID = accountID)
        accounts.update { it.map { existing -> if (existing.id == accountID) account else existing } }
        return account
    }

    override suspend fun updateNotificationsEnabled(accountID: String, enabled: Boolean): LinkedSchoolAccount {
        val account = (accounts.value.firstOrNull { it.id == accountID } ?: error("This school account is no longer available.")).copy(notificationsEnabled = enabled)
        accounts.update { it.map { existing -> if (existing.id == accountID) account else existing } }
        return account
    }

    override suspend fun unlinkAccount(accountID: String) {
        sessions.remove(accountID)
        accounts.update { it.filterNot { account -> account.id == accountID } }
    }

    override suspend fun clearLocalAccounts() {
        sessions.clear()
        accounts.value = emptyList()
    }
}

class MockGradeyHistoryRepository : GradeyHistoryRepository {
    override suspend fun gradeHistory(accountID: String?): List<GradeHistoryTrend> = listOf(
        GradeHistoryTrend("math", "Mathematics", averageDelta = -0.62, markCountDelta = 2),
        GradeHistoryTrend("czech", "Czech Language", averageDelta = 0.4, markCountDelta = 1),
    )
}

class MockDevicePushTokenClient : DevicePushTokenClient {
    val registeredTokens = mutableListOf<String>()
    var preferences: NotificationPreferences = NotificationPreferences.Default

    override suspend fun registerDeviceToken(token: String, platform: String, environment: String, gradeySession: GradeyAuthSession) {
        registeredTokens += token
    }

    override suspend fun updateNotificationPreferences(preferences: NotificationPreferences, gradeySession: GradeyAuthSession) {
        this.preferences = preferences
    }
}

class MockStravaCZRepository : StravaCZRepository {
    private var session: StravaCZStoredSession? = demoSession()
    private var menu = com.bukovinafilip.gradey.domain.DemoData.stravaMenu

    override suspend fun bootstrapSession(): StravaCZStoredSession? = session
    override suspend fun loadCachedMenu(): StravaCZMenu? = if (session != null) menu else null
    override suspend fun login(canteenNumber: String, username: String, password: String): StravaCZStoredSession {
        require(canteenNumber.isNotBlank() && username.isNotBlank() && password.isNotEmpty()) {
            "Enter your canteen number, username, and password."
        }
        menu = com.bukovinafilip.gradey.domain.DemoData.stravaMenu
        return demoSession().copy(canteenNumber = canteenNumber.trim(), username = username.trim()).also { session = it }
    }

    override suspend fun loadMenu(forceRefresh: Boolean): Pair<StravaCZStoredSession, StravaCZMenu> =
        checkNotNull(session) { "Connect your demo canteen to load meals." } to menu

    override suspend fun setMeal(meal: StravaCZMeal, ordered: Boolean): Pair<StravaCZStoredSession, StravaCZMenu> {
        checkNotNull(session) { "Connect your demo canteen to load meals." }
        check(meal.canModify) { "This meal cannot be changed." }
        check(menu.days.any { day -> day.meals.any { it.id == meal.id } }) { "This meal is no longer available." }
        menu = menu.copy(days = menu.days.map { day ->
            if (day.meals.none { it.id == meal.id }) day else day.copy(meals = day.meals.map { existing ->
                when {
                    existing.id == meal.id -> existing.copy(ordered = ordered)
                    ordered && existing.canModify -> existing.copy(ordered = false)
                    else -> existing
                }
            })
        })
        return loadMenu(forceRefresh = true)
    }

    override suspend fun logout() {
        session = null
        menu = com.bukovinafilip.gradey.model.StravaCZMenu()
    }

    private fun demoSession() = StravaCZStoredSession("demo-strava", "0000", "demo", "100.00", canteenName = "Demo canteen")
}
