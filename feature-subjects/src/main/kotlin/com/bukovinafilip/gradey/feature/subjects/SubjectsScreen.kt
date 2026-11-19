package com.bukovinafilip.gradey.feature.subjects

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.bukovinafilip.gradey.ui.GradeyColors
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
import java.time.temporal.ChronoUnit
import java.util.Locale

private val SchoolZone: ZoneId = ZoneId.of("Europe/Prague")
private const val PushMillis = 320

/**
 * Marks tab (iOS 2.8.1 `SubjectsView`): the overall average, the report card and Recap rows, then the
 * subjects sorted by Priority or Average. Tapping a subject pushes its page inside this tab; system
 * Back returns to the list. The shell draws the background and the bottom bar.
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
    LaunchedEffect(initialSubjectID) {
        if (initialSubjectID != null) { selectedID = initialSubjectID; onInitialSubjectHandled?.invoke() }
    }
    val entries = remember(subjects, absence) { subjectEntries(subjects, absence) }
    val insights = remember(insightState, schoolScope) { insightState?.takeIf { it.scope == schoolScope } }
    val scores = remember(entries, insights, plannerItems, schoolScope) { priorityScores(entries, insights, plannerItems, schoolScope) }
    val sortMode = SubjectSortMode.entries[sortIndex.coerceIn(0, SubjectSortMode.entries.lastIndex)]
    val sorted = remember(entries, sortMode, scores, locale) { sortedEntries(entries, sortMode, scores, locale) }
    val newMarkIDs = remember(insights) {
        insights?.observations.orEmpty().filter { it.seenAt == null }.flatMapTo(HashSet()) { it.addedMarkIDs }
    }
    val activeOverrides = remember(absenceOverrides) { absenceOverrides.active() }
    val selected = remember(entries, selectedID) { entries.firstOrNull { it.id == selectedID } }
    BackHandler(enabled = selectedID != null) { selectedID = null }

    AnimatedContent(
        targetState = selectedID, modifier = modifier.fillMaxSize(), label = "marks",
        transitionSpec = {
            val spec = tween<IntOffset>(PushMillis, easing = FastOutSlowInEasing)
            if (targetState != null) slideInHorizontally(spec) { it } togetherWith (slideOutHorizontally(spec) { -it / 4 } + fadeOut(tween(PushMillis)))
            else (slideInHorizontally(spec) { -it / 4 } + fadeIn(tween(PushMillis))) togetherWith slideOutHorizontally(spec) { it }
        },
    ) { subjectID ->
        if (subjectID == null) MarksList(
            entries = sorted, overall = remember(entries) { overallAverage(entries) }, totalMarks = remember(entries) { entries.sumOf { it.subject.marks.size } },
            sortIndex = sortIndex, onSortChange = { sortIndex = it }, isRefreshing = isRefreshing, errorMessage = errorMessage,
            hasAbsenceAdjustments = activeOverrides.isNotEmpty(), onRefresh = onRefresh, onOpenAI = onOpenAI, onOpenSettings = onOpenSettings,
            onOpenSubject = { selectedID = it }, reportCardRow = reportCardRow, recapTile = recapTile,
        ) else SubjectDetail(
            entry = selected.takeIf { it?.id == subjectID } ?: entries.firstOrNull { it.id == subjectID },
            newMarkIDs = newMarkIDs, hasAbsenceAdjustment = remember(selected, activeOverrides) { hasLocalAbsenceAdjustment(selected?.absence, activeOverrides) },
            onBack = { selectedID = null }, onAskAI = onAskAI?.let { ask -> { ask(subjectID) } },
        )
    }
}

// MARK: - List

@Composable
private fun MarksList(
    entries: List<SubjectEntry>,
    overall: Double?,
    totalMarks: Int,
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
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    Column(Modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.subjects_subjects_title), scrolled = scrolled,
            onOpenAI = onOpenAI, aiDescription = stringResource(R.string.subjects_gradey_ai_title),
            onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = stringResource(R.string.subjects_action_refresh),
            onOpenSettings = onOpenSettings, settingsDescription = stringResource(R.string.subjects_settings_title),
        )
        when {
            entries.isEmpty() && isRefreshing -> LoadingState(Modifier.weight(1f).padding(top = GradeySpacing.xxl).testTag("subjectsLoadingView"), stringResource(R.string.subjects_subjects_loading))
            entries.isEmpty() && errorMessage != null -> ErrorState(
                errorMessage, Modifier.weight(1f).padding(top = GradeySpacing.xxl).testTag("subjectsErrorView"),
                title = stringResource(R.string.subjects_error_title), retryLabel = stringResource(R.string.subjects_action_retry), onRetry = onRefresh,
            )
            entries.isEmpty() -> EmptyState(
                stringResource(R.string.subjects_subjects_empty_title), Modifier.weight(1f).padding(top = GradeySpacing.xxl).testTag("subjectsEmptyView"),
                message = stringResource(R.string.subjects_subjects_empty_message), icon = HugeIcons.LeftToRightListBullet,
            )
            else -> GradeyLazyColumn(Modifier.weight(1f).testTag("subjectsList"), state = listState, itemSpacing = 0.dp) {
                item(key = "hero", contentType = "hero") {
                    val average = remember(overall, locale) { GradeMath.formattedAverage(overall, locale) }
                    PlainHeader(
                        label = stringResource(R.string.subjects_marks_hero_average), value = average, valueColor = GradeMath.band(overall).color(),
                        summary = pluralStringResource(R.plurals.subjects_marks_hero_subject_count, entries.size, entries.size) + " · " +
                            pluralStringResource(R.plurals.subjects_subject_mark_count, totalMarks, totalMarks),
                        modifier = Modifier.testTag("marksHeroCard"),
                    )
                }
                if (reportCardRow != null) item(key = "reportCard", contentType = "reportCard") { Box(Modifier.padding(top = GradeySpacing.xl)) { reportCardRow() } }
                if (recapTile != null) item(key = "recap", contentType = "recap") { Box(Modifier.padding(top = GradeySpacing.xl)) { recapTile() } }
                if (hasAbsenceAdjustments) item(key = "adjusted", contentType = "note") {
                    AdjustedLabel(
                        stringResource(R.string.subjects_absence_title) + " · " + stringResource(R.string.subjects_absence_override_adjusted),
                        Modifier.padding(top = GradeySpacing.xl, start = GradeySpacing.xs).testTag("subjectsAbsenceAdjusted"),
                    )
                }
                item(key = "directoryTitle", contentType = "title") {
                    PlainSectionTitle(
                        stringResource(R.string.subjects_marks_directory_title), Modifier.padding(top = GradeySpacing.xl, bottom = GradeySpacing.sm),
                        trailing = {
                            SegmentedControl(
                                options = listOf(stringResource(R.string.subjects_marks_sort_focus), stringResource(R.string.subjects_marks_sort_average)),
                                selectedIndex = sortIndex, onSelect = onSortChange, modifier = Modifier.widthIn(max = 220.dp).testTag("subjectsSortPicker"),
                            )
                        },
                    )
                }
                items(entries, key = { it.id }, contentType = { "subject" }) { entry ->
                    SubjectRow(entry, onClick = { onOpenSubject(entry.id) }, Modifier.animateItem().testTag("subjectRow-${entry.id}"))
                }
            }
        }
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
private fun SubjectRow(entry: SubjectEntry, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val average = remember(entry.average, locale) { GradeMath.formattedAverage(entry.average, locale) }
    val count = entry.subject.marks.size
    PlainRow(modifier, onClick = onClick) {
        Box(
            Modifier.size(width = 44.dp, height = 40.dp).clip(RoundedCornerShape(GradeyRadius.sm)).background(entry.band.softColor()),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                entry.abbreviation, style = GradeyType.subheadlineBold, color = entry.band.color(), maxLines = 1,
                overflow = TextOverflow.Clip, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 2.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(entry.name, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                Text(
                    pluralStringResource(R.plurals.subjects_subject_mark_count, count, count),
                    style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel, maxLines = 1,
                )
                entry.latestMarkText?.let { InlinePill(it, entry.latestMarkBand.color()) }
            }
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(average, style = GradeyType.title3.tabular(), color = entry.band.color(), maxLines = 1)
            entry.absence?.let { row ->
                Text(
                    stringResource(R.string.subjects_marks_row_absence_percent, row.absencePercentage),
                    style = GradeyType.captionSemibold.tabular(), color = MaterialTheme.gradeyColors.secondaryLabel, maxLines = 1,
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
) {
    val locale = LocalConfiguration.current.locales[0]
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    val untitled = stringResource(R.string.subjects_mark_untitled)
    Column(Modifier.fillMaxSize()) {
        GradeyTopBar(
            title = entry?.name ?: stringResource(R.string.subjects_subjects_title), scrolled = scrolled,
            onBack = onBack, backDescription = stringResource(R.string.subjects_back),
            actions = if (entry != null && onAskAI != null) ({ GradeyAIButton(onAskAI, stringResource(R.string.subjects_gradey_ai_title)) }) else null,
        )
        if (entry == null) {
            EmptyState(stringResource(R.string.subjects_detail_intelligence_subject_unavailable), Modifier.weight(1f).padding(top = GradeySpacing.xxl), icon = HugeIcons.Book02)
            return@Column
        }
        val marks = remember(entry, locale, untitled) { markEntries(entry.subject, entry.prepared, locale, untitled, SchoolZone) }
        val chart = remember(entry) { averageChartData(entry.prepared) }
        val today = remember { LocalDate.now(SchoolZone) }
        val shortDates = remember(locale) { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dM"), locale) }
        val fullDates = remember(locale) { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "dMy"), locale) }
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
                MarkRow(
                    mark, isNew = mark.key in newMarkIDs, untitled = untitled, today = today,
                    dateText = remember(mark.date, today, shortDates) {
                        mark.date?.let { (if (it.year == today.year) shortDates else fullDates).format(it) }
                    },
                    Modifier.testTag("markRow-${mark.key}"),
                )
            }
            item(key = "calculator", contentType = "calculator") {
                Column(Modifier.padding(top = GradeySpacing.xl), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    PlainSectionTitle(stringResource(R.string.subjects_detail_calculator_section))
                    TryAMark(entry.prepared, Modifier.padding(horizontal = GradeySpacing.xs))
                }
            }
            if (chart != null) item(key = "chart", contentType = "chart") {
                Column(Modifier.padding(top = GradeySpacing.xl).testTag("subjectGradeTrendSection"), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    PlainSectionTitle(stringResource(R.string.subjects_detail_chart_title))
                    AverageHistoryChart(chart, entry.band.color(), Modifier.fillMaxWidth().height(220.dp).padding(horizontal = GradeySpacing.xs))
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
                    ) {
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

/**
 * One mark (iOS `MarkRow`): caption, the topic when it adds something, date and type, weight chip and
 * the mark badge. The row expands on tap only when its text is actually cut off.
 */
