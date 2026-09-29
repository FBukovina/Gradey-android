package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.Subject
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID
import kotlin.math.round

enum class GradeCalculationConfidence { EXACT, ESTIMATED, UNAVAILABLE }
enum class GradeCalculationIssue {
    INFERRED_WEIGHTS, MISSING_WEIGHTS, MODIFIER_MAPPING, EXCLUDED_MARKS, INVALID_WEIGHTS,
    DUPLICATE_MARKS, CONFLICTING_DUPLICATE_IDS, INCONSISTENT_OFFICIAL_AVERAGE,
    MISSING_MARK_IDENTIFIERS, NO_RECORDED_GRADES,
}

data class PreparedGradeMark(
    val id: String,
    val date: LocalDate?,
    val value: Double,
    val weight: ResolvedMarkWeight,
    val usesModifierMapping: Boolean,
)

data class PreparedGradeCalculation(
    val subjectID: String,
    val revision: String,
    val marks: List<PreparedGradeMark>,
    val weightedSum: Double,
    val totalWeight: Double,
    val calculatedAverage: Double?,
    val officialAverage: Double?,
    val confidence: GradeCalculationConfidence,
    val issues: Set<GradeCalculationIssue>,
    val excludedMarkCount: Int,
) {
    val displayAverage: Double? get() = officialAverage ?: calculatedAverage
    val canSimulate: Boolean get() = calculatedAverage != null && totalWeight > 0
    val resolvedWeights: Map<String, ResolvedMarkWeight> get() = marks.associate { it.id to it.weight }

    fun averageWithout(markID: String): Double? {
        val mark = marks.firstOrNull { it.id == markID } ?: return null
        val remainingWeight = totalWeight - mark.weight.value
        if (remainingWeight <= 0) return null
        return ((weightedSum - mark.value * mark.weight.value) / remainingWeight).takeIf { it.isFinite() }
    }

    /** A counterfactual contribution, never a claim about observed grade history. */
    fun impact(markID: String): Double? {
        val average = calculatedAverage ?: return null
        return averageWithout(markID)?.let { average - it }
    }
}

data class HypotheticalGrade(val id: String = UUID.randomUUID().toString(), val value: Double = 1.0, val weight: Int = 1)
data class GradeSimulationResult(
    val baselineAverage: Double?, val estimatedAverage: Double, val difference: Double?,
    val confidence: GradeCalculationConfidence, val issues: Set<GradeCalculationIssue>,
)
data class GradeTargetOption(val grade: Int, val count: Int, val weight: Int, val estimatedAverage: Double)
data class GradeTargetResult(val status: Status, val options: List<GradeTargetOption>, val confidence: GradeCalculationConfidence) {
    enum class Status { OPTIONS, ALREADY_REACHED, UNREACHABLE, UNAVAILABLE, INVALID_TARGET }
}

/** Parity with iOS: all scenarios share one immutable baseline and fixed resolved weights. */
object GradeSimulation {
    const val MaximumHypotheticalGrades = 10

    /** Canonical comparison excludes read/confirmation bookkeeping. */
    fun revision(mark: Mark): String = canonicalRecord(mark)

    fun revision(subject: Subject): String = digest(listOf(
        subject.id, subject.pointsOnly.toString(), subject.averageText?.trim().orEmpty(),
        subject.marks.map(::canonicalRecord).sorted().joinToString("\u001e"),
    ))

