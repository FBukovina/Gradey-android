package com.bukovinafilip.gradey.feature.today

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.GradeBand
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.domain.SchoolSourceFreshness
import com.bukovinafilip.gradey.domain.TodayOverview
import com.bukovinafilip.gradey.domain.TodayTimetableOverview
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.TimetableKind
import com.bukovinafilip.gradey.model.TimetableWeek
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeBadge
import com.bukovinafilip.gradey.ui.GradeBadgeSize
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InfoRowContent
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.SchoolTimeZone
import com.bukovinafilip.gradey.ui.SectionFooter
import com.bukovinafilip.gradey.ui.bleedToEdges
import com.bukovinafilip.gradey.ui.brandGradient
import com.bukovinafilip.gradey.ui.color
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.rememberSchoolTime
import com.bukovinafilip.gradey.ui.struck
import com.bukovinafilip.gradey.ui.tabular
import java.text.NumberFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Currency
import java.util.Locale

/** iOS treats a cached timetable older than 15 minutes as stale. */
private const val TimetableStaleMillis = 15 * 60 * 1000L

// MARK: - Section scaffold

/** A plain Today section whose title links to the full screen (iOS `TodaySection`). Rows are [PlainRow]s. */
@Composable
private fun TodaySection(
    title: String,
    modifier: Modifier = Modifier,
    onTitleClick: (() -> Unit)? = null,
    titleTag: String? = null,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        PlainSectionTitle(title, if (titleTag != null) Modifier.testTag(titleTag) else Modifier, onClick = onTitleClick)
        Spacer(Modifier.height(GradeySpacing.sm))
        content()
        if (footer != null) SectionFooter(footer)
    }
}

/** Icon tile + bold title + optional caption in a plain row (iOS `TodayInfoRow`). */
@Composable
private fun TodayInfoRow(icon: String, title: String, tint: Color, modifier: Modifier = Modifier, subtitle: String? = null, onClick: (() -> Unit)? = null) {
    PlainRow(modifier, onClick = onClick) { InfoRowContent(icon, title, subtitle, tint) }
}

/** Accent colour for a change dot or row, reusing the grade palette like iOS. */
@Composable
private fun LessonChangeKind.changeColor(): Color = when (this) {
    LessonChangeKind.NONE -> MaterialTheme.gradeyColors.secondaryLabel
    LessonChangeKind.CANCELED -> GradeyColors.Poor
    LessonChangeKind.SUBSTITUTION -> GradeyColors.Average
    LessonChangeKind.ROOM_CHANGED -> GradeBand.GOOD.color()
    LessonChangeKind.ADDED -> MaterialTheme.gradeyColors.purple
}

private fun LessonChangeKind.labelRes(): Int? = when (this) {
    LessonChangeKind.NONE -> null
    LessonChangeKind.CANCELED -> R.string.today_change_cancelled
    LessonChangeKind.SUBSTITUTION -> R.string.today_change_substitution
    LessonChangeKind.ROOM_CHANGED -> R.string.today_change_room
    LessonChangeKind.ADDED -> R.string.today_change_added
}

// MARK: - Now and next

/**
 * Lesson strip, current/next rows and upcoming changes. This is the only place on Today that reads
 * the school clock at minute resolution, so the rest of the screen never recomposes for time.
 */
@Composable
internal fun NowAndNextSection(
    timetable: TimetableWeek?,
    sources: Map<String, SchoolSourceFreshness>,
    onOpenTimetable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clock = rememberSchoolTime()
    // Lesson boundaries fall on whole minutes, so minute resolution gives the same current/next and countdowns.
    val now by remember { derivedStateOf { clock.value.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES) } }
    val overview = remember(timetable, now) { TodayOverview.timetable(timetable, now.toLocalDate(), now.toLocalTime()) }
    val stale = remember(timetable, sources, now) {
        val nowMillis = now.atZone(SchoolTimeZone).toInstant().toEpochMilli()
        timetable != null && sources[timetableSourceKey(now.toLocalDate())]?.isStale(nowMillis, TimetableStaleMillis) == true
    }
    val time = now.toLocalTime()
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val primary = MaterialTheme.colorScheme.primary

    TodaySection(
        title = stringResource(R.string.today_now_and_next),
        modifier = modifier.testTag("today-timetable-overview"),
        onTitleClick = onOpenTimetable,
        titleTag = "todayOpenTimetableButton",
        footer = if (stale) stringResource(R.string.today_cached_warning) else null,
    ) {
        when {
            timetable == null || timetable.kind != TimetableKind.WEEKLY ->
                TodayInfoRow(HugeIcons.Calendar03, stringResource(R.string.today_timetable_unavailable), secondary)
            // The week is loaded but has no school day today (weekend, holiday).
            overview == null -> TodayInfoRow(HugeIcons.Sun01, stringResource(R.string.today_no_school), primary)
            else -> TodayTimetableRows(overview, time, onOpenTimetable)
        }
    }
}

