package com.bukovinafilip.gradey.feature.timetable

import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.TimetableHour
import com.bukovinafilip.gradey.model.TimetableKind
import com.bukovinafilip.gradey.model.TimetableWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TimetableLessonTextTest {
    private val monday = LocalDate.of(2026, 10, 5)

    private fun lesson(
        id: String,
        caption: String = "1",
        begin: String = "08:00",
        end: String = "08:45",
        name: String? = "Mathematics",
        abbrev: String? = "M",
        room: String? = "214",
        teacher: String? = "Jana Nováková",
        change: LessonChangeKind = LessonChangeKind.NONE,
        description: String? = null,
    ) = ScheduledLesson(
        id, TimetableHour(caption, caption, begin, end), name, abbrev, teacher, "NOV", room, null,
        emptyList(), null, false, changeDescription = description, changeKind = change, subjectID = "math",
    )

    private fun day(date: LocalDate?, dayOfWeek: Int, lessons: List<ScheduledLesson> = emptyList(), type: String = "WorkDay") =
        ScheduledDay(date?.toString() ?: "permanent-$dayOfWeek", date?.toString(), dayOfWeek, "", type, lessons, false)

    @Test fun schoolTimesParseOnlyValidClockValues() {
        assertEquals(LocalTime.of(8, 0), "8:00".asSchoolTime())
        assertEquals(LocalTime.of(14, 5), " 14:05 ".asSchoolTime())
        assertNull("24:00".asSchoolTime())
        assertNull("8.00".asSchoolTime())
        assertNull("".asSchoolTime())
    }

    @Test fun lessonsSortByStartWithUntimedLessonsLast() {
        val day = day(monday, 1, listOf(lesson("c", begin = ""), lesson("b", begin = "9:50"), lesson("a", begin = "8:00")))
        assertEquals(listOf("a", "b", "c"), day.sortedLessons().map { it.id })
        assertTrue((null as ScheduledDay?).sortedLessons().isEmpty())
    }

    @Test fun cancelledLessonIsNeverCurrent() {
        val now = LocalTime.of(8, 20)
        assertTrue(lesson("on").isCurrentAt(now))
        assertTrue(lesson("edge").isCurrentAt(LocalTime.of(8, 45)))
        assertFalse(lesson("later", begin = "9:00", end = "9:45").isCurrentAt(now))
        assertFalse(lesson("off", change = LessonChangeKind.CANCELED).isCurrentAt(now))
        assertFalse(lesson("untimed", begin = "", end = "").isCurrentAt(now))
    }

    @Test fun subjectFallsBackToAbbreviationThenUnknown() {
        assertEquals("Mathematics", lesson("a").subjectTitle("Lesson"))
        assertEquals("M", lesson("b", name = " ").subjectTitle("Lesson"))
        assertEquals("Lesson", lesson("c", name = null, abbrev = null).subjectTitle("Lesson"))
        assertEquals("M", lesson("d").tileText)
        assertNull(lesson("e", name = null, abbrev = "").tileText)
    }

    @Test fun changeNoteHidesRestatementsButCancelledKeepsIt() {
        val restated = lesson("room", room = "Aula", change = LessonChangeKind.ROOM_CHANGED, description = "Změna učebny: Aula.")
        assertNull(restated.distinctChangeNote("Změna učebny"))
        val chipOnly = lesson("sub", change = LessonChangeKind.SUBSTITUTION, description = "Suplování.")
        assertNull(chipOnly.distinctChangeNote("Suplování"))
        val distinct = lesson("sub2", change = LessonChangeKind.SUBSTITUTION, description = "Spojeno s 3.B")
        assertEquals("Spojeno s 3.B", distinct.distinctChangeNote("Suplování"))
        val cancelled = lesson("off", change = LessonChangeKind.CANCELED, description = "Odpadá: exkurze")
        assertEquals("Odpadá: exkurze", cancelled.distinctChangeNote("Odpadá"))
        assertNull(lesson("plain", description = "ignored").changeNote())
    }

    @Test fun daySlotsMatchDatesOrPermanentWeekdays() {
        val dated = TimetableWeek(monday.toString(), listOf(day(monday.plusDays(2), 3, listOf(lesson("w")))), emptyList())
        val slots = timetableDaySlots(dated, monday)
        assertEquals(5, slots.size)
        assertEquals("w", slots[2].day?.lessons?.single()?.id)
        assertNull(slots[0].day)

        val permanent = TimetableWeek(monday.toString(), listOf(day(null, 2, listOf(lesson("p")))), emptyList(), TimetableKind.PERMANENT)
        assertEquals("p", timetableDaySlots(permanent, monday)[1].day?.lessons?.single()?.id)

        val withWeekend = TimetableWeek(monday.toString(), listOf(day(monday.plusDays(5), 6, type = "Weekend")), emptyList())
        assertEquals(7, timetableDaySlots(withWeekend, monday).size)
    }

    @Test fun initialDayPrefersTodayThenFirstSchoolDay() {
        val week = TimetableWeek(
            monday.toString(),
            listOf(day(monday, 1, type = "Holiday"), day(monday.plusDays(1), 2, listOf(lesson("t")))),
            emptyList(),
        )
        val slots = timetableDaySlots(week, monday)
        assertEquals(monday.plusDays(3), initialDayDate(slots, monday.plusDays(3), monday, isPermanent = false))
        assertEquals(monday.plusDays(1), initialDayDate(slots, monday.plusDays(3), monday, isPermanent = true))
        assertEquals(monday.plusDays(1), initialDayDate(slots, monday.plusDays(10), monday, isPermanent = false))
        assertEquals(EmptyDayKind.NO_SCHOOL, slots[0].day.emptyDayKind())
        assertTrue(slots[1].day?.isSchoolDay == true)
    }

    @Test fun plannerItemsAttachOnlyToTheirOpenLessonInScope() {
        val lesson = lesson("atom")
        val day = day(monday, 1, listOf(lesson))
        val linked = PersonalPlanner.linked(lesson, day, "school-a").copy(id = "linked", title = "Exercises")
        val done = linked.copy(id = "done", isCompleted = true)
        val elsewhere = PersonalPlanner.linked(lesson, day, "school-b").copy(id = "elsewhere")
        val items = listOf(done, elsewhere, linked)
        assertEquals(listOf("linked"), attachedPlannerItems(items, lesson, day, "school-a").map { it.id })
        assertTrue(attachedPlannerItems(items, lesson, day, null).isEmpty())
        assertTrue(attachedPlannerItems(items, lesson, null, "school-a").isEmpty())
    }
}
