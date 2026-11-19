package com.bukovinafilip.gradey.feature.subjects

import android.icu.text.RelativeDateTimeFormatter
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.GradeBand
import com.bukovinafilip.gradey.domain.GradeCalculationIssue
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.domain.GradeSimulation
import com.bukovinafilip.gradey.domain.HypotheticalGrade
import com.bukovinafilip.gradey.domain.PreparedGradeCalculation
import com.bukovinafilip.gradey.domain.SchoolInsightState
import com.bukovinafilip.gradey.model.AbsenceDayOverride
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.EmptyState
import com.bukovinafilip.gradey.ui.ErrorState
import com.bukovinafilip.gradey.ui.GradeBadge
import com.bukovinafilip.gradey.ui.GradeyAIButton
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlinePill
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.PlainHeader
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.SegmentedControl
import com.bukovinafilip.gradey.ui.StatusChip
import com.bukovinafilip.gradey.ui.color
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.softColor
import com.bukovinafilip.gradey.ui.tabular
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlinx.coroutines.delay

private val SchoolZone: ZoneId = ZoneId.of("Europe/Prague")
private const val PushMillis = 300
private const val ExpandMillis = 250
private const val PredictionDebounceMillis = 300L
private val AbbreviationAutoSize = TextAutoSize.StepBased(GradeyType.subheadlineBold.fontSize * 0.55f, GradeyType.subheadlineBold.fontSize)

/** Overall numbers under the Marks header, computed once per data change. */
@Immutable
private class MarksSummary(val average: Double?, val subjectCount: Int, val markCount: Int)

/**
 * Marks tab (iOS 2.8.1 `SubjectsView`): the overall average, the report card and Recap rows, then the
 * subjects sorted by Priority or Average. Tapping a subject pushes its page (iOS `SubjectDetailView`)
 * inside this tab with a horizontal slide; system Back returns to the list at the same scroll position.
 * The shell draws the background and the bottom bar.
 *
 * [predictSubjectAverage] asks the school for its own what-if average (iOS `predictSubjectAverage`);
 * without it, "Try a mark" shows the local estimate only. [reportCardRow] and [recapTile] are drawn in
 * that order under the header when given.
 */
