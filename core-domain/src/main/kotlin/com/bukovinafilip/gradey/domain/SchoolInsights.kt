package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.Subject
import java.security.MessageDigest
import kotlinx.serialization.Serializable

@Serializable
data class SchoolSourceFreshness(val lastSuccessAt: Long? = null, val failed: Boolean = false) {
    fun isStale(now: Long, intervalMillis: Long): Boolean = lastSuccessAt?.let { failed || now < it || now - it >= intervalMillis } ?: true
}

@Serializable
data class SchoolGradeObservation(
    val id: String, val scope: String, val subjectID: String, val observedAt: Long, val previousObservedAt: Long,
    val previousAverage: Double?, val average: Double?, val addedMarkIDs: List<String>, val editedMarkIDs: List<String>,
    val digest: String, val seenAt: Long? = null,
) {
    val averageDelta: Double? get() = average?.let { next -> previousAverage?.let { next - it } }
}

@Serializable
data class SchoolSubjectFact(val id: String, val name: String, val average: Double?, val isEstimated: Boolean)

@Serializable
data class SchoolSubjectBaseline(
    val revision: String, val average: Double?, val observedAt: Long, val marks: Map<String, String>,
    val stableMarkIDs: Set<String>, val averageBasis: String, val weightSignatures: Map<String, String>,
)

@Serializable
data class SchoolInsightState(
    val version: Int = 1, val scope: String,
    val baselines: Map<String, SchoolSubjectBaseline> = emptyMap(),
    val observations: List<SchoolGradeObservation> = emptyList(),
    val subjects: List<SchoolSubjectFact> = emptyList(),
    val sources: Map<String, SchoolSourceFreshness> = emptyMap(),
)

interface SchoolInsightStorage {
    fun read(scope: String): SchoolInsightState?
    fun write(state: SchoolInsightState)
    fun clear(scope: String)
    fun clearAll()
}

class MemorySchoolInsightStorage : SchoolInsightStorage {
    private val states = mutableMapOf<String, SchoolInsightState>()
    override fun read(scope: String) = states[scope]
    override fun write(state: SchoolInsightState) { states[state.scope] = state }
    override fun clear(scope: String) { states.remove(scope) }
    override fun clearAll() = states.clear()
}

/** Derived observations of real successful reads. Grade dates never become observed history. */
class SchoolInsightStore(private val storage: SchoolInsightStorage = MemorySchoolInsightStorage()) {
    private val states = mutableMapOf<String, SchoolInsightState>()
    var persistenceFailed: Boolean = false
        private set

    /** Call only when installing a scoped cache, not for UI recompositions or cached fallbacks. */
    @Synchronized fun activate(scope: String, subjects: List<Subject>, fetchedAt: Long?, now: Long = System.currentTimeMillis()): SchoolInsightState {
        val old = load(scope)
        val next = baselines(subjects, fetchedAt ?: now)
        val matches = old.baselines.size == next.size && next.all { (id, value) -> old.baselines[id]?.let {
            it.revision == value.revision && it.averageBasis == value.averageBasis && it.weightSignatures == value.weightSignatures
        } == true }
        return commit(prune(old.copy(
            baselines = if (fetchedAt == null) emptyMap() else if (matches) old.baselines else next,
            observations = if (fetchedAt != null && matches) old.observations else emptyList(),
            subjects = if (fetchedAt == null) emptyList() else facts(subjects),
            sources = old.sources + ("marks" to SchoolSourceFreshness(fetchedAt)),
        ), now))
    }

    @Synchronized fun observe(scope: String, subjects: List<Subject>, fetchedAt: Long): SchoolInsightState {
        val old = load(scope)
        if ((old.baselines.values.maxOfOrNull { it.observedAt } ?: Long.MIN_VALUE) > fetchedAt) return old
        val next = baselines(subjects, fetchedAt)
        val observations = old.observations.filter { it.subjectID in next }.toMutableList()
        for ((id, current) in next) {
            val previous = old.baselines[id] ?: continue // First read is a quiet baseline.
            val removed = previous.marks.keys - current.marks.keys
            val assumptionsChanged = previous.averageBasis != current.averageBasis || (previous.marks.keys intersect current.marks.keys).any { markID ->
                val before = previous.weightSignatures[markID]
                val after = current.weightSignatures[markID]
                if (before?.endsWith(":explicit") == true && after?.endsWith(":explicit") == true) false else before != after
            }
            if (removed.isNotEmpty() || assumptionsChanged) {
                observations.removeAll { it.subjectID == id }
                continue
            }
            if (previous.revision == current.revision) continue
            val added = (current.stableMarkIDs - previous.marks.keys).sorted()
            val edited = current.marks.keys.filter { it in previous.marks && previous.marks[it] != current.marks[it] }.sorted()
            if (previous.average == current.average && added.isEmpty() && edited.isEmpty()) continue
            observations += SchoolGradeObservation(
                digest(listOf(scope, id, previous.revision, current.revision, fetchedAt.toString())), scope, id, fetchedAt, previous.observedAt,
                previous.average, current.average, added, edited, current.revision,
            )
        }
        return commit(prune(old.copy(baselines = next, observations = observations, subjects = facts(subjects),
            sources = old.sources + ("marks" to SchoolSourceFreshness(fetchedAt))), fetchedAt))
    }

    @Synchronized fun sourceSucceeded(scope: String, source: String, fetchedAt: Long) {
        val old = load(scope)
        if ((old.sources[source]?.lastSuccessAt ?: Long.MIN_VALUE) > fetchedAt) return
        commit(old.copy(sources = old.sources + (source to SchoolSourceFreshness(fetchedAt))))
    }

