package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.time.LocalDate

interface AbsenceOverrideRepository {
    suspend fun load(scope: String): List<AbsenceDayOverride>
    suspend fun save(override: AbsenceDayOverride): List<AbsenceDayOverride>
    suspend fun replace(scope: String, overrides: List<AbsenceDayOverride>): List<AbsenceDayOverride>
    suspend fun restore(scope: String, dateKey: String): List<AbsenceDayOverride>
    suspend fun reset(scope: String): List<AbsenceDayOverride>
}

data class AbsenceOverrideResult(
    val response: AbsenceResponse,
    val activeOverrides: List<AbsenceDayOverride>,
    val reviewOverrides: List<AbsenceDayOverride>,
    val reconciledOverrides: List<AbsenceDayOverride>,
) {
    val hiddenCount: Int get() = activeOverrides.sumOf { it.hiddenAllocationIDs.size }
}

/** Always starts from untouched provider data. Unknown subject denominators remain unknown. */
object AbsenceOverrideProjection {
    fun dateKey(value: String): String = value.trim().substringBefore('T')
    fun normalizedSubjectName(value: String): String = SchoolDirectorySearch.normalize(value.trim()).replace(Regex("\\s+"), " ")

    fun count(day: Absence, category: AbsenceOverrideCategory): Int = when (category) {
        AbsenceOverrideCategory.UNSOLVED -> day.unsolved
        AbsenceOverrideCategory.OK -> day.ok
        AbsenceOverrideCategory.MISSED -> day.missed
        AbsenceOverrideCategory.LATE -> day.late
        AbsenceOverrideCategory.SOON -> day.soon
        AbsenceOverrideCategory.SCHOOL -> day.school
        AbsenceOverrideCategory.DISTANCE_TEACHING -> day.distanceTeaching
    }

    fun project(
        rawResponse: AbsenceResponse,
        overrides: List<AbsenceDayOverride>,
        scope: String,
        lessonsByDate: Map<String, List<ScheduledLesson>>? = null,
    ): AbsenceOverrideResult {
        val scoped = overrides.filter { it.scope == scope }
        val duplicateDates = scoped.groupingBy { it.dateKey }.eachCount().filterValues { it != 1 }.keys
        val rawDays = rawResponse.absences.groupBy { dateKey(it.date) }
        var effectiveDays = rawResponse.absences
        var effectiveSubjects = rawResponse.absencesPerSubject
        val active = mutableListOf<AbsenceDayOverride>()
        val review = mutableListOf<AbsenceDayOverride>()
        val reconciled = mutableListOf<AbsenceDayOverride>()
        scoped.sortedBy { it.dateKey }.forEach { saved ->
            var reason = saved.pauseReason
                ?: if (saved.dateKey in duplicateDates) AbsenceOverridePauseReason.INVALID_ALLOCATION else null
            if (reason == null) reason = validationReason(saved, rawDays[saved.dateKey].orEmpty(), lessonsByDate?.get(saved.dateKey))
            var nextSubjects = effectiveSubjects
            if (reason == null && rawResponse.absencesPerSubject.isNotEmpty()) {
                for (allocation in saved.hiddenAllocations) {
                    val matching = nextSubjects.indices.filter {
                        normalizedSubjectName(nextSubjects[it].subjectName) == normalizedSubjectName(allocation.subjectName)
                    }
                    if (matching.size != 1) { reason = AbsenceOverridePauseReason.SUBJECT_MAPPING_CHANGED; break }
                    val index = matching.single()
                    val adjusted = subtract(nextSubjects[index], allocation.category)
                    if (adjusted == null) { reason = AbsenceOverridePauseReason.INSUFFICIENT_SUBJECT_COUNT; break }
                    nextSubjects = nextSubjects.mapIndexed { i, row -> if (i == index) adjusted else row }
                }
            }
            val entry = saved.copy(pauseReason = reason)
            if (reason != null) review += entry
            else {
                effectiveDays = if (saved.hidesEntireDay) effectiveDays.filterNot { dateKey(it.date) == saved.dateKey }
                    else effectiveDays.map { if (dateKey(it.date) == saved.dateKey) subtract(it, saved.hiddenAllocations) else it }
                effectiveSubjects = nextSubjects
                active += entry
            }
            reconciled += entry
        }
        return AbsenceOverrideResult(rawResponse.copy(absences = effectiveDays, absencesPerSubject = effectiveSubjects), active, review, reconciled)
    }

