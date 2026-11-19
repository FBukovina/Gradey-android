package com.bukovinafilip.gradey.feature.absence

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.AbsencePlanning
import com.bukovinafilip.gradey.domain.AbsencePrediction
import com.bukovinafilip.gradey.domain.AbsencePredictionResult
import com.bukovinafilip.gradey.domain.AbsencePredictionSubjectRow
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
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InfoRowContent
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.SectionFooter
import com.bukovinafilip.gradey.ui.SegmentedControl
import com.bukovinafilip.gradey.ui.StatusChip
import com.bukovinafilip.gradey.ui.ToolbarIconButton
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.tabular
import kotlinx.coroutines.launch
import java.time.LocalDate

/** The lesson number's column (iOS `AbsenceLessonToggleRow`, 24 pt). */
private val HourColumnWidth = 24.dp

/** A week with more than this many school days gets narrow weekday names in the day picker. */
private const val RoomyDayCount = 5

/** A saved plan as the Saved plans list shows it, prepared before composition. */
@Immutable
private data class PlanRowModel(val date: String, val title: String, val upcoming: Int, val subjects: String)

/**
 * Plan absence, pushed from the Absence tab: pick a day of the week and the lessons you may miss,
 * see what every saved plan does to your absence (iOS `AbsencePredictionSheet` and
 * `AbsencePredictorCard`), and keep the plan on this device. The shell draws the background and
 * the bottom navigation bar, so the list already ends above the system navigation bar.
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
    val formatters = remember(locale, today) { AbsenceFormatters(locale, today) }
    val currentMonday = remember(today) { TimetableDates.monday(today) }
    val weekStart = remember(requestedWeek, timetable?.weekStart, currentMonday) {
        (parseDate(requestedWeek) ?: parseDate(timetable?.weekStart))?.let(TimetableDates::monday) ?: currentMonday
    }
    val days = remember(weekStart, timetable, plans) { plannerDays(weekStart, timetable, plans) }
    val dayLabels = remember(days, formatters) { days.map { formatters.segmentTitle(it, compact = days.size > RoomyDayCount) } }

    // A saved plan opened from another week becomes the selected day once that week has loaded.
    var focusDate by rememberSaveable { mutableStateOf<String?>(null) }
    var chosenDate by rememberSaveable(weekStart.toString()) {
        mutableStateOf(focusDate?.takeIf { parseDate(it)?.let(TimetableDates::monday) == weekStart })
    }
    val date = remember(chosenDate, days, today) {
        chosenDate?.takeIf { chosen -> days.any { it.toString() == chosen } } ?: initialPlannerDate(days, today).toString()
    }
    val selectedDay = remember(date) { parseDate(date) ?: today }

    val lessons = remember(timetable, date) { AbsencePlanning.availableLessons(timetable, date) }
    val hourCaptions = remember(timetable, date) {
        timetable?.days.orEmpty().filter { it.date?.take(10) == date }.flatMap { it.lessons }.associate { "$date|${it.id}" to it.hour.caption }
    }
    val savedPlan = remember(plans, date) { plans.firstOrNull { it.date == date } }
    var selectedIDs by rememberSaveable(date, savedPlan) { mutableStateOf(savedPlan?.lessons?.map { it.id }.orEmpty()) }
    val selectedSet = remember(selectedIDs) { selectedIDs.toHashSet() }
    val selectedLessons = remember(lessons, selectedSet) { lessons.filter { it.id in selectedSet } }
    val draft = remember(date, selectedLessons) { PlannedAbsence(date, selectedLessons) }
    val unchanged = remember(selectedLessons, savedPlan) { selectedLessons.mapTo(HashSet()) { it.id } == savedPlan?.lessons?.mapTo(HashSet()) { it.id } }

    val subjects = remember(response) { AbsenceRiskSummary.make(response, response.absencesPerSubject).subjects }
    val currentTotal = remember(response) { AbsenceTimeline.make(response).total }
    val threshold = remember(response.percentageThreshold) { normalizedAbsenceThreshold(response.percentageThreshold) }
    val noLessons = lessons.isEmpty()
    val prediction = remember(plans, draft, noLessons, subjects, currentTotal, response.percentageThreshold) {
        AbsencePrediction.project(
            currentTotalCounts = currentTotal,
            subjectRows = subjects,
            selectedLessons = AbsencePlanning.upcomingLessons(if (noLessons) plans else AbsencePlanning.upsert(plans, draft)),
            threshold = response.percentageThreshold,
        )
    }
    val planRows = remember(plans, formatters) {
        plans.sortedBy { it.date }.map { plan ->
            PlanRowModel(
                date = plan.date,
                title = parseDate(plan.date)?.let(formatters::dayTitle) ?: plan.date,
                upcoming = AbsencePlanning.upcomingLessons(listOf(plan)).size,
                subjects = plan.lessons.map { it.subjectName }.distinct().joinToString(" · "),
            )
        }
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrolled by remember(listState) { derivedStateOf { listState.isScrolled } }
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val openPlan: (String) -> Unit = remember(weekStart, onWeekChange) {
        { planDate ->
            val planDay = parseDate(planDate)
            if (planDay != null && TimetableDates.monday(planDay) != weekStart) {
                focusDate = planDate
                onWeekChange(planDay)
            } else {
                chosenDate = planDate
            }
            scope.launch { listState.animateScrollToItem(0) }
        }
    }

    Column(modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.absence_plan_absence_c429a), scrolled = scrolled,
            onBack = onClose, backDescription = stringResource(R.string.absence_back_b52b3),
            onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = stringResource(R.string.absence_refresh_planner_timetable_a9529),
        )
        GradeyLazyColumn(
            Modifier.weight(1f).testTag("absencePlanner"),
            state = listState,
            maxWidth = GradeyMetrics.narrowContentMaxWidth,
            topPadding = GradeySpacing.md,
            itemSpacing = AbsenceItemSpacing,
        ) {
            item(key = "intro", contentType = "intro") {
                Text(
                    stringResource(R.string.absence_select_the_lessons_you_may_miss_to_preview_your_absence_risk_p_e4316),
                    Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.subheadline, color = secondary,
                )
            }
            item(key = "week", contentType = "week") {
                WeekPicker(
                    range = formatters.range(weekStart, days.lastOrNull() ?: weekStart.plusDays(4)),
                    labels = dayLabels,
                    selectedIndex = days.indexOfFirst { it.toString() == date }.coerceAtLeast(0),
                    canGoBack = !isRefreshing && weekStart.isAfter(currentMonday),
                    canGoForward = !isRefreshing,
                    onPrevious = { onWeekChange(weekStart.minusWeeks(1)) },
                    onNext = { onWeekChange(weekStart.plusWeeks(1)) },
                    onSelect = { chosenDate = days[it].toString() },
                )
            }
            if (noLessons) {
                item(key = "noLessons", contentType = "state") {
                    EmptyAbsenceRow(
                        stringResource(
                            when {
                                isRefreshing -> R.string.absence_loading_lessons_1db40
                                timetable == null -> R.string.absence_refresh_to_load_the_timetable_before_planning_an_absence_d3daa
                                timetable.days.none { it.date?.take(10) == date } -> R.string.absence_no_timetable_is_available_for_this_date_try_another_day_or_ref_4c4e8
                                else -> R.string.absence_no_upcoming_lessons_on_this_date_canceled_lessons_are_excluded_11245
                            },
                        ),
                        Modifier.testTag("absencePredictorNoLessons"),
                    )
                }
            } else {
                item(key = "lessonsTitle", contentType = "title") {
                    val all = selectedLessons.size == lessons.size
                    Column(Modifier.padding(top = GradeySpacing.sm)) {
                        PlainSectionTitle(formatters.longDayTitle(selectedDay)) {
                            PlainLink(
                                stringResource(if (all) R.string.absence_clear_719ea else R.string.absence_select_all_913af),
                                { selectedIDs = if (all) emptyList() else lessons.map { it.id } }, showsArrow = false,
                            )
                        }
                        SectionFooter(stringResource(R.string.absence_lessons_selected, selectedLessons.size, lessons.size))
                    }
                }
                items(lessons, key = { "lesson-${it.id}" }, contentType = { "lesson" }) { lesson ->
                    val selected = lesson.id in selectedSet
                    LessonToggleRow(
                        lesson = lesson, hour = hourCaptions[lesson.id] ?: "–", selected = selected,
                        onToggle = { checked -> selectedIDs = if (checked) selectedIDs + lesson.id else selectedIDs - lesson.id },
                        modifier = Modifier.joinedListRow().testTag("absencePredictorLesson-${lesson.id}"),
                    )
                }
                item(key = "save", contentType = "action") {
                    Column(Modifier.padding(top = GradeySpacing.sm), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                        GradeyPrimaryButton(
                            stringResource(
                                when {
                                    unchanged -> R.string.absence_plan_saved_e502f
                                    savedPlan == null -> R.string.absence_save_plan_842dc
                                    else -> R.string.absence_update_plan_8e5d6
                                },
                            ),
                            { onSavePlan(draft) },
                            enabled = selectedLessons.isNotEmpty() && !unchanged && !isRefreshing,
                        )
                        if (savedPlan != null && selectedLessons.isEmpty()) {
                            SectionFooter(stringResource(R.string.absence_use_the_delete_button_below_to_remove_this_saved_plan_24ce7))
                        }
                    }
                }
            }
            predictionItems(prediction, threshold, formatters)
            item(key = "plansTitle", contentType = "title") {
                PlainSectionTitle(stringResource(R.string.absence_saved_plans_e2882), Modifier.padding(top = GradeySpacing.md))
            }
            if (planRows.isEmpty()) {
                item(key = "noPlans", contentType = "state") { EmptyAbsenceRow(stringResource(R.string.absence_no_planned_absences_yet_d9b1c)) }
            } else {
                items(planRows, key = { "plan-${it.date}" }, contentType = { "plan" }) { plan ->
                    SavedPlanRow(plan, onOpen = openPlan, onRemove = onRemovePlan, modifier = Modifier.joinedListRow())
                }
            }
        }
    }
}

/** Previous and next week around the dates, then the school days as segments. */
@Composable
private fun WeekPicker(
    range: String,
    labels: List<String>,
    selectedIndex: Int,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ToolbarIconButton(HugeIcons.ArrowLeft01, stringResource(R.string.absence_previous_week_c4970), onPrevious, enabled = canGoBack)
            Text(
                range, Modifier.weight(1f),
                style = GradeyType.subheadlineBold.tabular(), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center,
            )
            ToolbarIconButton(HugeIcons.ArrowRight01, stringResource(R.string.absence_next_week_5a207), onNext, enabled = canGoForward)
        }
        if (labels.isNotEmpty()) {
            SegmentedControl(labels, selectedIndex, onSelect, Modifier.fillMaxWidth().testTag("absencePlannerDays"))
        }
    }
}

