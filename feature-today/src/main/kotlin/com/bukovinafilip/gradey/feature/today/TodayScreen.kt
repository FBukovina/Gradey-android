package com.bukovinafilip.gradey.feature.today

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bukovinafilip.gradey.domain.AbsenceRiskSummary
import com.bukovinafilip.gradey.domain.AbsenceSubjectSummary
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.domain.SchoolInsightState
import com.bukovinafilip.gradey.domain.TodayInsight
import com.bukovinafilip.gradey.domain.TodayInsights
import com.bukovinafilip.gradey.domain.TodayTimetableOverview
import com.bukovinafilip.gradey.domain.TodayOverview
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.TimetableWeek
import com.bukovinafilip.gradey.ui.AbsenceRiskRing
import com.bukovinafilip.gradey.ui.GradeBadge
import com.bukovinafilip.gradey.ui.GradeBadgeSize
import com.bukovinafilip.gradey.ui.GradeyCard
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyPageHeader
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InfoRow
import com.bukovinafilip.gradey.ui.LinkPill
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.SectionHeaderRow
import com.bukovinafilip.gradey.ui.StatTile
import com.bukovinafilip.gradey.ui.StatusChip
import com.bukovinafilip.gradey.ui.brandGradient
import com.bukovinafilip.gradey.ui.floatingNavigationContentPadding
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.rememberSchoolTime
import com.bukovinafilip.gradey.ui.riskColor
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

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
    onOpenSubject: ((String) -> Unit)? = null,
    mealMenu: StravaCZMenu? = null,
    hasMealAccount: Boolean = false,
    onOpenMeals: (() -> Unit)? = null,
    linkedAccounts: List<LinkedSchoolAccount> = emptyList(),
    activeAccountID: String? = null,
    onSelectAccount: ((LinkedSchoolAccount) -> Unit)? = null,
    isSwitchingAccount: Boolean = false,
    insightState: SchoolInsightState? = null,
    plannerItems: List<PersonalPlannerItem> = emptyList(),
    schoolScope: String? = null,
    onOpenAttention: ((TodayInsight) -> Unit)? = null,
    onOpenPlanner: (() -> Unit)? = null,
    onOpenPlannerItem: ((String) -> Unit)? = null,
    onStudyPrioritiesAI: (() -> Unit)? = null,
    onWeekSummaryAI: (() -> Unit)? = null,
    hasLocalAbsenceAdjustments: Boolean = false,
) {
    val strings = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val subjects = dashboard.marksResponse.subjects
    val overall = remember(subjects, locale) { GradeMath.formattedAverage(GradeMath.overallAverage(subjects), locale) }
    val schoolTime by rememberSchoolTime()
    val today = schoolTime.toLocalDate()
    val now = schoolTime.toLocalTime()
    val overview = remember(timetable, today, now) { TodayOverview.timetable(timetable, today, now) }
    val attention = remember(insightState, plannerItems, schoolScope, schoolTime) {
        TodayInsights.make(schoolScope, insightState, plannerItems, schoolTime.toInstant(), PragueZone)
    }
    val newMarkIDs = remember(insightState, schoolScope) {
        insightState?.takeIf { it.scope == schoolScope }?.observations.orEmpty().filter { it.seenAt == null }.flatMap { it.addedMarkIDs }.toSet()
    }
    val absenceRisk = remember(absence) { AbsenceRiskSummary.make(absence, absence.absencesPerSubject) }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("todayScroll").statusBarsPadding(),
        contentPadding = PaddingValues(start = GradeySpacing.lg, top = GradeySpacing.sm, end = GradeySpacing.lg, bottom = floatingNavigationContentPadding()),
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
    ) {
        item {
            GradeyPageHeader(
                title = strings.getString(R.string.today_today),
                onOpenAI = onOpenGradeyTools, aiDescription = strings.getString(R.string.today_open_gradey_tools),
                onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = strings.getString(R.string.today_refresh_today),
                onOpenSettings = onOpenAccount, settingsDescription = strings.getString(R.string.today_open_account),
            )
        }
        item {
            TodayHero(
                fullName = dashboard.user?.fullName, classAbbrev = dashboard.user?.classAbbrev,
                accounts = linkedAccounts, activeAccountID = activeAccountID, onSelectAccount = onSelectAccount, isSwitchingAccount = isSwitchingAccount,
                now = schoolTime, overview = overview, timetableAvailable = timetable != null,
                overallAverage = overall, newMarks = newMarkIDs.size, isRefreshing = isRefreshing,
            )
        }
        item { TodayTimetableCard(timetable, overview, today, now, onOpenTimetable) }
        onOpenAttention?.let { open -> item { AttentionCard(attention, insightState?.sources?.get("marks")?.failed == true, open) } }
        if (onStudyPrioritiesAI != null || onWeekSummaryAI != null) item { TodayAIActions(onStudyPrioritiesAI, onWeekSummaryAI) }
        item { RecentMarksCard(subjects, newMarkIDs, onOpenMarks, onOpenSubject) }
        onOpenPlanner?.let { open -> item { TodayPlannerCard(plannerItems, schoolScope, schoolTime.toInstant(), open, onOpenPlannerItem) } }
        if (absenceRisk.subjects.isNotEmpty()) item { AbsenceRiskCard(absenceRisk, hasLocalAbsenceAdjustments, onOpenAbsence) }
        if (hasMealAccount && onOpenMeals != null) item { LunchCard(mealMenu, today, onOpenMeals) }
    }
}

