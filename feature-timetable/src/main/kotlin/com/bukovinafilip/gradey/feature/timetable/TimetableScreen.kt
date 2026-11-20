package com.bukovinafilip.gradey.feature.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.TimetableDates
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.Friend
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.TimetableKind
import com.bukovinafilip.gradey.model.TimetableWeek
import com.bukovinafilip.gradey.ui.EmptyState
import com.bukovinafilip.gradey.ui.ErrorState
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.DetailRow
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySheet
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.IconTile
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.ModalCloseButton
import com.bukovinafilip.gradey.ui.StatusChip
import com.bukovinafilip.gradey.ui.ToolbarIconButton
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.rememberSchoolTime
import com.bukovinafilip.gradey.ui.struck
import com.bukovinafilip.gradey.ui.tabular
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Width of the weekly/permanent title menu, centred under the title. */
private val KindMenuWidth = 200.dp

/** Subject tiles shrink long text down to 60 % (iOS `minimumScaleFactor(0.6)`); bounds come from the type scale. */
private val TileAutoSize = TextAutoSize.StepBased(GradeyType.subheadlineBold.fontSize * 0.6f, GradeyType.subheadlineBold.fontSize)

/** The week title shrinks down to 70 % before it truncates (iOS `minimumScaleFactor(0.7)`). */
private val WeekTitleAutoSize = TextAutoSize.StepBased(GradeyType.headline.fontSize * 0.7f, GradeyType.headline.fontSize)

@Immutable
private data class LessonSelection(val lesson: ScheduledLesson, val day: ScheduledDay)

@Immutable
private data class DayChipModel(
    val date: LocalDate,
    val weekday: String,
    val number: String,
    val description: String,
    val hasChanges: Boolean,
)

