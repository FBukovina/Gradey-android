package com.bukovinafilip.gradey

import androidx.compose.ui.res.stringResource
import com.bukovinafilip.gradey.R
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bukovinafilip.gradey.feature.absence.AbsenceScreen
import com.bukovinafilip.gradey.feature.account.AccountScreen
import com.bukovinafilip.gradey.feature.auth.GradeyIdLoginScreen
import com.bukovinafilip.gradey.feature.login.SchoolLoginScreen
import com.bukovinafilip.gradey.feature.subjects.SubjectsScreen
import com.bukovinafilip.gradey.feature.timetable.TimetableScreen
import com.bukovinafilip.gradey.feature.today.TodayScreen
import com.bukovinafilip.gradey.ui.GradeyTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingLaunchLink: String? = null
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguages.localizedContext(newBase))
    }

    override fun onResume() {
        super.onResume()
        val app = application as GradeyApplication
        app.updates.onForeground()
        if (!app.activeGraph.isDemo && app.plannerCalendarMirror.hasAccess) lifecycleScope.launch {
            try { app.plannerCalendarMirror.synchronize() }
            catch (error: kotlinx.coroutines.CancellationException) { throw error }
            catch (_: Exception) { /* Planner keeps pending operations and shows their status on the next visit. */ }
        }
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (consumeAppleCallback(intent)) return
        val raw = intent.data?.toString()
        val destination = gradeyDestination(raw) ?: return
        pendingLaunchLink = raw
        intent.data = null
        val model = androidx.lifecycle.ViewModelProvider(this)[GradeyViewModel::class.java]
        model.openDestination(destination)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        val model = androidx.lifecycle.ViewModelProvider(this)[GradeyViewModel::class.java]
        val state = model.state.value
        outState.putString("gradey.savedTab", state.tab.name)
        if (model.hasPendingDestination()) outState.putString("gradey.pendingLaunchLink", pendingLaunchLink)
        else pendingLaunchLink = null
        super.onSaveInstanceState(outState)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        consumeAppleCallback(intent)
        pendingLaunchLink = savedInstanceState?.getString("gradey.pendingLaunchLink") ?: if (savedInstanceState == null) intent?.data?.toString() else null
        val savedTab = savedInstanceState?.getString("gradey.savedTab")?.let { name -> AppTab.entries.firstOrNull { it.name == name } }
        val destination = gradeyDestination(pendingLaunchLink) ?: AppDestination(savedTab ?: AppTab.TODAY)
        intent?.data = null // Recreating the process must not replay a consumed creation URL over a saved draft.
        setContent {
            GradeyTheme(darkTheme = androidx.compose.foundation.isSystemInDarkTheme()) {
                GradeyApp(initialDestination = destination)
            }
        }
    }

    private fun consumeAppleCallback(intent: android.content.Intent?): Boolean {
        val callback = intent?.data?.toString()
        if (!isAppleSignInCallback(callback)) return false
        intent?.data = null
        androidx.lifecycle.ViewModelProvider(this)[GradeyViewModel::class.java].acceptAppleCallback(requireNotNull(callback))
        return true
    }
}

