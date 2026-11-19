package com.bukovinafilip.gradey.feature.absence

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.AbsencePlanning
import com.bukovinafilip.gradey.domain.AbsencePrediction
import com.bukovinafilip.gradey.domain.AbsencePredictionResult
import com.bukovinafilip.gradey.domain.AbsenceRiskSummary
import com.bukovinafilip.gradey.domain.AbsenceTimeline
import com.bukovinafilip.gradey.domain.TimetableDates
import com.bukovinafilip.gradey.domain.normalizedAbsenceThreshold
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.PlannedAbsence
import com.bukovinafilip.gradey.model.PlannedAbsenceLesson
import com.bukovinafilip.gradey.model.TimetableWeek
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.ToolbarIconButton
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.tabular
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Plan absence, pushed from the Absence tab: pick a day and the lessons you may miss, see what that
 * does to each subject's absence, and keep the plan. The shell draws the background and the bottom bar.
 */
@Composable
fun AbsencePlannerScreen(
    response: AbsenceResponse,
    timetable: TimetableWeek?,
    plans: List<PlannedAbsence>,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
    onWeekChange: (LocalDate) -> Unit,
    onSavePlan: (PlannedAbsence) -> Unit,
    onRemovePlan: (String) -> Unit,
    modifier: Modifier = Modifier,
    requestedWeek: String? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    val today = remember { LocalDate.now(SchoolZone) }
    val dateFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d MMM", locale) }
    val dayFormat = remember(locale) { DateTimeFormatter.ofPattern("EEE d", locale) }
    val weekStart = remember(requestedWeek, timetable?.weekStart) {
        runCatching { LocalDate.parse(requestedWeek).let { TimetableDates.monday(it) } }.getOrNull()
            ?: runCatching { LocalDate.parse(timetable?.weekStart) }.getOrNull()
            ?: TimetableDates.monday(today)
    }
    var selectedDate by rememberSaveable(weekStart.toString()) {
        mutableStateOf(maxOf(weekStart, today).coerceAtMost(weekStart.plusDays(6)).toString())
    }
    val lessons = remember(timetable, selectedDate) { AbsencePlanning.availableLessons(timetable, selectedDate) }
    val savedPlan = plans.firstOrNull { it.date == selectedDate }
    var selectedIDs by rememberSaveable(selectedDate, savedPlan) { mutableStateOf(savedPlan?.lessons?.map { it.id }.orEmpty()) }
    val selectedLessons = lessons.filter { it.id in selectedIDs }
    val draft = PlannedAbsence(selectedDate, selectedLessons)
    val summary = remember(response) { AbsenceRiskSummary.make(response, response.absencesPerSubject) }
    val timeline = remember(response) { AbsenceTimeline.make(response) }
    val prediction = remember(plans, draft, lessons.isEmpty(), summary, timeline, response.percentageThreshold) {
        AbsencePrediction.project(
            currentTotalCounts = timeline.total,
            subjectRows = summary.subjects,
            selectedLessons = AbsencePlanning.upcomingLessons(if (lessons.isEmpty()) plans else AbsencePlanning.upsert(plans, draft)),
            threshold = response.percentageThreshold,
        )
    }
    val unchanged = selectedLessons.map { it.id }.toSet() == savedPlan?.lessons?.map { it.id }?.toSet()
    val sortedPlans = remember(plans) { plans.sortedBy { it.date } }
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    val secondary = MaterialTheme.gradeyColors.secondaryLabel

    Column(modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.absence_plan_absence_c429a), scrolled = scrolled,
            onBack = onClose, backDescription = stringResource(R.string.absence_back_b52b3),
            onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = stringResource(R.string.absence_refresh_planner_timetable_a9529),
        )
        GradeyLazyColumn(Modifier.weight(1f).testTag("absencePlanner"), state = listState, maxWidth = GradeyMetrics.narrowContentMaxWidth, itemSpacing = 0.dp) {
            item(key = "intro", contentType = "intro") {
                Text(
                    stringResource(R.string.absence_select_the_lessons_you_may_miss_to_preview_your_absence_risk_p_e4316),
                    Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.subheadline, color = secondary,
                )
            }
            item(key = "week", contentType = "week") {
                Column(Modifier.padding(top = GradeySpacing.lg), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        ToolbarIconButton(
                            HugeIcons.ArrowLeft01, stringResource(R.string.absence_previous_week_c4970), { onWeekChange(weekStart.minusWeeks(1)) },
                            enabled = !isRefreshing && weekStart.isAfter(TimetableDates.monday(today)),
                        )
                        Text(
                            "${weekStart.format(dateFormat)} – ${weekStart.plusDays(6).format(dateFormat)}", Modifier.weight(1f),
                            style = GradeyType.subheadlineBold.tabular(), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
                        )
                        ToolbarIconButton(HugeIcons.ArrowRight01, stringResource(R.string.absence_next_week_5a207), { onWeekChange(weekStart.plusWeeks(1)) }, enabled = !isRefreshing)
                    }
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                        for (offset in 0L..6L) {
                            val date = weekStart.plusDays(offset)
                            DayPill(
                                date.format(dayFormat).replaceFirstChar { it.titlecase(locale) }, selected = selectedDate == date.toString(),
                                enabled = !date.isBefore(today) && !isRefreshing, onClick = { selectedDate = date.toString() },
                            )
                        }
                    }
                }
            }
            if (lessons.isEmpty()) item(key = "noLessons", contentType = "state") {
                Text(
                    stringResource(
                        when {
                            isRefreshing -> R.string.absence_loading_lessons_1db40
                            timetable == null -> R.string.absence_refresh_to_load_the_timetable_before_planning_an_absence_d3daa
                            timetable.days.none { it.date?.take(10) == selectedDate } -> R.string.absence_no_timetable_is_available_for_this_date_try_another_day_or_ref_4c4e8
                            else -> R.string.absence_no_upcoming_lessons_on_this_date_canceled_lessons_are_excluded_11245
                        },
                    ),
                    Modifier.padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.xl), style = GradeyType.subheadline, color = secondary,
                )
            } else {
                item(key = "lessonsTitle", contentType = "title") {
                    Row(Modifier.fillMaxWidth().padding(top = GradeySpacing.lg, start = GradeySpacing.xs), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.absence_lessons_selected, selectedLessons.size, lessons.size), Modifier.weight(1f),
                            style = GradeyType.subheadlineBold.tabular(), color = MaterialTheme.colorScheme.onSurface,
                        )
                        val all = selectedLessons.size == lessons.size
                        PlainLink(
                            stringResource(if (all) R.string.absence_clear_719ea else R.string.absence_select_all_913af),
                            { selectedIDs = if (all) emptyList() else lessons.map { it.id } }, showsArrow = false,
                        )
                    }
                }
                items(lessons, key = { "lesson-${it.id}" }, contentType = { "lesson" }) { lesson ->
                    PlannerLessonRow(lesson, lesson.id in selectedIDs) { checked -> selectedIDs = if (checked) selectedIDs + lesson.id else selectedIDs - lesson.id }
                }
                item(key = "save", contentType = "action") {
                    Column(Modifier.padding(top = GradeySpacing.lg), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                        GradeyPrimaryButton(
                            stringResource(if (unchanged) R.string.absence_plan_saved_e502f else if (savedPlan == null) R.string.absence_save_plan_842dc else R.string.absence_update_plan_8e5d6),
                            { onSavePlan(draft) }, enabled = selectedLessons.isNotEmpty() && !unchanged && !isRefreshing,
                        )
                        if (savedPlan != null && selectedLessons.isEmpty()) Text(
                            stringResource(R.string.absence_use_the_delete_button_below_to_remove_this_saved_plan_24ce7),
                            Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary,
                        )
                    }
                }
            }
            item(key = "prediction", contentType = "prediction") { PredictionSection(prediction, normalizedAbsenceThreshold(response.percentageThreshold)) }
            item(key = "plansTitle", contentType = "title") {
                PlainSectionTitle(stringResource(R.string.absence_saved_plans_e2882), Modifier.padding(top = GradeySpacing.xl, bottom = GradeySpacing.sm))
            }
            if (sortedPlans.isEmpty()) item(key = "noPlans", contentType = "state") {
                Text(stringResource(R.string.absence_no_planned_absences_yet_d9b1c), Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.subheadline, color = secondary)
            } else items(sortedPlans, key = { "plan-${it.date}" }, contentType = { "plan" }) { plan ->
                val title = remember(plan.date, dateFormat) { runCatching { LocalDate.parse(plan.date) }.getOrNull()?.format(dateFormat) ?: plan.date }
                val upcoming = remember(plan) { AbsencePlanning.upcomingLessons(listOf(plan)).size }
                PlainRow {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = GradeyType.subheadlineBold.tabular(), color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (upcoming == 0) stringResource(R.string.absence_past_plan_excluded_from_prediction_896c0) else stringResource(R.string.absence_planned_lesson_count, upcoming),
                            style = GradeyType.caption.tabular(), color = secondary,
                        )
                        Text(plan.lessons.map { it.subjectName }.distinct().joinToString(" · "), style = GradeyType.caption, color = secondary)
                    }
                    ToolbarIconButton(HugeIcons.Delete02, stringResource(R.string.absence_delete_plan_for, title), { onRemovePlan(plan.date) }, tint = GradeyColors.Poor)
                }
            }
        }
    }
}

