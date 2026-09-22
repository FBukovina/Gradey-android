package com.bukovinafilip.gradey

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bukovinafilip.gradey.data.AndroidGradeyGraph
import com.bukovinafilip.gradey.data.SecureJsonStore
import com.bukovinafilip.gradey.domain.GradeHistoryTrend
import com.bukovinafilip.gradey.domain.SchoolLoginStep
import com.bukovinafilip.gradey.domain.isEduPageParentSession
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.network.GradeyJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.builtins.ListSerializer
import java.time.LocalDate

internal enum class AppPhase { CHECKING, SIGNED_OUT, NEEDS_SCHOOL, SIGNED_IN }
internal enum class AppTab(val label: String) {
    TODAY("Today"), SUBJECTS("Marks"), ABSENCE("Absence"), TIMETABLE("Timetable"),
    STRAVACZ("Meals"), ACCOUNT("Account"), PLANNER("Plan absence"), HISTORY("Grade history"), TOOLS("Gradey tools"),
    AI("Gradey AI"), SUPPORT("Support Gradey"), UPDATES("App updates")
}

internal data class GradeyState(
    val phase: AppPhase = AppPhase.CHECKING,
    val tab: AppTab = AppTab.TODAY,
    val loading: Boolean = false,
    val error: String? = null,
    val account: GradeyAccount? = null,
    val school: StoredSession? = null,
    val linkedAccounts: List<LinkedSchoolAccount> = emptyList(),
    val dashboard: DashboardData = DashboardData(MarksResponse()),
    val absence: AbsenceResponse = AbsenceResponse(),
    val timetable: TimetableWeek? = null,
    val currentTimetable: TimetableWeek? = null,
    val viewedWeek: String = LocalDate.now(java.time.ZoneId.of("Europe/Prague")).toString(),
    val plans: List<PlannedAbsence> = emptyList(),
    val history: List<GradeHistoryTrend> = emptyList(),
    val preferences: NotificationPreferences = NotificationPreferences.Default,
    val isDemo: Boolean = false,
    val loginStep: SchoolLoginStep? = null,
)

/** Reset every school-specific value before another account or child can become visible. */
internal fun GradeyState.withSchool(session: StoredSession, destination: AppTab = AppTab.ACCOUNT) = GradeyState(
    phase = AppPhase.SIGNED_IN, tab = destination, loading = true, account = account, school = session,
    isDemo = isDemo, preferences = preferences, linkedAccounts = linkedAccounts,
)

