package com.bukovinafilip.gradey.feature.absence

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
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
import androidx.compose.ui.text.style.TextAlign
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
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySheet
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.SectionFooter
import com.bukovinafilip.gradey.ui.StatusChip
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.struck
import com.bukovinafilip.gradey.ui.tabular

/** The lesson number's column, as wide as two of its digits (iOS `hourColumnWidth`). */
private val HourColumnWidth = 24.dp

/** A timetable lesson the day's units can be linked to, prepared once per timetable. */
@Immutable
private class LessonChoice(val lesson: ScheduledLesson, val label: String, val title: String)

/**
 * Sheet for correcting one recorded day (iOS `AbsenceOverrideEditorView`): every recorded unit gets
 * its original subject and lesson and can be removed from Gradey's calculations or put back. The
 * school record itself never changes.
 */
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
    val date = remember(day) { AbsenceOverrideProjection.dateKey(day.date) }
    // A saved correction is reused only while the school still reports the same counts.
    val compatible = remember(existing, day) {
        existing?.takeIf { saved ->
            AbsenceOverrideCategory.entries.all { AbsenceOverrideProjection.count(saved.baselineDay, it) == AbsenceOverrideProjection.count(day, it) }
        }
    }
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
    val choices = remember(lessons) {
        lessons.filter { it.changeKind != LessonChangeKind.CANCELED }.map { lesson ->
            val subject = lesson.subjectName ?: lesson.title
            LessonChoice(lesson, "${lesson.hour.caption}. $subject · ${lesson.timeRange}", "${lesson.hour.caption}. $subject")
        }
    }
    val choiceLabels = remember(choices) { choices.map { it.label } }
    val lessonsByID = remember(choices) { choices.associateBy { it.lesson.id } }
    val mixedCategories = remember(allocations) { allocations.mapTo(HashSet()) { it.category }.size > 1 }
    val draft = remember(scope, date, day, allocations, hidden, existing?.id) {
        AbsenceDayOverride(scope, date, day, allocations, hidden, id = existing?.id ?: "$scope-$date")
    }
    val issue = remember(rawResponse, draft, lessons) {
        AbsenceOverrideProjection.project(
            rawResponse, listOf(draft), scope, lessonsByDate = if (lessons.isEmpty()) null else mapOf(date to lessons),
        ).reviewOverrides.firstOrNull()?.pauseReason
    }
    val canSave = hidden.isNotEmpty() && issue == null && !isSaving
    fun update(id: String, change: (AbsenceOverrideAllocation) -> AbsenceOverrideAllocation) {
        allocations = allocations.map { if (it.id == id) change(it) else it }
    }

    GradeySheet(onDismiss = { if (!isSaving) onDismiss() }, modifier = Modifier.testTag("absenceOverrideEditor")) {
        // Cancel · Edit absence · Save, like the iOS sheet toolbar.
        Row(Modifier.fillMaxWidth().heightIn(min = GradeyMetrics.minTouch), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                PlainLink(stringResource(R.string.absence_cancel_77dfd), onDismiss, enabled = !isSaving, showsArrow = false)
            }
            Text(
                stringResource(R.string.absence_override_edit_title), Modifier.weight(1.4f).semantics { heading() },
                style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
            )
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (isSaving) {
                    CircularProgressIndicator(Modifier.padding(end = GradeySpacing.sm).size(20.dp), color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
                } else {
                    PlainLink(
                        stringResource(R.string.absence_save), { onSave(draft.copy(savedAtEpochMillis = System.currentTimeMillis())) },
                        Modifier.testTag("absenceOverrideSaveButton"), enabled = canSave, showsArrow = false,
                    )
                }
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(AbsenceItemSpacing)) {
            item(key = "intro", contentType = "intro") {
                Column(Modifier.padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(dayTitle, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
                    Text(stringResource(R.string.absence_override_remove_hint), style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
                    Text(stringResource(R.string.absence_override_local_only), style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel)
                }
            }
            val savedReason = existing?.pauseReason
            if (savedReason != null) {
                item(key = "review", contentType = "review") {
                    Column(Modifier.padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                        NoticeLabel(stringResource(R.string.absence_override_needs_review), HugeIcons.Alert02, GradeyColors.Average)
                        Text(savedReason.localizedDescription(), style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
                    }
                }
            }
            item(key = "lessonsTitle", contentType = "title") {
                Column(Modifier.padding(top = GradeySpacing.sm)) {
                    PlainSectionTitle(stringResource(R.string.absence_override_lessons_title))
                    if (mixedCategories) SectionFooter(stringResource(R.string.absence_override_mixed_categories))
                }
            }
            itemsIndexed(allocations, key = { _, allocation -> allocation.id }, contentType = { _, _ -> "unit" }) { _, allocation ->
                val category = AbsenceCategory.of(allocation.category)
                val lesson = allocation.lessonID?.let(lessonsByID::get)
                AllocationRow(
                    allocation = allocation,
                    isHidden = allocation.id in hidden,
                    hour = lesson?.lesson?.hour?.caption ?: "–",
                    timeRange = lesson?.lesson?.timeRange,
                    categoryText = "${category.symbol} ${stringResource(category.shortLabel)}",
                    categoryColor = palette.color(category),
                    names = names,
                    lessonLabels = choiceLabels,
                    enabled = !isSaving,
                    onSubjectText = { value -> update(allocation.id) { it.copy(subjectName = value, subjectID = null, lessonID = null) } },
                    onSubject = { index -> update(allocation.id) { it.copy(subjectName = names[index], subjectID = null, lessonID = null, lessonTitle = "") } },
                    onLesson = { index ->
                        val choice = choices[index]
                        update(allocation.id) {
                            it.copy(
                                lessonID = choice.lesson.id, subjectID = choice.lesson.subjectID,
                                subjectName = choice.lesson.subjectName ?: choice.lesson.title, lessonTitle = choice.title,
                            )
                        }
                    },
                    onToggle = { hidden = if (allocation.id in hidden) hidden - allocation.id else hidden + allocation.id },
                    modifier = Modifier.joinedListRow(),
                )
            }
            item(key = "summary", contentType = "summary") {
                Column(Modifier.padding(top = GradeySpacing.sm), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs).testTag("absenceOverridePreview").semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
                    ) {
                        Text(
                            stringResource(R.string.absence_override_result_count), Modifier.weight(1f),
                            style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            "${allocations.size} → ${allocations.size - hidden.size}",
                            style = GradeyType.headline.tabular(), color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Column(Modifier.padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                        if (rawResponse.absencesPerSubject.isEmpty()) Text(
                            stringResource(R.string.absence_subject_percentages_remain_unavailable_because_the_school_does_7a94d),
                            style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel,
                        )
                        if (hidden.isNotEmpty() && issue != null) NoticeLabel(issue.localizedDescription(), HugeIcons.Alert02, GradeyColors.Poor, bold = false)
                        errorMessage?.let { NoticeLabel(it, HugeIcons.Alert02, GradeyColors.Poor, Modifier.testTag("absenceOverrideError"), bold = false) }
                    }
                }
            }
            item(key = "actions", contentType = "actions") {
                Column {
                    PlainLink(
                        stringResource(R.string.absence_override_remove_day), { hidden = allocations.mapTo(HashSet()) { it.id } },
                        Modifier.testTag("absenceOverrideHideDay"), enabled = !isSaving && hidden.size < allocations.size, showsArrow = false,
                    )
                    PlainLink(
                        stringResource(R.string.absence_override_put_all_back), { hidden = emptySet() },
                        enabled = !isSaving && hidden.isNotEmpty(), showsArrow = false,
                    )
                }
            }
        }
    }
}

/**
 * One recorded unit: lesson number, subject (struck through once removed), time and category, and
 * Remove or Put back on the right (iOS `allocationRow`).
 */
@Composable
private fun AllocationRow(
    allocation: AbsenceOverrideAllocation,
    isHidden: Boolean,
    hour: String,
    timeRange: String?,
    categoryText: String,
    categoryColor: androidx.compose.ui.graphics.Color,
    names: List<String>,
    lessonLabels: List<String>,
    enabled: Boolean,
    onSubjectText: (String) -> Unit,
    onSubject: (Int) -> Unit,
    onLesson: (Int) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    PlainRow(modifier, verticalAlignment = Alignment.Top) {
        Text(
            hour, Modifier.widthIn(min = HourColumnWidth).padding(top = GradeySpacing.xs),
            style = GradeyType.headline.tabular(), color = secondary, maxLines = 1,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            when {
                names.isEmpty() -> BrandTextField(
                    value = allocation.subjectName, onValueChange = onSubjectText,
                    modifier = Modifier.fillMaxWidth().testTag("absenceOverrideSubject-${allocation.id}"),
                    placeholder = stringResource(R.string.absence_override_subject), enabled = enabled,
                )
                allocation.lessonID != null && allocation.subjectName.isNotBlank() -> Text(
                    allocation.subjectName, Modifier.padding(top = GradeySpacing.xs),
                    style = GradeyType.subheadlineSemibold.struck(isHidden),
                    color = if (isHidden) secondary else MaterialTheme.colorScheme.onSurface,
                )
                else -> MenuPicker(
                    label = allocation.subjectName.ifBlank { stringResource(R.string.absence_override_choose_subject) },
                    options = names, enabled = enabled, onSelect = onSubject,
                    struck = isHidden, modifier = Modifier.testTag("absenceOverrideSubject-${allocation.id}"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                StatusChip(categoryText, color = categoryColor)
                if (!timeRange.isNullOrBlank()) Text(timeRange, style = GradeyType.caption.tabular(), color = secondary)
            }
            if (lessonLabels.isNotEmpty()) {
                MenuPicker(
                    label = allocation.lessonTitle.ifBlank { stringResource(R.string.absence_choose_original_timetable_lesson_0551f) },
                    options = lessonLabels, enabled = enabled, onSelect = onLesson, small = true,
                )
            }
            if (isHidden) Text(stringResource(R.string.absence_override_removed), style = GradeyType.caption, color = GradeyColors.Poor)
        }
        PlainLink(
            stringResource(if (isHidden) R.string.absence_override_put_back else R.string.absence_override_remove), onToggle,
            Modifier.testTag(if (isHidden) "absenceOverridePutBack-${allocation.id}" else "absenceOverrideRemove-${allocation.id}"),
            enabled = enabled, color = if (isHidden) MaterialTheme.colorScheme.primary else GradeyColors.Poor, showsArrow = false,
        )
    }
}

/**
 * The current choice in brand green with an up-down chevron; tapping opens the choices
 * (iOS `Picker` in `.menu` style).
 */
@Composable
private fun MenuPicker(
    label: String,
    options: List<String>,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    small: Boolean = false,
    struck: Boolean = false,
) {
    var expanded by remember { mutableStateOf(false) }
    val tint = MaterialTheme.colorScheme.primary.copy(alpha = if (enabled) 1f else 0.45f)
    Box(modifier) {
        Row(
            Modifier.heightIn(min = GradeyMetrics.minTouch).clip(RoundedCornerShape(GradeyRadius.xs))
                .clickable(enabled = enabled, role = Role.DropdownList) { expanded = true },
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
        ) {
            Text(label, Modifier.weight(1f, fill = false), style = (if (small) GradeyType.caption else GradeyType.subheadlineSemibold).struck(struck), color = tint)
            GradeyIcon(HugeIcons.ArrowDataTransferVertical, size = 13.dp, tint = tint)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }, containerColor = MaterialTheme.gradeyColors.secondaryGroupedBackground) {
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
