package com.bukovinafilip.gradey.feature.subjects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.automirrored.filled.TrendingDown
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.TrendingUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import com.bukovinafilip.gradey.ui.adaptiveColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.bukovinafilip.gradey.domain.AbsenceRiskSummary
import com.bukovinafilip.gradey.domain.GradeSimulation
import com.bukovinafilip.gradey.domain.GradeHistoryTrend
import com.bukovinafilip.gradey.domain.GradeBand
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.Subject
import java.text.Collator
import java.text.Normalizer
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException

private val BackgroundTop: Color
    @Composable get() = adaptiveColor(Color(0xFFCBDDDD), Color(0xFF17382D))
private val BackgroundBottom: Color
    @Composable get() = adaptiveColor(Color(0xFFEFEFF4), Color(0xFF0C1713))
private val AccentTeal: Color
    @Composable get() = MaterialTheme.colorScheme.primary
private val HeroStart = Color(0xFF18A182)
private val HeroEnd = Color(0xFF1CA567)
private val DetailHeroStart = Color(0xFF148A94)
private val DetailHeroEnd = Color(0xFF3099A1)
private val CardWhite: Color
    @Composable get() = adaptiveColor(Color(0xFFFDFDFE), Color(0xFF17261F))
private val MutedText: Color
    @Composable get() = adaptiveColor(Color(0xFF8E8E93), Color(0xFFB0C5B9))
private val DividerColor: Color
    @Composable get() = adaptiveColor(Color(0xFFC6C6C8), Color(0xFF344F40))
private val SoftTeal: Color
    @Composable get() = adaptiveColor(Color(0xFFDEEFF0), Color(0xFF244B46))
private val SoftMint: Color
    @Composable get() = adaptiveColor(Color(0xFFE0F3EA), Color(0xFF214F3D))
private val SoftGray: Color
    @Composable get() = adaptiveColor(Color(0xFFEEEEF0), Color(0xFF293D33))
private val ExcellentGreen = Color(0xFF18A56F)
private val WarningOrange = Color(0xFFE0921A)
private val DangerRed = Color(0xFFD95461)
private val PragueZone = ZoneId.of("Europe/Prague")

private enum class SubjectSortMode(val label: Int) {
    Focus(R.string.subjects_focus),
    Average(R.string.subjects_average),
    Alphabetical(R.string.subjects_a_z),
}

private enum class SubjectFilterMode(val label: Int) {
    All(R.string.subjects_all),
    WithAverage(R.string.subjects_with_average),
    WithoutAverage(R.string.subjects_no_average),
}

private data class SchoolPredictionRequest(val mark: String, val weight: Int, val attempt: Int)

