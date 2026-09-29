package com.bukovinafilip.gradey.model

import kotlinx.serialization.Serializable

@Serializable
data class PlannerCalendarMapping(
    val itemID: String,
    val calendarID: Long,
    val ownershipToken: String,
    val eventID: Long? = null,
    val attempted: Boolean = false,
    val syncedRevision: String? = null,
)

/** Mappings outlive deleted items until their owned events have been removed. No item notes are retained. */
@Serializable
data class PlannerCalendarState(val version: Int = 1, val mappings: List<PlannerCalendarMapping> = emptyList())

data class PlannerWritableCalendar(val id: Long, val title: String, val accountName: String)
