package com.bukovinafilip.gradey.feature.absence

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.AbsenceMonthSummary
import com.bukovinafilip.gradey.domain.AbsenceOverrideProjection
import com.bukovinafilip.gradey.domain.AbsenceRiskSummary
import com.bukovinafilip.gradey.domain.AbsenceSubjectSummary
import com.bukovinafilip.gradey.domain.AbsenceTimeline
import com.bukovinafilip.gradey.domain.normalizedAbsenceThreshold
import com.bukovinafilip.gradey.model.AbsenceCounts
import com.bukovinafilip.gradey.model.AbsenceDayOverride
import com.bukovinafilip.gradey.model.AbsenceOverridePauseReason
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.TimetableWeek
import com.bukovinafilip.gradey.ui.DestructiveLink
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyDialog
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.PlainHeader
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.RiskCapsuleBar
import com.bukovinafilip.gradey.ui.SectionFooter
import com.bukovinafilip.gradey.ui.SegmentedControl
import com.bukovinafilip.gradey.ui.StatusChip
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.riskColor
import com.bukovinafilip.gradey.ui.tabular
import java.time.LocalDate

private enum class AbsenceMode(val label: Int) {
    Subjects(R.string.absence_subjects_27894),
    Days(R.string.absence_segment_days),
    Months(R.string.absence_segment_months),
}

/**
 * Space between the parts of the screen (iOS `LazyVStack(spacing: Spacing.md)`). The list itself
 * has no item spacing because the Subjects, Days and Months rows are one lazy item each and, as on
 * iOS, touch each other with only a hairline between them.
 */
private val SectionGap = GradeySpacing.md

/** At most four category chips per row, then "+n" (iOS `categoryChips`). */
private const val MaxChips = 4

/** Width of the trailing hour total so totals line up (iOS `minWidth: 28`). */
private val TotalColumnWidth = 28.dp

/**
 * The Absence tab (iOS `AbsenceView`): total hours, the Plan absence entry, then the absence by
 * subject, by day or by month. Local attendance corrections open from the Days rows and from the
 * corrections section at the end.
 */
