package com.bukovinafilip.gradey.feature.absence

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.AbsenceOverrideProjection
import com.bukovinafilip.gradey.model.*

@Composable
internal fun AbsenceOverrideEditor(
    rawResponse: AbsenceResponse,
    day: Absence,
    scope: String,
    existing: AbsenceDayOverride?,
    availableSubjects: List<Subject>,
    lessons: List<ScheduledLesson>,
    isSaving: Boolean,
    errorMessage: String?,
    onSave: (AbsenceDayOverride) -> Unit,
    onDismiss: () -> Unit,
) {
    val date = AbsenceOverrideProjection.dateKey(day.date)
    val compatible = existing?.takeIf { saved -> AbsenceOverrideCategory.entries.all {
        AbsenceOverrideProjection.count(saved.baselineDay, it) == AbsenceOverrideProjection.count(day, it)
    } }
    var allocations by remember(scope, date) {
        mutableStateOf(compatible?.allocations ?: AbsenceOverrideCategory.entries.flatMap { category ->
            List(AbsenceOverrideProjection.count(day, category).coerceIn(0, 100)) { index ->
                AbsenceOverrideAllocation(id = "$date-${category.name}-$index", subjectName = day.subject.orEmpty(), category = category)
            }
        })
    }
    var hidden by remember(scope, date) { mutableStateOf(compatible?.hiddenAllocationIDs.orEmpty()) }
    val names = remember(rawResponse, availableSubjects) {
        (rawResponse.absencesPerSubject.map { it.subjectName } + availableSubjects.map { it.displayName }).filter { it.isNotBlank() }.distinct().sorted()
    }
    val draft = AbsenceDayOverride(scope, date, day, allocations, hidden, id = existing?.id ?: "$scope-$date")
    val validation = remember(rawResponse, allocations, hidden, scope, lessons) {
        AbsenceOverrideProjection.project(rawResponse, listOf(draft), scope,
            lessonsByDate = if (lessons.isEmpty()) null else mapOf(date to lessons))
    }
    val issue = validation.reviewOverrides.firstOrNull()?.pauseReason
    val canSave = hidden.isNotEmpty() && issue == null && !isSaving

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = { Text(stringResource(R.string.absence_correction_title, date)) },
        text = {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.absence_these_changes_affect_your_calculations_in_gradey_they_do_not_c_631d3))
                Text(stringResource(R.string.absence_assign_every_recorded_attendance_unit_to_its_original_subject__50b9c), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { hidden = allocations.map { it.id }.toSet() }, enabled = !isSaving) { Text(stringResource(R.string.absence_hide_entire_day_16a85)) }
                    TextButton(onClick = { hidden = emptySet() }, enabled = !isSaving) { Text(stringResource(R.string.absence_clear_selection_247fd)) }
                }
                allocations.forEachIndexed { index, allocation ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("${index + 1}. ${allocation.category.localizedLabel()}", fontWeight = FontWeight.SemiBold)
                            if (names.isEmpty()) {
                                OutlinedTextField(value = allocation.subjectName, onValueChange = { value ->
                                    allocations = allocations.map { if (it.id == allocation.id) it.copy(subjectName = value, subjectID = null, lessonID = null) else it }
                                }, label = { Text(stringResource(R.string.absence_original_subject_694f9)) }, singleLine = true, enabled = !isSaving, modifier = Modifier.fillMaxWidth())
                            } else {
                                CorrectionChoice(label = allocation.subjectName.ifBlank { stringResource(R.string.absence_choose_original_subject_52b82) }, options = names, enabled = !isSaving) { name ->
                                    allocations = allocations.map { if (it.id == allocation.id) it.copy(subjectName = name, subjectID = null, lessonID = null, lessonTitle = "") else it }
                                }
                            }
                            if (lessons.isNotEmpty()) {
                                var expanded by remember(allocation.id) { mutableStateOf(false) }
                                Box {
                                    TextButton(onClick = { expanded = true }, enabled = !isSaving) {
                                        Text(allocation.lessonTitle.ifBlank { stringResource(R.string.absence_choose_original_timetable_lesson_0551f) })
                                    }
                                    DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                                        lessons.filter { it.changeKind != LessonChangeKind.CANCELED }.forEach { lesson ->
                                            DropdownMenuItem(text = { Text("${lesson.hour.caption}. ${lesson.subjectName ?: lesson.title} · ${lesson.timeRange}") }, onClick = {
                                                allocations = allocations.map { if (it.id == allocation.id) it.copy(
                                                    lessonID = lesson.id, subjectID = lesson.subjectID,
                                                    subjectName = lesson.subjectName ?: lesson.title,
                                                    lessonTitle = "${lesson.hour.caption}. ${lesson.subjectName ?: lesson.title}",
                                                ) else it }
                                                expanded = false
                                            })
                                        }
                                    }
                                }
                            }
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                Text(stringResource(R.string.absence_hide_from_my_calculations_83a1d), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                Switch(checked = allocation.id in hidden, onCheckedChange = { selected ->
                                    hidden = if (selected) hidden + allocation.id else hidden - allocation.id
                                }, enabled = !isSaving)
                            }
                        }
                    }
                }
                Text(stringResource(R.string.absence_remaining_units, allocations.size - hidden.size, allocations.size), fontWeight = FontWeight.SemiBold)
                if (rawResponse.absencesPerSubject.isEmpty()) Text(stringResource(R.string.absence_subject_percentages_remain_unavailable_because_the_school_does_7a94d), style = MaterialTheme.typography.bodySmall)
                if (hidden.isNotEmpty() && issue != null) Text(issue.localizedDescription(), color = MaterialTheme.colorScheme.error)
                errorMessage?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (isSaving) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(draft.copy(savedAtEpochMillis = System.currentTimeMillis())) }, enabled = canSave) { Text(stringResource(R.string.absence_save_correction_c66c5)) } },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !isSaving) { Text(stringResource(R.string.absence_cancel_77dfd)) } },
    )
}

