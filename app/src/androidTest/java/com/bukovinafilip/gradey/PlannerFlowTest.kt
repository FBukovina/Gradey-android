package com.bukovinafilip.gradey

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.bukovinafilip.gradey.domain.PersonalPlannerRepository
import kotlinx.coroutines.CompletableDeferred
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bukovinafilip.gradey.data.InMemoryPersonalPlannerRepository
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.feature.planner.PlannerRoute
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.ui.GradeyTheme
import java.time.Instant
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Runs the production planner flow against isolated local demo storage; no live accounts involved. */
@RunWith(AndroidJUnit4::class)
class PlannerFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private var latest: List<PersonalPlannerItem> = emptyList()
    private var loaded = false

    @Test fun createEditCompleteReopenAndDeletePersonalItem() {
        val repository = InMemoryPersonalPlannerRepository()
        compose.setContent { GradeyTheme { PlannerRoute(repository, onBack = {}, onItemsChanged = { latest = it; loaded = true }) } }
        compose.waitUntil(5_000) { loaded }
        compose.onNodeWithContentDescription("Add planner item").performClick()
        compose.onNodeWithText("Title").performTextInput("Read chapter three")
        compose.onNodeWithText("Notes").performTextInput("Pages 42 to 55")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { latest.size == 1 }
        compose.onNodeWithText("Read chapter three").assertExists()
        compose.onNodeWithContentDescription("Edit Read chapter three").performClick()
        compose.onNodeWithText("Title").performTextReplacement("Read chapter four")
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { latest.singleOrNull()?.title == "Read chapter four" }
        compose.onNodeWithContentDescription("Mark Read chapter four complete").performClick()
        compose.waitUntil(5_000) { latest.single().isCompleted }
        compose.onNodeWithText("Completed", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Read chapter four").assertExists()
        compose.onNodeWithContentDescription("Edit Read chapter four").performClick()
        compose.onNodeWithTag("plannerEditor").performScrollToNode(hasText("Delete item"))
        compose.onNodeWithText("Delete item").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(5_000) { latest.isEmpty() }
        compose.onNodeWithText("Completed and collected.").assertExists()
    }

    @Test fun nextLessonLookupPreservesContextAndSetsTimedDueDate() {
        val dayDate = LocalDate.now().toString()
        val lesson = ScheduledLesson("demo", TimetableHour("1", "Lesson 1", "08:00", "08:45"), "Mathematics", "M",
            "Teacher", "T", "101", "Room 101", listOf("A"), null, false, subjectID = "math", groupIDs = listOf("A"))
        val day = ScheduledDay(dayDate, dayDate, LocalDate.now().dayOfWeek.value, "", "WorkDay", listOf(lesson), true)
        val draft = PersonalPlanner.linked(lesson, day, "demo-school")
        val next = Instant.parse("2026-10-06T06:00:00Z")
        val repository = InMemoryPersonalPlannerRepository()
        compose.setContent { GradeyTheme { PlannerRoute(repository, onBack = {}, initialItem = draft,
            onFindNextLesson = { next }, onItemsChanged = { latest = it; loaded = true }) } }
        compose.waitUntil(5_000) { loaded }
        compose.onNodeWithText("Title").performTextInput("Math homework")
        compose.onNodeWithTag("plannerEditor").performScrollToNode(hasText("Due at the next lesson"))
        compose.onNodeWithText("Due at the next lesson").performClick()
        compose.onNodeWithText("Due date set to the next matching lesson.").assertExists()
        compose.onNodeWithTag("plannerEditor").performScrollToNode(hasText("Save", substring = false))
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { latest.size == 1 }
        assertEquals("2026-10-06", latest.single().dueDate)
        assertEquals("08:00", latest.single().dueTime)
        assertEquals("math", latest.single().lesson!!.subjectID)
        assertEquals("demo-school", latest.single().subject!!.scope)
    }

    @Test fun incomingLinkPreservesRestoredDraftUntilTheUserChoosesToDiscard() {
        val repository = InMemoryPersonalPlannerRepository()
        val incoming = mutableStateOf<PersonalPlannerItem?>(PersonalPlannerItem(id = "original", title = "Original title"))
        val restoration = StateRestorationTester(compose)
        restoration.setContent { GradeyTheme { PlannerRoute(repository, onBack = {}, initialItem = incoming.value,
            onInitialItemHandled = { incoming.value = null }, onItemsChanged = { latest = it; loaded = true }) } }
        compose.waitUntil(5_000) { loaded }
        compose.onNodeWithText("Title").performTextReplacement("My unsaved edits")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("My unsaved edits").assertExists()
        // A process-restored launch URL can carry a fresh draft ID. It must not replace saved state.
        compose.runOnIdle { incoming.value = PersonalPlannerItem(id = "replayed", title = "Original title") }
        compose.onNodeWithText("Open another planner item?").assertExists()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("My unsaved edits").assertExists()
        assertTrue(latest.isEmpty())
        compose.runOnIdle { incoming.value = PersonalPlannerItem(id = "second", title = "Second draft") }
        compose.onNodeWithText("Discard changes and open").performClick()
        compose.onNodeWithText("Second draft").assertExists()
        compose.onNodeWithText("My unsaved edits").assertDoesNotExist()
        assertTrue(latest.isEmpty())
    }

    @Test fun incomingLinkDuringSuspendedSaveOpensAfterSaveWithoutBeingClearedByItsCallback() {
        val backing = InMemoryPersonalPlannerRepository()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val repository = object : PersonalPlannerRepository by backing {
            override suspend fun save(item: PersonalPlannerItem): List<PersonalPlannerItem> {
                started.complete(Unit)
                finish.await()
                return backing.save(item)
            }
        }
        val incoming = mutableStateOf<PersonalPlannerItem?>(PersonalPlannerItem(id = "first", title = "Saved first"))
        compose.setContent { GradeyTheme { PlannerRoute(repository, onBack = {}, initialItem = incoming.value,
            onInitialItemHandled = { incoming.value = null }, onItemsChanged = { latest = it; loaded = true }) } }
        compose.waitUntil(5_000) { loaded }
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle { incoming.value = PersonalPlannerItem(id = "second", title = "Incoming second") }
        compose.onNodeWithText("Open another planner item?").assertDoesNotExist()
        compose.onNodeWithText("Incoming second").assertDoesNotExist()
        compose.runOnIdle { finish.complete(Unit) }
        compose.waitUntil(5_000) { latest.singleOrNull()?.id == "first" }
        compose.onNodeWithText("Incoming second").assertExists()
        assertEquals("Saved first", latest.single().title)
    }

    @Test fun incomingLinkWaitsForNextLessonLookupAndPreservesItsResolvedDate() {
        val repository = InMemoryPersonalPlannerRepository()
        val started = CompletableDeferred<Unit>()
        val next = CompletableDeferred<Instant?>()
        val original = PersonalPlannerItem(id = "lookup", title = "Linked homework", timeZoneID = "Europe/Prague",
            lesson = PlannerLessonReference("school", "lesson", "2026-10-05", "1", subjectID = "math",
                startEpochMillis = Instant.parse("2026-10-05T06:00:00Z").toEpochMilli(), hourCaption = "1"))
        val incoming = mutableStateOf<PersonalPlannerItem?>(original)
        compose.setContent { GradeyTheme { PlannerRoute(repository, onBack = {}, initialItem = incoming.value,
            onInitialItemHandled = { incoming.value = null }, onFindNextLesson = { started.complete(Unit); next.await() },
            onItemsChanged = { latest = it; loaded = true }) } }
        compose.waitUntil(5_000) { loaded }
        compose.onNodeWithTag("plannerEditor").performScrollToNode(hasText("Due at the next lesson"))
        compose.onNodeWithText("Due at the next lesson").performClick()
        compose.waitUntil(5_000) { started.isCompleted }
        compose.runOnIdle { incoming.value = PersonalPlannerItem(id = "interrupt", title = "Other item") }
        compose.onNodeWithText("Open another planner item?").assertDoesNotExist()
        compose.runOnIdle { next.complete(Instant.parse("2026-10-06T06:00:00Z")) }
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Due date set to the next matching lesson.").assertExists()
        compose.onNodeWithTag("plannerEditor").performScrollToNode(hasText("Save", substring = false))
        compose.onNodeWithText("Save", useUnmergedTree = true).performClick()
        compose.waitUntil(5_000) { latest.singleOrNull()?.id == "lookup" }
        assertEquals("2026-10-06", latest.single().dueDate)
        assertEquals("08:00", latest.single().dueTime)
    }

}