@Composable
fun SubjectsScreen(
    subjects: List<Subject>,
    absence: AbsenceResponse,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenAI: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onAskAI: ((String) -> Unit)? = null,
    predictSubjectAverage: (suspend (Subject, String, Int) -> Double?)? = null,
    initialSubjectID: String? = null,
    onInitialSubjectHandled: (() -> Unit)? = null,
    insightState: SchoolInsightState? = null,
    plannerItems: List<PersonalPlannerItem> = emptyList(),
    schoolScope: String? = null,
    absenceOverrides: List<AbsenceDayOverride> = emptyList(),
    errorMessage: String? = null,
    reportCardRow: (@Composable () -> Unit)? = null,
    recapTile: (@Composable () -> Unit)? = null,
) {
    val locale = LocalConfiguration.current.locales[0]
    var selectedID by rememberSaveable { mutableStateOf<String?>(null) }
    var sortIndex by rememberSaveable { mutableIntStateOf(0) }
    // Hoisted above the push transition so returning from a subject keeps the list where it was.
    val marksListState = rememberLazyListState()
    LaunchedEffect(initialSubjectID) {
        if (initialSubjectID != null) {
            selectedID = initialSubjectID
            onInitialSubjectHandled?.invoke()
        }
    }

    val entries = remember(subjects, absence) { subjectEntries(subjects, absence) }
    val entriesByID = remember(entries) { entries.associateBy { it.id } }
    val summary = remember(entries) { MarksSummary(overallAverage(entries), entries.size, entries.sumOf { it.subject.marks.size }) }
    val insights = remember(insightState, schoolScope) { insightState?.takeIf { it.scope == schoolScope } }
    val scores = remember(entries, insights, plannerItems, schoolScope) { priorityScores(entries, insights, plannerItems, schoolScope) }
    val sortMode = SubjectSortMode.entries[sortIndex.coerceIn(0, SubjectSortMode.entries.lastIndex)]
    val sorted = remember(entries, sortMode, scores, locale) { sortedEntries(entries, sortMode, scores, locale) }
    val newMarkIDs = remember(insights) {
        insights?.observations.orEmpty().filter { it.seenAt == null }.flatMapTo(HashSet()) { it.addedMarkIDs }
    }
    val activeOverrides = remember(absenceOverrides) { absenceOverrides.active() }
    val openSubject = remember { { id: String -> selectedID = id } }
    val closeSubject = remember { { selectedID = null } }
    val changeSort = remember { { index: Int -> sortIndex = index } }
    BackHandler(enabled = selectedID != null, onBack = closeSubject)

    AnimatedContent(
        targetState = selectedID, modifier = modifier.fillMaxSize(), label = "marksPush",
        transitionSpec = {
            val slide = tween<IntOffset>(PushMillis, easing = FastOutSlowInEasing)
            val fade = tween<Float>(PushMillis)
            if (targetState != null) {
                slideInHorizontally(slide) { it } togetherWith (slideOutHorizontally(slide) { -it / 4 } + fadeOut(fade))
            } else {
                (slideInHorizontally(slide) { -it / 4 } + fadeIn(fade)) togetherWith slideOutHorizontally(slide) { it }
            }
        },
    ) { subjectID ->
        if (subjectID == null) {
            MarksList(
                entries = sorted, summary = summary, listState = marksListState,
                sortIndex = sortIndex, onSortChange = changeSort, isRefreshing = isRefreshing, errorMessage = errorMessage,
                hasAbsenceAdjustments = activeOverrides.isNotEmpty(), onRefresh = onRefresh, onOpenAI = onOpenAI,
                onOpenSettings = onOpenSettings, onOpenSubject = openSubject, reportCardRow = reportCardRow, recapTile = recapTile,
            )
        } else {
            val entry = entriesByID[subjectID]
            SubjectDetail(
                entry = entry,
                newMarkIDs = newMarkIDs,
                hasAbsenceAdjustment = remember(entry, activeOverrides) { hasLocalAbsenceAdjustment(entry?.absence, activeOverrides) },
                onBack = closeSubject,
                onAskAI = onAskAI?.let { ask -> { ask(subjectID) } },
                predictSubjectAverage = predictSubjectAverage,
            )
        }
    }
}

// MARK: - List