/** Lesson number, subject and time, and a circle that fills when selected (iOS `AbsenceLessonToggleRow`). */
@Composable
private fun LessonToggleRow(
    lesson: PlannedAbsenceLesson,
    hour: String,
    selected: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    PlainRow(modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = onToggle)) {
        Text(
            hour, Modifier.widthIn(min = HourColumnWidth),
            style = GradeyType.headline.tabular(), color = if (selected) primary else secondary, maxLines = 1,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(lesson.subjectName, style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface)
            if (lesson.timeRange.isNotBlank()) Text(lesson.timeRange, style = GradeyType.caption.tabular(), color = secondary)
        }
        GradeyIcon(
            if (selected) HugeIcons.CheckmarkCircle02 else HugeIcons.Circle, size = 20.dp,
            tint = if (selected) primary else MaterialTheme.gradeyColors.tertiaryLabel,
        )
    }
}

/**
 * Absence estimate for every saved plan plus the day being edited (iOS `AbsencePredictorCard`):
 * total hours before and after, then the change for each subject.
 */
private fun androidx.compose.foundation.lazy.LazyListScope.predictionItems(
    prediction: AbsencePredictionResult,
    threshold: Double?,
    formatters: AbsenceFormatters,
) {
    item(key = "estimateTitle", contentType = "title") {
        PlainSectionTitle(stringResource(R.string.absence_predictor_title), Modifier.padding(top = GradeySpacing.md))
    }
    if (!prediction.hasSelection) {
        item(key = "estimateEmpty", contentType = "state") {
            Text(
                stringResource(R.string.absence_select_lessons_to_see_their_impact_5594f),
                Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel,
            )
        }
        return
    }
    item(key = "estimateTotal", contentType = "estimateTotal") {
        val added = stringResource(R.string.absence_predictor_added_hours, prediction.addedHours)
        PlainRow(Modifier.testTag("absencePredictionTotal"), showDivider = false) {
            InfoRowContent(
                icon = HugeIcons.TimeSchedule,
                title = stringResource(R.string.absence_predictor_total),
                subtitle = stringResource(R.string.absence_predictor_ok_added) + " " + added,
            )
            Text(
                "${prediction.currentTotal.total} → ${prediction.projectedTotal.total}",
                style = GradeyType.headline.tabular(), color = MaterialTheme.colorScheme.primary, maxLines = 1,
            )
        }
    }
    items(prediction.subjectRows, key = { "prediction-${it.id}" }, contentType = { "prediction" }) { row ->
        PredictionSubjectRow(row, formatters, Modifier.joinedListRow().testTag("absencePredictionRow-${row.id}"))
    }
    item(key = "estimateNote", contentType = "note") {
        Column {
            SectionFooter(stringResource(R.string.absence_estimate_assumes_each_selected_lesson_adds_one_missed_and_one__6c504))
            if (threshold == null) SectionFooter(stringResource(R.string.absence_your_school_has_not_supplied_an_absence_limit_risk_warnings_ar_ebc04))
        }
    }
}

