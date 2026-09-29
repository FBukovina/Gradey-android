package com.bukovinafilip.gradey.feature.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.CheckCircleOutline
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.automirrored.filled.EventNote
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import com.bukovinafilip.gradey.ui.adaptiveColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bukovinafilip.gradey.domain.AbsenceRiskLevel
import com.bukovinafilip.gradey.domain.AbsenceRiskSummary
import com.bukovinafilip.gradey.domain.AbsencePlanning
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.PlannedAbsence
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.TimetableWeek
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.domain.SchoolInsightState
import com.bukovinafilip.gradey.domain.TodayInsight
import com.bukovinafilip.gradey.domain.TodayInsightCopy
import com.bukovinafilip.gradey.domain.TodayInsightKind
import com.bukovinafilip.gradey.domain.TodayInsights
import com.bukovinafilip.gradey.ui.color
import com.bukovinafilip.gradey.ui.rememberSchoolTime
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.text.NumberFormat
import kotlin.math.roundToInt

private val BackgroundTop: Color
    @Composable get() = adaptiveColor(Color(0xFFDDF4F2), Color(0xFF17382D))
private val BackgroundMiddle: Color
    @Composable get() = adaptiveColor(Color(0xFFF0F8F8), Color(0xFF12251E))
private val BackgroundBottom: Color
    @Composable get() = adaptiveColor(Color(0xFFF7F7FA), Color(0xFF0C1713))
private val AccentTeal: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val HeroStart = Color(0xFF16A083)
private val AccentGreen = Color(0xFF1CA46A)
private val AccentDark = Color(0xFF063C36)
private val CardWhite: Color
    @Composable get() = adaptiveColor(Color(0xFFFDFDFE), Color(0xFF17261F))
private val MutedText: Color
    @Composable get() = adaptiveColor(Color(0xFF919196), Color(0xFFB0C5B9))
private val SoftMint: Color
    @Composable get() = adaptiveColor(Color(0xFFDDF4EF), Color(0xFF214F3D))
private val SoftGray: Color
    @Composable get() = adaptiveColor(Color(0xFFF0F0F2), Color(0xFF293D33))
private val WarningOrange = Color(0xFFFF8D28)
private val DangerRed = Color(0xFFE5545D)
private val PragueZone = ZoneId.of("Europe/Prague")

@Composable
fun TodayScreen(
    dashboard: DashboardData,
    absence: AbsenceResponse,
    timetable: TimetableWeek?,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenGradeyTools: () -> Unit,
    onOpenMarks: () -> Unit,
    onOpenAbsence: () -> Unit,
    onOpenTimetable: () -> Unit,
    modifier: Modifier = Modifier,
    plannedAbsences: List<PlannedAbsence> = emptyList(),
    onPlanAbsence: () -> Unit = onOpenAbsence,
    mealMenu: StravaCZMenu? = null,
    hasMealAccount: Boolean = false,
    onOpenMeals: (() -> Unit)? = null,
    schoolAccountContent: (@Composable () -> Unit)? = null,
    insightState: SchoolInsightState? = null,
    plannerItems: List<PersonalPlannerItem> = emptyList(),
    schoolScope: String? = null,
    onOpenAttention: ((TodayInsight) -> Unit)? = null,
    onOpenPlanner: (() -> Unit)? = null,
    onOpenPlannerItem: ((String) -> Unit)? = null,
    onStudyPrioritiesAI: (() -> Unit)? = null,
    onWeekSummaryAI: (() -> Unit)? = null,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val subjects = dashboard.marksResponse.subjects
    val displayLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val overall = remember(subjects, displayLocale) { GradeMath.formattedAverage(GradeMath.overallAverage(subjects), displayLocale) }
    val totalMarks = subjects.sumOf { it.marks.size }
    val absenceRows = AbsenceRiskSummary.make(absence, absence.absencesPerSubject).subjects.take(2)
    val schoolTime by rememberSchoolTime()
    val attention = remember(insightState, plannerItems, schoolScope, schoolTime) {
        TodayInsights.make(schoolScope, insightState, plannerItems, schoolTime.toInstant(), PragueZone)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(BackgroundTop, BackgroundMiddle, BackgroundBottom),
                ),
            ),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .testTag("todayScroll")
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 30.dp, end = 16.dp, bottom = com.bukovinafilip.gradey.ui.floatingNavigationContentPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                TodayHeader(
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    onOpenAccount = onOpenAccount,
                    onOpenGradeyTools = onOpenGradeyTools,
                )
            }
            item {
                AverageCard(
                    fullName = dashboard.user?.fullName ?: strings.getString(R.string.today_student),
                    overallAverage = overall,
                    subjectCount = subjects.size,
                    markCount = totalMarks,
                )
            }
            schoolAccountContent?.let { accountContent -> item { accountContent() } }
            item { MarksShortcut(onClick = onOpenMarks) }
            item { TodayTimetableCard(timetable, schoolTime.toLocalDate(), schoolTime.toLocalTime(), onOpenTimetable) }
            onOpenAttention?.let { open -> item {
                AttentionCard(attention, insightState?.sources?.get("marks")?.failed == true, open)
            } }
            if (onStudyPrioritiesAI != null || onWeekSummaryAI != null) item { TodayAIActions(onStudyPrioritiesAI, onWeekSummaryAI) }
            item { RecentMarksCard(subjects, onOpenMarks) }
            onOpenPlanner?.let { open -> item { TodayPlannerCard(plannerItems, schoolScope, schoolTime.toInstant(), open, onOpenPlannerItem) } }
            item { AbsenceRiskCard(rows = absenceRows, onOpenAbsence = onOpenAbsence) }
            onOpenMeals?.let { openMeals ->
                item { LunchCard(mealMenu, hasMealAccount, schoolTime.toLocalDate(), openMeals) }
            }
            item {
                val upcomingLessons = AbsencePlanning.upcomingLessons(plannedAbsences)
                AbsencePredictorCard(
                    plannedLessonCount = upcomingLessons.size,
                    plannedDayCount = upcomingLessons.map { it.dateKey }.distinct().size,
                    onPlanAbsence = onPlanAbsence,
                )
            }
        }
    }
}

