package com.bukovinafilip.gradey

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.bukovinafilip.gradey.updates.AppUpdateManager
import com.bukovinafilip.gradey.updates.UpdatePhase
import com.bukovinafilip.gradey.updates.UpdateUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import java.util.concurrent.TimeUnit

/** Checks the real app and persisted updater settings without a release download or installer. */
@RunWith(AndroidJUnit4::class)
class UpdaterFlowTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var app: GradeyApplication
    private lateinit var baseline: UpdateUiState
    private val safeUpdaterSetup = TestRule { base, _: Description ->
        object : Statement() {
            override fun evaluate() {
                assumeTrue("Updater UI tests only run on an unconfigured direct-download build.",
                    BuildConfig.DIRECT_UPDATES_ENABLED && BuildConfig.SUPABASE_URL.isBlank() &&
                        BuildConfig.SUPABASE_ANON_KEY.isBlank() && BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank() &&
                        BuildConfig.REVENUECAT_ANDROID_KEY.isBlank())
                app = ApplicationProvider.getApplicationContext()
                val original = app.updates.state.value
                assumeTrue("Do not interrupt a user's available or downloaded update.",
                    original.release == null && original.phase !in listOf(UpdatePhase.STORE_MANAGED, UpdatePhase.DISABLED))
                try {
                    // This outer rule runs before Activity.onResume, preventing a foreground check.
                    runBlocking {
                        app.updates.setAutomaticChecks(false)
                        app.updates.setAutomaticDownloads(false)
                        app.updates.setWifiOnly(true)
                    }
                    baseline = app.updates.state.value
                    base.evaluate()
                } finally {
                    // The Activity rule has already closed the Activity before these are restored.
                    runBlocking {
                        app.updates.setAutomaticChecks(false)
                        app.updates.setWifiOnly(original.wifiOnly)
                        app.updates.setAutomaticDownloads(original.automaticDownloads)
                        app.updates.setAutomaticChecks(original.automaticChecks)
                    }
                }
            }
        }
    }

    @get:Rule
    val rules: TestRule = RuleChain.outerRule(safeUpdaterSetup).around(compose)

    @Test fun updatesWorkBeforeSignInAndSettingsPersistWithoutBackgroundChecks() {
        compose.waitUntil(15_000) { schoolState().phase != AppPhase.CHECKING && !schoolState().loading }
        if (schoolState().phase != AppPhase.SIGNED_OUT) {
            assumeTrue("Only a sample account may be signed out by this test.", schoolState().isDemo)
            compose.activityRule.scenario.onActivity { activity ->
                ViewModelProvider(activity)[GradeyViewModel::class.java].signOut()
            }
            compose.waitUntil(15_000) { schoolState().phase == AppPhase.SIGNED_OUT && !schoolState().loading }
        }
        if (compose.onAllNodesWithText("I’m 16 or older").fetchSemanticsNodes().isNotEmpty()) {
            compose.onNodeWithText("I’m 16 or older").performScrollTo().performClick()
            compose.onNodeWithText("I have read and agree to the Privacy Policy and Terms of Use.").performScrollTo().performClick()
            compose.onNodeWithText("Continue to Gradey").performScrollTo().performClick()
        }
        compose.onNodeWithText("Gradey ID").assertExists()
        compose.onNodeWithText("App updates").performScrollTo().performClick()
        compose.waitUntil(10_000) { schoolState().tab == AppTab.UPDATES }
        compose.onNodeWithText("App updates").assertIsDisplayed()
        compose.onNodeWithText("Gradey ${BuildConfig.VERSION_NAME}").assertIsDisplayed()
        assertEquals(AppPhase.SIGNED_OUT, schoolState().phase)

        compose.onNodeWithTag("update-auto-checks").performScrollTo().assertIsOff().performClick()
        compose.waitUntil(10_000) {
            app.updates.state.value.automaticChecks && checkWork().any { it.state == WorkInfo.State.ENQUEUED }
        }
        compose.onNodeWithTag("update-auto-checks").assertIsOn().performClick()
        compose.waitUntil(10_000) {
            !app.updates.state.value.automaticChecks && checkWork().all { it.state.isFinished }
        }
        assertTrue("Turning checks off cancels the periodic task.", checkWork().any { it.state == WorkInfo.State.CANCELLED })

        compose.onNodeWithTag("update-auto-downloads").performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag("update-wifi-only").performScrollTo().assertIsOn().performClick()
        compose.waitUntil(10_000) { app.updates.state.value.automaticDownloads && !app.updates.state.value.wifiOnly }

        // Checks remain off during recreation, so onResume cannot start a network request.
        compose.activityRule.scenario.recreate()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(hasText("App updates"))
        compose.onNodeWithText("App updates").assertIsDisplayed()
        compose.onNodeWithTag("update-auto-checks").performScrollTo().assertIsOff()
        compose.onNodeWithTag("update-auto-downloads").performScrollTo().assertIsOn()
        compose.onNodeWithTag("update-wifi-only").performScrollTo().assertIsOff()
        assertEquals(AppPhase.SIGNED_OUT, schoolState().phase)

        // A fresh manager reads disk preferences rather than the Activity's retained state flow.
        val restored = AppUpdateManager(app).state.value
        assertFalse(restored.automaticChecks)
        assertTrue(restored.automaticDownloads)
        assertFalse(restored.wifiOnly)
        assertTrue(checkWork().all { it.state.isFinished })
        assertEquals("The test must not start a check or download.", baseline.phase, restored.phase)
        assertEquals("The test must not contact the release service.", baseline.lastCheckedAt, restored.lastCheckedAt)
    }

    private fun checkWork(): List<WorkInfo> = WorkManager.getInstance(app)
        .getWorkInfosForUniqueWork(AppUpdateManager.CHECK_WORK).get(10, TimeUnit.SECONDS)

    private fun schoolState(): GradeyState {
        var snapshot: GradeyState? = null
        compose.activityRule.scenario.onActivity { activity ->
            snapshot = ViewModelProvider(activity)[GradeyViewModel::class.java].state.value
        }
        return requireNotNull(snapshot)
    }
}