@Composable
private fun GradeyApp(initialDestination: AppDestination, model: GradeyViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val scope = rememberCoroutineScope()
    val appPreferences = remember { context.getSharedPreferences("gradey-app-preferences", 0) }
    var ageAttestation by rememberSaveable { mutableStateOf(appPreferences.getString("ageAttestation", null)) }
    var acceptedPrivacyRevision by remember { mutableStateOf(appPreferences.getInt("privacyRevision", 0)) }
    val hasCompletedWelcome = acceptedPrivacyRevision >= PrivacyRevision && isValidAgeAttestation(ageAttestation)
    var reviewPrivacyChanges by rememberSaveable { mutableStateOf(false) }
    var showHelp by remember { mutableStateOf(false) }
    var showWatchAccess by remember { mutableStateOf(false) }
    fun acceptPrivacy(age: String) {
        appPreferences.edit().putString("ageAttestation", age).putInt("privacyRevision", PrivacyRevision)
            .putString("privacyAcceptedAt", java.time.Instant.now().toString()).apply()
        ageAttestation = age
        acceptedPrivacyRevision = PrivacyRevision
        reviewPrivacyChanges = false
    }
    var showMealsTab by rememberSaveable { mutableStateOf(appPreferences.getBoolean("showMealsTab", true)) }
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val appLanguage = AppLanguages.current(context)
    var plannerRemindersEnabled by rememberSaveable { mutableStateOf(appPreferences.getBoolean("plannerReminders", false)) }
    val updates = (context.applicationContext as GradeyApplication).updates
    val updateState by updates.state.collectAsStateWithLifecycle()
    var googleLoading by remember { mutableStateOf(false) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val liveSignInAvailable = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() &&
        BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank()
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) scope.launch {
        registerPush(context, model.graph)
        PlannerReminderScheduler.reconcile(context, state, plannerRemindersEnabled)
    } }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val contents = model.graph.accountManagement?.exportAccountData() ?: error(resources.getString(R.string.app_export_unavailable))
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(contents.toByteArray(Charsets.UTF_8)) }
                }
                model.showError(resources.getString(R.string.app_export_success))
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { model.showError(safeError(e, context)) }
        }
    }
    LaunchedEffect(Unit) { model.applyInitialDestination(initialDestination) }
    LaunchedEffect(state.personalPlannerItems, state.school?.cacheScope, state.preferences, state.phase, state.isDemo, state.isGuest, state.account?.id, plannerRemindersEnabled, hasCompletedWelcome) {
        PlannerReminderScheduler.reconcile(context, state, plannerRemindersEnabled && hasCompletedWelcome)
    }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it, actionLabel = resources.getString(R.string.app_dismiss), duration = androidx.compose.material3.SnackbarDuration.Long) }
    }
    LaunchedEffect(state.account?.id, state.isDemo, hasCompletedWelcome) {
        if (state.account != null && !state.isDemo && hasCompletedWelcome) registerPush(context, model.graph)
    }
    LaunchedEffect(hasCompletedWelcome, state.phase) {
        if (hasCompletedWelcome && state.phase == AppPhase.SIGNED_IN) model.refreshCompanionVisibility()
    }
    LaunchedEffect(state.account?.id, state.school?.cacheScope, state.isGuest, state.isDemo, ageAttestation, hasCompletedWelcome) {
        com.bukovinafilip.gradey.feature.support.GradeySupport.reset()
        showHelp = false
        showWatchAccess = false
    }
    androidx.activity.compose.BackHandler(enabled = (state.isGuest && state.phase == AppPhase.SIGNED_OUT) || state.tab == AppTab.UPDATES || (state.phase == AppPhase.SIGNED_IN && state.tab != AppTab.TODAY)) {
        if (state.isGuest && state.phase == AppPhase.SIGNED_OUT) model.continueAsGuest()
        else if (state.tab == AppTab.UPDATES) model.select(AppTab.TODAY) else model.back()
    }
    val timetableTabState = androidx.compose.runtime.saveable.rememberSaveableStateHolder()
    val standard = Modifier.fillMaxSize().statusBarsPadding().padding(bottom = com.bukovinafilip.gradey.ui.floatingNavigationContentPadding())
    Box(Modifier.fillMaxSize().background(androidx.compose.material3.MaterialTheme.colorScheme.background), contentAlignment = Alignment.TopCenter) {
    Box(Modifier.widthIn(max = 840.dp).fillMaxSize()) {
        if (state.tab == AppTab.UPDATES) {
            com.bukovinafilip.gradey.updates.UpdateScreen(
                state = updateState, onCheck = { scope.launch { updates.checkForUpdates() } },
                onDownload = { scope.launch { updates.downloadNow() } },
                onInstall = { scope.launch { updates.install(context) } },
                onCancelDownload = { scope.launch { updates.cancelDownload() } },
                onAutoCheckChange = { scope.launch { updates.setAutomaticChecks(it) } },
                onAutoDownloadChange = { scope.launch { updates.setAutomaticDownloads(it) } },
                onWifiOnlyChange = { scope.launch { updates.setWifiOnly(it) } },
                onBack = { model.select(AppTab.TODAY) }, onOpenStore = { updates.openStore(context) },
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
            )
        } else if (!isValidAgeAttestation(ageAttestation)) {
            WelcomeScreen(onComplete = ::acceptPrivacy)
        } else if (!hasCompletedWelcome || reviewPrivacyChanges) {
            PrivacyUpdateScreen(onAccept = { acceptPrivacy(requireNotNull(ageAttestation)) },
                onClose = if (hasCompletedWelcome) ({ reviewPrivacyChanges = false }) else null)
        } else when (state.phase) {
            AppPhase.CHECKING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            AppPhase.SIGNED_OUT -> GradeyIdLoginScreen(
                onOpenHelp = { showHelp = true },
                onContinueWithoutAccount = model::continueAsGuest,
                appleSignInAvailable = state.appleSignInAvailable,
                onAppleSignIn = {
                    scope.launch {
                        googleLoading = true
                        try { launchAppleSignIn(context, model.beginAppleSignIn()) }
                        catch (error: kotlinx.coroutines.CancellationException) { throw error }
                        catch (error: Exception) {
                            model.cancelAppleSignIn()
                            model.showError(safeError(error, context))
                        } finally { googleLoading = false }
                    }
                },
                isLoading = state.loading || googleLoading,
                onGoogleSignIn = {
                    scope.launch {
                        googleLoading = true
                        try { val identity = googleIdentity(context); model.signIn(identity.idToken, identity.displayName) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: androidx.credentials.exceptions.GetCredentialCancellationException) { }
                        catch (e: Exception) { model.showError(safeError(e, context)) }
                        finally { googleLoading = false }
                    }
                },
                onTestingBypass = { model.demo() },
                onOpenUpdates = { model.select(AppTab.UPDATES) },
                isConfigured = liveSignInAvailable,
                errorMessage = state.error,
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
            )
            AppPhase.NEEDS_SCHOOL -> if (state.loginStep != null) com.bukovinafilip.gradey.feature.login.SchoolVerificationScreen(
                onOpenHelp = { showHelp = true },
                step = state.loginStep!!, isLoading = state.loading, errorMessage = state.error,
                onSubmitCode = { model.submitCode(it) }, onCheckApproval = { model.checkApproval() },
                onResend = { model.resendApproval() }, onSelectStudent = { model.selectStudent(it) }, onBack = model::cancelVerification,
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()),
            ) else SchoolLoginScreen(
                onOpenHelp = { showHelp = true },
                isLoading = state.loading, onLogin = model::login,
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()),
                availableProviders = com.bukovinafilip.gradey.model.SchoolProvider.entries,
                initialProvider = state.reconnectingAccount?.provider,
                initialSchoolURL = state.reconnectingAccount?.baseURL,
                isReconnecting = state.reconnectingAccount != null,
                directorySchools = state.directorySchools, directoryLoading = state.directoryLoading,
                directoryError = state.directoryError, onLoadDirectory = model::loadSchoolDirectory,
                accountPicker = { if (state.reconnectingAccount == null) SchoolAccountSwitcher(state, model::activateAccount, model::reconnectAccount) },
                errorMessage = state.error, onBack = if (state.school != null) model::back else ({ model.signOut() }),
            )
            AppPhase.SIGNED_IN -> {
                when (state.tab) {
                    AppTab.TODAY -> TodayScreen(
                        onOpenPlanner = { model.openPlanner() }, onOpenPlannerItem = { model.openPlanner(itemID = it) },
                        onStudyPrioritiesAI = { model.openAI(AIEntryRequest(com.bukovinafilip.gradey.feature.ai.AIAction.PRIORITIES)) },
                        onWeekSummaryAI = { model.openAI(AIEntryRequest(com.bukovinafilip.gradey.feature.ai.AIAction.WEEK, week = state.currentTimetable?.weekStart)) },
                        insightState = state.insightState, plannerItems = state.personalPlannerItems,
                        schoolScope = state.school?.cacheScope, onOpenAttention = model::openAttention,
                        dashboard = state.dashboard, absence = state.absence, timetable = state.currentTimetable,
                        isRefreshing = state.loading, onRefresh = { model.refresh() },
                        onOpenAccount = { model.select(AppTab.ACCOUNT) }, onOpenGradeyTools = { model.select(AppTab.TOOLS) },
                        onOpenMarks = { model.select(AppTab.SUBJECTS) }, onOpenAbsence = { model.select(AppTab.ABSENCE) },
                        onOpenTimetable = { model.select(AppTab.TIMETABLE) }, modifier = Modifier.fillMaxSize(),
                        plannedAbsences = state.plans, onPlanAbsence = { model.select(AppTab.PLANNER) },
                        mealMenu = state.mealMenu, hasMealAccount = state.hasMealAccount,
                        onOpenMeals = { model.select(AppTab.STRAVACZ) },
                        schoolAccountContent = { SchoolAccountSwitcher(state, model::activateAccount, model::reconnectAccount) },
                    )
                    AppTab.SUBJECTS -> SubjectsScreen(
                        insightState = state.insightState, plannerItems = state.personalPlannerItems, schoolScope = state.school?.cacheScope,
                        onAskTestAI = { subjectID, eventID -> model.openAI(AIEntryRequest(com.bukovinafilip.gradey.feature.ai.AIAction.TEST, subjectID = subjectID, eventID = eventID)) },
                        initialSubjectID = state.focusedSubjectID,
                        onAskAI = { model.openAI(AIEntryRequest(com.bukovinafilip.gradey.feature.ai.AIAction.SUBJECT, subjectID = it)) },
                        subjects = state.dashboard.marksResponse.subjects, absence = state.absence, gradeTrends = state.history,
                        predictSubjectAverage = model.graph.schoolRepository::predictSubjectAverage,
                        isRefreshing = state.loading, onRefresh = { model.refresh() },
                        onOpenAccount = { model.select(AppTab.ACCOUNT) }, onOpenGradeyTools = { model.select(AppTab.TOOLS) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppTab.ABSENCE -> AbsenceScreen(
                        rawResponse = state.rawAbsence, overrideScope = state.absenceOverrideScope, overrides = state.absenceOverrides,
                        availableSubjects = state.dashboard.marksResponse.subjects, correctionTimetable = state.correctionTimetable,
                        correctionError = state.correctionError, onSaveOverride = model::saveAbsenceOverride,
                        onRestoreOverride = model::restoreAbsenceOverride, onResetOverrides = model::resetAbsenceOverrides,
                        onLoadCorrectionWeek = model::loadCorrectionWeek,
                        response = state.absence, studentName = state.dashboard.user?.fullName ?: stringResource(R.string.app_student),
                        isRefreshing = state.loading, onRefresh = { model.refresh() },
                        onOpenAccount = { model.select(AppTab.ACCOUNT) }, onOpenGradeyTools = { model.select(AppTab.TOOLS) },
                        modifier = Modifier.fillMaxSize(), onPlanAbsence = { model.select(AppTab.PLANNER) },
                    )
                    AppTab.TIMETABLE -> timetableTabState.SaveableStateProvider(
                        "timetable:${state.account?.id}:${state.school?.cacheScope}:${state.viewedWeek}:${state.timetableKind}",
                    ) {
                        TimetableScreen(
                            plannerItems = state.personalPlannerItems, schoolScope = state.school?.cacheScope,
                            onOpenPlannerItem = { model.openPlanner(itemID = it) },
                            errorMessage = if (state.timetableKind == com.bukovinafilip.gradey.model.TimetableKind.PERMANENT) state.permanentTimetableError else null,
                            kind = state.timetableKind,
                            onChangeKind = if (state.school?.provider == com.bukovinafilip.gradey.model.SchoolProvider.BAKALARI) model::changeTimetableKind else null,
                            onAskWeekAI = { model.openAI(AIEntryRequest(com.bukovinafilip.gradey.feature.ai.AIAction.WEEK, week = it)) },
                            onOpenPlanner = { model.openPlanner() },
                            onPlanDay = { model.openPlanner(day = it.date?.take(10)) },
                            onPlanLesson = { lesson, day -> state.school?.cacheScope?.let { model.openPlanner(item = com.bukovinafilip.gradey.domain.PersonalPlanner.linked(lesson, day, it)) } },
                            week = if (state.timetableKind == com.bukovinafilip.gradey.model.TimetableKind.PERMANENT) state.permanentTimetable else state.timetable,
                            isRefreshing = if (state.timetableKind == com.bukovinafilip.gradey.model.TimetableKind.PERMANENT) state.permanentTimetableLoading else state.loading,
                            requestedWeek = state.viewedWeek,
                            onRefresh = { if (state.timetableKind == com.bukovinafilip.gradey.model.TimetableKind.PERMANENT) model.refreshPermanentTimetable() else model.changeWeek(state.viewedWeek) },
                            onChangeWeek = { model.changeWeek(it) },
                            onOpenAccount = { model.select(AppTab.ACCOUNT) }, onOpenGradeyTools = { model.select(AppTab.TOOLS) },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    AppTab.PLANNER -> com.bukovinafilip.gradey.feature.absence.AbsencePlannerScreen(
                        response = state.absence, timetable = state.timetable, plans = state.plans,
                        isRefreshing = state.loading, onRefresh = { model.changeWeek(state.viewedWeek) },
                        onClose = { model.select(AppTab.TODAY) }, onWeekChange = { model.changeWeek(it.toString()) },
                        onSavePlan = model::savePlan, onRemovePlan = model::removePlan, modifier = Modifier.fillMaxSize(), requestedWeek = state.viewedWeek,
                    )
                    AppTab.STRAVACZ -> com.bukovinafilip.gradey.feature.stravacz.StravaCZRoute(model.graph.stravaCZRepository, standard, isDemo = state.isDemo)
                    AppTab.ACCOUNT -> AccountScreen(
                        onOpenWatch = { showWatchAccess = true },
                        ageAttestation = ageAttestation, onOpenPrivacyChanges = { reviewPrivacyChanges = true },
                        canteenAccounts = state.canteenAccounts, canteenSession = state.canteenSession,
                        currentCanteenLinkID = state.currentCanteenLinkID, onLinkCanteen = model::linkCanteen,
                        onUnlinkCanteen = model::unlinkCanteen, onOpenMeals = { model.select(AppTab.STRAVACZ) },
                        isGuest = state.isGuest, onGuestSignIn = model::requestGradeySignIn,
                        localSchoolName = state.school?.linkedAccountSchoolName ?: state.dashboard.user?.schoolName ?: state.school?.provider?.displayName,
                        account = state.account, linkedAccounts = state.linkedAccounts, onSignOut = { model.signOut() },
                        modifier = standard, preferences = state.preferences, onPreferencesChange = { model.preferences(it) },
                        students = state.school?.eduPage?.linkedStudents.orEmpty(), activeStudentID = state.school?.eduPage?.activeStudent?.id,
                        onSelectStudent = { model.switchStudent(it) },
                        activeAccountID = state.school?.linkedAccountID, onActivateAccount = { model.activateAccount(it.id) },
                        onUnlinkAccount = { model.unlinkAccount(it.id) }, onAddAccount = model::addAccount,
                        isLoading = state.loading, errorMessage = state.error, onRetry = { model.refresh() }, isDemo = state.isDemo,
                        liveSignInAvailable = liveSignInAvailable || state.appleSignInAvailable,
                        versionName = BuildConfig.VERSION_NAME,
                        onOpenUpdates = { model.select(AppTab.UPDATES) },
                        onExportData = if (state.isDemo) null else ({ exportLauncher.launch("gradey-account-data.json") }),
                        onDeleteAccount = if (state.isDemo) null else ({ model.deleteAccount() }),
                        plannerRemindersEnabled = plannerRemindersEnabled,
                        onPlannerRemindersChange = { enabled ->
                            appPreferences.edit().putBoolean("plannerReminders", enabled).apply()
                            plannerRemindersEnabled = enabled
                            if (enabled && android.os.Build.VERSION.SDK_INT >= 33 && !canNotify(context)) notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                        },
                        onUpdateProfile = model::updateProfile, onReconnectAccount = model::reconnectAccount,
                        onAccountNotificationsChange = model::accountNotifications,
                        showMealsTab = showMealsTab,
                        onShowMealsTabChange = { showMealsTab = it; appPreferences.edit().putBoolean("showMealsTab", it).apply() },
                        appLanguage = appLanguage,
                        onLanguageChange = { AppLanguages.select(context as MainActivity, it) },
                        onOpenPrivacy = { uriHandler.openUri(PrivacyUrl) }, onOpenTerms = { uriHandler.openUri(TermsUrl) },
                        onOpenHelp = { showHelp = true },
                        onNotificationSettings = {
                            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                        },
                    )
                    AppTab.PERSONAL_PLANNER -> com.bukovinafilip.gradey.feature.planner.PlannerRoute(
                        calendarMirror = if (state.isDemo) null else (context.applicationContext as GradeyApplication).plannerCalendarMirror,
                        onAskTestAI = { model.openAI(AIEntryRequest(com.bukovinafilip.gradey.feature.ai.AIAction.TEST, eventID = it)) },
                        repository = model.plannerRepository,
                        subjects = state.school?.cacheScope?.let { schoolScope -> state.dashboard.marksResponse.subjects.map {
                            com.bukovinafilip.gradey.model.PlannerSubjectReference(schoolScope, it.subjectInfo.id, it.subjectInfo.name, it.subjectInfo.abbrev)
                        } }.orEmpty(),
                        initialItem = state.plannerDraft, initialItemID = state.plannerItemID, focusedDay = state.plannerDay,
                        onInitialItemHandled = model::plannerEntryHandled,
                        onFindNextLesson = model::findNextPlannerLesson, onItemsChanged = model::plannerChanged,
                        onBack = { model.select(AppTab.TIMETABLE) }, modifier = standard,
                    )
                    AppTab.HISTORY -> HistoryScreen(state, { model.select(AppTab.TOOLS) }, standard)
                    AppTab.SUPPORT -> SupportScreen(state.account?.id, state.isDemo, standard)
                    AppTab.AI -> if (state.isGuest) GuestCloudGate(model::requestGradeySignIn, standard)
                    else com.bukovinafilip.gradey.feature.ai.GradeyAIRoute(
                        model.graph.schoolRepository, standard, gradeyAccountID = state.account?.id, isDemo = state.isDemo,
                        historyRepository = model.graph.historyRepository, schoolAccountKey = state.school?.cacheScope,
                        gradeyAuthRepository = model.graph.gradeyAuthRepository, plannerItems = state.personalPlannerItems,
                        insightStore = model.graph.schoolInsightStore,
                        initialAction = state.aiEntry?.action, initialSubjectID = state.aiEntry?.subjectID,
                        initialEventID = state.aiEntry?.eventID, initialWeek = state.aiEntry?.week, initialRequestKey = state.aiEntry?.key,
                    )
                    AppTab.TOOLS -> ToolsScreen(state.isDemo, model::select, standard)
                    AppTab.UPDATES -> Unit // Available above, including before sign-in.
                }
                if (state.tab != AppTab.PLANNER) GradeyBottomNavigation(state.tab, model::select, Modifier.align(Alignment.BottomCenter), showMealsTab)
            }
        }
        if (updateState.phase == com.bukovinafilip.gradey.updates.UpdatePhase.READY && state.tab != AppTab.UPDATES) {
            Surface(
                onClick = { model.select(AppTab.UPDATES) }, shape = RoundedCornerShape(16.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp,
                    bottom = if (state.phase == AppPhase.SIGNED_IN) 112.dp else 32.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                    Text(stringResource(R.string.app_update_banner, updateState.release?.versionName.orEmpty()), modifier = Modifier.weight(1f))
                }
            }
        }
        if (showHelp) com.bukovinafilip.gradey.feature.support.SupportHelpDialog(
            onDismiss = { showHelp = false }, onOpenHelpCenter = { uriHandler.openUri(HelpUrl) },
            allowed = hasCompletedWelcome && isValidAgeAttestation(ageAttestation), isDemo = state.isDemo,
            sessionKey = "${state.account?.id ?: "guest-local"}:${state.school?.cacheScope}",
        )
        if (showWatchAccess) WatchAccessDialog(
            onDismiss = { showWatchAccess = false }, isDemo = state.isDemo,
            onAccessChanged = model::refreshCompanionVisibility,
        )
        if (hasCompletedWelcome && !reviewPrivacyChanges && state.phase == AppPhase.SIGNED_IN && state.tab !in listOf(AppTab.ACCOUNT, AppTab.UPDATES)) androidx.compose.material3.SnackbarHost(
            snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp, start = 12.dp, end = 12.dp),
        )
    }
    }
}

