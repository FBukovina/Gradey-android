package com.bukovinafilip.gradey.feature.today

import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.bukovinafilip.gradey.model.TimetableHour
import com.bukovinafilip.gradey.model.TimetableKind
import com.bukovinafilip.gradey.model.TimetableWeek
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TodayLogicTest {
    private fun lesson(
        id: String, caption: String = "1", begin: String = "08:00", end: String = "08:45",
        name: String? = "Mathematics", abbrev: String? = "M", room: String? = "214",
        change: LessonChangeKind = LessonChangeKind.NONE, description: String? = null,
    ) = ScheduledLesson(id, TimetableHour(caption, caption, begin, end), name, abbrev, "Jana Nováková", "NOV",
        room, null, emptyList(), null, false, changeDescription = description, changeKind = change)

    @Test fun greetingDropsTheBakalariClassSuffix() {
        assertEquals("Novák Jan", nameWithoutClass("Novák Jan, 3.A", "3.A"))
        assertEquals("Novák Jan", nameWithoutClass("Novák Jan, T2.C", null))
        assertEquals("Novák, Jan Petr", nameWithoutClass("Novák, Jan Petr", null))
        assertEquals("Novák Jan", greetingName("  ", null, "Novák Jan, 3.A"))
        assertNull(greetingName(null, null, null))
    }

    @Test fun greetingSlotsMatchIOS() {
        assertEquals(GreetingSlot.EVENING, greetingSlot(4))
        assertEquals(GreetingSlot.MORNING, greetingSlot(5))
        assertEquals(GreetingSlot.AFTERNOON, greetingSlot(12))
        assertEquals(GreetingSlot.EVENING, greetingSlot(18))
    }

    @Test fun nextLessonCountsDownOnlyForTwoHours() {
        val next = lesson("a", begin = "13:00")
        assertEquals(LessonStart.InMinutes(120), lessonStart(next, 120))
        assertEquals(LessonStart.AtTime("13:00"), lessonStart(next, 121))
        assertEquals(LessonStart.InMinutes(300), lessonStart(lesson("b", begin = ""), 300))
        assertNull(lessonStart(lesson("c", begin = ""), null))
        assertEquals(35, minutesBetween(LocalTime.of(8, 10, 30), LocalTime.of(8, 45)))
        assertEquals(0, minutesBetween(LocalTime.of(9, 0), LocalTime.of(8, 45)))
    }

    @Test fun chipStatesFollowTheClock() {
        val now = LocalTime.of(9, 0)
        assertEquals(LessonChipState.PAST, lessonChipState(lesson("a"), null, now))
        assertEquals(LessonChipState.CURRENT, lessonChipState(lesson("b", begin = "08:55", end = "09:40"), "b", now))
        assertEquals(LessonChipState.UPCOMING, lessonChipState(lesson("c", begin = "10:00", end = "10:45"), "b", now))
        assertEquals(LessonChipState.CANCELLED, lessonChipState(lesson("d", change = LessonChangeKind.CANCELED), null, now))
    }

    @Test fun lessonCopyFallsBackLikeIOS() {
        val cancelled = lesson("x", name = null, abbrev = null, room = null, description = "Exkurze do muzea", change = LessonChangeKind.CANCELED)
        assertEquals("Exkurze do muzea", optionalLessonSubject(cancelled))
        assertEquals("Exkurz", shortLessonSubject(cancelled))
        assertEquals("Exkurze do muzea · 1 · 08:00–08:45", changeSubtitle(cancelled))
        assertEquals("Mathematics · 1 · 08:00–08:45 · 214", changeSubtitle(lesson("y")))
        assertNull(lessonTimeRange(lesson("z", end = "")))
        assertEquals("Jana Nováková", lessonTeacher(lesson("t")))
    }

    @Test fun lessonsTodayIsUnknownWithoutAWeeklyTimetable() {
        val today = LocalDate.of(2026, 11, 19)
        val day = ScheduledDay("d", "2026-11-19T00:00:00+01:00", 4, "", "WorkDay",
            listOf(lesson("a"), lesson("b", change = LessonChangeKind.CANCELED), lesson("c")), true)
        val week = TimetableWeek("2026-11-16", listOf(day), emptyList())
        assertEquals(2, activeLessonCount(week, today))
        assertEquals(0, activeLessonCount(week, today.plusDays(2)))
        assertNull(activeLessonCount(week.copy(kind = TimetableKind.PERMANENT), today))
        assertNull(activeLessonCount(null, today))
        assertEquals("timetable-2026-11-16", timetableSourceKey(today))
    }

    @Test fun reconnectPrefersTheActiveAccount() {
        val ok = LinkedSchoolAccount("1", SchoolProvider.BAKALARI, "Jan")
        val other = LinkedSchoolAccount("2", SchoolProvider.BAKALARI, "Eva", status = "action_required")
        val active = LinkedSchoolAccount("3", SchoolProvider.BAKALARI, "Petr", status = "failed")
        assertNull(accountRequiringReconnect(listOf(ok), "1"))
        assertEquals("3", accountRequiringReconnect(listOf(ok, other, active), "3")?.id)
        assertEquals("2", accountRequiringReconnect(listOf(ok, other, active), "1")?.id)
    }

    @Test fun recentMarksAreNewestFirstAndUnique() {
        fun mark(id: String, date: String?, caption: String? = null) = Mark(markDate = date, caption = caption, markText = "1", subjectID = "s", id = id)
        val maths = Subject(listOf(mark("1", "2026-11-10T00:00:00+01:00"), mark("2", "2026-11-18T00:00:00+01:00", "Quiz")), SubjectInfo("m", "M", "Mathematics"))
        val physics = Subject(listOf(mark("3", null), mark("2", "2026-11-18T00:00:00+01:00")), SubjectInfo("p", "F", "Physics"))
        val marks = recentMarks(listOf(maths, physics))
        assertEquals(listOf("2", "1", "3"), marks.map { it.id })
        assertEquals("Quiz", marks.first().detail)
        assertEquals("M", marks[1].detail)
        assertEquals(LocalDate.of(2026, 11, 18), marks.first().date)
        assertNull(marks.last().date)
    }
}
