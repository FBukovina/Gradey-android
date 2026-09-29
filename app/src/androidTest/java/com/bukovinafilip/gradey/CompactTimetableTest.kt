package com.bukovinafilip.gradey

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bukovinafilip.gradey.feature.timetable.TimetableScreen
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.ui.GradeyTheme
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Actual feature layout at the narrow supported width, independent of emulator dimensions. */
@RunWith(AndroidJUnit4::class)
class CompactTimetableTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun enlargedTitlesDoNotOverlapActionsAndLessonsRemainReachable() {
        var plannerOpened = 0
        var weekAsked = 0
        var plannedLesson: String? = null
        val today = LocalDate.now(ZoneId.of("Europe/Prague"))
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        val lesson = ScheduledLesson("physics", TimetableHour("1", "1", "08:00", "08:45"), "Physics", "Ph",
            null, null, null, null, emptyList(), null, false)
        val days = (0L..6L).map { offset ->
            val date = monday.plusDays(offset)
            ScheduledDay(date.toString(), date.toString(), date.dayOfWeek.value, "", "WorkDay", listOf(lesson), true)
        }
        compose.setContent {
            GradeyTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.requiredSize(320.dp, 568.dp)) {
                        TimetableScreen(TimetableWeek(monday.toString(), days, emptyList()), false,
                            onRefresh = {}, onChangeWeek = {}, onOpenAccount = {}, onOpenGradeyTools = {},
                            onChangeKind = {}, onOpenPlanner = { plannerOpened++ }, onAskWeekAI = { weekAsked++ },
                            onPlanLesson = { selected, _ -> plannedLesson = selected.id })
                    }
                }
            }
        }
        val title = compose.onNodeWithText("Timetable").fetchSemanticsNode().boundsInRoot
        for (description in listOf("Refresh timetable", "Open account", "Open Gradey tools")) {
            val action = compose.onNodeWithContentDescription(description).fetchSemanticsNode().boundsInRoot
            assertFalse("Title overlaps $description", title.overlaps(action))
        }
        for (label in listOf("Plan week with AI", "Open Planner")) {
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertTrue("Action text was not measured", layouts.isNotEmpty())
            // FlowRow intrinsic measurement may leave extra paragraph layout width; check
            // the rendered line bounds, with only subpixel rounding tolerance.
            assertTrue("Action $label is clipped or fragmented: ${layouts.map { "${it.lineCount} lines, line=${it.getLineLeft(0)}..${it.getLineRight(0)}, bottom=${it.getLineBottom(0)}, size=${it.size}" }}", layouts.all { it.getLineLeft(0) >= -0.5f && it.getLineRight(0) <= it.size.width + 0.5f && it.getLineBottom(0) <= it.size.height + 0.5f && !it.isLineEllipsized(0) && it.lineCount == 1 })
            compose.onNodeWithText(label).performScrollTo().performClick()
        }
        assertEquals(1, plannerOpened)
        assertEquals(1, weekAsked)
        val lessonButton = hasContentDescription("Open Physics lesson details", substring = true)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
            .performScrollToNode(lessonButton)
        compose.onNode(lessonButton).assertIsDisplayed().performClick()
        compose.onNodeWithText("Add to Planner").performScrollTo().performClick()
        assertEquals("physics", plannedLesson)
    }
}
