package com.bukovinafilip.gradey.feature.absence

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PlannerDateFormat: DateTimeFormatter
    @Composable get() = DateTimeFormatter.ofPattern("EEE d MMM", LocalConfiguration.current.locales[0])
private val PlannerMuted: androidx.compose.ui.graphics.Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val PlannerTeal: androidx.compose.ui.graphics.Color
    @Composable get() = MaterialTheme.colorScheme.primary

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
    val today = LocalDate.now(ZoneId.of("Europe/Prague"))
    val weekStart = runCatching { LocalDate.parse(requestedWeek).let { TimetableDates.monday(it) } }.getOrNull()
        ?: runCatching { LocalDate.parse(timetable?.weekStart) }.getOrNull()
        ?: TimetableDates.monday(today)
    var selectedDate by rememberSaveable(weekStart.toString()) {
        mutableStateOf(maxOf(weekStart, today).coerceAtMost(weekStart.plusDays(6)).toString())
    }
    val lessons = AbsencePlanning.availableLessons(timetable, selectedDate)
    val savedPlan = plans.firstOrNull { it.date == selectedDate }
    var selectedIDs by rememberSaveable(selectedDate, savedPlan) {
        mutableStateOf(savedPlan?.lessons?.map { it.id }.orEmpty())
    }
    val selectedLessons = lessons.filter { it.id in selectedIDs }
    val draft = PlannedAbsence(selectedDate, selectedLessons)
    val previewPlans = if (lessons.isEmpty()) plans else AbsencePlanning.upsert(plans, draft)
    val summary = remember(response) { AbsenceRiskSummary.make(response, response.absencesPerSubject) }
    val timeline = remember(response) { AbsenceTimeline.make(response) }
    val prediction = AbsencePrediction.project(
        currentTotalCounts = timeline.total,
        subjectRows = summary.subjects,
        selectedLessons = AbsencePlanning.upcomingLessons(previewPlans),
        threshold = response.percentageThreshold,
    )
    val unchanged = selectedLessons.map { it.id }.toSet() == savedPlan?.lessons?.map { it.id }?.toSet()

    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.absence_back_b52b3))
                }
                Text(stringResource(R.string.absence_plan_absence_c429a), fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = stringResource(R.string.absence_refresh_planner_timetable_a9529))
                }
            }
        }
        item {
            Text(
                stringResource(R.string.absence_select_the_lessons_you_may_miss_to_preview_your_absence_risk_p_e4316),
                color = PlannerMuted,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        item {
            PlannerCard {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = { onWeekChange(weekStart.minusWeeks(1)) },
                        enabled = !isRefreshing && weekStart.isAfter(TimetableDates.monday(today)),
                    ) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(R.string.absence_previous_week_c4970))
                    }
                    Text(
                        "${weekStart.format(PlannerDateFormat)} – ${weekStart.plusDays(6).format(PlannerDateFormat)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = { onWeekChange(weekStart.plusWeeks(1)) }, enabled = !isRefreshing) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(R.string.absence_next_week_5a207))
                    }
                }
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items((0L..6L).map(weekStart::plusDays)) { date ->
                        FilterChip(
                            selected = selectedDate == date.toString(),
                            onClick = { selectedDate = date.toString() },
                            enabled = !date.isBefore(today) && !isRefreshing,
                            label = { Text(date.format(PlannerDateFormat)) },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                if (lessons.isEmpty()) {
                    Text(
                        when {
                            isRefreshing -> stringResource(R.string.absence_loading_lessons_1db40)
                            timetable == null -> stringResource(R.string.absence_refresh_to_load_the_timetable_before_planning_an_absence_d3daa)
                            timetable.days.none { it.date?.take(10) == selectedDate } -> stringResource(R.string.absence_no_timetable_is_available_for_this_date_try_another_day_or_ref_4c4e8)
                            else -> stringResource(R.string.absence_no_upcoming_lessons_on_this_date_canceled_lessons_are_excluded_11245)
                        },
                        color = PlannerMuted,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.absence_lessons_selected, selectedLessons.size, lessons.size), modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            selectedIDs = if (selectedLessons.size == lessons.size) emptyList() else lessons.map { it.id }
                        }) { Text(if (selectedLessons.size == lessons.size) stringResource(R.string.absence_clear_719ea) else stringResource(R.string.absence_select_all_913af)) }
                    }
                    lessons.forEach { lesson ->
                        PlannerLessonRow(lesson, lesson.id in selectedIDs) { checked ->
                            selectedIDs = if (checked) selectedIDs + lesson.id else selectedIDs - lesson.id
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onSavePlan(draft) },
                        enabled = selectedLessons.isNotEmpty() && !unchanged && !isRefreshing,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(if (unchanged) stringResource(R.string.absence_plan_saved_e502f) else if (savedPlan == null) stringResource(R.string.absence_save_plan_842dc) else stringResource(R.string.absence_update_plan_8e5d6))
                    }
                    if (savedPlan != null && selectedLessons.isEmpty()) {
                        Text(stringResource(R.string.absence_use_the_delete_button_below_to_remove_this_saved_plan_24ce7), color = PlannerMuted, fontSize = 12.sp)
                    }
                }
            }
        }
        item { PredictionCard(prediction, normalizedAbsenceThreshold(response.percentageThreshold)) }
        item {
            Text(stringResource(R.string.absence_saved_plans_e2882), color = PlannerMuted, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, fontSize = 13.sp)
        }
        if (plans.isEmpty()) {
            item { PlannerCard { Text(stringResource(R.string.absence_no_planned_absences_yet_d9b1c), color = PlannerMuted) } }
        } else {
            items(plans.sortedBy { it.date }, key = { it.date }) { plan ->
                PlannerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            val date = runCatching { LocalDate.parse(plan.date) }.getOrNull()
                            Text(date?.format(PlannerDateFormat) ?: plan.date, fontWeight = FontWeight.SemiBold)
                            val upcomingCount = AbsencePlanning.upcomingLessons(listOf(plan)).size
                            Text(
                                if (upcomingCount == 0) stringResource(R.string.absence_past_plan_excluded_from_prediction_896c0)
                                else stringResource(R.string.absence_planned_lesson_count, upcomingCount),
                                color = PlannerMuted,
                                fontSize = 13.sp,
                            )
                        }
                        IconButton(onClick = { onRemovePlan(plan.date) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = stringResource(R.string.absence_delete_plan_for, plan.date), tint = PlannerMuted)
                        }
                    }
                    Text(plan.lessons.map { it.subjectName }.distinct().joinToString(" · "), fontSize = 13.sp, color = PlannerMuted)
                }
            }
        }
    }
}

