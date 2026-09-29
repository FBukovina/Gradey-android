package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import org.junit.Assert.*
import org.junit.Test

class GradeSimulationTest {
    @Test fun `fractional weights preserve calculated baseline independently of school average`() {
        val baseline = GradeSimulation.prepare(subject(listOf(mark("1", 0.5), mark("3", 1.5, "b")), "2,70"))
        assertEquals(2.5, baseline.calculatedAverage!!, 0.000001)
        assertEquals(2.7, baseline.displayAverage!!, 0.000001)
        assertTrue(GradeCalculationIssue.INCONSISTENT_OFFICIAL_AVERAGE in baseline.issues)
        val result = GradeSimulation.simulate(baseline, listOf(HypotheticalGrade(value = 1.0)))!!
        assertEquals(2.0, result.estimatedAverage, 0.000001)
        assertEquals(-0.5, result.difference!!, 0.000001)
    }

    @Test fun `known weights produce exact arithmetic but modifiers are estimates`() {
        val baseline = GradeSimulation.prepare(subject(listOf(mark("1", 1.0), mark("3", 3.0, "b")), "2,50"))
        assertEquals(GradeCalculationConfidence.EXACT, baseline.confidence)
        assertTrue(baseline.issues.isEmpty())
        assertTrue(GradeCalculationIssue.MODIFIER_MAPPING in GradeSimulation.prepare(subject(listOf(mark("1-", 1.0)))).issues)
    }

    @Test fun `invalid weights points and unsupported values cannot poison arithmetic`() {
        val baseline = GradeSimulation.prepare(subject(listOf(
            mark("2", 2.0), mark("5", 0.0, "zero"), mark("1", -1.0, "negative"),
            mark("3", Double.POSITIVE_INFINITY, "infinite"), mark("NaN", 1.0, "nan"),
            mark("85", 1.0, "pointsArtifact"), mark("4", 1.0, "unsupported").copy(type = "unsupported"),
            mark("5", 1.0, "points").copy(isPoints = true),
        )))
        assertEquals(1, baseline.marks.size)
        assertEquals(7, baseline.excludedMarkCount)
        assertEquals(2.0, baseline.calculatedAverage!!, 0.000001)
        assertTrue(GradeCalculationIssue.INVALID_WEIGHTS in baseline.issues)
        assertTrue(GradeCalculationIssue.EXCLUDED_MARKS in baseline.issues)
        assertEquals("-", GradeMath.formattedWeight(Double.POSITIVE_INFINITY))
    }

    @Test fun `identical duplicates count once but conflicting identities are excluded`() {
        val original = mark("2", 3.0)
        val duplicate = GradeSimulation.prepare(subject(listOf(original, original)))
        assertEquals(3.0, duplicate.totalWeight, 0.000001)
        assertEquals(1, duplicate.marks.size)
        val conflicting = GradeSimulation.prepare(subject(listOf(original, mark("5", 3.0), mark("1", 1.0, "safe"))))
        assertEquals(listOf("safe"), conflicting.marks.map { it.id })
        assertTrue(GradeCalculationIssue.CONFLICTING_DUPLICATE_IDS in conflicting.issues)
    }

    @Test fun `revision ignores provider ordering and tracks actual grade edits`() {
        val a = mark("1", 1.0)
        val b = mark("3", 1.0, "b")
        assertEquals(GradeSimulation.revision(subject(listOf(a, b))), GradeSimulation.revision(subject(listOf(b, a))))
        assertNotEquals(GradeSimulation.revision(subject(listOf(a))), GradeSimulation.revision(subject(listOf(a.copy(markText = "2")))))
        val noID = GradeSimulation.prepare(subject(listOf(a.copy(id = ""))))
        assertTrue(noID.marks.single().id.startsWith("local-mark-"))
        assertTrue(GradeCalculationIssue.MISSING_MARK_IDENTIFIERS in noID.issues)
    }

    @Test fun `target solver ranks fewest marks then the least demanding grade`() {
        val baseline = GradeSimulation.prepare(subject(listOf(mark("3", 1.0))))
        val result = GradeSimulation.targetOptions(baseline, 2.6)
        assertEquals(GradeTargetResult.Status.OPTIONS, result.status)
        assertEquals(listOf(2, 1), result.options.map { it.grade })
        assertTrue(result.options.all { it.count == 1 })
    }