@Composable
private fun ToolsScreen(isDemo: Boolean, onSelect: (AppTab) -> Unit, modifier: Modifier) {
    com.bukovinafilip.gradey.ui.GradeyScreen(modifier.verticalScroll(rememberScrollState())) {
        com.bukovinafilip.gradey.ui.GradeyHero(stringResource(R.string.app_tools), if (isDemo) stringResource(R.string.app_tools_demo) else stringResource(R.string.app_tools_subtitle))
        listOf(
            Triple(AppTab.AI, stringResource(R.string.app_tools_ai), Icons.Default.AutoAwesome),
            Triple(AppTab.PERSONAL_PLANNER, stringResource(R.string.app_tools_planner), Icons.Default.CalendarMonth),
            Triple(AppTab.PLANNER, stringResource(R.string.app_tools_absence), Icons.Default.CalendarMonth),
            Triple(AppTab.HISTORY, stringResource(R.string.app_tools_history), Icons.Default.History),
            Triple(AppTab.STRAVACZ, stringResource(R.string.app_tools_meals), Icons.Default.Restaurant),
            Triple(AppTab.ACCOUNT, stringResource(R.string.app_tools_account), Icons.Default.Person),
            Triple(AppTab.SUPPORT, stringResource(R.string.app_tools_support), Icons.Default.Favorite),
            Triple(AppTab.UPDATES, stringResource(R.string.app_tools_updates), Icons.Default.SystemUpdate),
        ).forEach { (tab, detail, icon) ->
            Surface(
                onClick = { onSelect(tab) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(22.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer,
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp), tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(tab.labelRes), style = androidx.compose.material3.MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(detail, style = androidx.compose.material3.MaterialTheme.typography.bodyMedium, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, contentDescription = null, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

@Composable
private fun HistoryScreen(state: GradeyState, onBack: () -> Unit, modifier: Modifier) {
    com.bukovinafilip.gradey.ui.GradeyScreen(modifier.verticalScroll(rememberScrollState())) {
        com.bukovinafilip.gradey.ui.GradeyHero(stringResource(R.string.app_history), stringResource(R.string.app_history_subtitle))
        androidx.compose.material3.TextButton(onClick = onBack) { Text(stringResource(R.string.app_back_tools)) }
        if (state.loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.history.isEmpty() && !state.loading) Text(stringResource(R.string.app_history_empty))
        state.history.forEach { trend ->
            com.bukovinafilip.gradey.ui.GradeySectionCard(title = trend.subjectName) {
                Text(stringResource(R.string.app_average_change, String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%+.2f", trend.averageDelta)))
                Text(when {
                    trend.markCountDelta > 0 -> stringResource(R.string.app_marks_added, trend.markCountDelta)
                    trend.markCountDelta < 0 -> stringResource(R.string.app_marks_removed, kotlin.math.abs(trend.markCountDelta))
                    else -> stringResource(R.string.app_marks_unchanged)
                })
            }
        }
    }
}

private val CoreTabs = listOf(AppTab.TODAY, AppTab.SUBJECTS, AppTab.ABSENCE, AppTab.TIMETABLE)
private val MarksTabs = CoreTabs + AppTab.STRAVACZ

@Composable
private fun AppTab.icon() = when (this) {
    AppTab.TODAY -> Icons.Default.LightMode
    AppTab.SUBJECTS -> Icons.Default.Verified
    AppTab.ABSENCE -> Icons.Default.CalendarMonth
    AppTab.TIMETABLE -> Icons.Default.CalendarMonth
    AppTab.STRAVACZ -> Icons.Default.Restaurant
    AppTab.ACCOUNT -> Icons.Default.Person
    AppTab.PLANNER, AppTab.PERSONAL_PLANNER -> Icons.Default.CalendarMonth
    AppTab.HISTORY -> Icons.Default.Verified
    AppTab.TOOLS -> Icons.Default.LightMode
    AppTab.AI -> Icons.Default.LightMode
    AppTab.SUPPORT -> Icons.Default.Verified
    AppTab.UPDATES -> Icons.Default.SystemUpdate
}

@Composable
private fun GradeyBottomNavigation(
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    showMealsTab: Boolean = true,
) {
    val visibleTabs = if (showMealsTab) MarksTabs else CoreTabs
    val barHeight = bottomNavigationHeight()
    Box(
        modifier = modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 21.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(barHeight),
            shape = RoundedCornerShape(31.dp),
            color = androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.98f),
            shadowElevation = 8.dp,
        ) {
            Row(
                modifier = Modifier.padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleTabs.forEach { tab ->
                    BottomNavigationItem(
                        tab = tab,
                        selected = selectedTab == tab,
                        onClick = { onSelect(tab) },
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.BottomNavigationItem(
    tab: AppTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val itemHeight = bottomNavigationHeight() - 8.dp
    val foreground = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.primary else androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(itemHeight),
        onClick = onClick,
        shape = RoundedCornerShape(27.dp),
        color = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(26.dp),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = tab.icon(),
                    contentDescription = stringResource(tab.labelRes),
                    tint = foreground,
                    modifier = Modifier.size(24.dp),
                )
                if (tab == AppTab.ABSENCE) {
                    Icon(
                        imageVector = Icons.Default.Error,
                        contentDescription = null,
                        tint = foreground,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(11.dp),
                    )
                }
            }
            Text(
                text = stringResource(tab.labelRes).replace("Timetable", "Time\u00adtable").replace("Absence", "Ab\u00adsence").replace("Docházka", "Do\u00adcház\u00adka").replace("Rozvrh", "Roz\u00advrh"),
                color = foreground,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                style = androidx.compose.ui.text.TextStyle(hyphens = androidx.compose.ui.text.style.Hyphens.Auto),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun bottomNavigationHeight() = with(androidx.compose.ui.platform.LocalDensity.current) {
    // Android's non-linear font scaling must be evaluated at the label's actual line size.
    if (fontScale > 1.15f) 48.dp + 14.sp.toDp() * 3 else maxOf(62.dp, 34.dp + 14.sp.toDp() * 2)
}
