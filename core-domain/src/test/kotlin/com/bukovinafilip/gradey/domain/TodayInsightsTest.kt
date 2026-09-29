package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.PlannerLessonReference
import com.bukovinafilip.gradey.model.PlannerSubjectReference
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

class TodayInsightsTest {
    private val now = Instant.parse("2026-09-29T10:00:00Z")
    private val time = now.toEpochMilli()
    private val zone = ZoneId.of("Europe/Prague")
    private val day = SchoolInsightStore.DAY
    private fun item(id: String, type: PlannerItemType = PlannerItemType.TEST, date: String = "2026-09-30", dueTime: String? = null) =
        PersonalPlannerItem(id = id, title = id, type = type, dueDate = date, dueTime = dueTime, timeZoneID = zone.id, updatedAtEpochMillis = time - 1000)
    private fun observation(id: String, subject: String = "math", at: Long = time - 1000, seenAt: Long? = null, previous: Double = 2.0, average: Double = 3.0) =
        SchoolGradeObservation(id, "school", subject, at, at - day, previous, average, listOf("mark"), emptyList(), "digest-$id", seenAt)
    private fun state(observations: List<SchoolGradeObservation> = listOf(observation("change"))) = SchoolInsightState(scope = "school",
        observations = observations, subjects = listOf(SchoolSubjectFact("math", "Mathematics", 3.0, false), SchoolSubjectFact("history", "History", 3.0, false)),
        sources = mapOf("marks" to SchoolSourceFreshness(time)))
    private fun make(state: SchoolInsightState? = state(), items: List<PersonalPlannerItem> = emptyList()) = TodayInsights.make("school", state, items, now, zone)

    @Test fun rankShowsUrgentAssessmentsThenDeadlinesThenObservedChangesWithThreeItemCap() {
        val result = make(items = listOf(item("tomorrow"), item("today", date = "2026-09-29"), item("homework", PlannerItemType.HOMEWORK)))
        assertThat(result.map { it.id }).containsExactly("planner-today", "planner-tomorrow", "planner-homework").inOrder()
        assertThat(result).hasSize(3)
        assertThat(result.first().destination).isEqualTo(TodayInsightDestination.PlannerItem("today"))
        val noHomework = make(items = listOf(item("tomorrow"), item("today", date = "2026-09-29")))
        assertThat(noHomework.last().destination).isEqualTo(TodayInsightDestination.Subject("math"))
    }

    @Test fun plannerProjectionHonorsScopeCompletionConflictsAndSavedDayExpiry() {
        val own = item("own", date = "2026-09-29").copy(subject = PlannerSubjectReference("school", "math", "Mathematics"))
        val conflict = own.copy(id = "conflict", lesson = PlannerLessonReference("other", "lesson", "2026-09-29", "1", hourCaption = "1"))
        val result = make(state = null, items = listOf(own, own.copy(id = "completed", isCompleted = true),
            own.copy(id = "wrong-school", subject = own.subject?.copy(scope = "other")), conflict,
            item("note", PlannerItemType.NOTE), item("undated").copy(dueDate = null),
            item("expired", date = "2026-09-28"), item("expired-time", date = "2026-09-29", dueTime = "11:00"),
            item("personal", PlannerItemType.TASK, "2026-09-29")))
        assertThat(result.map { it.id }).containsExactly("planner-own", "planner-personal").inOrder()
        val savedLosAngeles = item("saved-day", date = "2026-09-28").copy(timeZoneID = "America/Los_Angeles")
        val localLateNight = Instant.parse("2026-09-29T05:00:00Z")
        assertThat(TodayInsights.make("school", null, listOf(savedLosAngeles), localLateNight, zone)).hasSize(1)
    }

