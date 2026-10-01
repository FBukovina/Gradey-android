@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.bukovinafilip.gradey.feature.absence

import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.HugeIcon

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import com.bukovinafilip.gradey.ui.adaptiveColor
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.gradeyColors
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.bukovinafilip.gradey.domain.AbsenceOverrideProjection
import com.bukovinafilip.gradey.model.AbsenceDayOverride
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.TimetableWeek
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bukovinafilip.gradey.domain.AbsenceDaySummary
import com.bukovinafilip.gradey.domain.AbsenceMonthSummary
import com.bukovinafilip.gradey.domain.AbsenceRiskLevel
import com.bukovinafilip.gradey.domain.AbsenceRiskSummary
import com.bukovinafilip.gradey.domain.AbsenceSubjectSummary
import com.bukovinafilip.gradey.domain.AbsenceTimeline
import com.bukovinafilip.gradey.domain.AbsenceTimelineSummary
import com.bukovinafilip.gradey.domain.normalizedAbsenceThreshold
import com.bukovinafilip.gradey.model.AbsenceCounts
import com.bukovinafilip.gradey.model.AbsenceResponse
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlin.math.max

private val AccentTeal: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val NavigationTeal: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val ExcusedGreen = GradeyColors.Excellent
private val RiskOrange: Color
    @Composable get() = MaterialTheme.gradeyColors.orange
private val LateOrange = GradeyColors.Average
private val MissedRed = GradeyColors.Poor
private val MutedText: Color
    @Composable get() = MaterialTheme.colorScheme.onSurfaceVariant
private val CardWhite: Color
    @Composable get() = MaterialTheme.colorScheme.surface
private val DividerColor: Color
    @Composable get() = MaterialTheme.gradeyColors.separator
private val SoftTeal: Color
    @Composable get() = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
private val SoftGray: Color
    @Composable get() = MaterialTheme.gradeyColors.tertiaryFill
private val ProgressRail: Color
    @Composable get() = MaterialTheme.gradeyColors.tertiaryFill
private val DayFormatter: DateTimeFormatter
    @Composable get() = DateTimeFormatter.ofPattern("EEE d. M.", LocalConfiguration.current.locales[0])
private val MonthFormatter: DateTimeFormatter
    @Composable get() = DateTimeFormatter.ofPattern("MMMM yyyy", LocalConfiguration.current.locales[0])

private enum class AbsenceMode(val label: Int, val description: Int) {
    Subjects(R.string.absence_subjects_27894, R.string.absence_show_subjects_cf41c),
    Days(R.string.absence_by_days_bacd2, R.string.absence_show_by_days_ff84f),
    Months(R.string.absence_by_months_fd49d, R.string.absence_show_by_months_a465a),
}