@Composable
private fun MarkRow(mark: MarkEntry, isNew: Boolean, untitled: String, today: LocalDate, dateText: String?, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(mark.key) { mutableStateOf(false) }
    var captionCut by remember(mark.key) { mutableStateOf(false) }
    var topicCut by remember(mark.key) { mutableStateOf(false) }
    val canExpand = expanded || captionCut || topicCut
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val daysAgo = mark.date?.let { ChronoUnit.DAYS.between(it, today).toInt() }
    val rotation by animateFloatAsState(if (expanded) 180f else 0f, tween(250), label = "markChevron")
    PlainRow(
        modifier.animateContentSize(tween(250)), onClick = if (canExpand) ({ expanded = !expanded }) else null,
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    mark.caption ?: untitled, Modifier.weight(1f, fill = false), style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) captionCut = it.hasVisualOverflow },
                )
                if (isNew) {
                    val label = stringResource(R.string.subjects_new_mark)
                    Box(Modifier.size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape).clearAndSetSemantics { contentDescription = label })
                }
            }
            mark.topic?.let { topic ->
                Text(
                    topic, style = GradeyType.subheadline, color = secondary,
                    maxLines = if (expanded) Int.MAX_VALUE else 2, overflow = TextOverflow.Ellipsis,
                    onTextLayout = { if (!expanded) topicCut = it.hasVisualOverflow },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (daysAgo != null && daysAgo in 0..6) {
                    Text(
                        when (daysAgo) {
                            0 -> stringResource(R.string.subjects_today)
                            1 -> stringResource(R.string.subjects_yesterday)
                            else -> pluralStringResource(R.plurals.subjects_days_ago, daysAgo, daysAgo)
                        },
                        style = GradeyType.captionSemibold, color = MaterialTheme.colorScheme.primary, maxLines = 1,
                    )
                    if (dateText != null) Separator()
                }
                if (dateText != null) Text(dateText, style = GradeyType.caption.tabular(), color = secondary, maxLines = 1)
                mark.typeNote?.let { note ->
                    Separator()
                    Text(note, Modifier.weight(1f, fill = false), style = GradeyType.caption, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
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

@Composable
private fun Separator() = Text("·", style = GradeyType.caption, color = MaterialTheme.gradeyColors.tertiaryLabel)

/** "Try a mark" (iOS `TheoreticalCalculatorView`): a mark and its weight give the new average at once. */
@Composable
private fun TryAMark(prepared: PreparedGradeCalculation, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    if (!prepared.canSimulate) {
        Text(stringResource(R.string.subjects_detail_simulator_unavailable), modifier, style = GradeyType.subheadline, color = secondary)
        return
    }
    var text by rememberSaveable(prepared.subjectID) { mutableStateOf("") }
    var weight by rememberSaveable(prepared.subjectID) { mutableIntStateOf(1) }
    val result = remember(prepared, text, weight) {
        GradeMath.parseMarkValue(text)?.let { value -> GradeSimulation.simulate(prepared, listOf(HypotheticalGrade(value = value, weight = weight))) }
    }
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
                StepButton(HugeIcons.MinusSign, stringResource(R.string.subjects_detail_weight_decrease), enabled = weight > 1) { weight -= 1 }
                Text(stringResource(R.string.subjects_detail_weight, weight), style = GradeyType.subheadlineSemibold.tabular(), color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
                StepButton(HugeIcons.Add01, stringResource(R.string.subjects_detail_weight_increase), enabled = weight < 10) { weight += 1 }
            }
        }
        if (result != null) {
            val average = remember(result, locale) { GradeMath.formattedAverage(result.estimatedAverage, locale) }
            val difference = result.difference
            val differenceText = when {
                difference == null -> null
                difference < -0.01 -> stringResource(R.string.subjects_detail_calculator_better, -difference)
                difference > 0.01 -> stringResource(R.string.subjects_detail_calculator_worse, difference)
                else -> stringResource(R.string.subjects_detail_calculator_same)
            }
            val differenceColor = when {
                difference == null -> secondary
                difference < -0.01 -> GradeBand.EXCELLENT.color()
                difference > 0.01 -> GradeyColors.Poor
                else -> secondary
            }
            val spoken = listOfNotNull(stringResource(R.string.subjects_detail_simulator_result, result.estimatedAverage), differenceText).joinToString()
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