    @Test fun staleFutureOrOtherAccountGradesNeverBecomeAttention() {
        val stale = state().copy(sources = mapOf("marks" to SchoolSourceFreshness(time - 300_000)))
        assertThat(make(stale)).isEmpty()
        assertThat(make(state().copy(sources = mapOf("marks" to SchoolSourceFreshness(time, true))))).isEmpty()
        assertThat(make(state().copy(sources = mapOf("marks" to SchoolSourceFreshness(time + 1))))).isEmpty()
        assertThat(make(state().copy(scope = "other"))).isEmpty()
        assertThat(make(state(listOf(observation("future", at = time + 1))))).isEmpty()
        assertThat(make(state(listOf(observation("expired", at = time - 7 * day))))).isEmpty()
        assertThat(make(stale, listOf(item("real-planner"))).single().id).isEqualTo("planner-real-planner")
    }

    @Test fun viewingNeverMarksSeenAndSeenChangesStayStoredButLeaveAttention() {
        val disk = MemorySchoolInsightStorage()
        disk.write(state())
        val store = SchoolInsightStore(disk)
        val before = store.snapshot("school", time)
        assertThat(make(before)).hasSize(1)
        assertThat(store.snapshot("school", time).observations.single().seenAt).isNull()
        store.markSeen("school", make(before).single().observationIDs.toSet(), time)
        val viewed = store.snapshot("school", time)
        assertThat(viewed.observations).hasSize(1)
        assertThat(viewed.observations.single().seenAt).isEqualTo(time)
        assertThat(make(viewed)).isEmpty()
    }

    @Test fun clusteringThreeTestsAvoidsDuplicatingIndividualCards() {
        val later = listOf(item("one", date = "2026-10-01"), item("two", date = "2026-10-02"), item("three", date = "2026-10-03"))
        val clustered = make(state = null, items = later)
        assertThat(clustered.single().kind).isEqualTo(TodayInsightKind.BUSY_TESTS)
        assertThat(clustered.single().destination).isEqualTo(TodayInsightDestination.Planner)
        val withNear = make(state = null, items = later + item("near", date = "2026-09-29"))
        assertThat(withNear.map { it.id }).containsExactly("planner-near")
    }

    @Test fun sameSubjectGradeCardsDeduplicateAndTieBreakDeterministically() {
        val observations = listOf(observation("b"), observation("a"), observation("other", "history"))
        val first = make(state(observations))
        val reversed = make(state(observations.reversed()))
        assertThat(first).isEqualTo(reversed)
        assertThat(first.map { it.id }).containsExactly("grade-a", "grade-other").inOrder()
    }

    @Test fun averageChangeThresholdUsesMagnitudeOrRealHalfGradeCrossing() {
        assertThat(TodayInsights.meaningfulAverageChange(2.0, 2.2)).isTrue()
        assertThat(TodayInsights.meaningfulAverageChange(2.45, 2.55)).isTrue()
        assertThat(TodayInsights.meaningfulAverageChange(2.55, 2.45)).isTrue()
        assertThat(TodayInsights.meaningfulAverageChange(2.01, 2.15)).isFalse()
        assertThat(TodayInsights.meaningfulAverageChange(2.49, 2.51)).isFalse()
        assertThat(TodayInsights.meaningfulAverageChange(null, 3.0)).isFalse()
        assertThat(TodayInsights.meaningfulAverageChange(Double.NaN, 3.0)).isFalse()
    }

    @Test fun worseningTrendRequiresComparableObservationsAndCanRemainAfterGradeTap() {
        val points = listOf(observation("first", at = time - 4 * day, seenAt = time, previous = 1.0, average = 2.0).copy(previousObservedAt = time - 8 * day),
            observation("latest", at = time - day, seenAt = time, previous = 2.0, average = 3.0).copy(previousObservedAt = time - 4 * day))
        val result = make(state(points))
        assertThat(result.single().kind).isEqualTo(TodayInsightKind.WORSENING_TREND)
        assertThat(result.single().observationIDs).isEmpty()
        assertThat(make(state(points.take(1)))).isEmpty()
    }
}