@Composable
fun AbsenceScreen(
    response: AbsenceResponse,
    studentName: String,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenGradeyTools: () -> Unit,
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
    var editingDate by remember(overrideScope) { mutableStateOf<String?>(null) }
    var pendingSave by remember(overrideScope) { mutableStateOf<AbsenceDayOverride?>(null) }
    LaunchedEffect(overrides, pendingSave) {
        val pending = pendingSave
        if (pending != null && overrides.any { it.id == pending.id && it.savedAtEpochMillis == pending.savedAtEpochMillis }) {
            pendingSave = null
            editingDate = null
        }
    }
    val correctionDay = rawResponse.absences.singleOrNull { AbsenceOverrideProjection.dateKey(it.date) == editingDate }
    if (correctionDay != null && overrideScope != null && onSaveOverride != null) {
        AbsenceOverrideEditor(rawResponse = rawResponse, day = correctionDay, scope = overrideScope,
            existing = overrides.firstOrNull { it.dateKey == editingDate }, availableSubjects = availableSubjects,
            lessons = correctionTimetable?.days?.firstOrNull { AbsenceOverrideProjection.dateKey(it.date.orEmpty()) == editingDate }?.lessons.orEmpty(),
            isSaving = isRefreshing, errorMessage = correctionError,
            onSave = { pendingSave = it; onSaveOverride(it) }, onDismiss = { editingDate = null; pendingSave = null })
    }
    val timeline = remember(response) { AbsenceTimeline.make(response) }
    val riskSummary = remember(response) {
        AbsenceRiskSummary.make(response, response.absencesPerSubject)
    }

    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = com.bukovinafilip.gradey.ui.floatingNavigationContentPadding()),
        ) {
            item {
                com.bukovinafilip.gradey.ui.GradeyPageHeader(
                    title = stringResource(R.string.absence_absence_f7eb4),
                    onOpenAI = onOpenGradeyTools, aiDescription = stringResource(R.string.absence_open_gradey_tools_fba07),
                    onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = stringResource(R.string.absence_refresh_absence_64777),
                    onOpenSettings = onOpenAccount, settingsDescription = stringResource(R.string.absence_open_account_db17a),
                )
            }
            item { Spacer(Modifier.height(12.dp)) }
            item {
                AbsenceSummaryCard(
                    studentName = studentName,
                    counts = timeline.total,
                    threshold = normalizedThreshold(response.percentageThreshold),
                )
            }
            if (onPlanAbsence != null) {
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    com.bukovinafilip.gradey.ui.GradeyCard(onClick = onPlanAbsence) {
                        com.bukovinafilip.gradey.ui.InfoRow(HugeIcons.CalendarAdd01, stringResource(R.string.absence_plan_absence_c429a), stringResource(R.string.absence_preview_the_impact_of_missed_lessons_de5c9)) {
                            com.bukovinafilip.gradey.ui.DisclosureIcon()
                        }
                    }
                }
            }
            if (overrideScope != null && onSaveOverride != null) {
                item { Spacer(Modifier.height(12.dp)) }
                item {
                    AttendanceCorrectionsCard(rawResponse = rawResponse, overrides = overrides, scope = overrideScope,
                        isLoading = isRefreshing,
                        onEdit = { date -> editingDate = date; pendingSave = null; onLoadCorrectionWeek?.invoke(date) },
                        onRestore = onRestoreOverride, onReset = onResetOverrides)
                }
            }
            item { Spacer(Modifier.height(11.dp)) }
            item { AbsenceModePicker(selected = mode, onSelect = { mode = it }) }
            when (mode) {
                AbsenceMode.Subjects -> {
                    item { Spacer(Modifier.height(12.dp)) }
                    item { SubjectsCard(riskSummary.subjects) }
                }

                AbsenceMode.Days -> {
                    item { Spacer(Modifier.height(12.dp)) }
                    item { DaysCard(timeline) }
                }

                AbsenceMode.Months -> {
                    item { Spacer(Modifier.height(8.dp)) }
                    item { SectionHeading(stringResource(R.string.absence_hours_by_month_3ff19)) }
                    item { Spacer(Modifier.height(8.dp)) }
                    item { MonthsChartCard(timeline.months) }
                    item { Spacer(Modifier.height(9.dp)) }
                    item { MonthsCard(timeline) }
                }
            }
        }
    }
}

@Composable
private fun AbsenceSummaryCard(
    studentName: String,
    counts: AbsenceCounts,
    threshold: Double?,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 140.dp),
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 15.dp, end = 16.dp, bottom = 12.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 46.dp),
                verticalAlignment = Alignment.Top,
            ) {
                AbsenceIconTile()
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.absence_absence_f7eb4),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 17.sp,
                        lineHeight = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = studentName.ifBlank { stringResource(R.string.absence_student_42b32) },
                        color = MutedText,
                        fontSize = 17.sp,
                        lineHeight = 20.sp,
                    )
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = counts.total.toString(),
                        color = AccentTeal,
                        fontSize = 24.sp,
                        lineHeight = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = stringResource(R.string.absence_hours_5aede),
                        color = MutedText,
                        fontSize = 14.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            SummaryPills(counts)
            Spacer(Modifier.height(8.dp))
            Text(
                text = threshold?.let { stringResource(R.string.absence_school_limit_value, formatWhole(it)) } ?: stringResource(R.string.absence_school_limit_unavailable_0c832),
                color = MutedText,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun AbsenceIconTile() {
    com.bukovinafilip.gradey.ui.IconTile(HugeIcons.CalendarRemove02, AccentTeal, size = 46.dp, iconSize = 22.dp, cornerRadius = 13.dp)
}

@Composable
private fun SummaryPills(counts: AbsenceCounts) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        absenceCategories(counts).filter { it.second > 0 }.forEach { (kind, count) ->
            LegendPill(kind, "$count ${stringResource(kind.label).lowercase()}")
        }
        if (counts.total == 0) Text(stringResource(R.string.absence_no_absences_recorded_e483d), color = AccentTeal, fontSize = 14.sp)
    }
}

