package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PersonalPlannerTest {
    private val zone = ZoneId.of("Europe/Prague")
    private fun lesson(id: String = "l", subject: String = " math ", groups: List<String> = listOf("a"), time: String = "08:00", canceled: Boolean = false) =
        ScheduledLesson(id, TimetableHour("1", "1", time, "08:45"), "Math", "M", "Teacher", "T", "101", "Room101", groups, null, false,
            changeKind = if (canceled) LessonChangeKind.CANCELED else LessonChangeKind.NONE, subjectID = subject, groupIDs = groups)
    private fun day(date: String, lessons: List<ScheduledLesson>, kind: String = "WorkDay") = ScheduledDay(date, date, LocalDate.parse(date).dayOfWeek.value, "", kind, lessons, false)
    private fun week(vararg days: ScheduledDay) = TimetableWeek("2026-09-28", days.toList(), emptyList())
    private fun linked() = PersonalPlanner.linked(lesson(), day("2026-09-28", listOf(lesson())), "school-a", zone)

    @Test fun `lesson references retain exact provider IDs and ignore teacher room or atom order changes`() {
        val item = linked()
        assertEquals(" math ", item.subject!!.id)
        assertTrue(PersonalPlanner.matches(item.lesson!!, lesson(id = "different").copy(teacherName = "Substitute", roomName = "New room"), day("2026-09-28", emptyList()), "school-a"))
        assertFalse(PersonalPlanner.matches(item.lesson!!, lesson(subject = "math"), day("2026-09-28", emptyList()), "school-a"))
        assertFalse(PersonalPlanner.matches(item.lesson!!, lesson(), day("2026-09-28", emptyList()), "other-school"))
    }

    @Test fun `next lesson skips same occurrence cancelled lessons wrong groups and holidays`() {
        val reference = linked().lesson!!
        val timetable = week(
            day("2026-09-28", listOf(lesson(), lesson(id = "later", groups = listOf("b"), time = "09:00"))),
            day("2026-09-29", listOf(lesson(canceled = true))),
            day("2026-09-30", listOf(lesson()), "Holiday"),
            day("2026-10-01", listOf(lesson(time = "bad"))),
            day("2026-10-02", listOf(lesson())),
        )
        assertEquals(Instant.parse("2026-10-02T06:00:00Z"), PlannerNextLessonSelector.nextDate(reference, listOf(timetable), zone))
        assertNull(PlannerNextLessonSelector.nextDate(reference.copy(startEpochMillis = null), listOf(timetable), zone))
    }

    @Test fun `next lesson accepts an ungrouped lesson but preserves exact subject ID`() {
        val reference = linked().lesson!!
        val timetable = week(day("2026-09-29", listOf(lesson(subject = "math", time = "07:00"), lesson(groups = emptyList()))))
        assertEquals(Instant.parse("2026-09-29T06:00:00Z"), PlannerNextLessonSelector.nextDate(reference, listOf(timetable), zone))
    }

    @Test fun `resolver uses cached failed week and stops when an earlier week is unavailable`() = runTest {
        var loads = 0
        val resolver = PlannerLessonResolver(loadWeek = { loads++; error("offline") }, cachedWeek = { week(day("2026-09-29", listOf(lesson()))) }, currentScope = { "school-a" })
        assertEquals(Instant.parse("2026-09-29T06:00:00Z"), resolver.nextDate(linked()))
        assertEquals(1, loads)
        loads = 0
        val unavailable = PlannerLessonResolver(loadWeek = { loads++; error("offline") }, currentScope = { "school-a" })
        try { unavailable.nextDate(linked()); fail("Expected unavailable timetable") } catch (_: IllegalStateException) { }
        assertEquals(1, loads)
    }

    @Test fun `resolver rejects account changes before publishing a date`() = runTest {
        var scope = "school-a"
        val resolver = PlannerLessonResolver(loadWeek = { scope = "school-b"; week(day("2026-09-29", listOf(lesson()))) }, currentScope = { scope })
        try { resolver.nextDate(linked()); fail("Expected account mismatch") } catch (_: IllegalStateException) { }
    }

    @Test fun `date only entries preserve authored civil date and DST all day end`() {
        val item = PersonalPlannerItem(title = "Test", dueDate = "2026-03-29", timeZoneID = zone.id)
        val event = PlannerCalendarExport.event(item)!!
        assertTrue(event.allDay)
        assertEquals(23 * 60 * 60, java.time.Duration.between(event.start, event.end).seconds.toInt())
        assertEquals(LocalDate.of(2026, 3, 29), PersonalPlanner.orderingDate(item))
        assertFalse(PersonalPlanner.isOverdue(item, Instant.parse("2026-03-29T19:00:00Z")))
        assertTrue(PersonalPlanner.isOverdue(item, Instant.parse("2026-03-30T00:00:00Z")))
    }

    @Test fun `explicit due date overrides linked lesson and next lesson keeps timezone`() {
        val item = linked().copy(title = "Homework", dueDate = "2026-10-02", dueTime = "15:30")
        assertEquals(Instant.parse("2026-10-02T13:30:00Z"), PlannerCalendarExport.event(item)!!.start)
        val next = PersonalPlanner.withDueInstant(item, Instant.parse("2026-10-05T06:00:00Z"))
        assertEquals("2026-10-05", next.dueDate)
        assertEquals("08:00", next.dueTime)
    }

    @Test fun `calendar export escapes injection folds utf8 and emits proper all day event`() {
        val item = PersonalPlannerItem(title = "Š".repeat(60) + "\nEND:VEVENT", notes = "a,b;c\\d", dueDate = "2026-10-01", timeZoneID = zone.id)
        val ics = PlannerCalendarExport.ics(item)!!
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20261001\r\n"))
        assertTrue(ics.contains("DTEND;VALUE=DATE:20261002\r\n"))
        assertTrue(ics.replace("\r\n ", "").contains("\\nEND:VEVENT"))
        assertEquals(1, ics.split("\r\n").count { it == "END:VEVENT" })
        assertTrue(ics.split("\r\n").all { it.toByteArray(Charsets.UTF_8).size <= 75 })
    }

    @Test fun `undated item has no calendar export and invalid due date cannot save`() {
        assertNull(PlannerCalendarExport.event(PersonalPlannerItem(title = "Note")))
        try { PersonalPlanner.validate(PersonalPlannerItem(title = "  ")); fail("Expected title validation") } catch (_: IllegalArgumentException) { }
        try { PersonalPlanner.validate(PersonalPlannerItem(title = "Test", dueDate = "2026-02-31")); fail("Expected date validation") } catch (_: IllegalArgumentException) { }
        try { PersonalPlanner.validate(PersonalPlannerItem(title = "Test", dueTime = "08:00")); fail("Expected date validation") } catch (_: IllegalArgumentException) { }
    }
}