/** Day of the planned week: a capsule that fills with the primary tint when selected. */
@Composable
private fun DayPill(text: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tokens = MaterialTheme.gradeyColors
    Box(
        Modifier.heightIn(min = GradeyMetrics.minTouch).clip(CircleShape).background(if (selected) primary.copy(alpha = 0.14f) else tokens.tertiaryFill)
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick).semantics { this.selected = selected }
            .padding(horizontal = GradeySpacing.lg, vertical = GradeySpacing.sm),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = (if (selected) GradeyType.subheadlineBold else GradeyType.subheadlineMedium).tabular(), maxLines = 1,
            color = when { selected -> primary; enabled -> MaterialTheme.colorScheme.onSurface; else -> tokens.tertiaryLabel },
        )
    }
}

@Composable
private fun PlannerLessonRow(lesson: PlannedAbsenceLesson, selected: Boolean, onSelect: (Boolean) -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(GradeyRadius.xs)
    PlainRow(Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = onSelect)) {
        Box(
            Modifier.size(24.dp).then(if (selected) Modifier.background(primary, shape) else Modifier.border(1.5.dp, MaterialTheme.gradeyColors.tertiaryLabel, shape)),
            contentAlignment = Alignment.Center,
        ) { if (selected) GradeyIcon(HugeIcons.Tick02, size = 14.dp, tint = GradeyColors.OnAccent) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(lesson.subjectName, style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface)
            Text(
                lesson.timeRange.ifBlank { stringResource(R.string.absence_time_unavailable_ec422) },
                style = GradeyType.caption.tabular(), color = MaterialTheme.gradeyColors.secondaryLabel,
            )
        }
    }
}

