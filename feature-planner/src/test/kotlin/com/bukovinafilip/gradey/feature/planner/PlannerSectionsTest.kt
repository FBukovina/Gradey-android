package com.bukovinafilip.gradey.feature.planner

import com.bukovinafilip.gradey.domain.Homework
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

    @Test fun schoolHomeworkJoinsItsDueDayAheadOfTheUsersOwnItems() {
        fun homework(id: String, due: String?, done: Boolean = false, closed: Boolean = false) =
            Homework(id, "math", "Mathematics", "M", null, "Task $id", null, null, due?.let(LocalDate::parse), done, closed, false, emptyList())
        val items = listOf(
            PersonalPlannerItem(id = "own-tomorrow", title = "Read", dueDate = "2026-10-18"),
            PersonalPlannerItem(id = "own-undated", title = "Someday"),
        )
        val school = listOf(
            homework("hw-tomorrow", "2026-10-18"), homework("hw-next-week", "2026-10-21"), homework("hw-undated", null),
            homework("hw-done", "2026-10-16", done = true), homework("hw-closed", "2026-10-20", closed = true),
        )
        val upcoming = plannerSections(items, completed = false, filterDay = null, labels = labels, today = today, homework = school)
        // A day with only homework still gets its section; closed homework is in neither list.
        assertEquals(listOf("Tomorrow", "Wednesday 21. 10.", "No date"), upcoming.map { it.title })
        assertEquals(listOf("hw-tomorrow"), upcoming[0].homework.map { it.id })
        assertEquals(listOf("own-tomorrow"), upcoming[0].rows.map { it.item.id })
        assertEquals(listOf("hw-next-week"), upcoming[1].homework.map { it.id })
        assertTrue(upcoming[1].rows.isEmpty())
        assertEquals(listOf("hw-undated"), upcoming[2].homework.map { it.id })

        val completed = plannerSections(items, completed = true, filterDay = null, labels = labels, today = today, homework = school)
        assertEquals(listOf("hw-done"), completed.flatMap { it.homework }.map { it.id })
        assertEquals("Friday 16. 10.", completed.single().title)

        val focused = plannerSections(items, completed = false, filterDay = "2026-10-18", labels = labels, today = today, homework = school)
        assertEquals(listOf("hw-tomorrow"), focused.single().homework.map { it.id })
        assertEquals(listOf("own-tomorrow"), focused.single().rows.map { it.item.id })
    }

    @Test fun homeworkLabelsNameTheDueDayAndFallBackWhenThereIsNoText() {
        val homeworkLabels = HomeworkLabels("School homework", "Due today", "Due tomorrow", "Due %s", Locale.ENGLISH)
        fun homework(due: String?, content: String = "Workbook p. 34\nmore", subject: String? = "Mathematics") =
            Homework("h", "math", subject, "M", null, content, null, null, due?.let(LocalDate::parse), false, false, false, emptyList())
        assertEquals("Due today", homeworkLabels.due(homework("2026-10-17"), today))
        assertEquals("Due tomorrow", homeworkLabels.due(homework("2026-10-18"), today))
        assertEquals("Due Wed 21. 10.", homeworkLabels.due(homework("2026-10-21"), today))
        assertEquals("Due Fri 1. 1. 2027", homeworkLabels.due(homework("2027-01-01"), today))
        // Overdue homework names its day too.
        assertEquals("Due Thu 15. 10.", homeworkLabels.due(homework("2026-10-15"), today))
        assertNull(homeworkLabels.due(homework(null), today))
        assertEquals("Workbook p. 34", homeworkLabels.title(homework("2026-10-17")))
        assertEquals("Mathematics", homeworkLabels.title(homework("2026-10-17", content = "")))
        assertEquals("School homework", homeworkLabels.title(homework("2026-10-17", content = "", subject = null)))
        // Czech abbreviates weekdays without a trailing dot before the date.
        assertEquals("Odevzdat st 21. 10.", HomeworkLabels("Úkol ze školy", "Odevzdat dnes", "Odevzdat zítra", "Odevzdat %s", Locale.forLanguageTag("cs")).due(homework("2026-10-21"), today))
    }

    @Test fun datesOutsideTheCurrentYearShowTheYear() {
        assertEquals("Friday 1. 1. 2027", dayTitle(LocalDate.of(2027, 1, 1), today, labels))
    }
}