/**
 * The Timetable tab (iOS `TimetableView`, compact layout): the top bar with the weekly/permanent
 * title menu and the Planner button, a fixed week bar and day row, then the selected day's lessons.
 * Tapping a lesson opens its detail sheet with the Planner items attached to it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimetableScreen(
    week: TimetableWeek?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onChangeWeek: (String) -> Unit,
    onOpenAI: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    requestedWeek: String? = null,
    onOpenPlanner: (() -> Unit)? = null,
    onPlanLesson: ((ScheduledLesson, ScheduledDay) -> Unit)? = null,
    kind: TimetableKind = TimetableKind.WEEKLY,
    onChangeKind: ((TimetableKind) -> Unit)? = null,
    errorMessage: String? = null,
    plannerItems: List<PersonalPlannerItem> = emptyList(),
    schoolScope: String? = null,
    onOpenPlannerItem: ((String) -> Unit)? = null,
    lessonHomework: (@Composable (ScheduledLesson, ScheduledDay) -> Unit)? = null,
    people: TimetablePeople? = null,
    /** What follows the selected day's lessons, such as a dance class that evening. Not asked for the permanent timetable, which has no dates. */
    afterSchool: (@Composable (LocalDate) -> Unit)? = null,
) {
    val isPermanent = kind == TimetableKind.PERMANENT
    val schoolTime = rememberSchoolTime()
    val today by remember { derivedStateOf { schoolTime.value.toLocalDate() } }
    val format = rememberTimetableDateFormat()

    val monday = remember(week?.weekStart, requestedWeek) {
        requestedWeek?.let { runCatching { TimetableDates.monday(LocalDate.parse(it.take(10))) }.getOrNull() } ?: weekMonday(week)
    }
    val displayedWeek = remember(week, kind, monday) {
        week?.takeIf { it.kind == kind && (isPermanent || weekMonday(it) == monday) }
    }
    val isLoaded = displayedWeek != null
    val daySlots = remember(displayedWeek, monday) { timetableDaySlots(displayedWeek, monday) }
    val chips = remember(daySlots, format, isPermanent) {
        daySlots.map { slot ->
            val weekday = format.weekday(slot.date)
            DayChipModel(
                date = slot.date,
                weekday = weekday.uppercase(format.locale),
                number = slot.date.dayOfMonth.toString(),
                description = if (isPermanent) weekday else "$weekday, ${slot.date.dayOfMonth}",
                hasChanges = slot.day?.hasChanges == true,
            )
        }
    }

    var selectedDate by rememberSaveable(monday.toString(), kind, schoolScope) {
        mutableStateOf(initialDayDate(daySlots, today, monday, isPermanent).toString())
    }
    val selectedLocalDate = remember(selectedDate) { LocalDate.parse(selectedDate) }
    val selectedDay = daySlots.firstOrNull { it.date == selectedLocalDate }?.day
    val lessons = remember(selectedDay) { selectedDay.sortedLessons() }
    val plannerByLesson = remember(plannerItems, lessons, selectedDay, schoolScope) {
        lessons.associate { lesson -> lesson.id to attachedPlannerItems(plannerItems, lesson, selectedDay, schoolScope) }
    }
    val selectedIsToday = !isPermanent && selectedLocalDate == today
    // The clock ticks every 30 s; only a change of the current lesson recomposes the list.
    val currentLessonIDs by remember(lessons, selectedIsToday) {
        derivedStateOf<Set<String>> {
            if (!selectedIsToday) emptySet() else {
                val now = schoolTime.value.toLocalTime()
                lessons.filter { it.isCurrentAt(now) }.mapTo(HashSet<String>()) { it.id }
            }
        }
    }

    var selection by remember(kind, monday, schoolScope) { mutableStateOf<LessonSelection?>(null) }
    val openLesson: (ScheduledLesson) -> Unit = remember(selectedDay) {
        { lesson -> selectedDay?.let { selection = LessonSelection(lesson, it) } }
    }
    val selectDay: (LocalDate) -> Unit = remember(monday, kind, schoolScope) { { date -> selectedDate = date.toString() } }
    val listState = rememberLazyListState()

    Column(modifier.fillMaxSize()) {
        TimetableTopBar(
            kind = kind,
            onChangeKind = onChangeKind,
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            onOpenAI = onOpenAI,
            onOpenSettings = onOpenSettings,
            onOpenPlanner = onOpenPlanner,
            people = people,
        )
        Column(Modifier.align(Alignment.CenterHorizontally).widthIn(max = GradeyMetrics.contentMaxWidth).fillMaxWidth()) {
            if (isPermanent) {
                PermanentHeader()
            } else {
                val title = remember(monday, format, today.year) { format.weekTitle(monday, today.year) }
                val thisMonday = TimetableDates.monday(today)
                WeekBar(
                    title = title,
                    isCurrentWeek = monday == thisMonday,
                    enabled = !isRefreshing,
                    onPrevious = { onChangeWeek(monday.minusWeeks(1).toString()) },
                    onNext = { onChangeWeek(monday.plusWeeks(1).toString()) },
                    onToday = { onChangeWeek(thisMonday.toString()) },
                )
            }
            DayRow(
                chips = chips,
                selectedDate = selectedLocalDate,
                today = if (isPermanent) null else today,
                showDates = !isPermanent,
                onSelect = selectDay,
            )
        }
        Hairline()
        GradeyLazyColumn(
            modifier = Modifier.weight(1f).testTag("timetableList"),
            state = listState,
            topPadding = GradeySpacing.md,
            bottomPadding = GradeySpacing.md,
            itemSpacing = 0.dp,
        ) {
            if (isRefreshing && isLoaded) {
                item(key = "refreshing", contentType = "refreshing") { RefreshingRow() }
            }
            when {
                !isLoaded && isRefreshing -> item(key = "loading", contentType = "state") {
                    LoadingState(text = stringResource(R.string.timetable_loading))
                }
                !isLoaded && errorMessage != null -> item(key = "error", contentType = "state") {
                    ErrorState(
                        message = errorMessage,
                        title = stringResource(R.string.timetable_error_title),
                        retryLabel = stringResource(R.string.timetable_retry),
                        onRetry = onRefresh,
                    )
                }
                !isLoaded -> item(key = "unavailable", contentType = "state") {
                    EmptyState(
                        title = stringResource(R.string.timetable_timetable_unavailable_tap_refresh),
                        icon = HugeIcons.CalendarRemove02,
                    )
                }
                lessons.isEmpty() -> item(key = "empty", contentType = "state") { EmptyDay(selectedDay) }
                else -> itemsIndexed(lessons, key = { _, lesson -> lesson.id }, contentType = { _, _ -> "lesson" }) { index, lesson ->
                    LessonRow(
                        lesson = lesson,
                        isCurrent = lesson.id in currentLessonIDs,
                        plannerItems = plannerByLesson[lesson.id].orEmpty(),
                        showDivider = index > 0,
                        onOpen = openLesson,
                    )
                }
            }
            if (afterSchool != null && isLoaded && !isPermanent && selectedLocalDate != null) {
                item(key = "afterSchool", contentType = "afterSchool") { afterSchool(selectedLocalDate) }
            }
        }
    }

    selection?.let { current ->
        val attached = remember(plannerItems, current, schoolScope) {
            attachedPlannerItems(plannerItems, current.lesson, current.day, schoolScope)
        }
        LessonDetailSheet(
            selection = current,
            plannerItems = attached,
            showPlanner = !schoolScope.isNullOrBlank() && (attached.isNotEmpty() || onPlanLesson != null),
            // The permanent timetable has no dates, so no homework is given or due in its lessons.
            homework = lessonHomework?.takeIf { !isPermanent },
            onDismiss = { selection = null },
            onOpenPlannerItem = onOpenPlannerItem?.let { open ->
                { itemID: String ->
                    selection = null
                    open(itemID)
                }
            },
            onAdd = onPlanLesson?.let { plan ->
                {
                    selection = null
                    plan(current.lesson, current.day)
                }
            },
        )
    }
}

