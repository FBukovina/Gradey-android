package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs

enum class TodayInsightKind { ASSESSMENT, DEADLINE, AVERAGE_CHANGE, BUSY_TESTS, WORSENING_TREND, RECENT_GRADE }
enum class TodayInsightCopy { ASSESSMENT_DUE, DEADLINE_DUE, AVERAGE_CHANGED, BUSY_TESTS, TREND_WORSENING, GRADE_NEW, GRADE_EDITED }
sealed interface TodayInsightDestination {
    data class Subject(val id: String) : TodayInsightDestination
    data class PlannerItem(val id: String) : TodayInsightDestination
    data object Planner : TodayInsightDestination
}

data class TodayInsight(
    val id: String, val kind: TodayInsightKind, val priority: Int, val subjectID: String?,
    val eventIDs: List<String>, val observationIDs: List<String>, val relevantAt: Long,
    val observedAt: Long, val expiresAt: Long, val copy: TodayInsightCopy,
    val titleValue: String, val previousAverage: Double? = null, val average: Double? = null,
    val destination: TodayInsightDestination,
    val dateZoneID: String? = null,
)

/** Ranked, attributable attention; successful observation times are distinct from grade dates. */
object TodayInsights {
    const val MAX_VISIBLE = 3
    private const val GRADE_LIFETIME = 7 * SchoolInsightStore.DAY

