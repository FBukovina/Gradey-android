package com.bukovinafilip.gradey.model

import kotlinx.serialization.Serializable
import java.time.ZoneId
import java.util.UUID

@Serializable
enum class PlannerItemType { HOMEWORK, TEST, PRESENTATION, NOTE, TASK }

/** Provider identifiers retain their exact spelling and are scoped to their school account. */
@Serializable
data class PlannerSubjectReference(val scope: String, val id: String, val name: String, val abbreviation: String? = null)

@Serializable
data class PlannerLessonReference(
    val scope: String,
    val lessonID: String,
    val dayKey: String,
    val hourID: String,
    val subjectID: String? = null,
    val groupIDs: List<String> = emptyList(),
    val startEpochMillis: Long? = null,
    val endEpochMillis: Long? = null,
    val hourCaption: String,
    val teacher: String? = null,
    val room: String? = null,
)

/** Personal local data survives school changes, sign-out and school-cache clearing. */
@Serializable
data class PersonalPlannerItem(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val notes: String = "",
    val type: PlannerItemType = PlannerItemType.HOMEWORK,
    val subject: PlannerSubjectReference? = null,
    val lesson: PlannerLessonReference? = null,
    val linkedDate: String? = null,
    val dueDate: String? = null,
    val dueTime: String? = null,
    val isCompleted: Boolean = false,
    val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val updatedAtEpochMillis: Long = System.currentTimeMillis(),
    val timeZoneID: String = ZoneId.systemDefault().id,
    /** Null means no Calendar access or automatic export has been requested for this item. */
    val calendarID: Long? = null,
    val calendarTitle: String? = null,
)

@Serializable
data class PersonalPlannerPayload(val version: Int = 1, val items: List<PersonalPlannerItem>)
