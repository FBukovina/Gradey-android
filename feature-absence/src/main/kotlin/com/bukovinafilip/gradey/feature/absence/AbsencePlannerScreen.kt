package com.bukovinafilip.gradey.feature.absence

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
import androidx.compose.ui.graphics.Color
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
import java.util.Locale

private val PlannerDateFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val PlannerMuted = Color(0xFF72777A)
private val PlannerTeal = Color(0xFF0C967C)

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
        modifier = modifier.fillMaxSize().background(Color(0xFFF2F7F6)).statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Text("Plan absence", fontSize = 21.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                    if (isRefreshing) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.Refresh, contentDescription = "Refresh planner timetable")
                }
            }
        }
        item {
            Text(
                "Select the lessons you may miss to preview your absence risk. Plans stay on this device and are never sent to your school.",
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
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous week")
                    }
                    Text(
                        "${weekStart.format(PlannerDateFormat)} – ${weekStart.plusDays(6).format(PlannerDateFormat)}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    IconButton(onClick = { onWeekChange(weekStart.plusWeeks(1)) }, enabled = !isRefreshing) {
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next week")
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
                            isRefreshing -> "Loading lessons…"
                            timetable == null -> "Refresh to load the timetable before planning an absence."
                            timetable.days.none { it.date?.take(10) == selectedDate } -> "No timetable is available for this date. Try another day or refresh."
                            else -> "No upcoming lessons on this date. Canceled lessons are excluded."
                        },
                        color = PlannerMuted,
                        modifier = Modifier.padding(vertical = 16.dp),
                    )
                } else {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("${selectedLessons.size} of ${lessons.size} selected", modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        TextButton(onClick = {
                            selectedIDs = if (selectedLessons.size == lessons.size) emptyList() else lessons.map { it.id }
                        }) { Text(if (selectedLessons.size == lessons.size) "Clear" else "Select all") }
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
                        Text(if (unchanged) "Plan saved" else if (savedPlan == null) "Save plan" else "Update plan")
                    }
                    if (savedPlan != null && selectedLessons.isEmpty()) {
                        Text("Use the delete button below to remove this saved plan.", color = PlannerMuted, fontSize = 12.sp)
                    }
                }
            }
        }
        item { PredictionCard(prediction, normalizedAbsenceThreshold(response.percentageThreshold)) }
        item {
            Text("SAVED PLANS", color = PlannerMuted, fontWeight = FontWeight.Bold, letterSpacing = 0.8.sp, fontSize = 13.sp)
        }
        if (plans.isEmpty()) {
            item { PlannerCard { Text("No planned absences yet.", color = PlannerMuted) } }
        } else {
            items(plans.sortedBy { it.date }, key = { it.date }) { plan ->
                PlannerCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            val date = runCatching { LocalDate.parse(plan.date) }.getOrNull()
                            Text(date?.format(PlannerDateFormat) ?: plan.date, fontWeight = FontWeight.SemiBold)
                            val upcomingCount = AbsencePlanning.upcomingLessons(listOf(plan)).size
                            Text(
                                if (upcomingCount == 0) "Past plan · excluded from prediction"
                                else "$upcomingCount planned ${if (upcomingCount == 1) "lesson" else "lessons"}",
                                color = PlannerMuted,
                                fontSize = 13.sp,
                            )
                        }
                        IconButton(onClick = { onRemovePlan(plan.date) }) {
                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete plan for ${plan.date}", tint = PlannerMuted)
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
            Text(lesson.timeRange.ifBlank { "Time unavailable" }, color = PlannerMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PredictionCard(prediction: AbsencePredictionResult, threshold: Double?) {
    PlannerCard {
        Text("PREDICTED ABSENCE", color = PlannerTeal, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.7.sp)
        Spacer(Modifier.height(8.dp))
        Text(
            if (prediction.hasSelection) "${prediction.addedHours} planned ${if (prediction.addedHours == 1) "lesson" else "lessons"} across all plans"
            else "Select lessons to see their impact.",
            fontWeight = FontWeight.SemiBold,
        )
        if (prediction.hasSelection) {
            Text("Estimate assumes each selected lesson adds one missed and one taught lesson. It excludes other future lessons.", color = PlannerMuted, fontSize = 12.sp)
            if (threshold == null) {
                Text("Your school has not supplied an absence limit; risk warnings are unavailable.", color = PlannerMuted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            prediction.subjectRows.forEach { row ->
                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = Color(0xFFECEEEE))
                Text(row.subjectName, fontWeight = FontWeight.SemiBold)
                val projected = row.projectedPercentage
                Text(
                    if (projected == null) "+${row.addedHours} lessons · attendance baseline unavailable"
                    else "${percentage(row.currentPercentage ?: 0.0)} → ${percentage(projected)} · +${row.addedHours} ${if (row.addedHours == 1) "lesson" else "lessons"}",
                    color = if (row.exceedsThreshold) Color(0xFFBA3044) else PlannerTeal,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (row.exceedsThreshold && threshold != null) {
                    Text(
                        if (row.crossesThreshold) "This plan reaches the ${percentage(threshold)} limit."
                        else "Projected absence remains at or above the ${percentage(threshold)} limit.",
                        color = Color(0xFFBA3044),
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun PlannerCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(20.dp), shadowElevation = 1.dp) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

private fun percentage(value: Double): String = String.format(Locale.ENGLISH, "%.1f%%", value)