    @Synchronized fun sourceFailed(scope: String, source: String, attemptStartedAt: Long = System.currentTimeMillis()) {
        val old = load(scope)
        val previous = old.sources[source] ?: SchoolSourceFreshness()
        if ((previous.lastSuccessAt ?: Long.MIN_VALUE) > attemptStartedAt) return
        commit(old.copy(sources = old.sources + (source to previous.copy(failed = true))))
    }

    @Synchronized fun sourceUnavailable(scope: String, source: String) {
        val old = load(scope)
        // A broken sidecar cannot prove continuity across this snapshot. Start quietly on recovery.
        commit(old.copy(sources = old.sources + (source to SchoolSourceFreshness(failed = true)),
            baselines = if (source == "marks") emptyMap() else old.baselines,
            observations = if (source == "marks") emptyList() else old.observations,
            subjects = if (source == "marks") emptyList() else old.subjects))
    }

    @Synchronized fun snapshot(scope: String, now: Long = System.currentTimeMillis()): SchoolInsightState = prune(load(scope), now).also { states[scope] = it }

    @Synchronized fun markSeen(scope: String, ids: Set<String>, at: Long = System.currentTimeMillis()) {
        val old = load(scope)
        commit(prune(old.copy(observations = old.observations.map { if (it.id in ids && it.seenAt == null) it.copy(seenAt = at) else it }), at))
    }

    @Synchronized fun clear(scope: String) {
        states.remove(scope)
        runCatching { storage.clear(scope) }.onFailure { persistenceFailed = true }
    }

    @Synchronized fun clearAll() {
        states.clear()
        runCatching { storage.clearAll() }.onFailure { persistenceFailed = true }
    }

    private fun facts(subjects: List<Subject>) = subjects.groupBy { it.id }.filterValues { it.size == 1 }.values.map { values ->
        val subject = values.single()
        val prepared = GradeSimulation.prepare(subject)
        SchoolSubjectFact(subject.id, subject.displayName.take(120), prepared.displayAverage?.takeIf(Double::isFinite), prepared.confidence != GradeCalculationConfidence.EXACT)
    }

    private fun baselines(subjects: List<Subject>, at: Long): Map<String, SchoolSubjectBaseline> = subjects.groupBy { it.id }.mapNotNull { (id, group) ->
        if (group.size != 1) return@mapNotNull null
        val subject = group.single()
        val prepared = GradeSimulation.prepare(subject)
        if (GradeCalculationIssue.CONFLICTING_DUPLICATE_IDS in prepared.issues) return@mapNotNull null
        val marks = subject.marks.groupBy { it.id }.mapNotNull { (markID, values) ->
            val revisions = values.map(GradeSimulation::revision).distinct()
            if (revisions.size == 1) markID to revisions.single() else null
        }.toMap()
        val average = prepared.displayAverage?.takeIf { prepared.confidence != GradeCalculationConfidence.UNAVAILABLE && it.isFinite() }
        val basis = when { average == null -> "unavailable"; prepared.officialAverage != null -> "provider"; else -> "reconstructed" }
        id to SchoolSubjectBaseline(prepared.revision, average, at, marks,
            marks.keys.filter { it.isNotBlank() && !it.startsWith("local-mark-") }.toSet(), basis,
            prepared.resolvedWeights.mapValues { "${it.value.value}:${it.value.source.name.lowercase(java.util.Locale.ROOT)}" })
    }.toMap()

    private fun load(scope: String): SchoolInsightState = states.getOrPut(scope) {
        runCatching { storage.read(scope)?.takeIf { it.version == 1 && it.scope == scope && it.observations.map { item -> item.id }.distinct().size == it.observations.size } }
            .getOrNull() ?: SchoolInsightState(scope = scope)
    }

    private fun commit(state: SchoolInsightState): SchoolInsightState {
        states[state.scope] = state
        persistenceFailed = runCatching { storage.write(state) }.isFailure
        return state
    }

    private fun prune(state: SchoolInsightState, now: Long) = state.copy(observations = state.observations.filter {
        it.scope == state.scope && it.observedAt in (now - 30 * DAY)..now
    }.sortedWith(compareByDescending<SchoolGradeObservation> { it.observedAt }.thenBy { it.id }).take(100))

    private fun digest(values: List<String>): String = MessageDigest.getInstance("SHA-256")
        .digest(values.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    companion object { const val DAY = 86_400_000L }
}

object SchoolSubjectInsights {
    /** Requires three comparable points over at least seven days; no backfilling from mark dates. */
    fun trendDelta(observations: List<SchoolGradeObservation>, now: Long): Double? {
        val points = sortedMapOf<Long, Double>()
        val range = (now - 30 * SchoolInsightStore.DAY)..now
        observations.sortedBy { it.observedAt }.forEach { observation ->
            observation.previousAverage?.takeIf { it.isFinite() && observation.previousObservedAt in range }?.let { points[observation.previousObservedAt] = it }
            observation.average?.takeIf { it.isFinite() && observation.observedAt in range }?.let { points[observation.observedAt] = it }
        }
        if (points.size < 3 || points.lastKey() - points.firstKey() < 7 * SchoolInsightStore.DAY) return null
        return (points.getValue(points.lastKey()) - points.getValue(points.firstKey())).takeIf { kotlin.math.abs(it) >= 0.20 - 0.000001 }
    }
}