@Composable
private fun CorrectionChoice(label: String, options: List<String>, enabled: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text(label) }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option -> DropdownMenuItem(text = { Text(option) }, onClick = { onSelect(option); expanded = false }) }
        }
    }
}

@Composable
internal fun AttendanceCorrectionsCard(
    rawResponse: AbsenceResponse,
    overrides: List<AbsenceDayOverride>,
    scope: String,
    isLoading: Boolean,
    onEdit: (String) -> Unit,
    onRestore: ((String) -> Unit)?,
    onReset: (() -> Unit)?,
) {
    var choosingDay by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val projection = remember(rawResponse, overrides, scope) { AbsenceOverrideProjection.project(rawResponse, overrides, scope) }
    val dates = remember(rawResponse) { rawResponse.absences.groupBy { AbsenceOverrideProjection.dateKey(it.date) }
        .filterValues { it.size == 1 }.keys.sortedDescending() }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.absence_attendance_corrections_4cc6e), style = MaterialTheme.typography.titleMedium)
            Text(if (projection.hiddenCount > 0) stringResource(R.string.absence_hidden_units, projection.hiddenCount)
                else stringResource(R.string.absence_make_a_local_correction_while_keeping_your_school_s_original_r_9d10c), style = MaterialTheme.typography.bodySmall)
            Box {
                OutlinedButton(onClick = { choosingDay = true }, enabled = !isLoading && dates.isNotEmpty()) { Text(stringResource(R.string.absence_correct_a_recorded_day_cfa2a)) }
                DropdownMenu(choosingDay, onDismissRequest = { choosingDay = false }) {
                    dates.forEach { date -> DropdownMenuItem(text = { Text(date) }, onClick = { choosingDay = false; onEdit(date) }) }
                }
            }
            if (dates.isEmpty()) Text(stringResource(R.string.absence_there_are_no_unambiguous_recorded_days_to_correct_c54a9), style = MaterialTheme.typography.bodySmall)
            projection.reconciledOverrides.forEach { saved ->
                HorizontalDivider()
                Text(saved.dateKey, fontWeight = FontWeight.SemiBold)
                Text(saved.pauseReason?.let { stringResource(R.string.absence_needs_review, it.localizedDescription()) } ?: stringResource(R.string.absence_hidden_locally, saved.hiddenAllocationIDs.size), style = MaterialTheme.typography.bodySmall,
                    color = if (saved.pauseReason != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    if (saved.dateKey in dates) TextButton(onClick = { onEdit(saved.dateKey) }, enabled = !isLoading) { Text(stringResource(R.string.absence_review_e29a7)) }
                    if (onRestore != null) TextButton(onClick = { onRestore(saved.dateKey) }, enabled = !isLoading) { Text(stringResource(R.string.absence_restore_school_record_a0dd4)) }
                }
            }
            if (overrides.isNotEmpty() && onReset != null) TextButton(onClick = { confirmReset = true }, enabled = !isLoading) { Text(stringResource(R.string.absence_restore_all_school_records_8b60b)) }
        }
    }
    if (confirmReset && onReset != null) AlertDialog(
        onDismissRequest = { confirmReset = false }, title = { Text(stringResource(R.string.absence_restore_school_records_bd7cd)) },
        text = { Text(stringResource(R.string.absence_remove_all_local_attendance_corrections_for_this_school_accoun_ce530)) },
        confirmButton = { TextButton(onClick = { confirmReset = false; onReset() }) { Text(stringResource(R.string.absence_restore_all_9eca7)) } },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.absence_cancel_77dfd)) } },
    )
}

@Composable
private fun AbsenceOverrideCategory.localizedLabel(): String = stringResource(when (this) {
    AbsenceOverrideCategory.UNSOLVED -> R.string.absence_correction_category_unsolved
    AbsenceOverrideCategory.OK -> R.string.absence_correction_category_ok
    AbsenceOverrideCategory.MISSED -> R.string.absence_correction_category_missed
    AbsenceOverrideCategory.LATE -> R.string.absence_correction_category_late
    AbsenceOverrideCategory.SOON -> R.string.absence_correction_category_soon
    AbsenceOverrideCategory.SCHOOL -> R.string.absence_correction_category_school
    AbsenceOverrideCategory.DISTANCE_TEACHING -> R.string.absence_correction_category_distance_teaching
})

@Composable
private fun AbsenceOverridePauseReason.localizedDescription(): String = stringResource(when (this) {
    AbsenceOverridePauseReason.DAY_MISSING -> R.string.absence_correction_reason_day_missing
    AbsenceOverridePauseReason.DAY_CHANGED -> R.string.absence_correction_reason_day_changed
    AbsenceOverridePauseReason.AMBIGUOUS_DAY -> R.string.absence_correction_reason_ambiguous_day
    AbsenceOverridePauseReason.INVALID_ALLOCATION -> R.string.absence_correction_reason_invalid_allocation
    AbsenceOverridePauseReason.SUBJECT_MAPPING_CHANGED -> R.string.absence_correction_reason_subject_mapping_changed
    AbsenceOverridePauseReason.LESSON_MAPPING_CHANGED -> R.string.absence_correction_reason_lesson_mapping_changed
    AbsenceOverridePauseReason.INSUFFICIENT_SUBJECT_COUNT -> R.string.absence_correction_reason_insufficient_subject_count
})
