package com.bukovinafilip.gradey.feature.timetable

import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.HugeIcon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.MaterialTheme
import com.bukovinafilip.gradey.ui.adaptiveColor
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.brandGradient
import com.bukovinafilip.gradey.ui.gradeyColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bukovinafilip.gradey.domain.TimetableDates
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.ui.rememberSchoolTime
import com.bukovinafilip.gradey.ui.SchoolTimeZone
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.TimetableKind
import com.bukovinafilip.gradey.model.TimetableWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.Locale

private val AccentTeal: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val MutedText: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val MutedLight: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
private val SubjectTile: Color
    @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
private val NoticeRed = GradeyColors.Poor
private val DividerColor: Color
    @Composable get() = MaterialTheme.gradeyColors.separator

private data class TimetableDaySlot(
    val date: LocalDate,
    val day: ScheduledDay?,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    week: TimetableWeek?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onChangeWeek: (String) -> Unit,
    onOpenAccount: () -> Unit,
    onOpenGradeyTools: () -> Unit,
    modifier: Modifier = Modifier,
    requestedWeek: String? = null,
    onOpenPlanner: (() -> Unit)? = null,
    onPlanDay: ((ScheduledDay) -> Unit)? = null,
    onPlanLesson: ((ScheduledLesson, ScheduledDay) -> Unit)? = null,
    onAskWeekAI: ((String) -> Unit)? = null,
    kind: TimetableKind = TimetableKind.WEEKLY,
    onChangeKind: ((TimetableKind) -> Unit)? = null,
    errorMessage: String? = null,
    plannerItems: List<PersonalPlannerItem> = emptyList(),
    schoolScope: String? = null,
    onOpenPlannerItem: ((String) -> Unit)? = null,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val schoolTime by rememberSchoolTime()
    val monday = remember(week?.weekStart, requestedWeek) {
        requestedWeek?.let { runCatching { com.bukovinafilip.gradey.domain.TimetableDates.monday(LocalDate.parse(it.take(10))) }.getOrNull() } ?: weekMonday(week)
    }
    val isPermanent = kind == TimetableKind.PERMANENT
    val displayedWeek = week?.takeIf { it.kind == kind && (isPermanent || weekMonday(it) == monday) }
    val daySlots = remember(displayedWeek, monday) { timetableDaySlots(displayedWeek, monday) }
    val initialDate = if (isPermanent) monday else daySlots.firstOrNull { it.date == schoolTime.toLocalDate() }?.date ?: monday
    var selectedDate by rememberSaveable(monday.toString(), kind, schoolScope) { mutableStateOf(initialDate.toString()) }
    var selectedLesson by remember(kind, monday, schoolScope) { mutableStateOf<ScheduledLesson?>(null) }
    val selectedDay = daySlots.firstOrNull { it.date.toString() == selectedDate }?.day
    val plannerByLesson = remember(plannerItems, selectedDay, schoolScope) {
        selectedDay?.lessons.orEmpty().associate { lesson ->
            lesson.id to attachedPlannerItems(plannerItems, lesson, selectedDay, schoolScope)
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 8.dp),
            contentPadding = PaddingValues(bottom = com.bukovinafilip.gradey.ui.floatingNavigationContentPadding()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item(key = "timetable_controls") {
                Column {
                    com.bukovinafilip.gradey.ui.GradeyPageHeader(
                        title = strings.getString(R.string.timetable_timetable),
                        modifier = Modifier.padding(horizontal = 16.dp),
                        onOpenAI = onOpenGradeyTools, aiDescription = strings.getString(R.string.timetable_open_gradey_tools),
                        onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = strings.getString(R.string.timetable_refresh_timetable),
                        onOpenSettings = onOpenAccount, settingsDescription = strings.getString(R.string.timetable_open_account),
                    )
                    Spacer(Modifier.height(8.dp))
                    onChangeKind?.let { selectKind ->
                        FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(!isPermanent, { selectKind(TimetableKind.WEEKLY) }, label = { Text(strings.getString(R.string.timetable_weekly)) })
                            FilterChip(isPermanent, { selectKind(TimetableKind.PERMANENT) }, label = { Text(strings.getString(R.string.timetable_permanent)) })
                        }
                    }
                    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End,
                        maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.15f) 1 else 2) {
                        onAskWeekAI?.takeIf { !isPermanent }?.let { askAI -> TextButton(onClick = { askAI(monday.toString()) }) { Text(strings.getString(R.string.timetable_plan_week_with_ai), color = AccentTeal) } }
                        onOpenPlanner?.let { openPlanner -> TextButton(onClick = openPlanner) { Text(strings.getString(R.string.timetable_open_planner), color = AccentTeal) } }
                    }
                    Spacer(Modifier.height(12.dp))
                    if (isPermanent) {
                        Text(strings.getString(R.string.timetable_permanent_description), modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp), color = MutedText, fontSize = 13.sp)
                    } else WeekNavigator(
                        monday = monday,
                        enabled = !isRefreshing,
                        onPrevious = { onChangeWeek(monday.minusWeeks(1).toString()) },
                        onNext = { onChangeWeek(monday.plusWeeks(1).toString()) },
                    )
                    if (!isPermanent && monday != TimetableDates.monday(schoolTime.toLocalDate())) {
                        TextButton(onClick = { onChangeWeek(TimetableDates.monday(schoolTime.toLocalDate()).toString()) }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                            Text(strings.getString(R.string.timetable_back_to_this_week), color = AccentTeal)
                        }
                    }
                    errorMessage?.let { message ->
                        Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(message, color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                                TextButton(onClick = onRefresh, enabled = !isRefreshing) { Text(strings.getString(R.string.timetable_retry)) }
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    DayStrip(
                        daySlots = daySlots,
                        selectedDate = selectedDate,
                        onSelect = { selectedDate = it.toString() },
                        showDates = !isPermanent,
                    )
                    Spacer(Modifier.height(11.dp))
                    HorizontalDivider(thickness = 0.5.dp, color = DividerColor.copy(alpha = 0.62f))
                }
            }
            timetableLessons(
                strings = strings,
                day = selectedDay,
                isLoaded = displayedWeek != null,
                isPermanent = isPermanent,
                selectedDate = LocalDate.parse(selectedDate),
                today = schoolTime.toLocalDate(),
                currentTime = schoolTime.toLocalTime(),
                onPlanDay = onPlanDay,
                onOpenLesson = { selectedLesson = it },
                plannerByLesson = plannerByLesson,
            )
        }
    }

    selectedLesson?.let { lesson ->
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { selectedLesson = null },
            modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp),
            sheetState = sheetState,
            shape = RoundedCornerShape(28.dp),
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 12.dp,
            scrimColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.20f),
            dragHandle = null,
            contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        ) {
            LessonDetailSheet(
                lesson = lesson,
                onPlanLesson = if (onPlanLesson != null && selectedDay != null) ({ onPlanLesson(lesson, selectedDay) }) else null,
                plannerItems = attachedPlannerItems(plannerItems, lesson, selectedDay, schoolScope),
                onOpenPlannerItem = onOpenPlannerItem?.let { open -> { itemID: String ->
                    selectedLesson = null
                    open(itemID)
                } },
            )
        }
    }
}