    fun prepare(subject: Subject): PreparedGradeCalculation {
        val issues = mutableSetOf<GradeCalculationIssue>()
        var excluded = 0
        val unique = mutableListOf<Mark>()
        subject.marks.groupBy { it.id.ifBlank { "local-mark-${canonicalRecord(it)}" } }.toSortedMap().forEach { (identity, records) ->
            val first = records.first()
            if (records.size > 1) {
                issues += GradeCalculationIssue.DUPLICATE_MARKS
                if (records.map(::canonicalRecord).distinct().size > 1) {
                    issues += GradeCalculationIssue.CONFLICTING_DUPLICATE_IDS
                    excluded += records.size
                    return@forEach
                }
                excluded += records.size - 1
            }
            val value = GradeMath.parseMarkValue(first.markText)
            if (subject.pointsOnly || first.isPoints || first.type == "unsupported" || value == null || value !in 1.0..5.7) {
                excluded++
                issues += GradeCalculationIssue.EXCLUDED_MARKS
                return@forEach
            }
            val explicitWeight = first.weight
            if (explicitWeight != null && (!explicitWeight.isFinite() || explicitWeight <= 0)) {
                excluded++
                issues += GradeCalculationIssue.INVALID_WEIGHTS
                return@forEach
            }
            if (first.id.isBlank() || first.id.startsWith("local-mark-")) issues += GradeCalculationIssue.MISSING_MARK_IDENTIFIERS
            unique += first.copy(id = identity)
        }
        val weights = GradeMath.resolvedWeights(unique, subject.averageText)
        var sum = 0.0
        var total = 0.0
        val prepared = unique.mapNotNull { mark ->
            val value = GradeMath.parseMarkValue(mark.markText) ?: return@mapNotNull null
            val weight = weights[mark.id] ?: ResolvedMarkWeight(1.0, MarkWeightSource.FALLBACK)
            when (weight.source) {
                MarkWeightSource.EXPLICIT -> Unit
                MarkWeightSource.INFERRED -> issues += GradeCalculationIssue.INFERRED_WEIGHTS
                MarkWeightSource.FALLBACK -> issues += GradeCalculationIssue.MISSING_WEIGHTS
            }
            val modifier = mark.markText.trim().endsWith('+') || mark.markText.trim().endsWith('-')
            if (modifier) issues += GradeCalculationIssue.MODIFIER_MAPPING
            val nextSum = sum + value * weight.value
            val nextWeight = total + weight.value
            if (!nextSum.isFinite() || !nextWeight.isFinite()) {
                excluded++
                issues += GradeCalculationIssue.INVALID_WEIGHTS
                return@mapNotNull null
            }
            sum = nextSum
            total = nextWeight
            PreparedGradeMark(mark.id, mark.markDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }, value, weight, modifier)
        }
        val calculated = if (total > 0) sum / total else null
        val official = GradeMath.parseAverageText(subject.averageText)
        if (calculated != null && official != null && round(calculated * 100) != round(official * 100)) {
            issues += GradeCalculationIssue.INCONSISTENT_OFFICIAL_AVERAGE
        }
        if (prepared.isEmpty()) issues += GradeCalculationIssue.NO_RECORDED_GRADES
        val confidence = if (prepared.isEmpty()) GradeCalculationConfidence.UNAVAILABLE else if (issues.isEmpty()) GradeCalculationConfidence.EXACT else GradeCalculationConfidence.ESTIMATED
        return PreparedGradeCalculation(subject.id, revision(subject), prepared, sum, total, calculated, official, confidence, issues.toSet(), excluded)
    }

    fun simulate(prepared: PreparedGradeCalculation, additions: List<HypotheticalGrade>): GradeSimulationResult? {
        if (additions.size > MaximumHypotheticalGrades || additions.map { it.id }.distinct().size != additions.size ||
            additions.any { !it.value.isFinite() || it.value !in 1.0..5.7 || it.weight !in 1..10 }) return null
        val sum = additions.fold(prepared.weightedSum) { acc, grade -> acc + grade.value * grade.weight }
        val weight = additions.fold(prepared.totalWeight) { acc, grade -> acc + grade.weight }
        if (!sum.isFinite() || !weight.isFinite() || weight <= 0) return null
        val average = sum / weight
        if (!average.isFinite()) return null
        return GradeSimulationResult(prepared.calculatedAverage, average, prepared.calculatedAverage?.let { average - it },
            if (prepared.confidence == GradeCalculationConfidence.UNAVAILABLE) GradeCalculationConfidence.ESTIMATED else prepared.confidence, prepared.issues)
    }

    fun targetOptions(prepared: PreparedGradeCalculation, targetAverage: Double, weight: Int = 1, maximumCount: Int = 10): GradeTargetResult {
        fun result(status: GradeTargetResult.Status, options: List<GradeTargetOption> = emptyList()) = GradeTargetResult(status, options, prepared.confidence)
        if (!targetAverage.isFinite() || targetAverage !in 1.0..5.0 || weight !in 1..10 || maximumCount <= 0) return result(GradeTargetResult.Status.INVALID_TARGET)
        val current = prepared.calculatedAverage
        if (current == null || !prepared.canSimulate) return result(GradeTargetResult.Status.UNAVAILABLE)
        if (current <= targetAverage) return result(GradeTargetResult.Status.ALREADY_REACHED)
        val options = (1..5).mapNotNull { grade ->
            (1..minOf(maximumCount, MaximumHypotheticalGrades)).firstNotNullOfOrNull { count ->
                val simulation = simulate(prepared, List(count) { HypotheticalGrade(value = grade.toDouble(), weight = weight) })
                simulation?.takeIf { it.estimatedAverage <= targetAverage }?.let { GradeTargetOption(grade, count, weight, it.estimatedAverage) }
            }
        }.sortedWith(compareBy<GradeTargetOption> { it.count }.thenByDescending { it.grade }).take(3)
        return result(if (options.isEmpty()) GradeTargetResult.Status.UNREACHABLE else GradeTargetResult.Status.OPTIONS, options)
    }

    private fun canonicalRecord(mark: Mark): String = digest(listOf(mark.id, mark.subjectID, mark.markDate.orEmpty(), mark.markText.trim(),
        mark.type.orEmpty(), mark.typeNote.orEmpty(), mark.caption.orEmpty(), mark.theme.orEmpty(), mark.weight?.toString() ?: "nil",
        mark.isPoints.toString(), mark.pointsText.orEmpty(), mark.maxPoints?.toString().orEmpty()))

    private fun digest(values: List<String>): String {
        val input = values.joinToString("") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