@Composable
fun AbsenceScreen(
    response: AbsenceResponse,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenAI: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onPlanAbsence: (() -> Unit)? = null,
    rawResponse: AbsenceResponse = response,
    overrideScope: String? = null,
    overrides: List<AbsenceDayOverride> = emptyList(),
    availableSubjects: List<Subject> = emptyList(),
    correctionTimetable: TimetableWeek? = null,
    correctionError: String? = null,
    onSaveOverride: ((AbsenceDayOverride) -> Unit)? = null,
    onRestoreOverride: ((String) -> Unit)? = null,
    onResetOverrides: (() -> Unit)? = null,
    onLoadCorrectionWeek: ((String) -> Unit)? = null,
) {
    var mode by rememberSaveable { mutableStateOf(AbsenceMode.Subjects) }
    val correctionsEnabled = overrideScope != null && onSaveOverride != null
    var editingDate by remember(overrideScope) { mutableStateOf<String?>(null) }
    var pendingSave by remember(overrideScope) { mutableStateOf<AbsenceDayOverride?>(null) }
    var confirmReset by remember { mutableStateOf(false) }
    LaunchedEffect(overrides, pendingSave) {
        val pending = pendingSave
        if (pending != null && overrides.any { it.id == pending.id && it.savedAtEpochMillis == pending.savedAtEpochMillis }) {
            pendingSave = null
            editingDate = null
        }
    }

    val locale = LocalConfiguration.current.locales[0]
    val formatters = remember(locale) { AbsenceFormatters(locale, LocalDate.now(SchoolZone)) }
    val palette = rememberAbsencePalette()
    val timeline = remember(response) { AbsenceTimeline.make(response) }
    val threshold = remember(response.percentageThreshold) { normalizedAbsenceThreshold(response.percentageThreshold) }
    val subjects = remember(response) { AbsenceRiskSummary.make(response, response.absencesPerSubject).subjects }
    val subjectKeys = remember(subjects) { uniqueKeys(subjects.map { "subject-${it.stableID}" }) }
    val dayRows = remember(response, rawResponse, formatters) {
        displayedAbsenceDays(response, rawResponse).map { day ->
            AbsenceCountRowModel("day-${day.date}", formatters.dayTitle(day.date), day.counts, day.date.toString())
        }
    }
    val monthRows = remember(timeline, formatters) {
        timeline.months.map { month -> AbsenceCountRowModel("month-${month.month}", formatters.monthTitle(month.month), month.counts, null) }
    }
    val projection = remember(rawResponse, overrides, overrideScope) {
        overrideScope?.let { AbsenceOverrideProjection.project(rawResponse, overrides, it) }
    }
    // Only days with a single school record can be corrected.
    val correctableDates = remember(rawResponse) {
        rawResponse.absences.groupBy { AbsenceOverrideProjection.dateKey(it.date) }.filterValues { it.size == 1 }.keys.sortedDescending()
    }
    val correctableSet = remember(correctableDates) { correctableDates.toHashSet() }
    val savedCorrections = remember(projection, formatters, correctableSet) {
        val saved = projection?.reconciledOverrides.orEmpty()
        val keys = uniqueKeys(saved.map { "correction-${it.id}" })
        saved.mapIndexed { index, entry ->
            SavedCorrectionModel(keys[index], entry.dateKey, formatters.dayTitle(entry.dateKey), entry.pauseReason, entry.hiddenAllocationIDs.size, entry.dateKey in correctableSet)
        }
    }

    val shortLabels = AbsenceCategory.entries.map { stringResource(it.shortLabel) }
    val thresholdCaption = threshold?.let { stringResource(R.string.absence_threshold_caption, it) }
    val summary = remember(timeline.total, shortLabels, thresholdCaption) { absenceSummaryLine(timeline.total, shortLabels, thresholdCaption) }

    val editDay: (String) -> Unit = remember(onLoadCorrectionWeek) {
        { date ->
            editingDate = date
            pendingSave = null
            onLoadCorrectionWeek?.invoke(date)
        }
    }

    val editingKey = editingDate
    val correctionDay = remember(rawResponse, editingKey) {
        editingKey?.let { key -> rawResponse.absences.singleOrNull { AbsenceOverrideProjection.dateKey(it.date) == key } }
    }
    if (correctionDay != null && editingKey != null && overrideScope != null && onSaveOverride != null) {
        AbsenceOverrideEditor(
            rawResponse = rawResponse, day = correctionDay, dayTitle = formatters.dayTitle(editingKey), scope = overrideScope,
            existing = overrides.firstOrNull { it.dateKey == editingKey }, availableSubjects = availableSubjects,
            lessons = correctionTimetable?.days?.firstOrNull { AbsenceOverrideProjection.dateKey(it.date.orEmpty()) == editingKey }?.lessons.orEmpty(),
            isSaving = isRefreshing, errorMessage = correctionError, palette = palette,
            onSave = { pendingSave = it; onSaveOverride(it) },
            onDismiss = { editingDate = null; pendingSave = null },
        )
    }

    val listState = rememberLazyListState()
    val scrolled by remember(listState) { derivedStateOf { listState.isScrolled } }
    Column(modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.absence_absence_f7eb4),
            scrolled = scrolled,
            onOpenAI = onOpenAI, aiDescription = stringResource(R.string.absence_gradey_ai),
            onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = stringResource(R.string.absence_refresh),
            onOpenSettings = onOpenSettings, settingsDescription = stringResource(R.string.absence_settings),
        )
        GradeyLazyColumn(
            modifier = Modifier.weight(1f).testTag("absenceList"),
            state = listState,
            topPadding = GradeySpacing.md,
            itemSpacing = 0.dp,
        ) {
            item(key = "header", contentType = "header") { AbsenceHeader(timeline.total.total, summary) }
            if (projection != null && projection.activeOverrides.isNotEmpty()) {
                item(key = "adjusted", contentType = "notice") {
                    NoticeLabel(
                        text = stringResource(R.string.absence_override_adjusted), icon = HugeIcons.ViewOff,
                        color = MaterialTheme.gradeyColors.secondaryLabel, modifier = Modifier.testTag("absenceLocallyAdjusted"),
                    )
                }
            }
            if (projection != null && projection.reviewOverrides.isNotEmpty()) {
                item(key = "review", contentType = "notice") {
                    NoticeLabel(
                        text = stringResource(R.string.absence_override_needs_review), icon = HugeIcons.Alert02,
                        color = GradeyColors.Average, modifier = Modifier.testTag("absenceOverridesNeedReview"),
                    )
                }
            }
            if (onPlanAbsence != null) {
                item(key = "plan", contentType = "plan") { PlanAbsenceRow(onPlanAbsence) }
            }
            item(key = "segments", contentType = "segments") {
                SegmentedControl(
                    options = AbsenceMode.entries.map { stringResource(it.label) },
                    selectedIndex = mode.ordinal,
                    onSelect = { mode = AbsenceMode.entries[it] },
                    modifier = Modifier.fillMaxWidth().padding(top = SectionGap).testTag("absenceSegmentedControl"),
                )
            }
            if (isRefreshing) item(key = "refreshing", contentType = "refreshing") { RefreshingRow() }
            when (mode) {
                AbsenceMode.Subjects -> subjectItems(subjects, subjectKeys, formatters)
                AbsenceMode.Days -> countItems(
                    prefix = "days", rows = dayRows, total = timeline.total, palette = palette,
                    emptyText = R.string.absence_days_empty,
                    onEditDay = if (correctionsEnabled) editDay else null, editableDates = correctableSet,
                )
                AbsenceMode.Months -> {
                    if (monthRows.size >= 2) {
                        item(key = "months-chart", contentType = "chart") {
                            AbsenceMonthsChartSection(timeline.months, palette, formatters)
                        }
                    }
                    countItems(
                        prefix = "months", rows = monthRows, total = timeline.total, palette = palette,
                        emptyText = R.string.absence_months_empty, onEditDay = null, editableDates = emptySet(),
                    )
                }
            }
            if (correctionsEnabled && projection != null) {
                correctionItems(
                    hiddenCount = projection.hiddenCount, dates = correctableDates, formatters = formatters,
                    saved = savedCorrections, isLoading = isRefreshing, onEdit = editDay, onRestore = onRestoreOverride,
                    onReset = if (overrides.isNotEmpty() && onResetOverrides != null) ({ confirmReset = true }) else null,
                )
            }
        }
    }

    if (confirmReset && onResetOverrides != null) {
        GradeyDialog(
            title = stringResource(R.string.absence_restore_school_records_bd7cd),
            text = stringResource(R.string.absence_remove_all_local_attendance_corrections_for_this_school_accoun_ce530),
            onDismiss = { confirmReset = false },
            confirmLabel = stringResource(R.string.absence_restore_all_9eca7),
            onConfirm = { confirmReset = false; onResetOverrides() },
            dismissLabel = stringResource(R.string.absence_cancel_77dfd),
            destructive = true,
        )
    }
}

