package com.bukovinafilip.gradey.feature.today

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.domain.TodayInsight
import com.bukovinafilip.gradey.domain.TodayInsightCopy
import com.bukovinafilip.gradey.domain.TodayInsightKind
import com.bukovinafilip.gradey.domain.TodayOverview
import com.bukovinafilip.gradey.domain.TodayTimetableOverview
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeyCard
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.IconTile
import com.bukovinafilip.gradey.ui.InfoRow
import com.bukovinafilip.gradey.ui.LinkPill
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.SectionHeaderRow
import com.bukovinafilip.gradey.ui.bleedHorizontally
import com.bukovinafilip.gradey.ui.brandGradient
import com.bukovinafilip.gradey.ui.gradeyColors
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Accent colour for a change chip and lesson emphasis, reusing the grade palette like iOS. */
@Composable
internal fun LessonChangeKind.changeColor(): Color = when (this) {
    LessonChangeKind.NONE -> MaterialTheme.colorScheme.onSurfaceVariant
    LessonChangeKind.CANCELED -> GradeyColors.Poor
    LessonChangeKind.SUBSTITUTION -> GradeyColors.Average
    LessonChangeKind.ROOM_CHANGED -> GradeyColors.Good
    LessonChangeKind.ADDED -> MaterialTheme.gradeyColors.purple
}

// MARK: - Now and next

@Composable
internal fun TodayTimetableCard(timetable: TimetableWeek?, overview: TodayTimetableOverview?, today: LocalDate, now: LocalTime, onOpen: () -> Unit) {
    val strings = LocalResources.current
    GradeyCard(Modifier.testTag("today-timetable-overview")) {
        SectionHeaderRow(strings.getString(R.string.today_now_and_next)) {
            LinkPill(strings.getString(R.string.today_overview_timetable), onOpen, testTag = "todayOpenTimetableButton")
        }
        if (overview != null && overview.lessons.isNotEmpty()) TodayLessonStrip(overview, now, onOpen)
        overview?.current?.let { lesson ->
            val minutes = parseTime(lesson.hour.endTime)?.let { minutesBetween(now, it) }
            TodayLessonRow(lesson, current = true, minutes?.let { strings.getString(R.string.today_summary_remaining, it) }, onOpen)
        }
        overview?.next?.let { lesson ->
            val minutes = if (overview.current == null) parseTime(lesson.hour.beginTime)?.let { minutesBetween(now, it) } else null
            TodayLessonRow(lesson, current = false, startsIn(lesson, minutes, strings), onOpen)
        }
        if (overview?.current == null && overview?.next == null) {
            val noActive = overview != null && overview.lessons.none { it.changeKind != LessonChangeKind.CANCELED }
            val muted = MaterialTheme.colorScheme.onSurfaceVariant
            when {
                timetable == null -> InfoRow(HugeIcons.Calendar03, strings.getString(R.string.today_timetable_unavailable), strings.getString(R.string.today_timetable_unavailable_subtitle), tint = muted)
                overview == null || noActive -> InfoRow(HugeIcons.Sun01, strings.getString(R.string.today_summary_empty_title), strings.getString(R.string.today_summary_empty_message))
                overview.untimedLessons && overview.lessons.none { parseTime(it.hour.beginTime) != null } ->
                    InfoRow(HugeIcons.Calendar03, strings.getString(R.string.today_lesson_times_unavailable), strings.getString(R.string.today_open_timetable_to_view_today_s_lessons), tint = muted)
                else -> InfoRow(HugeIcons.CheckmarkCircle02, strings.getString(R.string.today_no_more_lessons), strings.getString(R.string.today_no_more_lessons_subtitle))
            }
        }
        val changes = overview?.changedLessons.orEmpty()
        if (changes.isNotEmpty()) {
            HorizontalDivider(color = MaterialTheme.gradeyColors.separator)
            val summary = if (changes.size == 1) strings.getQuantityString(R.plurals.today_summary_changes, 1, 1, lessonSubject(changes.first(), strings))
                else strings.getQuantityString(R.plurals.today_summary_changes, changes.size, changes.size, "")
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                GradeyIcon(HugeIcons.Alert02, size = 14.dp, tint = GradeyColors.Poor)
                Text(summary, style = GradeyType.captionBold, color = GradeyColors.Poor)
            }
            changes.take(3).forEach { lesson ->
                Surface(onClick = onOpen, color = Color.Transparent, shape = RoundedCornerShape(GradeyRadius.sm), modifier = Modifier.fillMaxWidth().testTag("today-change-${lesson.id}")) {
                    InfoRow(
                        HugeIcons.Alert02, strings.getString(changeLabel(lesson.changeKind)),
                        listOf(lessonSubject(lesson, strings), lesson.hour.caption, lesson.timeRange, lesson.roomTitle).filterNotNull().filter { it.isNotBlank() }.distinct().joinToString(" · "),
                        tint = lesson.changeKind.changeColor(),
                    )
                }
            }
        }
    }
}

