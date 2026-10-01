package com.bukovinafilip.gradey.feature.timetable

import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.HugeIcon

import android.content.res.Resources
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Use the provider day, including its undated permanent ID, never a synthetic display date. */
internal fun attachedPlannerItems(
    items: List<PersonalPlannerItem>,
    lesson: ScheduledLesson,
    day: ScheduledDay?,
    scope: String?,
): List<PersonalPlannerItem> {
    if (day == null || scope.isNullOrBlank()) return emptyList()
    return items.filter { item ->
        val reference = item.lesson
        !item.isCompleted && reference != null &&
            (item.subject == null || (item.subject?.scope == scope && item.subject?.id == reference.subjectID)) &&
            PersonalPlanner.matches(reference, lesson, day, scope)
    }.sortedWith(compareBy<PersonalPlannerItem> { PersonalPlanner.orderingInstant(it) ?: Instant.MAX }
        .thenBy { it.createdAtEpochMillis }.thenBy { it.id })
}

@Composable
internal fun TimetablePlannerBadge(items: List<PersonalPlannerItem>) {
    val first = items.firstOrNull() ?: return
    val strings = LocalResources.current
    val type = plannerTypeTitle(first.type, strings)
    Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(top = 5.dp).testTag("timetable-planner-badge")) {
        Row(Modifier.padding(horizontal = 7.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            HugeIcon(plannerTypeIcon(first.type), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(if (items.size == 1) type else strings.getString(R.string.timetable_planner_multiple, type, items.size - 1),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
    }
}

@Composable
internal fun LessonPlannerItems(items: List<PersonalPlannerItem>, onOpen: ((String) -> Unit)?, modifier: Modifier = Modifier) {
    val strings = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(strings.getString(R.string.timetable_planner_attached), style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        items.forEach { item ->
            Surface(onClick = { onOpen?.invoke(item.id) }, enabled = onOpen != null,
                color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("timetable-planner-item-${item.id}")) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                                HugeIcon(plannerTypeIcon(item.type), null, Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                                Text(plannerTypeTitle(item.type, strings), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            item.subject?.let { Text(it.abbreviation?.takeIf(String::isNotBlank) ?: it.name,
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            if (item.calendarID != null) HugeIcon(HugeIcons.Calendar03,
                                strings.getString(R.string.timetable_planner_calendar), Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        // An undated permanent lesson has no invented calendar date. A user-chosen
                        // due date remains visible, independently of that original lesson context.
                        val date = PersonalPlanner.orderingDate(item)
                        date?.let {
                            val timed = item.dueTime != null || (item.dueDate == null && item.lesson?.startEpochMillis != null)
                            val schedule = if (timed) PersonalPlanner.orderingInstant(item)?.atZone(PersonalPlanner.zone(item))
                                ?.format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale))
                                else it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                            schedule?.let { text -> Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    if (onOpen != null) HugeIcon(HugeIcons.ArrowRight01, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private fun plannerTypeTitle(type: PlannerItemType, strings: Resources): String = strings.getString(when (type) {
    PlannerItemType.HOMEWORK -> R.string.timetable_planner_homework
    PlannerItemType.TEST -> R.string.timetable_planner_test
    PlannerItemType.PRESENTATION -> R.string.timetable_planner_presentation
    PlannerItemType.NOTE -> R.string.timetable_planner_note
    PlannerItemType.TASK -> R.string.timetable_planner_task
})

private fun plannerTypeIcon(type: PlannerItemType): String = when (type) {
    PlannerItemType.HOMEWORK -> HugeIcons.Edit02
    PlannerItemType.TEST, PlannerItemType.PRESENTATION -> HugeIcons.University
    PlannerItemType.NOTE -> HugeIcons.TaskDaily02
    PlannerItemType.TASK -> HugeIcons.CheckList
}