@Composable
private fun AbsenceModePicker(
    selected: AbsenceMode,
    onSelect: (AbsenceMode) -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(33.dp),
        shape = RoundedCornerShape(17.dp),
        color = Color(0x0D000000),
    ) {
        Row(modifier = Modifier.padding(2.dp)) {
            AbsenceMode.entries.forEach { mode ->
                val description = stringResource(mode.description)
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxSize()
                        .selectable(
                            selected = selected == mode,
                            role = Role.Tab,
                            onClick = { onSelect(mode) },
                        )
                        .semantics { contentDescription = description },
                    shape = RoundedCornerShape(15.dp),
                    color = if (selected == mode) MaterialTheme.colorScheme.surface else Color.Transparent,
                    shadowElevation = if (selected == mode) 1.dp else 0.dp,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(mode.label),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            lineHeight = 18.sp,
                            fontWeight = if (selected == mode) FontWeight.SemiBold else FontWeight.Medium,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SubjectsCard(subjects: List<AbsenceSubjectSummary>) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 1.dp,
    ) {
        if (subjects.isEmpty()) {
            EmptyState(stringResource(R.string.absence_no_subject_absence_data_8033c))
        } else {
            Column {
                subjects.forEachIndexed { index, subject ->
                    SubjectRow(subject)
                    if (index != subjects.lastIndex) {
                        HorizontalDivider(color = DividerColor, thickness = 0.33.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubjectRow(subject: AbsenceSubjectSummary) {
    val color = when (subject.level) {
        AbsenceRiskLevel.SAFE -> AccentTeal
        AbsenceRiskLevel.WATCH, AbsenceRiskLevel.HIGH -> RiskOrange
        AbsenceRiskLevel.OVER_LIMIT -> MissedRed
        AbsenceRiskLevel.UNAVAILABLE -> MutedText
    }
    val missedSummary = stringResource(R.string.absence_subject_missed_summary, subject.base, subject.lessonsCount)
    val limitSummary = when (subject.missesUntilLimit) {
        null -> stringResource(R.string.absence_limit_unavailable)
        0 -> stringResource(R.string.absence_at_limit)
        else -> stringResource(R.string.absence_misses_until_limit, subject.missesUntilLimit ?: 0)
    }
    val threshold = subject.threshold ?: 100.0
    val progress = (subject.absencePercentage / threshold).toFloat().coerceIn(0f, 1f)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 81.dp)
            .padding(horizontal = 12.dp, vertical = 9.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = subject.subjectName,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${formatOneDecimal(subject.absencePercentage)} %",
                color = color,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .background(ProgressRail, RoundedCornerShape(3.dp)),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .height(6.dp)
                    .background(color, RoundedCornerShape(3.dp)),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = buildAnnotatedString {
                append(missedSummary)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(limitSummary)
                }
            },
            color = MutedText,
            fontSize = 13.sp,
            lineHeight = 16.sp,
        )
    }
}

@Composable
private fun DaysCard(timeline: AbsenceTimelineSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 1.dp,
    ) {
        Column {
            TotalsRow(timeline.total)
            timeline.days.forEach { day ->
                HorizontalDivider(color = DividerColor, thickness = 0.33.dp)
                DayRow(day)
            }
            if (timeline.days.isEmpty()) EmptyState(stringResource(R.string.absence_no_absence_days_e0758))
        }
    }
}

@Composable
private fun TotalsRow(counts: AbsenceCounts) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(SoftGray)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.absence_total_b2592),
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = counts.total.toString(),
            modifier = Modifier.width(34.dp),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun DayRow(day: AbsenceDaySummary) {
    AbsenceCountRow(day.date.format(DayFormatter), day.counts)
}

@Composable
private fun AbsenceCountRow(label: String, counts: AbsenceCounts) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("${counts.total} h", color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        CompactCountPills(counts)
    }
}

@Composable
private fun CompactCountPills(counts: AbsenceCounts) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        absenceCategories(counts).filter { it.second > 0 }.forEach { (kind, count) -> CountPill(kind, count) }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = MutedText,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp,
    )
}

