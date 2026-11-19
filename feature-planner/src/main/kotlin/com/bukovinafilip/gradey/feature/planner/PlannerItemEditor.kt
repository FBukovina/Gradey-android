package com.bukovinafilip.gradey.feature.planner

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.domain.PlannerCalendarMirror
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.PlannerSubjectReference
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.DestructiveLink
import com.bukovinafilip.gradey.ui.GradeyDialog
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySheet
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SettingsGroup
import com.bukovinafilip.gradey.ui.SettingsNote
import com.bukovinafilip.gradey.ui.SettingsRowDivider
import com.bukovinafilip.gradey.ui.SettingsToggleRow
import com.bukovinafilip.gradey.ui.SettingsValueRow
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.tabular
import java.text.Collator
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class EditorPicker { LINKED_DAY, DUE_DATE, DUE_TIME }

/** Inset for dividers between rows that have no leading icon. */
private val PlainRowInset = GradeySpacing.xs

/**
 * New or existing planner item (iOS `PlannerItemEditorView`): details, school context, due date and
 * Calendar sections in a sheet, with Save as the one primary action and Delete below it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerItemEditor(
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
    onAskTestAI: ((String) -> Unit)?,
) {
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    var nextMessage by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<EditorPicker?>(null) }
    val enabled = !busy && !finding
    val canSave = enabled && item.title.isNotBlank()
    DisposableEffect(item.id) { onDispose { onFindingChanged(false) } }
    // Like iOS `interactiveDismissDisabled(isSaving)`: a swipe, scrim tap or back press waits for the save.
    val saving by rememberUpdatedState(busy)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { it != SheetValue.Hidden || !saving })
    fun close() { scope.launch { sheetState.hide() }.invokeOnCompletion { onDismiss() } }

    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val dateFormatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val timeFormatter = remember(locale, is24Hour) { DateTimeFormatter.ofPattern(if (is24Hour) "H:mm" else "h:mm a", locale) }
    val subjectChoices = remember(subjects, item.subject, locale) {
        val collator = Collator.getInstance(locale)
        (subjects + listOfNotNull(item.subject)).distinctBy { "${it.scope}\u0000${it.id}" }.sortedWith(compareBy(collator) { it.name })
    }
    val today = remember(item.timeZoneID) { LocalDate.now(PersonalPlanner.zone(item)) }
    fun findNextLesson(lookup: suspend (PersonalPlannerItem) -> Instant?) {
        onFindingChanged(true)
        nextMessage = null
        scope.launch {
            try {
                val next = lookup(item)
                if (next == null) nextMessage = resources.getString(R.string.planner_no_next_lesson_was_found_in_the)
                else { onChange(PersonalPlanner.withDueInstant(item, next)); nextMessage = resources.getString(R.string.planner_due_date_set_to_the_next_matching) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { nextMessage = failure.message?.takeIf { it.length <= 250 } ?: resources.getString(R.string.planner_could_not_look_up_the_next_lesson) }
            finally { onFindingChanged(false) }
        }
    }

    GradeySheet(onDismiss = { if (busy) scope.launch { sheetState.show() } else onDismiss() }, sheetState = sheetState) {
        Box(Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.minTouch)) {
            PlainLink(stringResource(R.string.planner_cancel), ::close, Modifier.align(Alignment.CenterStart), enabled = !busy, showsArrow = false)
            Text(
                stringResource(if (existing) R.string.planner_edit_item else R.string.planner_new_item),
                Modifier.align(Alignment.Center).padding(horizontal = 76.dp).semantics { heading() },
                style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center, maxLines = 2,
            )
        }
        LazyColumn(
            Modifier.fillMaxWidth().imePadding().testTag("plannerEditor"),
            contentPadding = PaddingValues(bottom = GradeySpacing.lg),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl),
        ) {
            error?.let { message -> item(key = "error") { InlineNotice(message) } }
            item(key = "details") {
                SettingsGroup(title = stringResource(R.string.planner_details)) {
                    TypePicker(item.type, enabled) { onChange(item.copy(type = it)) }
                    Column(Modifier.padding(horizontal = GradeySpacing.xs).padding(top = GradeySpacing.xs, bottom = GradeySpacing.md), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                        BrandTextField(
                            item.title, { onChange(item.copy(title = it.take(300))) },
                            Modifier.fillMaxWidth().testTag("plannerTitleField"),
                            placeholder = stringResource(R.string.planner_title), enabled = enabled, imeAction = ImeAction.Next,
                        )
                        BrandTextField(
                            item.notes, { onChange(item.copy(notes = it.take(20_000))) },
                            Modifier.fillMaxWidth().testTag("plannerNotesField"),
                            placeholder = stringResource(R.string.planner_notes), enabled = enabled,
                            singleLine = false, minLines = 3, maxLines = 8, imeAction = ImeAction.Default,
                        )
                    }
                }
            }
            item(key = "context") {
                SettingsGroup(title = stringResource(R.string.planner_school_context)) {
                    val lesson = item.lesson
                    if (lesson != null) {
                        SettingsValueRow(stringResource(R.string.planner_subject), item.subject?.name ?: stringResource(R.string.planner_no_subject))
                        SettingsRowDivider(leadingInset = PlainRowInset)
                        SettingsValueRow(stringResource(R.string.planner_lesson), lesson.hourCaption)
                        lesson.teacher?.takeIf { it.isNotBlank() }?.let { DetailLabel(HugeIcons.User, it) }
                        lesson.room?.takeIf { it.isNotBlank() }?.let { DetailLabel(HugeIcons.DoorOpen, it) }
                        if (item.linkedDate == null) SettingsNote(stringResource(R.string.planner_lesson_permanent), Modifier.padding(vertical = GradeySpacing.xs))
                        SettingsRowDivider(leadingInset = PlainRowInset)
                        PlainLink(
                            stringResource(R.string.planner_unlink_lesson), { onChange(item.copy(lesson = null)) },
                            Modifier.testTag("plannerUnlinkLessonButton"), enabled = enabled, showsArrow = false,
                        )
                    } else {
                        if (subjectChoices.isNotEmpty()) {
                            SubjectPicker(item.subject, subjectChoices, enabled) { onChange(item.copy(subject = it)) }
                            SettingsRowDivider(leadingInset = PlainRowInset)
                        }
                        SettingsToggleRow(
                            stringResource(R.string.planner_link_to_a_day), null, item.linkedDate != null, enabled,
                            onChange = { linked -> onChange(item.copy(linkedDate = if (linked) today.toString() else null)) },
                            modifier = Modifier.testTag("plannerLinkDayToggle"),
                        )
                        item.linkedDate?.let(PersonalPlannerDates::parse)?.let { day ->
                            SettingsRowDivider(leadingInset = PlainRowInset)
                            PickerRow(stringResource(R.string.planner_day)) {
                                PickerPill(day.format(dateFormatter), enabled) { picker = EditorPicker.LINKED_DAY }
                            }
                        }
                    }
                }
            }
            item(key = "due") {
                SettingsGroup(title = stringResource(R.string.planner_due)) {
                    SettingsToggleRow(
                        stringResource(R.string.planner_set_a_due_date), null, item.dueDate != null, enabled,
                        onChange = { dated -> onChange(item.copy(dueDate = if (dated) item.linkedDate ?: today.toString() else null, dueTime = null)) },
                        modifier = Modifier.testTag("plannerDueToggle"),
                    )
                    item.dueDate?.let(PersonalPlannerDates::parse)?.let { due ->
                        SettingsRowDivider(leadingInset = PlainRowInset)
                        PickerRow(stringResource(R.string.planner_due_date)) {
                            PickerPill(due.format(dateFormatter), enabled) { picker = EditorPicker.DUE_DATE }
                            item.dueTime?.let(::parseTime)?.let { time ->
                                PickerPill(time.format(timeFormatter), enabled) { picker = EditorPicker.DUE_TIME }
                            }
                        }
                        SettingsRowDivider(leadingInset = PlainRowInset)
                        SettingsToggleRow(
                            stringResource(R.string.planner_include_a_time), null, item.dueTime != null, enabled,
                            onChange = { timed -> onChange(item.copy(dueTime = if (timed) "08:00" else null)) },
                        )
                    }
                    if (item.lesson?.startEpochMillis != null && onFindNextLesson != null) {
                        SettingsRowDivider(leadingInset = PlainRowInset)
                        NextLessonRow(finding, enabled) { findNextLesson(onFindNextLesson) }
                    }
                    nextMessage?.let { SettingsNote(it, Modifier.padding(vertical = GradeySpacing.xs)) }
                }
            }
            calendarMirror?.let { mirror -> item(key = "calendarSync") { PlannerCalendarSettings(item, mirror, enabled, onChange) } }
            if (PersonalPlanner.orderingDate(item) != null) item(key = "calendarCopy") {
                SettingsGroup(title = stringResource(R.string.planner_calendar_copy), footer = stringResource(R.string.planner_export_a_copy_to_your_calendar_later)) {
                    PlainLink(stringResource(R.string.planner_save_add_to_calendar), { onSave(item, PlannerExport.CALENDAR) }, enabled = canSave)
                    PlainLink(stringResource(R.string.planner_save_export_calendar_file), { onSave(item, PlannerExport.ICS) }, enabled = canSave)
                }
            }
            if (existing && item.type == PlannerItemType.TEST && onAskTestAI != null) item(key = "ai") {
                PlainLink(stringResource(R.string.planner_prepare_with_ai), { onAskTestAI(item.id) }, enabled = enabled)
            }
            item(key = "save") {
                GradeyPrimaryButton(
                    stringResource(R.string.planner_save), { onSave(item, PlannerExport.NONE) },
                    Modifier.testTag("plannerSaveButton"), enabled = canSave, loading = busy,
                )
            }
            if (existing) item(key = "delete") {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    DestructiveLink(stringResource(R.string.planner_delete_item), { confirmDelete = true }, Modifier.testTag("plannerDeleteButton"), enabled = enabled)
                }
            }
        }
    }

    when (picker) {
        EditorPicker.LINKED_DAY -> PlannerDatePicker(
            initial = item.linkedDate?.let(PersonalPlannerDates::parse) ?: today,
            onDismiss = { picker = null },
            onConfirm = { onChange(item.copy(linkedDate = it.toString())); picker = null },
        )
        EditorPicker.DUE_DATE -> PlannerDatePicker(
            initial = item.dueDate?.let(PersonalPlannerDates::parse) ?: today,
            onDismiss = { picker = null },
            onConfirm = { onChange(item.copy(dueDate = it.toString())); picker = null },
        )
        EditorPicker.DUE_TIME -> PlannerTimePicker(
            title = stringResource(R.string.planner_due_date),
            initial = item.dueTime?.let(::parseTime) ?: LocalTime.of(8, 0),
            is24Hour = is24Hour,
            onDismiss = { picker = null },
            onConfirm = { time -> onChange(item.copy(dueTime = "%02d:%02d".format(Locale.ROOT, time.hour, time.minute))); picker = null },
        )
        null -> Unit
    }
    if (confirmDelete) GradeyDialog(
        title = stringResource(R.string.planner_delete_this_planner_item),
        text = stringResource(R.string.planner_1_s_will_be_removed_from_your, item.title),
        onDismiss = { confirmDelete = false },
        confirmLabel = stringResource(R.string.planner_delete),
        onConfirm = { confirmDelete = false; onDelete() },
        dismissLabel = stringResource(R.string.planner_cancel),
        destructive = true,
    )
}

private fun parseTime(raw: String): LocalTime? = runCatching { LocalTime.parse(raw) }.getOrNull()

// MARK: - Rows

/**
 * Type menu (iOS `LabeledContent` with a menu picker). Five types do not fit a segmented control at
 * phone width, so this follows iOS rather than truncating every label.
 */