// MARK: - Header and entries

/** "Total absence", 52 dp hours in Brand.primary and the category summary (iOS `AbsenceHeader`). */
@Composable
private fun AbsenceHeader(total: Int, summary: String) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    PlainHeader(
        label = stringResource(R.string.absence_header_total),
        value = total.toString(),
        valueColor = MaterialTheme.colorScheme.primary,
        valueSuffix = stringResource(R.string.absence_hours_5aede),
        modifier = Modifier.testTag("absenceHeader"),
        below = if (summary.isEmpty()) null else {
            { Text(summary, style = GradeyType.subheadline, color = secondary) }
        },
    )
}

@Composable
private fun NoticeLabel(text: String, icon: String, color: Color, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(top = SectionGap, start = GradeySpacing.xs, end = GradeySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
    ) {
        GradeyIcon(icon, size = 13.dp, tint = color)
        Text(text, style = GradeyType.captionSemibold, color = color)
    }
}

/** Calendar-clock icon, "Plan absence" and a chevron (iOS `AbsencePredictorCard` without a plan). */
@Composable
private fun PlanAbsenceRow(onPlanAbsence: () -> Unit) {
    PlainRow(
        modifier = Modifier.padding(top = SectionGap).testTag("absencePredictorOpenButton"),
        onClick = onPlanAbsence,
        showDivider = false,
    ) {
        GradeyIcon(HugeIcons.TimeSchedule, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(R.string.absence_plan_absence_c429a), Modifier.weight(1f),
            style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface,
        )
        DisclosureIcon()
    }
}