@Composable
private fun TodayTimetableRows(overview: TodayTimetableOverview, time: LocalTime, onOpenTimetable: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    if (overview.lessons.isNotEmpty()) {
        PlainRow {
            TodayLessonStrip(
                lessons = overview.lessons, currentID = overview.current?.id,
                focusID = overview.current?.id ?: overview.next?.id, now = time,
                onOpen = onOpenTimetable, modifier = Modifier.weight(1f),
            )
        }
    }
    overview.current?.let { lesson ->
        val minutes = parseTime(lesson.hour.endTime)?.let { minutesBetween(time, it) }
        TodayLessonRow(lesson, current = true, timing = minutes?.let { stringResource(R.string.today_minutes_remaining, it) }, onClick = onOpenTimetable)
    }
    overview.next?.let { lesson ->
        val minutes = if (overview.current == null) parseTime(lesson.hour.beginTime)?.let { minutesBetween(time, it) } else null
        val timing = when (val start = lessonStart(lesson, minutes)) {
            is LessonStart.InMinutes -> stringResource(R.string.today_starts_in, start.minutes)
            is LessonStart.AtTime -> stringResource(R.string.today_starts_at, start.time)
            null -> null
        }
        TodayLessonRow(lesson, current = false, timing = timing, onClick = onOpenTimetable)
    }
    if (overview.current == null && overview.next == null) {
        if (overview.lessons.isEmpty()) TodayInfoRow(HugeIcons.Sun01, stringResource(R.string.today_no_school), primary)
        else TodayInfoRow(HugeIcons.CheckmarkCircle02, stringResource(R.string.today_no_more_lessons), primary)
    }
    val changes = overview.changedLessons
    if (changes.isNotEmpty()) {
        val firstSubject = optionalLessonSubject(changes.first()) ?: stringResource(R.string.today_lesson_fallback)
        PlainRow(horizontalSpacing = GradeySpacing.sm) {
            GradeyIcon(HugeIcons.Alert02, size = 14.dp, tint = GradeyColors.Poor)
            Text(
                pluralStringResource(R.plurals.today_changes, changes.size, changes.size, firstSubject),
                Modifier.weight(1f), style = GradeyType.captionBold, color = GradeyColors.Poor,
            )
        }
        val fallbackTitle = stringResource(R.string.today_timetable_change)
        changes.take(3).forEach { lesson ->
            key(lesson.id) {
                TodayInfoRow(
                    HugeIcons.Alert02,
                    lesson.changeKind.labelRes()?.let { stringResource(it) } ?: fallbackTitle,
                    lesson.changeKind.changeColor(),
                    Modifier.testTag("today-change-${lesson.id}"),
                    subtitle = remember(lesson) { changeSubtitle(lesson) },
                    onClick = onOpenTimetable,
                )
            }
        }
    }
}

