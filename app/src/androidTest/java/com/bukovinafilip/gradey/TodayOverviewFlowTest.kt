package com.bukovinafilip.gradey

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bukovinafilip.gradey.feature.today.TodayScreen
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.ui.GradeyTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Production dashboard components use fixture data and callbacks only; no live service is called. */
@RunWith(AndroidJUnit4::class)
class TodayOverviewFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun largeTextPlannerAndAIActionsOpenTheirExplicitDestinations() {
        var selectedPlanner: String? = null
        var priorities = 0
        var weeks = 0
        var timetables = 0
        val date = LocalDate.now(ZoneId.of("Europe/Prague")).toString()
        val cancelled = ScheduledLesson("cancelled", TimetableHour("1", "1", "08:00", "08:45"), "Math", "M",
            null, null, null, null, emptyList(), null, false, changeKind = LessonChangeKind.CANCELED)
        val timetable = TimetableWeek(date, listOf(ScheduledDay(date, date, 2, "", "WorkDay", listOf(cancelled), true)), emptyList())
        compose.setContent {
            GradeyTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                    TodayScreen(DashboardData(MarksResponse()), AbsenceResponse(), timetable, false,
                        onRefresh = {}, onOpenAccount = {}, onOpenGradeyTools = {}, onOpenMarks = {}, onOpenAbsence = {},
                        onOpenTimetable = { timetables++ }, plannerItems = listOf(PersonalPlannerItem(id = "personal", title = "Read the next chapter")),
                        onOpenPlanner = {}, onOpenPlannerItem = { selectedPlanner = it },
                        onStudyPrioritiesAI = { priorities++ }, onWeekSummaryAI = { weeks++ })
                }
            }
        }
        compose.onNodeWithTag("todayScroll").performScrollToNode(hasTestTag("today-lesson-strip"))
        compose.onNodeWithTag("today-lesson-cancelled").performClick()
        assertEquals(1, timetables)
        compose.onNodeWithTag("todayScroll").performScrollToNode(hasTestTag("today-study-priorities"))
        compose.onNodeWithTag("today-study-priorities").performClick()
        compose.onNodeWithTag("todayScroll").performScrollToNode(hasTestTag("today-week-summary"))
        compose.onNodeWithTag("today-week-summary").performClick()
        assertEquals(1, priorities)
        assertEquals(1, weeks)
        compose.onNodeWithTag("todayScroll").performScrollToNode(hasTestTag("today-planner-personal"))
        compose.onNodeWithText("Read the next chapter").assertIsDisplayed()
        compose.onNodeWithTag("today-planner-personal").performClick()
        assertEquals("personal", selectedPlanner)
    }
}
