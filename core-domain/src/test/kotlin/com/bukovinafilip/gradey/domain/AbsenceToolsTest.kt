package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.AbsencePerSubject
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.Absence
import com.google.common.truth.Truth.assertThat
import java.time.YearMonth
import org.junit.Test

class AbsenceToolsTest {
    @Test
    fun hundredPercentLimitCannotBeReachedAfterAttendingLessons() {
        val response = AbsenceResponse(percentageThreshold = 100.0,
            absencesPerSubject = listOf(AbsencePerSubject("Math", 20, 5)))

        assertThat(AbsenceRiskSummary.make(response, response.absencesPerSubject).subjects.single().missesUntilLimit).isNull()
    }

    @Test
    fun noTaughtLessonsDoesNotClaimStudentIsAtLimit() {
        val response = AbsenceResponse(percentageThreshold = 25.0,
            absencesPerSubject = listOf(AbsencePerSubject("Math", 0, 0)))
        val subject = AbsenceRiskSummary.make(response, response.absencesPerSubject).subjects.single()

        assertThat(subject.missesUntilLimit).isNull()
        assertThat(subject.level).isEqualTo(AbsenceRiskLevel.UNAVAILABLE)
    }

    @Test
    fun invalidThresholdsDoNotProvideRiskAdvice() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 101.0).forEach { invalid ->
            assertThat(normalizedAbsenceThreshold(invalid)).isNull()
        }
        assertThat(normalizedAbsenceThreshold(0.25)).isEqualTo(25.0)
        assertThat(normalizedAbsenceThreshold(25.0)).isEqualTo(25.0)
    }

    @Test
    fun missingAttendanceCannotHideAHighRiskSubjectOnTheDashboard() {
        val response = AbsenceResponse(percentageThreshold = 25.0, absencesPerSubject = listOf(
            AbsencePerSubject("Missing data", 0, 0),
            AbsencePerSubject("Mathematics", 20, 6),
        ))

        assertThat(AbsenceRiskSummary.make(response, response.absencesPerSubject).subjects.first().subjectName)
            .isEqualTo("Mathematics")
    }

    @Test
    fun exactThresholdIsOverLimit() {
        val response = AbsenceResponse(
            percentageThreshold = 25.0,
            absencesPerSubject = listOf(AbsencePerSubject("Math", lessonsCount = 20, base = 5)),
        )

        val subject = AbsenceRiskSummary.make(response, response.absencesPerSubject).subjects.single()

        assertThat(subject.level).isEqualTo(AbsenceRiskLevel.OVER_LIMIT)
        assertThat(subject.missesUntilLimit).isEqualTo(0)
    }

    @Test
    fun missingThresholdDoesNotGuessRisk() {
        val response = AbsenceResponse(
            percentageThreshold = null,
            absencesPerSubject = listOf(AbsencePerSubject("Biology", lessonsCount = 10, base = 3)),
        )

        val summary = AbsenceRiskSummary.make(response, response.absencesPerSubject)

        assertThat(summary.isThresholdUnavailable).isTrue()
        assertThat(summary.subjects.single().level).isEqualTo(AbsenceRiskLevel.UNAVAILABLE)
    }

    @Test
    fun timelineAggregatesDailyRowsAndMonths() {
        val response = AbsenceResponse(
            absences = listOf(
                Absence(date = "2026-04-07T08:00:00+02:00", ok = 2),
                Absence(date = "2026-04-07T09:00:00+02:00", late = 1),
                Absence(date = "2026-05-04", unsolved = 6),
                Absence(date = "not-a-date", ok = 99),
            ),
        )

        val timeline = AbsenceTimeline.make(response)

        assertThat(timeline.total.total).isEqualTo(9)
        assertThat(timeline.total.ok).isEqualTo(2)
        assertThat(timeline.total.late).isEqualTo(1)
        assertThat(timeline.total.unsolved).isEqualTo(6)
        assertThat(timeline.days).hasSize(2)
        assertThat(timeline.months.map { it.month }).containsExactly(
            YearMonth.of(2026, 4),
            YearMonth.of(2026, 5),
        ).inOrder()
        assertThat(timeline.months.first().counts.total).isEqualTo(3)
    }
}
