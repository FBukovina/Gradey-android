package com.bukovinafilip.gradey.feature.subjects

import com.bukovinafilip.gradey.domain.GradeSimulation
import com.bukovinafilip.gradey.model.AbsencePerSubject
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import org.junit.Test

class SubjectsModelTest {
    private val zone = ZoneId.of("Europe/Prague")

    private fun mark(id: String, text: String, date: String?, weight: Double? = 1.0, caption: String? = null, theme: String? = null, typeNote: String? = null, subject: String = "m") =
        Mark(markDate = date, caption = caption, theme = theme, markText = text, typeNote = typeNote, weight = weight, subjectID = subject, id = id)

    private fun subject(id: String, name: String, abbrev: String = "", marks: List<Mark> = emptyList(), averageText: String? = null) =
        Subject(marks = marks, subjectInfo = SubjectInfo(id = id, abbrev = abbrev, name = name), averageText = averageText)

    @Test fun subjectKeyFoldsCaseDiacriticsAndWhitespace() {
        assertThat("  Český   Jazyk ".subjectKey()).isEqualTo("cesky jazyk")
        assertThat("ČJ".subjectKey()).isEqualTo("cj")
    }

    @Test fun absenceMatchesByNameOrAbbreviationAndRejectsAmbiguousRows() {
        val math = subject("m", "Matematika", "M")
        val rows = listOf(AbsencePerSubject("matematika", lessonsCount = 40, base = 4)).map { it.subjectName.subjectKey() to it }
        assertThat(matchingAbsence(math, rows)?.absencePercentage).isWithin(1e-9).of(10.0)

        val ambiguous = rows + (AbsencePerSubject("M", lessonsCount = 10, base = 1).let { it.subjectName.subjectKey() to it })
        assertThat(matchingAbsence(math, ambiguous)).isNull()
    }

    @Test fun entriesCarryAverageAbbreviationLatestMarkAndAbsence() {
        val math = subject(
            "m", "Matematika", "",
            listOf(mark("1", "1", "2026-11-02T00:00:00+01:00"), mark("2", "3", "2026-11-10T00:00:00+01:00")),
        )
        val entry = subjectEntries(listOf(math, math), AbsenceResponse(absencesPerSubject = listOf(AbsencePerSubject("Matematika", 20, 2)))).single()
        assertThat(entry.abbreviation).isEqualTo("MA")
        assertThat(entry.average).isWithin(1e-9).of(2.0)
        assertThat(entry.latestMarkText).isEqualTo("3")
        assertThat(entry.absence?.absencePercentage).isWithin(1e-9).of(10.0)
    }

    @Test fun averageSortPutsBestFirstAndSubjectsWithoutAverageLast() {
        val entries = subjectEntries(
            listOf(
                subject("a", "Biologie", marks = listOf(mark("a1", "3", "2026-11-01", subject = "a"))),
                subject("b", "Angličtina", marks = listOf(mark("b1", "1", "2026-11-01", subject = "b"))),
                subject("c", "Chemie"),
            ),
            AbsenceResponse(),
        )
        val byAverage = sortedEntries(entries, SubjectSortMode.AVERAGE, priorityScores(entries, null, emptyList(), null), Locale.ROOT)
        assertThat(byAverage.map { it.id }).containsExactly("b", "a", "c").inOrder()
        val byPriority = sortedEntries(entries, SubjectSortMode.PRIORITY, priorityScores(entries, null, emptyList(), null), Locale.ROOT)
        assertThat(byPriority.map { it.id }).containsExactly("a", "b", "c").inOrder()
        assertThat(overallAverage(entries)).isWithin(1e-9).of(2.0)
    }

    @Test fun markRowsAreNewestFirstWithTopicDateLabelsAndChips() {
        val math = subject(
            "m", "Matematika",
            marks = listOf(
                mark("old", "2", "2026-09-01T00:00:00+02:00", weight = 2.0, caption = "Zlomky", theme = "Zlomky", typeNote = "Z"),
                mark("new", "1-", "2026-11-17T00:00:00+01:00", caption = "Rovnice", theme = "Lineární rovnice", typeNote = "Písemka"),
                mark("raw", "3", "bad-date", caption = null, theme = "Opakování"),
            ),
        )
        val prepared = GradeSimulation.prepare(math)
        val rows = markEntries(
            math, prepared, Locale.ROOT, "Untitled", zone, today = LocalDate.of(2026, 11, 19),
            formatDate = { "${it.dayOfMonth}.${it.monthValue}." }, formatRecent = { "$it days ago" },
        )
        assertThat(rows.map { it.key }).containsExactly("new", "old", "raw").inOrder()

        val newest = rows[0]
        assertThat(newest.topic).isEqualTo("Lineární rovnice")
        assertThat(newest.typeNote).isEqualTo("Písemka")
        assertThat(newest.recentText).isEqualTo("2 days ago")
        assertThat(newest.dateText).isEqualTo("17.11.")
        assertThat(newest.chip).isNull()

        val older = rows[1]
        assertThat(older.topic).isNull()
        assertThat(older.typeNote).isNull()
        assertThat(older.recentText).isNull()
        assertThat(older.chip).isEqualTo(MarkChip.Weight("2", estimated = false))

        val undated = rows[2]
        assertThat(undated.caption).isEqualTo("Opakování")
        assertThat(undated.dateText).isEqualTo("bad-date")
        assertThat(undated.recentText).isNull()
    }

