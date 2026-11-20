package com.bukovinafilip.gradey

import kotlinx.coroutines.sync.withLock
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bukovinafilip.gradey.data.AndroidGradeyGraph
import com.bukovinafilip.gradey.data.SecureJsonStore
import com.bukovinafilip.gradey.domain.Homework
import com.bukovinafilip.gradey.domain.HomeworkList
import com.bukovinafilip.gradey.domain.KomensMessage
import com.bukovinafilip.gradey.model.KomensSnapshot
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
internal data class AIEntryRequest(
    val action: com.bukovinafilip.gradey.feature.ai.AIAction,
    val subjectID: String? = null,
    val eventID: String? = null,
    val week: String? = null,
    val key: String = java.util.UUID.randomUUID().toString(),
)
/**
 * Every destination of the signed-in app. [kind] decides how the shell presents it: the five tabs sit
 * on the bottom bar, pushed screens slide in over their tab and keep the bar, and full-screen
 * surfaces (Settings, Gradey AI, Recap story and the screens opened from Settings) cover it.
 */
internal enum class AppTab(val labelRes: Int, val kind: DestinationKind) {
    TODAY(R.string.app_today, DestinationKind.TAB), SUBJECTS(R.string.app_marks, DestinationKind.TAB),
    ABSENCE(R.string.app_absence, DestinationKind.TAB), TIMETABLE(R.string.app_timetable, DestinationKind.TAB),
    STRAVACZ(R.string.app_meals, DestinationKind.TAB),
    PLANNER(R.string.app_plan_absence, DestinationKind.PUSHED), PERSONAL_PLANNER(R.string.app_planner, DestinationKind.PUSHED),
    REPORT_CARD(R.string.app_report_card, DestinationKind.PUSHED), RECAP_LIST(R.string.app_recap, DestinationKind.PUSHED),
    ACCOUNT(R.string.app_account, DestinationKind.FULL_SCREEN), AI(R.string.app_ai, DestinationKind.FULL_SCREEN),
    RECAP_STORY(R.string.app_recap, DestinationKind.FULL_SCREEN), SUPPORT(R.string.app_support, DestinationKind.FULL_SCREEN),
    KOMENS(R.string.app_messages, DestinationKind.FULL_SCREEN),
    UPDATES(R.string.app_updates, DestinationKind.FULL_SCREEN),
}

internal enum class DestinationKind { TAB, PUSHED, FULL_SCREEN }

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
    val rawAbsence: AbsenceResponse = AbsenceResponse(),
    val absenceOverrides: List<AbsenceDayOverride> = emptyList(),
    val correctionTimetable: TimetableWeek? = null,
    val correctionError: String? = null,
    val timetable: TimetableWeek? = null,
    val currentTimetable: TimetableWeek? = null,
    val timetableKind: TimetableKind = TimetableKind.WEEKLY,
    val permanentTimetable: TimetableWeek? = null,
    val permanentTimetableLoading: Boolean = false,
    val permanentTimetableError: String? = null,
    val viewedWeek: String = LocalDate.now(java.time.ZoneId.of("Europe/Prague")).toString(),
    val plans: List<PlannedAbsence> = emptyList(),
    val preferences: NotificationPreferences = NotificationPreferences.Default,
    val isDemo: Boolean = false,
    val isGuest: Boolean = false,
    val appleSignInAvailable: Boolean = false,
    val loginStep: SchoolLoginStep? = null,
    val reconnectingAccount: LinkedSchoolAccount? = null,
    val mealMenu: StravaCZMenu? = null,
    val hasMealAccount: Boolean = false,
    val canteenAccounts: List<LinkedCanteenAccount> = emptyList(),
    val canteenSession: StravaCZStoredSession? = null,
    val currentCanteenLinkID: String? = null,
    val personalPlannerItems: List<PersonalPlannerItem> = emptyList(),
    val plannerDraft: PersonalPlannerItem? = null,
    val plannerItemID: String? = null,
    val plannerDay: String? = null,
    val aiEntry: AIEntryRequest? = null,
    val directorySchools: List<SchoolDirectorySchool> = emptyList(),
    val directoryLoading: Boolean = false,
    val directoryError: String? = null,
    val insightState: com.bukovinafilip.gradey.domain.SchoolInsightState? = null,
    val focusedSubjectID: String? = null,
    /** Destinations under [tab], oldest first; Back returns to the last one. */
    val backStack: List<AppTab> = emptyList(),
    val recapPeriodID: String? = null,
    /** Homework from the school, by due day. Empty for schools without it. */
    val homework: List<Homework> = emptyList(),
    /** Done ticks still on their way to the school. */
    val homeworkPendingIDs: Set<String> = emptySet(),
    /** Komens messages and the noticeboard as the school sent them; null until they were loaded once. */
    val komens: KomensSnapshot? = null,
    /** Messages opened here; they stay read even if a list loaded meanwhile says otherwise. */
    val komensReadIDs: Set<String> = emptySet(),
    val komensRefreshing: Boolean = false,
    val komensError: String? = null,
) {
    /** The bottom-bar tab the current destination belongs to. */
    val rootTab: AppTab get() = if (tab.kind == DestinationKind.TAB) tab else backStack.lastOrNull { it.kind == DestinationKind.TAB } ?: AppTab.TODAY

    val absenceOverrideScope: String? get() = (account?.id ?: "guest-local".takeIf { isGuest })?.let { owner -> school?.cacheScope?.let { "$owner:$it" } }
}

/** Reset every school-specific value before another account or child can become visible. */
internal fun GradeyState.withSchool(session: StoredSession, destination: AppTab = AppTab.ACCOUNT) = GradeyState(
    phase = AppPhase.SIGNED_IN, tab = destination, loading = true, account = account, school = session,
    isDemo = isDemo, isGuest = isGuest, preferences = preferences, linkedAccounts = linkedAccounts, personalPlannerItems = personalPlannerItems,
)