internal class GradeyViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GradeyApplication
    var graph: AndroidGradeyGraph = app.graph
        private set(value) { field = value; app.activeGraph = value }
    private val mutableState = MutableStateFlow(GradeyState(isDemo = graph.isDemo))
    val state = mutableState.asStateFlow()
    private val plansStore = SecureJsonStore(application, "gradey-planned-absences", GradeyJson)
    private val modeStore = application.getSharedPreferences("gradey-mode", 0)
    private var historyRequested = false
    private var initialTabApplied = false
    fun applyInitialTab(tab: AppTab) {
        if (!initialTabApplied) { initialTabApplied = true; select(tab) }
    }

    init { bootstrap() }

    private fun bootstrap() = action {
        if (modeStore.getBoolean("demo", false)) {
            enterDemo()
        } else {
            var offline = false
            val auth = try { graph.gradeyAuthRepository.bootstrapSession() } catch (_: java.io.IOException) {
                offline = true
                graph.gradeyAuthRepository.cachedSession()
            }
            val school = if (auth == null) null else if (offline) graph.schoolRepository.currentStoredSession() else try {
                graph.schoolRepository.bootstrapSession()
            } catch (_: java.io.IOException) {
                offline = true
                graph.schoolRepository.currentStoredSession()
            }
            mutableState.update { it.copy(account = auth?.account, school = school,
                phase = if (auth == null) AppPhase.SIGNED_OUT else if (school == null) AppPhase.NEEDS_SCHOOL else AppPhase.SIGNED_IN) }
            if (auth != null && school != null) {
                restoreCachedData()
                if (offline) showError("You’re offline. Showing saved school data; refresh when you’re connected.") else refreshData()
            } else clearCompanions()
        }
    }

    fun demo() = action { enterDemo() }

    private suspend fun enterDemo() {
        clearCompanions()
        graph = app.createDemoGraph()
        val auth = graph.gradeyAuthRepository.signInWithGoogle("demo", fullName = "Demo Student")
        var school = (graph.schoolRepository.beginLogin(SchoolProvider.BAKALARI, "https://demo.gradey.app", "demo", "demo") as SchoolLoginStep.SignedIn).session
        val linked = graph.linkedAccountRepository.linkSchoolAccount(school, "Demo school account")
        school = school.copy(linkedAccountID = linked.id)
        graph.schoolRepository.activateLinkedSchoolAccount(school)
        modeStore.edit().putBoolean("demo", true).apply()
        mutableState.value = GradeyState(phase = AppPhase.SIGNED_IN, tab = state.value.tab, loading = true, account = auth.account, school = school, isDemo = true)
        restoreCachedData()
        refreshData()
    }

    fun signIn(token: String, name: String?) = action {
        graph = app.graph
        val auth = graph.gradeyAuthRepository.signInWithGoogle(token, fullName = name)
        // A new Gradey identity must never inherit another user's school sessions or local plans.
        if (modeStore.getString("owner", null) != auth.account.id) {
            graph.linkedAccountRepository.clearLocalAccounts()
            graph.schoolRepository.logout()
            graph.cache?.clearAll()
            plansStore.clearPrefix("plans.")
        }
        clearCompanions()
        modeStore.edit().putBoolean("demo", false).putString("owner", auth.account.id).apply()
        val school = graph.schoolRepository.currentStoredSession()
        mutableState.value = GradeyState(account = auth.account, school = school, tab = state.value.tab,
            phase = if (school == null) AppPhase.NEEDS_SCHOOL else AppPhase.SIGNED_IN, loading = true, isDemo = graph.isDemo)
        if (school != null) {
            restoreCachedData()
            refreshData()
        }
    }

    fun login(provider: SchoolProvider, school: String, user: String, password: String) = action {
        finishLogin(graph.schoolRepository.beginLogin(provider, school, user, password))
    }

    private suspend fun finishLogin(step: SchoolLoginStep) {
        when (step) {
            is SchoolLoginStep.SignedIn -> {
                clearCompanions()
                var session = step.session
                var linkError: String? = null
                try {
                    val linked = graph.linkedAccountRepository.linkSchoolAccount(session, session.eduPage?.activeStudent?.fullName ?: session.bakalari?.username ?: "School account")
                    session = session.copy(linkedAccountID = linked.id, linkedAccountDisplayName = linked.displayName, linkedAccountSchoolName = linked.schoolName)
                    graph.schoolRepository.activateLinkedSchoolAccount(session)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    linkError = "School connected. Account sync is unavailable; try linking again from Account."
                }
                mutableState.update { it.withSchool(session, it.tab) }
                restoreCachedData()
                refreshData()
                linkError?.let(::showError)
            }
            else -> mutableState.update { it.copy(loginStep = step, phase = AppPhase.NEEDS_SCHOOL) }
        }
    }

    fun submitCode(code: String) = action { finishLogin(graph.schoolRepository.completeEduPageTwoFactor(code)) }
    fun checkApproval() = action {
        if (graph.schoolRepository.isEduPageTwoFactorConfirmed()) finishLogin(graph.schoolRepository.completeApprovedEduPageTwoFactor())
        else showError("Approval is still pending. Approve the request in EduPage, then try again.")
    }
    fun resendApproval() = action { graph.schoolRepository.resendEduPageTwoFactorNotification() }
    fun selectStudent(id: String) = action { finishLogin(SchoolLoginStep.SignedIn(graph.schoolRepository.selectEduPageStudent(id))) }
    fun cancelVerification() { if (!state.value.loading) mutableState.update { it.copy(loginStep = null, error = null) } }

    fun showError(message: String) { mutableState.update { it.copy(error = message) } }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    fun select(tab: AppTab) {
        mutableState.update { it.copy(tab = tab) }
        if (tab == AppTab.HISTORY) {
            if (state.value.loading) historyRequested = true else loadHistory()
        }
    }
    fun back() {
        if (state.value.loading && state.value.phase != AppPhase.SIGNED_IN) return
        if (state.value.phase == AppPhase.NEEDS_SCHOOL && state.value.school != null) {
            mutableState.update { it.copy(phase = AppPhase.SIGNED_IN, tab = AppTab.ACCOUNT, error = null) }
        } else select(AppTab.TODAY)
    }
    fun addAccount() { if (!state.value.loading) mutableState.update { it.copy(phase = AppPhase.NEEDS_SCHOOL, error = null, loginStep = null) } }

    fun refresh() = action {
        if (state.value.phase == AppPhase.SIGNED_IN) refreshData()
    }

    private suspend fun restoreCachedData() {
        val session = state.value.school ?: return
        val cached = graph.schoolRepository.loadCachedDashboard()
        val cachedAbsence = graph.cache?.loadAbsence(session.cacheScope)
        val week = graph.schoolRepository.loadCachedTimetable(LocalDate.now(java.time.ZoneId.of("Europe/Prague")).toString())
        val plans = plansStore.load("plans.${session.cacheScope}", ListSerializer(PlannedAbsence.serializer())).orEmpty()
        val preferences = graph.notificationSettings.load()
        val linkedAccounts = graph.linkedAccountRepository.cachedAccounts()
        mutableState.update { it.copy(dashboard = cached ?: DashboardData(MarksResponse()),
            timetable = week, currentTimetable = week, viewedWeek = week?.weekStart ?: LocalDate.now(java.time.ZoneId.of("Europe/Prague")).toString(), plans = plans,
            preferences = preferences, linkedAccounts = linkedAccounts,
            absence = cachedAbsence ?: AbsenceResponse(absencesPerSubject = cached?.absencesPerSubject.orEmpty())) }
        // Widgets and watches can use the same cached week as the phone during an offline launch.
        if (week != null) syncCompanions(week)
    }

    private suspend fun refreshData() {
        val failures = mutableListOf<String>()
        suspend fun attempt(label: String, block: suspend () -> Unit) {
            try { block() } catch (e: Exception) {
                if (e is CancellationException) throw e
                failures += label
            }
        }
        attempt("marks") { val dashboard = graph.schoolRepository.loadDashboard(true); mutableState.update { it.copy(dashboard = dashboard) } }
        attempt("absence") { val absence = graph.schoolRepository.loadAbsence(true); mutableState.update { it.copy(absence = absence) } }
        attempt("timetable") {
            val week = graph.schoolRepository.loadTimetable(LocalDate.now(java.time.ZoneId.of("Europe/Prague")).toString())
            mutableState.update { it.copy(currentTimetable = week, timetable = week, viewedWeek = week.weekStart) }
            syncCompanions(week)
        }
        attempt("linked accounts") { val accounts = graph.linkedAccountRepository.localAccounts(); mutableState.update { it.copy(linkedAccounts = accounts) } }
        val savedPreferences = graph.notificationSettings.load()
        mutableState.update { it.copy(preferences = savedPreferences) }
        attempt("notification settings") { val prefs = graph.notificationSettings.refresh(); mutableState.update { it.copy(preferences = prefs) } }
        // Refreshes can rotate provider tokens or restore a selected student's session.
        val refreshedSession = graph.schoolRepository.currentStoredSession()
        mutableState.update { it.copy(school = refreshedSession) }
        if (failures.isNotEmpty()) showError("Could not refresh ${failures.joinToString()}. Showing saved data where available. Check your connection and retry.")
    }

    fun changeWeek(date: String) = action {
        mutableState.update { it.copy(viewedWeek = date, timetable = null) }
        val cached = graph.schoolRepository.loadCachedTimetable(date)
        mutableState.update { it.copy(timetable = cached) }
        val week = graph.schoolRepository.loadTimetable(date)
        mutableState.update { it.copy(timetable = week) }
    }

    fun savePlan(plan: PlannedAbsence) {
        if (state.value.loading || state.value.phase != AppPhase.SIGNED_IN) return
        val school = state.value.school ?: return
        val plans = (state.value.plans.filterNot { it.date == plan.date } + plan).sortedBy { it.date }
        plansStore.save("plans.${school.cacheScope}", plans, ListSerializer(PlannedAbsence.serializer()))
        mutableState.update { it.copy(plans = plans) }
    }

    fun removePlan(date: String) {
        if (state.value.loading || state.value.phase != AppPhase.SIGNED_IN) return
        val school = state.value.school ?: return
        val plans = state.value.plans.filterNot { it.date == date }
        plansStore.save("plans.${school.cacheScope}", plans, ListSerializer(PlannedAbsence.serializer()))
        mutableState.update { it.copy(plans = plans) }
    }

    fun activateAccount(id: String) = action {
        val session = graph.linkedAccountRepository.activateAccount(id) ?: error("Please reconnect this school account.")
        graph.schoolRepository.activateLinkedSchoolAccount(session)
        mutableState.update { it.withSchool(session) }
        clearCompanions()
        restoreCachedData()
        refreshData()
    }

    fun switchStudent(id: String) = action {
        val current = state.value.school ?: error("Connect your school first.")
        require(current.provider == SchoolProvider.EDU_PAGE) { "Student profiles are available for EduPage accounts." }
        require(current.eduPage?.linkedStudents?.any { it.id == id } == true) { "This student profile is no longer available. Reconnect your school." }
        if (current.eduPage?.activeStudent?.id == id) return@action
        graph.schoolRepository.switchEduPageStudent(id)
        val updated = graph.schoolRepository.currentStoredSession() ?: error("Please reconnect this school account.")
        mutableState.update { it.withSchool(updated) }
        clearCompanions()
        restoreCachedData()
        refreshData()
    }

    fun unlinkAccount(id: String) = action {
        val removedScope = graph.linkedAccountRepository.storedSession(id)?.cacheScope
        graph.linkedAccountRepository.unlinkAccount(id)
        removedScope?.let { plansStore.clear("plans.$it") }
        if (state.value.school?.linkedAccountID == id) {
            graph.schoolRepository.logout()
            clearCompanions()
            mutableState.update { GradeyState(phase = AppPhase.NEEDS_SCHOOL, loading = true, account = it.account, isDemo = it.isDemo) }
        } else {
            val accounts = graph.linkedAccountRepository.localAccounts()
            mutableState.update { it.copy(linkedAccounts = accounts) }
        }
    }

    fun preferences(value: NotificationPreferences) = action {
        graph.notificationSettings.save(value)
        mutableState.update { it.copy(preferences = value) }
    }

    private fun loadHistory() = action {
        val school = state.value.school ?: return@action
        if (school.isEduPageParentSession()) {
            mutableState.update { it.copy(history = emptyList()) }
            showError("Cloud grade history is unavailable for EduPage parent accounts. Open Marks to refresh the selected student’s grades.")
            return@action
        }
        val accountID = school.linkedAccountID
        if (accountID == null) {
            mutableState.update { it.copy(history = emptyList()) }
            showError("Link this school to Gradey before viewing its cloud grade history.")
            return@action
        }
        val history = graph.historyRepository.gradeHistory(accountID)
        mutableState.update { it.copy(history = history) }
    }

    fun deleteAccount() = action {
        val management = graph.accountManagement ?: error("Account deletion is unavailable in demo mode.")
        management.deleteAccount()
        clearSignedInState()
    }

    fun signOut() = action { clearSignedInState() }

    private suspend fun clearSignedInState() {
        // Complete local removal even when one provider is offline or the screen disappears mid-sign-out.
        withContext(NonCancellable) {
            val signingOutGraph = graph
            mutableState.value = GradeyState(phase = AppPhase.SIGNED_OUT, loading = true, isDemo = graph.isDemo)
            var localCleanupFailed = false
            suspend fun cleanup(block: suspend () -> Unit) {
                try { block() } catch (_: Exception) { localCleanupFailed = true }
            }
            cleanup { clearCompanions() }
            cleanup { signingOutGraph.linkedAccountRepository.clearLocalAccounts() }
            cleanup { signingOutGraph.schoolRepository.logout() }
            cleanup { signingOutGraph.cache?.clearAll() }
            cleanup { plansStore.clearPrefix("plans.") }
            cleanup { signingOutGraph.stravaCZRepository.logout() }
            cleanup { signingOutGraph.gradeyAuthRepository.signOut() }
            cleanup { com.bukovinafilip.gradey.feature.ai.resetAiIdentity(app) }
            cleanup { withTimeoutOrNull(5_000) { resetSupportIdentity() } }
            modeStore.edit().remove("demo").remove("owner").apply()
            graph = app.graph
            if (!signingOutGraph.isDemo && com.google.firebase.FirebaseApp.getApps(app).isNotEmpty()) {
                try { withTimeoutOrNull(5_000) { com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().await() } }
                catch (_: Exception) { /* The cloud session and local school data have already been removed. */ }
            }
            mutableState.value = GradeyState(phase = AppPhase.SIGNED_OUT, isDemo = graph.isDemo,
                error = if (localCleanupFailed) "Some saved data could not be removed. Clear Gradey’s app storage before sharing this device." else null)
        }
    }

    private suspend fun syncCompanions(week: TimetableWeek) {
        CompanionSync.publish(app, week, state.value.dashboard.user, state.value.school?.cacheScope)
    }
    private suspend fun clearCompanions() { CompanionSync.clear(app) }

    private fun action(block: suspend () -> Unit) {
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableState.update { it.copy(error = safeError(e), phase = if (it.phase == AppPhase.CHECKING) AppPhase.SIGNED_OUT else it.phase) }
            } finally {
                mutableState.update { it.copy(loading = false) }
                if (historyRequested) {
                    historyRequested = false
                    if (state.value.phase == AppPhase.SIGNED_IN && state.value.tab == AppTab.HISTORY) loadHistory()
                }
            }
        }
    }
}

