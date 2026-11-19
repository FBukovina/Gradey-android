package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.CertificateTermDTO
import com.bukovinafilip.gradey.model.FinalMarkDTO
import com.bukovinafilip.gradey.model.FinalMarkSubjectDTO
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class ReportCardTest {
    private fun subject(id: String, name: String, abbrev: String, average: String) =
        Subject(marks = emptyList(), subjectInfo = SubjectInfo(id = id, abbrev = abbrev, name = name), averageText = average)

    @Test fun periodFollowsTheCzechSchoolYear() {
        assertThat(ReportCardPeriod.containing(LocalDate.of(2026, 9, 1))).isEqualTo(ReportCardPeriod(2026, 1))
        assertThat(ReportCardPeriod.containing(LocalDate.of(2027, 1, 31))).isEqualTo(ReportCardPeriod(2026, 1))
        assertThat(ReportCardPeriod.containing(LocalDate.of(2027, 2, 1))).isEqualTo(ReportCardPeriod(2026, 2))
        assertThat(ReportCardPeriod.containing(LocalDate.of(2027, 8, 31))).isEqualTo(ReportCardPeriod(2026, 2))
        assertThat(ReportCardPeriod(2026, 1).schoolYear).isEqualTo("2026/27")
        assertThat(ReportCardPeriod(2099, 2).schoolYear).isEqualTo("2099/00")
    }

    @Test fun averageRoundsToTheMarkAtTheHalf() {
        assertThat(ReportCardEstimate.mark(1.0)).isEqualTo(1)
        assertThat(ReportCardEstimate.mark(1.5)).isEqualTo(1)
        assertThat(ReportCardEstimate.mark(1.51)).isEqualTo(2)
        assertThat(ReportCardEstimate.mark(4.5)).isEqualTo(4)
        assertThat(ReportCardEstimate.mark(4.6)).isEqualTo(5)
        assertThat(ReportCardEstimate.mark(0.2)).isEqualTo(1)
        assertThat(ReportCardEstimate.mark(5.7)).isEqualTo(5)
    }

    @Test fun borderlineWarnsWithinATenthOfTheNextMark() {
        assertThat(ReportCardEstimate.borderline(1.45, 1)).isEqualTo(ReportCardEstimate.Borderline(2, 1.5 - 1.45))
        assertThat(ReportCardEstimate.borderline(1.4, 1)?.mark).isEqualTo(2)
        assertThat(ReportCardEstimate.borderline(1.39, 1)).isNull()
        assertThat(ReportCardEstimate.borderline(1.55, 2)?.mark).isEqualTo(1)
        assertThat(ReportCardEstimate.borderline(2.0, 2)).isNull()
        assertThat(ReportCardEstimate.borderline(4.95, 5)).isNull()
        assertThat(ReportCardEstimate.borderline(4.55, 5)?.mark).isEqualTo(4)
    }

    @Test fun writtenMarkReplacesTheEstimateAndItsWarning() {
        val math = subject("M", "Matematika", "M", "1,45")
        val czech = subject("CJ", "Český jazyk", "ČJ", "2,60")
        val term = ReportCardTerm("2026/27", "1", true, null, null, null, null, null, listOf(ReportCardMark("other-id", "matematika", "x", "2")))
        val estimates = ReportCardEstimate.estimates(listOf(math, czech), emptyMap(), term)
        assertThat(estimates[0].writtenMarkText).isEqualTo("2")
        assertThat(estimates[0].borderline).isNull()
        assertThat(estimates[0].markValue).isEqualTo(2.0)
        assertThat(estimates[1].writtenMarkText).isNull()
        assertThat(estimates[1].estimatedMark).isEqualTo(3)
        assertThat(estimates[1].borderline?.mark).isEqualTo(2)
        assertThat(ReportCardEstimate.average(estimates)).isEqualTo(2.5)
        assertThat(ReportCardEstimate.average(emptyList())).isNull()
    }

    @Test fun termKeepsSubjectOrderAndAddsMarksWithoutASubject() {
        val dto = CertificateTermDTO(
            finalMarks = listOf(FinalMarkDTO(subjectID = "b", markText = " 2 "), FinalMarkDTO(subjectID = "a", markText = "1"),
                FinalMarkDTO(subjectID = "a", markText = "5"), FinalMarkDTO(subjectID = "z", markText = "3"), FinalMarkDTO(subjectID = "empty", markText = " ")),
            subjects = listOf(FinalMarkSubjectDTO("a", " M ", "Matematika"), FinalMarkSubjectDTO("b", null, " "), FinalMarkSubjectDTO("empty", "E", "Empty")),
            schoolYear = " 2025/26 ", semester = "2.", closed = true, achievementText = "  ", marksAverage = 0.0, certificateDate = "2026-06-30T00:00:00+02:00",
        )
        val term = ReportCardTerm.from(dto)
        assertThat(term.id).isEqualTo("2025/26-2")
        assertThat(term.marks.map { it.subjectID to it.markText }).containsExactly("a" to "1", "b" to "2", "z" to "3").inOrder()
        assertThat(term.marks[0].subjectAbbrev).isEqualTo("M")
        assertThat(term.marks[1].subjectName).isEqualTo("b")
        assertThat(term.achievementText).isNull()
        assertThat(term.average).isNull()
        assertThat(term.issuedOn).isEqualTo(LocalDate.of(2026, 6, 30))
        assertThat(term.matches(ReportCardPeriod(2025, 2))).isTrue()
        assertThat(term.matches(ReportCardPeriod(2025, 1))).isFalse()
    }

    @Test fun termsSortNewestFirst() {
        fun term(year: String, semester: String) = ReportCardTerm(year, semester, true, null, null, null, null, null, emptyList())
        val sorted = ReportCardTerm.sorted(listOf(term("2024/25", "2"), term("2025/26", "1"), term("2025/26", "2"), term("2024/25", "1")))
        assertThat(sorted.map { it.id }).containsExactly("2025/26-2", "2025/26-1", "2024/25-2", "2024/25-1").inOrder()
    }
}
