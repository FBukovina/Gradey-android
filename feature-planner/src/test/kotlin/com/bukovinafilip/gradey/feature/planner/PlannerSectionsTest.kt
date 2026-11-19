package com.bukovinafilip.gradey.feature.planner

import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.PlannerSubjectReference
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerSectionsTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val labels = PlannerLabels(
        today = "Today", tomorrow = "Tomorrow", noDate = "No date",
        types = PlannerItemType.entries.associateWith { it.name.lowercase().replaceFirstChar(Char::titlecase) },
        locale = Locale.ENGLISH, is24Hour = true,
    )

    @Test fun groupsUpcomingItemsByDayWithIOSTitles() {
        val items = listOf(
            PersonalPlannerItem(id = "later", title = "Essay", dueDate = "2026-10-19", dueTime = "14:00", type = PlannerItemType.TASK),
            PersonalPlannerItem(id = "undated", title = "Someday"),
            PersonalPlannerItem(id = "tomorrow", title = "Read", dueDate = "2026-10-18"),
            PersonalPlannerItem(id = "today", title = "Quiz", dueDate = "2026-10-17", type = PlannerItemType.TEST,
                subject = PlannerSubjectReference("school", "math", "Mathematics", "M")),
            PersonalPlannerItem(id = "done", title = "Finished", dueDate = "2026-10-17", isCompleted = true),
        )
        val sections = plannerSections(items, completed = false, filterDay = null, labels = labels, today = today)
        assertEquals(listOf("Today", "Tomorrow", "Monday 19. 10.", "No date"), sections.map { it.title })
        val quiz = sections.first().rows.single()
        assertEquals("Test", quiz.typeTitle)
        assertEquals("M", quiz.subject)
        assertEquals("17. 10.", quiz.schedule)
        assertEquals("19. 10. 14:00", sections[2].rows.single().schedule)
        assertNull(sections.last().rows.single().schedule)
        assertTrue(sections.flatMap { it.rows }.none { it.item.isCompleted })
    }

    @Test fun completedFilterAndDayFilterNarrowTheList() {
        val items = listOf(
            PersonalPlannerItem(id = "a", title = "A", dueDate = "2026-10-17", isCompleted = true),
            PersonalPlannerItem(id = "b", title = "B", dueDate = "2026-10-18", isCompleted = true),
            PersonalPlannerItem(id = "c", title = "C", dueDate = "2026-10-18"),
        )
        val completed = plannerSections(items, completed = true, filterDay = "2026-10-18", labels = labels, today = today)
        assertEquals(listOf("b"), completed.flatMap { section -> section.rows.map { it.item.id } })
    }

    @Test fun datesOutsideTheCurrentYearShowTheYear() {
        assertEquals("Friday 1. 1. 2027", dayTitle(LocalDate.of(2027, 1, 1), today, labels))
    }
}