@Composable
private fun MonthsChartCard(months: List<AbsenceMonthSummary>) {
    Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = CardWhite, shadowElevation = 1.dp) {
        Column(Modifier.padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MonthBars(months, Modifier.fillMaxWidth().height(189.dp))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                AttendanceKind.entries.filter { kind -> months.takeLast(5).any { month -> absenceCategories(month.counts).any { it.first == kind && it.second > 0 } } }
                    .forEach { LegendPill(it, stringResource(it.label)) }
            }
        }
    }
}

@Composable
private fun MonthBars(
    months: List<AbsenceMonthSummary>,
    modifier: Modifier = Modifier,
) {
    val chartGrid = MaterialTheme.colorScheme.outlineVariant
    val visibleMonths = months.takeLast(5)
    val chartLabel = stringResource(R.string.absence_hours_by_month_accessibility)
    val maxMonth = visibleMonths.maxOfOrNull { it.counts.total } ?: 0
    val axisMax = max(10, ceil(maxMonth / 5.0).toInt() * 5)
    Box(
        modifier = modifier.semantics {
            contentDescription = visibleMonths.joinToString(
                prefix = chartLabel + ": ",
                separator = ", ",
            ) { "${it.month.month.name.lowercase().replaceFirstChar(Char::uppercase)} ${it.counts.total}" }
        },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val gridLeft = 16.dp.toPx()
            val gridRight = size.width - 32.dp.toPx()
            val plotTop = 16.dp.toPx()
            val plotBottom = 159.dp.toPx()
            val plotHeight = plotBottom - plotTop
            val gridColor = chartGrid
            listOf(plotTop, plotTop + plotHeight / 2f, plotBottom).forEach { y ->
                drawLine(gridColor, Offset(gridLeft, y), Offset(gridRight, y), strokeWidth = 0.7.dp.toPx())
            }

            if (visibleMonths.isNotEmpty()) {
                val barRegionLeft = 13.5.dp.toPx()
                val barRegionRight = size.width - 31.5.dp.toPx()
                val slotWidth = (barRegionRight - barRegionLeft) / visibleMonths.size
                val barWidth = minOf(44.dp.toPx(), slotWidth * 0.72f)
                visibleMonths.forEachIndexed { index, month ->
                    val left = barRegionLeft + slotWidth * index + (slotWidth - barWidth) / 2f
                    drawMonthBar(
                        counts = month.counts,
                        left = left,
                        bottom = plotBottom,
                        width = barWidth,
                        plotHeight = plotHeight,
                        axisMax = axisMax,
                    )
                }
            }
        }

        Text(
            text = axisMax.toString(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 9.dp)
                .offset(y = 7.dp),
            color = MutedText,
            fontSize = 13.sp,
        )
        Text(
            text = (axisMax / 2).toString(),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 9.dp)
                .offset(y = 78.dp),
            color = MutedText,
            fontSize = 13.sp,
        )
        Text(
            text = "0",
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(end = 9.dp)
                .offset(y = 150.dp),
            color = MutedText,
            fontSize = 13.sp,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 45.dp)
                .offset(x = (-9.5).dp, y = 160.dp)
                .height(20.dp),
        ) {
            visibleMonths.forEach { month ->
                Text(
                    text = month.month.format(DateTimeFormatter.ofPattern("MMM", LocalConfiguration.current.locales[0])),
                    modifier = Modifier.weight(1f),
                    color = MutedText,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

private fun DrawScope.drawMonthBar(
    counts: AbsenceCounts,
    left: Float,
    bottom: Float,
    width: Float,
    plotHeight: Float,
    axisMax: Int,
) {
    val segments = listOf(
        counts.ok to ExcusedGreen,
        counts.unsolved to AttendanceKind.Unresolved.foreground,
        counts.missed to MissedRed,
        counts.late to LateOrange,
        counts.soon to AttendanceKind.Early.foreground,
        counts.school to AttendanceKind.School.foreground,
        counts.distanceTeaching to AttendanceKind.Distance.foreground,
    ).filter { it.first > 0 }
    var currentBottom = bottom
    segments.forEachIndexed { index, (value, color) ->
        val height = plotHeight * value.toFloat() / axisMax.toFloat()
        val top = currentBottom - height
        if (index == segments.lastIndex) {
            drawRoundRect(
                color = color,
                topLeft = Offset(left, top),
                size = Size(width, height + if (segments.size > 1) 2.dp.toPx() else 0f),
                cornerRadius = CornerRadius(3.dp.toPx(), 3.dp.toPx()),
            )
        } else {
            drawRect(color = color, topLeft = Offset(left, top), size = Size(width, height))
        }
        currentBottom = top
    }
}

@Composable
private fun MonthsCard(timeline: AbsenceTimelineSummary) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 1.dp,
    ) {
        Column {
            TotalsRow(timeline.total)
            timeline.months.forEach { month ->
                HorizontalDivider(color = DividerColor, thickness = 0.33.dp)
                MonthRow(month)
            }
            if (timeline.months.isEmpty()) EmptyState(stringResource(R.string.absence_no_monthly_absence_data_ab52f))
        }
    }
}

@Composable
private fun MonthRow(month: AbsenceMonthSummary) {
    AbsenceCountRow(month.month.format(MonthFormatter), month.counts)
}

private enum class AttendanceKind(
    val label: Int,
    val foreground: Color,
) {
    Unresolved(R.string.absence_unresolved_eeccd, GradeyColors.Good),
    Excused(R.string.absence_excused_6c2a9, ExcusedGreen),
    Late(R.string.absence_late_arrivals_9e2a1, LateOrange),
    Missed(R.string.absence_unexcused_9076d, MissedRed),
    Early(R.string.absence_early_departures_b3f13, Color(0xFF9A7FD6)),
    School(R.string.absence_school_events_4b8bc, Color(0xFF4C98CC)),
    Distance(R.string.absence_distance_learning_e96ec, Color(0xFF8DA3AE)),
}

private fun absenceCategories(counts: AbsenceCounts): List<Pair<AttendanceKind, Int>> = listOf(
    AttendanceKind.Unresolved to counts.unsolved,
    AttendanceKind.Excused to counts.ok,
    AttendanceKind.Missed to counts.missed,
    AttendanceKind.Late to counts.late,
    AttendanceKind.Early to counts.soon,
    AttendanceKind.School to counts.school,
    AttendanceKind.Distance to counts.distanceTeaching,
)

@Composable
private fun CountPill(kind: AttendanceKind, count: Int) {
    val accessibleLabel = "${stringResource(kind.label)}: $count"
    Surface(
        modifier = Modifier.heightIn(min = 25.dp).semantics { contentDescription = accessibleLabel },
        shape = RoundedCornerShape(13.dp),
        color = kind.foreground.copy(alpha = 0.14f),
        contentColor = kind.foreground,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            AttendanceIcon(kind)
            Text(
                text = count.toString(),
                color = kind.foreground,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun LegendPill(kind: AttendanceKind, label: String) {
    Surface(
        modifier = Modifier.height(25.dp),
        shape = RoundedCornerShape(13.dp),
        color = kind.foreground.copy(alpha = 0.14f),
        contentColor = kind.foreground,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AttendanceIcon(kind)
            Text(
                text = label,
                color = kind.foreground,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AttendanceIcon(kind: AttendanceKind) {
    when (kind) {
        AttendanceKind.Unresolved -> Text("?", color = kind.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        AttendanceKind.Excused -> HugeIcon(HugeIcons.Tick02, contentDescription = null, modifier = Modifier.size(14.dp))
        AttendanceKind.Late -> Text("P", color = kind.foreground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        AttendanceKind.Missed -> Text("!", color = kind.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        AttendanceKind.Early -> Text("↗", color = kind.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        AttendanceKind.School -> Text("S", color = kind.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        AttendanceKind.Distance -> Text("D", color = kind.foreground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, color = MutedText, fontSize = 14.sp)
    }
}

private fun normalizedThreshold(value: Double?): Double? =
    normalizedAbsenceThreshold(value)

@Composable
private fun formatWhole(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString() else String.format(LocalConfiguration.current.locales[0], "%.1f", value)

@Composable
private fun formatOneDecimal(value: Double): String = String.format(LocalConfiguration.current.locales[0], "%.1f", value)