@Composable
private fun RefreshingRow() {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Row(
        Modifier.fillMaxWidth().padding(top = SectionGap, bottom = GradeySpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm, Alignment.CenterHorizontally),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), color = secondary, strokeWidth = 2.dp)
        Text(stringResource(R.string.absence_refreshing), style = GradeyType.subheadline, color = secondary)
    }
}

/** Icon and secondary text for an empty list (iOS `EmptyAbsenceSection`). */
@Composable
internal fun EmptyAbsenceRow(text: String, modifier: Modifier = Modifier) {
    PlainRow(modifier, showDivider = false) {
        GradeyIcon(HugeIcons.CalendarCheckIn02, size = 18.dp, tint = MaterialTheme.colorScheme.primary)
        Text(text, Modifier.weight(1f), style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
    }
}

// MARK: - Subjects

private fun LazyListScope.subjectItems(
    subjects: List<AbsenceSubjectSummary>,
    keys: List<String>,
    formatters: AbsenceFormatters,
) {
    if (subjects.isEmpty()) {
        item(key = "subjects-empty", contentType = "empty") {
            EmptyAbsenceRow(stringResource(R.string.absence_subjects_empty), Modifier.padding(top = SectionGap).testTag("absenceSubjectsEmpty"))
        }
        return
    }
    itemsIndexed(subjects, key = { index, _ -> keys[index] }, contentType = { _, _ -> "subjectRow" }) { index, subject ->
        SubjectRiskRow(
            subject,
            Modifier.then(if (index == 0) Modifier.padding(top = SectionGap) else Modifier).testTag("absenceRow-${keys[index]}"),
        )
    }
}

/**
 * Subject, "12.5 %" in the risk colour, the risk capsule and "3 of 40 lessons missed · status"
 * (iOS `SubjectAbsenceTable.riskRow`).
 */
@Composable
private fun SubjectRiskRow(subject: AbsenceSubjectSummary, modifier: Modifier = Modifier) {
    val riskColor = subject.level.riskColor()
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val status = riskStatus(subject)
    val missed = stringResource(R.string.absence_risk_missed, subject.base, subject.lessonsCount)
    PlainRow(modifier) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                Text(
                    subject.subjectName, Modifier.weight(1f).alignByBaseline(),
                    style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    stringResource(R.string.absence_percentage, formatOneDecimal(subject.absencePercentage)), Modifier.alignByBaseline(),
                    style = GradeyType.headline.tabular(), color = riskColor, maxLines = 1,
                )
            }
            RiskCapsuleBar(subject.absencePercentage, subject.threshold, subject.level)
            Text(
                buildAnnotatedString {
                    append(missed)
                    if (status != null) {
                        append(" · ")
                        withStyle(GradeyType.captionSemibold.toSpanStyle().copy(color = status.second)) { append(status.first) }
                    }
                },
                style = GradeyType.caption, color = secondary,
            )
        }
    }
}