    @Test fun `target respects original weight and unrounded boundaries`() {
        val light = GradeSimulation.prepare(subject(listOf(mark("3", 1.0))))
        val heavy = GradeSimulation.prepare(subject(listOf(mark("3", 10.0))))
        assertEquals(1, GradeSimulation.targetOptions(light, 2.49).options.first().count)
        assertEquals(4, GradeSimulation.targetOptions(heavy, 2.49).options.first().count)
        assertEquals(2, GradeSimulation.targetOptions(light, 2.49).options.first { it.grade == 2 }.count)
        val boundary = GradeSimulation.prepare(subject(listOf(mark("2.500000001", 1.0))))
        assertEquals(GradeTargetResult.Status.OPTIONS, GradeSimulation.targetOptions(boundary, 2.5).status)
        assertEquals(GradeTargetResult.Status.ALREADY_REACHED, GradeSimulation.targetOptions(GradeSimulation.prepare(subject(listOf(mark("2.5", 1.0)))), 2.5).status)
    }

    @Test fun `all target options forward verify with fractional and inferred weights`() {
        val baselines = listOf(
            GradeSimulation.prepare(subject(listOf(mark("2", 0.5), mark("4", 1.5, "b")))),
            GradeSimulation.prepare(subject(listOf(mark("1", 1.0), mark("5", null, "b").copy(typeNote = "Test")), "3.67")),
        )
        baselines.forEach { baseline ->
            val originalWeights = baseline.resolvedWeights
            listOf(1.49, 2.49, 2.5, 3.49).forEach { target ->
                listOf(1, 3, 10).forEach { weight ->
                    GradeSimulation.targetOptions(baseline, target, weight).options.forEach { option ->
                        val result = GradeSimulation.simulate(baseline, List(option.count) { HypotheticalGrade(value = option.grade.toDouble(), weight = option.weight) })!!
                        assertEquals(option.estimatedAverage, result.estimatedAverage, 0.0)
                        assertTrue(result.estimatedAverage <= target)
                        assertEquals(originalWeights, baseline.resolvedWeights)
                    }
                }
            }
        }
    }

    @Test fun `unavailable unreachable and invalid targets remain distinct`() {
        assertEquals(GradeTargetResult.Status.UNAVAILABLE, GradeSimulation.targetOptions(GradeSimulation.prepare(subject(emptyList(), "2.7")), 2.49).status)
        val hard = GradeSimulation.prepare(subject(listOf(mark("5", 100.0))))
        assertEquals(GradeTargetResult.Status.UNREACHABLE, GradeSimulation.targetOptions(hard, 1.0).status)
        assertEquals(GradeTargetResult.Status.INVALID_TARGET, GradeSimulation.targetOptions(hard, Double.NaN).status)
        assertEquals(GradeTargetResult.Status.INVALID_TARGET, GradeSimulation.targetOptions(hard, 2.49, 0).status)
    }

    @Test fun `simulation enforces limits and does not mutate baseline`() {
        val baseline = GradeSimulation.prepare(subject(listOf(mark("3", 2.0))))
        val result = GradeSimulation.simulate(baseline, listOf(HypotheticalGrade(value = 1.0, weight = 2), HypotheticalGrade(value = 2.0)))!!
        assertEquals(2.0, result.estimatedAverage, 0.000001)
        assertEquals(2.0, baseline.totalWeight, 0.0)
        assertNull(GradeSimulation.simulate(baseline, List(11) { HypotheticalGrade() }))
        assertNull(GradeSimulation.simulate(baseline, listOf(HypotheticalGrade(value = Double.NaN))))
        assertNull(GradeSimulation.simulate(baseline, listOf(HypotheticalGrade(weight = 0))))
        val repeated = HypotheticalGrade()
        assertNull(GradeSimulation.simulate(baseline, listOf(repeated, repeated)))
    }

    @Test fun `contribution uses the fixed inferred weights`() {
        val baseline = GradeSimulation.prepare(subject(listOf(mark("1", 1.0), mark("5", null, "b").copy(typeNote = "Large test")), "3.67"))
        assertEquals(2.0, baseline.resolvedWeights.getValue("b").value, 0.0)
        assertEquals(1.0, baseline.averageWithout("b")!!, 0.0)
        assertEquals(8.0 / 3.0, baseline.impact("b")!!, 0.000001)
        assertNull(baseline.impact("missing"))
    }

    @Test fun `malformed official average never overrides calculated values`() {
        listOf("NaN", "inf", "-infinity", " ", "2 3", "2\t3").forEach { text ->
            val baseline = GradeSimulation.prepare(subject(listOf(mark("3", 1.0)), text))
            assertNull(baseline.officialAverage)
            assertEquals(3.0, baseline.displayAverage!!, 0.0)
        }
        assertEquals(2.5, GradeMath.parseAverageText(" \n2,50\t")!!, 0.0)
    }

    private fun subject(marks: List<Mark>, average: String? = null) = Subject(marks, SubjectInfo("math", "M", "Mathematics"), average)
    private fun mark(value: String, weight: Double?, id: String = "a") = Mark(markDate = "2026-09-10T10:00:00+02:00", markText = value,
        type = "grade", weight = weight, subjectID = "math", id = id)
}
