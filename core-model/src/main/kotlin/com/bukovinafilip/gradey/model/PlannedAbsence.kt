package com.bukovinafilip.gradey.model

import kotlinx.serialization.Serializable

/** A local what-if plan. Saving a plan never submits an absence to the school. */
@Serializable
data class PlannedAbsence(
    val date: String,
    val lessons: List<PlannedAbsenceLesson>,
)

/** Preserve the selected timetable lessons so plans survive week changes and offline use. */
@Serializable
data class PlannedAbsenceLesson(
    val id: String,
    val hourID: String,
    val subjectKey: String,
    val subjectName: String,
    val timeRange: String = "",
    val startTime: String = "",
)
