package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GradeMathTest {
    @Test
    fun invalidNumericMarksAndNonfiniteAveragesAreIgnored() {
        listOf("NaN", "Infinity", "-Infinity", "0", "-1", "99", "1e300").forEach { value ->
            assertThat(GradeMath.parseMarkValue(value)).isNull()
            assertThat(GradeMath.parseAverageText(value)).isNull()
        }
        assertThat(GradeMath.parseMarkValue(" 2,5 ")).isEqualTo(2.5)
        assertThat(GradeMath.parseMarkValue("5-")).isEqualTo(5.7)
        assertThat(GradeMath.formattedAverage(Double.NaN)).isEqualTo("-")
        assertThat(GradeMath.band(Double.NaN)).isEqualTo(GradeBand.NEUTRAL)
    }

    @Test
    fun invalidWeightsCannotPoisonAStudentsAverage() {
        val marks = listOf(
            mark("a", "1", Double.NaN),
            mark("b", "3", Double.POSITIVE_INFINITY),
            mark("c", "NaN", 5.0),
            mark("d", "100", 5.0),
        )
        assertThat(GradeMath.weightedAverage(marks)).isEqualTo(2.0)
    }

    @Test
    fun pointsAndUnsupportedGradesNeverEnterLocalGradeAverage() {
        val marks = listOf(
            mark("grade", "2", 1.0),
            mark("points", "5", 10.0).copy(isPoints = true),
            mark("unsupported", "1", 10.0).copy(type = "unsupported"),
        )
        assertThat(GradeMath.weightedAverage(marks)).isEqualTo(2.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun theoreticalAverageRejectsNonfiniteTrialValue() {
        GradeMath.theoreticalAverage(emptyList(), markValue = Double.NaN, weight = 1)
    }

    @Test
    fun theoreticalAverageUsesExplicitWeights() {
        val marks = listOf(
            mark(id = "a", value = "1", weight = 3.0),
            mark(id = "b", value = "3", weight = 1.0),
        )

        val average = GradeMath.theoreticalAverage(
            existingMarks = marks,
            markValue = 2.0,
            weight = 2,
        )

        assertThat(average).isWithin(0.0001).of(1.6667)
    }

    @Test
    fun overallAverageIgnoresUngradedSubjects() {
        val subjects = listOf(
            Subject(subjectInfo = SubjectInfo("math"), averageText = "1.50"),
            Subject(subjectInfo = SubjectInfo("art"), averageText = null, marks = emptyList()),
            Subject(subjectInfo = SubjectInfo("czech"), averageText = "2,50"),
        )

        assertThat(GradeMath.overallAverage(subjects)).isEqualTo(2.0)
    }

    @Test
    fun hiddenWeightsCanBeInferredFromDisplayedAverage() {
        val marks = listOf(
            mark(id = "a", value = "1", weight = 3.0, caption = "test"),
            mark(id = "b", value = "3", weight = null, caption = "oral"),
        )

        val weights = GradeMath.resolvedWeights(marks, matchingAverageText = "1.50")

        assertThat(weights["b"]?.value).isEqualTo(1.0)
    }

    @Test
    fun weightedSubsetUsesTheSubjectsResolvedWeights() {
        val subject = Subject(
            subjectInfo = SubjectInfo("math"),
            averageText = "1.50",
            marks = listOf(
                mark(id = "a", value = "1", weight = 3.0),
                mark(id = "b", value = "3", weight = 1.0),
            ),
        )

        val average = GradeMath.weightedAverage(subject.marks, subject)

        assertThat(average).isWithin(0.0001).of(1.5)
    }

    private fun mark(
        id: String,
        value: String,
        weight: Double?,
        caption: String = "test",
    ) = Mark(
        markText = value,
        weight = weight,
        caption = caption,
        subjectID = "math",
        id = id,
    )
}