@Composable
private fun riskStatus(subject: AbsenceSubjectSummary): Pair<String, Color>? {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    if (subject.threshold == null) return stringResource(R.string.absence_school_limit_unavailable_0c832) to secondary
    val misses = subject.missesUntilLimit ?: return null
    return if (misses == 0) stringResource(R.string.absence_risk_over_limit) to GradeyColors.Poor
    else stringResource(R.string.absence_risk_until_limit, misses) to secondary
}

// MARK: - Days and months

private fun LazyListScope.countItems(
    prefix: String,
    rows: List<AbsenceCountRowModel>,
    total: AbsenceCounts,
    palette: AbsencePalette,
    emptyText: Int,
    onEditDay: ((String) -> Unit)?,
    editableDates: Set<String>,
) {
    if (rows.isEmpty()) {
        item(key = "$prefix-empty", contentType = "empty") {
            EmptyAbsenceRow(stringResource(emptyText), Modifier.padding(top = SectionGap))
        }
        return
    }
    item(key = "$prefix-total", contentType = "countRow") {
        AbsenceCountRow(
            title = stringResource(R.string.absence_total_b2592), counts = total,
            categories = AbsenceCategory.nonZero(total), isTotal = true, palette = palette,
            modifier = Modifier.padding(top = SectionGap).testTag("absenceRow-total"),
        )
    }
    items(rows, key = { it.key }, contentType = { "countRow" }) { row ->
        val date = row.dateKey
        val editable = onEditDay != null && date != null && date in editableDates
        AbsenceCountRow(
            title = row.title, counts = row.counts, categories = row.categories, isTotal = false, palette = palette,
            modifier = Modifier.testTag("absenceRow-${row.key}"),
            onClick = if (editable) ({ onEditDay?.invoke(date!!) }) else null,
            onClickLabel = if (editable) stringResource(R.string.absence_override_edit_hint) else null,
        )
    }
}

/** Title, up to four category chips and the hour total (iOS `AbsenceCountsList.countRowContent`). */
@Composable
private fun AbsenceCountRow(
    title: String,
    counts: AbsenceCounts,
    categories: List<AbsenceCategory>,
    isTotal: Boolean,
    palette: AbsencePalette,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
) {
    PlainRow(modifier, onClick = onClick, onClickLabel = onClickLabel, horizontalSpacing = GradeySpacing.sm) {
        Text(
            title, Modifier.weight(1f),
            style = if (isTotal) GradeyType.subheadlineBold else GradeyType.subheadlineSemibold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        CategoryChips(counts, categories, palette)
        Text(
            counts.total.toString(), Modifier.widthIn(min = TotalColumnWidth),
            style = GradeyType.subheadlineBold.tabular(), color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.End, maxLines = 1,
        )
    }
}

@Composable
private fun CategoryChips(counts: AbsenceCounts, categories: List<AbsenceCategory>, palette: AbsencePalette) {
    if (categories.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs), verticalAlignment = Alignment.CenterVertically) {
        categories.take(MaxChips).forEach { category ->
            val value = category.value(counts)
            val spoken = stringResource(R.string.absence_category_value, stringResource(category.label), value)
            StatusChip(
                text = "${category.symbol} $value",
                color = palette.color(category),
                modifier = Modifier.clearAndSetSemantics { contentDescription = spoken },
            )
        }
        if (categories.size > MaxChips) {
            StatusChip(stringResource(R.string.absence_chip_overflow, categories.size - MaxChips))
        }
    }
}

