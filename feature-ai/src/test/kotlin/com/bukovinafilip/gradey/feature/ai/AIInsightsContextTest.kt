package com.bukovinafilip.gradey.feature.ai

import com.bukovinafilip.gradey.domain.SchoolGradeObservation
import com.bukovinafilip.gradey.domain.SchoolInsightState
import com.bukovinafilip.gradey.domain.SchoolSourceFreshness
import com.bukovinafilip.gradey.domain.SchoolSubjectFact
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.LocalDate
import org.junit.Test

class AIInsightsContextTest {
    private val now = Instant.parse("2026-09-29T10:00:00Z").toEpochMilli()
    private fun observation(id: String, subject: String = "math", at: Long = now, seen: Long? = null) = SchoolGradeObservation(
        id, "school", subject, at, at - 86_400_000, 2.0, 3.0, listOf("mark-id"), emptyList(), "digest", seen,
    )
    private fun source(insights: List<Map<String, Any?>>): Map<String, Any?> = mapOf(
        "schoolScope" to "scope", "generatedAt" to now, "isStale" to false, "unavailableSections" to emptyList<String>(),
        "subjects" to listOf(mapOf("id" to "math"), mapOf("id" to "other")), "events" to emptyList<Any>(),
        "timetable" to emptyList<Any>(), "trends" to emptyList<Any>(), "insights" to insights,
    )

    @Test fun observedFactsIncludeSeenChangesWithoutClaimingUnprovenTrend() {
        val state = SchoolInsightState(scope = "school", observations = listOf(observation("first", seen = now)),
            subjects = listOf(SchoolSubjectFact("math", "Mathematics", 3.0, false)))
        val result = AIInsightsContext.insights(state, now)
        assertThat(result.first()["kind"]).isEqualTo("observed_grade_change")
        assertThat(result.first()["summary"]).isEqualTo("Observed 1 added and 0 edited grades. Recorded average: 2.0 to 3.0.")
        assertThat(result.first()["isEstimated"]).isEqualTo(false)
        assertThat(result.last()["summary"].toString()).contains("Not enough comparable observations for a trend.")
        assertThat(result.toString()).doesNotContain("mark-id")
    }

    @Test fun subjectSelectionAppliesLimitAfterFilteringUnrelatedObservations() {
        val unrelated = (1..8).map { observation("unrelated-$it", "other", now - it) }
        val state = SchoolInsightState(scope = "school", observations = unrelated + observation("selected", at = now - 20),
            subjects = listOf(SchoolSubjectFact("math", "Mathematics", 3.0, false)))
        val selected = AIContext.select(source(AIInsightsContext.insights(state, now)), AISelection(AIAction.SUBJECT, subjectID = "math"), LocalDate.of(2026, 9, 29))
        val insights = selected["insights"] as List<*>
        assertThat(insights.map { it.objectMap()["id"] }).containsExactly("selected", "subject-summary-math")
        assertThat(insights.toString()).doesNotContain("unrelated")
    }

    @Test fun selectedWeekUsesObservedTimeAndExcludesSummariesAndOtherWeeks() {
        val state = SchoolInsightState(scope = "school", observations = listOf(observation("current"), observation("old", at = now - 14 * 86_400_000)),
            subjects = listOf(SchoolSubjectFact("math", "Mathematics", 3.0, false)))
        val selected = AIContext.select(source(AIInsightsContext.insights(state, now)), AISelection(AIAction.WEEK, weekStart = "2026-09-14"), LocalDate.of(2026, 9, 29))
        assertThat((selected["insights"] as List<*>).map { it.objectMap()["id"] }).containsExactly("old")
    }

    @Test fun failedAndMissingSourceFreshnessIsHonestAndOnlySelectedSectionsAreShared() {
        val state = SchoolInsightState(scope = "school", sources = mapOf(
            "marks" to SchoolSourceFreshness(now - 100, failed = true), "timetable-2026-09-28" to SchoolSourceFreshness(now - 50),
        ))
        val freshness = AIInsightsContext.freshness(state, "2026-09-28", true, now)
        assertThat(freshness.first { it["section"] == "marks" }["isStale"]).isEqualTo(true)
        assertThat(freshness.first { it["section"] == "marks" }["fetchedAt"]).isEqualTo(now - 100)
        assertThat(freshness.first { it["section"] == "trends" }["fetchedAt"]).isNull()
        val raw = source(emptyList()) + ("sourceFreshness" to freshness)
        val general = AIContext.select(raw, AISelection(), LocalDate.of(2026, 9, 29))
        assertThat(general["sourceFreshness"] as List<*>).isEmpty()
        assertThat(general["isStale"]).isEqualTo(false)
        val tomorrow = AIContext.select(raw, AISelection(AIAction.TOMORROW), LocalDate.of(2026, 9, 29))
        assertThat((tomorrow["sourceFreshness"] as List<*>).map { it.objectMap()["section"] }).containsExactly("timetable", "events")
        assertThat(tomorrow["isStale"]).isEqualTo(false)
        val subject = AIContext.select(raw, AISelection(AIAction.SUBJECT, subjectID = "math"), LocalDate.of(2026, 9, 29))
        assertThat(subject["isStale"]).isEqualTo(true)
    }
}
