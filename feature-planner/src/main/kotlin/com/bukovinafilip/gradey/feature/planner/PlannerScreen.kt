package com.bukovinafilip.gradey.feature.planner

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.provider.CalendarContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.domain.PlannerCalendarMirror
import com.bukovinafilip.gradey.domain.PlannerCalendarReport
import com.bukovinafilip.gradey.domain.PersonalPlannerRepository
import com.bukovinafilip.gradey.domain.PlannerCalendarExport
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.PlannerSubjectReference
import com.bukovinafilip.gradey.ui.GradeyHero
import com.bukovinafilip.gradey.ui.GradeySectionCard
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private enum class PlannerExport { NONE, CALENDAR, ICS }
private val PlannerJson = Json { ignoreUnknownKeys = true }

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
    val strings = androidx.compose.ui.platform.LocalContext.current.resources
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
                        ?: throw IllegalStateException(strings.getString(R.string.planner_the_selected_file_could_not_be_opened))
                }
                exportMessage = strings.getString(R.string.planner_calendar_file_saved_it_is_a_copy)
            } catch (_: Exception) { error = strings.getString(R.string.planner_the_calendar_file_could_not_be_saved) }
            finally { pendingICS = null }
        } else pendingICS = null
    }
    fun export(item: PersonalPlannerItem, action: PlannerExport) {
        if (action == PlannerExport.NONE) return
        val event = PlannerCalendarExport.event(item)
        if (event == null) { error = strings.getString(R.string.planner_add_a_day_or_due_date_before); return }
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
            } catch (_: Exception) { error = strings.getString(R.string.planner_no_calendar_app_is_available_you_can) }
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
        catch (_: Exception) { error = strings.getString(R.string.planner_your_planner_could_not_be_opened_saved) }
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
            catch (failure: Exception) { error = failure.message?.takeIf { it.length <= 250 } ?: strings.getString(R.string.planner_the_planner_could_not_be_saved_your) }
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
                    error = strings.getString(R.string.planner_entry_unavailable)
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
    BackHandler(enabled = draft == null, onBack = onBack)

    val visible = remember(items, complete, filterDay) {
        items.filter { it.isCompleted == complete && (filterDay == null || PersonalPlanner.orderingDate(it)?.toString() == filterDay) }
            .sortedWith(compareBy<PersonalPlannerItem> { PersonalPlanner.orderingDate(it) ?: LocalDate.MAX }.thenBy { it.dueTime ?: "23:59" }.thenBy { it.title.lowercase() })
    }
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(Modifier.testTag("plannerList"), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, strings.getString(R.string.planner_back)) }
                    Text(strings.getString(R.string.planner_planner), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    IconButton(onClick = { edit(PersonalPlannerItem(linkedDate = filterDay)) }, enabled = loaded && !busy) { Icon(Icons.Default.Add, strings.getString(R.string.planner_add_planner_item)) }
                }
            }
            item { GradeyHero(strings.getString(R.string.planner_a_little_more_prepared), strings.getString(R.string.planner_keep_homework_tests_and_everything_in_between)) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(!complete, { complete = false }, label = { Text(strings.getString(R.string.planner_upcoming)) }) }
                    item { FilterChip(complete, { complete = true }, label = { Text(strings.getString(R.string.planner_completed)) }) }
                    filterDay?.let { day -> item { FilterChip(true, { filterDay = null }, label = { Text(strings.getString(R.string.planner_1_s_clear_day_filter, day)) }) } }
                }
            }
            if (busy) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item {
                GradeySectionCard {
                    Text(message, color = MaterialTheme.colorScheme.error)
                    if (!loaded) TextButton(onClick = { scope.launch { load() } }, enabled = !busy) { Text(strings.getString(R.string.planner_retry_opening_planner)) }
                }
            } }
            exportMessage?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.primary) } }
            if (calendarMirror != null && (calendarError || (calendarReport?.pending ?: 0) > 0)) item {
                PlannerCalendarStatus(calendarReport, calendarError, calendarMirror, busy) {
                    busy = true
                    scope.launch { try { synchronizeCalendar() } finally { busy = false } }
                }
            }
            if (loaded && visible.isEmpty()) item {
                GradeySectionCard {
                    Icon(Icons.Default.Checklist, null, tint = MaterialTheme.colorScheme.primary)
                    Text(if (complete) strings.getString(R.string.planner_completed_and_collected) else strings.getString(R.string.planner_make_room_for_what_s_next), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(if (complete) strings.getString(R.string.planner_items_you_finish_will_appear_here) else strings.getString(R.string.planner_add_homework_a_test_a_presentation_a), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (!complete) Button(onClick = { edit(PersonalPlannerItem(linkedDate = filterDay)) }, enabled = !busy) { Text(strings.getString(R.string.planner_add_an_item)) }
                }
            }
            val groups = visible.groupBy { PersonalPlanner.orderingDate(it) }
            groups.forEach { (date, group) ->
                item(key = "heading-$date") { Text(dayLabel(date, strings), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold) }
                items(group, key = { it.id }) { item ->
                    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { mutate({ repository.setCompleted(item.id, !item.isCompleted) }) }, enabled = !busy) {
                                Icon(if (item.isCompleted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    if (item.isCompleted) strings.getString(R.string.planner_mark_1_s_incomplete, item.title) else strings.getString(R.string.planner_mark_1_s_complete, item.title), tint = MaterialTheme.colorScheme.primary)
                            }
                            Column(Modifier.weight(1f).clickable(enabled = !busy) { edit(item) }.padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textDecoration = if (item.isCompleted) TextDecoration.LineThrough else null)
                                Text(listOfNotNull(plannerTypeTitle(item.type, strings), item.subject?.name, item.dueTime).joinToString(" · "),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (item.notes.isNotBlank()) Text(item.notes, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                if (PersonalPlanner.isOverdue(item)) Text(strings.getString(R.string.planner_overdue), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                                if (item.type == PlannerItemType.TEST && onAskTestAI != null) {
                                    TextButton(onClick = { onAskTestAI(item.id) }) { Text(strings.getString(R.string.planner_prepare_with_ai)) }
                                }
                            }
                            IconButton(onClick = { edit(item) }, enabled = !busy) { Icon(Icons.Default.ChevronRight, strings.getString(R.string.planner_edit_1_s, item.title)) }
                        }
                    }
                }
            }
            item { Text(strings.getString(R.string.planner_your_planner_is_stored_securely_on_this), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
    draft?.let { current ->
        key(current.id) {
            PlannerEditor(current, subjects, items.any { it.id == current.id }, busy, finding, error,
                onFindingChanged = { active ->
                    if (active) findingItemID = current.id
                    else if (findingItemID == current.id) findingItemID = null
                },
                onChange = ::edit,
                onDismiss = { draftData = null; error = null },
                onSave = { item, exportAction -> mutate({ repository.save(item) }) { draftData = null; export(item, exportAction) } },
                onDelete = { mutate({ repository.delete(current.id) }) { draftData = null } },
                onFindNextLesson = onFindNextLesson, calendarMirror = calendarMirror)
        }
    }
    if (pendingEntry != null && draft != null && !busy && !finding) {
        AlertDialog(
            onDismissRequest = { pendingEntryData = null },
            title = { Text(strings.getString(R.string.planner_entry_replace_title)) },
            text = { Text(strings.getString(R.string.planner_entry_replace_body, pendingEntry.title.ifBlank { strings.getString(R.string.planner_new_item) })) },
            confirmButton = { TextButton(onClick = { edit(pendingEntry); pendingEntryData = null; error = null }) {
                Text(strings.getString(R.string.planner_entry_discard_open))
            } },
            dismissButton = { TextButton(onClick = { pendingEntryData = null }) { Text(strings.getString(R.string.planner_entry_keep_editing)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannerEditor(
    item: PersonalPlannerItem,
    subjects: List<PlannerSubjectReference>,
    existing: Boolean,
    busy: Boolean,
    finding: Boolean,
    error: String?,
    onFindingChanged: (Boolean) -> Unit,
    onChange: (PersonalPlannerItem) -> Unit,
    onDismiss: () -> Unit,
    onSave: (PersonalPlannerItem, PlannerExport) -> Unit,
    onDelete: () -> Unit,
    onFindNextLesson: (suspend (PersonalPlannerItem) -> Instant?)?,
    calendarMirror: PlannerCalendarMirror?,
) {
    val strings = androidx.compose.ui.platform.LocalContext.current.resources
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var nextMessage by remember { mutableStateOf<String?>(null) }
    var subjectMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val enabled = !busy && !finding
    DisposableEffect(item.id) { onDispose { onFindingChanged(false) } }
    val choices = (subjects + listOfNotNull(item.subject)).distinctBy { "${it.scope}\u0000${it.id}" }
    fun pickDate(current: String?, apply: (String) -> Unit) {
        val date = current?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now(PersonalPlanner.zone(item))
        DatePickerDialog(context, { _, year, month, day -> apply(LocalDate.of(year, month + 1, day).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
    }
    ModalBottomSheet(onDismissRequest = { if (!busy) onDismiss() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.imePadding().testTag("plannerEditor"), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text(strings.getString(R.string.planner_cancel)) }
                    Text(if (existing) strings.getString(R.string.planner_edit_item) else strings.getString(R.string.planner_new_item), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    TextButton(onClick = { onSave(item, PlannerExport.NONE) }, enabled = enabled && item.title.isNotBlank()) { Text(strings.getString(R.string.planner_save)) }
                }
            }
            if (!enabled) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
            error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
            item {
                GradeySectionCard(title = strings.getString(R.string.planner_details)) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(PlannerItemType.entries) { type -> FilterChip(item.type == type, { onChange(item.copy(type = type)) }, enabled = enabled, label = { Text(plannerTypeTitle(type, strings)) }) }
                    }
                    OutlinedTextField(item.title, { onChange(item.copy(title = it.take(300))) }, modifier = Modifier.fillMaxWidth(), label = { Text(strings.getString(R.string.planner_title)) }, enabled = enabled, singleLine = true)
                    OutlinedTextField(item.notes, { onChange(item.copy(notes = it.take(20_000))) }, modifier = Modifier.fillMaxWidth(), label = { Text(strings.getString(R.string.planner_notes)) }, enabled = enabled, minLines = 3, maxLines = 8)
                }
            }
            item {
                GradeySectionCard(title = strings.getString(R.string.planner_school_context)) {
                    if (item.lesson != null) {
                        Text(item.subject?.name ?: strings.getString(R.string.planner_linked_lesson), fontWeight = FontWeight.SemiBold)
                        Text(listOfNotNull(item.lesson?.hourCaption, item.lesson?.teacher, item.lesson?.room).joinToString(" · "), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        item.linkedDate?.let { Text(it) } ?: Text(strings.getString(R.string.planner_this_is_an_undated_timetable_lesson_choose), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onChange(item.copy(lesson = null)) }, enabled = enabled) { Text(strings.getString(R.string.planner_unlink_lesson)) }
                    } else {
                        Column {
                            OutlinedButton(onClick = { subjectMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = enabled) { Text(item.subject?.name ?: strings.getString(R.string.planner_choose_a_subject_optional)) }
                            DropdownMenu(subjectMenu, { subjectMenu = false }) {
                                DropdownMenuItem(text = { Text(strings.getString(R.string.planner_no_subject)) }, onClick = { onChange(item.copy(subject = null)); subjectMenu = false })
                                choices.forEach { subject -> DropdownMenuItem(text = { Text(subject.name) }, onClick = { onChange(item.copy(subject = subject)); subjectMenu = false }) }
                            }
                        }
                        PlannerToggle(strings.getString(R.string.planner_link_to_a_day), item.linkedDate != null, enabled) { linked -> onChange(item.copy(linkedDate = if (linked) LocalDate.now(PersonalPlanner.zone(item)).toString() else null)) }
                        item.linkedDate?.let { day -> OutlinedButton(onClick = { pickDate(day) { onChange(item.copy(linkedDate = it)) } }, enabled = enabled) { Text(dayLabel(LocalDate.parse(day), strings)) } }
                    }
                }
            }
            item {
                GradeySectionCard(title = strings.getString(R.string.planner_due_date)) {
                    PlannerToggle(strings.getString(R.string.planner_set_a_due_date), item.dueDate != null, enabled) { dated -> onChange(item.copy(dueDate = if (dated) item.linkedDate ?: LocalDate.now(PersonalPlanner.zone(item)).toString() else null, dueTime = null)) }
                    item.dueDate?.let { due ->
                        OutlinedButton(onClick = { pickDate(due) { onChange(item.copy(dueDate = it)) } }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(dayLabel(LocalDate.parse(due), strings)) }
                        PlannerToggle(strings.getString(R.string.planner_include_a_time), item.dueTime != null, enabled) { timed -> onChange(item.copy(dueTime = if (timed) "08:00" else null)) }
                        item.dueTime?.let { raw ->
                            OutlinedButton(onClick = {
                                val time = LocalTime.parse(raw)
                                TimePickerDialog(context, { _, hour, minute -> onChange(item.copy(dueTime = "%02d:%02d".format(Locale.ROOT, hour, minute))) }, time.hour, time.minute, true).show()
                            }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(strings.getString(R.string.planner_due_at_1_s, raw)) }
                        }
                    }
                    if (item.lesson?.startEpochMillis != null && onFindNextLesson != null) {
                        OutlinedButton(onClick = {
                            onFindingChanged(true)
                            nextMessage = null
                            scope.launch {
                                try {
                                    val next = onFindNextLesson(item)
                                    if (next == null) nextMessage = strings.getString(R.string.planner_no_next_lesson_was_found_in_the)
                                    else { onChange(PersonalPlanner.withDueInstant(item, next)); nextMessage = strings.getString(R.string.planner_due_date_set_to_the_next_matching) }
                                } catch (cancelled: CancellationException) { throw cancelled }
                                catch (failure: Exception) { nextMessage = failure.message?.takeIf { it.length <= 250 } ?: strings.getString(R.string.planner_could_not_look_up_the_next_lesson) }
                                finally { onFindingChanged(false) }
                            }
                        }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(strings.getString(R.string.planner_due_at_the_next_lesson)) }
                    }
                    nextMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    Text(strings.getString(R.string.planner_time_zone_1_s, item.timeZoneID), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            calendarMirror?.let { mirror -> item { PlannerCalendarSettings(item, mirror, enabled, onChange) } }
            if (PersonalPlanner.orderingDate(item) != null) item {
                GradeySectionCard(title = strings.getString(R.string.planner_calendar_copy)) {
                    Text(strings.getString(R.string.planner_export_a_copy_to_your_calendar_later), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedButton(onClick = { onSave(item, PlannerExport.CALENDAR) }, enabled = enabled && item.title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.CalendarMonth, null); Text(strings.getString(R.string.planner_save_add_to_calendar)) }
                    TextButton(onClick = { onSave(item, PlannerExport.ICS) }, enabled = enabled && item.title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(strings.getString(R.string.planner_save_export_calendar_file)) }
                }
            }
            if (existing) item { TextButton(onClick = { confirmDelete = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(strings.getString(R.string.planner_delete_item), color = MaterialTheme.colorScheme.error) } }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(strings.getString(R.string.planner_delete_this_planner_item)) }, text = { Text(strings.getString(R.string.planner_1_s_will_be_removed_from_your, item.title)) },
        confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text(strings.getString(R.string.planner_delete), color = MaterialTheme.colorScheme.error) } }, dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(strings.getString(R.string.planner_keep_item)) } })
}

@Composable
private fun PlannerToggle(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked, onChange, enabled = enabled, modifier = Modifier.semantics { contentDescription = label })
    }
}

private fun dayLabel(date: LocalDate?, strings: android.content.res.Resources): String = when (date) {
    null -> strings.getString(R.string.planner_no_date)
    LocalDate.now() -> strings.getString(R.string.planner_today)
    LocalDate.now().plusDays(1) -> strings.getString(R.string.planner_tomorrow)
    else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", strings.configuration.locales[0]))
}

private fun plannerTypeTitle(type: PlannerItemType, strings: android.content.res.Resources): String = when (type) {
    PlannerItemType.HOMEWORK -> strings.getString(R.string.planner_homework)
    PlannerItemType.TEST -> strings.getString(R.string.planner_test)
    PlannerItemType.PRESENTATION -> strings.getString(R.string.planner_presentation)
    PlannerItemType.NOTE -> strings.getString(R.string.planner_note)
    PlannerItemType.TASK -> strings.getString(R.string.planner_task)
}
