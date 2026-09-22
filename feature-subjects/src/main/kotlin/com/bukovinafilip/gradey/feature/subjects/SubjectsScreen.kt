package com.bukovinafilip.gradey.feature.subjects

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
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

private val BackgroundTop = Color(0xFFCBDDDD)
private val BackgroundBottom = Color(0xFFEFEFF4)
private val AccentTeal = Color(0xFF17A185)
private val HeroStart = Color(0xFF18A182)
private val HeroEnd = Color(0xFF1CA567)
private val DetailHeroStart = Color(0xFF148A94)
private val DetailHeroEnd = Color(0xFF3099A1)
private val CardWhite = Color(0xFFFDFDFE)
private val MutedText = Color(0xFF8E8E93)
private val DividerColor = Color(0xFFC6C6C8)
private val SoftTeal = Color(0xFFDEEFF0)
private val SoftMint = Color(0xFFE0F3EA)
private val SoftGray = Color(0xFFEEEEF0)
private val ExcellentGreen = Color(0xFF18A56F)
private val WarningOrange = Color(0xFFE0921A)
private val DangerRed = Color(0xFFD95461)
private val PragueZone = ZoneId.of("Europe/Prague")

private enum class SubjectSortMode(val label: String) {
    Focus("Focus"),
    Average("Average"),
    Alphabetical("A-Z"),
}

private enum class SubjectFilterMode(val label: String) {
    All("All"),
    WithAverage("With average"),
    WithoutAverage("No average"),
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
) {
    var selectedSubjectID by rememberSaveable { mutableStateOf<String?>(null) }
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
        )
    }
}

@Composable
private fun SubjectsOverview(
    subjects: List<Subject>,
    absence: AbsenceResponse,
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
    val absenceRows = remember(absence) {
        AbsenceRiskSummary.make(absence, absence.absencesPerSubject).subjects.associateBy { it.subjectName.subjectKey() }
    }
    val sortedSubjects = remember(subjects, absenceRows, sortMode) {
        when (sortMode) {
            SubjectSortMode.Focus -> subjects.sortedWith(
                compareByDescending<Subject> {
                    val average = GradeMath.subjectAverage(it) ?: -1.0
                    val absencePercentage = absenceRows[it.displayName.subjectKey()]?.absencePercentage ?: 0.0
                    average + absencePercentage / 100.0
                }.thenBy { it.displayName },
            )

            SubjectSortMode.Average -> subjects.sortedWith(
                compareBy<Subject> { GradeMath.subjectAverage(it) ?: Double.MAX_VALUE }.thenBy { it.displayName },
            )

            SubjectSortMode.Alphabetical -> {
                val collator = Collator.getInstance(Locale.forLanguageTag("cs-CZ"))
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
            contentPadding = PaddingValues(start = 16.dp, top = 13.dp, end = 16.dp, bottom = 126.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                MarksHeader(
                    title = "Marks",
                    isRefreshing = isRefreshing,
                    onRefresh = onRefresh,
                    onOpenAccount = onOpenAccount,
                    onOpenGradeyTools = onOpenGradeyTools,
                )
            }
            item { OverallAverageCard(subjects) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubjectSearchField(searchQuery, onSearchQueryChange)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SubjectFilterMode.entries) { filter ->
                            FilterChip(
                                selected = filter == filterMode,
                                onClick = { onFilterModeChange(filter) },
                                label = { Text(filter.label) },
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
                        emptyMessage = if (subjects.isEmpty()) "No subjects available" else "No subjects match your search or filter",
                    )
                }
            }
        }
    }
}