@Composable
fun SubjectsScreen(
    subjects: List<Subject>,
    absence: AbsenceResponse,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenGradeyTools: () -> Unit,
    modifier: Modifier = Modifier,
    predictSubjectAverage: (suspend (Subject, String, Int) -> Double?)? = null,
    gradeTrends: List<GradeHistoryTrend> = emptyList(),
    onAskAI: ((String) -> Unit)? = null,
    initialSubjectID: String? = null,
    insightState: com.bukovinafilip.gradey.domain.SchoolInsightState? = null,
    plannerItems: List<com.bukovinafilip.gradey.model.PersonalPlannerItem> = emptyList(),
    schoolScope: String? = null,
    onAskTestAI: ((subjectID: String, eventID: String) -> Unit)? = null,
) {
    var selectedSubjectID by rememberSaveable(initialSubjectID) { mutableStateOf(initialSubjectID) }
    var sortMode by rememberSaveable { mutableStateOf(SubjectSortMode.Focus) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var filterMode by rememberSaveable { mutableStateOf(SubjectFilterMode.All) }
    val selectedSubject = subjects.firstOrNull { it.id == selectedSubjectID }

    LaunchedEffect(selectedSubjectID, subjects) {
        if (selectedSubjectID != null && selectedSubject == null) selectedSubjectID = null
    }
    BackHandler(enabled = selectedSubject != null) { selectedSubjectID = null }

    if (selectedSubject == null) {
        SubjectsOverview(
            subjects = subjects,
            absence = absence,
            gradeTrends = gradeTrends,
            sortMode = sortMode,
            searchQuery = searchQuery,
            onSearchQueryChange = { searchQuery = it },
            filterMode = filterMode,
            onFilterModeChange = { filterMode = it },
            onSortModeChange = { sortMode = it },
            isRefreshing = isRefreshing,
            onRefresh = onRefresh,
            onOpenAccount = onOpenAccount,
            onOpenGradeyTools = onOpenGradeyTools,
            onOpenSubject = { selectedSubjectID = it.id },
            modifier = modifier,
        )
    } else {
        SubjectDetail(
            subject = selectedSubject,
            absence = absence,
            onBack = { selectedSubjectID = null },
            modifier = modifier,
            predictSubjectAverage = predictSubjectAverage,
            onAskAI = onAskAI,
            insightState = insightState, plannerItems = plannerItems, schoolScope = schoolScope, onAskTestAI = onAskTestAI,
        )
    }
}

@Composable
private fun SubjectsOverview(
    subjects: List<Subject>,
    absence: AbsenceResponse,
    gradeTrends: List<GradeHistoryTrend>,
    sortMode: SubjectSortMode,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    filterMode: SubjectFilterMode,
    onFilterModeChange: (SubjectFilterMode) -> Unit,
    onSortModeChange: (SubjectSortMode) -> Unit,
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onOpenAccount: () -> Unit,
    onOpenGradeyTools: () -> Unit,
    onOpenSubject: (Subject) -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val displayLocale = LocalConfiguration.current.locales[0]
    val absenceRows = remember(absence) {
        AbsenceRiskSummary.make(absence, absence.absencesPerSubject).subjects.associateBy { it.subjectName.subjectKey() }
    }
    val trendsBySubject = remember(gradeTrends) { gradeTrends.associateBy { it.subjectID } }
    val sortedSubjects = remember(subjects, absenceRows, sortMode, trendsBySubject, displayLocale) {
        when (sortMode) {
            SubjectSortMode.Focus -> subjects.sortedWith(
                compareByDescending<Subject> {
                    val average = GradeMath.subjectAverage(it) ?: -1.0
                    val absencePercentage = absenceRows[it.displayName.subjectKey()]?.absencePercentage ?: 0.0
                    average + absencePercentage / 25.0 +
                        ((trendsBySubject[it.id]?.averageDelta ?: 0.0).coerceAtLeast(0.0) * 2) -
                        (if (it.marks.isEmpty()) 2.0 else 0.0)
                }.thenBy { it.displayName },
            )

            SubjectSortMode.Average -> subjects.sortedWith(
                compareBy<Subject> { GradeMath.subjectAverage(it) ?: Double.MAX_VALUE }.thenBy { it.displayName },
            )

            SubjectSortMode.Alphabetical -> {
                val collator = Collator.getInstance(displayLocale)
                subjects.sortedWith { first, second -> collator.compare(first.displayName, second.displayName) }
            }
        }
    }
    val visibleSubjects = remember(sortedSubjects, searchQuery, filterMode) {
        val query = searchQuery.subjectKey()
        sortedSubjects.filter { subject ->
            val matchesSearch = query.isEmpty() || subject.displayName.subjectKey().contains(query) ||
                subject.subjectInfo.abbrev.subjectKey().contains(query)
            val hasAverage = GradeMath.subjectAverage(subject) != null
            matchesSearch && when (filterMode) {
                SubjectFilterMode.All -> true
                SubjectFilterMode.WithAverage -> hasAverage
                SubjectFilterMode.WithoutAverage -> !hasAverage
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBottom),
    ) {
        MarksBackgroundGlow()
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, top = 13.dp, end = 16.dp, bottom = com.bukovinafilip.gradey.ui.floatingNavigationContentPadding()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                MarksHeader(
                    title = strings.getString(R.string.subjects_marks),
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    onOpenAccount = onOpenAccount,
                    onOpenGradeyTools = onOpenGradeyTools,
                )
            }
            item { OverallAverageCard(subjects) }
            val visibleTrends = if (searchQuery.isBlank() && filterMode == SubjectFilterMode.All) gradeTrends else gradeTrends.filter { trend ->
                visibleSubjects.any { it.id == trend.subjectID || it.displayName.subjectKey() == trend.subjectName.subjectKey() }
            }
            if (visibleTrends.isNotEmpty()) {
                item { GradeMovementCard(visibleTrends, subjects, onOpenSubject) }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubjectSearchField(searchQuery, onSearchQueryChange)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SubjectFilterMode.entries) { filter ->
                            FilterChip(
                                selected = filter == filterMode,
                                onClick = { onFilterModeChange(filter) },
                                label = { Text(strings.getString(filter.label)) },
                            )
                        }
                    }
                }
            }
            item {
                Column {
                    SubjectsSectionHeader(sortMode = sortMode, onSortModeChange = onSortModeChange)
                    Spacer(Modifier.height(12.dp))
                    SubjectsCard(
                        subjects = visibleSubjects,
                        absenceByName = absenceRows,
                        onOpenSubject = onOpenSubject,
                        emptyMessage = if (subjects.isEmpty()) strings.getString(R.string.subjects_no_subjects_available) else strings.getString(R.string.subjects_no_subjects_match_your_search_or_filter),
                    )
                }
            }
        }
    }
}