// MARK: - Top bar

/**
 * Whose timetable is on screen (iOS `personMenuContent`): the user's own or a friend's, chosen from
 * the title's menu, which also adds a friend.
 */
@Immutable
class TimetablePeople(val friends: List<Friend>, val selectedID: String?, val onSelect: (String?) -> Unit, val onAdd: () -> Unit) {
    val selected: Friend? get() = friends.firstOrNull { it.id == selectedID }
}

/**
 * Inline title with its menu (iOS `toolbarTitleMenu`): whose timetable, then weekly or permanent.
 * Planner, refresh and Settings sit on the right.
 */
@Composable
private fun TimetableTopBar(
    kind: TimetableKind,
    onChangeKind: ((TimetableKind) -> Unit)?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenAI: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPlanner: (() -> Unit)?,
    people: TimetablePeople? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val plannerDescription = stringResource(R.string.timetable_planner_title)
    val hasMenu = onChangeKind != null || people != null
    // Wider with large text, so "Add friend" and a friend's name are not cut short.
    val menuWidth = KindMenuWidth * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    Box(Modifier.fillMaxWidth()) {
        GradeyTopBar(
            title = people?.selected?.name ?: stringResource(R.string.timetable_title),
            onOpenAI = onOpenAI,
            aiDescription = stringResource(R.string.timetable_gradey_ai),
            onRefresh = onRefresh,
            isRefreshing = isRefreshing,
            refreshDescription = stringResource(R.string.timetable_refresh),
            onOpenSettings = onOpenSettings,
            settingsDescription = stringResource(R.string.timetable_settings),
            onTitleClick = if (hasMenu) ({ menuOpen = true }) else null,
            actions = if (onOpenPlanner != null) {
                { ToolbarIconButton(HugeIcons.CheckList, plannerDescription, onOpenPlanner, Modifier.testTag("timetablePlannerButton")) }
            } else null,
        )
        if (hasMenu) {
            Box(Modifier.align(Alignment.BottomCenter).width(menuWidth)) {
                DropdownMenu(
                    expanded = menuOpen,
                    onDismissRequest = { menuOpen = false },
                    modifier = Modifier.width(menuWidth).testTag("timetableKindPicker"),
                    shape = RoundedCornerShape(GradeyRadius.md),
                    containerColor = MaterialTheme.gradeyColors.secondaryGroupedBackground,
                ) {
                    if (people != null) {
                        // You, then each friend, then Add friend.
                        TitleMenuItem(stringResource(R.string.timetable_friends_me), people.selectedID == null, Modifier.testTag("timetablePerson-me")) {
                            menuOpen = false
                            if (people.selectedID != null) people.onSelect(null)
                        }
                        people.friends.forEach { friend ->
                            TitleMenuItem(friend.name, friend.id == people.selectedID, Modifier.testTag("timetablePerson-${friend.id}")) {
                                menuOpen = false
                                if (friend.id != people.selectedID) people.onSelect(friend.id)
                            }
                        }
                        TitleMenuItem(stringResource(R.string.timetable_friends_add), false, Modifier.testTag("timetableAddFriendButton"), icon = HugeIcons.Add01) {
                            menuOpen = false
                            people.onAdd()
                        }
                        if (onChangeKind != null) Hairline(Modifier.padding(vertical = GradeySpacing.xs))
                    }
                    if (onChangeKind != null) TimetableKind.entries.forEach { option ->
                        TitleMenuItem(
                            stringResource(if (option == TimetableKind.PERMANENT) R.string.timetable_kind_permanent else R.string.timetable_kind_weekly),
                            option == kind,
                        ) {
                            menuOpen = false
                            if (option != kind) onChangeKind(option)
                        }
                    }
                }
            }
        }
    }
}