@Composable
private fun WeekNavigator(
    monday: LocalDate,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        WeekArrow(
            description = strings.getString(R.string.timetable_previous_week),
            enabled = enabled,
            onClick = onPrevious,
        ) {
            HugeIcon(HugeIcons.ArrowLeft01, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(24.dp))
        }
        Text(
            text = formatWeekRange(monday, locale),
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )
        WeekArrow(
            description = strings.getString(R.string.timetable_next_week),
            enabled = enabled,
            onClick = onNext,
        ) {
            HugeIcon(HugeIcons.ArrowRight01, contentDescription = null, tint = AccentTeal, modifier = Modifier.size(24.dp))
        }
    }
}

@Composable
private fun WeekArrow(
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier
            .size(40.dp)
            .semantics { contentDescription = description },
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 0.12f else 0.06f),
    ) {
        Box(contentAlignment = Alignment.Center) { icon() }
    }
}

@Composable
private fun DayStrip(
    daySlots: List<TimetableDaySlot>,
    selectedDate: String,
    onSelect: (LocalDate) -> Unit,
    showDates: Boolean = true,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val WeekdayFormatter = DateTimeFormatter.ofPattern("EEE", locale)
    val dayHeight = if (showDates) (26 + 40 * LocalDensity.current.fontScale).dp else (22 + 16 * LocalDensity.current.fontScale).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(dayHeight)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        daySlots.forEach { slot ->
            val selected = slot.date.toString() == selectedDate
            val description = if (showDates) "${slot.date.format(WeekdayFormatter)}, ${slot.date.dayOfMonth}" else slot.date.format(WeekdayFormatter)
            val shape = RoundedCornerShape(17.dp)
            Box(
                modifier = Modifier
                    .size(width = (32 + 20 * LocalDensity.current.fontScale).dp, height = dayHeight)
                    .clip(shape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.brandGradient
                        } else {
                            Brush.linearGradient(listOf(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.surface))
                        },
                    )
                    .clickable(
                        role = Role.Tab,
                        onClickLabel = strings.getString(R.string.timetable_show_1_s, description),
                        onClick = { onSelect(slot.date) },
                    )
                    .semantics {
                        contentDescription = description
                        this.selected = selected
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(9.dp))
                    Text(
                        text = slot.date.format(WeekdayFormatter).uppercase(locale),
                        color = if (selected) Color(0xFF041816) else MaterialTheme.colorScheme.onSurface,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    if (showDates) Text(
                        text = slot.date.dayOfMonth.toString(),
                        color = if (selected) Color(0xFF041816) else MaterialTheme.colorScheme.onSurface,
                        fontSize = 20.sp,
                        lineHeight = 24.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    val hasNotice = slot.day?.dayType.equals("notice", ignoreCase = true) ||
                        slot.day?.lessons?.any { it.changeKind != LessonChangeKind.NONE } == true
                    if (!selected && hasNotice) {
                        Spacer(Modifier.height(1.dp))
                        Box(Modifier.size(5.dp).background(NoticeRed, CircleShape))
                    }
                }
            }
        }
    }
}