@Composable
private fun GradeMovementCard(trends: List<GradeHistoryTrend>, subjects: List<Subject>, onOpenSubject: (Subject) -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val visible = remember(trends) { trends.sortedByDescending { kotlin.math.abs(it.averageDelta) }.take(4) }
    Surface(Modifier.fillMaxWidth(), color = CardWhite, shape = RoundedCornerShape(20.dp), shadowElevation = 1.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionHeading(strings.getString(R.string.subjects_grade_movement))
                Text(strings.getString(R.string.subjects_changes_recorded_in_the_last_90_days), color = MutedText, fontSize = 13.sp)
            }
            visible.forEach { trend ->
                val subject = subjects.firstOrNull { it.id == trend.subjectID || it.displayName.equals(trend.subjectName, ignoreCase = true) }
                val delta = trend.averageDelta
                val improved = delta < -0.005
                val worsened = delta > 0.005
                val color = if (improved) ExcellentGreen else if (worsened) WarningOrange else MutedText
                Row(
                    modifier = Modifier.fillMaxWidth().then(if (subject != null) Modifier.clickable { onOpenSubject(subject) } else Modifier).padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(12.dp)) {
                        Box(Modifier.size(42.dp), contentAlignment = Alignment.Center) {
                            Icon(if (improved) Icons.AutoMirrored.Filled.TrendingDown else if (worsened) Icons.AutoMirrored.Filled.TrendingUp else Icons.AutoMirrored.Filled.TrendingFlat,
                                contentDescription = if (improved) strings.getString(R.string.subjects_average_improved) else if (worsened) strings.getString(R.string.subjects_average_increased) else strings.getString(R.string.subjects_average_unchanged), tint = color)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(subject?.displayName ?: trend.subjectName, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(when {
                            trend.markCountDelta > 0 -> strings.getQuantityString(R.plurals.subjects_added_marks, trend.markCountDelta, trend.markCountDelta)
                            trend.markCountDelta < 0 -> strings.getQuantityString(R.plurals.subjects_removed_marks, -trend.markCountDelta, -trend.markCountDelta)
                            else -> strings.getString(R.string.subjects_school_average_updated)
                        }, color = MutedText, fontSize = 13.sp)
                    }
                    Text(if (!improved && !worsened) strings.getString(R.string.subjects_no_change) else "${if (delta > 0) "+" else "−"}${formatAverage(kotlin.math.abs(delta))}", color = color, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SubjectSearchField(query: String, onQueryChange: (String) -> Unit) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    Surface(modifier = Modifier.fillMaxWidth(), color = CardWhite, shape = RoundedCornerShape(14.dp)) {
        Row(modifier = Modifier.heightIn(min = 52.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MutedText, modifier = Modifier.size(22.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp).semantics { contentDescription = strings.getString(R.string.subjects_search_subjects) },
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                cursorBrush = SolidColor(AccentTeal),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) Text(strings.getString(R.string.subjects_search_subjects), color = MutedText, fontSize = 16.sp)
                        field()
                    }
                },
            )
            IconButton(onClick = { onQueryChange("") }, enabled = query.isNotEmpty()) {
                Icon(Icons.Default.Close, contentDescription = strings.getString(R.string.subjects_clear_subject_search), tint = if (query.isEmpty()) Color.Transparent else MutedText)
            }
        }
    }
}

@Composable
private fun MarksHeader(
    title: String,
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
            color = adaptiveColor(Color(0xFFE8FAFB), Color(0xFF203E33)),
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    color = adaptiveColor(Color(0xFFC7ECE9), Color(0xFF2A5142)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = strings.getString(R.string.subjects_open_gradey_tools),
                            tint = AccentTeal,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        Text(
            text = title,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )

        Surface(
            modifier = Modifier
                .width(105.dp)
                .height(44.dp),
            shape = RoundedCornerShape(22.dp),
            color = adaptiveColor(Color(0xFFDCFAF6), Color(0xFF203E33)),
            shadowElevation = 2.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp),
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
                                contentDescription = strings.getString(R.string.subjects_refresh_marks),
                                tint = AccentTeal,
                                modifier = Modifier.size(27.dp),
                            )
                        }
                    }
                }
                Surface(
                    modifier = Modifier.size(32.dp),
                    onClick = onOpenAccount,
                    shape = CircleShape,
                    color = adaptiveColor(Color(0xFFBDECE4), Color(0xFF2A5142)),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = strings.getString(R.string.subjects_open_account),
                            tint = AccentTeal,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OverallAverageCard(subjects: List<Subject>) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val overallAverage = formatAverage(GradeMath.overallAverage(subjects))
    val totalMarks = subjects.sumOf { it.marks.size }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 124.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .background(Brush.horizontalGradient(listOf(HeroStart, HeroEnd)))
                .padding(horizontal = 24.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            Column {
                Text(
                    text = strings.getString(R.string.subjects_overall_average),
                    color = Color(0xFF073C35),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = overallAverage,
                    color = Color(0xFF001D19),
                    fontSize = 50.sp,
                    lineHeight = 55.sp,
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = strings.getQuantityString(R.plurals.subjects_count_subject, subjects.size, subjects.size),
                    color = Color(0xFF073C35),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = strings.getQuantityString(R.plurals.subjects_count_mark, totalMarks, totalMarks),
                    color = Color(0xFF073C35),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

@Composable
private fun SubjectsSectionHeader(
    sortMode: SubjectSortMode,
    onSortModeChange: (SubjectSortMode) -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionHeading(strings.getString(R.string.subjects_subjects))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            color = adaptiveColor(Color(0xFFD7E4E4), Color(0xFF253A30)).copy(alpha = 0.92f),
        ) {
            Row(modifier = Modifier.padding(3.dp)) {
                SubjectSortMode.entries.forEach { mode ->
                    SortSegment(Modifier.weight(1f), mode, sortMode == mode) { onSortModeChange(mode) }
                }
            }
        }
    }
}

@Composable
private fun SortSegment(
    modifier: Modifier,
    mode: SubjectSortMode,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    Surface(
        modifier = modifier.heightIn(min = 36.dp),
        onClick = onClick,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) MaterialTheme.colorScheme.surface else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp,
    ) {
        Box(Modifier.padding(horizontal = 4.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
            Text(
                text = strings.getString(mode.label),
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SubjectsCard(
    subjects: List<Subject>,
    absenceByName: Map<String, com.bukovinafilip.gradey.domain.AbsenceSubjectSummary>,
    onOpenSubject: (Subject) -> Unit,
    emptyMessage: String = androidx.compose.ui.res.stringResource(R.string.subjects_no_subjects_available),
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = CardWhite,
        shadowElevation = 1.dp,
    ) {
        if (subjects.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(68.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(emptyMessage, color = MutedText, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp), textAlign = TextAlign.Center)
            }
        } else {
            Column {
                subjects.forEachIndexed { index, subject ->
                    SubjectRow(
                        subject = subject,
                        absencePercentage = absenceByName[subject.displayName.subjectKey()]?.absencePercentage,
                        onClick = { onOpenSubject(subject) },
                    )
                    if (index != subjects.lastIndex) {
                        HorizontalDivider(
                            modifier = Modifier.padding(start = 68.dp),
                            thickness = 0.5.dp,
                            color = DividerColor.copy(alpha = 0.72f),
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SubjectRow(
    subject: Subject,
    absencePercentage: Double?,
    onClick: () -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val largeText = LocalDensity.current.fontScale > 1.2f
    val absenceLabel = absencePercentage?.let { strings.getString(R.string.subjects_1_d_absent, it.roundToInt()) } ?: strings.getString(R.string.subjects_absent)
    val average = GradeMath.subjectAverage(subject)
    val latestMark = subject.marks.maxByOrNull { it.markDate.orEmpty() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 7.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier
                .width(44.dp)
                .height(40.dp),
            shape = RoundedCornerShape(12.dp),
            color = SoftTeal,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = subject.subjectInfo.abbrev.ifBlank { subject.displayName.take(2) },
                    color = adaptiveColor(Color(0xFF108A94), Color(0xFF76CFD5)),
                    fontSize = 17.sp,
                    lineHeight = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = subject.displayName,
                maxLines = if (largeText) 2 else 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Bold,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val markCount = subject.marks.size
                Text(
                    text = strings.getQuantityString(R.plurals.subjects_count_mark, markCount, markCount),
                    color = MutedText,
                    fontSize = 16.sp,
                    lineHeight = 19.sp,
                )
                latestMark?.let {
                    GradePill(mark = it, compact = true)
                }
            }
            if (largeText) Text(absenceLabel, color = MutedText, fontSize = 13.sp, lineHeight = 17.sp)
        }
        Column(
            modifier = Modifier.padding(start = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = formatAverage(average),
                color = adaptiveColor(Color(0xFF108A94), Color(0xFF76CFD5)),
                fontSize = 20.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Bold,
            )
            if (!largeText) Text(
                text = absenceLabel,
                color = MutedText,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(4.dp))
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = strings.getString(R.string.subjects_open_1_s, subject.displayName),
            tint = Color(0xFFC7C7CC),
            modifier = Modifier.size(22.dp),
        )
    }
}

@Composable
private fun SubjectDetail(
    subject: Subject,
    absence: AbsenceResponse,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    predictSubjectAverage: (suspend (Subject, String, Int) -> Double?)? = null,
    onAskAI: ((String) -> Unit)? = null,
    insightState: com.bukovinafilip.gradey.domain.SchoolInsightState?,
    plannerItems: List<com.bukovinafilip.gradey.model.PersonalPlannerItem>,
    schoolScope: String?,
    onAskTestAI: ((String, String) -> Unit)?,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val preparedWeights = remember(subject) { GradeSimulation.prepare(subject).resolvedWeights }
    var showSimulator by rememberSaveable(subject.id) { mutableStateOf(false) }
    if (showSimulator) GradeSimulatorSheet(subject, onDismiss = { showSimulator = false })
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 60 }
    }
    val absenceRow = remember(subject.id, absence) {
        AbsenceRiskSummary.make(absence, absence.absencesPerSubject).subjects
            .firstOrNull { it.subjectName.subjectKey() == subject.displayName.subjectKey() }
    }
    var trialMark by rememberSaveable(subject.id) { mutableStateOf("") }
    var trialWeight by rememberSaveable(subject.id) { mutableIntStateOf(1) }
    val canPredict = !subject.pointsOnly && (subject.marks.isEmpty() || subject.marks.any {
        !it.isPoints && it.type != "unsupported" && GradeMath.parseMarkValue(it.markText) != null
    })
    val trialValue = remember(trialMark, canPredict) { if (canPredict) GradeMath.parseMarkValue(trialMark) else null }
    var predictionRequest by remember(subject.id) { mutableStateOf<SchoolPredictionRequest?>(null) }
    var schoolAverage by remember(subject.id, trialMark, trialWeight) { mutableStateOf<Double?>(null) }
    var isPredicting by remember(subject.id) { mutableStateOf(false) }
    var predictionError by remember(subject.id, trialMark, trialWeight) { mutableStateOf<String?>(null) }
    LaunchedEffect(subject, trialMark, trialWeight, predictionRequest) {
        schoolAverage = null
        predictionError = null
        isPredicting = false
        val requested = predictionRequest ?: return@LaunchedEffect
        val predict = predictSubjectAverage ?: return@LaunchedEffect
        if (requested.mark != trialMark || requested.weight != trialWeight || trialValue == null || !subject.markPredictionEnabled) return@LaunchedEffect
        isPredicting = true
        try {
            schoolAverage = predict(subject, trialMark.trim(), trialWeight)?.takeIf { it.isFinite() && it in 1.0..5.7 }
            if (schoolAverage == null) predictionError = strings.getString(R.string.subjects_the_school_did_not_return_a_prediction)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            predictionError = strings.getString(R.string.subjects_school_prediction_unavailable_showing_a_local_estimate)
        } finally {
            isPredicting = false
        }
    }
    val localAverage = remember(subject, trialValue, trialWeight) {
        trialValue?.let {
            GradeMath.theoreticalAverage(
                existingMarks = subject.marks,
                subjectAverageText = subject.averageText,
                markValue = it,
                weight = trialWeight,
            )
        } ?: GradeMath.subjectAverage(subject)
    }

    StatusBarAppearance(useDarkIcons = !isScrolled)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(BackgroundBottom),
    ) {
        MarksBackgroundGlow()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState,
            contentPadding = PaddingValues(start = 16.dp, top = 117.dp, end = 16.dp, bottom = com.bukovinafilip.gradey.ui.floatingNavigationContentPadding()),
        ) {
            item {
                SubjectAverageHero(
                    average = schoolAverage ?: localAverage,
                    predicted = trialValue != null,
                    predictionLabel = if (schoolAverage == null) strings.getString(R.string.subjects_estimated_average) else strings.getString(R.string.subjects_school_prediction),
                    markCount = subject.marks.size,
                    absencePercentage = absenceRow?.absencePercentage,
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                Surface(onClick = { showSimulator = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp), color = SoftTeal) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = AccentTeal)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(strings.getString(R.string.subjects_grade_simulator), color = MaterialTheme.colorScheme.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                            Text(strings.getString(R.string.subjects_reach_a_target_or_try_multiple_new), color = MutedText, fontSize = 13.sp)
                        }
                        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = AccentTeal)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
            item {
                SubjectInsightsCard(subject, schoolScope, insightState, plannerItems, onAskAI, onAskTestAI)
                Spacer(Modifier.height(16.dp))
            }
            item { SectionHeading(strings.getString(R.string.subjects_average_over_time)) }
            item { Spacer(Modifier.height(18.dp)) }
            item { AverageChartCard(subject) }
            item { Spacer(Modifier.height(10.dp)) }
            item { SectionHeading(strings.getString(R.string.subjects_try_a_mark)) }
            item { Spacer(Modifier.height(24.dp)) }
            item {
                TryMarkCard(
                    value = trialMark,
                    weight = trialWeight,
                    enabled = canPredict,
                    onValueChange = { trialMark = it.take(8) },
                    onDecreaseWeight = { trialWeight = (trialWeight - 1).coerceAtLeast(1) },
                    onIncreaseWeight = { trialWeight = (trialWeight + 1).coerceAtMost(10) },
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 10.dp)) {
                    Text(
                        text = when {
                            !canPredict -> strings.getString(R.string.subjects_this_calculator_supports_grades_from_1_to)
                            trialMark.isNotBlank() && trialValue == null -> strings.getString(R.string.subjects_enter_a_grade_from_1_to_5)
                            predictionError != null -> predictionError.orEmpty()
                            schoolAverage != null -> strings.getString(R.string.subjects_prediction_returned_by_your_school_this_does)
                            else -> strings.getString(R.string.subjects_local_estimate_from_available_grades_and_known)
                        },
                        color = if (predictionError != null || (trialMark.isNotBlank() && trialValue == null)) DangerRed else MutedText,
                        fontSize = 13.sp,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (subject.markPredictionEnabled && predictSubjectAverage != null && trialValue != null) {
                            Button(
                                onClick = { predictionRequest = SchoolPredictionRequest(trialMark, trialWeight, (predictionRequest?.attempt ?: 0) + 1) },
                                enabled = !isPredicting,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (isPredicting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(if (isPredicting) strings.getString(R.string.subjects_checking) else if (predictionError != null) strings.getString(R.string.subjects_retry_prediction) else strings.getString(R.string.subjects_check_school_prediction))
                            }
                        }
                        if (trialMark.isNotBlank()) {
                            TextButton(onClick = { trialMark = ""; predictionRequest = null }) { Text(strings.getString(R.string.subjects_clear)) }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeading(strings.getString(R.string.subjects_marks_2)) }
            item { Spacer(Modifier.height(25.dp)) }
            if (subject.marks.isEmpty()) {
                item { EmptyMarksCard() }
            } else {
                items(
                    items = subject.marks.sortedByDescending { parseMarkDate(it.markDate) },
                    key = Mark::id,
                ) { mark ->
                    MarkCard(mark = mark, weight = preparedWeights[mark.id]?.value)
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        SubjectDetailHeader(
            title = subject.displayName,
            isScrolled = isScrolled,
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopCenter),
        )
    }
}

@Composable
private fun SubjectDetailHeader(
    title: String,
    isScrolled: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(101.dp)
            .background(
                if (isScrolled) Color(0xE61A7B80) else Color.Transparent,
            ),
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 13.dp, start = 16.dp, end = 16.dp)
            .height(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(44.dp),
            onClick = onBack,
            shape = CircleShape,
            color = if (isScrolled) adaptiveColor(Color(0xFFA3E3E6), Color(0xFF1B4544)) else adaptiveColor(Color(0xFFE9FCFB), Color(0xFF203E33)),
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = strings.getString(R.string.subjects_back),
                    tint = adaptiveColor(Color(0xFF061C1B), Color(0xFFCFE9DE)),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            text = title,
            modifier = Modifier.padding(horizontal = 56.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isScrolled) Color.White else MaterialTheme.colorScheme.onSurface,
            fontSize = 17.sp,
            lineHeight = 21.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun SubjectAverageHero(
    average: Double?,
    predicted: Boolean,
    markCount: Int,
    absencePercentage: Double?,
    predictionLabel: String = androidx.compose.ui.res.stringResource(R.string.subjects_predicted_average),
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 194.dp),
        shape = RoundedCornerShape(20.dp),
        color = Color.Transparent,
        shadowElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .background(Brush.horizontalGradient(listOf(DetailHeroStart, DetailHeroEnd)))
                .padding(top = 24.dp, bottom = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = if (predicted) predictionLabel else strings.getString(R.string.subjects_average),
                color = Color.White,
                fontSize = 17.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = formatAverage(average),
                color = Color.White,
                fontSize = 66.sp,
                lineHeight = 70.sp,
                fontWeight = FontWeight.ExtraBold,
            )
            Spacer(Modifier.height(18.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                DetailChip(
                    icon = { Icon(Icons.Default.Verified, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    text = strings.getQuantityString(R.plurals.subjects_count_mark, markCount, markCount),
                )
                DetailChip(
                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    text = absencePercentage?.let { strings.getString(R.string.subjects_absence_1_s, formatOneDecimal(it)) } ?: strings.getString(R.string.subjects_absence),
                )
            }
        }
    }
}

@Composable
private fun DetailChip(
    icon: @Composable () -> Unit,
    text: String,
) {
    Surface(
        modifier = Modifier.heightIn(min = 28.dp),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = 0.22f),
        contentColor = Color.White,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            icon()
            Text(
                text = text,
                color = Color.White,
                fontSize = 14.sp,
                lineHeight = 17.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

private data class AveragePoint(
    val date: LocalDate?,
    val average: Double,
)

@Composable
private fun AverageChartCard(subject: Subject) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val chartGrid = MaterialTheme.colorScheme.outlineVariant
    val chartPoint = MaterialTheme.colorScheme.surface
    val points = remember(subject) { subject.averagePoints() }
    if (points.isEmpty()) {
        Surface(modifier = Modifier.fillMaxWidth().height(150.dp), shape = RoundedCornerShape(20.dp), color = CardWhite) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(strings.getString(R.string.subjects_no_grade_trend_yet), color = MutedText, fontSize = 16.sp)
                Text(strings.getString(R.string.subjects_a_trend_appears_when_numeric_grades_are), color = MutedText, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }
        return
    }
    val values = points.map(AveragePoint::average)
    val topValue = ((values.minOrNull()!! * 10.0).roundToInt() - 1) / 10.0
    val bottomValue = ((values.maxOrNull()!! * 10.0).roundToInt() + 1) / 10.0
    val safeBottomValue = if (bottomValue <= topValue) topValue + 0.2 else bottomValue
    val labels = if (points.size == 1) listOf(points.first().date?.shortDate() ?: strings.getString(R.string.subjects_date_unavailable))
        else listOf(points.first(), points[points.size / 2], points.last()).map { it.date?.shortDate() ?: "—" }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(237.dp)
            .semantics {
                contentDescription = strings.getString(R.string.subjects_average_over_time_1_d_data_points, points.size)
            },
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 1.dp,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val xStart = 16.dp.toPx()
                val xEnd = size.width - 36.dp.toPx()
                val yTop = 51.dp.toPx()
                val yBottom = 114.dp.toPx()
                drawLine(chartGrid, Offset(xStart, yTop), Offset(xEnd, yTop), strokeWidth = 0.7.dp.toPx())
                drawLine(chartGrid, Offset(xStart, yBottom), Offset(xEnd, yBottom), strokeWidth = 0.7.dp.toPx())

                val drawablePoints = points
                val offsets = drawablePoints.mapIndexed { index, point ->
                    val x = if (drawablePoints.size == 1) {
                        (xStart + xEnd) / 2f
                    } else {
                        xStart + (xEnd - xStart) * index.toFloat() / drawablePoints.lastIndex.toFloat()
                    }
                    val progress = ((point.average - topValue) / (safeBottomValue - topValue)).coerceIn(0.0, 1.0)
                    val y = yTop + (yBottom - yTop) * progress.toFloat()
                    Offset(x, y)
                }
                if (offsets.size > 1) {
                    val path = Path().apply {
                        moveTo(offsets.first().x, offsets.first().y)
                        offsets.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(
                        path = path,
                        color = Color(0xFF1396A0),
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
                offsets.forEach { point ->
                    drawCircle(chartPoint, radius = 6.dp.toPx(), center = point)
                    drawCircle(Color(0xFF1396A0), radius = 4.5.dp.toPx(), center = point)
                }
            }

            Text(
                text = formatOneDecimal(topValue),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 44.dp, end = 16.dp),
                color = MutedText,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
            Text(
                text = formatOneDecimal(safeBottomValue),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 107.dp, end = 16.dp),
                color = MutedText,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .offset(y = 170.dp)
                    .padding(start = 54.dp),
            ) {
                labels.forEach { label ->
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Text(label, color = MutedText, fontSize = 13.sp, lineHeight = 17.sp)
                    }
                }
            }
            Text(
                text = strings.getString(R.string.subjects_computed_from_marks),
                modifier = Modifier.offset(x = 16.dp, y = 194.dp),
                color = MutedText,
                fontSize = 13.sp,
                lineHeight = 17.sp,
            )
        }
    }
}

@Composable
private fun TryMarkCard(
    value: String,
    weight: Int,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onDecreaseWeight: () -> Unit,
    onIncreaseWeight: () -> Unit,
) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 138.dp),
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = enabled,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = strings.getString(R.string.subjects_trial_mark) }
                    .height(46.dp)
                    .background(SoftGray, RoundedCornerShape(13.dp))
                    .padding(horizontal = 13.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                cursorBrush = SolidColor(AccentTeal),
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                ),
                decorationBox = { field ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = if (enabled) strings.getString(R.string.subjects_mark_e_g_2) else strings.getString(R.string.subjects_prediction_unavailable),
                                color = Color(0xFFC4C4C8),
                                fontSize = 18.sp,
                                lineHeight = 22.sp,
                            )
                        }
                        field()
                    }
                },
            )
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .background(SoftGray, RoundedCornerShape(13.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                StepperButton(
                    enabled = enabled && weight > 1,
                    onClick = onDecreaseWeight,
                    contentDescription = strings.getString(R.string.subjects_decrease_weight),
                    tint = Color(0xFFD1D1D6),
                ) {
                    Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(22.dp))
                }
                Text(
                    text = strings.getString(R.string.subjects_weight_1_d, weight),
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
                StepperButton(
                    enabled = enabled && weight < 10,
                    onClick = onIncreaseWeight,
                    contentDescription = strings.getString(R.string.subjects_increase_weight),
                    tint = AccentTeal,
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(27.dp))
                }
            }
        }
    }
}

@Composable
private fun StepperButton(
    enabled: Boolean,
    onClick: () -> Unit,
    contentDescription: String,
    tint: Color,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = Modifier
            .width(36.dp)
            .height(34.dp)
            .semantics { this.contentDescription = contentDescription },
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(9.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = if (enabled) 0.96f else 0.64f),
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun MarkCard(mark: Mark, weight: Double?) {
    val strings = androidx.compose.ui.platform.LocalResources.current
    val displayLocale = LocalConfiguration.current.locales[0]
    val date = parseMarkDate(mark.markDate)
    val caption = mark.caption?.takeIf(String::isNotBlank) ?: mark.type?.takeIf(String::isNotBlank) ?: strings.getString(R.string.subjects_mark)
    val detail = mark.theme?.takeIf(String::isNotBlank) ?: mark.typeNote?.takeIf(String::isNotBlank) ?: ""

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 112.dp),
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = caption,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = detail,
                    color = MutedText,
                    fontSize = 17.sp,
                    lineHeight = 20.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = relativeDate(date, strings),
                        color = AccentTeal,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = date?.fullDate().orEmpty(),
                        color = MutedText,
                        fontSize = 13.sp,
                        lineHeight = 17.sp,
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TagPill(text = caption, color = adaptiveColor(Color(0xFFF0F0F2), Color(0xFF293D33)), textColor = MutedText)
                    TagPill(
                        text = weight?.let { strings.getString(R.string.subjects_weight_1_s, GradeMath.formattedWeight(it, displayLocale)) } ?: strings.getString(R.string.subjects_not_included_in_average),
                        color = SoftMint,
                        textColor = ExcellentGreen,
                    )
                }
            }
            GradePill(mark = mark, compact = false)
        }
    }
}

