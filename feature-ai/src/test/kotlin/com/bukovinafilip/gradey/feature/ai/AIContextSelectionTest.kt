package com.bukovinafilip.gradey.feature.ai

import com.bukovinafilip.gradey.domain.GradeHistoryTrend
import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.PlannerLessonReference
import com.bukovinafilip.gradey.model.PlannerSubjectReference
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class AIContextSelectionTest {
    private val today = LocalDate.of(2026, 9, 29)

    @Test fun generalChatStripsEverySchoolSectionIncludingNotes() {
        val selected = AIContext.select(source(), AISelection(), today)
        listOf("subjects", "trends", "timetable", "events", "insights").forEach { assertThat(selected[it] as List<*>).isEmpty() }
        assertThat(selected["unavailableSections"] as List<*>).isEmpty()
        assertThat(selected.toString()).doesNotContain("private notes")
    }

    @Test fun selectedTestSharesOnlyItsSubjectAndExplicitlySelectedNotes() {
        val selection = AISelection(AIAction.TEST, eventID = "test")
        val withoutNotes = AIContext.select(source(), selection, today)
        assertThat((withoutNotes["subjects"] as List<*>).map { it.objectMap()["id"] }).containsExactly("math")
        assertThat((withoutNotes["events"] as List<*>).map { it.objectMap()["id"] }).containsExactly("test")
        assertThat(withoutNotes.toString()).doesNotContain("private notes")
        assertThat(withoutNotes["timetable"] as List<*>).isEmpty()
        assertThat(withoutNotes["trends"] as List<*>).isEmpty()
        val withNotes = AIContext.select(source(), selection.copy(includeNotes = true), today)
        assertThat((withNotes["events"] as List<*>).single().objectMap()["notes"]).isEqualTo("private notes for test")
        assertThat(withNotes.toString()).doesNotContain("private notes for other event")
        assertThat(selection.id).isNotEqualTo(selection.copy(includeNotes = true).id)
    }

    @Test fun tomorrowKeepsOnlyTomorrowWithoutMarksOrNotes() {
        val selected = AIContext.select(source(), AISelection(AIAction.TOMORROW), today)
        assertThat((selected["timetable"] as List<*>).map { it.objectMap()["id"] }).containsExactly("tomorrow")
        assertThat((selected["events"] as List<*>).map { it.objectMap()["id"] }).containsExactly("test")
        assertThat(selected["subjects"] as List<*>).isEmpty()
        assertThat(selected.toString()).doesNotContain("private notes")
    }

    @Test fun prioritiesSendAtMostThreeSubjectSummariesWithoutRecentMarks() {
        val selected = AIContext.select(source() + ("subjects" to (1..6).map { mapOf("id" to "s$it", "average" to it.toDouble(), "recentMarks" to listOf("record")) }),
            AISelection(AIAction.PRIORITIES), today)
        val subjects = (selected["subjects"] as List<*>).map { it.objectMap() }
        assertThat(subjects).hasSize(3)
        assertThat(subjects.all { (it["recentMarks"] as List<*>).isEmpty() }).isTrue()
    }

    @Test fun prioritiesIncludeSubjectsWithUpcomingPlannerWorkBeforeWorstAverages() {
        val subjects = (1..5).map { mapOf("id" to "s$it", "average" to it.toDouble()) }
        val events = listOf(mapOf("id" to "soon", "subjectID" to "s1", "date" to "2026-09-30"),
            mapOf("id" to "past", "subjectID" to "s2", "date" to "2026-09-28"),
            mapOf("id" to "unknown", "subjectID" to "missing", "date" to "2026-09-30"))
        val selected = AIContext.select(source() + mapOf("subjects" to subjects, "events" to events), AISelection(AIAction.PRIORITIES), today)
        assertThat((selected["subjects"] as List<*>).map { it.objectMap()["id"] }).containsExactly("s1", "s4", "s5")
    }

    @Test fun plannerProjectionRejectsOtherSchoolsConflictingScopesAndCompletedItems() {
        val reference = PlannerSubjectReference("mine", "math", "Mathematics")
        val own = PersonalPlannerItem(id = "own", title = "Fractions", type = PlannerItemType.TEST, subject = reference, dueDate = "2026-09-30", timeZoneID = "Europe/Prague")
        val lesson = PlannerLessonReference("other", "lesson", "2026-09-30", "1", hourCaption = "1")
        val events = AIPlannerContext.events(listOf(own, own.copy(id = "completed", isCompleted = true),
            own.copy(id = "other", subject = reference.copy(scope = "other")), own.copy(id = "conflict", lesson = lesson),
            own.copy(id = "personal", subject = null), own.copy(id = "undated", dueDate = null)), "mine")
        assertThat(events.map { it.id }).containsExactly("own", "personal")
        assertThat(events.first().hasTime).isFalse()
        assertThat(events.first().date).startsWith("2026-09-30T00:00")
    }

    @Test fun trendBaselineComesFromHistoryNotCurrentDashboard() {
        val dashboard = DashboardData(MarksResponse(listOf(Subject(subjectInfo = SubjectInfo("math", "M", "Mathematics"), averageText = "4.5"))))
        val history = listOf(GradeHistoryTrend("math", "Mathematics", 0.2, 2, firstAverage = 1.5, latestAverage = 1.7, firstMarkCount = 5, latestMarkCount = 7))
        val context = AIContext.build("scope", dashboard, emptyList(), true, false, history = history)
        val trend = (context["trends"] as List<*>).single().objectMap()
        assertThat(trend["firstAverage"]).isEqualTo(1.5)
        assertThat(trend["latestAverage"]).isEqualTo(1.7)
        assertThat(trend["latestMarkCount"]).isEqualTo(7)
        val incomplete = AIContext.build("scope", dashboard, emptyList(), true, false, history = listOf(history.single().copy(firstMarkCount = null)))
        assertThat(incomplete["trends"] as List<*>).isEmpty()
        assertThat(incomplete["unavailableSections"] as List<*>).contains("trends")
    }

    @Test fun unknownComputeSchemasAndUnavailableActionsFailClosed() {
        val status = AIContract.status(mapOf("enabled" to true, "consentRequired" to false, "dailyLimit" to 20, "remaining" to 10,
            "compute" to mapOf("schemaVersion" to 2, "catalogVersion" to "future", "actions" to listOf(mapOf("id" to "reply", "cost" to 1, "available" to true)))))
        assertThat(status.canAfford(AIAction.REPLY)).isFalse()
        assertThat(status.copy(compute = null).canAfford(AIAction.REPLY)).isTrue()
        assertThat(status.copy(compute = null).canAfford(AIAction.TEST)).isFalse()
        assertThat(status.copy(compute = AICompute(1, "v1", 0, listOf(AIComputeAction("reply", 11, true)))).canAfford(AIAction.REPLY)).isFalse()
    }

    @Test fun arithmeticSummaryUsesAllPreparedMarksWhileRecentMarksStayBounded() {
        val subject = Subject(subjectInfo = SubjectInfo("math", "M", "Mathematics"),
            marks = (1..12).map { Mark(id = "mark-$it", subjectID = "math", markText = "2", weight = 1.0) } + Mark(id = "final", subjectID = "math", markText = "4", weight = 1.0))
        val context = AIContext.build("scope", DashboardData(MarksResponse(listOf(subject))), emptyList(), true, false)
        val value = (context["subjects"] as List<*>).single().objectMap()
        assertThat(value["recentMarks"] as List<*>).hasSize(5)
        val calculation = value["calculation"].objectMap()
        assertThat(calculation["includedCount"]).isEqualTo(13)
        assertThat(calculation["totalWeight"]).isEqualTo(13.0)
        assertThat(calculation["weightedSum"]).isEqualTo(28.0)
        assertThat(calculation["confidence"]).isEqualTo("exact")
    }

    private fun source(): Map<String, Any?> = mapOf(
        "schoolScope" to "safe", "generatedAt" to 1L, "isStale" to false, "unavailableSections" to emptyList<String>(),
        "subjects" to listOf(mapOf("id" to "math", "average" to 2.0, "recentMarks" to listOf("1")), mapOf("id" to "other", "average" to 1.0)),
        "trends" to listOf(mapOf("subjectID" to "math")), "insights" to emptyList<Any>(),
        "timetable" to listOf(mapOf("id" to "today", "date" to "2026-09-29"), mapOf("id" to "tomorrow", "date" to "2026-09-30")),
        "events" to listOf(mapOf("id" to "test", "subjectID" to "math", "date" to "2026-09-30T00:00:00+02:00", "notes" to "private notes for test"),
            mapOf("id" to "other", "subjectID" to "other", "date" to "2026-10-01T00:00:00+02:00", "notes" to "private notes for other event")),
    )
}
