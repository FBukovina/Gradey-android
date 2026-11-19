package com.bukovinafilip.gradey.feature.absence

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.AbsenceOverrideProjection
import com.bukovinafilip.gradey.model.Absence
import com.bukovinafilip.gradey.model.AbsenceDayOverride
import com.bukovinafilip.gradey.model.AbsenceOverrideAllocation
import com.bukovinafilip.gradey.model.AbsenceOverrideCategory
import com.bukovinafilip.gradey.model.AbsenceOverridePauseReason
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySheet
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SettingsToggleRow
import com.bukovinafilip.gradey.ui.StatusChip
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.tabular

/**
 * Sheet for correcting one recorded day (iOS `AbsenceOverrideEditorView`): each recorded unit gets
 * its original subject and lesson and can be hidden from Gradey's calculations. The school record
 * itself never changes.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AbsenceOverrideEditor(
    rawResponse: AbsenceResponse,
    day: Absence,
    dayTitle: String,
    scope: String,
    existing: AbsenceDayOverride?,
    availableSubjects: List<Subject>,
    lessons: List<ScheduledLesson>,
    isSaving: Boolean,
    errorMessage: String?,
    palette: AbsencePalette,
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
    val selectableLessons = remember(lessons) { lessons.filter { it.changeKind != LessonChangeKind.CANCELED } }
    val draft = AbsenceDayOverride(scope, date, day, allocations, hidden, id = existing?.id ?: "$scope-$date")
    val validation = remember(rawResponse, allocations, hidden, scope, lessons) {
        AbsenceOverrideProjection.project(rawResponse, listOf(draft), scope,
            lessonsByDate = if (lessons.isEmpty()) null else mapOf(date to lessons))
    }
    val issue = validation.reviewOverrides.firstOrNull()?.pauseReason
    val canSave = hidden.isNotEmpty() && issue == null && !isSaving
    fun update(id: String, change: (AbsenceOverrideAllocation) -> AbsenceOverrideAllocation) {
        allocations = allocations.map { if (it.id == id) change(it) else it }
    }

    GradeySheet(onDismiss = { if (!isSaving) onDismiss() }, modifier = Modifier.testTag("absenceOverrideEditor")) {
        Box(Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.minTouch)) {
            PlainLink(stringResource(R.string.absence_cancel_77dfd), onDismiss, Modifier.align(Alignment.CenterStart), enabled = !isSaving, showsArrow = false)
            Text(
                dayTitle, Modifier.align(Alignment.Center).padding(horizontal = 88.dp).semantics { heading() },
                style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            item(key = "intro", contentType = "intro") {
                Column(Modifier.padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    Text(
                        stringResource(R.string.absence_these_changes_affect_your_calculations_in_gradey_they_do_not_c_631d3),
                        style = GradeyType.subheadline, color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        stringResource(R.string.absence_assign_every_recorded_attendance_unit_to_its_original_subject__50b9c),
                        style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.lg)) {
                        PlainLink(stringResource(R.string.absence_hide_entire_day_16a85), { hidden = allocations.map { it.id }.toSet() }, enabled = !isSaving, showsArrow = false)
                        PlainLink(stringResource(R.string.absence_clear_selection_247fd), { hidden = emptySet() }, enabled = !isSaving && hidden.isNotEmpty(), showsArrow = false)
                    }
                }
            }
            itemsIndexed(allocations, key = { _, allocation -> allocation.id }, contentType = { _, _ -> "unit" }) { index, allocation ->
                val category = AbsenceCategory.of(allocation.category)
                Column(Modifier.fillMaxWidth()) {
                    Hairline(Modifier.padding(horizontal = GradeySpacing.xs))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs).padding(top = GradeySpacing.md),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
                    ) {
                        Text("${index + 1}.", style = GradeyType.subheadlineBold.tabular(), color = MaterialTheme.gradeyColors.secondaryLabel)
                        StatusChip(stringResource(category.label), color = palette.color(category))
                    }
                    if (names.isEmpty()) {
                        BrandTextField(
                            value = allocation.subjectName,
                            onValueChange = { value -> update(allocation.id) { it.copy(subjectName = value, subjectID = null, lessonID = null) } },
                            modifier = Modifier.fillMaxWidth().padding(top = GradeySpacing.sm),
                            placeholder = stringResource(R.string.absence_original_subject_694f9), enabled = !isSaving,
                        )
                    } else {
                        ChoiceRow(
                            icon = HugeIcons.Book02,
                            label = allocation.subjectName.ifBlank { stringResource(R.string.absence_choose_original_subject_52b82) },
                            placeholder = allocation.subjectName.isBlank(), options = names, enabled = !isSaving,
                        ) { index -> update(allocation.id) { it.copy(subjectName = names[index], subjectID = null, lessonID = null, lessonTitle = "") } }
                    }
                    if (selectableLessons.isNotEmpty()) {
                        val lessonLabels = remember(selectableLessons) {
                            selectableLessons.map { lesson -> "${lesson.hour.caption}. ${lesson.subjectName ?: lesson.title} · ${lesson.timeRange}" }
                        }
                        ChoiceRow(
                            icon = HugeIcons.Clock01,
                            label = allocation.lessonTitle.ifBlank { stringResource(R.string.absence_choose_original_timetable_lesson_0551f) },
                            placeholder = allocation.lessonTitle.isBlank(), options = lessonLabels, enabled = !isSaving,
                        ) { index ->
                            val lesson = selectableLessons[index]
                            update(allocation.id) {
                                it.copy(
                                    lessonID = lesson.id, subjectID = lesson.subjectID, subjectName = lesson.subjectName ?: lesson.title,
                                    lessonTitle = "${lesson.hour.caption}. ${lesson.subjectName ?: lesson.title}",
                                )
                            }
                        }
                    }
                    SettingsToggleRow(
                        title = stringResource(R.string.absence_hide_from_my_calculations_83a1d), subtitle = null,
                        checked = allocation.id in hidden, enabled = !isSaving,
                        onChange = { selected -> hidden = if (selected) hidden + allocation.id else hidden - allocation.id },
                    )
                }
            }
            item(key = "summary", contentType = "summary") {
                Column(Modifier.padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    Hairline()
                    Text(
                        stringResource(R.string.absence_remaining_units, allocations.size - hidden.size, allocations.size),
                        Modifier.padding(top = GradeySpacing.sm), style = GradeyType.subheadlineBold.tabular(), color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (rawResponse.absencesPerSubject.isEmpty()) Text(
                        stringResource(R.string.absence_subject_percentages_remain_unavailable_because_the_school_does_7a94d),
                        style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel,
                    )
                    if (hidden.isNotEmpty() && issue != null) InlineNotice(issue.localizedDescription())
                    errorMessage?.let { InlineNotice(it) }
                }
            }
        }
        GradeyPrimaryButton(
            stringResource(R.string.absence_save_correction_c66c5), { onSave(draft.copy(savedAtEpochMillis = System.currentTimeMillis())) },
            enabled = canSave, loading = isSaving,
        )
    }
}

/** A value that opens a menu of choices: icon, current value, chevron (iOS menu picker row). */
@Composable
private fun ChoiceRow(icon: String, label: String, placeholder: Boolean, options: List<String>, enabled: Boolean, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val tokens = MaterialTheme.gradeyColors
    Box(Modifier.fillMaxWidth().padding(top = GradeySpacing.sm)) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.fieldHeight).clip(RoundedCornerShape(GradeyRadius.sm)).background(tokens.tertiaryFill)
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true }
                .padding(horizontal = GradeySpacing.md, vertical = GradeySpacing.sm),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
        ) {
            GradeyIcon(icon, size = 16.dp, tint = tokens.secondaryLabel)
            Text(
                label, Modifier.weight(1f), style = GradeyType.body,
                color = if (placeholder) tokens.tertiaryLabel else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            GradeyIcon(HugeIcons.ArrowDown01, size = 13.dp, tint = tokens.secondaryLabel)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = tokens.secondaryGroupedBackground) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(
                    text = { Text(option, style = GradeyType.body, color = MaterialTheme.colorScheme.onSurface) },
                    onClick = { expanded = false; onSelect(index) },
                )
            }
        }
    }
}

@Composable
internal fun AbsenceOverridePauseReason.localizedDescription(): String = stringResource(when (this) {
    AbsenceOverridePauseReason.DAY_MISSING -> R.string.absence_correction_reason_day_missing
    AbsenceOverridePauseReason.DAY_CHANGED -> R.string.absence_correction_reason_day_changed
    AbsenceOverridePauseReason.AMBIGUOUS_DAY -> R.string.absence_correction_reason_ambiguous_day
    AbsenceOverridePauseReason.INVALID_ALLOCATION -> R.string.absence_correction_reason_invalid_allocation
    AbsenceOverridePauseReason.SUBJECT_MAPPING_CHANGED -> R.string.absence_correction_reason_subject_mapping_changed
    AbsenceOverridePauseReason.LESSON_MAPPING_CHANGED -> R.string.absence_correction_reason_lesson_mapping_changed
    AbsenceOverridePauseReason.INSUFFICIENT_SUBJECT_COUNT -> R.string.absence_correction_reason_insufficient_subject_count
})