internal class GradeyViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as GradeyApplication
    private val context get() = AppLanguages.localizedContext(app)
    var graph: AndroidGradeyGraph = app.activeGraph
        private set(value) { field = value; app.activeGraph = value }
    private val mutableState = MutableStateFlow(GradeyState(isDemo = graph.isDemo))
    val state = mutableState.asStateFlow()
    private val plansStore = SecureJsonStore(application, "gradey-planned-absences", GradeyJson)
    private val modeStore = application.getSharedPreferences("gradey-mode", 0)
    private val guestPreferences = GuestPreferences(application)
    private val guestMigrationJournal = com.bukovinafilip.gradey.data.AndroidGuestSchoolMigrationJournal(application)
    private val guestPlanMigration by lazy { com.bukovinafilip.gradey.data.GuestAbsencePlanMigration(application) }
    val plannerRepository: com.bukovinafilip.gradey.domain.PersonalPlannerRepository
        get() = if (graph.isDemo) app.demoPlanner else app.personalPlanner
    private val absenceOverrideRepository: com.bukovinafilip.gradey.domain.AbsenceOverrideRepository
        get() = if (graph.isDemo) app.demoAbsenceOverrides else app.absenceOverrides
    private var correctionRevision = 0L
    private var permanentRevision = 0L
    private var initialTabApplied = false
    private var pendingDestination: AppDestination? = null
    /** The account the homework list was last loaded for, and when (elapsed realtime). */
    private var homeworkLoaded: Pair<String, Long>? = null
    private var homeworkRefresh: kotlinx.coroutines.Job? = null
    private var komensLoaded: Pair<String, Long>? = null
    private var komensRefresh: kotlinx.coroutines.Job? = null
    private var awaitingPlannerEntry = false
    private var appleAvailable = false
    private var pendingAppleCallback: String? = null
    fun applyInitialDestination(destination: AppDestination) {
        if (!initialTabApplied) { initialTabApplied = true; openDestination(destination) }
    }
    fun hasPendingDestination(): Boolean = pendingDestination != null || awaitingPlannerEntry

    fun openDestination(destination: AppDestination) {
        if (state.value.phase != AppPhase.SIGNED_IN && destination.tab != AppTab.UPDATES) {
            pendingDestination = destination
            return
        }
        pendingDestination = null
        val handledDayEntry = destination.tab == AppTab.PERSONAL_PLANNER && destination.plannerDay != null &&
            destination.plannerDraft == null && destination.plannerItemID == null &&
            state.value.tab == AppTab.PERSONAL_PLANNER && state.value.plannerDay == destination.plannerDay && !awaitingPlannerEntry
        awaitingPlannerEntry = destination.tab == AppTab.PERSONAL_PLANNER &&
            (destination.plannerDraft != null || destination.plannerItemID != null || destination.plannerDay != null) && !handledDayEntry
        if (destination.tab == AppTab.PERSONAL_PLANNER) {
            val draft = destination.plannerDraft?.let { PersonalPlannerItem(title = it.title, type = it.type, dueDate = it.dueDate) }
            openPlanner(day = destination.plannerDay, itemID = destination.plannerItemID, item = draft)
        } else select(destination.tab)
    }

    fun openPlanner(day: String? = null, item: PersonalPlannerItem? = null, itemID: String? = null) {
        mutableState.update { it.copy(tab = AppTab.PERSONAL_PLANNER, plannerDay = day, plannerDraft = item, plannerItemID = itemID, backStack = nextBackStack(it, AppTab.PERSONAL_PLANNER)) }
    }

    fun plannerEntryHandled() {
        awaitingPlannerEntry = false
        mutableState.update { it.copy(plannerDraft = null, plannerItemID = null) }
    }

    fun refreshCompanionVisibility() {
        val snapshot = state.value
        val week = snapshot.currentTimetable
        val token = CompanionSync.capture(app)
        viewModelScope.launch {
            try { CompanionSync.publish(app, week, snapshot.dashboard.user, snapshot.school?.cacheScope, token) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* School data stays available if the launcher cannot refresh its widget. */ }
        }
    }

    fun openAI(request: AIEntryRequest) {
        mutableState.update { it.copy(tab = AppTab.AI, aiEntry = request, backStack = nextBackStack(it, AppTab.AI)) }
    }

    /** Opens Gradey AI on a new chat, without a requested action. */
    fun openAI() {
        mutableState.update { it.copy(tab = AppTab.AI, aiEntry = null, backStack = nextBackStack(it, AppTab.AI)) }
    }

    /** Marks consumed the request to open one subject, so returning to Marks shows the list. */
    fun subjectHandled() {
        mutableState.update { it.copy(focusedSubjectID = null) }
    }

    fun openSubject(subjectID: String) {
        mutableState.update { it.copy(tab = AppTab.SUBJECTS, focusedSubjectID = subjectID, backStack = emptyList()) }
    }

    fun plannerChanged(items: List<PersonalPlannerItem>) {
        mutableState.update { it.copy(personalPlannerItems = items) }
    }

    private suspend fun refreshCanteenAccounts(force: Boolean) {
        val session = graph.stravaCZRepository.bootstrapSession()
        val accounts = if (state.value.isGuest) emptyList() else if (force) graph.canteenAccountRepository.refreshAccounts() else graph.canteenAccountRepository.cachedAccounts()
        val linkID = if (state.value.isGuest || session == null) null else graph.canteenAccountRepository.linkedAccountID(session)
        mutableState.update { it.copy(canteenAccounts = accounts, canteenSession = session, currentCanteenLinkID = linkID, hasMealAccount = session != null) }
    }

    fun linkCanteen() = action {
        if (state.value.isDemo || state.value.isGuest) return@action
        val session = graph.stravaCZRepository.bootstrapSession() ?: return@action
        graph.canteenAccountRepository.linkCurrent(session)
        refreshCanteenAccounts(false)
    }

    fun unlinkCanteen(account: LinkedCanteenAccount) = action {
        if (state.value.isDemo || state.value.isGuest) return@action
        graph.canteenAccountRepository.unlinkAccount(account.id)
        refreshCanteenAccounts(false)
    }

    private suspend fun updateInsights() {
        val schoolScope = state.value.school?.cacheScope ?: return
        val store = graph.schoolInsightStore
        val snapshot = withContext(kotlinx.coroutines.Dispatchers.IO) { store.snapshot(schoolScope) }
        mutableState.update { if (it.school?.cacheScope == schoolScope) it.copy(insightState = snapshot) else it }
    }

    fun openAttention(insight: com.bukovinafilip.gradey.domain.TodayInsight) = action {
        val schoolScope = state.value.school?.cacheScope ?: return@action
        withContext(kotlinx.coroutines.Dispatchers.IO) { graph.schoolInsightStore.markSeen(schoolScope, insight.observationIDs.toSet()) }
        updateInsights()
        when (val destination = insight.destination) {
            is com.bukovinafilip.gradey.domain.TodayInsightDestination.Subject -> mutableState.update { it.copy(tab = AppTab.SUBJECTS, focusedSubjectID = destination.id) }
            is com.bukovinafilip.gradey.domain.TodayInsightDestination.PlannerItem -> openPlanner(itemID = destination.id)
            com.bukovinafilip.gradey.domain.TodayInsightDestination.Planner -> openPlanner()
        }
    }

    suspend fun findNextPlannerLesson(item: PersonalPlannerItem): java.time.Instant? =
        com.bukovinafilip.gradey.domain.PlannerLessonResolver(
            loadWeek = { graph.schoolRepository.loadTimetable(it) },
            cachedWeek = { graph.schoolRepository.loadCachedTimetable(it) },
            currentScope = { state.value.school?.cacheScope },
        ).nextDate(item, state.value.timetable)


    init {
        bootstrap()
        viewModelScope.launch {
            val available = try { app.graph.gradeyAuthRepository.appleSignInAvailable() }
                catch (error: CancellationException) { throw error } catch (_: Exception) { false }
            appleAvailable = available
            mutableState.update { it.copy(appleSignInAvailable = available) }
        }
    }

    private fun bootstrap() = action {
        if (modeStore.getBoolean("guest", false)) {
            restoreGuest()
        } else if (modeStore.getBoolean("demo", false)) {
            enterDemo()
        } else {
            var offline = false
            val auth = try { graph.gradeyAuthRepository.bootstrapSession() } catch (_: java.io.IOException) {
                offline = true
                graph.gradeyAuthRepository.cachedSession()
            }
            if (auth != null) modeStore.edit().putString("owner", auth.account.id).commit()
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
                if (offline) showError(context.getString(R.string.app_error_offline)) else refreshData()
            } else {
                clearCompanions()
                if (auth != null) {
                    val linked = if (offline) graph.linkedAccountRepository.cachedAccounts() else graph.linkedAccountRepository.localAccounts()
                    mutableState.update { it.copy(linkedAccounts = linked) }
                }
            }
        }
    }

    fun demo() = action { enterDemo() }

    fun continueAsGuest() = action {
        if (state.value.isGuest) {
            mutableState.update { it.copy(phase = if (it.school == null) AppPhase.NEEDS_SCHOOL else AppPhase.SIGNED_IN) }
        } else {
            clearSignedInState()
            graph = app.localGraph
            graph.gradeyAuthRepository.signOut()
            graph.schoolRepository.logout()
            modeStore.edit().putBoolean("guest", true).putBoolean("demo", false).apply()
            mutableState.value = GradeyState(phase = AppPhase.NEEDS_SCHOOL, isGuest = true, loading = true, preferences = guestPreferences.load())
        }
    }

    fun requestGradeySignIn() {
        if (state.value.isGuest && !state.value.loading) mutableState.update { it.copy(phase = AppPhase.SIGNED_OUT, error = null) }
    }

    private suspend fun restoreGuest() {
        graph = app.localGraph
        // A provider sign-in may have committed its session just before the process died.
        val pendingAuth = graph.gradeyAuthRepository.cachedSession()
        if (pendingAuth != null) {
            val verifiedSession = try {
                val verified = graph.gradeyAuthRepository.refreshAccount()
                check(verified.id == pendingAuth.account.id)
                val current = graph.gradeyAuthRepository.cachedSession() ?: error("Gradey ID is no longer signed in.")
                check(current.account.id == verified.id)
                current.copy(account = verified)
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { null }
            if (verifiedSession != null) {
                mutableState.update { it.copy(isGuest = true) }
                finishGradeySignIn(verifiedSession)
                return
            }
        }
        var offline = false
        val school = try { graph.schoolRepository.bootstrapSession() }
            catch (_: java.io.IOException) { offline = true; graph.schoolRepository.currentStoredSession() }
        mutableState.value = GradeyState(phase = if (school == null) AppPhase.NEEDS_SCHOOL else AppPhase.SIGNED_IN,
            school = school, isGuest = true, loading = true, preferences = guestPreferences.load(), tab = state.value.tab)
        if (school != null) {
            restoreCachedData()
            if (offline) showError(context.getString(R.string.app_error_offline)) else refreshData()
        } else clearCompanions()
        if (pendingAuth != null) showError(context.getString(R.string.app_guest_upgrade_pending))
    }

    private suspend fun enterDemo() {
        clearCompanions()
        graph = app.createDemoGraph()
        val auth = graph.gradeyAuthRepository.signInWithGoogle("demo", fullName = "Demo Student")
        var school = (graph.schoolRepository.beginLogin(SchoolProvider.BAKALARI, "https://demo.gradey.app", "demo", "demo") as SchoolLoginStep.SignedIn).session
        val linked = graph.linkedAccountRepository.linkSchoolAccount(school, "Demo school account")
        school = school.copy(linkedAccountID = linked.id)
        graph.schoolRepository.activateLinkedSchoolAccount(school)
        modeStore.edit().putBoolean("demo", true).remove("guest").apply()
        mutableState.value = GradeyState(phase = AppPhase.SIGNED_IN, tab = state.value.tab, loading = true, account = auth.account, school = school, isDemo = true)
        restoreCachedData()
        refreshData()
    }

    fun signIn(token: String, name: String?) = action {
        graph = app.graph
        val auth = graph.gradeyAuthRepository.signInWithGoogle(token, fullName = name)
        finishGradeySignIn(auth)
    }

    suspend fun beginAppleSignIn(): String = app.graph.gradeyAuthRepository.beginAppleSignIn()
    suspend fun cancelAppleSignIn() = app.graph.gradeyAuthRepository.cancelAppleSignIn()

    fun acceptAppleCallback(callback: String) {
        if (state.value.loading || state.value.phase == AppPhase.CHECKING) {
            pendingAppleCallback = callback
            return
        }
        action {
            graph = app.graph
            finishGradeySignIn(graph.gradeyAuthRepository.completeAppleSignIn(callback))
        }
    }

    private suspend fun finishGradeySignIn(auth: GradeyAuthSession) {
        val migratingGuest = modeStore.getBoolean("guest", false)
        val pendingMigration = if (migratingGuest) guestMigrationJournal.load() else null
        clearCompanions()
        if (migratingGuest) {
            check(pendingMigration == null || pendingMigration.ownerID == auth.account.id) { "The pending school upgrade belongs to another Gradey ID." }
            val source = pendingMigration?.source ?: graph.schoolRepository.currentStoredSession()
            if (source != null) {
                try {
                    migrateGuestSchool(auth, source)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) {
                    // Linking may have saved its cloud ID before a later local write failed. Keep the old
                    // scope visible and leave the durable journal in place for Refresh / next launch.
                    val current = graph.schoolRepository.currentStoredSession()
                    val sameSchool = current?.copy(linkedAccountID = null)?.cacheScope == source.copy(linkedAccountID = null).cacheScope
                    if (sameSchool) graph.schoolRepository.activateLinkedSchoolAccount(current!!.copy(
                        linkedAccountID = source.linkedAccountID, linkedAccountDisplayName = source.linkedAccountDisplayName,
                        linkedAccountSchoolName = source.linkedAccountSchoolName))
                    check(modeStore.edit().putBoolean("guest", true).putBoolean("demo", false).commit())
                    val school = graph.schoolRepository.currentStoredSession()
                    mutableState.value = GradeyState(isGuest = true, school = school, loading = true, tab = state.value.tab,
                        preferences = guestPreferences.load(), phase = if (school == null) AppPhase.NEEDS_SCHOOL else AppPhase.SIGNED_IN)
                    if (school != null) restoreCachedData()
                    showError(context.getString(R.string.app_guest_upgrade_pending))
                    return
                }
            }
        } else if (modeStore.getString("owner", null) != auth.account.id) {
            // A new Gradey identity must never inherit another user's school sessions or local plans.
            graph.linkedAccountRepository.clearLocalAccounts()
            graph.canteenAccountRepository.clearLocalAccounts()
            graph.schoolRepository.logout()
            graph.cache?.clearAll()
            graph.schoolInsightStore.clearAll()
            plansStore.clearPrefix("plans.")
            guestMigrationJournal.save(null)
        }
        check(modeStore.edit().putBoolean("demo", false).remove("guest").putString("owner", auth.account.id).commit())
        // Clearing this only after the ownership flags are durable closes the process-death window.
        guestMigrationJournal.save(null)
        val school = graph.schoolRepository.currentStoredSession()
        mutableState.value = GradeyState(account = auth.account, school = school, tab = state.value.tab,
            phase = if (school == null) AppPhase.NEEDS_SCHOOL else AppPhase.SIGNED_IN, loading = true, isDemo = graph.isDemo)
        if (school != null) {
            restoreCachedData()
            refreshData()
        } else {
            val accounts = graph.linkedAccountRepository.localAccounts()
            mutableState.update { it.copy(linkedAccounts = accounts) }
        }
    }

    private suspend fun migrateGuestSchool(auth: GradeyAuthSession, source: StoredSession) {
        val effects = object : com.bukovinafilip.gradey.domain.GuestSchoolMigrationEffects {
            override suspend fun currentOwnerID() = graph.gradeyAuthRepository.cachedSession()?.account?.id
            override suspend fun currentSchool() = graph.schoolRepository.currentStoredSession()
            override suspend fun link(source: StoredSession): StoredSession {
                val user = graph.schoolRepository.loadDashboard(true).user
                check(currentOwnerID() == auth.account.id)
                // Use the provider's freshly returned stable user identity, never a cached display name.
                check(!user?.userUID.isNullOrBlank() || !source.eduPage?.activeStudent?.id.isNullOrBlank()) { "The school identity could not be verified." }
                val fresh = currentSchool() ?: error("The school account is no longer available.")
                check(fresh.copy(linkedAccountID = null).cacheScope == source.copy(linkedAccountID = null).cacheScope)
                val displayName = user?.fullName ?: fresh.eduPage?.activeStudent?.fullName ?: context.getString(R.string.app_error_school_account)
                val linked = graph.linkedAccountRepository.linkSchoolAccount(fresh, displayName, user)
                return fresh.copy(linkedAccountID = linked.id, linkedAccountDisplayName = linked.displayName, linkedAccountSchoolName = linked.schoolName)
            }
            override suspend fun copyAbsencePlans(sourceScope: String, targetScope: String) = guestPlanMigration.copy(sourceScope, targetScope)
            override suspend fun copyAbsenceOverrides(sourceScope: String, targetScope: String) {
                val target = absenceOverrideRepository.load(targetScope)
                val source = absenceOverrideRepository.load(sourceScope).map { it.copy(scope = targetScope) }
                absenceOverrideRepository.replace(targetScope, (target + source).distinctBy { it.dateKey })
            }
            override suspend fun rebindPlanner(sourceScope: String, targetScope: String) {
                plannerRepository.rebindSchoolScope(sourceScope, targetScope)
            }
            override suspend fun activate(target: StoredSession) { graph.schoolRepository.activateLinkedSchoolAccount(target) }
        }
        com.bukovinafilip.gradey.domain.GuestSchoolMigrationCoordinator(guestMigrationJournal, effects).migrate(auth.account.id, source)
    }

    fun loadSchoolDirectory() {
        if (state.value.directoryLoading) return
        val provider = graph.schoolDirectoryProvider
        mutableState.update { it.copy(directoryLoading = true, directoryError = null) }
        viewModelScope.launch {
            try {
                val cached = provider.cachedSchools()
                mutableState.update { it.copy(directorySchools = cached) }
                val schools = provider.refreshDirectory()
                mutableState.update { it.copy(directorySchools = schools) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) {
                mutableState.update { it.copy(directoryError = context.getString(R.string.app_error_directory)) }
            } finally { mutableState.update { it.copy(directoryLoading = false) } }
        }
    }

    fun login(provider: SchoolProvider, school: String, user: String, password: String) = action {
        if (!allowSchoolAccountChanges()) return@action
        clearCompanions()
        finishLogin(graph.schoolRepository.beginLogin(provider, school, user, password))
    }

    private suspend fun finishLogin(step: SchoolLoginStep) {
        when (step) {
            is SchoolLoginStep.SignedIn -> {
                clearCompanions()
                var session = step.session
                var linkError: String? = null
                if (!state.value.isGuest) try {
                    val user = graph.schoolRepository.loadDashboard(true).user
                    val displayName = user?.fullName ?: session.eduPage?.activeStudent?.fullName ?: session.bakalari?.username ?: context.getString(R.string.app_error_school_account)
                    val reconnecting = state.value.reconnectingAccount
                    val linked = if (reconnecting != null) {
                        graph.linkedAccountRepository.reconnectSchoolAccount(reconnecting.id, session, reconnecting.displayName, user)
                    } else graph.linkedAccountRepository.linkSchoolAccount(session, displayName, user)
                    session = session.copy(linkedAccountID = linked.id, linkedAccountDisplayName = linked.displayName, linkedAccountSchoolName = linked.schoolName)
                    graph.schoolRepository.activateLinkedSchoolAccount(session)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    if (state.value.reconnectingAccount != null) {
                        state.value.school?.let { graph.schoolRepository.activateLinkedSchoolAccount(it) }
                        throw e
                    }
                    linkError = context.getString(R.string.app_error_school_sync)
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
        else showError(context.getString(R.string.app_error_edupage_pending))
    }
    fun resendApproval() = action { graph.schoolRepository.resendEduPageTwoFactorNotification() }
    fun selectStudent(id: String) = action { finishLogin(SchoolLoginStep.SignedIn(graph.schoolRepository.selectEduPageStudent(id))) }
    fun cancelVerification() { if (!state.value.loading) mutableState.update { it.copy(loginStep = null, error = null) } }

    fun showError(message: String) { mutableState.update { it.copy(error = message) } }
    fun dismissError() { mutableState.update { it.copy(error = null) } }
    fun select(tab: AppTab) {
        if (tab != AppTab.PERSONAL_PLANNER) awaitingPlannerEntry = false
        mutableState.update {
            val moved = if (tab == AppTab.PERSONAL_PLANNER && it.tab != tab) it.copy(tab = tab, plannerDraft = null, plannerDay = null, plannerItemID = null) else it.copy(tab = tab, focusedSubjectID = if (tab == AppTab.SUBJECTS && it.tab != tab) null else it.focusedSubjectID, aiEntry = if (tab == AppTab.AI && it.tab != tab) null else it.aiEntry)
            moved.copy(backStack = nextBackStack(it, tab))
        }
        if (tab == AppTab.TODAY && state.value.phase == AppPhase.SIGNED_IN && !state.value.loading) action {
            updateInsights()
            val mealSession = graph.stravaCZRepository.bootstrapSession()
            val menu = if (mealSession == null) null else graph.stravaCZRepository.loadCachedMenu()
            mutableState.update { it.copy(hasMealAccount = mealSession != null, mealMenu = menu) }
        }
        if (tab == AppTab.ACCOUNT && state.value.phase == AppPhase.SIGNED_IN && !state.value.loading) action { refreshCanteenAccounts(true) }
    }
    fun back() {
        if (state.value.loading && state.value.phase != AppPhase.SIGNED_IN) return
        if (state.value.phase == AppPhase.NEEDS_SCHOOL && state.value.school != null) {
            mutableState.update { it.copy(phase = AppPhase.SIGNED_IN, tab = AppTab.ACCOUNT, error = null) }
            return
        }
        val previous = state.value.backStack.lastOrNull()
        if (previous == null) { select(AppTab.TODAY); return }
        if (previous != AppTab.PERSONAL_PLANNER) awaitingPlannerEntry = false
        mutableState.update { it.copy(tab = previous, backStack = it.backStack.dropLast(1)) }
    }

    /** True when Back should leave the current destination rather than the app. */
    val canGoBack: Boolean get() = state.value.tab != AppTab.TODAY || state.value.backStack.isNotEmpty()

    fun openReportCard() = select(AppTab.REPORT_CARD)
    fun openRecapList() = select(AppTab.RECAP_LIST)
    fun openRecapStory(periodID: String) {
        mutableState.update { it.copy(recapPeriodID = periodID) }
        select(AppTab.RECAP_STORY)
    }
    fun updateProfile(name: String) = action {
        val account = graph.gradeyAuthRepository.updateFullName(name.trim())
        mutableState.update { it.copy(account = account) }
    }

    fun reconnectAccount(account: LinkedSchoolAccount) {
        if (state.value.loading || !allowSchoolAccountChanges()) return
        mutableState.update { it.copy(phase = AppPhase.NEEDS_SCHOOL, reconnectingAccount = account, loginStep = null, error = null) }
    }

    fun accountNotifications(account: LinkedSchoolAccount, enabled: Boolean) = action {
        val updated = try { graph.linkedAccountRepository.updateNotificationsEnabled(account.id, enabled) }
            finally { AccountNotifications.clearChannel(app, MARKS_CHANNEL) }
        mutableState.update { it.copy(linkedAccounts = it.linkedAccounts.map { linked -> if (linked.id == updated.id) updated else linked }) }
    }

    fun addAccount() {
        if (state.value.loading || !allowSchoolAccountChanges()) return
        mutableState.update { it.copy(phase = AppPhase.NEEDS_SCHOOL, error = null, loginStep = null, reconnectingAccount = null) }
    }

    private fun allowSchoolAccountChanges(): Boolean {
        if (!graph.isDemo) return true
        showError(context.getString(R.string.app_error_demo_accounts))
        return false
    }

    fun refresh() = action {
        if (state.value.isGuest && graph.gradeyAuthRepository.cachedSession() != null) { restoreGuest(); return@action }
        if (state.value.phase == AppPhase.SIGNED_IN) refreshData()
    }

    private suspend fun restoreCachedData() {
        val session = state.value.school ?: return
        val isGuest = state.value.isGuest
        val plannerItems = off { plannerRepository.load() }
        val cached = off { graph.schoolRepository.loadCachedDashboard() }
        val cachedAbsence = off { graph.cache?.loadAbsence(session.cacheScope) }
        val week = off { graph.schoolRepository.loadCachedTimetable(LocalDate.now(java.time.ZoneId.of("Europe/Prague")).toString()) }
        val plans = off { plansStore.load("plans.${session.cacheScope}", ListSerializer(PlannedAbsence.serializer())).orEmpty() }
        val preferences = off { if (isGuest) guestPreferences.load() else graph.notificationSettings.load() }
        val linkedAccounts = off { if (isGuest) emptyList() else graph.linkedAccountRepository.cachedAccounts() }
        val mealSession = off { graph.stravaCZRepository.bootstrapSession() }
        val mealMenu = if (mealSession != null) off { graph.stravaCZRepository.loadCachedMenu() } else null
        val homework = off { runCatching { graph.schoolRepository.loadCachedHomework() }.getOrDefault(emptyList()) }
        val komens = off { runCatching { graph.schoolRepository.loadCachedKomens() }.getOrNull() }
        mutableState.update { it.copy(dashboard = cached ?: DashboardData(MarksResponse()), personalPlannerItems = plannerItems, homework = homework, komens = komens,
            timetable = week, currentTimetable = week, viewedWeek = week?.weekStart ?: LocalDate.now(java.time.ZoneId.of("Europe/Prague")).toString(), plans = plans,
            preferences = preferences, linkedAccounts = linkedAccounts, mealMenu = mealMenu, hasMealAccount = mealSession != null,
            absence = cachedAbsence ?: AbsenceResponse(absencesPerSubject = cached?.absencesPerSubject.orEmpty())) }
        applyAbsence(cachedAbsence ?: AbsenceResponse(absencesPerSubject = cached?.absencesPerSubject.orEmpty()))
        updateInsights()
        refreshCanteenAccounts(false)
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
        attempt(context.getString(R.string.app_planner)) { val items = off { plannerRepository.load() }; mutableState.update { it.copy(personalPlannerItems = items) } }
        attempt(context.getString(R.string.app_marks)) { val dashboard = off { graph.schoolRepository.loadDashboard(true) }; mutableState.update { it.copy(dashboard = dashboard) } }
        attempt(context.getString(R.string.app_absence)) { applyAbsence(off { graph.schoolRepository.loadAbsence(true) }) }
        attempt(context.getString(R.string.app_timetable)) {
            val week = off { graph.schoolRepository.loadTimetable(LocalDate.now(java.time.ZoneId.of("Europe/Prague")).toString()) }
            mutableState.update { it.copy(currentTimetable = week, timetable = week, viewedWeek = week.weekStart) }
            syncCompanions(week)
        }
        refreshHomework()
        refreshKomens()
        if (!state.value.isGuest) attempt(context.getString(R.string.app_school_accounts)) { val accounts = off { graph.linkedAccountRepository.localAccounts() }; mutableState.update { it.copy(linkedAccounts = accounts) } }
        attempt(context.getString(R.string.app_meals)) {
            val mealSession = off { graph.stravaCZRepository.bootstrapSession() }
            val menu = if (mealSession != null) off { graph.stravaCZRepository.loadMenu().second } else null
            mutableState.update { it.copy(hasMealAccount = mealSession != null, mealMenu = menu) }
            refreshCanteenAccounts(!state.value.isGuest)
        }
        val guest = state.value.isGuest
        val savedPreferences = off { if (guest) guestPreferences.load() else graph.notificationSettings.load() }
        mutableState.update { it.copy(preferences = savedPreferences) }
        if (!guest) attempt(context.getString(R.string.app_notification_settings)) { val prefs = off { graph.notificationSettings.refresh() }; mutableState.update { it.copy(preferences = prefs) } }
        // Refreshes can rotate provider tokens or restore a selected student's session.
        val refreshedSession = off { graph.schoolRepository.currentStoredSession() }
        mutableState.update { it.copy(school = refreshedSession) }
        updateInsights()
        if (failures.isNotEmpty()) showError(context.getString(R.string.app_refresh_failed, failures.joinToString()))
    }

    /**
     * Homework is an extra: a school without the module, or a failed load, keeps the list there is
     * and never adds to the refresh error.
     */
    private suspend fun refreshHomework() {
        val schoolScope = state.value.school?.cacheScope ?: return
        try {
            val fresh = off { graph.schoolRepository.loadHomework() }
            mutableState.update {
                if (it.school?.cacheScope != schoolScope) it else it.copy(homework = HomeworkList.merged(fresh, it.homework, it.homeworkPendingIDs))
            }
            homeworkLoaded = schoolScope to android.os.SystemClock.elapsedRealtime()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (_: Exception) { }
    }

    /** Opening the Planner or Timetable brings in new homework, at most once in fifteen minutes (as on iOS). */
    fun refreshHomeworkIfStale() {
        val schoolScope = state.value.school?.cacheScope ?: return
        if (state.value.phase != AppPhase.SIGNED_IN || homeworkRefresh?.isActive == true) return
        val (loadedScope, loadedAt) = homeworkLoaded ?: (null to 0L)
        if (loadedScope == schoolScope && android.os.SystemClock.elapsedRealtime() - loadedAt < 15 * 60_000L) return
        homeworkRefresh = viewModelScope.launch { refreshHomework() }
    }

    /**
     * Komens is an extra, like homework: a failed load keeps the lists there are and is reported
     * only on the Messages screen, never in the refresh error.
     */
    private suspend fun refreshKomens() {
        val school = state.value.school ?: return
        if (school.provider != SchoolProvider.BAKALARI) return
        val schoolScope = school.cacheScope
        mutableState.update { if (it.school?.cacheScope == schoolScope) it.copy(komensRefreshing = true) else it }
        try {
            val snapshot = off { graph.schoolRepository.loadKomens() }
            mutableState.update { if (it.school?.cacheScope != schoolScope) it else it.copy(komens = snapshot, komensError = null) }
            komensLoaded = schoolScope to android.os.SystemClock.elapsedRealtime()
        } catch (cancelled: CancellationException) { throw cancelled
        } catch (error: Exception) {
            mutableState.update { if (it.school?.cacheScope != schoolScope) it else it.copy(komensError = safeError(error, context)) }
        } finally {
            mutableState.update { if (it.school?.cacheScope == schoolScope) it.copy(komensRefreshing = false) else it }
        }
    }

    /** Opening Messages loads them at most once in fifteen minutes; the refresh button always does (as on iOS). */
    fun refreshKomens(force: Boolean) {
        val schoolScope = state.value.school?.cacheScope ?: return
        if (state.value.phase != AppPhase.SIGNED_IN || komensRefresh?.isActive == true) return
        val (loadedScope, loadedAt) = komensLoaded ?: (null to 0L)
        if (!force && loadedScope == schoolScope && android.os.SystemClock.elapsedRealtime() - loadedAt < 15 * 60_000L) return
        komensRefresh = viewModelScope.launch { refreshKomens() }
    }

    fun openKomens() {
        select(AppTab.KOMENS)
        refreshKomens(force = false)
    }

    /**
     * Shows the message as read at once (iOS `KomensStore.markRead`). If the school refuses, it turns
     * unread again and is marked the next time it is opened.
     */
    fun markKomensRead(message: KomensMessage) {
        val snapshot = state.value
        val schoolScope = snapshot.school?.cacheScope ?: return
        val id = message.messageID
        if (message.isRead || id in snapshot.komensReadIDs) return
        val repository = graph.schoolRepository
        mutableState.update { it.copy(komensReadIDs = it.komensReadIDs + id) }
        viewModelScope.launch {
            val sent = try { off { repository.markKomensRead(id) }; true }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { false }
            mutableState.update {
                when {
                    it.school?.cacheScope != schoolScope -> it
                    // The lists on screen follow the saved ones, so the message stays read after the next load too.
                    sent -> it.copy(komens = it.komens?.markingRead(id))
                    else -> it.copy(komensReadIDs = it.komensReadIDs - id)
                }
            }
        }
    }

    /** Shows the tick at once and puts it back if the school refuses it (iOS `HomeworkStore.setDone`). */
    fun setHomeworkDone(id: String, isDone: Boolean) {
        val snapshot = state.value
        val schoolScope = snapshot.school?.cacheScope ?: return
        val previous = snapshot.homework.firstOrNull { it.id == id }?.isDone ?: return
        if (id in snapshot.homeworkPendingIDs || previous == isDone) return
        val repository = graph.schoolRepository
        mutableState.update { it.copy(homework = HomeworkList.withDone(it.homework, id, isDone), homeworkPendingIDs = it.homeworkPendingIDs + id) }
        viewModelScope.launch {
            val failure = try { off { repository.setHomeworkDone(id, isDone) }; null }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (error: Exception) { error }
            mutableState.update {
                if (it.school?.cacheScope != schoolScope) it
                else it.copy(
                    homework = if (failure == null) it.homework else HomeworkList.withDone(it.homework, id, previous),
                    homeworkPendingIDs = it.homeworkPendingIDs - id,
                )
            }
            if (failure != null && state.value.school?.cacheScope == schoolScope) showError(safeError(failure, context))
        }
    }

    fun changeWeek(date: String) = action {
        mutableState.update { it.copy(viewedWeek = date, timetable = null) }
        val cached = off { graph.schoolRepository.loadCachedTimetable(date) }
        mutableState.update { it.copy(timetable = cached) }
        val week = off { graph.schoolRepository.loadTimetable(date) }
        mutableState.update { it.copy(timetable = week) }
    }

    fun changeTimetableKind(kind: TimetableKind) {
        if (kind == TimetableKind.PERMANENT && state.value.school?.provider != SchoolProvider.BAKALARI) return
        mutableState.update { it.copy(timetableKind = kind) }
        if (kind == TimetableKind.PERMANENT) refreshPermanentTimetable()
    }

    fun refreshPermanentTimetable() {
        val schoolScope = state.value.school?.cacheScope ?: return
        if (state.value.school?.provider != SchoolProvider.BAKALARI) return
        val repository = graph.schoolRepository
        val revision = ++permanentRevision
        mutableState.update { it.copy(permanentTimetableLoading = true, permanentTimetableError = null) }
        viewModelScope.launch {
            fun current() = revision == permanentRevision && schoolScope == state.value.school?.cacheScope && repository === graph.schoolRepository
            try {
                val cached = repository.loadCachedPermanentTimetable()
                if (current() && cached != null) mutableState.update { it.copy(permanentTimetable = cached) }
                val fresh = repository.loadPermanentTimetable()
                if (current()) mutableState.update { it.copy(permanentTimetable = fresh) }
            } catch (error: CancellationException) { throw error }
            catch (error: Exception) {
                if (current()) mutableState.update { it.copy(permanentTimetableError = safeError(error, context)) }
            } finally {
                if (current()) mutableState.update { it.copy(permanentTimetableLoading = false) }
            }
        }
    }

    private suspend fun applyAbsence(raw: AbsenceResponse) {
        val scope = state.value.absenceOverrideScope
        if (scope == null) { mutableState.update { it.copy(rawAbsence = raw, absence = raw, absenceOverrides = emptyList()) }; return }
        try {
            val result = off {
                val stored = absenceOverrideRepository.load(scope)
                val projected = com.bukovinafilip.gradey.domain.AbsenceOverrideProjection.project(raw, stored, scope)
                if (projected.reconciledOverrides != stored) absenceOverrideRepository.replace(scope, projected.reconciledOverrides)
                projected
            }
            mutableState.update { if (it.absenceOverrideScope != scope) it else it.copy(rawAbsence = raw, absence = result.response, absenceOverrides = result.reconciledOverrides, correctionError = null) }
        } catch (error: CancellationException) { throw error }
        catch (_: Exception) {
            mutableState.update { if (it.absenceOverrideScope != scope) it else it.copy(rawAbsence = raw, absence = raw, absenceOverrides = emptyList(), correctionError = context.getString(R.string.app_error_corrections_load)) }
        }
    }

    fun saveAbsenceOverride(value: AbsenceDayOverride) = action {
        check(value.scope == state.value.absenceOverrideScope) { context.getString(R.string.app_error_correction_scope) }
        try {
            absenceOverrideRepository.save(value)
            applyAbsence(state.value.rawAbsence)
        } catch (error: CancellationException) { throw error }
        catch (error: Exception) {
            mutableState.update { it.copy(correctionError = context.getString(R.string.app_error_correction_save)) }
            throw error
        }
    }

    fun restoreAbsenceOverride(date: String) = action {
        val scope = state.value.absenceOverrideScope ?: return@action
        absenceOverrideRepository.restore(scope, date)
        applyAbsence(state.value.rawAbsence)
    }

    fun resetAbsenceOverrides() = action {
        val scope = state.value.absenceOverrideScope ?: return@action
        absenceOverrideRepository.reset(scope)
        applyAbsence(state.value.rawAbsence)
    }

    fun loadCorrectionWeek(date: String) {
        val schoolScope = state.value.school?.cacheScope ?: return
        val repository = graph.schoolRepository
        val revision = ++correctionRevision
        mutableState.update { it.copy(correctionTimetable = null) }
        viewModelScope.launch {
            try {
                val cached = repository.loadCachedTimetable(date)
                if (revision != correctionRevision || state.value.school?.cacheScope != schoolScope) return@launch
                mutableState.update { it.copy(correctionTimetable = cached) }
                val week = repository.loadTimetable(date)
                if (revision == correctionRevision && state.value.school?.cacheScope == schoolScope) mutableState.update { it.copy(correctionTimetable = week) }
            } catch (error: CancellationException) { throw error }
            catch (_: Exception) { /* Manual subject allocation remains available without a timetable. */ }
        }
    }

    fun savePlan(plan: PlannedAbsence) {
        if (state.value.loading || state.value.phase != AppPhase.SIGNED_IN) return
        val school = state.value.school ?: return
        val plans = (state.value.plans.filterNot { it.date == plan.date } + plan).sortedBy { it.date }
        mutableState.update { it.copy(plans = plans) }
        persistPlans(school.cacheScope, plans)
    }

    fun removePlan(date: String) {
        if (state.value.loading || state.value.phase != AppPhase.SIGNED_IN) return
        val school = state.value.school ?: return
        val plans = state.value.plans.filterNot { it.date == date }
        mutableState.update { it.copy(plans = plans) }
        persistPlans(school.cacheScope, plans)
    }

    fun activateAccount(id: String) = action {
        clearCompanions()
        val session = graph.linkedAccountRepository.activateAccount(id)
        if (session == null) {
            val linked = state.value.linkedAccounts.find { it.id == id } ?: error(context.getString(R.string.app_error_school_reconnect))
            mutableState.update { it.copy(phase = AppPhase.NEEDS_SCHOOL, reconnectingAccount = linked, loginStep = null,
                error = context.getString(R.string.app_error_school_device)) }
            return@action
        }
        graph.schoolRepository.activateLinkedSchoolAccount(session)
        mutableState.update { it.withSchool(session) }
        clearCompanions()
        restoreCachedData()
        refreshData()
    }

    fun switchStudent(id: String) = action {
        val current = state.value.school ?: error(context.getString(R.string.app_error_school_first))
        require(current.provider == SchoolProvider.EDU_PAGE) { context.getString(R.string.app_error_students_edupage) }
        require(current.eduPage?.linkedStudents?.any { it.id == id } == true) { context.getString(R.string.app_error_student_missing) }
        if (current.eduPage?.activeStudent?.id == id) return@action
        clearCompanions()
        graph.schoolRepository.switchEduPageStudent(id)
        val updated = graph.schoolRepository.currentStoredSession() ?: error(context.getString(R.string.app_error_school_reconnect))
        mutableState.update { it.withSchool(updated) }
        clearCompanions()
        restoreCachedData()
        refreshData()
    }

    fun unlinkAccount(id: String) = action {
        if (!allowSchoolAccountChanges()) return@action
        if (state.value.school?.linkedAccountID == id) clearCompanions()
        val removedScope = graph.linkedAccountRepository.storedSession(id)?.cacheScope
        try { graph.linkedAccountRepository.unlinkAccount(id) }
        finally { AccountNotifications.clearChannel(app, MARKS_CHANNEL) }
        removedScope?.let { plansStore.clear("plans.$it") }
        if (state.value.school?.linkedAccountID == id) {
            graph.schoolRepository.logout()
            SchoolAttachments.clear(app)
            clearCompanions()
            mutableState.update { GradeyState(phase = AppPhase.NEEDS_SCHOOL, loading = true, account = it.account, isDemo = it.isDemo) }
        } else {
            val accounts = graph.linkedAccountRepository.localAccounts()
            mutableState.update { it.copy(linkedAccounts = accounts) }
        }
    }

    fun preferences(value: NotificationPreferences) = action {
        try { if (state.value.isGuest) guestPreferences.save(value) else graph.notificationSettings.save(value) }
        finally {
            AccountNotifications.clearChannel(app, MARKS_CHANNEL)
            PlannerReminderScheduler.invalidate(app)
        }
        mutableState.update { it.copy(preferences = if (it.isGuest) value.copy(newMarksEnabled = false) else value) }
    }

    fun deleteAccount() = action {
        val management = graph.accountManagement ?: error(context.getString(R.string.app_error_delete_demo))
        management.deleteAccount()
        clearSignedInState()
    }

    fun signOut() = action { clearSignedInState() }

    private suspend fun clearSignedInState() {
        // Complete local removal even when one provider is offline or the screen disappears mid-sign-out.
        withContext(NonCancellable) {
            awaitingPlannerEntry = false
            val signingOutGraph = graph
            mutableState.value = GradeyState(phase = AppPhase.SIGNED_OUT, loading = true, isDemo = graph.isDemo)
            var localCleanupFailed = false
            suspend fun cleanup(block: suspend () -> Unit) {
                try { block() } catch (_: Exception) { localCleanupFailed = true }
            }
            cleanup { AccountNotifications.endSession(app) }
            cleanup { androidx.work.WorkManager.getInstance(app).cancelUniqueWork("gradey-register-push") }
            cleanup { clearCompanions() }
            cleanup { com.bukovinafilip.gradey.push.PushDeliveryHistory.clear(app) }
            cleanup { signingOutGraph.linkedAccountRepository.clearLocalAccounts() }
            cleanup { signingOutGraph.canteenAccountRepository.clearLocalAccounts() }
            cleanup { signingOutGraph.schoolRepository.logout() }
            cleanup { SchoolAttachments.clear(app) }
            cleanup { signingOutGraph.cache?.clearAll() }
            cleanup { signingOutGraph.schoolInsightStore.clearAll() }
            cleanup { guestPreferences.clear() }
            cleanup { guestMigrationJournal.save(null) }
            cleanup { plansStore.clearPrefix("plans.") }
            cleanup { signingOutGraph.stravaCZRepository.logout() }
            cleanup { signingOutGraph.gradeyAuthRepository.signOut() }
            cleanup { com.bukovinafilip.gradey.feature.ai.resetAiIdentity(app) }
            cleanup { withTimeoutOrNull(5_000) { resetSupportIdentity() } }
            modeStore.edit().remove("demo").remove("guest").remove("owner").apply()
            graph = app.graph
            if (!signingOutGraph.isDemo && com.google.firebase.FirebaseApp.getApps(app).isNotEmpty()) {
                try { withTimeoutOrNull(5_000) { com.google.firebase.messaging.FirebaseMessaging.getInstance().deleteToken().await() } }
                catch (_: Exception) { /* The cloud session and local school data have already been removed. */ }
            }
            mutableState.value = GradeyState(phase = AppPhase.SIGNED_OUT, isDemo = graph.isDemo,
                error = if (localCleanupFailed) context.getString(R.string.app_error_cleanup) else null)
        }
    }

    private suspend fun syncCompanions(week: TimetableWeek) {
        CompanionSync.publish(app, week, state.value.dashboard.user, state.value.school?.cacheScope)
    }
    private suspend fun clearCompanions() {
        try { WearSchoolAccessManager.get(app).revokeAll() }
        finally {
            com.bukovinafilip.gradey.feature.support.GradeySupport.reset()
            PlannerReminderScheduler.invalidate(app)
            CompanionSync.clear(app)
        }
    }

    /** Tabs reset the stack; any other destination is pushed on top of the current one. */
    private fun nextBackStack(current: GradeyState, target: AppTab): List<AppTab> = when {
        target.kind == DestinationKind.TAB -> emptyList()
        current.tab == target -> current.backStack
        else -> (current.backStack.filterNot { it == target } + current.tab).takeLast(8)
    }

    /** Encrypted preferences are written off the main thread, in order, so a quick second edit cannot overtake the first. */
    private val planWrites = kotlinx.coroutines.sync.Mutex()
    private fun persistPlans(scope: String, plans: List<PlannedAbsence>) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            planWrites.withLock { plansStore.save("plans.$scope", plans, ListSerializer(PlannedAbsence.serializer())) }
        }
    }

    /**
     * Repository work (network decoding, Room/JSON caches, encrypted preferences) runs on a background
     * dispatcher so the main thread only applies the finished state and keeps every frame on time.
     */
    private suspend fun <T> off(block: suspend () -> T): T = withContext(kotlinx.coroutines.Dispatchers.Default) { block() }

    private fun action(block: suspend () -> Unit) {
        if (state.value.loading) return
        mutableState.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                mutableState.update { it.copy(error = safeError(e, context), phase = if (it.phase == AppPhase.CHECKING) AppPhase.SIGNED_OUT else it.phase) }
            } finally {
                mutableState.update { it.copy(loading = false, appleSignInAvailable = appleAvailable) }
                if (state.value.phase == AppPhase.SIGNED_IN) pendingDestination?.let(::openDestination)
                pendingAppleCallback?.let { callback ->
                    pendingAppleCallback = null
                    acceptAppleCallback(callback)
                }
            }
        }
    }
}

internal fun safeError(error: Exception, context: android.content.Context): String = when (error) {
    is com.bukovinafilip.gradey.domain.UserFacingFailure -> context.getString(error.failureReason.localizedResource())
    is com.bukovinafilip.gradey.network.BakalariApiException -> error.message ?: context.getString(R.string.app_error_bakalari)
    is com.bukovinafilip.gradey.network.EduPageAuthenticationException -> when (error.reason) {
        com.bukovinafilip.gradey.network.EduPageAuthFailure.BAD_CREDENTIALS -> context.getString(R.string.app_error_edupage_credentials)
        com.bukovinafilip.gradey.network.EduPageAuthFailure.CAPTCHA_REQUIRED -> context.getString(R.string.app_error_edupage_captcha)
        com.bukovinafilip.gradey.network.EduPageAuthFailure.TWO_FACTOR_REQUIRED -> context.getString(R.string.app_error_edupage_verification)
        com.bukovinafilip.gradey.network.EduPageAuthFailure.INVALID_TWO_FACTOR_CODE -> context.getString(R.string.app_error_edupage_code)
        com.bukovinafilip.gradey.network.EduPageAuthFailure.TWO_FACTOR_NOT_CONFIRMED -> context.getString(R.string.app_error_edupage_approve)
        com.bukovinafilip.gradey.network.EduPageAuthFailure.SESSION_EXPIRED -> context.getString(R.string.app_error_edupage_expired)
        else -> context.getString(R.string.app_error_edupage)
    }
    is java.io.IOException -> context.getString(R.string.app_error_connection)
    is com.bukovinafilip.gradey.network.GradeyApiException -> if (error.statusCode in listOf(401, 403)) context.getString(R.string.app_error_session) else context.getString(R.string.app_error_service)
    is IllegalArgumentException, is IllegalStateException -> error.message?.take(180) ?: context.getString(R.string.app_error_generic)
    else -> context.getString(R.string.app_error_generic)
}
