package com.bukovinafilip.gradey.feature.planner

import android.content.Intent
import android.provider.CalendarContract
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.domain.PersonalPlannerRepository
import com.bukovinafilip.gradey.domain.PlannerCalendarExport
import com.bukovinafilip.gradey.domain.PlannerCalendarMirror
import com.bukovinafilip.gradey.domain.PlannerCalendarReport
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.PlannerSubjectReference
import com.bukovinafilip.gradey.ui.EmptyState
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyDialog
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.SegmentedControl
import com.bukovinafilip.gradey.ui.ToolbarIconButton
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.struck
import com.bukovinafilip.gradey.ui.tabular
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal enum class PlannerExport { NONE, CALENDAR, ICS }

private val PlannerJson = Json { ignoreUnknownKeys = true }

/**
 * Planner, pushed from Today or Timetable (iOS `PlannerView`): Upcoming | Completed, one plain
 * section per day, and the item editor as a sheet. The app shell handles leaving the screen.
 */
@Composable
fun PlannerRoute(
    repository: PersonalPlannerRepository,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subjects: List<PlannerSubjectReference> = emptyList(),
    initialItem: PersonalPlannerItem? = null,
    initialItemID: String? = null,
    focusedDay: String? = null,
    onFindNextLesson: (suspend (PersonalPlannerItem) -> Instant?)? = null,
    onItemsChanged: (List<PersonalPlannerItem>) -> Unit = {},
    onAskTestAI: ((String) -> Unit)? = null,
    calendarMirror: PlannerCalendarMirror? = null,
    onInitialItemHandled: () -> Unit = {},
) {
    val resources = LocalResources.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var items by remember(repository) { mutableStateOf(emptyList<PersonalPlannerItem>()) }
    var loaded by remember(repository) { mutableStateOf(false) }
    var busy by remember(repository) { mutableStateOf(false) }
    var error by remember(repository) { mutableStateOf<String?>(null) }
    var complete by rememberSaveable { mutableStateOf(false) }
    var filterDay by rememberSaveable(focusedDay) { mutableStateOf(focusedDay) }
    var draftData by rememberSaveable { mutableStateOf<String?>(null) }
    var handledEntryID by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingEntryData by rememberSaveable { mutableStateOf<String?>(null) }
    var findingItemID by remember { mutableStateOf<String?>(null) }
    val finding = findingItemID != null
    var pendingICS by rememberSaveable { mutableStateOf<String?>(null) }
    var exportMessage by rememberSaveable { mutableStateOf<String?>(null) }
    var deletionID by remember { mutableStateOf<String?>(null) }
    var calendarReport by remember(calendarMirror) { mutableStateOf<PlannerCalendarReport?>(null) }
    var calendarError by remember(calendarMirror) { mutableStateOf(false) }
    suspend fun synchronizeCalendar() {
        calendarMirror ?: return
        try { calendarReport = calendarMirror.synchronize(); calendarError = false }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { calendarError = true }
    }
    val draft = remember(draftData) { draftData?.let { PlannerJson.decodeFromString<PersonalPlannerItem>(it) } }
    val pendingEntry = remember(pendingEntryData) { pendingEntryData?.let { PlannerJson.decodeFromString<PersonalPlannerItem>(it) } }
    fun edit(item: PersonalPlannerItem) { draftData = PlannerJson.encodeToString(item) }
    val exportFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        val encoded = pendingICS
        if (uri != null && encoded != null) scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(encoded.toByteArray(Charsets.UTF_8)) }
                        ?: throw IllegalStateException(resources.getString(R.string.planner_the_selected_file_could_not_be_opened))
                }
                exportMessage = resources.getString(R.string.planner_calendar_file_saved_it_is_a_copy)
            } catch (_: Exception) { error = resources.getString(R.string.planner_the_calendar_file_could_not_be_saved) }
            finally { pendingICS = null }
        } else pendingICS = null
    }
    fun export(item: PersonalPlannerItem, action: PlannerExport) {
        if (action == PlannerExport.NONE) return
        val event = PlannerCalendarExport.event(item)
        if (event == null) { error = resources.getString(R.string.planner_add_a_day_or_due_date_before); return }
        if (action == PlannerExport.ICS) {
            pendingICS = PlannerCalendarExport.ics(item)
            exportFile.launch("gradey-${item.id.take(8)}.ics")
        } else {
            try {
                val start = if (event.allDay) event.date!!.atStartOfDay(ZoneOffset.UTC).toInstant() else event.start
                val end = if (event.allDay) event.date!!.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant() else event.end
                context.startActivity(Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
                    .putExtra(CalendarContract.Events.TITLE, event.title)
                    .putExtra(CalendarContract.Events.DESCRIPTION, event.description)
                    .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.toEpochMilli())
                    .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.toEpochMilli())
                    .putExtra(CalendarContract.Events.ALL_DAY, event.allDay)
                    .putExtra(CalendarContract.Events.EVENT_TIMEZONE, event.timeZoneID))
            } catch (_: Exception) { error = resources.getString(R.string.planner_no_calendar_app_is_available_you_can) }
        }
    }
    suspend fun load() {
        busy = true
        error = null
        try {
            items = repository.load()
            loaded = true
            onItemsChanged(items)
            synchronizeCalendar()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = resources.getString(R.string.planner_your_planner_could_not_be_opened_saved) }
        finally { busy = false }
    }
    fun mutate(action: suspend () -> List<PersonalPlannerItem>, after: () -> Unit = {}) {
        if (!loaded || busy) return
        busy = true
        error = null
        scope.launch {
            try {
                items = action()
                onItemsChanged(items)
                after()
                synchronizeCalendar()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message?.takeIf { it.length <= 250 } ?: resources.getString(R.string.planner_the_planner_could_not_be_saved_your) }
            finally { busy = false }
        }
    }
    LaunchedEffect(repository) { load() }
    LaunchedEffect(loaded, initialItem?.id, initialItemID, focusedDay) {
        if (loaded) {
            val entryID = initialItem?.id ?: initialItemID
            if (entryID == null) {
                handledEntryID = null
                // The date filter is already in saveable state; day-only links need no editor.
                if (focusedDay != null) onInitialItemHandled()
            } else if (handledEntryID != entryID) {
                val entry = initialItem ?: items.firstOrNull { it.id == initialItemID }
                if (entry == null) {
                    pendingEntryData = null
                    error = resources.getString(R.string.planner_entry_unavailable)
                }
                // Reopening the same item must preserve the editor's unsaved version, including
                // after saved-state restoration. A different item waits for a deliberate choice.
                else pendingEntryData = if (entry.id == draft?.id) null else PlannerJson.encodeToString(entry)
                handledEntryID = entryID
                onInitialItemHandled()
            }
        }
    }
    LaunchedEffect(busy, finding, draftData == null, pendingEntryData) {
        if (!busy && !finding && draftData == null && pendingEntry != null) {
            edit(pendingEntry)
            pendingEntryData = null
        }
    }

    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(context)
    val labels = remember(resources, locale, is24Hour) { PlannerLabels.from(resources, locale, is24Hour) }
    val sections = remember(items, complete, filterDay, labels) { plannerSections(items, complete, filterDay, labels) }
    val filterDayTitle = remember(filterDay, labels) {
        filterDay?.let { day -> PersonalPlannerDates.parse(day)?.let { dayTitle(it, LocalDate.now(), labels) } ?: day }
    }
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }

    Column(modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.planner_planner),
            scrolled = scrolled,
            onBack = onBack,
            backDescription = stringResource(R.string.planner_back),
            actions = {
                ToolbarIconButton(
                    HugeIcons.Add01, stringResource(R.string.planner_add_planner_item),
                    onClick = { edit(PersonalPlannerItem(linkedDate = filterDay)) },
                    modifier = Modifier.testTag("plannerAddButton"), enabled = loaded && !busy,
                )
            },
        )
        GradeyLazyColumn(
            Modifier.weight(1f).testTag("plannerList"), state = listState,
            maxWidth = GradeyMetrics.narrowContentMaxWidth, itemSpacing = 0.dp,
        ) {
            item(key = "filter", contentType = "filter") {
                SegmentedControl(
                    options = listOf(stringResource(R.string.planner_upcoming), stringResource(R.string.planner_completed)),
                    selectedIndex = if (complete) 1 else 0,
                    onSelect = { complete = it == 1 },
                    modifier = Modifier.fillMaxWidth().testTag("plannerFilter"),
                )
            }
            filterDayTitle?.let { title ->
                item(key = "dayFilter", contentType = "link") {
                    PlainLink(stringResource(R.string.planner_1_s_clear_day_filter, title), { filterDay = null },
                        Modifier.padding(top = GradeySpacing.sm), showsArrow = false)
                }
            }
            error?.let { message ->
                item(key = "error", contentType = "notice") {
                    InlineNotice(
                        message, Modifier.padding(top = GradeySpacing.lg),
                        action = if (loaded) null else ({
                            PlainLink(stringResource(R.string.planner_try_again), { scope.launch { load() } }, enabled = !busy, showsArrow = false)
                        }),
                    )
                }
            }
            exportMessage?.let { message ->
                item(key = "exportMessage", contentType = "notice") {
                    InlineNotice(message, Modifier.padding(top = GradeySpacing.lg), color = MaterialTheme.colorScheme.primary, icon = HugeIcons.CheckmarkCircle02)
                }
            }
            if (calendarMirror != null && (calendarError || (calendarReport?.pending ?: 0) > 0)) {
                item(key = "calendarStatus", contentType = "notice") {
                    PlannerCalendarStatus(calendarReport, calendarError, calendarMirror, busy, Modifier.padding(top = GradeySpacing.lg)) {
                        busy = true
                        scope.launch { try { synchronizeCalendar() } finally { busy = false } }
                    }
                }
            }
            if (!loaded && error == null) {
                item(key = "loading", contentType = "state") { LoadingState(Modifier.padding(top = GradeySpacing.lg)) }
            } else if (loaded && sections.isEmpty()) {
                item(key = "empty", contentType = "state") {
                    EmptyState(
                        title = stringResource(if (complete) R.string.planner_empty_completed_title else R.string.planner_empty_title),
                        message = stringResource(if (complete) R.string.planner_empty_completed_message else R.string.planner_empty_message),
                        icon = HugeIcons.CheckList,
                        modifier = Modifier.padding(top = GradeySpacing.lg).testTag("plannerEmptyState"),
                    )
                }
            }
            sections.forEach { section ->
                item(key = "section-${section.key}", contentType = "sectionTitle") {
                    PlainSectionTitle(section.title, Modifier.padding(top = GradeySpacing.lg, bottom = GradeySpacing.sm))
                }
                items(section.rows, key = { it.item.id }, contentType = { "plannerRow" }) { row ->
                    PlannerRow(
                        row, enabled = !busy,
                        onToggle = { mutate({ repository.setCompleted(it.id, !it.isCompleted) }) },
                        onEdit = ::edit,
                        onDelete = { deletionID = it.id },
                    )
                }
            }
        }
    }

    draft?.let { current ->
        key(current.id) {
            PlannerItemEditor(
                item = current, subjects = subjects, existing = items.any { it.id == current.id },
                busy = busy, finding = finding, error = error,
                onFindingChanged = { active ->
                    if (active) findingItemID = current.id
                    else if (findingItemID == current.id) findingItemID = null
                },
                onChange = ::edit,
                onDismiss = { draftData = null; error = null },
                onSave = { item, exportAction -> mutate({ repository.save(item) }) { draftData = null; export(item, exportAction) } },
                onDelete = { mutate({ repository.delete(current.id) }) { draftData = null } },
                onFindNextLesson = onFindNextLesson, calendarMirror = calendarMirror, onAskTestAI = onAskTestAI,
            )
        }
    }
    if (pendingEntry != null && draft != null && !busy && !finding) {
        GradeyDialog(
            title = stringResource(R.string.planner_entry_replace_title),
            text = stringResource(R.string.planner_entry_replace_body, pendingEntry.title.ifBlank { stringResource(R.string.planner_new_item) }),
            onDismiss = { pendingEntryData = null },
            confirmLabel = stringResource(R.string.planner_entry_discard_open),
            onConfirm = { edit(pendingEntry); pendingEntryData = null; error = null },
            dismissLabel = stringResource(R.string.planner_entry_keep_editing),
        )
    }
    val deletionTarget = deletionID?.let { id -> items.firstOrNull { it.id == id } }
    deletionTarget?.let { target ->
        GradeyDialog(
            title = stringResource(R.string.planner_delete_this_planner_item),
            text = stringResource(R.string.planner_1_s_will_be_removed_from_your, target.title),
            onDismiss = { deletionID = null },
            confirmLabel = stringResource(R.string.planner_delete),
            onConfirm = { deletionID = null; mutate({ repository.delete(target.id) }) },
            dismissLabel = stringResource(R.string.planner_cancel),
            destructive = true,
            confirmEnabled = !busy,
        )
    }
}