    @Test fun pointsMarksShowTheirScore() {
        val points = Mark(markDate = "2026-11-01", markText = "8", subjectID = "p", isPoints = true, id = "p1", pointsText = "8", maxPoints = 10)
        val subject = subject("p", "Fyzika", marks = listOf(points))
        val row = markEntries(subject, GradeSimulation.prepare(subject), Locale.ROOT, "Untitled", zone, LocalDate.of(2026, 11, 19), { "" }, { "" }).single()
        assertThat(row.chip).isEqualTo(MarkChip.Points("8/10"))
        assertThat(points.displayText()).isEqualTo("8/10")
    }

    @Test fun chartUsesARunningAverageOnAReversedPaddedAxis() {
        val math = subject(
            "m", "Matematika",
            marks = listOf(
                mark("1", "1", "2026-09-01", weight = 1.0),
                mark("2", "3", "2026-09-01", weight = 1.0),
                mark("3", "5", "2026-10-01", weight = 2.0),
            ),
        )
        val chart = averageChartData(GradeSimulation.prepare(math))!!
        // Two marks on the first day collapse into one sample with that day's closing average.
        assertThat(chart.points.map { it.value }).containsExactly(2.0, 3.5).inOrder()
        assertThat(chart.marks).hasSize(3)
        assertThat(chart.yBest).isWithin(1e-9).of(0.75)
        assertThat(chart.yWorst).isWithin(1e-9).of(5.25)
        assertThat(chart.yTicks).containsExactly(1.0, 2.0, 3.0, 4.0, 5.0).inOrder()
        assertThat(chart.delta).isWithin(1e-9).of(2.5)
        assertThat(chart.xTicks.size).isAtMost(4)
        assertThat(chart.xStart).isLessThan(LocalDate.of(2026, 9, 1).toEpochDay().toDouble())
    }

    @Test fun singleDayChartIsPaddedThreeDaysAndHasNoDelta() {
        val math = subject("m", "Matematika", marks = listOf(mark("1", "2", "2026-09-10")))
        val chart = averageChartData(GradeSimulation.prepare(math))!!
        val day = LocalDate.of(2026, 9, 10).toEpochDay().toDouble()
        assertThat(chart.xStart).isWithin(1e-9).of(day - 3)
        assertThat(chart.xEnd).isWithin(1e-9).of(day + 3)
        assertThat(chart.delta).isNull()
        assertThat(averageChartData(GradeSimulation.prepare(subject("e", "Empty")))).isNull()
    }

    @Test fun narrowRangesFallBackToHalfGradeTicks() {
        assertThat(averageTicks(1.75, 2.45)).containsExactly(2.0).inOrder()
        assertThat(averageTicks(1.6, 2.2)).containsExactly(2.0).inOrder()
        assertThat(averageTicks(1.2, 1.9)).containsExactly(1.5).inOrder()
        assertThat(averageTicks(0.75, 3.25)).containsExactly(1.0, 2.0, 3.0).inOrder()
    }

    @Test fun nearestSamplePicksTheClosestDay() {
        val points = listOf(AverageSample(10, 1.0), AverageSample(20, 2.0), AverageSample(40, 3.0))
        assertThat(nearestSample(points, 14.0)).isEqualTo(0)
        assertThat(nearestSample(points, 16.0)).isEqualTo(1)
        assertThat(nearestSample(points, 100.0)).isEqualTo(2)
        assertThat(nearestSample(emptyList(), 1.0)).isEqualTo(-1)
    }

    @Test fun schoolPredictionNeedsPermissionAndIntegerWeights() {
        val ordinary = subject("m", "Matematika", marks = listOf(mark("1", "2", "2026-09-01", weight = 3.0)))
        assertThat(canAskSchoolPrediction(ordinary, GradeSimulation.prepare(ordinary))).isTrue()

        val disabled = ordinary.copy(markPredictionEnabled = false)
        assertThat(canAskSchoolPrediction(disabled, GradeSimulation.prepare(disabled))).isFalse()

        val fractional = subject("m", "Matematika", marks = listOf(mark("1", "2", "2026-09-01", weight = 0.5)))
        assertThat(canAskSchoolPrediction(fractional, GradeSimulation.prepare(fractional))).isFalse()

        val empty = subject("e", "Empty")
        assertThat(canAskSchoolPrediction(empty, GradeSimulation.prepare(empty))).isFalse()
    }

    @Test fun typeLabelHidesCodesSymbolsAndRepeats() {
        assertThat(typeLabel("X", "Test")).isNull()
        assertThat(typeLabel("%", "Test")).isNull()
        assertThat(typeLabel("test", "Test")).isNull()
        assertThat(typeLabel(" Ústní zkoušení ", "Test")).isEqualTo("Ústní zkoušení")
    }
}