/** Subject, "Absent 3 → 5 · 4.0 % → 6.0 %" and "+2 h" (iOS `AbsencePredictorCard.subjectRow`). */
@Composable
private fun PredictionSubjectRow(row: AbsencePredictionSubjectRow, formatters: AbsenceFormatters, modifier: Modifier = Modifier) {
    val currentBase = row.currentBase
    val projectedBase = row.projectedBase
    val currentPercentage = row.currentPercentage
    val projectedPercentage = row.projectedPercentage
    val detail = if (currentBase == null || projectedBase == null || currentPercentage == null || projectedPercentage == null) {
        stringResource(R.string.absence_predictor_baseline_unavailable)
    } else {
        stringResource(R.string.absence_predictor_absent_change, currentBase, projectedBase) + " · " +
            stringResource(R.string.absence_predictor_percent_change, formatters.oneDecimal(currentPercentage), formatters.oneDecimal(projectedPercentage))
    }
    val tint = if (row.exceedsThreshold) GradeyColors.Poor else MaterialTheme.gradeyColors.secondaryLabel
    PlainRow(modifier) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(
                row.subjectName, style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(detail, style = GradeyType.caption.tabular(), color = tint)
        }
        StatusChip(
            stringResource(R.string.absence_predictor_added_hours, row.addedHours),
            color = if (row.exceedsThreshold) GradeyColors.Poor else MaterialTheme.colorScheme.secondary,
        )
    }
}

/** A saved plan: its day, how many lessons still count, the subjects, and delete. Tapping opens it. */
@Composable
private fun SavedPlanRow(plan: PlanRowModel, onOpen: (String) -> Unit, onRemove: (String) -> Unit, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    PlainRow(modifier, onClick = { onOpen(plan.date) }, onClickLabel = stringResource(R.string.absence_predictor_edit)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(plan.title, style = GradeyType.subheadlineBold.tabular(), color = MaterialTheme.colorScheme.onSurface)
            Text(
                if (plan.upcoming == 0) stringResource(R.string.absence_past_plan_excluded_from_prediction_896c0)
                else stringResource(R.string.absence_planned_lesson_count, plan.upcoming),
                style = GradeyType.caption.tabular(), color = secondary,
            )
            if (plan.subjects.isNotEmpty()) Text(plan.subjects, style = GradeyType.caption, color = secondary)
        }
        // The spoken label keeps the stored date; instrumented tests find the button by it.
        ToolbarIconButton(HugeIcons.Delete02, stringResource(R.string.absence_delete_plan_for, plan.date), { onRemove(plan.date) }, tint = GradeyColors.Poor)
    }
}
