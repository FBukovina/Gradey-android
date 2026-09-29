package com.bukovinafilip.gradey.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
enum class AbsenceOverrideCategory(val label: String) {
    UNSOLVED("Unresolved"), OK("Excused"), MISSED("Unexcused"), LATE("Late arrival"),
    SOON("Early departure"), SCHOOL("School event"), DISTANCE_TEACHING("Distance learning");
    val contributesToBase: Boolean get() = this == UNSOLVED || this == OK || this == MISSED
}

@Serializable
data class AbsenceOverrideAllocation(
    val id: String = UUID.randomUUID().toString(),
    val lessonID: String? = null,
    val subjectID: String? = null,
    val subjectName: String,
    val lessonTitle: String = "",
    val category: AbsenceOverrideCategory,
)

@Serializable
enum class AbsenceOverridePauseReason(val description: String) {
    DAY_MISSING("The school no longer reports this day."),
    DAY_CHANGED("The school changed this day's attendance."),
    AMBIGUOUS_DAY("The school reports more than one record for this day."),
    INVALID_ALLOCATION("Review the original lessons and subjects."),
    SUBJECT_MAPPING_CHANGED("A subject can no longer be matched to the school's totals."),
    LESSON_MAPPING_CHANGED("A linked timetable lesson changed."),
    INSUFFICIENT_SUBJECT_COUNT("The school's subject totals no longer match this correction."),
}

@Serializable
data class AbsenceDayOverride(
    val scope: String,
    val dateKey: String,
    val baselineDay: Absence,
    val allocations: List<AbsenceOverrideAllocation>,
    val hiddenAllocationIDs: Set<String>,
    val id: String = UUID.randomUUID().toString(),
    val pauseReason: AbsenceOverridePauseReason? = null,
    val savedAtEpochMillis: Long = System.currentTimeMillis(),
) {
    val hiddenAllocations: List<AbsenceOverrideAllocation> get() = allocations.filter { it.id in hiddenAllocationIDs }
    val hidesEntireDay: Boolean get() = allocations.isNotEmpty() && hiddenAllocationIDs == allocations.map { it.id }.toSet()
}

@Serializable
data class AbsenceOverridePayload(val version: Int = 1, val scopes: Map<String, List<AbsenceDayOverride>>)