@Composable
private fun EmptyMarksCard() {
    val strings = androidx.compose.ui.platform.LocalResources.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(strings.getString(R.string.subjects_no_marks_yet), color = MutedText, fontSize = 15.sp)
        }
    }
}

@Composable
private fun GradePill(mark: Mark, compact: Boolean) {
    val (background, foreground) = mark.gradeColors()
    Surface(
        modifier = if (compact) Modifier.heightIn(min = 23.dp) else Modifier.width(76.dp).heightIn(min = 39.dp),
        shape = RoundedCornerShape(if (compact) 12.dp else 16.dp),
        color = background,
    ) {
        Box(
            modifier = if (compact) Modifier.padding(horizontal = 10.dp) else Modifier,
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = mark.markText,
                color = foreground,
                fontSize = if (compact) 14.sp else 22.sp,
                lineHeight = if (compact) 17.sp else 26.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun TagPill(text: String, color: Color, textColor: Color) {
    Surface(
        modifier = Modifier.heightIn(min = 23.dp),
        shape = RoundedCornerShape(12.dp),
        color = color,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                color = textColor,
                fontSize = 13.sp,
                lineHeight = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun SectionHeading(text: String) {
    Text(
        text = text,
        color = MutedText,
        fontSize = 14.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.7.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
private fun StatusBarAppearance(useDarkIcons: Boolean) {
    val isLight = MaterialTheme.colorScheme.background.luminance() >= 0.5f
    val view = LocalView.current
    val activity = androidx.activity.compose.LocalActivity.current
    if (activity != null && !view.isInEditMode) {
        SideEffect {
            WindowCompat.getInsetsController(activity.window, view).isAppearanceLightStatusBars = useDarkIcons && isLight
        }
        DisposableEffect(isLight) {
            onDispose {
                WindowCompat.getInsetsController(activity.window, view).isAppearanceLightStatusBars = isLight
            }
        }
    }
}

@Composable
private fun MarksBackgroundGlow() {
    val glowTop = BackgroundTop
    val glowBottom = BackgroundBottom
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to glowTop,
                    0.32f to glowBottom,
                    1f to glowBottom,
                ),
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x35309C89), Color.Transparent),
                center = Offset(size.width, size.height * 0.24f),
                radius = size.width * 0.75f,
            ),
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color(0x265EAEB5), Color.Transparent),
                center = Offset(0f, size.height * 0.59f),
                radius = size.width * 0.70f,
            ),
        )
    }
}