// MARK: - Months chart

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AbsenceMonthsChartSection(months: List<AbsenceMonthSummary>, palette: AbsencePalette, formatters: AbsenceFormatters) {
    val legend = remember(months) {
        AbsenceCategory.entries.filter { category -> months.any { category.value(it.counts) > 0 } }
    }
    Column(
        Modifier.fillMaxWidth().padding(top = SectionGap, bottom = SectionGap),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        PlainSectionTitle(stringResource(R.string.absence_months_chart))
        AbsenceMonthsChart(months, palette, formatters, Modifier.padding(horizontal = GradeySpacing.xs))
        FlowRow(
            Modifier.padding(horizontal = GradeySpacing.xs),
            horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
        ) {
            legend.forEach { category ->
                StatusChip("${category.symbol} ${stringResource(category.label)}", color = palette.color(category))
            }
        }
    }
}

@Immutable
private class MonthBar(val label: TextLayoutResult, val segments: List<Pair<Int, Color>>)

@Immutable
private class MonthsChartModel(val ticks: List<Pair<Int, TextLayoutResult>>, val bars: List<MonthBar>, val top: Int)

/**
 * Stacked bars of hours per month in category order, narrow month letters below, about three
 * gridlines with values on the trailing edge (iOS `AbsenceMonthsChart`, 160 pt tall).
 */
@Composable
private fun AbsenceMonthsChart(
    months: List<AbsenceMonthSummary>,
    palette: AbsencePalette,
    formatters: AbsenceFormatters,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val labelStyle = GradeyType.caption2.tabular().copy(color = MaterialTheme.gradeyColors.secondaryLabel)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val model = remember(months, palette, formatters, labelStyle, textMeasurer) {
        val ticks = absenceAxisTicks(months.maxOfOrNull { it.counts.total } ?: 0)
        MonthsChartModel(
            ticks = ticks.map { it to textMeasurer.measure(it.toString(), labelStyle) },
            bars = months.map { month ->
                MonthBar(
                    label = textMeasurer.measure(formatters.narrowMonth(month.month), labelStyle),
                    segments = AbsenceCategory.entries.mapNotNull { category ->
                        category.value(month.counts).takeIf { it > 0 }?.let { it to palette.color(category) }
                    },
                )
            },
            top = ticks.last(),
        )
    }
    val chartTitle = stringResource(R.string.absence_months_chart)
    val spoken = remember(months, formatters, chartTitle) {
        months.joinToString(prefix = "$chartTitle: ", separator = ", ") { "${formatters.monthName(it.month)} ${it.counts.total}" }
    }
    Canvas(modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = spoken }) {
        val gap = 6.dp.toPx()
        val yLabelWidth = model.ticks.maxOf { it.second.size.width }
        val xLabelHeight = model.bars.maxOfOrNull { it.label.size.height } ?: 0
        val firstLabelHalf = (model.ticks.firstOrNull()?.second?.size?.height ?: 0) / 2f
        val plotRight = size.width - yLabelWidth - gap
        val plotTop = firstLabelHalf
        val plotBottom = size.height - xLabelHeight - gap
        val plotHeight = (plotBottom - plotTop).coerceAtLeast(1f)
        val stroke = 1.dp.toPx()
        model.ticks.forEach { (value, label) ->
            val y = plotBottom - plotHeight * value / model.top
            drawLine(gridColor, Offset(0f, y), Offset(plotRight, y), strokeWidth = stroke)
            drawText(label, topLeft = Offset(plotRight + gap, y - label.size.height / 2f))
        }
        if (model.bars.isEmpty()) return@Canvas
        val slot = plotRight / model.bars.size
        val barWidth = minOf(slot * 0.6f, 32.dp.toPx())
        val radius = CornerRadius(3.dp.toPx())
        model.bars.forEachIndexed { index, bar ->
            val center = slot * (index + 0.5f)
            var bottom = plotBottom
            bar.segments.forEach { (value, color) ->
                val height = plotHeight * value / model.top
                drawRoundRect(color, topLeft = Offset(center - barWidth / 2f, bottom - height), size = Size(barWidth, height), cornerRadius = radius)
                bottom -= height
            }
            drawText(bar.label, topLeft = Offset(center - bar.label.size.width / 2f, plotBottom + gap))
        }
    }
}

