package com.bukovinafilip.gradey

import java.net.URI
import java.net.URLDecoder
import com.bukovinafilip.gradey.model.PlannerItemType

internal data class PlannerDraftInput(val title: String = "", val type: PlannerItemType = PlannerItemType.HOMEWORK, val dueDate: String? = null)
internal data class AppDestination(val tab: AppTab, val plannerItemID: String? = null, val plannerDay: String? = null, val plannerDraft: PlannerDraftInput? = null)

internal fun gradeyDestination(raw: String?): AppDestination? {
    val uri = raw?.let { runCatching { URI(it) }.getOrNull() } ?: return null
    if (uri.scheme?.lowercase() !in listOf("gradey", "gradely")) return null
    return when (uri.host?.lowercase()) {
        "today" -> AppDestination(AppTab.TODAY)
        "marks", "subjects" -> AppDestination(AppTab.SUBJECTS)
        "absence" -> AppDestination(AppTab.ABSENCE)
        "timetable" -> AppDestination(AppTab.TIMETABLE)
        "meals" -> AppDestination(AppTab.STRAVACZ)
        "ai" -> AppDestination(AppTab.AI)
        "updates" -> AppDestination(AppTab.UPDATES)
        "planner" -> {
            val parts = uri.path.orEmpty().split('/').filter(String::isNotEmpty)
            val id = parts.takeIf { it.size == 2 && it[0] == "item" }?.get(1)
            val query = uri.rawQuery.orEmpty().take(4096).split('&').mapNotNull { part ->
                val pair = part.split('=', limit = 2)
                if (pair.size != 2) null else runCatching { pair[0] to URLDecoder.decode(pair[1], "UTF-8") }.getOrNull()
            }.toMap()
            fun day(key: String) = query[key]?.let { runCatching { java.time.LocalDate.parse(it).toString() }.getOrNull() }
            val draft = if (parts == listOf("new")) PlannerDraftInput(
                title = query["title"].orEmpty().take(300),
                type = PlannerItemType.entries.firstOrNull { it.name.equals(query["type"], ignoreCase = true) } ?: PlannerItemType.HOMEWORK,
                dueDate = day("due"),
            ) else null
            AppDestination(AppTab.PERSONAL_PLANNER, id, day("day"), draft)
        }
        else -> null
    }
}