    fun make(scope: String?, state: SchoolInsightState?, plannerItems: List<PersonalPlannerItem>,
             now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<TodayInsight> {
        val currentTime = now.toEpochMilli()
        val candidates = mutableListOf<TodayInsight>()
        val events = plannerItems.mapNotNull { item ->
            if (item.isCompleted || item.type == PlannerItemType.NOTE) return@mapNotNull null
            val subjectScope = item.subject?.scope
            val lessonScope = item.lesson?.scope
            if (subjectScope != null && lessonScope != null && subjectScope != lessonScope) return@mapNotNull null
            val itemScope = subjectScope ?: lessonScope
            if (itemScope != null && itemScope != scope) return@mapNotNull null
            val date = PersonalPlanner.orderingInstant(item) ?: return@mapNotNull null
            val timed = if (item.dueDate != null) item.dueTime != null else item.lesson?.startEpochMillis != null
            val itemZone = PersonalPlanner.zone(item)
            val expiry = if (timed) date else date.atZone(itemZone).toLocalDate().plusDays(1).atStartOfDay(itemZone).toInstant()
            if (expiry <= now) return@mapNotNull null
            Event(item, date.toEpochMilli(), expiry.toEpochMilli(), itemZone.id)
        }.sortedWith(compareBy<Event> { it.date }.thenBy { it.item.id })
        events.filter { it.date <= currentTime + SchoolInsightStore.DAY }.forEach { event ->
            val assessment = event.item.type in setOf(PlannerItemType.TEST, PlannerItemType.PRESENTATION)
            candidates += TodayInsight("planner-${event.item.id}", if (assessment) TodayInsightKind.ASSESSMENT else TodayInsightKind.DEADLINE,
                if (assessment) 100 else 90, event.item.subject?.id, listOf(event.item.id), emptyList(), event.date,
                event.item.updatedAtEpochMillis, event.expiry, if (assessment) TodayInsightCopy.ASSESSMENT_DUE else TodayInsightCopy.DEADLINE_DUE,
                event.item.title, destination = TodayInsightDestination.PlannerItem(event.item.id), dateZoneID = event.zone)
        }
        val windowEnd = now.atZone(zone).plusDays(4).toInstant().toEpochMilli()
        val tests = events.filter { it.item.type == PlannerItemType.TEST && it.date <= windowEnd }
        if (tests.size >= 3) candidates += TodayInsight("busy-tests-${tests.map { it.item.id }.sorted().joinToString("-")}", TodayInsightKind.BUSY_TESTS,
            70, null, tests.map { it.item.id }, emptyList(), tests.first().date, tests.maxOf { it.item.updatedAtEpochMillis },
            tests.minOf { it.expiry }, TodayInsightCopy.BUSY_TESTS, tests.size.toString(), destination = TodayInsightDestination.Planner, dateZoneID = tests.first().zone)

        val marks = state?.sources?.get("marks")
        if (scope != null && state?.scope == scope && marks?.isStale(currentTime, 300_000) == false) {
            for (subject in state.subjects) {
                val observations = state.observations.filter { it.scope == scope && it.subjectID == subject.id }
                observations.filter { it.seenAt == null && it.observedAt <= currentTime && currentTime - it.observedAt < GRADE_LIFETIME }.forEach { observation ->
                    val meaningful = meaningfulAverageChange(observation.previousAverage, observation.average)
                    if (meaningful || observation.addedMarkIDs.isNotEmpty() || observation.editedMarkIDs.isNotEmpty()) {
                        candidates += TodayInsight("grade-${observation.id}", if (meaningful) TodayInsightKind.AVERAGE_CHANGE else TodayInsightKind.RECENT_GRADE,
                            if (meaningful) 80 else 50, subject.id, emptyList(), listOf(observation.id), observation.observedAt, observation.observedAt,
                            observation.observedAt + GRADE_LIFETIME, when {
                                meaningful -> TodayInsightCopy.AVERAGE_CHANGED
                                observation.addedMarkIDs.isNotEmpty() -> TodayInsightCopy.GRADE_NEW
                                else -> TodayInsightCopy.GRADE_EDITED
                            }, subject.name, observation.previousAverage, observation.average, TodayInsightDestination.Subject(subject.id))
                    }
                }
                val delta = SchoolSubjectInsights.trendDelta(observations, currentTime)
                val last = observations.maxWithOrNull(compareBy<SchoolGradeObservation> { it.observedAt }.thenBy { it.id })
                if (delta != null && delta >= 0.20 - 0.000001 && last != null && last.observedAt <= currentTime && currentTime - last.observedAt < GRADE_LIFETIME) {
                    candidates += TodayInsight("trend-${subject.id}-${last.digest}", TodayInsightKind.WORSENING_TREND, 60, subject.id, emptyList(), emptyList(),
                        last.observedAt, last.observedAt, last.observedAt + GRADE_LIFETIME, TodayInsightCopy.TREND_WORSENING, subject.name,
                        destination = TodayInsightDestination.Subject(subject.id))
                }
            }
        }
        return rank(candidates, currentTime)
    }

    fun rank(candidates: List<TodayInsight>, now: Long): List<TodayInsight> {
        val sorted = candidates.filter { it.expiresAt > now }.sortedWith(compareByDescending<TodayInsight> { it.priority }
            .thenBy { if (it.eventIDs.isEmpty()) Long.MAX_VALUE else it.relevantAt }.thenByDescending { it.observedAt }.thenBy { it.id })
        val ids = mutableSetOf<String>()
        val subjects = mutableSetOf<String>()
        val events = mutableSetOf<String>()
        val result = mutableListOf<TodayInsight>()
        for (candidate in sorted) {
            if (!ids.add(candidate.id) || candidate.eventIDs.any { it in events }) continue
            if (candidate.eventIDs.isEmpty() && candidate.subjectID != null && !subjects.add(candidate.subjectID)) continue
            result += candidate
            events += candidate.eventIDs
            if (result.size == MAX_VISIBLE) break
        }
        return result
    }

    fun meaningfulAverageChange(previous: Double?, current: Double?): Boolean {
        if (previous == null || current == null || !previous.isFinite() || !current.isFinite()) return false
        val delta = abs(current - previous)
        if (delta >= 0.20 - 0.000001) return true
        if (delta < 0.10 - 0.000001) return false
        return listOf(1.5, 2.5, 3.5, 4.5).any { (previous < it && current >= it) || (previous > it && current <= it) }
    }

    private data class Event(val item: PersonalPlannerItem, val date: Long, val expiry: Long, val zone: String)
}