// MARK: - Attendance corrections

@Immutable
private data class SavedCorrectionModel(
    val key: String,
    val dateKey: String,
    val title: String,
    val pauseReason: AbsenceOverridePauseReason?,
    val hiddenCount: Int,
    val editable: Boolean,
)

/**
 * Local corrections kept from Gradey Android 0.4: pick a recorded day, then review or restore each
 * saved correction, or restore every school record at once.
 */
private fun LazyListScope.correctionItems(
    hiddenCount: Int,
    dates: List<String>,
    formatters: AbsenceFormatters,
    saved: List<SavedCorrectionModel>,
    isLoading: Boolean,
    onEdit: (String) -> Unit,
    onRestore: ((String) -> Unit)?,
    onReset: (() -> Unit)?,
) {
    item(key = "corrections-header", contentType = "correctionsHeader") {
        var choosingDay by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(top = GradeySpacing.xl)) {
            PlainSectionTitle(stringResource(R.string.absence_attendance_corrections_4cc6e))
            SectionFooter(
                if (hiddenCount > 0) stringResource(R.string.absence_hidden_units, hiddenCount)
                else stringResource(R.string.absence_make_a_local_correction_while_keeping_your_school_s_original_r_9d10c),
            )
            Box(Modifier.padding(top = GradeySpacing.xs)) {
                PlainLink(
                    stringResource(R.string.absence_correct_a_recorded_day_cfa2a),
                    onClick = { choosingDay = true },
                    enabled = !isLoading && dates.isNotEmpty(),
                )
                DropdownMenu(
                    expanded = choosingDay,
                    onDismissRequest = { choosingDay = false },
                    containerColor = MaterialTheme.gradeyColors.secondaryGroupedBackground,
                ) {
                    dates.forEach { date ->
                        DropdownMenuItem(
                            text = { Text(formatters.dayTitle(date), style = GradeyType.body, color = MaterialTheme.colorScheme.onSurface) },
                            onClick = { choosingDay = false; onEdit(date) },
                        )
                    }
                }
            }
            if (dates.isEmpty()) SectionFooter(stringResource(R.string.absence_there_are_no_unambiguous_recorded_days_to_correct_c54a9))
        }
    }
    items(saved, key = { it.key }, contentType = { "correctionRow" }) { correction ->
        SavedCorrectionRow(correction, isLoading, onEdit, onRestore)
    }
    if (onReset != null) {
        item(key = "corrections-reset", contentType = "correctionsReset") {
            DestructiveLink(
                stringResource(R.string.absence_restore_all_school_records_8b60b), onClick = onReset, enabled = !isLoading,
                modifier = Modifier.padding(top = GradeySpacing.sm),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SavedCorrectionRow(
    correction: SavedCorrectionModel,
    isLoading: Boolean,
    onEdit: (String) -> Unit,
    onRestore: ((String) -> Unit)?,
) {
    val reason = correction.pauseReason
    PlainRow(verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(correction.title, style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface)
            if (reason != null) {
                Text(
                    stringResource(R.string.absence_needs_review, reason.localizedDescription()),
                    style = GradeyType.caption, color = GradeyColors.Average,
                )
            } else {
                Text(
                    stringResource(R.string.absence_hidden_locally, correction.hiddenCount),
                    style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                if (correction.editable) {
                    PlainLink(stringResource(R.string.absence_review_e29a7), onClick = { onEdit(correction.dateKey) }, enabled = !isLoading, showsArrow = false)
                }
                if (onRestore != null) {
                    PlainLink(
                        stringResource(R.string.absence_restore_school_record_a0dd4), onClick = { onRestore(correction.dateKey) },
                        enabled = !isLoading, showsArrow = false,
                    )
                }
            }
        }
    }
}
