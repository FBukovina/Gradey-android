package com.bukovinafilip.gradey

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    override fun onResume() {
        super.onResume()
        (application as GradeyApplication).updates.onForeground()
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val tab = when (intent.data?.host ?: intent.data?.path?.removePrefix("/")) {
            "timetable" -> AppTab.TIMETABLE
            "marks" -> AppTab.SUBJECTS
            "updates" -> AppTab.UPDATES
            else -> AppTab.TODAY
        }
        androidx.lifecycle.ViewModelProvider(this)[GradeyViewModel::class.java].select(tab)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GradeyTheme {
                GradeyApp(initialTab = when (intent?.data?.host ?: intent?.data?.path?.removePrefix("/")) { "timetable" -> AppTab.TIMETABLE; "marks" -> AppTab.SUBJECTS; "updates" -> AppTab.UPDATES; else -> AppTab.TODAY })
            }
        }
    }
}

@Composable
private fun GradeyApp(initialTab: AppTab, model: GradeyViewModel = androidx.lifecycle.viewmodel.compose.viewModel()) {
    val state by model.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val updates = (context.applicationContext as GradeyApplication).updates
    val updateState by updates.state.collectAsStateWithLifecycle()
    var googleLoading by remember { mutableStateOf(false) }
    val snackbar = remember { androidx.compose.material3.SnackbarHostState() }
    val notificationPermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) scope.launch { registerPush(context, model.graph) } }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            try {
                val contents = model.graph.accountManagement?.exportAccountData() ?: error("Data export is unavailable in demo mode.")
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    checkNotNull(context.contentResolver.openOutputStream(uri)).use { it.write(contents.toByteArray(Charsets.UTF_8)) }
                }
                model.showError("Your account data was exported.")
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { model.showError(safeError(e)) }
        }
    }
    LaunchedEffect(Unit) { model.applyInitialTab(initialTab) }
    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it, actionLabel = "Dismiss", duration = androidx.compose.material3.SnackbarDuration.Long) }
    }
    LaunchedEffect(state.account?.id, state.isDemo) {
        if (state.account != null && !state.isDemo) registerPush(context, model.graph)
    }
    androidx.activity.compose.BackHandler(enabled = state.tab == AppTab.UPDATES || (state.phase == AppPhase.SIGNED_IN && state.tab != AppTab.TODAY)) {
        if (state.tab == AppTab.UPDATES) model.select(AppTab.TODAY) else model.back()
    }
    val standard = Modifier.fillMaxSize().statusBarsPadding().padding(bottom = 96.dp)
    Box(Modifier.fillMaxSize()) {
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
        } else when (state.phase) {
            AppPhase.CHECKING -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { androidx.compose.material3.CircularProgressIndicator() }
            AppPhase.SIGNED_OUT -> GradeyIdLoginScreen(
                isLoading = state.loading || googleLoading,
                onGoogleSignIn = {
                    scope.launch {
                        googleLoading = true
                        try { val identity = googleIdentity(context); model.signIn(identity.idToken, identity.displayName) }
                        catch (e: kotlinx.coroutines.CancellationException) { throw e }
                        catch (_: androidx.credentials.exceptions.GetCredentialCancellationException) { }
                        catch (e: Exception) { model.showError(safeError(e)) }
                        finally { googleLoading = false }
                    }
                },
                onTestingBypass = { model.demo() },
                onOpenUpdates = { model.select(AppTab.UPDATES) },
                isConfigured = BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank() && BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank(),
                errorMessage = state.error,
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
            )
            AppPhase.NEEDS_SCHOOL -> if (state.loginStep != null) com.bukovinafilip.gradey.feature.login.SchoolVerificationScreen(
                step = state.loginStep!!, isLoading = state.loading, errorMessage = state.error,
                onSubmitCode = { model.submitCode(it) }, onCheckApproval = { model.checkApproval() },
                onResend = { model.resendApproval() }, onSelectStudent = { model.selectStudent(it) }, onBack = model::cancelVerification,
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()),
            ) else SchoolLoginScreen(
                isLoading = state.loading, onLogin = model::login,
                modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()),
                availableProviders = com.bukovinafilip.gradey.model.SchoolProvider.entries,
                errorMessage = state.error, onBack = if (state.school != null) model::back else ({ model.signOut() }),
            )
            AppPhase.SIGNED_IN -> {
                when (state.tab) {
                    AppTab.TODAY -> TodayScreen(
                        dashboard = state.dashboard, absence = state.absence, timetable = state.currentTimetable,
                        isRefreshing = state.loading, onRefresh = { model.refresh() },
                        onOpenAccount = { model.select(AppTab.ACCOUNT) }, onOpenGradeyTools = { model.select(AppTab.TOOLS) },
                        onOpenMarks = { model.select(AppTab.SUBJECTS) }, onOpenAbsence = { model.select(AppTab.ABSENCE) },
                        onOpenTimetable = { model.select(AppTab.TIMETABLE) }, modifier = Modifier.fillMaxSize(),
                        plannedAbsences = state.plans, onPlanAbsence = { model.select(AppTab.PLANNER) },
                    )
                    AppTab.SUBJECTS -> SubjectsScreen(
                        subjects = state.dashboard.marksResponse.subjects, absence = state.absence,
                        predictSubjectAverage = model.graph.schoolRepository::predictSubjectAverage,
                        isRefreshing = state.loading, onRefresh = { model.refresh() },
                        onOpenAccount = { model.select(AppTab.ACCOUNT) }, onOpenGradeyTools = { model.select(AppTab.TOOLS) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppTab.ABSENCE -> AbsenceScreen(
                        response = state.absence, studentName = state.dashboard.user?.fullName ?: "Student",
                        isRefreshing = state.loading, onRefresh = { model.refresh() },
                        onOpenAccount = { model.select(AppTab.ACCOUNT) }, onOpenGradeyTools = { model.select(AppTab.TOOLS) },
                        modifier = Modifier.fillMaxSize(), onPlanAbsence = { model.select(AppTab.PLANNER) },
                    )
                    AppTab.TIMETABLE -> TimetableScreen(
                        week = state.timetable, isRefreshing = state.loading, requestedWeek = state.viewedWeek,
                        onRefresh = { model.changeWeek(state.viewedWeek) }, onChangeWeek = { model.changeWeek(it) },
                        onOpenAccount = { model.select(AppTab.ACCOUNT) }, onOpenGradeyTools = { model.select(AppTab.TOOLS) },
                        modifier = Modifier.fillMaxSize(),
                    )
                    AppTab.PLANNER -> com.bukovinafilip.gradey.feature.absence.AbsencePlannerScreen(
                        response = state.absence, timetable = state.timetable, plans = state.plans,
                        isRefreshing = state.loading, onRefresh = { model.changeWeek(state.viewedWeek) },
                        onClose = { model.select(AppTab.TODAY) }, onWeekChange = { model.changeWeek(it.toString()) },
                        onSavePlan = model::savePlan, onRemovePlan = model::removePlan, modifier = Modifier.fillMaxSize(), requestedWeek = state.viewedWeek,
                    )
                    AppTab.STRAVACZ -> com.bukovinafilip.gradey.feature.stravacz.StravaCZRoute(model.graph.stravaCZRepository, standard, isDemo = state.isDemo)
                    AppTab.ACCOUNT -> AccountScreen(
                        account = state.account, linkedAccounts = state.linkedAccounts, onSignOut = { model.signOut() },
                        modifier = standard, preferences = state.preferences, onPreferencesChange = { model.preferences(it) },
                        students = state.school?.eduPage?.linkedStudents.orEmpty(), activeStudentID = state.school?.eduPage?.activeStudent?.id,
                        onSelectStudent = { model.switchStudent(it) },
                        activeAccountID = state.school?.linkedAccountID, onActivateAccount = { model.activateAccount(it.id) },
                        onUnlinkAccount = { model.unlinkAccount(it.id) }, onAddAccount = model::addAccount,
                        isLoading = state.loading, errorMessage = state.error, onRetry = { model.refresh() }, isDemo = state.isDemo,
                        versionName = BuildConfig.VERSION_NAME,
                        onOpenUpdates = { model.select(AppTab.UPDATES) },
                        onExportData = if (state.isDemo) null else ({ exportLauncher.launch("gradey-account-data.json") }),
                        onDeleteAccount = if (state.isDemo) null else ({ model.deleteAccount() }),
                        onNotificationSettings = {
                            if (android.os.Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                            } else context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName))
                        },
                    )
                    AppTab.HISTORY -> HistoryScreen(state, { model.select(AppTab.TOOLS) }, standard)
                    AppTab.SUPPORT -> SupportScreen(state.account?.id, state.isDemo, standard)
                    AppTab.AI -> com.bukovinafilip.gradey.feature.ai.GradeyAIRoute(model.graph.schoolRepository, standard, gradeyAccountID = state.account?.id, isDemo = state.isDemo)
                    AppTab.TOOLS -> ToolsScreen(state.isDemo, model::select, standard)
                    AppTab.UPDATES -> Unit // Available above, including before sign-in.
                }
                if (state.tab != AppTab.PLANNER) GradeyBottomNavigation(state.tab, model::select, Modifier.align(Alignment.BottomCenter))
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
                    Text("Gradey ${updateState.release?.versionName} is ready · Install update", modifier = Modifier.weight(1f))
                }
            }
        }
        if (state.phase == AppPhase.SIGNED_IN && state.tab !in listOf(AppTab.ACCOUNT, AppTab.UPDATES)) androidx.compose.material3.SnackbarHost(
            snackbar, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp, start = 12.dp, end = 12.dp),
        )
    }
}

