package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.Absence
import com.bukovinafilip.gradey.model.AbsenceDayOverride
import com.bukovinafilip.gradey.model.AbsenceOverrideAllocation
import com.bukovinafilip.gradey.model.AbsenceOverrideCategory
import com.bukovinafilip.gradey.model.AbsenceOverridePauseReason
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.bukovinafilip.gradey.model.TimetableHour
import com.bukovinafilip.gradey.model.TimetableWeek
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class AbsenceDayLessonsTest {
    private fun lesson(id: String, hour: String, subject: String?, change: LessonChangeKind = LessonChangeKind.NONE, subjectID: String? = subject) =
        ScheduledLesson(id, TimetableHour(hour, hour, "0$hour:00", "0$hour:45"), subject, subject?.take(2), null, null, null, null, emptyList(), null, false,
            changeKind = change, subjectID = subjectID)

    private val tuesday = ScheduledDay("d", "2026-10-06T00:00:00+02:00", 2, "", "WorkDay", listOf(
        lesson("l3", "3", "Fyzika"), lesson("l1", "1", "Matematika"), lesson("l2", "2", "Dějepis", LessonChangeKind.CANCELED),
        lesson("l1b", "1", "Matematika"), lesson("l4", "4", null), lesson("l5", "5", "Biologie"),
    ), false)
    private val week = TimetableWeek("2026-10-05", listOf(tuesday), emptyList())
    private fun absent(date: String, ok: Int = 0, unsolved: Int = 0, late: Int = 0) = Absence(date = date, ok = ok, unsolved = unsolved, late = late)

    @Test fun countableLessonsSkipCancelledUnnamedAndRepeatedOnesAndFollowTheHours() {
        assertThat(AbsenceDayLessonResolver.countableLessons(week, "2026-10-06")!!.map { it.id }).containsExactly("l1", "l3", "l5").inOrder()
        assertThat(AbsenceDayLessonResolver.countableLessons(week, "2026-10-07")).isNull()
    }

    @Test fun wholeDayListsEveryLessonAndAPartDayAsksWhichOnes() {
        val lessons = AbsenceDayLessonResolver.countableLessons(week, "2026-10-06")
        val whole = AbsenceDayLessonResolver.resolve(absent("2026-10-06T00:00:00+02:00", ok = 2, unsolved = 1), lessons)!!
        assertThat(whole.status).isEqualTo(AbsenceDayLessons.Status.Resolved)
        assertThat(whole.lessons.map { it.subjectName }).containsExactly("Matematika", "Fyzika", "Biologie").inOrder()
        assertThat(whole.lessons.first().timeRange).isEqualTo("01:00-01:45")

        val part = AbsenceDayLessonResolver.resolve(absent("2026-10-06", ok = 2), lessons)!!
        assertThat(part.status).isEqualTo(AbsenceDayLessons.Status.NeedsSelection(2))
        assertThat(part.lessons).isEmpty()
        val chosen = AbsenceDayLessonResolver.resolve(absent("2026-10-06", ok = 2), lessons, selectedLessonIDs = setOf("l1", "l5"))!!
        assertThat(chosen.lessons.map { it.subjectName }).containsExactly("Matematika", "Biologie").inOrder()

        // A late arrival misses no lesson; without a timetable nothing can be said.
        assertThat(AbsenceDayLessonResolver.resolve(absent("2026-10-06", late = 1), lessons)!!.lessons).isEmpty()
        assertThat(AbsenceDayLessonResolver.resolve(absent("2026-10-06", ok = 2), null)).isNull()
        assertThat(AbsenceDayLessonResolver.resolve(absent("2026-10-06", ok = 2), emptyList())).isNull()
    }

    @Test fun aSavedCorrectionNamesItsLessonsAndMarksTheRemovedOnes() {
        val day = absent("2026-10-06", ok = 3)
        val allocations = listOf(
            AbsenceOverrideAllocation(id = "a-free", subjectName = "Chemie", category = AbsenceOverrideCategory.OK),
            AbsenceOverrideAllocation(id = "a-bio", lessonID = "l5", subjectName = "Biologie", category = AbsenceOverrideCategory.OK),
            AbsenceOverrideAllocation(id = "a-math", lessonID = "l1", subjectName = "Matematika", category = AbsenceOverrideCategory.OK),
        )
        val saved = AbsenceDayOverride("scope", "2026-10-06", day, allocations, hiddenAllocationIDs = setOf("a-bio"))
        val resolved = AbsenceDayLessonResolver.resolve(day, AbsenceDayLessonResolver.countableLessons(week, "2026-10-06"), activeOverride = saved)!!
        // Lessons with a known hour come first, in order; the one without follows.
        assertThat(resolved.lessons.map { it.subjectName to it.isHidden }).containsExactly("Matematika" to false, "Biologie" to true, "Chemie" to false).inOrder()
        // The correction still answers when the week's timetable is gone.
        assertThat(AbsenceDayLessonResolver.resolve(day, null, activeOverride = saved)!!.lessons.map { it.subjectName })
            .containsExactly("Chemie", "Biologie", "Matematika").inOrder()

        val raw = AbsenceResponse(absences = listOf(day, absent("2026-10-06", ok = 1), absent("2026-11-03", ok = 1)))
        assertThat(AbsenceDayLessonResolver.weekStarts(raw)).containsExactly(LocalDate.parse("2026-10-05"), LocalDate.parse("2026-11-02")).inOrder()
        val all = AbsenceDayLessonResolver.resolveAll(raw, listOf(week), listOf(saved))
        assertThat(all.keys).containsExactly("2026-10-06")
        assertThat(all.getValue("2026-10-06").lessons).hasSize(3)
        // A paused correction is ignored, like on the totals.
        val paused = AbsenceDayLessonResolver.resolveAll(raw, listOf(week), listOf(saved.copy(pauseReason = AbsenceOverridePauseReason.entries.first())))
        assertThat(paused.getValue("2026-10-06").lessons.map { it.isHidden }).containsExactly(false, false, false)
    }

    @Test fun aMonthRecapNamesTheSubjectMissedMostLeavingOutRemovedLessons() {
        val subject = Subject(marks = listOf(Mark(markDate = "2026-10-08", markText = "1", subjectID = "m", id = "m1")), subjectInfo = SubjectInfo("m", "M", "Matematika"))
        val absence = AbsenceResponse(absences = listOf(absent("2026-10-06", ok = 3), absent("2026-10-13", ok = 2), absent("2026-11-03", ok = 3)))
        fun day(key: String, vararg lessons: Pair<String, Boolean>) = key to AbsenceDayLessons(key, AbsenceDayLessons.Status.Resolved,
            lessons.mapIndexed { index, (name, hidden) -> AbsenceDayLesson("$key-$index", "${index + 1}", "", name, hidden) })
        val lessons = mapOf(
            day("2026-10-06", "Fyzika" to false, "Matematika" to false, "Biologie" to true),
            day("2026-10-13", "Fyzika" to false, "Biologie" to true),
            day("2026-11-03", "Biologie" to false, "Biologie" to false, "Biologie" to false),
        )
        val semester = RecapSemester(ReportCardPeriod(2026, 1), listOf(subject), absence)
        val today = LocalDate.parse("2026-11-12")
        val october = RecapBuilder.build(RecapPeriod.month(10, 2026), RecapInput(listOf(semester), today = today, absenceLessons = lessons))
        assertThat(october.absence!!.topSubject).isEqualTo("Fyzika")
        assertThat(october.absence!!.topSubjectHours).isEqualTo(2)
        // Without the day lessons the slide still shows the hours, just no subject.
        assertThat(RecapBuilder.build(RecapPeriod.month(10, 2026), RecapInput(listOf(semester), today = today)).absence!!.topSubject).isNull()
    }
}
