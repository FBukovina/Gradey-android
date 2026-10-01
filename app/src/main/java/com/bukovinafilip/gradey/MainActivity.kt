package com.bukovinafilip.gradey

import androidx.compose.ui.res.stringResource
import com.bukovinafilip.gradey.R
import android.os.Bundle
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.gradeyColors
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
    com.bukovinafilip.gradey.ui.AuroraBackground(Modifier.fillMaxSize()) {
    Box(Modifier.widthIn(max = 840.dp).fillMaxSize().align(Alignment.TopCenter)) {
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
                        onOpenMarks = { model.select(AppTab.SUBJECTS) }, onOpenSubject = model::openSubject, onOpenAbsence = { model.select(AppTab.ABSENCE) },
                        onOpenTimetable = { model.select(AppTab.TIMETABLE) }, modifier = Modifier.fillMaxSize(),
                        mealMenu = state.mealMenu, hasMealAccount = state.hasMealAccount && showMealsTab,
                        onOpenMeals = { model.select(AppTab.STRAVACZ) },
                        linkedAccounts = state.linkedAccounts, activeAccountID = state.school?.linkedAccountID,
                        onSelectAccount = { account -> if (account.status == "active") model.activateAccount(account.id) else model.reconnectAccount(account) },
                        hasLocalAbsenceAdjustments = state.absenceOverrides.isNotEmpty(),
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
                        modifier = Modifier.fillMaxSize(), preferences = state.preferences, onPreferencesChange = { model.preferences(it) },
                        onClose = model::back, onOpenSupport = { model.select(AppTab.SUPPORT) },
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
                if (state.tab != AppTab.PLANNER && state.tab != AppTab.ACCOUNT) GradeyBottomNavigation(state.tab, model::select, Modifier.align(Alignment.BottomCenter), showMealsTab)
            }
        }
        if (updateState.phase == com.bukovinafilip.gradey.updates.UpdatePhase.READY && state.tab != AppTab.UPDATES) {
            Surface(
                onClick = { model.select(AppTab.UPDATES) }, shape = RoundedCornerShape(16.dp),
                color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
                border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.material3.MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)),
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp,
                    bottom = if (state.phase == AppPhase.SIGNED_IN) 112.dp else 32.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    GradeyIcon(HugeIcons.Download04, size = 20.dp, tint = androidx.compose.material3.MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.app_update_banner, updateState.release?.versionName.orEmpty()), modifier = Modifier.weight(1f), style = com.bukovinafilip.gradey.ui.GradeyType.subheadlineBold)
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
        com.bukovinafilip.gradey.ui.GradeyCard(padding = androidx.compose.foundation.layout.PaddingValues(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(0.dp)) {
            val entries = listOf(
                Triple(AppTab.AI, stringResource(R.string.app_tools_ai), HugeIcons.Sparkles),
                Triple(AppTab.PERSONAL_PLANNER, stringResource(R.string.app_tools_planner), HugeIcons.CheckList),
                Triple(AppTab.PLANNER, stringResource(R.string.app_tools_absence), HugeIcons.CalendarRemove02),
                Triple(AppTab.HISTORY, stringResource(R.string.app_tools_history), HugeIcons.ChartLineData01),
                Triple(AppTab.STRAVACZ, stringResource(R.string.app_tools_meals), HugeIcons.Restaurant02),
                Triple(AppTab.ACCOUNT, stringResource(R.string.app_tools_account), HugeIcons.Settings01),
                Triple(AppTab.SUPPORT, stringResource(R.string.app_tools_support), HugeIcons.Favourite),
                Triple(AppTab.UPDATES, stringResource(R.string.app_tools_updates), HugeIcons.Download04),
            )
            entries.forEachIndexed { index, (tab, detail, icon) ->
                if (index > 0) com.bukovinafilip.gradey.ui.SettingsRowDivider(leadingInset = 16.dp + 34.dp + 12.dp)
                com.bukovinafilip.gradey.ui.NavigationRow(icon, stringResource(tab.labelRes), detail, onClick = { onSelect(tab) }, modifier = Modifier.padding(horizontal = 16.dp))
            }
        }
    }
}