/** What the saved and selected plans would do to each subject's absence. */
@Composable
private fun PredictionSection(prediction: AbsencePredictionResult, threshold: Double?) {
    val locale = LocalConfiguration.current.locales[0]
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Column(Modifier.fillMaxWidth().padding(top = GradeySpacing.xl), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        PlainSectionTitle(stringResource(R.string.absence_predicted_absence_f4765))
        Column(Modifier.padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(
                if (prediction.hasSelection) stringResource(R.string.absence_all_plans_lesson_count, prediction.addedHours)
                else stringResource(R.string.absence_select_lessons_to_see_their_impact_5594f),
                style = GradeyType.subheadlineSemibold.tabular(), color = MaterialTheme.colorScheme.onSurface,
            )
            if (prediction.hasSelection) {
                Text(stringResource(R.string.absence_estimate_assumes_each_selected_lesson_adds_one_missed_and_one__6c504), style = GradeyType.caption, color = secondary)
                if (threshold == null) Text(
                    stringResource(R.string.absence_your_school_has_not_supplied_an_absence_limit_risk_warnings_ar_ebc04), style = GradeyType.caption, color = secondary,
                )
            }
        }
        if (prediction.hasSelection) prediction.subjectRows.forEach { row ->
            androidx.compose.runtime.key(row.subjectName) {
                val projected = row.projectedPercentage
                PlainRow(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(row.subjectName, style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            if (projected == null) stringResource(R.string.absence_unknown_baseline_count, row.addedHours)
                            else stringResource(R.string.absence_projected_percentage, percentage(row.currentPercentage ?: 0.0, locale), percentage(projected, locale), row.addedHours),
                            style = GradeyType.caption.tabular(), color = if (row.exceedsThreshold) GradeyColors.Poor else MaterialTheme.colorScheme.primary,
                        )
                        if (row.exceedsThreshold && threshold != null) Text(
                            stringResource(if (row.crossesThreshold) R.string.absence_reaches_limit else R.string.absence_remains_above_limit, percentage(threshold, locale)),
                            style = GradeyType.caption.tabular(), color = GradeyColors.Poor,
                        )
                    }
                }
            }
        }
    }
}

private fun percentage(value: Double, locale: Locale): String = String.format(locale, "%.1f%%", value)