private fun LazyListScope.timetableLessons(
    strings: android.content.res.Resources,
    day: ScheduledDay?,
    isLoaded: Boolean,
    isPermanent: Boolean,
    selectedDate: LocalDate,
    today: LocalDate,
    currentTime: LocalTime,
    onPlanDay: ((ScheduledDay) -> Unit)?,
    onOpenLesson: (ScheduledLesson) -> Unit,
    plannerByLesson: Map<String, List<PersonalPlannerItem>>,
) {
    val lessons = day?.lessons.orEmpty().sortedBy { it.hour.beginTime.asSchoolTime() ?: LocalTime.MAX }
    if (isLoaded && day != null && !isPermanent) {
        item {
            DaySummaryCard(day, selectedDate == today, currentTime, Modifier.padding(horizontal = 16.dp), onPlanDay?.let { { it(day) } })
        }
    }
    if (!isLoaded || lessons.isEmpty()) {
        item {
            Surface(
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp)
                    .fillMaxWidth()
                    .heightIn(min = 76.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = if (isLoaded) day?.dayDescription?.takeIf { it.isNotBlank() } ?: strings.getString(R.string.timetable_no_lessons_scheduled_2) else strings.getString(R.string.timetable_timetable_unavailable_tap_refresh),
                        color = MutedText,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    } else {
        items(lessons, key = ScheduledLesson::id) { lesson ->
            Box(Modifier.padding(end = 16.dp)) {
                LessonRow(lesson = lesson, isCurrent = !isPermanent && selectedDate == today && lesson.isCurrentAt(currentTime),
                    plannerItems = plannerByLesson[lesson.id].orEmpty(), onClick = { onOpenLesson(lesson) })
            }
        }
    }
}

@Composable
private fun DaySummaryCard(day: ScheduledDay, isToday: Boolean, now: LocalTime, modifier: Modifier = Modifier, onPlanDay: (() -> Unit)? = null) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val activeLessons = day.lessons.filter { it.changeKind != LessonChangeKind.CANCELED }
        .sortedBy { it.hour.beginTime.asSchoolTime() ?: LocalTime.MAX }
    val current = if (isToday) activeLessons.firstOrNull { it.isCurrentAt(now) } else null
    val next = if (isToday && current == null) activeLessons.firstOrNull { it.hour.beginTime.asSchoolTime()?.isAfter(now) == true } else null
    val changed = day.lessons.count { it.changeKind != LessonChangeKind.NONE }
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.primary.copy(alpha = 0.10f), contentColor = MaterialTheme.colorScheme.onSurface) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(when {
                current != null -> strings.getString(R.string.timetable_now_1_s, current.subjectDisplayName(strings))
                next != null -> strings.getString(R.string.timetable_next_1_s, next.subjectDisplayName(strings))
                activeLessons.isEmpty() -> strings.getString(R.string.timetable_a_day_to_recharge)
                isToday && activeLessons.any { it.hour.beginTime.asSchoolTime() == null || it.hour.endTime.asSchoolTime() == null } -> strings.getString(R.string.timetable_lesson_times_unavailable)
                isToday -> strings.getString(R.string.timetable_you_re_done_for_today)
                else -> strings.getQuantityString(R.plurals.timetable_lessons_scheduled, activeLessons.size, activeLessons.size)
            }, color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            val featured = current ?: next
            val description = if (featured != null) {
                val end = (if (current != null) featured.hour.endTime else featured.hour.beginTime).asSchoolTime()
                val minutes = end?.let { kotlin.math.ceil(Duration.between(now, it).seconds / 60.0).toInt().coerceAtLeast(1) }
                listOfNotNull(
                    minutes?.let { if (current != null) strings.getString(R.string.timetable_1_d_min_remaining, it) else strings.getString(R.string.timetable_starts_in_1_d_min, it) },
                    featured.roomTitle?.takeIf { it.isNotBlank() }?.let { strings.getString(R.string.timetable_room_1_s, it) },
                    featured.teacherTitle?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
            } else if (activeLessons.isEmpty()) day.dayDescription.ifBlank { strings.getString(R.string.timetable_no_lessons_scheduled) }
            else listOfNotNull(activeLessons.firstOrNull()?.hour?.beginTime, activeLessons.lastOrNull()?.hour?.endTime).joinToString(" – ")
            Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
            current?.let { lesson ->
                val start = lesson.hour.beginTime.asSchoolTime()
                val end = lesson.hour.endTime.asSchoolTime()
                if (start != null && end != null && end.isAfter(start)) {
                    val progress = Duration.between(start, now).seconds.toFloat() / Duration.between(start, end).seconds
                    LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = AccentTeal, trackColor = MaterialTheme.colorScheme.surface)
                }
            }
            if (changed > 0) Text(strings.getQuantityString(R.plurals.timetable_timetable_changes, changed, changed), color = GradeyColors.Average, fontSize = 12.sp)
            onPlanDay?.let { action -> TextButton(onClick = action) { Text(strings.getString(R.string.timetable_plan_this_day), color = AccentTeal) } }
        }
    }
}