@Composable
private fun ToolsScreen(isDemo: Boolean, onSelect: (AppTab) -> Unit, modifier: Modifier) {
    com.bukovinafilip.gradey.ui.GradeyScreen(modifier.verticalScroll(rememberScrollState())) {
        com.bukovinafilip.gradey.ui.GradeyHero("Gradey tools", if (isDemo) "Demo mode · Explore with sample school data" else "Plan your school day")
        listOf(
            Triple(AppTab.AI, "Grades and schedule, explained", Icons.Default.AutoAwesome),
            Triple(AppTab.PLANNER, "Preview your attendance", Icons.Default.CalendarMonth),
            Triple(AppTab.HISTORY, "Changes in grades and averages", Icons.Default.History),
            Triple(AppTab.STRAVACZ, "Your canteen menu and orders", Icons.Default.Restaurant),
            Triple(AppTab.ACCOUNT, "Schools, profiles and notifications", Icons.Default.Person),
            Triple(AppTab.SUPPORT, "Support development and restore purchases", Icons.Default.Favorite),
            Triple(AppTab.UPDATES, "Automatic downloads and new versions", Icons.Default.SystemUpdate),
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
                        Text(tab.label, style = androidx.compose.material3.MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
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
        com.bukovinafilip.gradey.ui.GradeyHero("Grade history", "Changes recorded for your linked school account")
        androidx.compose.material3.TextButton(onClick = onBack) { Text("Back to tools") }
        if (state.loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.history.isEmpty() && !state.loading) Text("No grade history yet. Changes will appear here after your linked account syncs.")
        state.history.forEach { trend ->
            com.bukovinafilip.gradey.ui.GradeySectionCard(title = trend.subjectName) {
                Text("Average change: %+.2f".format(trend.averageDelta))
                Text("${trend.markCountDelta} new marks")
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
    AppTab.PLANNER -> Icons.Default.CalendarMonth
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
) {
    val visibleTabs = MarksTabs
    Box(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 21.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(62.dp),
            shape = RoundedCornerShape(31.dp),
            color = Color(0xFFFDFDFF).copy(alpha = 0.96f),
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
    val foreground = if (selected) Color(0xFF0DA388) else Color(0xFF19191D)
    Surface(
        modifier = Modifier
            .weight(1f)
            .height(54.dp),
        onClick = onClick,
        shape = RoundedCornerShape(27.dp),
        color = if (selected) Color(0xFFE8E8EC) else Color.Transparent,
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
                    contentDescription = tab.label,
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
                text = tab.label,
                color = if (selected) Color(0xFF0DA388) else Color.Black,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