@Composable
private fun TodayLessonStrip(
    lessons: List<ScheduledLesson>,
    currentID: String?,
    focusID: String?,
    now: LocalTime,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val focusIndex = remember(lessons, focusID) { lessons.indexOfFirst { it.id == focusID } }
    LaunchedEffect(focusIndex) {
        if (focusIndex < 0) return@LaunchedEffect
        // Centre the current (or next) lesson like iOS `scrollTo(_, anchor: .center)`.
        listState.scrollToItem(focusIndex)
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.firstOrNull { it.index == focusIndex } ?: return@LaunchedEffect
        val viewportCenter = (info.viewportStartOffset + info.viewportEndOffset) / 2
        listState.scrollBy((item.offset + item.size / 2 - viewportCenter).toFloat())
    }
    LazyRow(
        state = listState,
        modifier = modifier.bleedToEdges(GradeySpacing.lg).testTag("today-lesson-strip"),
        contentPadding = PaddingValues(horizontal = GradeySpacing.lg, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        items(lessons, key = { it.id }, contentType = { "lessonChip" }) { lesson ->
            TodayLessonChip(lesson, lessonChipState(lesson, currentID, now), onOpen)
        }
    }
}

@Composable
private fun TodayLessonChip(lesson: ScheduledLesson, state: LessonChipState, onClick: () -> Unit) {
    val changeLabel = lesson.changeKind.labelRes()?.let { stringResource(it) }
    val fallback = stringResource(R.string.today_lesson_fallback)
    val description = remember(lesson, changeLabel, fallback) {
        listOfNotNull(lesson.hour.caption, optionalLessonSubject(lesson) ?: fallback, lessonTimeRange(lesson), changeLabel)
            .filter { it.isNotBlank() }.joinToString(" · ")
    }
    val shape = RoundedCornerShape(GradeyRadius.sm)
    val foreground = when (state) {
        LessonChipState.CURRENT -> GradeyColors.OnAccent
        LessonChipState.UPCOMING -> MaterialTheme.colorScheme.onSurface
        LessonChipState.PAST -> MaterialTheme.gradeyColors.secondaryLabel
        LessonChipState.CANCELLED -> GradeyColors.Poor
    }
    val fill = when (state) {
        LessonChipState.CURRENT -> Modifier.background(MaterialTheme.colorScheme.brandGradient, shape)
        LessonChipState.UPCOMING -> Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), shape)
        LessonChipState.PAST -> Modifier.background(MaterialTheme.gradeyColors.tertiaryFill, shape)
        LessonChipState.CANCELLED -> Modifier.background(GradeyColors.Poor.copy(alpha = 0.12f), shape)
    }
    Box(
        Modifier.testTag("today-lesson-${lesson.id}")
            .alpha(if (state == LessonChipState.PAST) 0.62f else 1f)
            .clip(shape).then(fill)
            .semantics { contentDescription = description }
            .clickable(role = Role.Button, onClick = onClick)
            .defaultMinSize(minWidth = 54.dp, minHeight = GradeyMetrics.minTouch),
    ) {
        Column(
            Modifier.align(Alignment.Center).padding(horizontal = 10.dp, vertical = GradeySpacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(lesson.hour.caption, style = GradeyType.caption2Bold.tabular(), color = foreground.copy(alpha = foreground.alpha * 0.8f), maxLines = 1)
            Text(
                remember(lesson) { shortLessonSubject(lesson) }, style = GradeyType.subheadlineBold.struck(state == LessonChipState.CANCELLED),
                color = foreground, maxLines = 1,
            )
        }
        if (lesson.changeKind != LessonChangeKind.NONE && lesson.changeKind != LessonChangeKind.CANCELED) {
            Box(Modifier.align(Alignment.TopEnd).padding(5.dp).size(7.dp).background(lesson.changeKind.changeColor(), CircleShape))
        }
    }
}