@Composable
private fun MarksList(
    entries: List<SubjectEntry>,
    summary: MarksSummary,
    listState: LazyListState,
    sortIndex: Int,
    onSortChange: (Int) -> Unit,
    isRefreshing: Boolean,
    errorMessage: String?,
    hasAbsenceAdjustments: Boolean,
    onRefresh: () -> Unit,
    onOpenAI: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenSubject: (String) -> Unit,
    reportCardRow: (@Composable () -> Unit)?,
    recapTile: (@Composable () -> Unit)?,
) {
    val locale = LocalConfiguration.current.locales[0]
    val scrolled by remember(listState) { derivedStateOf { listState.isScrolled } }
    Column(Modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.subjects_subjects_title), scrolled = scrolled,
            onOpenAI = onOpenAI, aiDescription = stringResource(R.string.subjects_gradey_ai_title),
            onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = stringResource(R.string.subjects_action_refresh),
            onOpenSettings = onOpenSettings, settingsDescription = stringResource(R.string.subjects_settings_title),
        )
        when {
            entries.isEmpty() && isRefreshing -> LoadingState(
                Modifier.weight(1f).padding(top = GradeySpacing.xxl).testTag("subjectsLoadingView"), stringResource(R.string.subjects_subjects_loading),
            )
            entries.isEmpty() && errorMessage != null -> ErrorState(
                errorMessage, Modifier.weight(1f).padding(top = GradeySpacing.xxl).testTag("subjectsErrorView"),
                title = stringResource(R.string.subjects_error_title), retryLabel = stringResource(R.string.subjects_action_retry), onRetry = onRefresh,
            )
            entries.isEmpty() -> EmptyState(
                stringResource(R.string.subjects_subjects_empty_title), Modifier.weight(1f).padding(top = GradeySpacing.xxl).testTag("subjectsEmptyView"),
                message = stringResource(R.string.subjects_subjects_empty_message), icon = HugeIcons.LeftToRightListBullet,
            )
            else -> GradeyLazyColumn(Modifier.weight(1f).testTag("subjectsList"), state = listState, itemSpacing = 0.dp) {
                // The refreshing line shares the header item so it shows above the header without
                // the list anchoring past it.
                item(key = "hero", contentType = "hero") {
                    Column {
                        if (isRefreshing) RefreshingLine(Modifier.padding(bottom = GradeySpacing.xl))
                        PlainHeader(
                            label = stringResource(R.string.subjects_marks_hero_average),
                            value = remember(summary, locale) { GradeMath.formattedAverage(summary.average, locale) },
                            valueColor = GradeMath.band(summary.average).color(),
                            summary = pluralStringResource(R.plurals.subjects_marks_hero_subject_count, summary.subjectCount, summary.subjectCount) +
                                " · " + pluralStringResource(R.plurals.subjects_subject_mark_count, summary.markCount, summary.markCount),
                            modifier = Modifier.testTag("marksHeroCard"),
                        )
                    }
                }
                if (reportCardRow != null) item(key = "reportCard", contentType = "reportCard") {
                    Box(Modifier.padding(top = GradeySpacing.xl)) { reportCardRow() }
                }
                if (recapTile != null) item(key = "recap", contentType = "recap") {
                    Box(Modifier.padding(top = GradeySpacing.xl)) { recapTile() }
                }
                if (hasAbsenceAdjustments) item(key = "adjusted", contentType = "note") {
                    AdjustedLabel(
                        stringResource(R.string.subjects_absence_title) + " · " + stringResource(R.string.subjects_absence_override_adjusted),
                        Modifier.padding(top = GradeySpacing.xl, start = GradeySpacing.xs).testTag("subjectsAbsenceAdjusted"),
                    )
                }
                item(key = "directoryTitle", contentType = "title") {
                    val sortLabel = stringResource(R.string.subjects_marks_sort_label)
                    PlainSectionTitle(
                        stringResource(R.string.subjects_marks_directory_title), Modifier.padding(top = GradeySpacing.xl, bottom = GradeySpacing.sm),
                        trailing = {
                            SegmentedControl(
                                options = listOf(stringResource(R.string.subjects_marks_sort_focus), stringResource(R.string.subjects_marks_sort_average)),
                                selectedIndex = sortIndex, onSelect = onSortChange,
                                modifier = Modifier.widthIn(max = 220.dp).semantics { contentDescription = sortLabel }.testTag("subjectsSortPicker"),
                            )
                        },
                    )
                }
                items(entries, key = { it.id }, contentType = { "subject" }) { entry ->
                    SubjectRow(entry, onOpenSubject, Modifier.animateItem().testTag("subjectRow-${entry.id}"))
                }
            }
        }
    }
}

/** iOS shows a small "Refreshing…" line at the top of Marks while a refresh runs. */
@Composable
private fun RefreshingLine(modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Row(
        modifier.fillMaxWidth().padding(vertical = GradeySpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(Modifier.size(18.dp), color = secondary, strokeWidth = 2.dp)
        Text(stringResource(R.string.subjects_refreshing), style = GradeyType.subheadline, color = secondary)
    }
}

@Composable
private fun AdjustedLabel(text: String, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        GradeyIcon(HugeIcons.ViewOff, size = 13.dp, tint = secondary)
        Text(text, style = GradeyType.caption, color = secondary)
    }
}