@Composable
private fun TodayLessonStrip(overview: TodayTimetableOverview, now: LocalTime, onOpen: () -> Unit) {
    val strings = LocalResources.current
    val listState = rememberLazyListState()
    val focus = overview.current?.id ?: overview.next?.id
    LaunchedEffect(overview.day.id, focus) {
        val index = overview.lessons.indexOfFirst { it.id == focus }
        if (index >= 0) listState.scrollToItem(index)
    }
    LazyRow(
        state = listState, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
        contentPadding = PaddingValues(horizontal = GradeySpacing.lg),
        modifier = Modifier.fillMaxWidth().bleedHorizontally(GradeySpacing.lg).testTag("today-lesson-strip"),
    ) {
        items(overview.lessons, key = { it.id }) { lesson ->
            val cancelled = lesson.changeKind == LessonChangeKind.CANCELED
            val current = lesson.id == overview.current?.id
            val past = parseTime(lesson.hour.endTime)?.let { it <= now } == true
            val foreground = when {
                cancelled -> GradeyColors.Poor
                current -> GradeyColors.OnAccent
                past -> MaterialTheme.colorScheme.onSurfaceVariant
                else -> MaterialTheme.colorScheme.onSurface
            }
            val background = when {
                cancelled -> GradeyColors.Poor.copy(alpha = 0.12f)
                current -> Color.Transparent
                past -> MaterialTheme.gradeyColors.tertiaryFill
                else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
            }
            val stateLabel = strings.getString(when { cancelled -> R.string.today_overview_cancelled; current -> R.string.today_overview_current; past -> R.string.today_overview_finished; else -> R.string.today_overview_upcoming })
            val change = lesson.changeKind.takeIf { it != LessonChangeKind.NONE }?.let { strings.getString(changeLabel(it)) }
            val description = listOf(lessonSubject(lesson, strings), lesson.timeRange, stateLabel, change).filterNotNull().joinToString(", ")
            val shape = RoundedCornerShape(GradeyRadius.sm)
            Box(
                Modifier.testTag("today-lesson-${lesson.id}").semantics { contentDescription = description }
                    .alpha(if (past && !current) 0.62f else 1f)
                    .clip(shape).background(if (current) MaterialTheme.colorScheme.brandGradient else androidx.compose.ui.graphics.SolidColor(background))
                    .clickable(onClick = onOpen)
                    .defaultMinSize(minWidth = 54.dp, minHeight = 44.dp),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = GradeySpacing.sm).align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(lesson.hour.caption, style = GradeyType.caption2Bold, color = foreground.copy(alpha = 0.8f))
                    Text(lesson.subjectAbbrev?.takeIf { it.isNotBlank() } ?: lesson.changeDescription?.takeIf { it.isNotBlank() }?.take(6) ?: "–",
                        style = GradeyType.subheadlineBold, color = foreground, maxLines = 1,
                        textDecoration = if (cancelled) TextDecoration.LineThrough else null)
                }
                if (lesson.changeKind != LessonChangeKind.NONE && !cancelled) Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(7.dp).clip(CircleShape).background(lesson.changeKind.changeColor()))
            }
        }
    }
}

