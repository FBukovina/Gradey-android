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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
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
import com.bukovinafilip.gradey.feature.subjects.MarksReportCardRow
import com.bukovinafilip.gradey.feature.subjects.ReportCardScreen
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
    androidx.activity.compose.BackHandler(
        enabled = (state.isGuest && state.phase == AppPhase.SIGNED_OUT) || state.tab == AppTab.UPDATES ||
            (state.phase == AppPhase.SIGNED_IN && (state.tab != AppTab.TODAY || state.backStack.isNotEmpty())),
    ) {
        if (state.isGuest && state.phase == AppPhase.SIGNED_OUT) model.continueAsGuest() else model.back()
    }
    val openAI = { model.openAI() }
    val openSettings = { model.select(AppTab.ACCOUNT) }
    val updateScreen: @Composable () -> Unit = {
        com.bukovinafilip.gradey.updates.UpdateScreen(
            state = updateState, onCheck = { scope.launch { updates.checkForUpdates() } },
            onDownload = { scope.launch { updates.downloadNow() } },
            onInstall = { scope.launch { updates.install(context) } },
            onCancelDownload = { scope.launch { updates.cancelDownload() } },
            onAutoCheckChange = { scope.launch { updates.setAutomaticChecks(it) } },
            onAutoDownloadChange = { scope.launch { updates.setAutomaticDownloads(it) } },
            onWifiOnlyChange = { scope.launch { updates.setWifiOnly(it) } },
            onBack = model::back, onOpenStore = { updates.openStore(context) },
            modifier = Modifier.fillMaxSize(),
        )
    }
    val signedIn = state.phase == AppPhase.SIGNED_IN && hasCompletedWelcome && !reviewPrivacyChanges
    val updateReady = updateState.phase == com.bukovinafilip.gradey.updates.UpdatePhase.READY && state.tab != AppTab.UPDATES
    Box(Modifier.fillMaxSize()) {
        if (signedIn) com.bukovinafilip.gradey.ui.AuroraBackground(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    DestinationHost(state.shellDestination(), scopeKey = "${state.account?.id}:${state.school?.cacheScope}") { tab ->
                        when (tab) {
                            AppTab.TODAY -> TodayScreen(
                                dashboard = state.dashboard, timetable = state.currentTimetable,
                                isRefreshing = state.loading, onRefresh = model::refresh, onOpenAI = openAI, onOpenSettings = openSettings,
                                onOpenMarks = { model.select(AppTab.SUBJECTS) }, onOpenTimetable = { model.select(AppTab.TIMETABLE) },
                                onOpenSubject = model::openSubject,
                                mealMenu = state.mealMenu, hasMealAccount = state.hasMealAccount && showMealsTab, onOpenMeals = { model.select(AppTab.STRAVACZ) },
                                linkedAccounts = state.linkedAccounts, activeAccountID = state.school?.linkedAccountID, onReconnectAccount = model::reconnectAccount,
                                insightState = state.insightState, plannerItems = state.personalPlannerItems, schoolScope = state.school?.cacheScope,
                                onOpenPlanner = { model.openPlanner() }, onOpenPlannerItem = { model.openPlanner(itemID = it) },
                            )
                            AppTab.SUBJECTS -> SubjectsScreen(
                                subjects = state.dashboard.marksResponse.subjects, absence = state.absence,
                                isRefreshing = state.loading, onRefresh = model::refresh, onOpenAI = openAI, onOpenSettings = openSettings,
                                onAskAI = { model.openAI(AIEntryRequest(com.bukovinafilip.gradey.feature.ai.AIAction.SUBJECT, subjectID = it)) },
                                initialSubjectID = state.focusedSubjectID, onInitialSubjectHandled = model::subjectHandled,
                                insightState = state.insightState, plannerItems = state.personalPlannerItems, schoolScope = state.school?.cacheScope,
                                absenceOverrides = state.absenceOverrides, errorMessage = state.error,
                                // Report cards exist only in Bakaláři (iOS `supportsReportCards`).
                                reportCardRow = if (state.school?.provider == com.bukovinafilip.gradey.model.SchoolProvider.BAKALARI) {
                                    { MarksReportCardRow(state.dashboard.marksResponse.subjects, onClick = model::openReportCard) }
                                } else null,
                            )
                            AppTab.ABSENCE -> AbsenceScreen(
                                response = state.absence, isRefreshing = state.loading, onRefresh = model::refresh, onOpenAI = openAI, onOpenSettings = openSettings,
                                onPlanAbsence = { model.select(AppTab.PLANNER) },
                                rawResponse = state.rawAbsence, overrideScope = state.absenceOverrideScope, overrides = state.absenceOverrides,
                                availableSubjects = state.dashboard.marksResponse.subjects, correctionTimetable = state.correctionTimetable,
                                correctionError = state.correctionError, onSaveOverride = model::saveAbsenceOverride,
                                onRestoreOverride = model::restoreAbsenceOverride, onResetOverrides = model::resetAbsenceOverrides,
                                onLoadCorrectionWeek = model::loadCorrectionWeek,
                            )
                            AppTab.TIMETABLE -> {
                                val permanent = state.timetableKind == com.bukovinafilip.gradey.model.TimetableKind.PERMANENT
                                TimetableScreen(
                                    week = if (permanent) state.permanentTimetable else state.timetable,
                                    isRefreshing = if (permanent) state.permanentTimetableLoading else state.loading,
                                    onRefresh = { if (permanent) model.refreshPermanentTimetable() else model.changeWeek(state.viewedWeek) },
                                    onChangeWeek = { model.changeWeek(it) }, onOpenAI = openAI, onOpenSettings = openSettings,
                                    requestedWeek = state.viewedWeek, onOpenPlanner = { model.openPlanner() },
                                    onPlanLesson = { lesson, day -> state.school?.cacheScope?.let { model.openPlanner(item = com.bukovinafilip.gradey.domain.PersonalPlanner.linked(lesson, day, it)) } },
                                    kind = state.timetableKind,
                                    onChangeKind = if (state.school?.provider == com.bukovinafilip.gradey.model.SchoolProvider.BAKALARI) model::changeTimetableKind else null,
                                    errorMessage = if (permanent) state.permanentTimetableError else null,
                                    plannerItems = state.personalPlannerItems, schoolScope = state.school?.cacheScope,
                                    onOpenPlannerItem = { model.openPlanner(itemID = it) },
                                )
                            }
                            AppTab.STRAVACZ -> com.bukovinafilip.gradey.feature.stravacz.StravaCZRoute(
                                model.graph.stravaCZRepository, onOpenAI = openAI, onOpenSettings = openSettings, isDemo = state.isDemo,
                            )
                            AppTab.PLANNER -> com.bukovinafilip.gradey.feature.absence.AbsencePlannerScreen(
                                response = state.absence, timetable = state.timetable, plans = state.plans,
                                isRefreshing = state.loading, onRefresh = { model.changeWeek(state.viewedWeek) },
                                onClose = model::back, onWeekChange = { model.changeWeek(it.toString()) },
                                onSavePlan = model::savePlan, onRemovePlan = model::removePlan, requestedWeek = state.viewedWeek,
                            )
                            AppTab.PERSONAL_PLANNER -> com.bukovinafilip.gradey.feature.planner.PlannerRoute(
                                repository = model.plannerRepository, onBack = model::back,
                                subjects = state.school?.cacheScope?.let { schoolScope -> state.dashboard.marksResponse.subjects.map {
                                    com.bukovinafilip.gradey.model.PlannerSubjectReference(schoolScope, it.subjectInfo.id, it.subjectInfo.name, it.subjectInfo.abbrev)
                                } }.orEmpty(),
                                initialItem = state.plannerDraft, initialItemID = state.plannerItemID, focusedDay = state.plannerDay,
                                onFindNextLesson = model::findNextPlannerLesson, onItemsChanged = model::plannerChanged,
                                onAskTestAI = { model.openAI(AIEntryRequest(com.bukovinafilip.gradey.feature.ai.AIAction.TEST, eventID = it)) },
                                calendarMirror = if (state.isDemo) null else (context.applicationContext as GradeyApplication).plannerCalendarMirror,
                                onInitialItemHandled = model::plannerEntryHandled,
                            )
                            AppTab.REPORT_CARD -> ReportCardScreen(
                                subjects = state.dashboard.marksResponse.subjects,
                                loadCached = model.graph.schoolRepository::loadCachedReportCards,
                                load = model.graph.schoolRepository::loadReportCards, onBack = model::back,
                            )
                            AppTab.RECAP_LIST, AppTab.RECAP_STORY -> LaunchedEffect(tab) { model.back() }
                            AppTab.ACCOUNT -> AccountScreen(
                                onOpenWatch = { showWatchAccess = true },
                                ageAttestation = ageAttestation, onOpenPrivacyChanges = { reviewPrivacyChanges = true },
                                canteenAccounts = state.canteenAccounts, canteenSession = state.canteenSession,
                                currentCanteenLinkID = state.currentCanteenLinkID, onLinkCanteen = model::linkCanteen,
                                onUnlinkCanteen = model::unlinkCanteen, onOpenMeals = { model.select(AppTab.STRAVACZ) },
                                isGuest = state.isGuest, onGuestSignIn = model::requestGradeySignIn,
                                localSchoolName = state.school?.linkedAccountSchoolName ?: state.dashboard.user?.schoolName ?: state.school?.provider?.displayName,
                                account = state.account, linkedAccounts = state.linkedAccounts, onSignOut = { model.signOut() },
                                preferences = state.preferences, onPreferencesChange = { model.preferences(it) },
                                onClose = model::back, onOpenSupport = { model.select(AppTab.SUPPORT) },
                                students = state.school?.eduPage?.linkedStudents.orEmpty(), activeStudentID = state.school?.eduPage?.activeStudent?.id,
                                onSelectStudent = { model.switchStudent(it) },
                                activeAccountID = state.school?.linkedAccountID, onActivateAccount = { model.activateAccount(it.id) },
                                onUnlinkAccount = { model.unlinkAccount(it.id) }, onAddAccount = model::addAccount,
                                isLoading = state.loading, errorMessage = state.error, onRetry = { model.refresh() }, isDemo = state.isDemo,
                                liveSignInAvailable = liveSignInAvailable || state.appleSignInAvailable,
                                versionName = BuildConfig.VERSION_NAME, versionCode = BuildConfig.VERSION_CODE,
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
                            AppTab.SUPPORT -> SupportScreen(state.account?.id, state.isDemo, onBack = model::back)
                            AppTab.AI -> if (state.isGuest) GuestCloudGate(model::requestGradeySignIn, onClose = model::back)
                            else com.bukovinafilip.gradey.feature.ai.GradeyAIRoute(
                                model.graph.schoolRepository, onClose = model::back, gradeyAccountID = state.account?.id, isDemo = state.isDemo,
                                historyRepository = model.graph.historyRepository, schoolAccountKey = state.school?.cacheScope,
                                gradeyAuthRepository = model.graph.gradeyAuthRepository, plannerItems = state.personalPlannerItems,
                                insightStore = model.graph.schoolInsightStore,
                                initialAction = state.aiEntry?.action, initialSubjectID = state.aiEntry?.subjectID,
                                initialEventID = state.aiEntry?.eventID, initialWeek = state.aiEntry?.week, initialRequestKey = state.aiEntry?.key,
                                onUpgrade = { model.select(AppTab.SUPPORT) },
                            )
                            AppTab.UPDATES -> updateScreen()
                        }
                    }
                    Column(
                        Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                            .then(if (state.tab.kind == DestinationKind.FULL_SCREEN) Modifier.navigationBarsPadding() else Modifier)
                            .padding(horizontal = GradeySpacing.md, vertical = GradeySpacing.sm),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
                    ) {
                        if (updateReady && state.tab.kind != DestinationKind.FULL_SCREEN) UpdateReadyBanner(updateState.release?.versionName.orEmpty()) { model.select(AppTab.UPDATES) }
                        if (state.tab != AppTab.ACCOUNT && state.tab != AppTab.UPDATES) androidx.compose.material3.SnackbarHost(snackbar) { data -> GradeySnackbar(data) }
                    }
                }
                BottomBarVisibility(visible = state.tab.kind != DestinationKind.FULL_SCREEN) {
                    GradeyNavigationBar(state.rootTab, showMealsTab, model::select)
                }
            }
        } else com.bukovinafilip.gradey.ui.SettingsBackground(Modifier.fillMaxSize()) {
            when {
                state.tab == AppTab.UPDATES -> updateScreen()
                !isValidAgeAttestation(ageAttestation) -> WelcomeScreen(onComplete = ::acceptPrivacy)
                !hasCompletedWelcome || reviewPrivacyChanges -> PrivacyUpdateScreen(
                    onAccept = { acceptPrivacy(requireNotNull(ageAttestation)) },
                    onClose = if (hasCompletedWelcome) ({ reviewPrivacyChanges = false }) else null,
                )
                state.phase == AppPhase.SIGNED_OUT -> GradeyIdLoginScreen(
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
                    modifier = Modifier.fillMaxSize(),
                )
                state.phase == AppPhase.NEEDS_SCHOOL -> if (state.loginStep != null) com.bukovinafilip.gradey.feature.login.SchoolVerificationScreen(
                    onOpenHelp = { showHelp = true },
                    step = state.loginStep!!, isLoading = state.loading, errorMessage = state.error,
                    onSubmitCode = { model.submitCode(it) }, onCheckApproval = { model.checkApproval() },
                    onResend = { model.resendApproval() }, onSelectStudent = { model.selectStudent(it) }, onBack = model::cancelVerification,
                    modifier = Modifier.fillMaxSize(),
                ) else SchoolLoginScreen(
                    onOpenHelp = { showHelp = true },
                    isLoading = state.loading, onLogin = model::login,
                    modifier = Modifier.fillMaxSize(),
                    availableProviders = com.bukovinafilip.gradey.model.SchoolProvider.entries,
                    initialProvider = state.reconnectingAccount?.provider,
                    initialSchoolURL = state.reconnectingAccount?.baseURL,
                    isReconnecting = state.reconnectingAccount != null,
                    directorySchools = state.directorySchools, directoryLoading = state.directoryLoading,
                    directoryError = state.directoryError, onLoadDirectory = model::loadSchoolDirectory,
                    accountPicker = { if (state.reconnectingAccount == null) SchoolAccountSwitcher(state, model::activateAccount, model::reconnectAccount) },
                    errorMessage = state.error, onBack = if (state.school != null) model::back else ({ model.signOut() }),
                )
                else -> com.bukovinafilip.gradey.ui.LoadingState(Modifier.align(Alignment.Center))
            }
            if (updateReady) Box(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(GradeySpacing.md)) {
                UpdateReadyBanner(updateState.release?.versionName.orEmpty()) { model.select(AppTab.UPDATES) }
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
    }
}