/** 44×40 abbreviation badge, name, mark count with the latest mark, then the average and absence share. */
@Composable
private fun SubjectRow(entry: SubjectEntry, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val average = remember(entry.average, locale) { GradeMath.formattedAverage(entry.average, locale) }
    val count = entry.subject.marks.size
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val bandColor = entry.band.color()
    PlainRow(modifier, onClick = { onOpen(entry.id) }) {
        Box(
            Modifier.size(width = 44.dp, height = 40.dp).clip(RoundedCornerShape(GradeyRadius.sm)).background(entry.band.softColor()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                entry.abbreviation, Modifier.padding(horizontal = 2.dp), style = GradeyType.subheadlineBold, color = bandColor,
                maxLines = 1, overflow = TextOverflow.Clip, textAlign = TextAlign.Center,
                autoSize = if (entry.abbreviation.length > 3) AbbreviationAutoSize else null,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(entry.name, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                Text(
                    pluralStringResource(R.plurals.subjects_subject_mark_count, count, count),
                    Modifier.weight(1f, fill = false), style = GradeyType.subheadline, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                entry.latestMarkText?.let { InlinePill(it, entry.latestMarkBand.color()) }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(average, style = GradeyType.title3.tabular(), color = bandColor, maxLines = 1)
            entry.absence?.let { row ->
                Text(
                    stringResource(R.string.subjects_marks_row_absence_percent, row.absencePercentage),
                    style = GradeyType.captionSemibold.tabular(), color = secondary, maxLines = 1,
                )
            }
        }
        DisclosureIcon()
    }
}

// MARK: - Subject page

/** One subject (iOS `SubjectDetailView`): average, marks, "Try a mark" and the average over time. */
@Composable
private fun SubjectDetail(
    entry: SubjectEntry?,
    newMarkIDs: Set<String>,
    hasAbsenceAdjustment: Boolean,
    onBack: () -> Unit,
    onAskAI: (() -> Unit)?,
    predictSubjectAverage: (suspend (Subject, String, Int) -> Double?)?,
) {
    val locale = LocalConfiguration.current.locales[0]
    val listState = rememberLazyListState()
    val scrolled by remember(listState) { derivedStateOf { listState.isScrolled } }
    val unavailable = stringResource(R.string.subjects_detail_intelligence_subject_unavailable)
    Column(Modifier.fillMaxSize()) {
        GradeyTopBar(
            title = entry?.name ?: unavailable, scrolled = scrolled,
            onBack = onBack, backDescription = stringResource(R.string.subjects_back),
            actions = if (entry != null && onAskAI != null) ({ GradeyAIButton(onAskAI, stringResource(R.string.subjects_gradey_ai_title)) }) else null,
        )
        if (entry == null) {
            EmptyState(unavailable, Modifier.weight(1f).padding(top = GradeySpacing.xxl), icon = HugeIcons.Book02)
            return@Column
        }
        val untitled = stringResource(R.string.subjects_mark_untitled)
        val today = remember { LocalDate.now(SchoolZone) }
        val marks = remember(entry, locale, untitled, today) {
            val shortDates = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dM"), locale)
            val fullDates = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMy"), locale)
            val relative = RelativeDateTimeFormatter.getInstance(locale)
            markEntries(
                entry.subject, entry.prepared, locale, untitled, SchoolZone, today,
                formatDate = { (if (it.year == today.year) shortDates else fullDates).format(it) },
                formatRecent = { days -> relativeDays(relative, days) },
            )
        }
        val chart = remember(entry) { averageChartData(entry.prepared) }
        val lineColor = if (entry.band == GradeBand.NEUTRAL) MaterialTheme.colorScheme.primary else entry.band.color()
        GradeyLazyColumn(
            Modifier.weight(1f).testTag("subjectDetail"), state = listState, maxWidth = GradeyMetrics.narrowContentMaxWidth, itemSpacing = 0.dp,
        ) {
            item(key = "hero", contentType = "hero") {
                val count = entry.subject.marks.size
                val summary = listOfNotNull(
                    pluralStringResource(R.plurals.subjects_subject_mark_count, count, count),
                    entry.absence?.let { stringResource(R.string.subjects_detail_absence_percent, it.absencePercentage) },
                ).joinToString(" · ")
                PlainHeader(
                    label = stringResource(
                        if (entry.prepared.officialAverage != null) R.string.subjects_detail_intelligence_school_average
                        else R.string.subjects_detail_intelligence_calculated_average,
                    ),
                    value = remember(entry.average, locale) { GradeMath.formattedAverage(entry.average, locale) },
                    valueColor = entry.band.color(), summary = summary,
                    below = if (hasAbsenceAdjustment) ({
                        AdjustedLabel(stringResource(R.string.subjects_absence_override_adjusted), Modifier.testTag("subjectAbsenceAdjusted"))
                    }) else null,
                )
            }
            item(key = "marksTitle", contentType = "title") {
                PlainSectionTitle(stringResource(R.string.subjects_marks_section), Modifier.padding(top = GradeySpacing.xl, bottom = GradeySpacing.sm))
            }
            items(marks, key = { it.key }, contentType = { "mark" }) { mark ->
                MarkRow(mark, isNew = mark.key in newMarkIDs, untitled = untitled, modifier = Modifier.testTag("markRow-${mark.key}"))
            }
            item(key = "calculator", contentType = "calculator") {
                Column(Modifier.padding(top = GradeySpacing.xl)) {
                    PlainSectionTitle(stringResource(R.string.subjects_detail_calculator_section), Modifier.padding(bottom = GradeySpacing.sm))
                    PlainRow { TryAMark(entry.subject, entry.prepared, predictSubjectAverage, Modifier.weight(1f)) }
                }
            }
            if (chart != null) item(key = "chart", contentType = "chart") {
                Column(Modifier.padding(top = GradeySpacing.xl).testTag("subjectGradeTrendSection")) {
                    PlainSectionTitle(stringResource(R.string.subjects_detail_chart_title), Modifier.padding(bottom = GradeySpacing.sm))
                    PlainRow { AverageHistoryChart(chart, lineColor, Modifier.weight(1f).height(220.dp)) }
                    PlainRow(horizontalSpacing = GradeySpacing.sm) {
                        GradeyIcon(HugeIcons.ChartLineData01, size = 13.dp, tint = MaterialTheme.gradeyColors.secondaryLabel)
                        Text(
                            stringResource(R.string.subjects_detail_chart_source_local), Modifier.weight(1f),
                            style = GradeyType.caption, color = MaterialTheme.gradeyColors.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        chart.delta?.let { AverageDeltaChip(it) }
                    }
                }
            }
        }
    }
}

/** iOS `RelativeDateTimeFormatter` output for the last week: "today", "2 days ago". */
private fun relativeDays(formatter: RelativeDateTimeFormatter, days: Long): String = when {
    days == 0L -> formatter.format(RelativeDateTimeFormatter.Direction.THIS, RelativeDateTimeFormatter.AbsoluteUnit.DAY)
    days > 0L -> formatter.format(days.toDouble(), RelativeDateTimeFormatter.Direction.LAST, RelativeDateTimeFormatter.RelativeUnit.DAYS)
    else -> formatter.format(abs(days).toDouble(), RelativeDateTimeFormatter.Direction.NEXT, RelativeDateTimeFormatter.RelativeUnit.DAYS)
}

/**
 * One mark (iOS `MarkRow`): caption, the topic when it adds something, date and type, weight or points
 * chip and the mark badge. The row expands on tap only when its text is actually cut off.
 */
@Composable
private fun MarkRow(mark: MarkEntry, isNew: Boolean, untitled: String, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(mark.key) { mutableStateOf(false) }
    var captionCut by remember(mark.key) { mutableStateOf(false) }
    var topicCut by remember(mark.key) { mutableStateOf(false) }
    val canExpand = expanded || captionCut || topicCut
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(ExpandMillis), label = "markChevron")
    PlainRow(
        modifier.animateContentSize(tween(ExpandMillis)).semantics(mergeDescendants = true) {},
        onClick = if (canExpand) ({ expanded = !expanded }) else null,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    mark.caption ?: untitled, Modifier.alignByBaseline().weight(1f, fill = false),
                    style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) captionCut = it.hasVisualOverflow },
                )
                if (isNew) {
                    val label = stringResource(R.string.subjects_new_mark)
                    Box(
                        Modifier.alignBy { it.measuredHeight }.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape)
                            .clearAndSetSemantics { contentDescription = label },
                    )
                }
            }
            mark.topic?.let { topic ->
                Text(
                    topic, style = GradeyType.subheadline, color = secondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) topicCut = it.hasVisualOverflow },
                )
            }
            MarkMetadataLine(mark, secondary)
            when (val chip = mark.chip) {
                is MarkChip.Points -> StatusChip(chip.text, Modifier.padding(top = 2.dp), color = MaterialTheme.gradeyColors.teal)
                is MarkChip.Weight -> StatusChip(
                    stringResource(if (chip.estimated) R.string.subjects_detail_weight_estimated_decimal else R.string.subjects_detail_weight_decimal, chip.formatted),
                    Modifier.padding(top = 2.dp), color = MaterialTheme.colorScheme.secondary,
                )
                null -> Unit
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            GradeBadge(mark.markText, mark.band)
            if (canExpand) GradeyIcon(
                HugeIcons.ArrowDown01, Modifier.graphicsLayer { rotationZ = rotation }, size = 14.dp, tint = MaterialTheme.gradeyColors.tertiaryLabel,
            )
        }
    }
}

