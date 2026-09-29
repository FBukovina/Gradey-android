package com.bukovinafilip.gradey.feature.today

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.domain.TodayOverview
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.ui.adaptiveColor
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.ceil

@Composable
internal fun TodayTimetableCard(timetable: TimetableWeek?, today: LocalDate, now: LocalTime, onOpen: () -> Unit) {
    val strings = LocalResources.current
    val overview = remember(timetable, today, now) { TodayOverview.timetable(timetable, today, now) }
    OverviewCard("today-timetable-overview") {
        OverviewHeading(strings.getString(R.string.today_now_and_next), strings.getString(R.string.today_overview_timetable), onOpen)
        if (overview != null && overview.lessons.isNotEmpty()) {
            val listState = rememberLazyListState()
            val focus = overview.current?.id ?: overview.next?.id
            LaunchedEffect(overview.day.id, focus) {
                val index = overview.lessons.indexOfFirst { it.id == focus }
                if (index >= 0) listState.scrollToItem(index)
            }
            LazyRow(state = listState, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("today-lesson-strip")) {
                items(overview.lessons, key = { it.id }) { lesson ->
                    val cancelled = lesson.changeKind == LessonChangeKind.CANCELED
                    val current = lesson.id == overview.current?.id
                    val past = parseTime(lesson.hour.endTime)?.let { it <= now } == true
                    val tint = when {
                        cancelled -> MaterialTheme.colorScheme.error
                        current -> MaterialTheme.colorScheme.onPrimary
                        past -> MaterialTheme.colorScheme.onSurfaceVariant
                        else -> MaterialTheme.colorScheme.primary
                    }
                    val background = when {
                        cancelled -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f)
                        current -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    }
                    val stateLabel = strings.getString(when { cancelled -> R.string.today_overview_cancelled; current -> R.string.today_overview_current; past -> R.string.today_overview_finished; else -> R.string.today_overview_upcoming })
                    val change = lesson.changeKind.takeIf { it != LessonChangeKind.NONE }?.let { strings.getString(changeLabel(it)) }
                    val description = listOf(lessonTitle(lesson, strings), lesson.timeRange, stateLabel, change).filterNotNull().joinToString(", ")
                    Surface(onClick = onOpen, shape = RoundedCornerShape(12.dp), color = background,
                        modifier = Modifier.testTag("today-lesson-${lesson.id}").semantics { contentDescription = description }) {
                        Column(Modifier.widthIn(min = 58.dp).padding(horizontal = 12.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(lesson.hour.caption, style = MaterialTheme.typography.labelSmall, color = tint)
                            Text(lesson.subjectAbbrev?.takeIf { it.isNotBlank() } ?: lessonTitle(lesson, strings),
                                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = tint,
                                textDecoration = if (cancelled) TextDecoration.LineThrough else null)
                            if (change != null && !cancelled) Icon(Icons.Default.WarningAmber, null, Modifier.size(12.dp), tint = tint)
                        }
                    }
                }
            }
        }
        overview?.current?.let { lesson ->
            val minutes = parseTime(lesson.hour.endTime)?.let { ceil(Duration.between(now, it).seconds / 60.0).toInt().coerceAtLeast(0) }
            TodayLessonRow(strings.getString(R.string.today_now_1_s, lessonTitle(lesson, strings)), lesson,
                minutes?.let { strings.getQuantityString(R.plurals.today_overview_minutes_remaining, it, it) }, onOpen)
        }
        overview?.next?.let { lesson ->
            val minutes = if (overview.current == null) parseTime(lesson.hour.beginTime)?.let { ceil(Duration.between(now, it).seconds / 60.0).toInt().coerceAtLeast(0) } else null
            TodayLessonRow(strings.getString(R.string.today_next_1_s, lessonTitle(lesson, strings)), lesson,
                minutes?.let { strings.getQuantityString(R.plurals.today_overview_starts_minutes, it, it) }, onOpen)
        }
        if (overview?.current == null && overview?.next == null) {
            val noActive = overview != null && overview.lessons.none { it.changeKind != LessonChangeKind.CANCELED }
            val (title, subtitle) = when {
                timetable == null -> R.string.today_timetable_unavailable to R.string.today_refresh_or_open_timetable
                overview == null -> R.string.today_no_timetable_for_today to R.string.today_open_timetable_to_view_available_days
                noActive -> R.string.today_no_lessons_today to R.string.today_your_schedule_is_clear_for_today
                overview.untimedLessons -> R.string.today_lesson_times_unavailable to R.string.today_open_timetable_to_view_today_s_lessons
                else -> R.string.today_no_more_lessons_today to R.string.today_open_timetable_to_see_your_next_school
            }
            OverviewInfo(Icons.Default.CalendarMonth, strings.getString(title), strings.getString(subtitle))
        }
        if (!overview?.changedLessons.isNullOrEmpty()) {
            val changes = overview!!.changedLessons
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Text(strings.getQuantityString(R.plurals.today_overview_changes, changes.size, changes.size),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelLarge)
            changes.take(3).forEach { lesson ->
                Surface(onClick = onOpen, color = Color.Transparent, modifier = Modifier.fillMaxWidth().testTag("today-change-${lesson.id}")) {
                    OverviewInfo(Icons.Default.WarningAmber, strings.getString(changeLabel(lesson.changeKind)),
                        listOf(lessonTitle(lesson, strings), lesson.timeRange, lesson.roomTitle, lesson.changeDescription)
                            .filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(" · "), MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun TodayLessonRow(title: String, lesson: ScheduledLesson, timing: String?, onOpen: () -> Unit) {
    Surface(onClick = onOpen, color = Color.Transparent, modifier = Modifier.fillMaxWidth()) {
        OverviewInfo(Icons.Default.Schedule, title,
            listOf(lesson.timeRange, lesson.roomTitle, timing).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "))
    }
}

@Composable
internal fun TodayPlannerCard(items: List<PersonalPlannerItem>, scope: String?, now: Instant, onOpen: () -> Unit, onOpenItem: ((String) -> Unit)?) {
    val strings = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val preview = remember(items, scope, now) { TodayOverview.planner(items, scope, now) }
    OverviewCard("today-planner") {
        OverviewHeading(strings.getString(R.string.today_overview_planner), strings.getString(R.string.today_open), onOpen)
        if (preview.isEmpty()) OverviewInfo(Icons.Default.Checklist, strings.getString(R.string.today_overview_planner_empty), strings.getString(R.string.today_overview_planner_empty_body))
        preview.forEach { item ->
            val date = PersonalPlanner.orderingDate(item)
            val tint = if (item.type in setOf(PlannerItemType.TEST, PlannerItemType.PRESENTATION))
                adaptiveColor(Color(0xFFA86708), Color(0xFFFFC16F)) else MaterialTheme.colorScheme.primary
            val type = strings.getString(when (item.type) {
                PlannerItemType.HOMEWORK -> R.string.today_overview_homework
                PlannerItemType.TEST -> R.string.today_overview_test
                PlannerItemType.PRESENTATION -> R.string.today_overview_presentation
                PlannerItemType.NOTE -> R.string.today_overview_note
                PlannerItemType.TASK -> R.string.today_overview_task
            })
            val hasTime = item.dueTime != null || (item.dueDate == null && item.lesson?.startEpochMillis != null)
            val dateLabel = date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) ?: strings.getString(R.string.today_overview_undated)
            val timeLabel = if (hasTime) PersonalPlanner.orderingInstant(item)?.atZone(PersonalPlanner.zone(item))
                ?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) else null
            Surface(onClick = { onOpenItem?.invoke(item.id) ?: onOpen() }, color = Color.Transparent, shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().testTag("today-planner-${item.id}")) {
                Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(shape = RoundedCornerShape(12.dp), color = tint.copy(alpha = 0.13f)) {
                        Column(Modifier.widthIn(min = 46.dp).padding(horizontal = 8.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            if (date != null) {
                                Text(date.dayOfMonth.toString(), color = tint, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(date.format(DateTimeFormatter.ofPattern("MMM", locale)), color = tint, style = MaterialTheme.typography.labelSmall)
                            } else Icon(Icons.AutoMirrored.Filled.EventNote, null, Modifier.size(28.dp), tint = tint)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(item.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        Text(listOf(type, item.subject?.name, dateLabel, timeLabel).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
internal fun TodayAIActions(onStudyPriorities: (() -> Unit)?, onWeekSummary: (() -> Unit)?) {
    val strings = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(strings.getString(R.string.today_overview_ai_title), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        if (LocalDensity.current.fontScale > 1.2f) {
            onStudyPriorities?.let { AIActionCard(strings.getString(R.string.today_overview_study_priorities), strings.getString(R.string.today_overview_study_priorities_body), Icons.Default.TrackChanges, "today-study-priorities", it, Modifier.fillMaxWidth()) }
            onWeekSummary?.let { AIActionCard(strings.getString(R.string.today_overview_week_summary), strings.getString(R.string.today_overview_week_summary_body), Icons.Default.CalendarMonth, "today-week-summary", it, Modifier.fillMaxWidth()) }
        } else Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
            onStudyPriorities?.let { AIActionCard(strings.getString(R.string.today_overview_study_priorities), strings.getString(R.string.today_overview_study_priorities_body), Icons.Default.TrackChanges, "today-study-priorities", it, Modifier.weight(1f).fillMaxHeight()) }
            onWeekSummary?.let { AIActionCard(strings.getString(R.string.today_overview_week_summary), strings.getString(R.string.today_overview_week_summary_body), Icons.Default.CalendarMonth, "today-week-summary", it, Modifier.weight(1f).fillMaxHeight()) }
        }
    }
}

@Composable
private fun AIActionCard(title: String, body: String, icon: ImageVector, tag: String, onClick: () -> Unit, modifier: Modifier) {
    Surface(onClick = onClick, modifier = modifier.testTag(tag), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(icon, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OverviewCard(tag: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(Modifier.fillMaxWidth().testTag(tag), shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}

@Composable
private fun OverviewHeading(title: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        TextButton(onClick = onClick) { Text(action) }
    }
}

@Composable
private fun OverviewInfo(icon: ImageVector, title: String, detail: String, tint: Color = MaterialTheme.colorScheme.primary) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(24.dp), tint = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun lessonTitle(lesson: ScheduledLesson, strings: android.content.res.Resources): String =
    lesson.subjectName?.takeIf { it.isNotBlank() } ?: lesson.subjectAbbrev?.takeIf { it.isNotBlank() }
    ?: lesson.changeDescription?.takeIf { it.isNotBlank() } ?: strings.getString(R.string.today_overview_lesson, lesson.hour.caption)

private fun changeLabel(kind: LessonChangeKind): Int = when (kind) {
    LessonChangeKind.NONE -> R.string.today_overview_changed
    LessonChangeKind.CANCELED -> R.string.today_overview_cancelled
    LessonChangeKind.SUBSTITUTION -> R.string.today_overview_substitution
    LessonChangeKind.ROOM_CHANGED -> R.string.today_overview_room_changed
    LessonChangeKind.ADDED -> R.string.today_overview_added
}

private fun parseTime(raw: String): LocalTime? {
    val parts = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$").matchEntire(raw.trim()) ?: return null
    return LocalTime.of(parts.groupValues[1].toInt(), parts.groupValues[2].toInt())
}
