package com.bukovinafilip.gradey.wear

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.lifecycle.ViewModelProvider
import com.bukovinafilip.gradey.model.*
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId

/** Synthetic on-device acceptance. No paired phone, provider login or AI generation is used. */
@RunWith(AndroidJUnit4::class)
class WearScreenTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    @Before fun clearBefore() { clear() }
    @After fun clearAfter() { clear() }
    private fun clear() { context.getSharedPreferences("gradey-watch", 0).edit().clear().commit() }

    @Test fun signedOutWatchShowsConnectionInstructions() {
        save(snapshot().copy(isSignedIn = false, aiIdentity = WearAIIdentity("previous-owner", "previous-school")))
        ActivityScenario.launch(WearMainActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.wear_sign_in)).assertIsDisplayed()
            compose.onNodeWithText("MAT").assertDoesNotExist()
        }
    }

    @Test fun currentLessonIsVisibleOnSmallWatch() {
        save(snapshot())
        ActivityScenario.launch(WearMainActivity::class.java).use {
            compose.onNodeWithText("MAT").assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.wear_now)).assertIsDisplayed()
        }
    }

    @Test fun expiredSnapshotCannotDisplayPreviousLesson() {
        val current = snapshot()
        save(current.copy(timetable = current.timetable!!.copy(cachedAtEpochMillis = System.currentTimeMillis() - 8L * 24 * 60 * 60_000)))
        ActivityScenario.launch(WearMainActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.wear_refresh_timetable)).assertIsDisplayed()
            compose.onNodeWithText("MAT").assertDoesNotExist()
        }
    }

    @Test fun watchAIRequiresPhoneIdentityBeforeAcceptingAQuestion() {
        ActivityScenario.launch(WearAIActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.wear_ai_setup)).assertIsDisplayed()
            compose.onNodeWithText(context.getString(R.string.wear_ai_question)).assertIsNotEnabled()
        }
    }

    @Test fun unavailablePhoneAllowsExplicitRecoveryAndSignOutClearsIt() {
        save(snapshot().copy(aiIdentity = WearAIIdentity("synthetic-owner", "synthetic-school")))
        context.getSharedPreferences("gradey-watch", 0).edit().putString("source-node", "never-a-real-phone").commit()
        ActivityScenario.launch(WearAIActivity::class.java).use { scenario ->
            lateinit var model: WearAIViewModel
            scenario.onActivity {
                model = ViewModelProvider(it)[WearAIViewModel::class.java]
                model.draft("Explain fractions")
                model.send()
            }
            compose.waitUntil(timeoutMillis = 25_000) { model.canRetry }
            assertFalse(model.busy)
            scenario.onActivity { model.retry() }
            compose.waitUntil(timeoutMillis = 25_000) { model.canRetry }
            assertTrue(model.question == "Explain fractions")
            save(GradeyWearSyncPayload.signedOut())
            scenario.onActivity { model.refreshIdentity() }
            assertFalse(model.canRetry)
            assertFalse(model.busy)
            assertNull(model.identity)
            compose.onNodeWithText("Explain fractions").assertDoesNotExist()
        }
    }

    private fun save(payload: GradeyWearSyncPayload) {
        context.getSharedPreferences("gradey-watch", 0).edit().putString("snapshot", Json.encodeToString(GradeyWearSyncPayload.serializer(), payload)).commit()
    }
    private fun snapshot(): GradeyWearSyncPayload {
        val now = System.currentTimeMillis()
        val date = Instant.ofEpochMilli(now).atZone(ZoneId.of("Europe/Prague")).toLocalDate()
        val day = date.atStartOfDay(ZoneId.of("Europe/Prague")).toInstant().toEpochMilli()
        return GradeyWearSyncPayload(generatedAtEpochMillis = now, isSignedIn = true,
            timetable = GradeyWearTimetable(date.toString(), now, listOf(GradeyWearTimetableDay(
                id = "synthetic-day", date = date.toString(), dayStartEpochMillis = day, weekdayTitle = "Test", isToday = true, isSchoolDay = true,
                lessons = listOf(GradeyWearTimetableLesson("synthetic-lesson", day, now - 15 * 60_000, now + 15 * 60_000,
                    subjectName = "Mathematics", subjectAbbrev = "MAT", timeRange = "09:00–09:45", room = "A12")),
            ))),
        )
    }
}