// MARK: - Rows

/** Completion circle in a 40×44 target, then the summary and a disclosure chevron (iOS planner row). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlannerRow(
    row: PlannerRowUi,
    enabled: Boolean,
    onToggle: (PersonalPlannerItem) -> Unit,
    onEdit: (PersonalPlannerItem) -> Unit,
    onDelete: (PersonalPlannerItem) -> Unit,
) {
    val item = row.item
    val tokens = MaterialTheme.gradeyColors
    var menu by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs)) {
        Hairline()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Box(
                Modifier.clickable(enabled = enabled, role = Role.Button, onClick = { onToggle(item) })
                    .padding(vertical = GradeySpacing.md)
                    .size(width = 40.dp, height = GradeyMetrics.minTouch)
                    .testTag("plannerComplete-${item.id}"),
                contentAlignment = Alignment.CenterStart,
            ) {
                GradeyIcon(
                    if (item.isCompleted) HugeIcons.CheckmarkCircle02 else HugeIcons.Circle,
                    size = 22.dp, tint = MaterialTheme.colorScheme.primary,
                    contentDescription = stringResource(if (item.isCompleted) R.string.planner_mark_1_s_incomplete else R.string.planner_mark_1_s_complete, item.title),
                )
            }
            Box(Modifier.weight(1f)) {
                Row(
                    Modifier.fillMaxWidth()
                        .combinedClickable(enabled = enabled, role = Role.Button, onClick = { onEdit(item) }, onLongClick = { menu = true })
                        .padding(vertical = GradeySpacing.md)
                        .testTag("plannerItem-${item.id}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
                ) {
                    PlannerItemSummary(row, Modifier.weight(1f))
                    GradeyIcon(HugeIcons.ArrowRight01, size = 13.dp, tint = tokens.tertiaryLabel, contentDescription = stringResource(R.string.planner_edit_1_s, item.title))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = tokens.secondaryGroupedBackground) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.planner_edit_item), style = GradeyType.body) },
                        onClick = { menu = false; onEdit(item) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.planner_delete), style = GradeyType.body, color = GradeyColors.Poor) },
                        onClick = { menu = false; onDelete(item) },
                    )
                }
            }
        }
    }
}

/** Title, type/subject metadata and the schedule line (iOS `PlannerItemSummary`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlannerItemSummary(row: PlannerRowUi, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        Text(row.item.title, style = GradeyType.headline.struck(row.item.isCompleted), color = MaterialTheme.colorScheme.onSurface)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            CaptionLabel(row.typeIcon, row.typeTitle)
            row.subject?.let { Text(it, style = GradeyType.caption, color = secondary) }
            if (row.calendarSynced) GradeyIcon(HugeIcons.Calendar03, size = 12.dp, tint = secondary)
        }
        row.schedule?.let { CaptionLabel(HugeIcons.Clock01, it) }
    }
}

@Composable
private fun CaptionLabel(icon: String, text: String) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        GradeyIcon(icon, size = 12.dp, tint = secondary)
        Text(text, style = GradeyType.caption.tabular(), color = secondary)
    }
}

// MARK: - Grouping

@Immutable
internal data class PlannerRowUi(
    val item: PersonalPlannerItem,
    val typeTitle: String,
    val typeIcon: String,
    val subject: String?,
    val calendarSynced: Boolean,
    val schedule: String?,
)

@Immutable
internal data class PlannerSectionUi(val key: String, val title: String, val rows: List<PlannerRowUi>)

@Immutable
internal class PlannerLabels(
    val today: String,
    val tomorrow: String,
    val noDate: String,
    val types: Map<PlannerItemType, String>,
    val locale: Locale,
    val is24Hour: Boolean,
) {
    val weekday: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE", locale)
    val time: DateTimeFormatter = DateTimeFormatter.ofPattern(if (is24Hour) "H:mm" else "h:mm a", locale)

    companion object {
        fun from(resources: android.content.res.Resources, locale: Locale, is24Hour: Boolean) = PlannerLabels(
            today = resources.getString(R.string.planner_today),
            tomorrow = resources.getString(R.string.planner_tomorrow),
            noDate = resources.getString(R.string.planner_no_date),
            types = PlannerItemType.entries.associateWith { resources.getString(plannerTypeTitle(it)) },
            locale = locale,
            is24Hour = is24Hour,
        )
    }
}

internal object PersonalPlannerDates {
    private val ShortDayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d. M.")
    private val ShortDayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("d. M. yyyy")

    fun parse(raw: String): LocalDate? = runCatching { LocalDate.parse(raw) }.getOrNull()

    /** Day and month ("19. 10."), with the year when it is not the current one (iOS `shortDate`). */
    fun shortDate(date: LocalDate, today: LocalDate): String =
        date.format(if (date.year == today.year) ShortDayMonth else ShortDayMonthYear)
}