// MARK: - Hero

private enum class DayState { UNAVAILABLE, EMPTY, UNTIMED, BEFORE_SCHOOL, CURRENT, BETWEEN, AFTER }

private fun dayState(overview: TodayTimetableOverview?, timetableAvailable: Boolean, now: LocalTime): DayState {
    if (!timetableAvailable) return DayState.UNAVAILABLE
    if (overview == null) return DayState.EMPTY
    val active = overview.lessons.filter { it.changeKind != LessonChangeKind.CANCELED }
    if (active.isEmpty()) return DayState.EMPTY
    val starts = active.mapNotNull { parseTime(it.hour.beginTime) }
    if (starts.isEmpty()) return DayState.UNTIMED
    if (overview.current != null) return DayState.CURRENT
    if (overview.next != null) return if (starts.min() > now) DayState.BEFORE_SCHOOL else DayState.BETWEEN
    return DayState.AFTER
}

@Composable
private fun TodayHero(
    fullName: String?,
    classAbbrev: String?,
    accounts: List<LinkedSchoolAccount>,
    activeAccountID: String?,
    onSelectAccount: ((LinkedSchoolAccount) -> Unit)?,
    isSwitchingAccount: Boolean,
    now: ZonedDateTime,
    overview: TodayTimetableOverview?,
    timetableAvailable: Boolean,
    overallAverage: String,
    newMarks: Int,
    isRefreshing: Boolean,
) {
    val strings = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val accessibility = LocalDensity.current.fontScale > 1.3f
    val onAccent = GradeyColors.OnAccent
    val state = dayState(overview, timetableAvailable, now.toLocalTime())
    val activeLessons = overview?.lessons?.count { it.changeKind != LessonChangeKind.CANCELED }
    val activeAccount = accounts.firstOrNull { it.id == activeAccountID }
    val greetingName = fullName?.let { nameWithoutClass(it, classAbbrev) }?.takeIf { it.isNotBlank() }
        ?: activeAccount?.displayName?.let { nameWithoutClass(it, classAbbrev) }?.takeIf { it.isNotBlank() }
    val greetingKey = when (now.hour) { in 5..11 -> R.string.today_greeting_morning; in 12..17 -> R.string.today_greeting_afternoon; else -> R.string.today_greeting_evening }
    val greeting = strings.getString(greetingKey) + (greetingName?.let { ", $it" } ?: "")
    val chipLabel = when {
        accounts.size > 1 && activeAccount != null -> nameWithoutClass(activeAccount.displayName, classAbbrev).ifBlank { null }
        else -> null
    } ?: classAbbrev?.trim()?.takeIf { it.isNotEmpty() } ?: activeAccount?.displayName?.trim()?.takeIf { it.isNotEmpty() }
    val dateText = remember(now.toLocalDate(), locale) {
        val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM")
        now.format(DateTimeFormatter.ofPattern(pattern, locale))
    }

    Column(
        Modifier.fillMaxWidth().testTag("todayHero")
            .shadow(16.dp, RoundedCornerShape(GradeyRadius.card), ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f), spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.24f))
            .clip(RoundedCornerShape(GradeyRadius.card)).background(MaterialTheme.colorScheme.brandGradient).padding(GradeySpacing.xl),
        verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
    ) {
        val dateRow: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                Text(dateText.uppercase(locale), style = GradeyType.captionBold.copy(letterSpacing = 0.6.sp), color = onAccent.copy(alpha = 0.72f), maxLines = if (accessibility) 2 else 1, overflow = TextOverflow.Ellipsis)
                if (isRefreshing) CircularProgressIndicator(Modifier.size(12.dp), color = onAccent, strokeWidth = 1.5.dp)
            }
        }
        val chip: @Composable () -> Unit = {
            if (chipLabel != null) AccountChip(chipLabel, accounts.takeIf { it.size > 1 && onSelectAccount != null }.orEmpty(), activeAccountID, onSelectAccount, isSwitchingAccount, compact = !accessibility)
        }
        if (accessibility) Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) { chip(); dateRow() }
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            Box(Modifier.weight(1f)) { dateRow() }
            chip()
        }

        Text(greeting, style = GradeyType.display(30), color = onAccent, maxLines = 3, overflow = TextOverflow.Ellipsis)

        // Status panel
        val icon = when (state) {
            DayState.UNAVAILABLE, DayState.UNTIMED -> HugeIcons.Calendar03
            DayState.EMPTY -> HugeIcons.Sun01
            DayState.BEFORE_SCHOOL -> HugeIcons.Sunrise
            DayState.CURRENT -> HugeIcons.PlayCircle
            DayState.BETWEEN -> HugeIcons.Coffee02
            DayState.AFTER -> HugeIcons.CheckmarkCircle02
        }
        val current = overview?.current
        val next = overview?.next
        val minutesRemaining = current?.let { lesson -> parseTime(lesson.hour.endTime)?.let { minutesBetween(now.toLocalTime(), it) } }
        val minutesUntilNext = next?.takeIf { current == null }?.let { lesson -> parseTime(lesson.hour.beginTime)?.let { minutesBetween(now.toLocalTime(), it) } }
        val title = when (state) {
            DayState.UNAVAILABLE -> strings.getString(R.string.today_timetable_unavailable)
            DayState.UNTIMED -> strings.getString(R.string.today_lesson_times_unavailable)
            DayState.EMPTY -> strings.getString(R.string.today_summary_empty_title)
            DayState.BEFORE_SCHOOL -> strings.getString(R.string.today_summary_before_school_title)
            DayState.CURRENT -> strings.getString(R.string.today_summary_now_title, current?.let { lessonSubject(it, strings) } ?: strings.getString(R.string.today_summary_lesson_fallback))
            DayState.BETWEEN -> strings.getString(R.string.today_summary_between_title)
            DayState.AFTER -> strings.getString(R.string.today_summary_after_title)
        }
        val subtitle = when (state) {
            DayState.UNAVAILABLE -> strings.getString(R.string.today_timetable_unavailable_subtitle)
            DayState.UNTIMED -> strings.getString(R.string.today_open_timetable_to_view_today_s_lessons)
            DayState.EMPTY -> strings.getString(R.string.today_summary_empty_message)
            DayState.BEFORE_SCHOOL, DayState.BETWEEN -> next?.let { nextLessonSummary(it, minutesUntilNext, strings) }
            DayState.CURRENT -> minutesRemaining?.let { strings.getString(R.string.today_summary_remaining, it) }
            DayState.AFTER -> strings.getString(if (!overview?.changedLessons.isNullOrEmpty()) R.string.today_summary_after_changes else R.string.today_summary_after_done)
        }
        val progress = current?.let { lesson ->
            val start = parseTime(lesson.hour.beginTime); val end = parseTime(lesson.hour.endTime)
            if (start != null && end != null && end > start) (Duration.between(start, now.toLocalTime()).seconds.toFloat() / Duration.between(start, end).seconds.toFloat()).coerceIn(0f, 1f) else null
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(GradeyRadius.md)).background(Color.White.copy(alpha = 0.16f)).padding(GradeySpacing.md).testTag("todayHeroStatus"),
            horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md), verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                GradeyIcon(icon, size = 18.dp, tint = onAccent)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                Text(title, style = GradeyType.headline.copy(fontWeight = FontWeight.Bold), color = onAccent)
                if (subtitle != null) Text(subtitle, style = GradeyType.subheadline, color = onAccent.copy(alpha = 0.78f))
                if (progress != null) Box(Modifier.fillMaxWidth().padding(top = GradeySpacing.xs).height(5.dp).clip(CircleShape).background(onAccent.copy(alpha = 0.14f))) {
                    Box(Modifier.fillMaxWidth(fraction = progress.coerceAtLeast(0.03f)).height(5.dp).clip(CircleShape).background(onAccent))
                }
            }
        }

        // Stats
        val tiles: List<Pair<String, String>> = listOf(
            strings.getString(R.string.today_hero_average) to overallAverage,
            strings.getString(R.string.today_hero_lessons_today) to (activeLessons?.toString()?.takeIf { timetableAvailable } ?: "–"),
            strings.getString(R.string.today_hero_new_marks) to newMarks.toString(),
        )
        if (accessibility) Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) { tiles.forEach { (label, value) -> StatTile(label, value) } }
        else Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            tiles.forEachIndexed { index, (label, value) ->
                if (index > 0) Box(Modifier.width(1.dp).height(30.dp).background(onAccent.copy(alpha = 0.14f)))
                StatTile(label, value, Modifier.weight(1f).semantics(mergeDescendants = true) { contentDescription = "$label $value" })
            }
        }
    }
}