@Composable
private fun SubjectSearchField(query: String, onQueryChange: (String) -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth(), color = CardWhite, shape = RoundedCornerShape(14.dp)) {
        Row(modifier = Modifier.heightIn(min = 52.dp).padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = null, tint = MutedText, modifier = Modifier.size(22.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp).semantics { contentDescription = "Search subjects" },
                singleLine = true,
                textStyle = TextStyle(color = Color.Black, fontSize = 16.sp),
                cursorBrush = SolidColor(AccentTeal),
                decorationBox = { field ->
                    Box {
                        if (query.isEmpty()) Text("Search subjects", color = MutedText, fontSize = 16.sp)
                        field()
                    }
                },
            )
            IconButton(onClick = { onQueryChange("") }, enabled = query.isNotEmpty()) {
                Icon(Icons.Default.Close, contentDescription = "Clear subject search", tint = if (query.isEmpty()) Color.Transparent else MutedText)
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
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(44.dp),
            onClick = onOpenGradeyTools,
            shape = CircleShape,
            color = Color(0xFFE8FAFB),
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(30.dp),
                    shape = CircleShape,
                    color = Color(0xFFC7ECE9),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "Open Gradey tools",
                            tint = AccentTeal,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
        }

        Text(
            text = title,
            color = Color.Black,
            fontSize = 18.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.SemiBold,
        )

        Surface(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .width(105.dp)
                .height(44.dp),
            shape = RoundedCornerShape(22.dp),
            color = Color(0xFFDCFAF6),
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
                                contentDescription = "Refresh Marks",
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
                    color = Color(0xFFBDECE4),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "Open account",
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
    val overallAverage = formatAverage(GradeMath.overallAverage(subjects))
    val totalMarks = subjects.sumOf { it.marks.size }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(124.dp),
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
                    text = "Overall average",
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
                    text = "${subjects.size} ${plural(subjects.size, "subject", "subjects")}",
                    color = Color(0xFF073C35),
                    fontSize = 14.sp,
                    lineHeight = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "$totalMarks ${plural(totalMarks, "mark", "marks")}",
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(31.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        SectionHeading("SUBJECTS")
        Surface(
            modifier = Modifier
                .width(173.dp)
                .height(31.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFFD7E4E4).copy(alpha = 0.92f),
        ) {
            Row(modifier = Modifier.padding(1.dp)) {
                SortSegment(
                    modifier = Modifier.width(55.dp),
                    mode = SubjectSortMode.Focus,
                    selected = sortMode == SubjectSortMode.Focus,
                    onClick = { onSortModeChange(SubjectSortMode.Focus) },
                )
                SortSegment(
                    modifier = Modifier.width(73.dp),
                    mode = SubjectSortMode.Average,
                    selected = sortMode == SubjectSortMode.Average,
                    onClick = { onSortModeChange(SubjectSortMode.Average) },
                )
                SortSegment(
                    modifier = Modifier.width(43.dp),
                    mode = SubjectSortMode.Alphabetical,
                    selected = sortMode == SubjectSortMode.Alphabetical,
                    onClick = { onSortModeChange(SubjectSortMode.Alphabetical) },
                )
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
    Surface(
        modifier = modifier.height(29.dp),
        onClick = onClick,
        shape = RoundedCornerShape(15.dp),
        color = if (selected) Color.White else Color.Transparent,
        shadowElevation = if (selected) 1.dp else 0.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = mode.label,
                color = Color.Black,
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
    emptyMessage: String = "No subjects available",
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

@Composable
private fun SubjectRow(
    subject: Subject,
    absencePercentage: Double?,
    onClick: () -> Unit,
) {
    val average = GradeMath.subjectAverage(subject)
    val latestMark = subject.marks.maxByOrNull { it.markDate.orEmpty() }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(68.dp)
            .clickable(onClick = onClick)
            .padding(start = 12.dp, end = 7.dp),
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
                    color = Color(0xFF108A94),
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
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.Black,
                fontSize = 18.sp,
                lineHeight = 21.sp,
                fontWeight = FontWeight.Bold,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val markCount = subject.marks.size
                Text(
                    text = "$markCount ${plural(markCount, "mark", "marks")}",
                    color = MutedText,
                    fontSize = 16.sp,
                    lineHeight = 19.sp,
                )
                latestMark?.let {
                    Spacer(Modifier.width(8.dp))
                    GradePill(mark = it, compact = true)
                }
            }
        }
        Column(
            modifier = Modifier.width(80.dp),
            horizontalAlignment = Alignment.End,
        ) {
            Text(
                text = formatAverage(average),
                color = Color(0xFF108A94),
                fontSize = 20.sp,
                lineHeight = 23.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = absencePercentage?.let { "${it.roundToInt()}% absent" } ?: "— absent",
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
            contentDescription = "Open ${subject.displayName}",
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
) {
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
            if (schoolAverage == null) predictionError = "The school did not return a prediction. Showing a local estimate."
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            predictionError = "School prediction unavailable. Showing a local estimate; try again when connected."
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
            contentPadding = PaddingValues(start = 16.dp, top = 117.dp, end = 16.dp, bottom = 126.dp),
        ) {
            item {
                SubjectAverageHero(
                    average = schoolAverage ?: localAverage,
                    predicted = trialValue != null,
                    predictionLabel = if (schoolAverage == null) "Estimated average" else "School prediction",
                    markCount = subject.marks.size,
                    absencePercentage = absenceRow?.absencePercentage,
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
            item { SectionHeading("AVERAGE OVER TIME") }
            item { Spacer(Modifier.height(18.dp)) }
            item { AverageChartCard(subject) }
            item { Spacer(Modifier.height(10.dp)) }
            item { SectionHeading("TRY A MARK") }
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
                            !canPredict -> "This calculator supports grades from 1 to 5. Points and other grading systems are excluded."
                            trialMark.isNotBlank() && trialValue == null -> "Enter a grade from 1 to 5, optionally with + or -."
                            predictionError != null -> predictionError.orEmpty()
                            schoolAverage != null -> "Prediction returned by your school. This does not add a real mark."
                            else -> "Local estimate from available grades and known or inferred weights. Your school's result may differ. No real mark is added."
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
                                Text(if (isPredicting) "Checking…" else if (predictionError != null) "Retry prediction" else "Check school prediction")
                            }
                        }
                        if (trialMark.isNotBlank()) {
                            TextButton(onClick = { trialMark = ""; predictionRequest = null }) { Text("Clear") }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
            item { SectionHeading("MARKS") }
            item { Spacer(Modifier.height(25.dp)) }
            if (subject.marks.isEmpty()) {
                item { EmptyMarksCard() }
            } else {
                items(
                    items = subject.marks.sortedByDescending { parseMarkDate(it.markDate) },
                    key = Mark::id,
                ) { mark ->
                    MarkCard(subject = subject, mark = mark)
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
            color = if (isScrolled) Color(0xFFA3E3E6) else Color(0xFFE9FCFB),
            shadowElevation = 2.dp,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Default.ChevronLeft,
                    contentDescription = "Back",
                    tint = Color(0xFF061C1B),
                    modifier = Modifier.size(28.dp),
                )
            }
        }
        Text(
            text = title,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (isScrolled) Color.White else Color.Black,
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
    predictionLabel: String = "Predicted average",
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(194.dp),
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
                text = if (predicted) predictionLabel else "Average",
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
                    text = "$markCount ${plural(markCount, "mark", "marks")}",
                )
                DetailChip(
                    icon = { Icon(Icons.Default.CalendarMonth, contentDescription = null, modifier = Modifier.size(14.dp)) },
                    text = absencePercentage?.let { "Absence ${formatOneDecimal(it)} %" } ?: "Absence —",
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
        modifier = Modifier.height(28.dp),
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
    val points = remember(subject) { subject.averagePoints() }
    if (points.isEmpty()) {
        Surface(modifier = Modifier.fillMaxWidth().height(150.dp), shape = RoundedCornerShape(20.dp), color = CardWhite) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("No grade trend yet", color = MutedText, fontSize = 16.sp)
                Text("A trend appears when numeric grades are available.", color = MutedText, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
            }
        }
        return
    }
    val values = points.map(AveragePoint::average)
    val topValue = ((values.minOrNull()!! * 10.0).roundToInt() - 1) / 10.0
    val bottomValue = ((values.maxOrNull()!! * 10.0).roundToInt() + 1) / 10.0
    val safeBottomValue = if (bottomValue <= topValue) topValue + 0.2 else bottomValue
    val labels = if (points.size == 1) listOf(points.first().date?.shortDate() ?: "Date unavailable")
        else listOf(points.first(), points[points.size / 2], points.last()).map { it.date?.shortDate() ?: "—" }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(237.dp)
            .semantics {
                contentDescription = "Average over time, ${points.size} ${plural(points.size, "point", "points")}"
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
                drawLine(Color(0xFFE6E6E8), Offset(xStart, yTop), Offset(xEnd, yTop), strokeWidth = 0.7.dp.toPx())
                drawLine(Color(0xFFE6E6E8), Offset(xStart, yBottom), Offset(xEnd, yBottom), strokeWidth = 0.7.dp.toPx())

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
                    drawCircle(Color.White, radius = 6.dp.toPx(), center = point)
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
                text = "Computed from marks",
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(138.dp),
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
                    .semantics { contentDescription = "Trial mark" }
                    .height(46.dp)
                    .background(SoftGray, RoundedCornerShape(13.dp))
                    .padding(horizontal = 13.dp),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                cursorBrush = SolidColor(AccentTeal),
                textStyle = TextStyle(
                    color = Color.Black,
                    fontSize = 18.sp,
                    lineHeight = 22.sp,
                ),
                decorationBox = { field ->
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        if (value.isEmpty()) {
                            Text(
                                text = if (enabled) "Mark, e.g. 2+" else "Prediction unavailable",
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
                    contentDescription = "Decrease weight",
                    tint = Color(0xFFD1D1D6),
                ) {
                    Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(22.dp))
                }
                Text(
                    text = "Weight $weight",
                    color = Color.Black,
                    fontSize = 17.sp,
                    lineHeight = 21.sp,
                    fontWeight = FontWeight.Bold,
                )
                StepperButton(
                    enabled = enabled && weight < 10,
                    onClick = onIncreaseWeight,
                    contentDescription = "Increase weight",
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
        color = Color.White.copy(alpha = if (enabled) 0.96f else 0.64f),
        contentColor = tint,
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

@Composable
private fun MarkCard(subject: Subject, mark: Mark) {
    val date = parseMarkDate(mark.markDate)
    val caption = mark.caption?.takeIf(String::isNotBlank) ?: mark.type?.takeIf(String::isNotBlank) ?: "Mark"
    val detail = mark.theme?.takeIf(String::isNotBlank) ?: mark.typeNote?.takeIf(String::isNotBlank) ?: ""
    val weight = GradeMath.resolvedWeight(mark, subject).value

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp),
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
                    color = Color.Black,
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
                        text = relativeDate(date),
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
                    TagPill(text = caption, color = Color(0xFFF0F0F2), textColor = MutedText)
                    TagPill(
                        text = "Weight ${GradeMath.formattedWeight(weight)}×",
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
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp),
        shape = RoundedCornerShape(20.dp),
        color = CardWhite,
        shadowElevation = 1.dp,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text("No marks yet", color = MutedText, fontSize = 15.sp)
        }
    }
}

@Composable
private fun GradePill(mark: Mark, compact: Boolean) {
    val (background, foreground) = mark.gradeColors()
    Surface(
        modifier = if (compact) Modifier.height(23.dp) else Modifier.size(width = 76.dp, height = 39.dp),
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
        modifier = Modifier.height(23.dp),
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
    val view = LocalView.current
    val activity = LocalContext.current as? Activity
    if (activity != null && !view.isInEditMode) {
        SideEffect {
            WindowCompat.getInsetsController(activity.window, view).isAppearanceLightStatusBars = useDarkIcons
        }
        DisposableEffect(Unit) {
            onDispose {
                WindowCompat.getInsetsController(activity.window, view).isAppearanceLightStatusBars = true
            }
        }
    }
}

@Composable
private fun MarksBackgroundGlow() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to BackgroundTop,
                    0.32f to BackgroundBottom,
                    1f to BackgroundBottom,
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
    val ordered = marks
        .filter { !it.isPoints && it.type != "unsupported" && GradeMath.parseMarkValue(it.markText) != null }
        .sortedWith(compareBy<Mark> { parseMarkDate(it.markDate) ?: LocalDate.MIN }.thenBy(Mark::id))
    return ordered.mapIndexedNotNull { index, mark ->
        GradeMath.weightedAverage(ordered.take(index + 1), this)?.let { average ->
            AveragePoint(parseMarkDate(mark.markDate), average)
        }
    }
}

private fun Mark.gradeColors(): Pair<Color, Color> = when (GradeMath.band(this)) {
    GradeBand.EXCELLENT -> SoftMint to ExcellentGreen
    GradeBand.GOOD -> SoftTeal to Color(0xFF108A94)
    GradeBand.AVERAGE -> Color(0xFFFFF0D7) to WarningOrange
    GradeBand.POOR -> Color(0xFFFFE5E8) to DangerRed
    GradeBand.NEUTRAL -> SoftGray to MutedText
}

private fun parseMarkDate(raw: String?): LocalDate? {
    val value = raw?.trim()?.takeIf(String::isNotEmpty) ?: return null
    return runCatching { LocalDate.parse(value.substringBefore('T')) }.getOrNull()
}

private fun relativeDate(date: LocalDate?): String {
    date ?: return "Date unavailable"
    val days = ChronoUnit.DAYS.between(date, LocalDate.now(PragueZone)).coerceAtLeast(0)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Yesterday"
        days < 7L -> "$days days ago"
        days < 30L -> "${days / 7} ${plural((days / 7).toInt(), "week", "weeks")} ago"
        days < 60L -> "1 month ago"
        days < 365L -> "${days / 30} months ago"
        else -> "${days / 365} ${plural((days / 365).toInt(), "year", "years")} ago"
    }
}

private fun LocalDate.shortDate(): String = "$dayOfMonth. $monthValue."

private fun LocalDate.fullDate(): String = "$dayOfMonth. $monthValue. $year"

private fun String.subjectKey(): String = Normalizer.normalize(this, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "")
    .lowercase(Locale.ROOT)
    .replace(Regex("[^a-z0-9]+"), "")

private fun formatAverage(average: Double?): String =
    GradeMath.formattedAverage(average).replace('.', ',')

private fun formatOneDecimal(value: Double): String =
    String.format(Locale.US, "%.1f", value).replace('.', ',')

private fun plural(count: Int, singular: String, plural: String): String =
    if (count == 1) singular else plural