@Composable
private fun TodayLessonRow(lesson: ScheduledLesson, current: Boolean, timing: String?, onOpen: () -> Unit) {
    val strings = LocalResources.current
    val tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    Surface(onClick = onOpen, color = Color.Transparent, shape = RoundedCornerShape(GradeyRadius.sm), modifier = Modifier.fillMaxWidth().testTag(if (current) "todayCurrentLessonRow" else "todayNextLessonRow")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(GradeyRadius.sm)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                Text(lesson.hour.caption, style = GradeyType.headline.copy(fontWeight = FontWeight.Bold), color = tint)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    Text(strings.getString(if (current) R.string.today_current_lesson else R.string.today_next_lesson).uppercase(), style = GradeyType.caption2Bold.copy(letterSpacing = 0.4.sp), color = tint)
                    if (timing != null) Text(timing, style = GradeyType.caption2.copy(fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(lessonSubject(lesson, strings), style = GradeyType.subheadlineBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val muted = MaterialTheme.colorScheme.onSurfaceVariant
                    if (lesson.timeRange.isNotBlank()) Text(lesson.timeRange, style = GradeyType.caption, color = muted, maxLines = 1)
                    lesson.roomTitle?.takeIf { it.isNotBlank() }?.let { room ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                            GradeyIcon(HugeIcons.Location01, size = 11.dp, tint = muted); Text(room, style = GradeyType.caption, color = muted, maxLines = 1)
                        }
                    }
                    (lesson.teacherName ?: lesson.teacherAbbrev)?.takeIf { it.isNotBlank() }?.let { teacher ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp), modifier = Modifier.weight(1f, fill = false)) {
                            GradeyIcon(HugeIcons.Teacher, size = 11.dp, tint = muted); Text(teacher, style = GradeyType.caption, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

// MARK: - Attention rows

@Composable
internal fun TodayInsightRow(insight: TodayInsight, onOpen: (TodayInsight) -> Unit) {
    val strings = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val tint = when (insight.kind) {
        TodayInsightKind.ASSESSMENT, TodayInsightKind.BUSY_TESTS -> GradeyColors.Average
        TodayInsightKind.DEADLINE -> MaterialTheme.gradeyColors.purple
        TodayInsightKind.WORSENING_TREND -> GradeyColors.Poor
        TodayInsightKind.AVERAGE_CHANGE, TodayInsightKind.RECENT_GRADE -> MaterialTheme.colorScheme.primary
    }
    val icon = when (insight.kind) {
        TodayInsightKind.ASSESSMENT -> HugeIcons.Edit02
        TodayInsightKind.DEADLINE -> HugeIcons.CheckList
        TodayInsightKind.BUSY_TESTS -> HugeIcons.TaskDaily02
        TodayInsightKind.AVERAGE_CHANGE -> HugeIcons.ChartLineData01
        TodayInsightKind.WORSENING_TREND -> HugeIcons.ChartDown
        TodayInsightKind.RECENT_GRADE -> HugeIcons.CheckmarkBadge02
    }
    val resource = when (insight.copy) {
        TodayInsightCopy.ASSESSMENT_DUE -> R.string.insight_assessment_due
        TodayInsightCopy.DEADLINE_DUE -> R.string.insight_deadline_due
        TodayInsightCopy.AVERAGE_CHANGED -> R.string.insight_average_changed
        TodayInsightCopy.BUSY_TESTS -> R.string.insight_busy_tests
        TodayInsightCopy.TREND_WORSENING -> R.string.insight_trend_worsening
        TodayInsightCopy.GRADE_NEW -> R.string.insight_grade_new
        TodayInsightCopy.GRADE_EDITED -> R.string.insight_grade_edited
    }
    val number = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
    val title = if (insight.copy == TodayInsightCopy.AVERAGE_CHANGED) strings.getString(resource, insight.titleValue,
        insight.previousAverage?.let(number::format).orEmpty(), insight.average?.let(number::format).orEmpty()) else strings.getString(resource, insight.titleValue)
    val subtitle = if (insight.eventIDs.isNotEmpty()) Instant.ofEpochMilli(insight.relevantAt)
        .atZone(insight.dateZoneID?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: ZoneId.of("Europe/Prague"))
        .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        else strings.getString(R.string.today_insight_recorded_average)
    Surface(onClick = { onOpen(insight) }, Modifier.fillMaxWidth().testTag("today-insight-${insight.id}"), color = Color.Transparent, shape = RoundedCornerShape(GradeyRadius.sm)) {
        InfoRow(icon, title, subtitle, Modifier.padding(vertical = 4.dp), tint = tint) { DisclosureIcon() }
    }
}

// MARK: - Planner

@Composable
internal fun TodayPlannerCard(items: List<PersonalPlannerItem>, scope: String?, now: Instant, onOpen: () -> Unit, onOpenItem: ((String) -> Unit)?) {
    val strings = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val preview = remember(items, scope, now) { TodayOverview.planner(items, scope, now) }
    GradeyCard(Modifier.testTag("today-planner")) {
        SectionHeaderRow(strings.getString(R.string.today_overview_planner)) { LinkPill(strings.getString(R.string.today_open), onOpen, testTag = "todayOpenPlannerButton") }
        if (preview.isEmpty()) InfoRow(HugeIcons.CheckList, strings.getString(R.string.today_planner_title), strings.getString(R.string.today_overview_planner_empty_body), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        preview.forEach { item ->
            val date = PersonalPlanner.orderingDate(item)
            val tint = if (item.type in setOf(PlannerItemType.TEST, PlannerItemType.PRESENTATION)) GradeyColors.Average else MaterialTheme.colorScheme.primary
            val type = strings.getString(when (item.type) {
                PlannerItemType.HOMEWORK -> R.string.today_overview_homework
                PlannerItemType.TEST -> R.string.today_overview_test
                PlannerItemType.PRESENTATION -> R.string.today_overview_presentation
                PlannerItemType.NOTE -> R.string.today_overview_note
                PlannerItemType.TASK -> R.string.today_overview_task
            })
            val hasTime = item.dueTime != null || (item.dueDate == null && item.lesson?.startEpochMillis != null)
            val timeLabel = if (hasTime) PersonalPlanner.orderingInstant(item)?.atZone(PersonalPlanner.zone(item))
                ?.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) else null
            Surface(onClick = { onOpenItem?.invoke(item.id) ?: onOpen() }, color = Color.Transparent, shape = RoundedCornerShape(GradeyRadius.sm),
                modifier = Modifier.fillMaxWidth().testTag("today-planner-${item.id}")) {
                Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(GradeyRadius.sm)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                        if (date != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(date.dayOfMonth.toString(), style = GradeyType.title3.copy(fontWeight = FontWeight.Bold), color = tint)
                            Text(date.format(DateTimeFormatter.ofPattern("MMM", locale)).uppercase(locale).trimEnd('.'), style = GradeyType.caption2Bold, color = tint)
                        } else GradeyIcon(HugeIcons.Calendar03, size = 22.dp, tint = tint)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title, style = GradeyType.subheadlineBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(listOf(type, item.subject?.name, if (date == null) strings.getString(R.string.today_overview_undated) else null, timeLabel).filterNotNull().filter { it.isNotBlank() }.joinToString(" · "),
                            style = GradeyType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    DisclosureIcon()
                }
            }
        }
    }
}

// MARK: - Ask Gradey

@Composable
internal fun TodayAIActions(onStudyPriorities: (() -> Unit)?, onWeekSummary: (() -> Unit)?) {
    val strings = LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        SectionHeader(strings.getString(R.string.today_overview_ai_title), Modifier.padding(horizontal = GradeySpacing.xs))
        val priorities: @Composable (Modifier) -> Unit = { m -> onStudyPriorities?.let { AIActionCard(strings.getString(R.string.today_overview_study_priorities), strings.getString(R.string.today_overview_study_priorities_body), HugeIcons.Target02, "today-study-priorities", it, m) } }
        val week: @Composable (Modifier) -> Unit = { m -> onWeekSummary?.let { AIActionCard(strings.getString(R.string.today_overview_week_summary), strings.getString(R.string.today_overview_week_summary_body), HugeIcons.CalendarCheckIn02, "today-week-summary", it, m) } }
        if (LocalDensity.current.fontScale > 1.3f) Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) { priorities(Modifier.fillMaxWidth()); week(Modifier.fillMaxWidth()) }
        else Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm), modifier = Modifier.height(IntrinsicSize.Min)) {
            if (onStudyPriorities != null) priorities(Modifier.weight(1f).fillMaxHeight())
            if (onWeekSummary != null) week(Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun AIActionCard(title: String, body: String, icon: String, tag: String, onClick: () -> Unit, modifier: Modifier) {
    val shape = RoundedCornerShape(GradeyRadius.md)
    Surface(onClick = onClick, modifier = modifier.testTag(tag).heightIn(min = 44.dp), shape = shape, color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.18f))) {
        Row(Modifier.padding(GradeySpacing.md), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(34.dp).clip(CircleShape).background(MaterialTheme.colorScheme.brandGradient), contentAlignment = Alignment.Center) {
                GradeyIcon(icon, size = 16.dp, tint = GradeyColors.OnAccent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = GradeyType.subheadlineBold)
                Text(body, style = GradeyType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

internal fun changeLabel(kind: LessonChangeKind): Int = when (kind) {
    LessonChangeKind.NONE -> R.string.today_overview_changed
    LessonChangeKind.CANCELED -> R.string.today_overview_cancelled
    LessonChangeKind.SUBSTITUTION -> R.string.today_overview_substitution
    LessonChangeKind.ROOM_CHANGED -> R.string.today_overview_room_changed
    LessonChangeKind.ADDED -> R.string.today_overview_added
}
