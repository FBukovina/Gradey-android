package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.AbsenceCounts
import com.bukovinafilip.gradey.model.AbsencePerSubject
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.PlannedAbsence
import com.bukovinafilip.gradey.model.PlannedAbsenceLesson
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.TimetableHour
import com.bukovinafilip.gradey.model.TimetableWeek
import com.google.common.truth.Truth.assertThat
import java.time.ZonedDateTime
import org.junit.Test

class AbsencePlanningTest {
    private val now = ZonedDateTime.parse("2026-09-22T09:00:00+02:00[Europe/Prague]")

    @Test
    fun availableLessonsExcludeCanceledPastAndDuplicateLessons() {
        val upcoming = lesson("upcoming", "10:00")
        val week = week("2026-09-22", listOf(
            lesson("finished", "8:00"),
            lesson("started", "9:00"),
            lesson("canceled", "10:00", LessonChangeKind.CANCELED),
            upcoming,
            upcoming,
        ))

        val available = AbsencePlanning.availableLessons(week, "2026-09-22", now)

        assertThat(available.map { it.id }).containsExactly("2026-09-22|upcoming")
        assertThat(available.single().subjectName).isEqualTo("Mathematics")
    }

    @Test
    fun noLessonsAreInventedForMissingTimetableDates() {
        assertThat(AbsencePlanning.availableLessons(null, "2026-09-23", now)).isEmpty()
        assertThat(AbsencePlanning.availableLessons(week("2026-09-22", listOf(lesson("x", "10:00"))), "2026-09-23", now)).isEmpty()
        assertThat(AbsencePlanning.availableLessons(week("2026-09-21", listOf(lesson("x", "10:00"))), "2026-09-21", now)).isEmpty()
    }

    @Test
    fun savedSnapshotsSurviveWeekChangesButPastLessonsDoNotAffectPrediction() {
        val plans = listOf(
            PlannedAbsence("2026-09-21", listOf(savedLesson("past"))),
            PlannedAbsence("2026-09-22", listOf(savedLesson("started", "8:00"), savedLesson("later", "10:00"))),
            PlannedAbsence("2026-09-23", listOf(savedLesson("future"))),
            PlannedAbsence("invalid", listOf(savedLesson("invalid"))),
        )

        assertThat(AbsencePlanning.upcomingLessons(plans, now).map { it.dateKey })
            .containsExactly("2026-09-22", "2026-09-23").inOrder()
        assertThat(AbsencePlanning.upcomingLessons(plans, now.withZoneSameInstant(java.time.ZoneId.of("America/New_York"))))
            .isEqualTo(AbsencePlanning.upcomingLessons(plans, now))
    }

    @Test
    fun savingDateReplacesExistingSelectionWithoutDuplicatingLessons() {
        val old = PlannedAbsence("2026-09-23", listOf(savedLesson("old")))
        val newLesson = savedLesson("new")
        val updated = AbsencePlanning.upsert(listOf(old), PlannedAbsence(old.date, listOf(newLesson, newLesson)))

        assertThat(updated).containsExactly(PlannedAbsence(old.date, listOf(newLesson)))
        assertThat(AbsencePlanning.upsert(updated, PlannedAbsence(old.date, emptyList()))).isEmpty()
        assertThat(AbsencePlanning.upsert(updated, PlannedAbsence("invalid", listOf(newLesson)))).isEqualTo(updated)
    }

    @Test
    fun predictionAddsBothMissedAndTaughtLessonsAndDetectsThresholdCrossing() {
        val response = AbsenceResponse(percentageThreshold = 0.25, absencesPerSubject = listOf(
            AbsencePerSubject("Mathematics", lessonsCount = 10, base = 2),
        ))
        val candidate = AbsenceLessonCandidate("one", "2026-09-23", "1", "mathematics", " Mathematics ")

        val result = AbsencePrediction.project(AbsenceCounts(ok = 2),
            AbsenceRiskSummary.make(response, response.absencesPerSubject).subjects,
            listOf(candidate, candidate), response.percentageThreshold)

        assertThat(result.addedHours).isEqualTo(1)
        assertThat(result.subjectRows.single().projectedBase).isEqualTo(3)
        assertThat(result.subjectRows.single().projectedLessonsCount).isEqualTo(11)
        assertThat(result.subjectRows.single().projectedPercentage).isWithin(0.001).of(27.272727)
        assertThat(result.subjectRows.single().crossesThreshold).isTrue()
    }

    @Test
    fun unrelatedSubjectDoesNotBorrowAttendanceFromMatchingNameSuffix() {
        val response = AbsenceResponse(absencesPerSubject = listOf(AbsencePerSubject("Advanced Math", 20, 5)))
        val result = AbsencePrediction.project(AbsenceCounts(),
            AbsenceRiskSummary.make(response, response.absencesPerSubject).subjects,
            listOf(AbsenceLessonCandidate("one", "2026-09-23", "1", "math", "Math")), 25.0)

        assertThat(result.subjectRows.single().projectedPercentage).isNull()
        assertThat(result.subjectRows.single().exceedsThreshold).isFalse()
    }

    @Test
    fun missingAttendanceDenominatorDoesNotProduceInventedPercentage() {
        val response = AbsenceResponse(absencesPerSubject = listOf(AbsencePerSubject("Math", 0, 0)))
        val result = AbsencePrediction.project(AbsenceCounts(),
            AbsenceRiskSummary.make(response, response.absencesPerSubject).subjects,
            listOf(AbsenceLessonCandidate("one", "2026-09-23", "1", "math", "Math")), 25.0)

        assertThat(result.subjectRows.single().projectedPercentage).isNull()
    }

    private fun savedLesson(id: String, start: String = "10:00") =
        PlannedAbsenceLesson(id, "1", "mathematics", "Mathematics", "10:00-10:45", start)

    private fun lesson(id: String, start: String, kind: LessonChangeKind = LessonChangeKind.NONE) = ScheduledLesson(
        id = id,
        hour = TimetableHour(id, "1", start, "10:45"),
        subjectName = "Mathematics", subjectAbbrev = "M", teacherName = null, teacherAbbrev = null,
        roomAbbrev = null, roomName = null, groups = emptyList(), theme = null,
        hasHomework = false, changeKind = kind,
    )

    private fun week(date: String, lessons: List<ScheduledLesson>) = TimetableWeek("2026-09-21",
        listOf(ScheduledDay(date, date, 2, "", "WorkDay", lessons, false)), emptyList())
}
