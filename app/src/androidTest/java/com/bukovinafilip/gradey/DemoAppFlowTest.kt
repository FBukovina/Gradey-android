package com.bukovinafilip.gradey

import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Bundle
import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bukovinafilip.gradey.data.SecureJsonStore
import com.bukovinafilip.gradey.model.PlannedAbsence
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.network.GradeyJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Exercises only the default demo build. Configured builds are skipped before Activity launch. */
@RunWith(AndroidJUnit4::class)
class DemoAppFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private val onlyDemoBuild = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Demo flow tests never run against configured accounts or services.",
                    BuildConfig.SUPABASE_URL.isBlank() && BuildConfig.SUPABASE_ANON_KEY.isBlank() &&
                        BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank() && BuildConfig.REVENUECAT_ANDROID_KEY.isBlank())
                base.evaluate()
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(onlyDemoBuild).around(compose)

    private val verticalScroll = SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
    private val dialog = SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)

    @Before fun startFreshDemo() {
        compose.waitUntil(15_000) { state().phase != AppPhase.CHECKING && !state().loading }
        assertTrue(state().isDemo)
        if (compose.onAllNodesWithText("I’m 16 or older").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("I’m 16 or older").performScrollTo().performClick()
            compose.onNodeWithText("I have read and agree to the Privacy Policy and Terms of Use.").performScrollTo().performClick()
            compose.onNodeWithText("Continue to Gradey").performScrollTo().performClick()
        }
        if (state().phase == AppPhase.SIGNED_IN) signOutThroughUi()
        compose.onNodeWithText("Use demo data").performScrollTo().performClick()
        compose.waitUntil(15_000) { state().phase == AppPhase.SIGNED_IN && !state().loading }
        compose.onNodeWithTag("todayHero").assertIsDisplayed()
    }

    @Test fun navigationAndActivityRecreationPreserveTheSelectedScreen() {
        compose.onNodeWithTag("tab-subjects").performClick()
        compose.onNodeWithTag("subjectsList").assertExists()

        // Activity recreation exercises the same retained ViewModel/saved Compose state as rotation.
        compose.activityRule.scenario.recreate()

        compose.waitUntil(10_000) { state().tab == AppTab.SUBJECTS }
        compose.onNodeWithTag("subjectsList").assertExists()
        compose.onNodeWithTag("tab-absence").performClick()
        compose.onNodeWithText("Plan absence").assertExists()
        compose.onNodeWithTag("tab-timetable").performClick()
        assertEquals(AppTab.TIMETABLE, state().tab)
        compose.onNodeWithTag("tab-today").performClick()
        compose.onNodeWithTag("todayHero").assertExists()
    }

    @Test fun plannedAbsenceIsSavedRestoredAndRemovedFromEncryptedStorage() {
        compose.onNodeWithTag("tab-absence").performClick()
        compose.onNodeWithText("Plan absence").performClick()
        compose.onNodeWithContentDescription("Next week").performClick()
        compose.waitUntil(10_000) { !state().loading && state().timetable != null }
        compose.onNodeWithText("Select all").performClick()
        compose.onNodeWithTag("absencePlanner").performScrollToNode(hasText("Save plan"))
        compose.onNodeWithText("Save plan").performClick()
        compose.waitUntil(10_000) { state().plans.isNotEmpty() }

        val plan = state().plans.single()
        val key = "plans.${requireNotNull(state().school).cacheScope}"
        assertTrue(plan.lessons.isNotEmpty())
        assertEquals(listOf(plan), readSavedPlans(key))
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { state().tab == AppTab.PLANNER && !state().loading }
        assertEquals(plan, state().plans.single())

        val deleteDescription = "Delete plan for ${plan.date}"
        compose.onNodeWithTag("absencePlanner").performScrollToNode(hasContentDescription(deleteDescription))
        compose.onNodeWithContentDescription(deleteDescription).performClick()
        compose.waitUntil(10_000) { state().plans.isEmpty() }
        assertTrue(readSavedPlans(key).isEmpty())
        compose.onNodeWithTag("absencePlanner").performScrollToNode(hasText("No planned absences yet."))
        compose.onNodeWithText("No planned absences yet.").assertExists()
    }

    @Test fun permanentTimetableDoesNotReplaceTheDatedWeek() {
        val datedWeek = requireNotNull(state().currentTimetable)
        compose.onNodeWithTag("tab-timetable").performClick()
        compose.onNode(hasText("Timetable") and SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)).performClick()
        compose.onNodeWithText("Permanent").performClick()
        compose.waitUntil(10_000) { !state().permanentTimetableLoading && state().permanentTimetable != null }
        assertEquals(com.bukovinafilip.gradey.model.TimetableKind.PERMANENT, state().permanentTimetable?.kind)
        assertTrue(state().permanentTimetable!!.days.all { it.date == null })
        assertEquals(datedWeek, state().currentTimetable)
        compose.activityRule.scenario.recreate()
        compose.waitUntil(10_000) { state().timetableKind == com.bukovinafilip.gradey.model.TimetableKind.PERMANENT }
        compose.onNodeWithText("Regular weekly schedule without date-specific changes.").assertExists()
        compose.onNodeWithTag("tab-today").performClick()
        assertEquals(datedWeek, state().currentTimetable)
        compose.onNodeWithTag("todayHero").assertExists()
    }

    @Test fun timetableSelectedDaySurvivesOpeningAttachedPlannerItemAndBack() {
        val application = compose.activity.application as GradeyApplication
        val snapshot = state()
        val week = requireNotNull(snapshot.timetable)
        val today = java.time.LocalDate.now(java.time.ZoneId.of("Europe/Prague"))
        // Neither today's automatic selection nor the fallback Monday may accidentally pass.
        val day = week.days.first { candidate ->
            val date = candidate.date?.take(10)?.let(java.time.LocalDate::parse)
            date != null && date != today && date.dayOfWeek != java.time.DayOfWeek.MONDAY && candidate.lessons.isNotEmpty()
        }
        val lesson = day.lessons.first()
        val item = com.bukovinafilip.gradey.domain.PersonalPlanner.linked(lesson, day, requireNotNull(snapshot.school).cacheScope)
            .copy(title = "Attached lesson return regression")
        val saved = runBlocking { application.demoPlanner.save(item) }
        compose.activityRule.scenario.onActivity { ViewModelProvider(it)[GradeyViewModel::class.java].plannerChanged(saved) }
        val date = java.time.LocalDate.parse(requireNotNull(day.date).take(10))
        val locale = compose.activity.resources.configuration.locales[0]
        val dayLabel = "${date.format(java.time.format.DateTimeFormatter.ofPattern("EEE", locale))}, ${date.dayOfMonth}"
        val lessonButton = hasContentDescription("Open ${lesson.subjectName ?: lesson.subjectAbbrev} lesson details", substring = true)
        try {
            compose.onNodeWithTag("tab-timetable").performClick()
            compose.onNodeWithContentDescription(dayLabel).performClick().assertIsSelected()
            compose.onNodeWithTag("timetableList").performScrollToNode(lessonButton)
            compose.onNode(lessonButton).performClick()
            compose.onNodeWithTag("timetable-planner-item-${item.id}").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("plannerEditor").fetchSemanticsNodes().isNotEmpty() }
            assertEquals(AppTab.PERSONAL_PLANNER, state().tab)
            compose.onNode(hasText(item.title) and hasAnyAncestor(hasTestTag("plannerEditor"))).assertExists()

            // Drive real Activity back dispatch through the editor and Planner screen.
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("plannerEditor").fetchSemanticsNodes().isEmpty() }
            assertEquals(AppTab.PERSONAL_PLANNER, state().tab)
            androidx.test.espresso.Espresso.pressBack()
            compose.waitUntil(5_000) { state().tab == AppTab.TIMETABLE }
            assertEquals(snapshot.viewedWeek, state().viewedWeek)
            compose.onNodeWithContentDescription(dayLabel).assertIsSelected()
            compose.onNodeWithTag("timetableList").performScrollToNode(lessonButton)
            compose.onNode(lessonButton).performClick()
            compose.onNodeWithTag("timetable-planner-item-${item.id}").assertExists()
            assertEquals(saved.first { it.id == item.id }, runBlocking { application.demoPlanner.load() }.first { it.id == item.id })
            androidx.test.espresso.Espresso.pressBack()
        } finally {
            val remaining = runBlocking { application.demoPlanner.delete(item.id) }
            compose.activityRule.scenario.onActivity { ViewModelProvider(it)[GradeyViewModel::class.java].plannerChanged(remaining) }
        }
    }

    @Test fun tryAMarkShowsAnEstimateWithoutEditingMarks() {
        val recorded = state().dashboard.marksResponse
        compose.onNodeWithTag("tab-subjects").performClick()
        compose.onNodeWithText("Matematika").performClick()
        compose.onNodeWithTag("subjectDetail").performScrollToNode(hasTestTag("theoreticalMarkField"))
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("theoreticalMarkField"))).performTextInput("3")
        compose.onNodeWithTag("subjectDetail").performScrollToNode(hasTestTag("theoreticalResultPanel"))
        compose.onNodeWithTag("theoreticalResultPanel").assertExists()
        assertEquals(recorded, state().dashboard.marksResponse)
        // The first Back would only hide the keyboard the mark field opened.
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("subjectsList").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(AppTab.SUBJECTS, state().tab)
    }

    @Test fun reportCardShowsEstimatesAndOpensAnEarlierTerm() {
        compose.onNodeWithTag("tab-subjects").performClick()
        compose.onNodeWithTag("marksReportCardRow").performClick()
        compose.waitUntil(5_000) { state().tab == AppTab.REPORT_CARD }
        compose.onNodeWithTag("reportCard").assertExists()
        compose.onNodeWithText("Expected report card average").assertIsDisplayed()
        compose.onNodeWithTag("tab-subjects").assertIsSelected()

        // Demo mode carries last school year's two report cards.
        val term = runBlocking { (compose.activity.application as GradeyApplication).activeGraph.schoolRepository.loadReportCards() }.first()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("reportCardTerm-${term.id}").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("reportCard").performScrollToNode(hasTestTag("reportCardTerm-${term.id}"))
        compose.onNodeWithTag("reportCardTerm-${term.id}").performClick()
        compose.onNodeWithTag("reportCardTerm").assertExists()
        compose.onNodeWithText("Report card average").assertIsDisplayed()

        // Back returns to the estimate first, then to Marks.
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithTag("reportCard").assertExists()
        assertEquals(AppTab.REPORT_CARD, state().tab)
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5_000) { state().tab == AppTab.SUBJECTS }
        compose.onNodeWithTag("subjectsList").assertExists()
    }

    @Test fun mealReplacementAndCancellationRequireConfirmation() {
        compose.onNodeWithTag("tab-stravacz").performClick()
        compose.onNodeWithTag("mealsList").performScrollToNode(hasTestTag("mealToggle-2"))
        compose.onNodeWithTag("mealToggle-2").performClick()
        compose.onNodeWithText("Replace lunch?").assertIsDisplayed()
        assertEquals(listOf(1), orderedMealIDs())
        compose.onNodeWithText("Keep current order").performClick()
        assertEquals(listOf(1), orderedMealIDs())

        compose.onNodeWithTag("mealToggle-2").performClick()
        compose.onNodeWithText("Replace order").performClick()
        compose.waitUntil(10_000) { orderedMealIDs() == listOf(2) }
        compose.onNodeWithTag("mealsList").performScrollToNode(hasTestTag("mealToggle-2"))
        compose.onNodeWithTag("mealToggle-2").performClick()
        compose.onNodeWithText("Cancel this meal?").assertIsDisplayed()
        assertEquals(listOf(2), orderedMealIDs())
        compose.onNodeWithText("Cancel meal").performClick()
        compose.waitUntil(10_000) { orderedMealIDs().isEmpty() }
    }

    @Test fun signOutClearsPrivateStateAndDoesNotDisplayCredentialValues() {
        compose.onNodeWithContentDescription("Settings").performClick()
        assertNoCredentialText()
        compose.onNodeWithTag("settingsDestination-account").performClick()
        compose.onNodeWithTag("settingsDetailScroll").performScrollToNode(hasTestTag("settingsSignOut"))
        compose.onNodeWithTag("settingsSignOut").performClick()
        compose.onNodeWithText("Sign out of all accounts on this device?").assertIsDisplayed()
        compose.onNode(hasText("Sign out") and hasAnyAncestor(dialog)).performClick()
        compose.waitUntil(10_000) { state().phase == AppPhase.SIGNED_OUT && !state().loading }

        compose.onNodeWithText("Gradey ID").assertExists()
        assertNull(state().account)
        assertNull(state().school)
        assertTrue(state().dashboard.marksResponse.subjects.isEmpty())
        assertTrue(state().plans.isEmpty())
        assertFalse(compose.activity.getSharedPreferences("gradey-mode", 0).getBoolean("demo", false))
        assertNoCredentialText()
    }

    @Test fun demoSchoolControlsCannotOpenLoginOrRemoveSampleAccount() {
        val school = state().school
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settingsDestination-account").performClick()
        compose.onNodeWithText("Explore Gradey with sample data. Real school accounts cannot be connected or changed in demo mode.").assertExists()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("settingsDestination-connectedServices").performClick()
        val linkedID = state().linkedAccounts.first().id
        compose.onNodeWithTag("settingsDetailScroll").performScrollToNode(hasTestTag("settingsLinkSchool"))
        compose.onNodeWithTag("settingsLinkSchool").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("linkedAccountMenu-$linkedID").performClick()
        compose.onNodeWithTag("linkedAccountUnlink-$linkedID").assertIsNotEnabled()
        androidx.test.espresso.Espresso.pressBack()

        assertEquals(AppPhase.SIGNED_IN, state().phase)
        assertEquals(school, state().school)
        compose.onAllNodesWithText("School URL").assertCountEquals(0)
        compose.onAllNodesWithText("Unlink account?").assertCountEquals(0)
    }

    @Test fun demoSchoolCommandsRejectRealCredentialsWithoutChangingSession() {
        val school = requireNotNull(state().school)
        val linkedAccounts = state().linkedAccounts
        val commands: List<(GradeyViewModel) -> Unit> = listOf(
            { it.addAccount() },
            { it.login(SchoolProvider.BAKALARI, "https://school.example", "student", "private-password") },
            { it.unlinkAccount(requireNotNull(school.linkedAccountID)) },
        )
        commands.forEach { command ->
            compose.activityRule.scenario.onActivity { activity ->
                val model = ViewModelProvider(activity)[GradeyViewModel::class.java]
                model.dismissError()
                command(model)
            }
            compose.waitUntil(10_000) { !state().loading && state().error != null }
            assertEquals("Real school accounts cannot be connected or changed in demo mode.", state().error)
            assertEquals(AppPhase.SIGNED_IN, state().phase)
            assertEquals(school, state().school)
            assertEquals(linkedAccounts, state().linkedAccounts)
            val graph = (compose.activity.application as GradeyApplication).activeGraph
            assertEquals(school, runBlocking { graph.schoolRepository.currentStoredSession() })
            assertEquals(linkedAccounts, runBlocking { graph.linkedAccountRepository.cachedAccounts() })
        }
    }

    @Test fun revokedCompanionRefreshCannotRestoreAClearedWidget() {
        val context = compose.activity.applicationContext
        val snapshot = state()
        val token = CompanionSync.capture(context)
        runBlocking {
            CompanionSync.clear(context)
            CompanionSync.publish(context, requireNotNull(snapshot.currentTimetable), snapshot.dashboard.user, snapshot.school?.cacheScope, token)
        }
        assertNull(com.bukovinafilip.gradey.widgets.WidgetSnapshotStore.load(context))
        runBlocking { CompanionSync.publish(context, requireNotNull(snapshot.currentTimetable), snapshot.dashboard.user, snapshot.school?.cacheScope) }
        assertTrue(com.bukovinafilip.gradey.widgets.WidgetSnapshotStore.load(context) != null)
    }

    @Test fun plannerShortcutRequiresSaveAndCanOpenAnotherDraftWhilePlannerIsVisible() {
        val before = runBlocking { (compose.activity.application as GradeyApplication).demoPlanner.load() }
        fun open(title: String) {
            compose.activityRule.scenario.onActivity { activity ->
                // ActivityScenario tracks its original launch intent; exercise the production router
                // without replacing that intent before recreation. OS dispatch is checked separately.
                ViewModelProvider(activity)[GradeyViewModel::class.java].openDestination(
                    requireNotNull(gradeyDestination("gradey://planner/new?type=test&title=$title")))
            }
        }
        open("Shortcut-one")
        compose.waitUntil(5_000) { state().tab == AppTab.PERSONAL_PLANNER }
        compose.onNodeWithText("Shortcut-one").assertExists()
        assertEquals(before, runBlocking { (compose.activity.application as GradeyApplication).demoPlanner.load() })
        compose.onNodeWithText("Cancel").performClick()
        open("Shortcut-two")
        compose.onNodeWithText("Shortcut-two").assertExists()
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText("Shortcut-two").assertExists()
        assertEquals(before, runBlocking { (compose.activity.application as GradeyApplication).demoPlanner.load() })
        compose.onNodeWithText("Cancel").performClick()
    }

    @Test fun restoredWelcomeAcceptanceDoesNotBypassRequiredPrivacyRevision() {
        val preferences = compose.activity.getSharedPreferences("gradey-app-preferences", 0)
        val acceptedBefore = preferences.getInt("privacyRevision", 0)
        try {
            // Equivalent to a required revision advancing between the saved Activity and recreation.
            assertTrue(preferences.edit().putInt("privacyRevision", PrivacyRevision - 1).commit())
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Privacy updates").assertIsDisplayed()
            compose.onNodeWithTag("todayHero").assertDoesNotExist()
            compose.onNodeWithText("I’m 16 or older").assertDoesNotExist()
            assertEquals(PrivacyRevision - 1, preferences.getInt("privacyRevision", 0))
        } finally {
            assertTrue(preferences.edit().putInt("privacyRevision", acceptedBefore).commit())
        }
    }

    @Test fun plannerShortcutBehindPrivacyGateSurvivesSavedStateAndFreshViewModel() {
        val application = compose.activity.application as GradeyApplication
        val preferences = application.getSharedPreferences("gradey-app-preferences", 0)
        val acceptedBefore = preferences.getInt("privacyRevision", 0)
        val before = runBlocking { application.demoPlanner.load() }
        val title = "Preserved-after-policy"
        val raw = "gradey://planner/new?type=test&title=$title&due=2026-10-06"
        try {
            assertTrue(preferences.edit().putInt("privacyRevision", PrivacyRevision - 1).commit())
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText("Privacy updates").assertIsDisplayed()
            dispatchLinkWithoutReplacingScenarioIntent(raw)
            compose.waitUntil(5_000) { state().plannerDraft?.title == title }
            compose.onNodeWithText(title).assertDoesNotExist()
            assertTrue(hasPendingDestination())
            val savedState = captureActivitySavedState()
            assertEquals(raw, savedState.getString("gradey.pendingLaunchLink"))

            // Rotation alone retains the ViewModel. Reconstruct a distinct store from the Activity's
            // actual saved route to exercise losing that in-memory state as process death would.
            assertPlannerRouteRestoresInFreshViewModel(savedState, title)
            compose.onNodeWithText("Privacy updates").assertIsDisplayed()
            compose.onNodeWithText("Read the full Privacy Policy").performScrollTo()
            compose.onNodeWithText("I’ve read this — accept").performClick()
            // PlannerRoute loads its repository before consuming an incoming draft. Compose can
            // be idle while that IO is still running, so wait for the resulting editor semantics.
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(title).assertExists()
            compose.waitUntil(5_000) { !hasPendingDestination() }
            assertEquals(before, runBlocking { application.demoPlanner.load() })
            val consumed = captureActivitySavedState()
            assertNull(consumed.getString("gradey.pendingLaunchLink"))
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10_000) { compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(title).assertExists()
            assertEquals(before, runBlocking { application.demoPlanner.load() })
            compose.onNodeWithText("Cancel").performClick()
        } finally {
            assertTrue(preferences.edit().putInt("privacyRevision", acceptedBefore).commit())
        }
    }

    @Test fun dayOnlyPlannerShortcutIsAcknowledgedWithoutOpeningAnEditor() {
        val raw = "gradey://planner?day=2026-10-06"
        dispatchLinkWithoutReplacingScenarioIntent(raw)
        compose.waitUntil(5_000) { state().tab == AppTab.PERSONAL_PLANNER && !hasPendingDestination() }
        assertEquals("2026-10-06", state().plannerDay)
        compose.onNodeWithText("Title").assertDoesNotExist()
        assertNull(captureActivitySavedState().getString("gradey.pendingLaunchLink"))
        // The same day is already visibly applied; another identical external intent is consumed too.
        dispatchLinkWithoutReplacingScenarioIntent(raw)
        compose.waitUntil(5_000) { !hasPendingDestination() }
        assertNull(captureActivitySavedState().getString("gradey.pendingLaunchLink"))
    }

    private fun dispatchLinkWithoutReplacingScenarioIntent(raw: String) {
        compose.activityRule.scenario.onActivity { activity ->
            val originalLaunchIntent = activity.intent
            try { InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity, Intent(activity, MainActivity::class.java).setData(Uri.parse(raw))) }
            finally {
                // ActivityScenario matches lifecycle events using its original launch intent.
                // Restore that test harness identity after exercising the production intent handler.
                activity.intent = originalLaunchIntent
            }
        }
    }

    private fun hasPendingDestination(): Boolean {
        var pending = false
        compose.activityRule.scenario.onActivity { pending = ViewModelProvider(it)[GradeyViewModel::class.java].hasPendingDestination() }
        return pending
    }

    private fun captureActivitySavedState(): Bundle {
        val saved = Bundle()
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.onActivity {
            InstrumentationRegistry.getInstrumentation().callActivityOnSaveInstanceState(it, saved)
        }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle()
        return saved
    }

    private fun assertPlannerRouteRestoresInFreshViewModel(saved: Bundle, title: String) {
        val application = compose.activity.application as GradeyApplication
        val originalGraph = application.activeGraph
        val store = ViewModelStore()
        val owner = object : ViewModelStoreOwner { override val viewModelStore = store }
        lateinit var restored: GradeyViewModel
        try {
            compose.activityRule.scenario.onActivity {
                restored = ViewModelProvider(owner, ViewModelProvider.AndroidViewModelFactory.getInstance(application))[GradeyViewModel::class.java]
                restored.applyInitialDestination(requireNotNull(gradeyDestination(saved.getString("gradey.pendingLaunchLink"))))
            }
            compose.waitUntil(15_000) { restored.state.value.phase == AppPhase.SIGNED_IN && !restored.state.value.loading }
            assertEquals(title, restored.state.value.plannerDraft?.title)
            assertEquals(com.bukovinafilip.gradey.model.PlannerItemType.TEST, restored.state.value.plannerDraft?.type)
            assertEquals("2026-10-06", restored.state.value.plannerDraft?.dueDate)
            assertTrue(restored.hasPendingDestination())
        } finally {
            compose.activityRule.scenario.onActivity { store.clear(); application.activeGraph = originalGraph }
        }
    }

    @Test fun guestEntryUsesRealLocalProvidersAndCanReturnFromGradeySignInWithoutLosingItsMode() {
        signOutThroughUi()
        try {
            compose.onNodeWithText("Continue without Gradey ID").performScrollTo().performClick()
            compose.waitUntil(10_000) { state().isGuest && state().phase == AppPhase.NEEDS_SCHOOL && !state().loading }
            assertNull(state().account)
            assertFalse(state().isDemo)
            assertFalse((compose.activity.application as GradeyApplication).activeGraph.isDemo)
            compose.onNodeWithTag("schoolLogin").assertExists()
            compose.activityRule.scenario.onActivity { ViewModelProvider(it)[GradeyViewModel::class.java].requestGradeySignIn() }
            compose.waitUntil(5_000) { state().phase == AppPhase.SIGNED_OUT }
            compose.onNodeWithText("Continue without Gradey ID").performScrollTo().performClick()
            compose.waitUntil(5_000) { state().phase == AppPhase.NEEDS_SCHOOL && !state().loading }
            assertTrue(state().isGuest)
            assertNull(state().account)
            assertFalse(state().preferences.newMarksEnabled)
        } finally {
            compose.activityRule.scenario.onActivity { ViewModelProvider(it)[GradeyViewModel::class.java].signOut() }
            compose.waitUntil(10_000) { state().phase == AppPhase.SIGNED_OUT && !state().loading }
        }
    }

    private fun signOutThroughUi() {
        compose.onNodeWithTag("tab-today").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag("settingsDestination-account").performClick()
        compose.onNodeWithTag("settingsDetailScroll").performScrollToNode(hasTestTag("settingsSignOut"))
        compose.onNodeWithTag("settingsSignOut").performClick()
        compose.onNode(hasText("Sign out") and hasAnyAncestor(dialog)).performClick()
        compose.waitUntil(10_000) { state().phase == AppPhase.SIGNED_OUT && !state().loading }
    }

    private fun scrollTo(matcher: SemanticsMatcher) {
        compose.onNode(verticalScroll).performScrollToNode(matcher)
    }

    private fun readSavedPlans(key: String): List<PlannedAbsence> = SecureJsonStore(
        compose.activity.applicationContext, "gradey-planned-absences", GradeyJson,
    ).load(key, ListSerializer(PlannedAbsence.serializer())).orEmpty()

    private fun orderedMealIDs(): List<Int> = runBlocking {
        (compose.activity.application as GradeyApplication).activeGraph.stravaCZRepository.loadMenu(false).second
            .days.flatMap { it.meals }.filter { it.ordered }.map { it.id }
    }

    private fun state(): GradeyState {
        var snapshot: GradeyState? = null
        compose.activityRule.scenario.onActivity { activity ->
            snapshot = ViewModelProvider(activity)[GradeyViewModel::class.java].state.value
        }
        return requireNotNull(snapshot)
    }

    private fun assertNoCredentialText() {
        listOf("demo-access", "demo-refresh", "demo-gradey-access", "demo-strava", "GradelyDemo2026!")
            .forEach { value -> compose.onAllNodes(hasText(value, substring = true), useUnmergedTree = true).assertCountEquals(0) }
    }
}
