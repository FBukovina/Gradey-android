package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import org.junit.Assert.*
import org.junit.Test

class PermanentTimetableTest {
    @Test fun permanentLessonsAreUndatedAndOnlyShowRestrictedCycles() {
        val response = TimetableResponse(
            hours = listOf(TimetableHour("1", "1", "08:00", "08:45")),
            subjects = listOf(TimetableEntity("M", name = "Math", abbrev = "M")),
            cycles = listOf(TimetableEntity("odd", name = "Odd week", abbrev = "O"), TimetableEntity("even", name = "Even week", abbrev = "E")),
            days = listOf(TimetableDayDTO(dayOfWeek = 1, date = "2026-09-28", dayType = "Holiday", dayDescription = "Dated holiday", atoms = listOf(
                TimetableAtom("1", subjectID = "M", cycleIDs = listOf("odd")),
                TimetableAtom("1", subjectID = "M", cycleIDs = listOf("odd", "even")),
            ))),
        )
        val permanent = TimetableMapper.makeWeek(response, "2026-09-28", "2026-09-28", TimetableKind.PERMANENT)
        val day = permanent.days.single()
        assertEquals(TimetableKind.PERMANENT, permanent.kind)
        assertNull(day.date)
        assertFalse(day.isToday)
        assertEquals("dow-1", day.id)
        assertEquals("Workday", day.dayType)
        assertEquals("", day.dayDescription)
        assertEquals(listOf("Odd week"), day.lessons.first().cycles)
        assertTrue(day.lessons.last().cycles.isEmpty())
        val planner = PersonalPlanner.linked(day.lessons.first(), day, "account")
        assertNull(planner.linkedDate)
        assertNull(planner.lesson?.startEpochMillis)
        assertNull(PlannerCalendarExport.event(planner.copy(title = "Homework")))
        val actual = TimetableMapper.makeWeek(response, "2026-09-28", "2026-09-28")
        assertEquals("2026-09-28", actual.days.single().date)
        assertTrue(actual.days.single().isToday)
        assertTrue(actual.days.single().lessons.all { it.cycles.isEmpty() })
    }

    @Test fun demoPermanentRemovesDatedChangesAndHomework() {
        val response = DemoData.permanentTimetableResponse()
        assertTrue(response.days.isNotEmpty())
        assertTrue(response.days.all { it.date.isEmpty() && it.dayDescription.isEmpty() })
        assertTrue(response.days.flatMap { it.atoms }.all { it.change == null && it.theme == null && it.homeworkIDs.isEmpty() })
    }
}