@Composable
private fun HistoryScreen(state: GradeyState, onBack: () -> Unit, modifier: Modifier) {
    com.bukovinafilip.gradey.ui.GradeyScreen(modifier.verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            com.bukovinafilip.gradey.ui.GradeyIconButton(HugeIcons.ArrowLeft01, stringResource(R.string.app_back_tools), onBack, size = 44.dp, iconSize = 18.dp, tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurface)
            com.bukovinafilip.gradey.ui.GradeyHero(stringResource(R.string.app_history), stringResource(R.string.app_history_subtitle), Modifier.weight(1f))
        }
        if (state.loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.history.isEmpty() && !state.loading) com.bukovinafilip.gradey.ui.GradeyCard {
            com.bukovinafilip.gradey.ui.InfoRow(HugeIcons.ChartLineData01, stringResource(R.string.app_history), stringResource(R.string.app_history_empty), tint = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.history.forEach { trend ->
            com.bukovinafilip.gradey.ui.GradeyCard {
                Text(trend.subjectName, style = com.bukovinafilip.gradey.ui.GradeyType.headline)
                com.bukovinafilip.gradey.ui.MetadataRow(stringResource(R.string.app_average_change, ""), String.format(androidx.compose.ui.platform.LocalConfiguration.current.locales[0], "%+.2f", trend.averageDelta))
                Text(when {
                    trend.markCountDelta > 0 -> stringResource(R.string.app_marks_added, trend.markCountDelta)
                    trend.markCountDelta < 0 -> stringResource(R.string.app_marks_removed, kotlin.math.abs(trend.markCountDelta))
                    else -> stringResource(R.string.app_marks_unchanged)
                }, style = com.bukovinafilip.gradey.ui.GradeyType.footnote, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val CoreTabs = listOf(AppTab.TODAY, AppTab.SUBJECTS, AppTab.ABSENCE, AppTab.TIMETABLE)
private val MarksTabs = CoreTabs + AppTab.STRAVACZ

/** Tab glyphs match the iOS asset catalog: sun, checkmark badge, calendar-remove, calendar, restaurant. */
private fun AppTab.icon(): String = when (this) {
    AppTab.TODAY -> HugeIcons.Sun03
    AppTab.SUBJECTS -> HugeIcons.CheckmarkBadge02
    AppTab.ABSENCE -> HugeIcons.CalendarRemove02
    AppTab.TIMETABLE -> HugeIcons.Calendar03
    AppTab.STRAVACZ -> HugeIcons.Restaurant02
    AppTab.ACCOUNT -> HugeIcons.Settings01
    AppTab.PLANNER -> HugeIcons.CalendarRemove02
    AppTab.PERSONAL_PLANNER -> HugeIcons.CheckList
    AppTab.HISTORY -> HugeIcons.ChartLineData01
    AppTab.TOOLS, AppTab.AI -> HugeIcons.Sparkles
    AppTab.SUPPORT -> HugeIcons.Favourite
    AppTab.UPDATES -> HugeIcons.Download04
}

@Composable
private fun GradeyBottomNavigation(
    selectedTab: AppTab,
    onSelect: (AppTab) -> Unit,
    modifier: Modifier = Modifier,
    showMealsTab: Boolean = true,
) {
    val visibleTabs = if (showMealsTab) MarksTabs else CoreTabs
    val barHeight = com.bukovinafilip.gradey.ui.floatingNavigationBarHeight()
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    Box(
        modifier = modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp)
            .padding(bottom = com.bukovinafilip.gradey.ui.FloatingNavigationBottomMargin),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth().height(barHeight),
            shape = RoundedCornerShape(barHeight / 2),
            color = colors.surface.copy(alpha = 0.96f),
            border = androidx.compose.foundation.BorderStroke(1.dp, colors.onSurface.copy(alpha = 0.08f)),
            shadowElevation = 12.dp,
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                visibleTabs.forEach { tab ->
                    BottomNavigationItem(tab = tab, selected = selectedTab == tab, onClick = { onSelect(tab) })
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
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val foreground = if (selected) colors.primary else colors.onSurface
    val isDark = androidx.compose.material3.MaterialTheme.gradeyColors.isDark
    Surface(
        modifier = Modifier.weight(1f).fillMaxHeight().semantics { this.selected = selected; role = androidx.compose.ui.semantics.Role.Tab },
        onClick = onClick,
        shape = RoundedCornerShape(100),
        color = if (!selected) Color.Transparent else if (isDark) Color.Black.copy(alpha = 0.55f) else colors.primary.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            GradeyIcon(tab.icon(), size = 22.dp, tint = foreground, contentDescription = stringResource(tab.labelRes))
            Text(
                text = stringResource(tab.labelRes).replace("Timetable", "Time\u00adtable").replace("Absence", "Ab\u00adsence").replace("Docházka", "Do\u00adcház\u00adka").replace("Rozvrh", "Roz\u00advrh"),
                color = foreground,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                style = androidx.compose.ui.text.TextStyle(hyphens = androidx.compose.ui.text.style.Hyphens.Auto),
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}