@Composable
private fun AccountChip(label: String, accounts: List<LinkedSchoolAccount>, activeAccountID: String?, onSelect: ((LinkedSchoolAccount) -> Unit)?, isSwitching: Boolean, compact: Boolean) {
    val strings = LocalResources.current
    val onAccent = GradeyColors.OnAccent
    var expanded by remember { mutableStateOf(false) }
    val canSwitch = accounts.size > 1 && onSelect != null
    Box {
        Surface(
            onClick = { if (canSwitch) expanded = true }, enabled = canSwitch && !isSwitching, shape = CircleShape, color = Color.White.copy(alpha = 0.22f),
            modifier = Modifier.heightIn(min = 30.dp).widthIn(max = if (compact) 190.dp else 320.dp).testTag("todayAccountSwitcher")
                .semantics { contentDescription = strings.getString(R.string.today_school_account) + ": " + label },
        ) {
            Row(Modifier.padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                if (isSwitching) CircularProgressIndicator(Modifier.size(12.dp), color = onAccent, strokeWidth = 1.5.dp)
                else {
                    if (compact) GradeyIcon(HugeIcons.UserGroup, size = 13.dp, tint = onAccent)
                    Text(label, style = GradeyType.captionBold, color = onAccent, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (canSwitch) GradeyIcon(HugeIcons.ArrowDataTransferVertical, size = 12.dp, tint = onAccent)
                }
            }
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            accounts.forEach { account ->
                DropdownMenuItem(
                    text = { Column { Text(account.displayName); Text(account.schoolName ?: account.provider.displayName, style = GradeyType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                    leadingIcon = { GradeyIcon(if (account.id == activeAccountID) HugeIcons.CheckmarkCircle02 else HugeIcons.University, size = 18.dp) },
                    onClick = { expanded = false; onSelect?.invoke(account) },
                )
            }
        }
    }
}

/** Bakaláři appends the class to `FullName` ("Novák Jan, 3.A"), which reads oddly in a greeting. */
internal fun nameWithoutClass(fullName: String, classAbbrev: String?): String {
    val trimmed = fullName.trim()
    val abbrev = classAbbrev?.trim().orEmpty()
    if (abbrev.isNotEmpty()) {
        val compact = trimmed.replace(", ", ",")
        if (compact.endsWith(",$abbrev") && compact.length > abbrev.length + 1) return trimmed.substringBeforeLast(',').trim()
    }
    val comma = trimmed.lastIndexOf(',')
    if (comma < 0) return trimmed
    val candidate = trimmed.substring(comma + 1).trim()
    val looksLikeClass = candidate.isNotEmpty() && candidate.length <= 6 && candidate.any(Char::isDigit) && ' ' !in candidate
    if (!looksLikeClass) return trimmed
    return trimmed.substring(0, comma).trim().ifEmpty { trimmed }
}

// MARK: - Attention

@Composable
private fun AttentionCard(insights: List<TodayInsight>, cachedWarning: Boolean, onOpen: (TodayInsight) -> Unit) {
    val strings = LocalResources.current
    GradeyCard(Modifier.testTag("today-attention")) {
        SectionHeaderRow(strings.getString(R.string.today_attention_title)) {
            if (insights.isNotEmpty()) StatusChip(insights.size.toString(), color = MaterialTheme.colorScheme.primary)
        }
        if (insights.isEmpty()) InfoRow(HugeIcons.CheckmarkCircle02, strings.getString(R.string.today_attention_empty_title), strings.getString(R.string.today_attention_empty))
        insights.forEach { insight -> TodayInsightRow(insight, onOpen) }
        if (cachedWarning) Text(strings.getString(R.string.today_cached_warning), style = GradeyType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// MARK: - Recent marks

@Composable
private fun RecentMarksCard(subjects: List<Subject>, newMarkIDs: Set<String>, onOpenMarks: () -> Unit, onOpenSubject: ((String) -> Unit)?) {
    val strings = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val recent = remember(subjects) {
        subjects.flatMap { subject -> subject.marks.map { subject to it } }
            .sortedWith(compareByDescending<Pair<Subject, Mark>> { it.second.markDate.orEmpty() }.thenBy { it.second.id })
            .distinctBy { it.second.id }.take(3)
    }
    GradeyCard(Modifier.testTag("today-recent-marks")) {
        SectionHeaderRow(strings.getString(R.string.today_recent_marks)) { LinkPill(strings.getString(R.string.today_marks), onOpenMarks, testTag = "todayOpenMarksButton") }
        if (recent.isEmpty()) InfoRow(HugeIcons.CheckmarkBadge02, strings.getString(R.string.today_recent_marks), strings.getString(R.string.today_recent_marks_empty), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        recent.forEach { (subject, mark) ->
            val date = mark.markDate?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            Surface(onClick = { onOpenSubject?.invoke(subject.id) ?: onOpenMarks() }, color = Color.Transparent, shape = RoundedCornerShape(GradeyRadius.sm), modifier = Modifier.fillMaxWidth().testTag("todayRecentMark-${mark.id}")) {
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                    GradeBadge(mark.markText, GradeMath.band(mark), size = GradeBadgeSize.SMALL)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(subject.displayName, style = GradeyType.subheadlineBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            if (mark.id in newMarkIDs) Box(Modifier.size(7.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary))
                        }
                        Text(mark.caption?.takeIf(String::isNotBlank) ?: mark.theme?.takeIf(String::isNotBlank) ?: subject.subjectInfo.abbrev.ifBlank { subject.displayName },
                            style = GradeyType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)) ?: "–", style = GradeyType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    com.bukovinafilip.gradey.ui.DisclosureIcon()
                }
            }
        }
    }
}

// MARK: - Absence risk

@Composable
private fun AbsenceRiskCard(risk: AbsenceRiskSummary, adjusted: Boolean, onOpenAbsence: () -> Unit) {
    val strings = LocalResources.current
    val rows = risk.subjects.take(3)
    GradeyCard(Modifier.testTag("today-absence-risk")) {
        SectionHeaderRow(strings.getString(R.string.today_absence_risk)) { LinkPill(strings.getString(R.string.today_open), onOpenAbsence, testTag = "todayOpenAbsenceButton") }
        if (adjusted) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            GradeyIcon(HugeIcons.ViewOff, size = 12.dp, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(strings.getString(R.string.today_absence_adjusted), style = GradeyType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        rows.forEach { RiskRow(it) }
        if (risk.isThresholdUnavailable) Text(strings.getString(R.string.today_absence_threshold_unavailable), style = GradeyType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RiskRow(subject: AbsenceSubjectSummary) {
    val strings = LocalResources.current
    val subtitle = when (val misses = subject.missesUntilLimit) {
        null -> strings.getString(R.string.today_absence_missed, subject.base, subject.lessonsCount)
        0 -> strings.getString(R.string.today_at_or_above_the_absence_limit)
        1 -> strings.getString(R.string.today_1_more_missed_lesson_reaches_the_limit)
        else -> strings.getQuantityString(R.plurals.today_lessons_until_limit, misses, misses)
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        AbsenceRiskRing(subject.absencePercentage, subject.threshold, subject.level)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(subject.subjectName, style = GradeyType.subheadlineBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = GradeyType.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${subject.absencePercentage.roundToInt()}%", style = GradeyType.headline, color = subject.level.riskColor())
    }
}

// MARK: - Lunch

@Composable
private fun LunchCard(menu: StravaCZMenu?, today: LocalDate, onOpen: () -> Unit) {
    val strings = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    val day = menu?.days?.firstOrNull { it.date.take(10) == today.toString() }
    val ordered = day?.meals.orEmpty().firstOrNull { it.ordered }
    GradeyCard(Modifier.testTag("today-lunch"), onClick = onOpen) {
        SectionHeader(strings.getString(R.string.today_lunch))
        if (ordered != null) InfoRow(
            HugeIcons.Restaurant02, ordered.description?.takeIf(String::isNotBlank) ?: ordered.title,
            ordered.price?.let { java.text.NumberFormat.getCurrencyInstance(locale).apply { currency = java.util.Currency.getInstance("CZK"); maximumFractionDigits = 0 }.format(it) },
        ) else InfoRow(HugeIcons.Restaurant02, strings.getString(R.string.today_no_meal), strings.getString(R.string.today_no_meal_subtitle), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// MARK: - Helpers

internal fun parseTime(raw: String): LocalTime? {
    val parts = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$").matchEntire(raw.trim()) ?: return null
    return LocalTime.of(parts.groupValues[1].toInt(), parts.groupValues[2].toInt())
}

internal fun minutesBetween(from: LocalTime, to: LocalTime): Int = ceil(Duration.between(from, to).seconds / 60.0).toInt().coerceAtLeast(0)

internal fun lessonSubject(lesson: com.bukovinafilip.gradey.model.ScheduledLesson, strings: android.content.res.Resources): String =
    lesson.subjectName?.takeIf { it.isNotBlank() } ?: lesson.subjectAbbrev?.takeIf { it.isNotBlank() }
    ?: lesson.changeDescription?.takeIf { it.isNotBlank() } ?: strings.getString(R.string.today_summary_lesson_fallback)

/** Countdowns only help for the next couple of hours; further out the start time is easier to read. */
private const val CountdownLimitMinutes = 120

internal fun nextLessonSummary(lesson: com.bukovinafilip.gradey.model.ScheduledLesson, minutes: Int?, strings: android.content.res.Resources): String {
    val subject = lessonSubject(lesson, strings)
    if (minutes != null && minutes <= CountdownLimitMinutes) return strings.getString(R.string.today_summary_next_in, subject, minutes)
    if (lesson.hour.beginTime.isBlank()) return strings.getString(R.string.today_summary_next_is, subject)
    return strings.getString(R.string.today_lesson_next_at, subject, lesson.hour.beginTime)
}

internal fun startsIn(lesson: com.bukovinafilip.gradey.model.ScheduledLesson, minutes: Int?, strings: android.content.res.Resources): String? {
    if (minutes != null && minutes <= CountdownLimitMinutes) return strings.getString(R.string.today_lesson_starts_in, minutes)
    if (lesson.hour.beginTime.isBlank()) return minutes?.let { strings.getString(R.string.today_lesson_starts_in, it) }
    return strings.getString(R.string.today_lesson_starts_at, lesson.hour.beginTime)
}