internal fun safeError(error: Exception): String = when (error) {
    is com.bukovinafilip.gradey.network.EduPageAuthenticationException -> when (error.reason) {
        com.bukovinafilip.gradey.network.EduPageAuthFailure.BAD_CREDENTIALS -> "EduPage could not sign you in. Check your username and password."
        com.bukovinafilip.gradey.network.EduPageAuthFailure.CAPTCHA_REQUIRED -> "EduPage requires a security check. Sign in on your school’s website, then try again."
        com.bukovinafilip.gradey.network.EduPageAuthFailure.TWO_FACTOR_REQUIRED -> "EduPage needs a new verification. Reconnect your school account."
        com.bukovinafilip.gradey.network.EduPageAuthFailure.INVALID_TWO_FACTOR_CODE -> "That verification code was not accepted. Check the code and try again."
        com.bukovinafilip.gradey.network.EduPageAuthFailure.TWO_FACTOR_NOT_CONFIRMED -> "Approve the sign-in request in EduPage, then try again."
        com.bukovinafilip.gradey.network.EduPageAuthFailure.SESSION_EXPIRED -> "Your EduPage session expired. Reconnect your school account."
        else -> "EduPage could not complete this request. Please try again."
    }
    is java.io.IOException -> "Could not connect. Check your connection and try again."
    is com.bukovinafilip.gradey.network.GradeyApiException -> if (error.statusCode in listOf(401, 403)) "Your session expired or access was denied. Please reconnect your account." else "The service is temporarily unavailable. Please try again."
    is IllegalArgumentException, is IllegalStateException -> error.message?.take(180) ?: "Unable to complete the action. Please try again."
    else -> "Unable to complete the action. Please try again."
}
