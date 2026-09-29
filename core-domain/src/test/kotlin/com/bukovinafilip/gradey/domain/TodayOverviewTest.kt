package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class TodayOverviewTest {
    private val date = LocalDate.parse("2026-09-29")
    private val now = Instant.parse("2026-09-29T08:20:00Z")
    private fun lesson(id: String, start: String, end: String, change: LessonChangeKind = LessonChangeKind.NONE) =
        ScheduledLesson(id, TimetableHour(start, start, start, end), "Math", "M", "Teacher", null, "12", null, emptyList(), null, false, changeKind = change, subjectID = "math")
    private fun week(lessons: List<ScheduledLesson>) = TimetableWeek("2026-09-28", listOf(
        ScheduledDay("tue", date.toString(), 2, "", "Workday", lessons, false)), emptyList())

    @Test fun stripRetainsCancelledAndPastButSummaryIsActionableAndSkipsUntimed() {
        val past = lesson("past", "8:00", "8:45", LessonChangeKind.ROOM_CHANGED)
        val current = lesson("current", "10:00", "10:45")
        val cancelled = lesson("cancel", "10:50", "11:35", LessonChangeKind.CANCELED)
        val next = lesson("next", "11:45", "12:30", LessonChangeKind.SUBSTITUTION)
        val untimed = lesson("untimed", "", "", LessonChangeKind.ADDED)
        val overview = TodayOverview.timetable(week(listOf(untimed, next, cancelled, current, past)), date, LocalTime.of(10, 20))!!
        assertEquals(listOf("past", "current", "cancel", "next", "untimed"), overview.lessons.map { it.id })
        assertEquals(current, overview.current)
        assertEquals(next, overview.next)
        assertEquals(listOf(cancelled, next), overview.changedLessons)
        assertTrue(overview.untimedLessons)
    }

    @Test fun repeatedAtomsCollapseButDistinctGroupsAndConsecutivePeriodsRemain() {
        val original = lesson("a", "11:00", "11:45", LessonChangeKind.SUBSTITUTION)
        val group = original.copy(id = "group", groupIDs = listOf("groupA"))
        val following = lesson("later", "11:50", "12:35", LessonChangeKind.SUBSTITUTION)
        val result = TodayOverview.timetable(week(listOf(original, original.copy(id = "duplicate"), group, following)), date, LocalTime.NOON.minusHours(2))!!
        assertEquals(listOf("a", "group", "later"), result.changedLessons.map { it.id })
    }

    @Test fun permanentAndWrongDatesNeverPretendToBeTodaysSchedule() {
        val weekly = week(listOf(lesson("a", "10:00", "10:45")))
        assertNull(TodayOverview.timetable(weekly.copy(kind = TimetableKind.PERMANENT), date, LocalTime.NOON))
        assertNull(TodayOverview.timetable(weekly, date.plusDays(1), LocalTime.NOON))
    }

    @Test fun plannerKeepsUndatedAndWholeDayItemsAndIsolatesSchoolReferences() {
        val base = PersonalPlannerItem(title = "Task", timeZoneID = "Europe/Prague")
        val otherSchool = base.copy(id = "other", subject = PlannerSubjectReference("other", "math", "Math"))
        val completed = base.copy(id = "done", isCompleted = true)
        val expired = base.copy(id = "expired", dueDate = "2026-09-28")
        val timedPast = base.copy(id = "timedPast", dueDate = date.toString(), dueTime = "10:19")
        val today = base.copy(id = "today", dueDate = date.toString())
        val future = base.copy(id = "future", dueDate = "2026-09-30")
        val undated = base.copy(id = "undated")
        assertEquals(listOf("today", "future", "undated"), TodayOverview.planner(listOf(otherSchool, completed, expired, timedPast, undated, future, today), "active", now).map { it.id })
    }

    @Test fun conflictingSchoolReferencesAreNotAttributedToEitherAccount() {
        val item = PersonalPlannerItem(title = "Conflicted", subject = PlannerSubjectReference("a", "math", "Math"),
            lesson = PlannerLessonReference("b", "lesson", "day", "hour", hourCaption = "1"))
        assertTrue(TodayOverview.planner(listOf(item), "a", now).isEmpty())
        assertTrue(TodayOverview.planner(listOf(item), "b", now).isEmpty())
    }
}
