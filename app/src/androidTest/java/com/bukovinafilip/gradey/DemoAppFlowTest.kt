package com.bukovinafilip.gradey

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bukovinafilip.gradey.data.SecureJsonStore
import com.bukovinafilip.gradey.model.PlannedAbsence
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
        if (state().phase == AppPhase.SIGNED_IN) signOutThroughUi()
        compose.onNodeWithText("Use demo data").performScrollTo().performClick()
        compose.waitUntil(15_000) { state().phase == AppPhase.SIGNED_IN && !state().loading }
        compose.onNodeWithText("Overall average").assertIsDisplayed()
    }

    @Test fun navigationSearchAndActivityRecreationPreserveTheSelectedScreen() {
        compose.onNodeWithContentDescription("Marks").performClick()
        compose.onNodeWithContentDescription("Search subjects").performTextInput("cesky")
        compose.onNodeWithText("Český jazyk").assertExists()
        compose.onAllNodesWithText("Matematika").assertCountEquals(0)

        // Activity recreation exercises the same retained ViewModel/saved Compose state as rotation.
        compose.activityRule.scenario.recreate()

        compose.waitUntil(10_000) { state().tab == AppTab.SUBJECTS }
        compose.onNodeWithContentDescription("Search subjects").assertExists()
        compose.onNodeWithText("cesky").assertExists()
        compose.onAllNodesWithText("Matematika").assertCountEquals(0)
        compose.onNodeWithContentDescription("Absence").performClick()
        compose.onNodeWithText("Plan absence").assertExists()
        compose.onNodeWithContentDescription("Timetable").performClick()
        assertEquals(AppTab.TIMETABLE, state().tab)
        compose.onNodeWithContentDescription("Today").performClick()
        compose.onNodeWithText("Overall average").assertExists()
    }

    @Test fun plannedAbsenceIsSavedRestoredAndRemovedFromEncryptedStorage() {
        compose.onNodeWithContentDescription("Absence").performClick()
        compose.onNodeWithText("Plan absence").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Next week").performClick()
        compose.waitUntil(10_000) { !state().loading && state().timetable != null }
        compose.onNodeWithText("Select all").performClick()
        scrollTo(hasText("Save plan"))
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
        scrollTo(hasContentDescription(deleteDescription))
        compose.onNodeWithContentDescription(deleteDescription).performClick()
        compose.waitUntil(10_000) { state().plans.isEmpty() }
        assertTrue(readSavedPlans(key).isEmpty())
        scrollTo(hasText("No planned absences yet."))
        compose.onNodeWithText("No planned absences yet.").assertExists()
    }

    @Test fun mealReplacementAndCancellationRequireConfirmation() {
        compose.onNodeWithContentDescription("Meals").performClick()
        scrollTo(hasText("Order meal"))
        compose.onNodeWithText("Order meal").performClick()
        compose.onNodeWithText("Replace your meal?").assertIsDisplayed()
        assertEquals(listOf(1), orderedMealIDs())
        compose.onNodeWithText("Keep current order").performClick()
        assertEquals(listOf(1), orderedMealIDs())

        compose.onNodeWithText("Order meal").performClick()
        compose.onNodeWithText("Replace meal").performClick()
        compose.waitUntil(10_000) { orderedMealIDs() == listOf(2) }
        scrollTo(hasText("Cancel order"))
        compose.onNodeWithText("Cancel order").performClick()
        compose.onNodeWithText("Cancel this meal?").assertIsDisplayed()
        assertEquals(listOf(2), orderedMealIDs())
        compose.onNodeWithText("Cancel meal").performClick()
        compose.waitUntil(10_000) { orderedMealIDs().isEmpty() }
    }

    @Test fun signOutClearsPrivateStateAndDoesNotDisplayCredentialValues() {
        compose.onNodeWithContentDescription("Open account").performClick()
        assertNoCredentialText()
        scrollTo(hasText("Sign out"))
        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Sign out of Gradey?").assertIsDisplayed()
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

    private fun signOutThroughUi() {
        compose.onNodeWithContentDescription("Today").performClick()
        compose.onNodeWithContentDescription("Open account").performClick()
        scrollTo(hasText("Sign out"))
        compose.onNodeWithText("Sign out").performClick()
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
