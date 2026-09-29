package com.bukovinafilip.gradey

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.feature.timetable.TimetableScreen
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.ui.GradeyTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Uses local fixtures only; verifies visible matching and routing, without school/calendar writes. */
class TimetablePlannerItemsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val school = "school-a"
    private val monday = "2026-10-05"
    private val lesson = ScheduledLesson("current-atom", TimetableHour("1", "1", "08:00", "08:45"),
        "Mathematics", "Ma", "Teacher", "T", "12", "Room 12", listOf("Group A"), null, false,
        subjectID = "math-exact", groupIDs = listOf("group-b", "group-a"))
    private val day = ScheduledDay(monday, monday, 1, "", "WorkDay", listOf(lesson), false)

    @Test fun lessonShowsOnlyExactIncompleteItemsAndOpensTheirExistingIDAtLargeFont() {
        val first = linked("homework", "Geometry exercises").let { item -> item.copy(
            lesson = requireNotNull(item.lesson).copy(lessonID = "previous-atom", groupIDs = listOf("group-a", "group-b"), teacher = "Previous teacher"),
            dueDate = "2026-10-07", createdAtEpochMillis = 1,
        ) }
        val second = linked("test", "Geometry test").copy(type = PlannerItemType.TEST, dueDate = "2026-10-08", createdAtEpochMillis = 2)
        val foreign = linked("foreign", "Other school's private work").let { it.copy(
            subject = requireNotNull(it.subject).copy(scope = "school-b"), lesson = requireNotNull(it.lesson).copy(scope = "school-b")) }
        val conflicts = linked("conflict", "Conflicting references").let { it.copy(subject = requireNotNull(it.subject).copy(scope = "school-b")) }
        val wrongDay = linked("day", "Another day").let { it.copy(lesson = requireNotNull(it.lesson).copy(dayKey = "2026-10-06")) }
        val wrongHour = linked("hour", "Another hour").let { it.copy(lesson = requireNotNull(it.lesson).copy(hourID = "2")) }
        val wrongSubject = linked("subject", "Same display name, different ID").let { it.copy(lesson = requireNotNull(it.lesson).copy(subjectID = "different-math")) }
        val wrongGroup = linked("group", "Another group").let { it.copy(lesson = requireNotNull(it.lesson).copy(groupIDs = listOf("group-c"))) }
        val completed = linked("completed", "Already finished").copy(isCompleted = true)
        val rejected = listOf(foreign, conflicts, wrongDay, wrongHour, wrongSubject, wrongGroup, completed)
        var records by mutableStateOf(listOf(first, second) + rejected)
        var opened: String? = null
        compose.setContent {
            GradeyTheme {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.5f)) {
                    Box(Modifier.requiredWidth(320.dp).fillMaxHeight()) {
                        TimetableScreen(TimetableWeek(monday, listOf(day), emptyList()), false,
                            onRefresh = {}, onChangeWeek = {}, onOpenAccount = {}, onOpenGradeyTools = {}, requestedWeek = monday,
                            plannerItems = records, schoolScope = school, onOpenPlannerItem = { opened = it })
                    }
                }
            }
        }
        openLesson()
        compose.onNodeWithText("Planner items for this lesson").assertExists()
        compose.onNodeWithTag("timetable-planner-item-homework").assertExists()
        compose.onNodeWithTag("timetable-planner-item-test").assertExists()
        rejected.forEach { compose.onNodeWithText(it.title).assertDoesNotExist() }
        compose.onNodeWithTag("timetable-planner-item-test").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(second.id, opened) }
        compose.onNodeWithText("Planner items for this lesson").assertDoesNotExist()
        compose.onNodeWithText("Homework +1").assertExists()
        compose.runOnIdle { records = records.map { if (it.id in setOf(first.id, second.id)) it.copy(isCompleted = true) else it } }
        compose.onNodeWithTag("timetable-planner-badge").assertDoesNotExist()
    }

    @Test fun permanentLessonUsesUndatedProviderDayAndNeverBorrowsWeeklyItems() {
        val permanentDay = day.copy(id = "permanent-monday", date = null)
        val permanent = PersonalPlanner.linked(lesson, permanentDay, school).copy(id = "permanent", title = "Recurring lesson context", type = PlannerItemType.NOTE)
        val weekly = linked("weekly", "Dated weekly homework")
        val otherPermanentDay = permanent.copy(id = "tuesday", title = "Different permanent day",
            lesson = requireNotNull(permanent.lesson).copy(dayKey = "permanent-tuesday"))
        var selected: String? = null
        compose.setContent {
            GradeyTheme {
                TimetableScreen(TimetableWeek(monday, listOf(permanentDay), emptyList(), TimetableKind.PERMANENT), false,
                    onRefresh = {}, onChangeWeek = {}, onOpenAccount = {}, onOpenGradeyTools = {}, requestedWeek = monday,
                    kind = TimetableKind.PERMANENT, plannerItems = listOf(weekly, otherPermanentDay, permanent), schoolScope = school,
                    onOpenPlannerItem = { selected = it })
            }
        }
        openLesson()
        compose.onNodeWithText(permanent.title).assertExists()
        compose.onNodeWithText(weekly.title).assertDoesNotExist()
        compose.onNodeWithText(otherPermanentDay.title).assertDoesNotExist()
        assertNull(permanent.linkedDate)
        assertNull(requireNotNull(permanent.lesson).startEpochMillis)
        compose.onNodeWithTag("timetable-planner-item-permanent").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(permanent.id, selected) }
    }

    private fun linked(id: String, title: String) = PersonalPlanner.linked(lesson, day, school).copy(id = id, title = title)

    private fun openLesson() {
        val details = hasContentDescription("Open Mathematics lesson details", substring = true)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)).performScrollToNode(details)
        compose.onNode(details).assertIsDisplayed().performClick()
    }
}