@Composable
private fun TypePicker(selected: PlannerItemType, enabled: Boolean, onSelect: (PlannerItemType) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val primary = MaterialTheme.colorScheme.primary
    Box {
        SettingsValueRow(
            stringResource(R.string.planner_type), stringResource(plannerTypeTitle(selected)),
            Modifier.testTag("plannerTypePicker"), onClick = { open = true }, enabled = enabled,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.gradeyColors.secondaryGroupedBackground) {
            PlannerItemType.entries.forEach { type ->
                DropdownMenuItem(
                    text = { Text(stringResource(plannerTypeTitle(type)), style = GradeyType.body) },
                    leadingIcon = { GradeyIcon(plannerTypeIcon(type), size = 18.dp, tint = primary) },
                    trailingIcon = if (type == selected) ({ GradeyIcon(HugeIcons.Tick02, size = 16.dp, tint = primary) }) else null,
                    onClick = { open = false; onSelect(type) },
                )
            }
        }
    }
}

/** Subject menu (iOS `LabeledContent` with a menu picker). */
@Composable
private fun SubjectPicker(
    selected: PlannerSubjectReference?,
    choices: List<PlannerSubjectReference>,
    enabled: Boolean,
    onSelect: (PlannerSubjectReference?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        SettingsValueRow(
            stringResource(R.string.planner_subject), selected?.name ?: stringResource(R.string.planner_no_subject),
            Modifier.testTag("plannerSubjectPicker"), onClick = { open = true }, enabled = enabled,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.gradeyColors.secondaryGroupedBackground) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.planner_no_subject), style = GradeyType.body) },
                onClick = { open = false; onSelect(null) },
            )
            choices.forEach { subject ->
                DropdownMenuItem(
                    text = { Text(subject.name, style = GradeyType.body) },
                    onClick = { open = false; onSelect(subject) },
                )
            }
        }
    }
}