private fun String.asSchoolTime(): LocalTime? {
    val parts = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$").matchEntire(trim()) ?: return null
    return LocalTime.of(parts.groupValues[1].toInt(), parts.groupValues[2].toInt())
}

private fun ScheduledLesson.isCurrentAt(time: LocalTime): Boolean {
    if (changeKind == LessonChangeKind.CANCELED) return false
    val start = hour.beginTime.asSchoolTime() ?: return false
    val end = hour.endTime.asSchoolTime() ?: return false
    return !time.isBefore(start) && time.isBefore(end)
}

@Composable
private fun LessonRow(
    lesson: ScheduledLesson,
    isCurrent: Boolean,
    plannerItems: List<PersonalPlannerItem>,
    onClick: () -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val hasTopic = !lesson.theme.isNullOrBlank()
    val hasChange = lesson.changeKind != LessonChangeKind.NONE || !lesson.changeDescription.isNullOrBlank()
    val rowHeight = if (hasTopic || hasChange) 83.dp else 68.dp
    val isCanceled = lesson.changeKind == LessonChangeKind.CANCELED
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .heightIn(min = rowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LessonTimeRail(lesson = lesson, modifier = Modifier.width(72.dp).fillMaxHeight())
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .semantics {
                    contentDescription = buildString {
                        append(strings.getString(R.string.timetable_open_1_s_lesson_details_2_s, lesson.subjectDisplayName(strings), lesson.formattedTimeRange()))
                        lesson.changeDescription?.takeIf { it.isNotBlank() }?.let { append(", $it") }
                        if (plannerItems.isNotEmpty()) append(", " + strings.getQuantityString(
                            R.plurals.timetable_planner_incomplete_count, plannerItems.size, plannerItems.size))
                    }
                },
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = if (isCanceled) GradeyColors.Poor.copy(alpha = 0.12f) else if (isCurrent) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface,
            border = if (isCurrent) BorderStroke(1.5.dp, AccentTeal) else null,
            shadowElevation = 1.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(SubjectTile, RoundedCornerShape(12.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = lesson.subjectAbbrev ?: lesson.subjectDisplayName(strings).take(2),
                        color = AccentTeal,
                        fontSize = 17.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = lesson.subjectDisplayName(strings),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 18.sp,
                        lineHeight = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        HugeIcon(
                            name = HugeIcons.DoorOpen,
                            contentDescription = null,
                            tint = MutedText,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(
                            text = lesson.roomAbbrev ?: lesson.roomName.orEmpty(),
                            color = MutedText,
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            maxLines = 1,
                        )
                        HugeIcon(
                            name = HugeIcons.User,
                            contentDescription = null,
                            tint = MutedText,
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = lesson.teacherName ?: lesson.teacherAbbrev.orEmpty(),
                            color = MutedText,
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (lesson.cycles.isNotEmpty()) Text(lesson.cycles.joinToString(" · "), color = AccentTeal, fontSize = 12.sp)
                    if (plannerItems.isNotEmpty()) TimetablePlannerBadge(plannerItems)
                    (lesson.changeDescription?.takeIf { it.isNotBlank() }
                        ?: lesson.theme?.takeIf { it.isNotBlank() })?.let { secondaryText ->
                        Text(
                            text = secondaryText,
                            color = when (lesson.changeKind) {
                                LessonChangeKind.CANCELED -> Color(0xFFD95461)
                                LessonChangeKind.NONE -> MutedText
                                else -> Color(0xFFD98F10)
                            },
                            fontSize = 13.sp,
                            lineHeight = 17.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LessonTimeRail(
    lesson: ScheduledLesson,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(top = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = lesson.hour.caption,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = lesson.hour.beginTime.clockDisplay(),
            color = MutedText,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
        Text(
            text = lesson.hour.endTime.clockDisplay(),
            color = MutedLight,
            fontSize = 13.sp,
            lineHeight = 16.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun LessonDetailSheet(
    lesson: ScheduledLesson,
    onPlanLesson: (() -> Unit)? = null,
    plannerItems: List<PersonalPlannerItem> = emptyList(),
    onOpenPlannerItem: ((String) -> Unit)? = null,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val hasTopic = !lesson.theme.isNullOrBlank()
    val hasChange = !lesson.changeDescription.isNullOrBlank()
    val detailRowCount = 2 + (if (hasTopic) 1 else 0) + (if (hasChange) 1 else 0)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 360.dp, max = 680.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(glow, Color.Transparent),
                    center = Offset(size.width * 0.76f, size.height * 0.05f),
                    radius = size.width * 0.65f,
                ),
                radius = size.width * 0.65f,
                center = Offset(size.width * 0.76f, size.height * 0.05f),
            )
        }
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 5.dp)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(3.dp)),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = lesson.subjectDisplayName(strings),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 20.sp,
                lineHeight = 26.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(31.dp))
            Box(
                modifier = Modifier
                    .padding(horizontal = 15.dp)
                    .fillMaxWidth()
                    .heightIn(min = 96.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.brandGradient)
                    .semantics {
                        contentDescription = "${lesson.subjectDisplayName(strings)}, ${lesson.formattedTimeRange()}"
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)) {
                    Text(
                        text = lesson.formattedTimeRange(),
                        color = GradeyColors.OnAccent.copy(alpha = 0.85f),
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = lesson.subjectDisplayName(strings),
                        color = GradeyColors.OnAccent,
                        fontSize = 22.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            if (plannerItems.isNotEmpty()) LessonPlannerItems(plannerItems, onOpenPlannerItem,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp))
            onPlanLesson?.let { action ->
                TextButton(onClick = action, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) { Text(strings.getString(R.string.timetable_add_to_planner), color = AccentTeal) }
            }
            Spacer(Modifier.height(15.dp))
            Surface(
                modifier = Modifier
                    .padding(horizontal = 15.dp)
                    .fillMaxWidth()
                    .heightIn(min = 108.dp),
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(start = 20.dp, top = 13.dp, end = 20.dp, bottom = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DetailRow(
                        icon = { HugeIcon(HugeIcons.User, contentDescription = null) },
                        label = strings.getString(R.string.timetable_teacher),
                        value = lesson.teacherName ?: lesson.teacherAbbrev ?: "—",
                    )
                    DetailRow(
                        icon = { HugeIcon(HugeIcons.DoorOpen, contentDescription = null) },
                        label = strings.getString(R.string.timetable_room),
                        value = lesson.roomName ?: lesson.roomAbbrev ?: "—",
                    )
                    if (lesson.groups.isNotEmpty()) DetailRow(
                        icon = { HugeIcon(HugeIcons.User, contentDescription = null) },
                        label = strings.getString(R.string.timetable_groups),
                        value = lesson.groups.joinToString(", "),
                    )
                    if (lesson.cycles.isNotEmpty()) DetailRow(
                        icon = { HugeIcon(HugeIcons.InformationCircle, contentDescription = null) },
                        label = strings.getString(R.string.timetable_cycles),
                        value = lesson.cycles.joinToString(", "),
                    )
                    if (lesson.hasHomework) DetailRow(
                        icon = { HugeIcon(HugeIcons.Book02, contentDescription = null) },
                        label = strings.getString(R.string.timetable_homework),
                        value = strings.getString(R.string.timetable_homework_assigned_for_this_lesson),
                    )
                    lesson.theme?.takeIf { it.isNotBlank() }?.let { topic ->
                        DetailRow(
                            icon = { HugeIcon(HugeIcons.Book02, contentDescription = null) },
                            label = strings.getString(R.string.timetable_topic),
                            value = topic,
                        )
                    }
                    lesson.changeDescription?.takeIf { it.isNotBlank() }?.let { change ->
                        DetailRow(
                            icon = { HugeIcon(HugeIcons.InformationCircle, contentDescription = null) },
                            label = strings.getString(R.string.timetable_change),
                            value = change,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailRow(
    icon: @Composable () -> Unit,
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 38.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .padding(top = 2.dp),
            contentAlignment = Alignment.TopStart,
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .semantics { contentDescription = label },
            ) {
                CompositionLocalProvider(LocalContentColor provides AccentTeal) { icon() }
            }
        }
        Column {
            Text(
                text = label,
                color = MutedText,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = value,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                lineHeight = 20.sp,
                fontWeight = FontWeight.Normal,
            )
        }
    }
}

private fun timetableDaySlots(week: TimetableWeek?, monday: LocalDate): List<TimetableDaySlot> {
    val byDate = week?.days.orEmpty().mapNotNull { day ->
        day.date?.let { date -> runCatching { LocalDate.parse(date.take(10)) }.getOrNull()?.let { it to day } }
    }.toMap()
    val byWeekday = week?.days.orEmpty().filter { it.date.isNullOrBlank() }.associateBy { it.dayOfWeek }
    val lastDay = if (week?.days.orEmpty().any { it.dayOfWeek in 6..7 }) 6L else 4L
    return (0L..lastDay).map { offset ->
        val date = monday.plusDays(offset)
        TimetableDaySlot(
            date = date,
            day = byDate[date] ?: byWeekday[date.dayOfWeek.value],
        )
    }
}

private fun weekMonday(week: TimetableWeek?): LocalDate {
    val parsed = week?.weekStart?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    return TimetableDates.monday(parsed ?: LocalDate.now(SchoolTimeZone))
}

private fun formatWeekRange(monday: LocalDate, locale: Locale): String {
    val WeekMonthFormatter = DateTimeFormatter.ofPattern("MMM", locale)
    val friday = monday.plusDays(4)
    if (locale.language == "cs") {
        val dayMonth = DateTimeFormatter.ofPattern("d. MMM", locale)
        return if (monday.month == friday.month) "${monday.dayOfMonth}.–${friday.format(dayMonth)}"
        else "${monday.format(dayMonth)}–${friday.format(dayMonth)}"
    }
    return if (monday.month == friday.month) {
        "${monday.format(WeekMonthFormatter)} ${monday.dayOfMonth}–${friday.dayOfMonth}"
    } else {
        "${monday.format(WeekMonthFormatter)} ${monday.dayOfMonth}–${friday.format(WeekMonthFormatter)} ${friday.dayOfMonth}"
    }
}

private fun ScheduledLesson.subjectDisplayName(strings: android.content.res.Resources): String = subjectName ?: subjectAbbrev ?: strings.getString(R.string.timetable_lesson)

private fun ScheduledLesson.formattedTimeRange(): String =
    listOf(hour.beginTime.clockDisplay(), hour.endTime.clockDisplay())
        .filter { it.isNotBlank() }
        .joinToString(" – ")

private fun String.clockDisplay(): String = trim().removePrefix("0")
