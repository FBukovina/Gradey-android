package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.Absence
import com.bukovinafilip.gradey.model.AbsencePerSubject
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.RecapArchive
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.bukovinafilip.gradey.model.TimetableHour
import com.bukovinafilip.gradey.model.TimetableWeek
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class RecapTest {
    private fun day(text: String): LocalDate = LocalDate.parse(text)

    private fun mark(id: String, subject: String, date: String, text: String, weight: Double? = 1.0, points: Boolean = false) =
        Mark(markDate = date, markText = text, weight = weight, subjectID = subject, isPoints = points, id = id)

    private fun subject(id: String, name: String, marks: List<Mark>, average: String? = null) =
        Subject(marks = marks, subjectInfo = SubjectInfo(id = id, abbrev = id.uppercase(), name = name), averageText = average)

    // Autumn 2026: maths starts with three ones and ends on a heavy 2; history climbs from a 4 to a 1.
    private val math = subject("m", "Matematika", listOf(
        mark("a1", "m", "2026-09-10T08:00:00+02:00", "1"), mark("a2", "m", "2026-09-17", "1-"),
        mark("a3", "m", "2026-09-24T10:00:00+02:00", "1"), mark("a4", "m", "2026-10-20T09:00:00+02:00", "2", weight = 3.0),
    ))
    private val history = subject("h", "Dějepis", listOf(
        mark("h1", "h", "2026-09-09", "4"), mark("h2", "h", "2026-09-25", "3"),
        mark("h3", "h", "2026-10-21", "2"), mark("h4", "h", "2026-11-05", "1"),
    ))
    private val sport = subject("p", "Tělesná výchova", listOf(
        mark("p1", "p", "2026-10-20", "N", weight = null), mark("p2", "p", "2026-10-20", "18", weight = null, points = true),
    ))
    private val autumnAbsence = AbsenceResponse(
        absences = listOf(
            Absence(date = "2026-09-15T00:00:00+02:00", ok = 6), Absence(date = "2026-10-02T00:00:00+02:00", unsolved = 2),
            Absence(date = "2026-11-20T00:00:00+01:00", ok = 3),
        ),
        absencesPerSubject = listOf(
            AbsencePerSubject(subjectName = "Matematika", lessonsCount = 40, base = 2), AbsencePerSubject(subjectName = " Fyzika ", lessonsCount = 30, base = 6),
        ),
    )
    private val autumn = RecapSemester(ReportCardPeriod(2026, 1), listOf(math, history, sport), autumnAbsence)
    private val lastSpring = ReportCardTerm("2025/26", "2", true, null, 1.8, 20.0, 0.0, day("2026-06-30"), listOf(ReportCardMark("m", "Matematika", "M", "2")))
    private val today = day("2026-11-12")

    @Test fun periodsHaveTheSchoolYearBoundariesAndIdsOfIos() {
        val october = RecapPeriod.month(10, 2026)
        assertThat(october.start to october.end).isEqualTo(day("2026-10-01") to day("2026-11-01"))
        assertThat(RecapPeriod.month(12, 2026).end).isEqualTo(day("2027-01-01"))
        assertThat(RecapPeriod.quarter(1, 2026).let { it.start to it.end }).isEqualTo(day("2026-09-01") to day("2026-11-16"))
        assertThat(RecapPeriod.quarter(3, 2026).let { it.start to it.end }).isEqualTo(day("2027-02-01") to day("2027-04-16"))
        assertThat(RecapPeriod.semester(1, 2026).let { it.start to it.end }).isEqualTo(day("2026-09-01") to day("2027-02-01"))
        assertThat(RecapPeriod.semester(2, 2026).let { it.start to it.end }).isEqualTo(day("2027-02-01") to day("2027-07-01"))
        assertThat(RecapPeriod.calendarYear(2026).let { it.start to it.end }).isEqualTo(day("2026-01-01") to day("2027-01-01"))

        assertThat(listOf(october, RecapPeriod.quarter(1, 2026), RecapPeriod.semester(2, 2026), RecapPeriod.calendarYear(2026)).map { it.id })
            .containsExactly("month-2026-10", "quarter-2026-1", "semester-2026-2", "calendarYear-2026-0").inOrder()
        assertThat(RecapPeriod.month(3, 2027).schoolYearStart).isEqualTo(2026)
        assertThat(october.schoolYearStart).isEqualTo(2026)
        assertThat(RecapPeriod.month(1, 2027).semesters).containsExactly(ReportCardPeriod(2026, 1))
        assertThat(RecapPeriod.calendarYear(2026).semesters)
            .containsExactly(ReportCardPeriod(2025, 1), ReportCardPeriod(2025, 2), ReportCardPeriod(2026, 1)).inOrder()
        assertThat(october.isOngoing(day("2026-10-31"))).isTrue()
        assertThat(october.isOngoing(day("2026-11-01"))).isFalse()
    }

    @Test fun recapsComeOutOnTheirDayAndTodayPointsAtThemForAWhile() {
        assertThat(RecapCalendar.window(RecapPeriod.month(10, 2026))).isEqualTo(RecapCalendar.Window(day("2026-11-01"), day("2026-11-08")))
        assertThat(RecapCalendar.window(RecapPeriod.quarter(1, 2026))).isEqualTo(RecapCalendar.Window(day("2026-11-10"), day("2026-11-25")))
        assertThat(RecapCalendar.window(RecapPeriod.quarter(3, 2026))).isEqualTo(RecapCalendar.Window(day("2027-04-10"), day("2027-04-25")))
        assertThat(RecapCalendar.window(RecapPeriod.semester(1, 2026))).isEqualTo(RecapCalendar.Window(day("2027-01-20"), day("2027-02-04")))
        assertThat(RecapCalendar.window(RecapPeriod.semester(2, 2026))).isEqualTo(RecapCalendar.Window(day("2027-06-15"), day("2027-06-30")))
        assertThat(RecapCalendar.window(RecapPeriod.calendarYear(2026))).isEqualTo(RecapCalendar.Window(day("2026-12-15"), day("2027-01-06")))

        assertThat(RecapCalendar.featured(today).map { it.id }).containsExactly("quarter-2026-1")
        assertThat(RecapCalendar.featured(day("2026-11-09")).map { it.id }).isEmpty()
        assertThat(RecapCalendar.featured(day("2026-11-07")).map { it.id }).containsExactly("month-2026-10")
        assertThat(RecapCalendar.featured(day("2027-01-03")).map { it.id }).containsExactly("calendarYear-2026-0", "month-2026-12").inOrder()

        assertThat(RecapCalendar.listed(today).map { it.id }).containsExactly(
            "quarter-2026-1", "month-2026-10", "month-2026-9", "month-2026-6", "semester-2025-2", "month-2026-5", "month-2026-4", "quarter-2025-3",
            "month-2026-3", "month-2026-2", "month-2026-1", "semester-2025-1", "month-2025-12", "calendarYear-2025-0", "month-2025-11",
            "quarter-2025-1", "month-2025-10", "month-2025-9",
        ).inOrder()
    }

    @Test fun archiveFilesEachSemesterUnderItsKeyAndDropsOldSchoolYears() {
        assertThat(RecapArchives.key(ReportCardPeriod(2026, 1))).isEqualTo("2026/27-1")
        assertThat(RecapArchives.period("2026/27-1")).isEqualTo(ReportCardPeriod(2026, 1))
        assertThat(RecapArchives.period("garbage")).isNull()
        assertThat(RecapArchives.period("2026/27-x")).isNull()

        assertThat(RecapArchives.semesterOfMarks(listOf(math, history))).isEqualTo(ReportCardPeriod(2026, 1))
        val tie = subject("t", "Tie", listOf(mark("t1", "t", "2026-01-20", "1"), mark("t2", "t", "2026-02-03", "1")))
        assertThat(RecapArchives.semesterOfMarks(listOf(tie))).isEqualTo(ReportCardPeriod(2025, 2))
        assertThat(RecapArchives.semesterOfMarks(listOf(subject("e", "Empty", listOf(mark("e1", "e", "", "1")))))).isNull()
        assertThat(RecapArchives.semesterOfAbsence(autumnAbsence)).isEqualTo(ReportCardPeriod(2026, 1))
        assertThat(RecapArchives.semesterOfAbsence(AbsenceResponse())).isNull()

        val marks = MarksResponse(subjects = listOf(math))
        val old = RecapArchive(marks = mapOf("2023/24-2" to marks, "2024/25-1" to marks, "garbage" to marks), absence = mapOf("2023/24-1" to autumnAbsence))
        val updated = RecapArchives.adding(RecapArchives.adding(old, marks, today), autumnAbsence, today)
        assertThat(updated.marks.keys).containsExactly("2024/25-1", "2026/27-1")
        assertThat(updated.absence.keys).containsExactly("2026/27-1")
        // A new semester without dated marks must not replace anything.
        assertThat(RecapArchives.adding(updated, MarksResponse(), today)).isEqualTo(updated)
    }

    @Test fun semesterRecapCountsMarksRanksSubjectsAndFindsTheMoments() {
        val week = TimetableWeek("2026-09-14", listOf(
            schoolDay("d1", "2026-09-14", "WorkDay", lesson("l1", "Fyzika", LessonChangeKind.CANCELED), lesson("l2", "Matematika", LessonChangeKind.SUBSTITUTION), lesson("l3", "Dějepis"),
                lesson("l4", null)),
            schoolDay("d2", "2026-09-19", "Weekend", lesson("l5", "Matematika")),
            schoolDay("d3", "2026-08-31", "WorkDay", lesson("l6", "Matematika")),
        ), emptyList())
        val recap = RecapBuilder.build(
            RecapPeriod.semester(1, 2026),
            RecapInput(listOf(autumn), listOf(lastSpring), timetableWeeks = listOf(week, week), today = today),
        )

        assertThat(recap.isOngoing).isTrue()
        assertThat(recap.hasContent).isTrue()
        assertThat(recap.markCount).isEqualTo(10)
        assertThat(recap.distribution).containsExactly(4, 2, 1, 1, 0).inOrder()
        assertThat(recap.otherMarks).isEqualTo(2)
        assertThat(recap.average!!).isWithin(1e-9).of((9.7 / 6 + 2.5) / 2)
        assertThat(recap.comparison).isEqualTo(RecapComparison(RecapComparison.Basis.PREVIOUS_REPORT_CARD, 1.8))
        assertThat(recap.topSubject!!.name).isEqualTo("Matematika")
        assertThat(recap.topSubject!!.markCount).isEqualTo(4)
        assertThat(recap.toughestSubject!!.name).isEqualTo("Dějepis")
        assertThat(recap.toughestSubject!!.average).isWithin(1e-9).of(2.5)
        // The busiest day is the fourth moment, and only three are kept.
        assertThat(recap.moments).containsExactly(
            RecapMoment.OnesStreak(3), RecapMoment.Comeback("Dějepis", 3.5, 1.5), RecapMoment.HeaviestMark("2", "Matematika", 3.0),
        ).inOrder()
        assertThat(recap.personality).isEqualTo(RecapPersonality.Comeback)
        // The absence on 20 November has not happened yet; the subject comes from the school's own totals.
        assertThat(recap.absence!!.counts.total).isEqualTo(8)
        assertThat(recap.absence!!.counts.unsolved).isEqualTo(2)
        assertThat(recap.absence!!.topSubject).isEqualTo("Fyzika")
        assertThat(recap.absence!!.topSubjectHours).isEqualTo(6)
        // One school day counts once, weekends and days outside the period not at all.
        assertThat(recap.timetable).isEqualTo(RecapTimetable(lessons = 3, canceled = 1, substitutions = 1, topCanceledSubject = "Fyzika"))
        assertThat(recap.reportCard).isEqualTo(RecapReportCard(average = 2.0, isEstimate = true, ones = 0, borderline = 1))
        assertThat(recap.lastYear).isNull()
        assertThat(recap.springFromReportCard).isFalse()
        assertThat(recap.slides).containsExactly(
            RecapSlide.INTRO, RecapSlide.MARKS, RecapSlide.AVERAGE, RecapSlide.TOP_SUBJECT, RecapSlide.TOUGHEST_SUBJECT, RecapSlide.MOMENTS,
            RecapSlide.ABSENCE, RecapSlide.TIMETABLE, RecapSlide.PERSONALITY, RecapSlide.REPORT_CARD, RecapSlide.SUMMARY,
        ).inOrder()
    }

    @Test fun monthRecapComparesWithTheMonthBeforeAndSkipsTheSemesterOnlySlides() {
        val input = RecapInput(listOf(autumn), listOf(lastSpring), today = today)
        val october = RecapBuilder.build(RecapPeriod.month(10, 2026), input)
        assertThat(october.isOngoing).isFalse()
        assertThat(october.markCount).isEqualTo(4)
        assertThat(october.distribution).containsExactly(0, 2, 0, 0, 0).inOrder()
        assertThat(october.average!!).isWithin(1e-9).of(2.0)
        assertThat(october.comparison!!.basis).isEqualTo(RecapComparison.Basis.PREVIOUS_MONTH)
        assertThat(october.comparison!!.average).isWithin(1e-9).of((3.7 / 3 + 3.5) / 2)
        // One mark per subject is not enough to rank anything in a month.
        assertThat(october.topSubject).isNull()
        assertThat(october.personality).isNull()
        assertThat(october.reportCard).isNull()
        assertThat(october.timetable).isNull()
        assertThat(october.absence!!.counts.total).isEqualTo(2)
        assertThat(october.absence!!.topSubject).isNull()
        assertThat(october.slides).containsExactly(RecapSlide.INTRO, RecapSlide.MARKS, RecapSlide.AVERAGE, RecapSlide.ABSENCE, RecapSlide.SUMMARY).inOrder()

        val september = RecapBuilder.build(RecapPeriod.month(9, 2026), input)
        assertThat(september.comparison).isNull()
        assertThat(september.topSubject!!.name).isEqualTo("Matematika")
        assertThat(september.toughestSubject!!.name).isEqualTo("Dějepis")
        // Toughest subject, moments and personality are computed but a month does not show them.
        assertThat(september.slides).containsNoneOf(RecapSlide.TOUGHEST_SUBJECT, RecapSlide.MOMENTS, RecapSlide.PERSONALITY)

        val empty = RecapBuilder.build(RecapPeriod.month(3, 2026), input)
        assertThat(empty.hasContent).isFalse()
        assertThat(empty.absence).isNull()
    }

    @Test fun subjectWhollyInsideThePeriodKeepsTheSchoolsAverageAndDuplicateMarksCountOnce() {
        val official = math.copy(averageText = "1,20")
        val semester = RecapSemester(ReportCardPeriod(2026, 1), listOf(official), null)
        // The live semester and its archived copy overlap; every mark still counts once.
        val recap = RecapBuilder.build(RecapPeriod.semester(1, 2026), RecapInput(listOf(semester, semester.copy()), today = today))
        assertThat(recap.markCount).isEqualTo(4)
        assertThat(recap.average!!).isWithin(1e-9).of(9.7 / 6)

        val single = RecapBuilder.build(RecapPeriod.semester(1, 2026), RecapInput(listOf(semester), today = today))
        assertThat(single.average!!).isWithin(1e-9).of(1.2)
        // September alone is only part of the subject, so the average is worked out from its marks.
        val september = RecapBuilder.build(RecapPeriod.month(9, 2026), RecapInput(listOf(semester), today = today))
        assertThat(september.average!!).isWithin(1e-9).of(3.7 / 3)
    }

    @Test fun closedReportCardReplacesTheEstimate() {
        val closed = ReportCardTerm("2026/27", "1", true, null, null, null, null, null, listOf(
            ReportCardMark("m", "Matematika", "M", "1"), ReportCardMark("h", "Dějepis", "D", "1"), ReportCardMark("p", "Tělesná výchova", "Tv", "2"),
        ))
        val recap = RecapBuilder.build(RecapPeriod.semester(1, 2026), RecapInput(listOf(autumn), listOf(closed), today = day("2027-02-10")))
        assertThat(recap.isOngoing).isFalse()
        assertThat(recap.reportCard!!.isEstimate).isFalse()
        assertThat(recap.reportCard!!.average).isWithin(1e-9).of(4.0 / 3)
        assertThat(recap.reportCard!!.ones).isEqualTo(2)
        // An open report card with some marks written still counts as an estimate.
        val open = RecapBuilder.build(RecapPeriod.semester(1, 2026), RecapInput(listOf(autumn), listOf(closed.copy(isClosed = false)), today = today))
        assertThat(open.reportCard).isEqualTo(RecapReportCard(average = 4.0 / 3, isEstimate = true, ones = 2, borderline = 0))
    }

    @Test fun calendarYearLooksBackAtLastSpringsReportCard() {
        val december = day("2026-12-20")
        val recap = RecapBuilder.build(RecapPeriod.calendarYear(2026), RecapInput(listOf(autumn), listOf(lastSpring), today = december))
        assertThat(recap.comparison).isNull()
        assertThat(recap.reportCard).isNull()
        val lastYear = recap.lastYear!!
        assertThat(lastYear.spring).isEqualTo(ReportCardPeriod(2025, 2))
        assertThat(lastYear.springAverage).isEqualTo(1.8)
        assertThat(lastYear.springAbsentHours).isEqualTo(20.0)
        assertThat(lastYear.autumnAverage!!).isWithin(1e-9).of((9.7 / 6 + 2.5) / 2)
        assertThat(lastYear.autumnAbsentHours).isEqualTo(11)
        assertThat(recap.springFromReportCard).isTrue()
        assertThat(recap.slides).contains(RecapSlide.LAST_YEAR)

        val spring = RecapSemester(ReportCardPeriod(2025, 2), listOf(subject("s", "Fyzika", listOf(mark("s1", "s", "2026-03-03", "3")))), null)
        val withSpring = RecapBuilder.build(RecapPeriod.calendarYear(2026), RecapInput(listOf(autumn, spring), listOf(lastSpring), today = december))
        assertThat(withSpring.springFromReportCard).isFalse()
        assertThat(withSpring.markCount).isEqualTo(11)
        // Without last spring's report card there is nothing to look back at.
        assertThat(RecapBuilder.build(RecapPeriod.calendarYear(2026), RecapInput(listOf(autumn), today = december)).lastYear).isNull()
    }

    @Test fun personalityIsPickedByTheFirstRuleThatFits() {
        fun personality(texts: List<String>, dates: List<String>? = null, today: LocalDate = this.today, second: List<String> = emptyList()): RecapPersonality? {
            val first = subject("a", "Alfa", texts.mapIndexed { index, text -> mark("a$index", "a", dates?.get(index) ?: "2026-09-%02d".format(2 + index), text) })
            val other = subject("b", "Beta", second.mapIndexed { index, text -> mark("b$index", "b", "2026-10-%02d".format(5 + index), text) })
            return RecapBuilder.build(RecapPeriod.semester(1, 2026), RecapInput(listOf(RecapSemester(ReportCardPeriod(2026, 1), listOf(first, other), null)), today = today)).personality
        }
        assertThat(personality(listOf("1", "1", "2", "1"))).isNull()
        assertThat(personality(listOf("1", "1", "2", "1", "1"))).isEqualTo(RecapPersonality.StraightAs)
        assertThat(personality(listOf("1", "5", "1", "5", "3"))).isEqualTo(RecapPersonality.Rollercoaster)
        assertThat(personality(listOf("2", "3", "2", "3", "2"))).isEqualTo(RecapPersonality.Steady)
        // Three of five marks in the last fifth of a finished semester.
        assertThat(personality(listOf("2", "3", "2", "3", "2"), listOf("2026-09-10", "2026-10-10", "2027-01-11", "2027-01-18", "2027-01-25"), today = day("2027-03-01")))
            .isEqualTo(RecapPersonality.LastMinute)
        assertThat(personality(listOf("1", "1", "2"), second = listOf("3", "3", "3"))).isEqualTo(RecapPersonality.Specialty("Alfa"))
    }

    @Test fun demoDataFollowsTheClockAndFillsARecap() {
        val now = ZonedDateTime.of(2026, 11, 12, 16, 0, 0, 0, ZoneId.of("Europe/Prague"))
        val archive = RecapDemoData.archive(now)
        assertThat(archive.marks.keys).containsExactly("2026/27-1")
        val marks = archive.marks.getValue("2026/27-1").subjects.flatMap { it.marks }
        assertThat(marks.map { it.id }).containsNoDuplicates()
        marks.forEach { mark ->
            val date = OffsetDateTime.parse(mark.markDate)
            assertThat(date.toInstant()).isLessThan(now.toInstant().plusSeconds(86_400))
            assertThat(date.dayOfWeek).isNoneOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            assertThat(date.toLocalDate()).isAtLeast(day("2026-09-01"))
        }
        val semesters = archive.marks.map { (key, response) -> RecapSemester(RecapArchives.period(key)!!, response.subjects, archive.absence[key]) }
        val quarter = RecapBuilder.build(RecapPeriod.quarter(1, 2026), RecapInput(semesters, today = now.toLocalDate()))
        assertThat(quarter.markCount).isAtLeast(30)
        assertThat(quarter.otherMarks).isAtLeast(1)
        assertThat(quarter.topSubject).isNotNull()
        assertThat(quarter.toughestSubject).isNotNull()
        assertThat(quarter.moments).isNotEmpty()
        assertThat(quarter.personality).isNotNull()
        assertThat(quarter.absence!!.counts.total).isGreaterThan(0)

        // In spring the autumn semester is there too, the way a device that had the app since September would have it.
        val spring = RecapDemoData.archive(ZonedDateTime.of(2027, 4, 12, 16, 0, 0, 0, ZoneId.of("Europe/Prague")))
        assertThat(spring.marks.keys).containsExactly("2026/27-1", "2026/27-2")
        assertThat(spring.absence.keys).containsExactly("2026/27-1", "2026/27-2")
    }

    private fun lesson(id: String, subject: String?, change: LessonChangeKind = LessonChangeKind.NONE) =
        ScheduledLesson(id, TimetableHour("1", "1", "08:00", "08:45"), subject, subject?.take(2), null, null, null, null, emptyList(), null, false, changeKind = change)

    private fun schoolDay(id: String, date: String, type: String, vararg lessons: ScheduledLesson) =
        ScheduledDay(id, date, LocalDate.parse(date).dayOfWeek.value, "", type, lessons.toList(), false)
}
