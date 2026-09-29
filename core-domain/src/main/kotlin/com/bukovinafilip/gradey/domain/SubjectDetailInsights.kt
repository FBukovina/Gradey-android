package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.Subject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

/** These values describe the current calculation, not a historical change observed by the app. */
data class SubjectGradeContribution(val markID: String, val markText: String, val reconstructedDelta: Double)
data class SubjectPlannerEvent(val id: String, val title: String, val type: PlannerItemType,
    val date: Instant, val hasTime: Boolean, val zoneID: String)
data class SubjectDetailInsightSummary(val marksFresh: Boolean, val trendDelta: Double?,
    val recentContribution: SubjectGradeContribution?, val upcomingEvents: List<SubjectPlannerEvent>)

object SubjectDetailInsights {
    fun make(subject: Subject, scope: String?, state: SchoolInsightState?, plannerItems: List<PersonalPlannerItem>,
             now: Instant = Instant.now(), zone: ZoneId = ZoneId.of("Europe/Prague")): SubjectDetailInsightSummary {
        val prepared = GradeSimulation.prepare(subject)
        val currentSnapshot = scope != null && state?.scope == scope && state.baselines[subject.id]?.revision == prepared.revision
        val fresh = currentSnapshot && state?.sources?.get("marks")?.isStale(now.toEpochMilli(), 300_000) == false
        val trend = if (fresh) SchoolSubjectInsights.trendDelta(state!!.observations.filter {
            it.scope == scope && it.subjectID == subject.id
        }, now.toEpochMilli()) else null
        val recent = prepared.marks.filter { mark -> mark.date?.atStartOfDay(zone)?.toInstant()?.let {
            it >= now.minusMillis(30 * SchoolInsightStore.DAY) && it <= now
        } == true }.sortedWith(compareByDescending<PreparedGradeMark> { it.date }.thenBy { it.id }).take(5)
        val contribution = recent.mapNotNull { mark ->
            val delta = prepared.impact(mark.id)?.takeIf(Double::isFinite) ?: return@mapNotNull null
            val original = subject.marks.firstOrNull { it.id == mark.id } ?: return@mapNotNull null
            SubjectGradeContribution(mark.id, original.markText, delta)
        }.sortedWith(compareByDescending<SubjectGradeContribution> { abs(it.reconstructedDelta) }.thenBy { it.markID }).firstOrNull()
        val events = plannerItems.mapNotNull { item ->
            // Subject names and unscoped personal records never prove school/subject identity.
            if (scope == null || item.isCompleted || item.subject?.scope != scope || item.subject?.id != subject.id) return@mapNotNull null
            if (item.lesson?.scope?.let { it != scope } == true) return@mapNotNull null
            val date = PersonalPlanner.orderingInstant(item) ?: return@mapNotNull null
            val itemZone = PersonalPlanner.zone(item)
            val timed = if (item.dueDate != null) item.dueTime != null else item.lesson?.startEpochMillis != null
            val expiry = if (timed) date else date.atZone(itemZone).toLocalDate().plusDays(1).atStartOfDay(itemZone).toInstant()
            if (expiry <= now) return@mapNotNull null
            SubjectPlannerEvent(item.id, item.title, item.type, date, timed, itemZone.id)
        }.sortedWith(compareBy<SubjectPlannerEvent> { it.date }.thenBy { it.id }).take(2)
        return SubjectDetailInsightSummary(fresh, trend, contribution, events)
    }
}