@Composable
private fun PlannerLessonRow(lesson: PlannedAbsenceLesson, selected: Boolean, onSelect: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().toggleable(value = selected, role = Role.Checkbox, onValueChange = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = selected, onCheckedChange = null)
        Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
            Text(lesson.subjectName, fontWeight = FontWeight.Medium)
            Text(lesson.timeRange.ifBlank { stringResource(R.string.absence_time_unavailable_ec422) }, color = PlannerMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PredictionCard(prediction: AbsencePredictionResult, threshold: Double?) {
    PlannerCard {
        Text(stringResource(R.string.absence_predicted_absence_f4765), color = PlannerTeal, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.7.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (prediction.hasSelection) stringResource(R.string.absence_all_plans_lesson_count, prediction.addedHours)
            else stringResource(R.string.absence_select_lessons_to_see_their_impact_5594f),
            fontWeight = FontWeight.SemiBold,
        )
        if (prediction.hasSelection) {
            Text(stringResource(R.string.absence_estimate_assumes_each_selected_lesson_adds_one_missed_and_one__6c504), color = PlannerMuted, fontSize = 12.sp)
            if (threshold == null) {
                Text(stringResource(R.string.absence_your_school_has_not_supplied_an_absence_limit_risk_warnings_ar_ebc04), color = PlannerMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            prediction.subjectRows.forEach { row ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.outlineVariant)
                Text(row.subjectName, fontWeight = FontWeight.SemiBold)
                val projected = row.projectedPercentage
                Text(
                    if (projected == null) stringResource(R.string.absence_unknown_baseline_count, row.addedHours)
                    else stringResource(R.string.absence_projected_percentage, percentage(row.currentPercentage ?: 0.0), percentage(projected), row.addedHours),
                    color = if (row.exceedsThreshold) MaterialTheme.colorScheme.error else PlannerTeal,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (row.exceedsThreshold && threshold != null) {
                    Text(
                        if (row.crossesThreshold) stringResource(R.string.absence_reaches_limit, percentage(threshold))
                        else stringResource(R.string.absence_remains_above_limit, percentage(threshold)),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlannerCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), shadowElevation = 1.dp) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun percentage(value: Double): String = String.format(LocalConfiguration.current.locales[0], "%.1f%%", value)