internal fun dayTitle(date: LocalDate?, today: LocalDate, labels: PlannerLabels): String = when (date) {
    null -> labels.noDate
    today -> labels.today
    today.plusDays(1) -> labels.tomorrow
    else -> date.format(labels.weekday).replaceFirstChar { it.titlecase(labels.locale) } + " " + PersonalPlannerDates.shortDate(date, today)
}

internal fun plannerSections(
    items: List<PersonalPlannerItem>,
    completed: Boolean,
    filterDay: String?,
    labels: PlannerLabels,
    today: LocalDate = LocalDate.now(),
): List<PlannerSectionUi> {
    val visible = items.filter { it.isCompleted == completed && (filterDay == null || PersonalPlanner.orderingDate(it)?.toString() == filterDay) }
        .sortedWith(compareBy<PersonalPlannerItem> { PersonalPlanner.orderingDate(it) ?: LocalDate.MAX }.thenBy { it.dueTime ?: "23:59" }.thenBy { it.title.lowercase() })
    return visible.groupBy { PersonalPlanner.orderingDate(it) }.map { (date, group) ->
        PlannerSectionUi(
            key = date?.toString() ?: "undated",
            title = dayTitle(date, today, labels),
            rows = group.map { item ->
                PlannerRowUi(
                    item = item,
                    typeTitle = labels.types.getValue(item.type),
                    typeIcon = plannerTypeIcon(item.type),
                    subject = item.subject?.let { subject -> subject.abbreviation?.takeIf { it.isNotBlank() } ?: subject.name },
                    calendarSynced = item.calendarID != null,
                    schedule = scheduleSummary(item, date, today, labels),
                )
            },
        )
    }
}