/** A row of the title's menu: a tick when it is the current choice, or its own icon. */
@Composable
private fun TitleMenuItem(text: String, isSelected: Boolean, modifier: Modifier = Modifier, icon: String? = null, onClick: () -> Unit) {
    DropdownMenuItem(
        text = { Text(text, style = GradeyType.body, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        onClick = onClick,
        modifier = modifier.semantics { selected = isSelected },
        leadingIcon = {
            Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                if (isSelected) GradeyIcon(HugeIcons.Tick02, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
                else if (icon != null) GradeyIcon(icon, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
            }
        },
    )
}

// MARK: - Week bar and day row

/** Previous/next arrows around the week's dates, with a "Today" link off the current week (iOS `WeekNavBar`). */
@Composable
private fun WeekBar(
    title: String,
    isCurrentWeek: Boolean,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = GradeySpacing.lg, end = GradeySpacing.lg, top = GradeySpacing.md, bottom = GradeySpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        WeekArrow(HugeIcons.ArrowLeft01, stringResource(R.string.timetable_previous_week), enabled, onPrevious, Modifier.testTag("weekPrev"))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                style = GradeyType.headline.tabular(),
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                autoSize = WeekTitleAutoSize,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            if (!isCurrentWeek) {
                Text(
                    stringResource(R.string.timetable_today),
                    modifier = Modifier.clip(RoundedCornerShape(GradeyRadius.xs))
                        .clickable(role = Role.Button, onClick = onToday)
                        .padding(horizontal = GradeySpacing.xs)
                        .testTag("weekToday"),
                    style = GradeyType.captionSemibold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        WeekArrow(HugeIcons.ArrowRight01, stringResource(R.string.timetable_next_week), enabled, onNext, Modifier.testTag("weekNext"))
    }
}

@Composable
private fun WeekArrow(icon: String, description: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(GradeyMetrics.minTouch).clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        GradeyIcon(icon, size = 14.dp, tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.45f))
    }
}

/** Replaces the week bar on the permanent timetable. */
@Composable
private fun PermanentHeader() {
    Row(
        Modifier.fillMaxWidth().padding(start = GradeySpacing.lg, end = GradeySpacing.lg, top = GradeySpacing.md, bottom = GradeySpacing.md),
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        GradeyIcon(HugeIcons.TimeSchedule, Modifier.padding(top = 2.dp), size = 15.dp, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.timetable_kind_permanent), style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.timetable_permanent_description), style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
        }
    }
}

