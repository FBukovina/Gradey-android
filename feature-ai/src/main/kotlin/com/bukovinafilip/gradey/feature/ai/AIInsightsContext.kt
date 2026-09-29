package com.bukovinafilip.gradey.feature.ai

import com.bukovinafilip.gradey.domain.SchoolInsightState
import com.bukovinafilip.gradey.domain.SchoolSubjectInsights

internal object AIInsightsContext {
    /** Seen state only affects Today presentation; AI uses every selected comparable observation. */
    fun insights(state: SchoolInsightState, now: Long): List<Map<String, Any?>> {
        val facts = state.subjects.associateBy { it.id }
        val changes = state.observations.sortedByDescending { it.observedAt }.map { observation ->
            var summary = "Observed ${observation.addedMarkIDs.size} added and ${observation.editedMarkIDs.size} edited grades."
            if (observation.previousAverage != null && observation.average != null)
                summary += " Recorded average: ${observation.previousAverage} to ${observation.average}."
            mapOf("id" to observation.id.take(160), "subjectID" to observation.subjectID,
                "kind" to "observed_grade_change", "summary" to summary.take(400), "observedAt" to observation.observedAt,
                "isEstimated" to (facts[observation.subjectID]?.isEstimated ?: true))
        }
        val summaries = state.subjects.map { fact ->
            var summary = "Subject: ${fact.name}."
            fact.average?.let { summary += " Current recorded average: $it." }
            val delta = SchoolSubjectInsights.trendDelta(state.observations.filter { it.subjectID == fact.id }, now)
            summary += if (delta != null) " Comparable observed trend change: $delta." else " Not enough comparable observations for a trend."
            mapOf("id" to "subject-summary-${fact.id.take(128)}", "subjectID" to fact.id, "kind" to "subject_summary",
                "summary" to summary.take(400), "observedAt" to null, "isEstimated" to fact.isEstimated)
        }
        return changes + summaries
    }

    fun freshness(state: SchoolInsightState, week: String, eventsAvailable: Boolean, now: Long): List<Map<String, Any?>> =
        listOf("marks", "trends", "timetable", "events", "insights").map { section ->
            if (section == "events") mapOf("section" to section, "fetchedAt" to now.takeIf { eventsAvailable }, "isStale" to !eventsAvailable)
            else {
                val key = when (section) { "trends" -> "history"; "insights" -> "marks"; "timetable" -> "timetable-$week"; else -> section }
                val freshness = state.sources[key]
                mapOf("section" to section, "fetchedAt" to freshness?.lastSuccessAt,
                    "isStale" to (freshness?.isStale(now, if (section == "marks") 300_000 else 900_000) ?: true))
            }
        }
}
