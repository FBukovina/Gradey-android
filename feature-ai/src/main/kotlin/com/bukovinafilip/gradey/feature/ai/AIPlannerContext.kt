package com.bukovinafilip.gradey.feature.ai

import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import java.time.Instant
import java.util.Locale

internal data class AIPlannerEvent(
    val id: String, val title: String, val type: String, val subjectID: String?, val subjectName: String?,
    val date: String, val hasTime: Boolean, val notes: String?, val expiresAt: Long,
) {
    val isAssessment get() = type in setOf("test", "presentation")
    fun payload(includeNotes: Boolean) = mapOf("id" to id, "title" to title, "type" to type,
        "subjectID" to subjectID, "subjectName" to subjectName, "date" to date, "hasTime" to hasTime,
        "notes" to notes?.takeIf { includeNotes }?.take(1_000))
}

internal object AIPlannerContext {
    fun events(items: List<PersonalPlannerItem>, scope: String): List<AIPlannerEvent> = items.mapNotNull { item ->
        if (item.isCompleted) return@mapNotNull null
        val subjectScope = item.subject?.scope
        val lessonScope = item.lesson?.scope
        if (subjectScope != null && lessonScope != null && subjectScope != lessonScope) return@mapNotNull null
        val itemScope = subjectScope ?: lessonScope
        if (itemScope != null && itemScope != scope) return@mapNotNull null
        val instant = PersonalPlanner.orderingInstant(item) ?: return@mapNotNull null
        val zone = PersonalPlanner.zone(item)
        val hasTime = item.dueTime != null || (item.dueDate == null && item.lesson?.startEpochMillis != null)
        AIPlannerEvent(item.id, item.title.take(200), item.type.name.lowercase(Locale.ROOT), item.subject?.id,
            item.subject?.name?.take(120), instant.atZone(zone).toOffsetDateTime().toString(), hasTime,
            item.notes.takeIf(String::isNotBlank),
            if (hasTime) instant.toEpochMilli() else instant.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli())
    }.sortedWith(compareBy<AIPlannerEvent> { it.date }.thenBy { it.id })
}
