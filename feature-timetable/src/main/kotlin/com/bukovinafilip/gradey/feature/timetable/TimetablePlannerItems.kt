package com.bukovinafilip.gradey.feature.timetable

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.SchoolTimeZone
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.tabular
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

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

/** Green caption-semibold label under a lesson naming its first Planner item, "+N" for more (iOS `LessonCard`). */
@Composable
internal fun TimetablePlannerBadge(items: List<PersonalPlannerItem>, modifier: Modifier = Modifier) {
    val first = items.firstOrNull() ?: return
    val type = plannerTypeTitle(first.type)
    val primary = MaterialTheme.colorScheme.primary
    Row(
        modifier.testTag("timetable-planner-badge"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
    ) {
        GradeyIcon(plannerTypeIcon(first.type), size = 12.dp, tint = primary)
        Text(
            if (items.size == 1) type else stringResource(R.string.timetable_planner_multiple, type, items.size - 1),
            style = GradeyType.captionSemibold, color = primary, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The Planner part of the lesson sheet: the uppercase "PLANNER" header, the lesson's unfinished
 * items as plain rows with a hairline above each, then the gradient "Add to Planner" button
 * (iOS `LessonDetailSheet.plannerSection`, without its card).
 */
@Composable
internal fun LessonPlannerSection(
    items: List<PersonalPlannerItem>,
    onOpen: ((String) -> Unit)?,
    onAdd: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        SectionHeader(stringResource(R.string.timetable_planner_title), Modifier.padding(horizontal = GradeySpacing.xs))
        if (items.isNotEmpty()) {
            Column(Modifier.fillMaxWidth()) {
                items.forEach { item ->
                    key(item.id) {
                        PlainRow(
                            modifier = Modifier.testTag("timetable-planner-item-${item.id}"),
                            onClick = onOpen?.let { open -> { open(item.id) } },
                            horizontalSpacing = GradeySpacing.sm,
                        ) {
                            PlannerItemSummary(item, Modifier.weight(1f))
                            if (onOpen != null) DisclosureIcon()
                        }
                    }
                }
            }
        }
        if (onAdd != null) {
            GradeyPrimaryButton(
                stringResource(R.string.timetable_planner_add), onAdd,
                Modifier.padding(top = GradeySpacing.xs).testTag("lessonAddPlannerButton"),
                icon = HugeIcons.Add01,
            )
        }
    }
}

/** Title, type, subject, Calendar link and due date of one item (iOS `PlannerItemSummary`). */
@Composable
private fun PlannerItemSummary(item: PersonalPlannerItem, modifier: Modifier = Modifier) {
    val format = rememberTimetableDateFormat()
    val schedule = remember(item.dueDate, item.dueTime, item.lesson, item.linkedDate, item.timeZoneID, format) {
        scheduleSummary(item, format)
    }
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        Text(item.title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                GradeyIcon(plannerTypeIcon(item.type), size = 12.dp, tint = secondary)
                Text(plannerTypeTitle(item.type), style = GradeyType.caption, color = secondary)
            }
            item.subject?.let { subject ->
                Text(subject.abbreviation?.takeIf { it.isNotBlank() } ?: subject.name, style = GradeyType.caption, color = secondary)
            }
            if (item.calendarID != null) {
                GradeyIcon(HugeIcons.Calendar03, size = 12.dp, tint = secondary, contentDescription = stringResource(R.string.timetable_planner_calendar))
            }
        }
        if (schedule != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                GradeyIcon(HugeIcons.Clock01, size = 12.dp, tint = secondary)
                Text(schedule, style = GradeyType.caption.tabular(), color = secondary)
            }
        }
    }
}

/**
 * Short day ("19. 10.") plus the time when the item has one. An undated permanent lesson has no
 * invented calendar date; a user-chosen due date stays visible, independently of that lesson.
 */
private fun scheduleSummary(item: PersonalPlannerItem, format: TimetableDateFormat): String? {
    val date = PersonalPlanner.orderingDate(item) ?: return null
    val currentYear = LocalDate.now(SchoolTimeZone).year
    val dueDate = item.dueDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    val dueTime = item.dueTime?.let { runCatching { LocalTime.parse(it) }.getOrNull() }
    if (dueDate != null && dueTime != null) return format.shortDate(dueDate, currentYear) + " " + format.time(dueTime)
    return format.shortDate(date, currentYear)
}

@Composable
private fun plannerTypeTitle(type: PlannerItemType): String = stringResource(
    when (type) {
        PlannerItemType.HOMEWORK -> R.string.timetable_planner_homework
        PlannerItemType.TEST -> R.string.timetable_planner_test
        PlannerItemType.PRESENTATION -> R.string.timetable_planner_presentation
        PlannerItemType.NOTE -> R.string.timetable_planner_note
        PlannerItemType.TASK -> R.string.timetable_planner_task
    },
)

/** iOS `PlannerItemType.systemImage`, through the Hugeicons catalog. */
private fun plannerTypeIcon(type: PlannerItemType): String = when (type) {
    PlannerItemType.HOMEWORK, PlannerItemType.NOTE -> HugeIcons.Book02
    PlannerItemType.TEST -> HugeIcons.Edit02
    PlannerItemType.PRESENTATION -> HugeIcons.UserGroup
    PlannerItemType.TASK -> HugeIcons.CheckList
}