/** One column per day: weekday, the date in a circle when selected, and a red dot for changes (iOS `DayStrip`). */
@Composable
private fun DayRow(
    chips: List<DayChipModel>,
    selectedDate: LocalDate,
    today: LocalDate?,
    showDates: Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(start = GradeySpacing.lg, end = GradeySpacing.lg, bottom = GradeySpacing.sm)) {
        chips.forEach { chip ->
            key(chip.date) {
                DayChip(
                    chip = chip,
                    isSelected = chip.date == selectedDate,
                    isToday = chip.date == today,
                    showDate = showDates,
                    onSelect = onSelect,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DayChip(
    chip: DayChipModel,
    isSelected: Boolean,
    isToday: Boolean,
    showDate: Boolean,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val numberColor = when {
        isSelected -> GradeyColors.OnAccent
        isToday -> primary
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier.heightIn(min = GradeyMetrics.minTouch)
            .clip(RoundedCornerShape(GradeyRadius.sm))
            .clickable(role = Role.Tab, onClickLabel = stringResource(R.string.timetable_show_1_s, chip.description)) { onSelect(chip.date) }
            .semantics {
                contentDescription = chip.description
                selected = isSelected
            }
            .testTag("dayChip-${chip.date.dayOfWeek.value}"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs, Alignment.CenterVertically),
    ) {
        if (showDate) {
            Text(
                chip.weekday,
                Modifier.clearAndSetSemantics { },
                style = GradeyType.caption2Semibold,
                color = if (isToday) primary else MaterialTheme.gradeyColors.secondaryLabel,
                maxLines = 1,
            )
            Box(
                Modifier.sizeIn(minWidth = 38.dp, minHeight = 38.dp)
                    .then(if (isSelected) Modifier.background(primary, CircleShape) else Modifier)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(chip.number, style = GradeyType.headline.tabular(), color = numberColor, maxLines = 1)
            }
            Box(Modifier.size(5.dp).then(if (chip.hasChanges) Modifier.background(GradeyColors.Poor, CircleShape) else Modifier))
        } else {
            Box(
                Modifier.then(if (isSelected) Modifier.background(primary, CircleShape) else Modifier)
                    .heightIn(min = 34.dp)
                    .padding(horizontal = 10.dp)
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center,
            ) {
                Text(chip.weekday, style = GradeyType.subheadlineBold, color = numberColor, maxLines = 1)
            }
        }
    }
}

// MARK: - Lesson list

/** "Refreshing…" above the lessons while new data loads. */
@Composable
private fun RefreshingRow() {
    Row(
        Modifier.fillMaxWidth().padding(bottom = GradeySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm, Alignment.CenterHorizontally),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.gradeyColors.secondaryLabel, strokeWidth = 2.dp)
        Text(stringResource(R.string.timetable_refreshing), style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
    }
}

/** No lessons on the selected day: holiday, weekend or an empty school day (iOS `EmptyDayView`). */
@Composable
private fun EmptyDay(day: ScheduledDay?) {
    val kind = day.emptyDayKind()
    val description = day?.dayDescription?.takeIf { it.isNotBlank() }
    EmptyState(
        title = stringResource(
            when (kind) {
                EmptyDayKind.NO_SCHOOL -> R.string.timetable_holiday
                EmptyDayKind.WEEKEND -> R.string.timetable_weekend
                EmptyDayKind.NO_LESSONS -> R.string.timetable_empty_title
            },
        ),
        message = description ?: if (kind == EmptyDayKind.NO_LESSONS) stringResource(R.string.timetable_empty_message) else null,
        icon = when (kind) {
            EmptyDayKind.NO_SCHOOL -> HugeIcons.Sparkles
            EmptyDayKind.WEEKEND -> HugeIcons.Sun01
            EmptyDayKind.NO_LESSONS -> HugeIcons.CalendarCheckIn02
        },
        modifier = Modifier.padding(top = GradeySpacing.sm).testTag("timetableEmptyDay"),
    )
}

/** Accent colour of a change, reusing the grade palette (iOS `LessonChangeKind.color`). */
@Composable
private fun LessonChangeKind.color(): Color = when (this) {
    LessonChangeKind.NONE -> MaterialTheme.gradeyColors.secondaryLabel
    LessonChangeKind.CANCELED -> GradeyColors.Poor
    LessonChangeKind.SUBSTITUTION -> GradeyColors.Average
    LessonChangeKind.ROOM_CHANGED -> MaterialTheme.gradeyColors.good
    LessonChangeKind.ADDED -> MaterialTheme.gradeyColors.purple
}

@Composable
private fun LessonChangeKind.label(): String? = when (this) {
    LessonChangeKind.NONE -> null
    LessonChangeKind.CANCELED -> stringResource(R.string.timetable_change_canceled)
    LessonChangeKind.SUBSTITUTION -> stringResource(R.string.timetable_change_substitution)
    LessonChangeKind.ROOM_CHANGED -> stringResource(R.string.timetable_change_room)
    LessonChangeKind.ADDED -> stringResource(R.string.timetable_change_added)
}

/** Hour rail, subject tile and the lesson's details; a hairline separates it from the row above. */
@Composable
private fun LessonRow(
    lesson: ScheduledLesson,
    isCurrent: Boolean,
    plannerItems: List<PersonalPlannerItem>,
    showDivider: Boolean,
    onOpen: (ScheduledLesson) -> Unit,
) {
    val resources = LocalResources.current
    val unknown = stringResource(R.string.timetable_lesson_unknown)
    val changeLabel = lesson.changeKind.label()
    val subject = remember(lesson, unknown) { lesson.subjectTitle(unknown) }
    val note = remember(lesson, changeLabel) { lesson.distinctChangeNote(changeLabel) }
    val cycles = remember(lesson.cycles) { lesson.cycles.joinToString(", ") }
    val description = remember(lesson, subject, plannerItems.size, resources) {
        buildString {
            append(resources.getString(R.string.timetable_open_1_s_lesson_details_2_s, subject, lesson.timeText))
            lesson.changeNote()?.let { append(", ").append(it) }
            if (plannerItems.isNotEmpty()) {
                append(", ").append(resources.getQuantityString(R.plurals.timetable_planner_incomplete_count, plannerItems.size, plannerItems.size))
            }
        }
    }
    val canceled = lesson.isCanceled
    val changeColor = lesson.changeKind.color()
    val accent = if (lesson.changeKind == LessonChangeKind.NONE) MaterialTheme.colorScheme.primary else changeColor
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Column(Modifier.fillMaxWidth()) {
        if (showDivider) Hairline()
        Row(
            Modifier.fillMaxWidth()
                .testTag("lessonRow-${lesson.id}")
                .clickable(role = Role.Button) { onOpen(lesson) }
                .semantics { contentDescription = description }
                .padding(vertical = GradeySpacing.md),
            horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
        ) {
            LessonTimeRail(lesson, isCurrent, canceled)
            LessonTile(lesson.tileText, canceled, accent)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                // Subject and change chip share the first baseline (iOS `.firstTextBaseline`).
                Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    Text(
                        subject,
                        Modifier.weight(1f).alignByBaseline(),
                        style = GradeyType.headline.struck(canceled),
                        color = if (canceled) secondary else MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (changeLabel != null) StatusChip(changeLabel, Modifier.alignByBaseline(), color = changeColor)
                }
                // A cancelled hour has no room or teacher worth reading; the school's note matters more.
                val room = lesson.roomDisplay
                val teacher = lesson.teacherDisplay
                if (!canceled && (room != null || teacher != null)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                        if (room != null) InlineLabel(room, HugeIcons.DoorOpen)
                        if (teacher != null) InlineLabel(teacher, HugeIcons.User, Modifier.weight(1f, fill = false))
                    }
                }
                if (note != null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        GradeyIcon(HugeIcons.InformationCircle, Modifier.padding(top = 1.dp), size = 12.dp, tint = changeColor)
                        Text(note, style = GradeyType.caption, color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (!canceled && cycles.isNotEmpty()) {
                    Text(cycles, style = GradeyType.captionSemibold, color = secondary)
                }
                if (!canceled) {
                    lesson.theme?.takeIf { it.isNotBlank() }?.let { theme ->
                        Text(theme, style = GradeyType.caption, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                if (plannerItems.isNotEmpty()) TimetablePlannerBadge(plannerItems)
            }
        }
    }
}

/** Lesson number, start and end; green while the lesson is on (iOS `hourRail`). */
@Composable
private fun LessonTimeRail(lesson: ScheduledLesson, isCurrent: Boolean, canceled: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val tokens = MaterialTheme.gradeyColors
    // At least 44 dp, wider when large text needs it: the times are never cut off.
    Column(Modifier.widthIn(min = GradeyMetrics.minTouch), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            lesson.hour.caption,
            style = GradeyType.headline.tabular(),
            color = when {
                isCurrent -> primary
                canceled -> tokens.secondaryLabel
                else -> MaterialTheme.colorScheme.onSurface
            },
            maxLines = 1,
        )
        if (lesson.hour.beginTime.isNotBlank()) {
            Text(
                lesson.hour.beginTime.trim(),
                style = GradeyType.caption2.tabular(),
                color = when {
                    isCurrent -> primary
                    canceled -> tokens.tertiaryLabel
                    else -> tokens.secondaryLabel
                },
                maxLines = 1,
            )
            Text(lesson.hour.endTime.trim(), style = GradeyType.caption2.tabular(), color = tokens.tertiaryLabel, maxLines = 1)
        }
    }
}

/** 40 dp subject abbreviation tile tinted by the change; a calendar glyph when there is no subject. */
@Composable
private fun LessonTile(text: String?, canceled: Boolean, accent: Color) {
    val fontScale = LocalDensity.current.fontScale
    Box(
        Modifier.size(40.dp).clip(RoundedCornerShape(GradeyRadius.md)).background(accent.copy(alpha = if (canceled) 0.10f else 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        if (text != null) {
            Text(
                text,
                Modifier.padding(horizontal = GradeySpacing.xs),
                style = GradeyType.subheadlineBold.struck(canceled),
                color = accent,
                maxLines = 1,
                softWrap = false,
                // Only long names and enlarged text need the shrink-to-fit layout passes.
                autoSize = if (text.length > 3 || fontScale > 1.15f) TileAutoSize else null,
            )
        } else {
            GradeyIcon(if (canceled) HugeIcons.CalendarRemove02 else HugeIcons.Calendar03, size = 18.dp, tint = accent)
        }
    }
}

@Composable
private fun InlineLabel(text: String, icon: String, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        GradeyIcon(icon, size = 13.dp, tint = secondary)
        Text(text, style = GradeyType.subheadline, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

// MARK: - Lesson detail

/**
 * The lesson in a bottom sheet: the change first, then time, teacher, room, group, weeks, topic and
 * homework, then the Planner section (iOS `LessonDetailSheet`).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LessonDetailSheet(
    selection: LessonSelection,
    plannerItems: List<PersonalPlannerItem>,
    showPlanner: Boolean,
    onDismiss: () -> Unit,
    onOpenPlannerItem: ((String) -> Unit)?,
    onAdd: (() -> Unit)?,
    homework: (@Composable (ScheduledLesson, ScheduledDay) -> Unit)? = null,
) {
    val lesson = selection.lesson
    val unknown = stringResource(R.string.timetable_lesson_unknown)
    val subject = remember(lesson, unknown) { lesson.subjectTitle(unknown) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    GradeySheet(onDismiss = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                Text(
                    subject,
                    Modifier.weight(1f).semantics { heading() },
                    style = GradeyType.title2.struck(lesson.isCanceled),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ModalCloseButton(
                    onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() } },
                    contentDescription = stringResource(R.string.timetable_done),
                )
            }
            // The change is the news: show it before the regular details.
            if (lesson.changeKind != LessonChangeKind.NONE) LessonChangeBlock(lesson)
            LessonDetailRows(lesson)
            // Homework given in this lesson or due in it; the slot draws nothing when there is none.
            homework?.invoke(lesson, selection.day)
            if (showPlanner) LessonPlannerSection(plannerItems, onOpenPlannerItem, onAdd)
        }
    }
}

@Composable
private fun LessonChangeBlock(lesson: ScheduledLesson) {
    val color = lesson.changeKind.color()
    val label = lesson.changeKind.label() ?: stringResource(R.string.timetable_detail_change)
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        IconTile(
            icon = if (lesson.isCanceled) HugeIcons.CalendarRemove02 else HugeIcons.Alert02,
            tint = color,
            size = 36.dp,
            iconSize = 20.dp,
            cornerRadius = GradeyRadius.xs,
            background = color.copy(alpha = 0.14f),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(label, style = GradeyType.headline, color = color)
            lesson.changeDescription?.trim()?.takeIf { it.isNotEmpty() }?.let { description ->
                Text(description, style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
            }
        }
    }
}

@Composable
private fun LessonDetailRows(lesson: ScheduledLesson) {
    val groups = remember(lesson.groups) { lesson.groups.joinToString(", ") }
    val cycles = remember(lesson.cycles) { lesson.cycles.joinToString(", ") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        DetailRow(HugeIcons.Clock01, stringResource(R.string.timetable_detail_time), lesson.timeText)
        lesson.teacherDisplay?.let { DetailRow(HugeIcons.User, stringResource(R.string.timetable_detail_teacher), it) }
        (lesson.roomName?.takeIf { it.isNotBlank() } ?: lesson.roomAbbrev?.takeIf { it.isNotBlank() })?.let {
            DetailRow(HugeIcons.DoorOpen, stringResource(R.string.timetable_detail_room), it)
        }
        if (groups.isNotEmpty()) DetailRow(HugeIcons.Students, stringResource(R.string.timetable_detail_group), groups)
        if (cycles.isNotEmpty()) DetailRow(HugeIcons.Calendar03, stringResource(R.string.timetable_detail_cycles), cycles)
        lesson.theme?.takeIf { it.isNotBlank() }?.let { DetailRow(HugeIcons.Book02, stringResource(R.string.timetable_detail_topic), it) }
        if (lesson.hasHomework) {
            DetailRow(HugeIcons.CheckList, stringResource(R.string.timetable_detail_homework), stringResource(R.string.timetable_detail_homework_assigned))
        }
    }
}