    private fun validationReason(entry: AbsenceDayOverride, days: List<Absence>, lessons: List<ScheduledLesson>?): AbsenceOverridePauseReason? {
        if (days.isEmpty()) return AbsenceOverridePauseReason.DAY_MISSING
        if (days.size != 1) return AbsenceOverridePauseReason.AMBIGUOUS_DAY
        val current = days.single()
        if (dateKey(entry.baselineDay.date) != entry.dateKey ||
            AbsenceOverrideCategory.entries.any { count(current, it) != count(entry.baselineDay, it) }) return AbsenceOverridePauseReason.DAY_CHANGED
        val ids = entry.allocations.map { it.id }.toSet()
        if (entry.hiddenAllocationIDs.isEmpty() || !ids.containsAll(entry.hiddenAllocationIDs) || ids.size != entry.allocations.size ||
            entry.allocations.any { it.id.isBlank() || it.subjectName.isBlank() } ||
            AbsenceOverrideCategory.entries.any { count(current, it) < 0 || entry.allocations.count { allocation -> allocation.category == it } != count(current, it) }) {
            return AbsenceOverridePauseReason.INVALID_ALLOCATION
        }
        val lessonIDs = entry.allocations.filter { it.category.contributesToBase }.mapNotNull { it.lessonID }
        if (lessonIDs.toSet().size != lessonIDs.size) return AbsenceOverridePauseReason.INVALID_ALLOCATION
        if (lessons != null) {
            for (allocation in entry.allocations) {
                val id = allocation.lessonID ?: continue
                val matches = lessons.filter { it.id == id && it.changeKind != LessonChangeKind.CANCELED }
                if (matches.size != 1 || (allocation.subjectID != null && allocation.subjectID != matches.single().subjectID)) {
                    return AbsenceOverridePauseReason.LESSON_MAPPING_CHANGED
                }
            }
        }
        return null
    }

    fun validateStored(scope: String, entries: List<AbsenceDayOverride>) {
        require(scope.isNotBlank() && entries.all { it.scope == scope }) { "Attendance corrections belong to a different school account." }
        require(entries.map { it.dateKey }.toSet().size == entries.size && entries.map { it.id }.toSet().size == entries.size) { "Saved attendance corrections contain duplicate days." }
        entries.forEach { entry ->
            require(runCatching { LocalDate.parse(entry.dateKey) }.isSuccess) { "Choose a valid attendance date." }
            require(entry.id.isNotBlank() && entry.allocations.isNotEmpty()) { "Select the original attendance units." }
        }
    }

    private fun subtract(row: AbsencePerSubject, kind: AbsenceOverrideCategory): AbsencePerSubject? {
        val updated = row.copy(
            base = row.base - if (kind.contributesToBase) 1 else 0,
            late = row.late - if (kind == AbsenceOverrideCategory.LATE) 1 else 0,
            soon = row.soon - if (kind == AbsenceOverrideCategory.SOON) 1 else 0,
            school = row.school - if (kind == AbsenceOverrideCategory.SCHOOL) 1 else 0,
            distanceTeaching = row.distanceTeaching - if (kind == AbsenceOverrideCategory.DISTANCE_TEACHING) 1 else 0,
        )
        return updated.takeIf { listOf(it.base, it.late, it.soon, it.school, it.distanceTeaching).all { value -> value >= 0 } }
    }

    private fun subtract(day: Absence, hidden: List<AbsenceOverrideAllocation>): Absence {
        fun remaining(kind: AbsenceOverrideCategory) = count(day, kind) - hidden.count { it.category == kind }
        return day.copy(unsolved = remaining(AbsenceOverrideCategory.UNSOLVED), ok = remaining(AbsenceOverrideCategory.OK),
            missed = remaining(AbsenceOverrideCategory.MISSED), late = remaining(AbsenceOverrideCategory.LATE),
            soon = remaining(AbsenceOverrideCategory.SOON), school = remaining(AbsenceOverrideCategory.SCHOOL),
            distanceTeaching = remaining(AbsenceOverrideCategory.DISTANCE_TEACHING))
    }
}