/** Short day ("19. 10.") plus the time when the item has one (iOS `scheduleSummary`). */
private fun scheduleSummary(item: PersonalPlannerItem, date: LocalDate?, today: LocalDate, labels: PlannerLabels): String? {
    date ?: return null
    val due = item.dueDate?.let(PersonalPlannerDates::parse)
    val time = item.dueTime?.let { raw -> runCatching { java.time.LocalTime.parse(raw) }.getOrNull() }
    if (due != null && time != null) return PersonalPlannerDates.shortDate(due, today) + " " + time.format(labels.time)
    return PersonalPlannerDates.shortDate(date, today)
}

internal fun plannerTypeTitle(type: PlannerItemType): Int = when (type) {
    PlannerItemType.HOMEWORK -> R.string.planner_homework
    PlannerItemType.TEST -> R.string.planner_test
    PlannerItemType.PRESENTATION -> R.string.planner_presentation
    PlannerItemType.NOTE -> R.string.planner_note
    PlannerItemType.TASK -> R.string.planner_task
}

/** iOS `PlannerItemType.systemImage`, through the Hugeicons catalog. */
internal fun plannerTypeIcon(type: PlannerItemType): String = when (type) {
    PlannerItemType.HOMEWORK, PlannerItemType.NOTE -> HugeIcons.Book02
    PlannerItemType.TEST -> HugeIcons.Edit02
    PlannerItemType.PRESENTATION -> HugeIcons.UserGroup
    PlannerItemType.TASK -> HugeIcons.CheckList
}