@Composable
private fun AttentionCard(insights: List<TodayInsight>, cachedWarning: Boolean, onOpen: (TodayInsight) -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    Surface(Modifier.fillMaxWidth().testTag("today-attention"), shape = RoundedCornerShape(24.dp), color = CardWhite) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(strings.getString(R.string.today_attention_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (insights.isNotEmpty()) Surface(shape = CircleShape, color = SoftMint) {
                    Text(insights.size.toString(), Modifier.padding(horizontal = 9.dp, vertical = 3.dp), color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium)
                }
            }
            if (insights.isEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CheckCircleOutline, null, Modifier.size(26.dp), tint = MaterialTheme.colorScheme.primary)
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(strings.getString(R.string.today_attention_empty_title), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(strings.getString(R.string.today_attention_empty), style = MaterialTheme.typography.bodySmall, color = MutedText)
                }
            }
            insights.forEach { insight ->
                val tint = when (insight.kind) {
                    TodayInsightKind.ASSESSMENT, TodayInsightKind.BUSY_TESTS -> adaptiveColor(Color(0xFFA86708), Color(0xFFFFC16F))
                    TodayInsightKind.DEADLINE -> adaptiveColor(Color(0xFF805BB3), Color(0xFFCBA7F8))
                    TodayInsightKind.WORSENING_TREND -> adaptiveColor(Color(0xFFBE3F4A), Color(0xFFFF9BA4))
                    else -> MaterialTheme.colorScheme.primary
                }
                val icon = when (insight.kind) {
                    TodayInsightKind.ASSESSMENT -> Icons.Default.EditNote
                    TodayInsightKind.DEADLINE -> Icons.Default.Checklist
                    TodayInsightKind.BUSY_TESTS -> Icons.AutoMirrored.Filled.EventNote
                    TodayInsightKind.AVERAGE_CHANGE -> Icons.AutoMirrored.Filled.ShowChart
                    TodayInsightKind.WORSENING_TREND -> Icons.AutoMirrored.Filled.TrendingDown
                    TodayInsightKind.RECENT_GRADE -> Icons.Default.Verified
                }
                val resource = when (insight.copy) {
                    TodayInsightCopy.ASSESSMENT_DUE -> R.string.insight_assessment_due
                    TodayInsightCopy.DEADLINE_DUE -> R.string.insight_deadline_due
                    TodayInsightCopy.AVERAGE_CHANGED -> R.string.insight_average_changed
                    TodayInsightCopy.BUSY_TESTS -> R.string.insight_busy_tests
                    TodayInsightCopy.TREND_WORSENING -> R.string.insight_trend_worsening
                    TodayInsightCopy.GRADE_NEW -> R.string.insight_grade_new
                    TodayInsightCopy.GRADE_EDITED -> R.string.insight_grade_edited
                }
                val number = NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
                val title = if (insight.copy == TodayInsightCopy.AVERAGE_CHANGED) strings.getString(resource, insight.titleValue,
                    insight.previousAverage?.let(number::format).orEmpty(), insight.average?.let(number::format).orEmpty()) else strings.getString(resource, insight.titleValue)
                val subtitle = if (insight.eventIDs.isNotEmpty()) Instant.ofEpochMilli(insight.relevantAt)
                    .atZone(insight.dateZoneID?.let { runCatching { ZoneId.of(it) }.getOrNull() } ?: PragueZone)
                    .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
                    else strings.getString(R.string.today_insight_recorded_average)
                Surface(onClick = { onOpen(insight) }, Modifier.fillMaxWidth().testTag("today-insight-${insight.id}"), color = Color.Transparent, shape = RoundedCornerShape(14.dp)) {
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = RoundedCornerShape(10.dp), color = tint.copy(alpha = 0.13f)) {
                            Box(Modifier.size(36.dp), contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(20.dp), tint = tint) }
                        }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MutedText)
                        }
                        Icon(Icons.Default.ChevronRight, null, Modifier.size(20.dp), tint = MutedText)
                    }
                }
            }
            if (cachedWarning) Text(strings.getString(R.string.today_cached_warning), style = MaterialTheme.typography.bodySmall, color = MutedText)
        }
    }
}