@Composable
private fun TodayLessonRow(lesson: ScheduledLesson, current: Boolean, timing: String?, onClick: () -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val tint = if (current) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
    val muted = MaterialTheme.gradeyColors.secondaryLabel
    val role = stringResource(if (current) R.string.today_current_lesson else R.string.today_next_lesson)
    val subject = optionalLessonSubject(lesson) ?: stringResource(R.string.today_lesson_fallback)
    val time = remember(lesson) { lessonTimeRange(lesson) }
    val room = remember(lesson) { lessonRoom(lesson) }
    val teacher = remember(lesson) { lessonTeacher(lesson) }
    PlainRow(Modifier.testTag(if (current) "todayCurrentLessonRow" else "todayNextLessonRow"), onClick = onClick) {
        Box(Modifier.size(38.dp).clip(RoundedCornerShape(GradeyRadius.sm)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Text(lesson.hour.caption, style = GradeyType.headline.tabular(), color = tint, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                Text(role.uppercase(locale), style = GradeyType.caption2Bold, color = tint, maxLines = 1)
                if (timing != null) Text(timing, style = GradeyType.caption2Semibold, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(subject, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (time != null || room != null || teacher != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (time != null) Text(time, style = GradeyType.caption.tabular(), color = muted, maxLines = 1)
                    if (room != null) LessonDetail(HugeIcons.Location01, room, muted)
                    if (teacher != null) LessonDetail(HugeIcons.Teacher, teacher, muted, Modifier.weight(1f, fill = false))
                }
            }
        }
    }
}

@Composable
private fun LessonDetail(icon: String, text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        GradeyIcon(icon, size = 11.dp, tint = color)
        Text(text, style = GradeyType.caption, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - Recent marks

@Composable
internal fun RecentMarksSection(
    subjects: List<Subject>,
    newMarkIDs: Set<String>,
    showsCachedWarning: Boolean,
    onOpenMarks: () -> Unit,
    onOpenSubject: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val marks = remember(subjects) { recentMarks(subjects) }
    val shortDate = remember(locale) { ShortDateFormat(locale) }
    val dates = remember(marks, shortDate) {
        val currentYear = LocalDate.now(SchoolTimeZone).year
        marks.associate { mark -> mark.id to (mark.date?.let { shortDate.format(it, currentYear) } ?: "–") }
    }
    TodaySection(
        title = stringResource(R.string.today_recent_marks),
        modifier = modifier.testTag("today-recent-marks"),
        onTitleClick = onOpenMarks,
        titleTag = "todayOpenMarksButton",
        footer = if (showsCachedWarning) stringResource(R.string.today_cached_warning) else null,
    ) {
        if (marks.isEmpty()) TodayInfoRow(HugeIcons.CheckmarkBadge02, stringResource(R.string.today_no_marks), MaterialTheme.gradeyColors.secondaryLabel)
        marks.forEach { mark ->
            key(mark.id) {
                TodayMarkRow(mark, mark.id in newMarkIDs, dates[mark.id] ?: "–") { onOpenSubject?.invoke(mark.subjectID) ?: onOpenMarks() }
            }
        }
    }
}

/** Day and month only ("31. 5."), with the year when it isn't the current one (iOS `SchoolDateFormatting.shortDate`). */
private class ShortDateFormat(locale: Locale) {
    private val dayMonth = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dM"), locale)
    private val dayMonthYear = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "dMyyyy"), locale)

    fun format(date: LocalDate, currentYear: Int): String = date.format(if (date.year == currentYear) dayMonth else dayMonthYear)
}

@Composable
private fun TodayMarkRow(mark: TodayRecentMark, isNew: Boolean, date: String, onClick: () -> Unit) {
    val muted = MaterialTheme.gradeyColors.secondaryLabel
    PlainRow(Modifier.testTag("todayRecentMark-${mark.id}"), onClick = onClick) {
        GradeBadge(mark.markText, mark.band, size = GradeBadgeSize.SMALL)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    mark.subjectTitle, Modifier.weight(1f, fill = false), style = GradeyType.subheadlineBold,
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (isNew) Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            }
            Text(mark.detail, style = GradeyType.caption, color = muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(date, style = GradeyType.caption.tabular(), color = muted, maxLines = 1)
        DisclosureIcon()
    }
}

// MARK: - Planner

@Composable
internal fun PlannerSection(
    items: List<PersonalPlannerItem>,
    schoolScope: String?,
    onOpenPlanner: () -> Unit,
    onOpenItem: ((String) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val formats = remember(locale) { PlannerDateFormat(locale) }
    val clock = rememberSchoolTime()
    // Recomputed on every clock tick, but only a changed preview recomposes the section.
    val preview by remember(items, schoolScope) { derivedStateOf { TodayOverview.planner(items, schoolScope, clock.value.toInstant()) } }
    TodaySection(
        title = stringResource(R.string.today_planner),
        modifier = modifier.testTag("today-planner"),
        onTitleClick = onOpenPlanner,
        titleTag = "todayOpenPlannerButton",
    ) {
        if (preview.isEmpty()) TodayInfoRow(HugeIcons.CheckList, stringResource(R.string.today_nothing_planned), MaterialTheme.gradeyColors.secondaryLabel)
        preview.forEach { item ->
            key(item.id) { TodayPlannerRow(item, formats) { onOpenItem?.invoke(item.id) ?: onOpenPlanner() } }
        }
    }
}

/** Abbreviated month for the date tile and the short time in the detail line (iOS `TodayPlannerRow.dateStyle`). */
private class PlannerDateFormat(private val locale: Locale) {
    private val month = DateTimeFormatter.ofPattern("LLL", locale)
    val time: DateTimeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

    fun month(date: LocalDate): String = date.format(month).uppercase(locale).trimEnd('.')
}

private fun PlannerItemType.titleRes(): Int = when (this) {
    PlannerItemType.HOMEWORK -> R.string.today_planner_homework
    PlannerItemType.TEST -> R.string.today_planner_test
    PlannerItemType.PRESENTATION -> R.string.today_planner_presentation
    PlannerItemType.NOTE -> R.string.today_planner_note
    PlannerItemType.TASK -> R.string.today_planner_task
}

@Composable
private fun TodayPlannerRow(item: PersonalPlannerItem, formats: PlannerDateFormat, onClick: () -> Unit) {
    val type = stringResource(item.type.titleRes())
    val undated = stringResource(R.string.today_planner_undated)
    val date = remember(item) { PersonalPlanner.orderingDate(item) }
    val month = remember(date, formats) { date?.let(formats::month) }
    val detail = remember(item, date, type, undated, formats) {
        val hasTime = item.dueTime != null || (item.dueDate == null && item.lesson?.startEpochMillis != null)
        val time = if (hasTime) PersonalPlanner.orderingInstant(item)?.atZone(PersonalPlanner.zone(item))?.format(formats.time) else null
        listOfNotNull(type, item.subject?.name?.trim(), if (date == null) undated else null, time)
            .filter { it.isNotBlank() }.joinToString(" · ")
    }
    val tint = if (item.type == PlannerItemType.TEST || item.type == PlannerItemType.PRESENTATION) GradeyColors.Average else MaterialTheme.colorScheme.primary
    PlainRow(Modifier.testTag("today-planner-${item.id}"), onClick = onClick) {
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(GradeyRadius.sm)).background(tint.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            if (date != null) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.dayOfMonth.toString(), style = GradeyType.title3.tabular(), color = tint, maxLines = 1)
                Text(month.orEmpty(), style = GradeyType.caption2Bold, color = tint, maxLines = 1)
            } else GradeyIcon(HugeIcons.Calendar03, size = 22.dp, tint = tint)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(item.title, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DisclosureIcon()
    }
}

// MARK: - Lunch

@Composable
internal fun LunchSection(menu: StravaCZMenu?, onOpenMeals: (() -> Unit)?, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val clock = rememberSchoolTime()
    val today by remember { derivedStateOf { clock.value.toLocalDate() } }
    val meal = remember(menu, today) { todayMeal(menu, today) }
    val currency = remember(locale) {
        NumberFormat.getCurrencyInstance(locale).apply { currency = Currency.getInstance("CZK"); maximumFractionDigits = 0 }
    }
    val price = remember(meal, currency) { meal?.price?.let(currency::format) }
    TodaySection(title = stringResource(R.string.today_lunch), modifier = modifier.testTag("today-lunch")) {
        if (meal != null) TodayInfoRow(
            HugeIcons.Restaurant02, meal.description?.takeIf { it.isNotBlank() } ?: meal.title,
            MaterialTheme.colorScheme.primary, subtitle = price, onClick = onOpenMeals,
        ) else TodayInfoRow(HugeIcons.Restaurant02, stringResource(R.string.today_no_meal), MaterialTheme.gradeyColors.secondaryLabel, onClick = onOpenMeals)
    }
}