private fun Subject.averagePoints(): List<AveragePoint> {
    val ordered = GradeSimulation.prepare(this).marks.sortedWith(compareBy({ it.date ?: LocalDate.MIN }, { it.id }))
    var sum = 0.0
    var weight = 0.0
    return ordered.map { mark ->
        sum += mark.value * mark.weight.value
        weight += mark.weight.value
        AveragePoint(mark.date, sum / weight)
    }
}

@Composable
private fun Mark.gradeColors(): Pair<Color, Color> = when (GradeMath.band(this)) {
    GradeBand.EXCELLENT -> SoftMint to ExcellentGreen
    GradeBand.GOOD -> SoftTeal to adaptiveColor(Color(0xFF108A94), Color(0xFF76CFD5))
    GradeBand.AVERAGE -> adaptiveColor(Color(0xFFFFF0D7), Color(0xFF493C21)) to WarningOrange
    GradeBand.POOR -> adaptiveColor(Color(0xFFFFE5E8), Color(0xFF4B2A30)) to DangerRed
    GradeBand.NEUTRAL -> SoftGray to MutedText
}

private fun parseMarkDate(raw: String?): LocalDate? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { LocalDate.parse(value.substringBefore('T')) }.getOrNull()
}

private fun relativeDate(date: LocalDate?, strings: android.content.res.Resources): String {
    date ?: return strings.getString(R.string.subjects_date_unavailable)
    val days = ChronoUnit.DAYS.between(date, LocalDate.now(PragueZone)).coerceAtLeast(0)
    return when {
        days == 0L -> strings.getString(R.string.subjects_today)
        days == 1L -> strings.getString(R.string.subjects_yesterday)
        days < 7L -> strings.getQuantityString(R.plurals.subjects_days_ago, days.toInt(), days.toInt())
        days < 30L -> strings.getQuantityString(R.plurals.subjects_weeks_ago, (days / 7).toInt(), (days / 7).toInt())
        days < 60L -> strings.getString(R.string.subjects_1_month_ago)
        days < 365L -> strings.getQuantityString(R.plurals.subjects_months_ago, (days / 30).toInt(), (days / 30).toInt())
        else -> strings.getQuantityString(R.plurals.subjects_years_ago, (days / 365).toInt(), (days / 365).toInt())
    }
}

private fun LocalDate.shortDate(): String = "$dayOfMonth. $monthValue."

private fun LocalDate.fullDate(): String = "$dayOfMonth. $monthValue. $year"

private fun String.subjectKey(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), "")

@Composable
internal fun formatAverage(average: Double?): String =
    GradeMath.formattedAverage(average, LocalConfiguration.current.locales[0])

@Composable
private fun formatOneDecimal(value: Double): String =
    String.format(LocalConfiguration.current.locales[0], "%.1f", value)

private fun plural(count: Int, singular: String, plural: String): String =
    if (count == 1) singular else plural