/** "2 days ago · 12. 11. · Písemka": the relative date in brand green for the last week, the date and the type. */
@Composable
private fun MarkMetadataLine(mark: MarkEntry, secondary: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        mark.recentText?.let { recent ->
            Text(recent, style = GradeyType.captionSemibold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            if (mark.dateText != null) Separator()
        }
        mark.dateText?.let { Text(it, style = GradeyType.caption.tabular(), color = secondary, maxLines = 1) }
        mark.typeNote?.let { note ->
            if (mark.recentText != null || mark.dateText != null) Separator()
            Text(note, Modifier.weight(1f, fill = false), style = GradeyType.caption, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun Separator() = Text("·", style = GradeyType.caption, color = MaterialTheme.gradeyColors.tertiaryLabel)

/**
 * "Try a mark" (iOS `TheoreticalCalculatorView`): a mark and its weight give the new average at once.
 * When the school allows it, its own prediction replaces the local estimate as soon as it arrives and
 * is compared with the school's average; the local estimate is compared with Gradey's calculation.
 */
@Composable
private fun TryAMark(
    subject: Subject,
    prepared: PreparedGradeCalculation,
    predictSubjectAverage: (suspend (Subject, String, Int) -> Double?)?,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    if (!prepared.canSimulate) {
        Text(stringResource(R.string.subjects_detail_simulator_unavailable), modifier, style = GradeyType.subheadline, color = secondary)
        return
    }
    var text by rememberSaveable(prepared.subjectID) { mutableStateOf("") }
    var weight by rememberSaveable(prepared.subjectID) { mutableIntStateOf(1) }
    val local = remember(prepared, text, weight) {
        GradeMath.parseMarkValue(text)?.let { value -> GradeSimulation.simulate(prepared, listOf(HypotheticalGrade(value = value, weight = weight))) }
    }
    val canAskSchool = remember(subject, prepared, predictSubjectAverage) {
        predictSubjectAverage != null && canAskSchoolPrediction(subject, prepared)
    }
    var school by remember(prepared.subjectID) { mutableStateOf<SchoolPrediction?>(null) }
    LaunchedEffect(prepared.revision, text, weight, canAskSchool, local != null) {
        val predict = predictSubjectAverage
        if (!canAskSchool || local == null || predict == null) return@LaunchedEffect
        delay(PredictionDebounceMillis)
        val average = try {
            predict(subject, text.trim(), weight)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (average != null && average.isFinite()) school = SchoolPrediction(text, weight, prepared.revision, average)
    }
    val exact = school?.takeIf { it.markText == text && it.weight == weight && it.revision == prepared.revision && local != null }?.average
    val estimated = exact ?: local?.estimatedAverage
    // Compare like with like: the school's answer against its own average, the local estimate against Gradey's.
    val difference = if (exact != null) prepared.officialAverage?.let { exact - it } else local?.difference

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            BrandTextField(
                value = text, onValueChange = { if (it.length <= 3) text = it }, modifier = Modifier.weight(1f).testTag("theoreticalMarkField"),
                placeholder = stringResource(R.string.subjects_detail_calculator_mark_placeholder), keyboardType = KeyboardType.Ascii,
            )
            Row(
                Modifier.clip(RoundedCornerShape(GradeyRadius.sm)).background(MaterialTheme.gradeyColors.tertiaryFill).padding(GradeySpacing.xs)
                    .testTag("theoreticalWeightStepper"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
            ) {
                StepButton(HugeIcons.MinusSign, stringResource(R.string.subjects_detail_weight_decrease), enabled = weight > 1) { weight = (weight - 1).coerceAtLeast(1) }
                Text(
                    stringResource(R.string.subjects_detail_weight, weight), style = GradeyType.subheadlineSemibold.tabular(),
                    color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                )
                StepButton(HugeIcons.Add01, stringResource(R.string.subjects_detail_weight_increase), enabled = weight < 10) { weight = (weight + 1).coerceAtMost(10) }
            }
        }
        if (estimated != null) {
            val average = remember(estimated, locale) { GradeMath.formattedAverage(estimated, locale) }
            val differenceText = when {
                difference == null -> null
                difference < -0.01 -> stringResource(R.string.subjects_detail_calculator_better, -difference)
                difference > 0.01 -> stringResource(R.string.subjects_detail_calculator_worse, difference)
                else -> stringResource(R.string.subjects_detail_calculator_same)
            }
            val differenceColor = when {
                difference == null -> secondary
                difference < -0.01 -> GradeBand.EXCELLENT.color()
                difference > 0.01 -> GradeBand.POOR.color()
                else -> secondary
            }
            val spoken = listOfNotNull(stringResource(R.string.subjects_detail_simulator_result, estimated), differenceText).joinToString(", ")
            Row(
                Modifier.clearAndSetSemantics { contentDescription = spoken }.testTag("theoreticalResultPanel"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
            ) {
                Text("→ $average", style = GradeyType.headline.tabular(), color = MaterialTheme.colorScheme.onSurface)
                if (differenceText != null) Text(differenceText, style = GradeyType.subheadlineSemibold.tabular(), color = differenceColor)
            }
        }
        calculationWarning(prepared.issues)?.let { Text(stringResource(it), style = GradeyType.caption, color = secondary) }
    }
}

/** The one caveat worth showing under the calculator, most serious first (iOS `calculationWarningKey`). */
private fun calculationWarning(issues: Set<GradeCalculationIssue>): Int? = when {
    GradeCalculationIssue.INCONSISTENT_OFFICIAL_AVERAGE in issues -> R.string.subjects_detail_intelligence_average_mismatch
    GradeCalculationIssue.CONFLICTING_DUPLICATE_IDS in issues || GradeCalculationIssue.INVALID_WEIGHTS in issues ||
        GradeCalculationIssue.EXCLUDED_MARKS in issues -> R.string.subjects_detail_intelligence_partial_grades
    GradeCalculationIssue.MODIFIER_MAPPING in issues -> R.string.subjects_detail_intelligence_modifier_estimate
    GradeCalculationIssue.MISSING_WEIGHTS in issues -> R.string.subjects_detail_intelligence_assumed_weights
    GradeCalculationIssue.INFERRED_WEIGHTS in issues -> R.string.subjects_detail_intelligence_inferred_weights
    else -> null
}

/** 44×44 stepper button on the secondary grouped background, radius 8 (iOS `stepButton`). */
@Composable
private fun StepButton(icon: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(GradeyMetrics.minTouch).clip(RoundedCornerShape(GradeyRadius.xs)).background(MaterialTheme.gradeyColors.secondaryGroupedBackground)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        GradeyIcon(icon, size = 16.dp, tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.gradeyColors.tertiaryLabel)
    }
}