/** Teacher or room line under a linked lesson. */
@Composable
private fun DetailLabel(icon: String, text: String) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Row(
        Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        GradeyIcon(icon, size = 14.dp, tint = secondary)
        Text(text, style = GradeyType.subheadline, color = secondary)
    }
}

/** Label on the left, compact date/time pills on the right (iOS compact `DatePicker`). */
@Composable
private fun PickerRow(title: String, pills: @Composable RowScope.() -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.xs),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        Text(title, Modifier.weight(1f), style = GradeyType.body, color = MaterialTheme.colorScheme.onSurface)
        pills()
    }
}

@Composable
private fun PickerPill(text: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = GradeyMetrics.minTouch).clip(RoundedCornerShape(GradeyRadius.xs))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            Modifier.background(MaterialTheme.gradeyColors.tertiaryFill, RoundedCornerShape(GradeyRadius.xs))
                .padding(horizontal = GradeySpacing.md, vertical = 6.dp),
            style = GradeyType.subheadline.tabular(),
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 1f else 0.45f),
        )
    }
}

/** "Next lesson" action with a spinner while it looks the lesson up (iOS `planner.nextLesson`). */
@Composable
private fun NextLessonRow(finding: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.45f)
    Row(
        Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.minTouch).clip(RoundedCornerShape(GradeyRadius.xs))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.sm)
            .testTag("plannerNextLessonButton"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        GradeyIcon(HugeIcons.Forward01, size = 16.dp, tint = tint)
        Text(stringResource(R.string.planner_next_lesson), Modifier.weight(1f), style = GradeyType.subheadlineSemibold, color = tint)
        if (finding) CircularProgressIndicator(Modifier.size(16.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
    }
}

// MARK: - Pickers

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannerDatePicker(initial: LocalDate, onDismiss: () -> Unit, onConfirm: (LocalDate) -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    val selected = state.selectedDateMillis
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            DialogAction(stringResource(R.string.planner_done), enabled = selected != null) {
                selected?.let { onConfirm(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
            }
        },
        dismissButton = { DialogAction(stringResource(R.string.planner_cancel), onClick = onDismiss) },
    ) { DatePicker(state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlannerTimePicker(title: String, initial: LocalTime, is24Hour: Boolean, onDismiss: () -> Unit, onConfirm: (LocalTime) -> Unit) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = is24Hour)
    TimePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = { DialogAction(stringResource(R.string.planner_done)) { onConfirm(LocalTime.of(state.hour, state.minute)) } },
        dismissButton = { DialogAction(stringResource(R.string.planner_cancel), onClick = onDismiss) },
        title = { Text(title, style = GradeyType.subheadlineSemibold, color = MaterialTheme.gradeyColors.secondaryLabel) },
    ) { TimePicker(state) }
}

/** Text action for the Material picker dialogs, styled like [GradeyDialog]'s buttons. */
@Composable
private fun DialogAction(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.heightIn(min = GradeyMetrics.minTouch).clip(RoundedCornerShape(GradeyRadius.xs))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = GradeySpacing.md),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.45f))
    }
}