/** Tappable notice that a downloaded update is ready to install. */
@Composable
private fun UpdateReadyBanner(versionName: String, onClick: () -> Unit) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val shape = RoundedCornerShape(GradeyRadius.md)
    Row(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(shape).background(androidx.compose.material3.MaterialTheme.gradeyColors.secondaryGroupedBackground)
            .border(1.dp, colors.primary.copy(alpha = 0.3f), shape).clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .padding(GradeySpacing.lg),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        GradeyIcon(HugeIcons.Download04, size = 20.dp, tint = colors.primary)
        Text(stringResource(R.string.app_update_banner, versionName), Modifier.weight(1f), style = GradeyType.subheadlineBold, color = colors.onSurface)
        GradeyIcon(HugeIcons.ArrowRight01, size = 13.dp, tint = androidx.compose.material3.MaterialTheme.gradeyColors.secondaryLabel)
    }
}

/** Snackbar in Gradey type on the inverse surface, with a text action. */
@Composable
private fun GradeySnackbar(data: androidx.compose.material3.SnackbarData) {
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    Row(
        Modifier.widthIn(max = 520.dp).fillMaxWidth().clip(RoundedCornerShape(GradeyRadius.md)).background(colors.inverseSurface)
            .padding(start = GradeySpacing.lg, end = GradeySpacing.sm, top = GradeySpacing.sm, bottom = GradeySpacing.sm),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        Text(data.visuals.message, Modifier.weight(1f).padding(vertical = GradeySpacing.sm), style = GradeyType.subheadline, color = colors.inverseOnSurface)
        data.visuals.actionLabel?.let { label ->
            Text(
                label,
                Modifier.clip(RoundedCornerShape(GradeyRadius.xs)).clickable(role = androidx.compose.ui.semantics.Role.Button) { data.dismiss() }
                    .padding(horizontal = GradeySpacing.md, vertical = GradeySpacing.md),
                style = GradeyType.subheadlineBold, color = colors.inversePrimary,
            )
        }
    }
}