@Composable
private fun TodayHeader(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenGradeyTools: () -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    com.bukovinafilip.gradey.ui.AdaptivePageHeader(
        modifier = Modifier
            .fillMaxWidth(),
    ) {
        Surface(
            modifier = Modifier
                .size(44.dp),
            onClick = onOpenGradeyTools,
            shape = CircleShape,
            color = adaptiveColor(Color(0xFFE9FCFB), Color(0xFF203E33)),
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    color = adaptiveColor(Color(0xFFCFF3EE), Color(0xFF2A5142)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = strings.getString(R.string.today_open_gradey_tools),
                            tint = AccentTeal,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        Text(
            text = strings.getString(R.string.today_today),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Surface(
            modifier = Modifier
                .width(106.dp)
                .height(44.dp),
            shape = RoundedCornerShape(25.dp),
            color = adaptiveColor(Color(0xFFE8FCFA), Color(0xFF203E33)),
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(
                    modifier = Modifier.size(34.dp),
                    onClick = onRefresh,
                    enabled = !isRefreshing,
                    shape = CircleShape,
                    color = Color.Transparent,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(21.dp),
                                color = AccentTeal,
                                strokeWidth = 2.5.dp,
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = strings.getString(R.string.today_refresh_today),
                                tint = AccentTeal,
                                modifier = Modifier.size(27.dp),
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier.size(34.dp),
                    onClick = onOpenAccount,
                    shape = CircleShape,
                    color = adaptiveColor(Color(0xFFC9F2EC), Color(0xFF2A5142)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = strings.getString(R.string.today_open_account),
                            tint = AccentTeal,
                            modifier = Modifier.size(23.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AverageCard(
    fullName: String,
    overallAverage: String,
    subjectCount: Int,
    markCount: Int,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 166.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier
                .background(Brush.horizontalGradient(listOf(HeroStart, AccentGreen)))
                .padding(horizontal = 24.dp, vertical = 24.dp),
        ) {
            Text(
                text = fullName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color(0xFF021D1A),
                fontSize = 22.sp,
                lineHeight = 27.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column {
                    Text(
                        text = strings.getString(R.string.today_overall_average),
                        color = AccentDark,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = overallAverage,
                        color = Color(0xFF001D19),
                        fontSize = 50.sp,
                        lineHeight = 53.sp,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
                Column(
                    modifier = Modifier.padding(top = 1.dp),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = strings.getQuantityString(R.plurals.today_subject_count, subjectCount, subjectCount),
                        color = AccentDark,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = strings.getQuantityString(R.plurals.today_mark_count, markCount, markCount),
                        color = AccentDark,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@Composable
private fun MarksShortcut(onClick: () -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    DashboardSurface(
        modifier = Modifier.heightIn(min = 62.dp),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(background = SoftMint) {
                Icon(
                    imageVector = Icons.Default.Verified,
                    contentDescription = null,
                    tint = AccentTeal,
                    modifier = Modifier.size(25.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = strings.getString(R.string.today_marks),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = strings.getString(R.string.today_averages_subjects_trends_and_calculator),
                    color = MutedText,
                    fontSize = 13.sp,
                    lineHeight = 17.sp,
                )
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = Color(0xFF858589),
                modifier = Modifier.size(27.dp),
            )
        }
    }
}

@Composable
private fun AbsenceRiskCard(
    rows: List<com.bukovinafilip.gradey.domain.AbsenceSubjectSummary>,
    onOpenAbsence: () -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    DashboardSurface(modifier = Modifier.heightIn(min = 190.dp)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 15.dp, bottom = 11.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionHeading(strings.getString(R.string.today_absence_risk))
                ActionPill(text = strings.getString(R.string.today_open), onClick = onOpenAbsence)
            }
            Spacer(Modifier.height(5.dp))
            if (rows.isEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconTile(background = SoftGray) {
                        Icon(Icons.Default.CalendarMonth, contentDescription = null, tint = MutedText)
                    }
                    Spacer(Modifier.width(13.dp))
                    Text(strings.getString(R.string.today_absence_data_unavailable), color = MutedText, fontSize = 14.sp)
                }
            } else {
                rows.forEach { row ->
                    val color = row.level.riskColor()
                    RiskRow(
                        subjectName = row.subjectName,
                        percentage = row.absencePercentage,
                        threshold = row.threshold,
                        missesUntilLimit = row.missesUntilLimit,
                        color = color,
                    )
                }
            }
        }
    }
}

@Composable
private fun RiskRow(
    subjectName: String,
    percentage: Double,
    threshold: Double?,
    missesUntilLimit: Int?,
    color: Color,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 70.dp)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RiskRing(
            progress = if (threshold != null && threshold > 0.0) percentage / threshold else percentage / 100.0,
            color = color,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subjectName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                lineHeight = 19.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = absenceLimitDescription(missesUntilLimit, strings),
                color = MutedText,
                fontSize = 13.sp,
                lineHeight = 16.sp,
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = "${percentage.roundToInt()}%",
            color = color,
            fontSize = 17.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun RiskRing(progress: Double, color: Color) {
    Canvas(modifier = Modifier.size(58.dp)) {
        val strokeWidth = 6.dp.toPx()
        val inset = Offset(strokeWidth / 2f, strokeWidth / 2f)
        val ringSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        drawArc(
            color = color.copy(alpha = 0.28f),
            startAngle = -90f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = inset,
            size = ringSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = (progress.coerceIn(0.0, 1.0) * 360.0).toFloat(),
            useCenter = false,
            topLeft = inset,
            size = ringSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

@Composable
private fun AbsencePredictorCard(plannedLessonCount: Int, plannedDayCount: Int, onPlanAbsence: () -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    DashboardSurface(modifier = Modifier.heightIn(min = 138.dp)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                SectionHeading(strings.getString(R.string.today_absence_predictor))
                ActionPill(text = strings.getString(R.string.today_plan_absence), onClick = onPlanAbsence)
            }
            Spacer(Modifier.height(13.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(background = SoftGray, size = 38.dp) {
                    Icon(
                        imageVector = Icons.Default.CalendarMonth,
                        contentDescription = null,
                        tint = MutedText,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(13.dp))
                Column {
                    Text(
                        text = if (plannedLessonCount == 0) strings.getString(R.string.today_no_planned_absences)
                            else strings.getQuantityString(R.plurals.today_planned_lessons, plannedLessonCount, plannedLessonCount),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 16.sp,
                        lineHeight = 20.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = if (plannedLessonCount == 0) strings.getString(R.string.today_plan_lessons_to_preview_your_absence_risk)
                            else strings.getQuantityString(R.plurals.today_planned_days, plannedDayCount, plannedDayCount),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        color = MutedText,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    )
                }
            }
        }
    }
}


@Composable
private fun LunchCard(menu: StravaCZMenu?, hasAccount: Boolean, today: LocalDate, onOpen: () -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val day = menu?.days?.firstOrNull { it.date.take(10) == today.toString() }
    val ordered = day?.meals.orEmpty().filter { it.ordered }
    DashboardSurface(onClick = onOpen) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionHeading(strings.getString(R.string.today_today_s_lunch))
                ActionPill(if (hasAccount) strings.getString(R.string.today_meals) else strings.getString(R.string.today_connect), onOpen)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                IconTile(SoftMint) { Icon(Icons.Default.Restaurant, null, tint = AccentTeal) }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(when {
                        ordered.isNotEmpty() -> ordered.joinToString(" · ") { it.description?.takeIf(String::isNotBlank) ?: it.title }
                        !hasAccount -> strings.getString(R.string.today_connect_your_canteen)
                        menu == null -> strings.getString(R.string.today_menu_unavailable)
                        day == null -> strings.getString(R.string.today_no_meals_for_today)
                        else -> strings.getString(R.string.today_no_lunch_ordered)
                    }, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(when {
                        ordered.isNotEmpty() -> strings.getString(R.string.today_ordered_strava_cz)
                        !hasAccount -> strings.getString(R.string.today_see_your_lunch_and_manage_meal_orders)
                        menu == null -> strings.getString(R.string.today_open_meals_to_refresh_your_canteen)
                        day == null -> strings.getString(R.string.today_open_meals_to_see_upcoming_days)
                        else -> strings.getString(R.string.today_open_meals_to_see_today_s_menu)
                    }, color = MutedText, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun RecentMarksCard(subjects: List<Subject>, onOpenMarks: () -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val recent = remember(subjects) {
        subjects.flatMap { subject -> subject.marks.map { subject to it } }
            .sortedWith(compareByDescending<Pair<Subject, com.bukovinafilip.gradey.model.Mark>> {
                it.second.markDate.orEmpty()
            }.thenBy { it.second.id }).take(5)
    }
    DashboardSurface {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SectionHeading(strings.getString(R.string.today_recent_marks))
                ActionPill(strings.getString(R.string.today_view_all), onOpenMarks)
            }
            if (recent.isEmpty()) {
                Text(strings.getString(R.string.today_your_latest_marks_will_appear_here_when), color = MutedText, fontSize = 14.sp)
            }
            recent.forEach { (subject, mark) ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val color = GradeMath.band(mark).color()
                    Surface(shape = RoundedCornerShape(12.dp), color = color.copy(alpha = 0.12f)) {
                        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
                            Text(mark.markText, color = color, fontWeight = FontWeight.Bold, fontSize = 20.sp, maxLines = 1)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(subject.displayName, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(mark.caption?.takeIf(String::isNotBlank) ?: mark.theme?.takeIf(String::isNotBlank) ?: strings.getString(R.string.today_new_mark),
                            color = MutedText, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    val date = mark.markDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                    date?.let { Text(it.format(DateTimeFormatter.ofPattern("d. M.")), color = MutedText, fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun DashboardSurface(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    if (onClick == null) {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = CardWhite.copy(alpha = 0.98f),
            shadowElevation = 3.dp,
            content = content,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            onClick = onClick,
            shape = RoundedCornerShape(20.dp),
            color = CardWhite.copy(alpha = 0.98f),
            shadowElevation = 3.dp,
            content = content,
        )
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = MutedText,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.65.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun ActionPill(text: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.heightIn(min = 28.dp),
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = SoftMint,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = AccentTeal,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun IconTile(
    background: Color,
    size: androidx.compose.ui.unit.Dp = 38.dp,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier.size(size),
        shape = RoundedCornerShape(13.dp),
        color = background,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun AbsenceRiskLevel.riskColor(): Color = when (this) {
    AbsenceRiskLevel.SAFE -> AccentTeal
    AbsenceRiskLevel.WATCH -> WarningOrange
    AbsenceRiskLevel.HIGH -> WarningOrange
    AbsenceRiskLevel.OVER_LIMIT -> DangerRed
    AbsenceRiskLevel.UNAVAILABLE -> MutedText
}

private fun absenceLimitDescription(missesUntilLimit: Int?, strings: android.content.res.Resources): String = when (missesUntilLimit) {
    null -> strings.getString(R.string.today_absence_limit_unavailable)
    0 -> strings.getString(R.string.today_at_or_above_the_absence_limit)
    1 -> strings.getString(R.string.today_1_more_missed_lesson_reaches_the_limit)
    else -> strings.getQuantityString(R.plurals.today_lessons_until_limit, missesUntilLimit, missesUntilLimit)
}

private fun plural(count: Int, singular: String, plural: String): String =
    if (count == 1) singular else plural
