package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import com.google.common.truth.Truth.assertThat
import java.time.Instant
import java.time.ZoneId
import org.junit.Test

class SubjectDetailInsightsTest {
    private val now = Instant.parse("2026-09-29T10:00:00Z")
    private val subject = Subject(subjectInfo = SubjectInfo("math", "M", "Mathematics"), marks = listOf(
        Mark(id = "old", subjectID = "math", markText = "1", weight = 1.0, markDate = "2026-09-21"),
        Mark(id = "new", subjectID = "math", markText = "5", weight = 2.0, markDate = "2026-09-28")))
    private val day = SchoolInsightStore.DAY
    private fun state(): SchoolInsightState {
        val store = SchoolInsightStore()
        val state = store.observe("school", listOf(subject), now.toEpochMilli())
        val times = (0..2).map { now.toEpochMilli() - (8 - it * 4) * day }
        val history = (1..2).map { index -> SchoolGradeObservation("obs$index", "school", "math", times[index], times[index - 1],
            2.0 + (index - 1) * 0.2, 2.0 + index * 0.2, emptyList(), emptyList(), "digest$index") }
        return state.copy(observations = history)
    }
    private fun item(id: String, date: String = "2026-09-30") = PersonalPlannerItem(id = id, title = id,
        type = PlannerItemType.TEST, dueDate = date, subject = PlannerSubjectReference("school", "math", "Mathematics"), timeZoneID = "Europe/Prague")
    private fun make(state: SchoolInsightState? = state(), items: List<PersonalPlannerItem> = emptyList()) = SubjectDetailInsights.make(subject, "school", state, items, now)

    @Test fun displaysOnlyFreshComparableObservedTrend() {
        assertThat(make().trendDelta).isWithin(0.00001).of(0.4)
        assertThat(make(state().copy(observations = emptyList())).trendDelta).isNull()
        assertThat(make(state().copy(scope = "other")).trendDelta).isNull()
        assertThat(make(state().copy(sources = mapOf("marks" to SchoolSourceFreshness(now.toEpochMilli() - 300_000)))).trendDelta).isNull()
        assertThat(make(state().copy(sources = mapOf("marks" to SchoolSourceFreshness(now.toEpochMilli(), failed = true)))).trendDelta).isNull()
    }
    @Test fun freshTimestampCannotMakeAChangedSubjectSnapshotComparable() {
        val state = state()
        val changed = subject.copy(marks = subject.marks + Mark(id = "later", subjectID = "math", markText = "2", weight = 1.0))
        assertThat(SubjectDetailInsights.make(changed, "school", state, emptyList(), now).marksFresh).isFalse()
    }
    @Test fun contributionUsesSharedPreparedWeightsWithoutInventingObservedHistory() {
        val result = make(null)
        assertThat(result.trendDelta).isNull()
        assertThat(result.recentContribution?.markID).isEqualTo("new")
        assertThat(result.recentContribution!!.reconstructedDelta).isWithin(0.00001).of(8.0 / 3)
        val single = subject.copy(marks = listOf(subject.marks.first()))
        assertThat(SubjectDetailInsights.make(single, "school", null, emptyList(), now).recentContribution).isNull()
    }
    @Test fun contributionIgnoresFutureOldAndUnsupportedMarks() {
        val excluded = subject.copy(marks = listOf(
            subject.marks[0].copy(markDate = "2026-08-01"), subject.marks[1].copy(markDate = "2026-10-01"),
            subject.marks[0].copy(id = "unsupported", markDate = "2026-09-28", type = "unsupported")))
        assertThat(SubjectDetailInsights.make(excluded, "school", null, emptyList(), now).recentContribution).isNull()
    }
    @Test fun eventsMatchExactSchoolAndSubjectBeforeApplyingTwoItemLimit() {
        val own = item("own")
        val records = listOf(item("other-school", "2026-09-29").copy(subject = own.subject?.copy(scope = "other")),
            item("wrong-subject", "2026-09-29").copy(subject = own.subject?.copy(id = "math ")),
            item("unscoped", "2026-09-29").copy(subject = null), own.copy(id = "done", isCompleted = true),
            own.copy(id = "conflict", lesson = PlannerLessonReference("other", "lesson", "day", "hour", hourCaption = "1")),
            item("third", "2026-10-01"), own, item("first", "2026-09-29"))
        assertThat(make(items = records).upcomingEvents.map { it.id }).containsExactly("first", "own").inOrder()
    }
    @Test fun allDayEventsRemainUntilTheirOwnMidnightAndTimedEventsExpireAtDueTime() {
        val allDay = item("all-day", "2026-09-28").copy(timeZoneID = "America/Los_Angeles")
        val localNight = Instant.parse("2026-09-29T05:00:00Z")
        val result = SubjectDetailInsights.make(subject, "school", null, listOf(allDay), localNight)
        assertThat(result.upcomingEvents.single().hasTime).isFalse()
        assertThat(result.upcomingEvents.single().date.atZone(ZoneId.of("America/Los_Angeles")).toLocalDate().toString()).isEqualTo("2026-09-28")
        assertThat(make(items = listOf(allDay, item("timed", "2026-09-29").copy(dueTime = "12:00"))).upcomingEvents).isEmpty()
    }
    @Test fun datedNotesAreAvailableButDoNotBecomeTests() {
        val result = make(items = listOf(item("note").copy(type = PlannerItemType.NOTE)))
        assertThat(result.upcomingEvents.single().type).isEqualTo(PlannerItemType.NOTE)
    }
}
