package com.bukovinafilip.gradey.feature.subjects

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.domain.GradeSimulation
import com.bukovinafilip.gradey.domain.ReportCardEstimate
import com.bukovinafilip.gradey.domain.ReportCardPeriod
import com.bukovinafilip.gradey.domain.ReportCardTerm
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.PlainHeader
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.SectionFooter
import com.bukovinafilip.gradey.ui.color
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.tabular
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

private val ReportCardZone: ZoneId = ZoneId.of("Europe/Prague")

/** Estimates for the semester that contains today, without a written report card. */
internal fun currentEstimates(subjects: List<Subject>, written: ReportCardTerm? = null): List<ReportCardEstimate> =
    ReportCardEstimate.estimates(subjects, subjects.associate { it.id to GradeSimulation.prepare(it) }, written)

@Composable
private fun termTitle(semester: String, schoolYear: String): String = stringResource(R.string.subjects_report_card_term, semester, schoolYear)

/**
 * The report card row on Marks (iOS `MarksReportCardRow`): the semester, the expected average and
 * how many subjects sit on the edge of another mark.
 */
@Composable
fun MarksReportCardRow(subjects: List<Subject>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val period = remember { ReportCardPeriod.containing(LocalDate.now(ReportCardZone)) }
    val estimates = remember(subjects) { currentEstimates(subjects) }
    val average = remember(estimates) { ReportCardEstimate.average(estimates) }
    val borderline = remember(estimates) { estimates.count { it.borderline != null } }
    val summary = listOfNotNull(
        termTitle(period.semester.toString(), period.schoolYear),
        average?.let { stringResource(R.string.subjects_report_card_row_estimate, GradeMath.formattedAverage(it, locale)) },
        if (borderline > 0) pluralStringResource(R.plurals.subjects_report_card_borderline_count, borderline, borderline) else null,
    ).joinToString(" · ")
    PlainRow(modifier.testTag("marksReportCardRow"), onClick = onClick) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(stringResource(R.string.subjects_report_card_title), style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            Text(summary, style = GradeyType.subheadline.tabular(), color = MaterialTheme.gradeyColors.secondaryLabel, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        DisclosureIcon()
    }
}

/**
 * Report card, pushed from Marks (iOS `ReportCardView`): the expected average for this semester, one
 * row per subject with its estimated or written mark, and the earlier report cards. Opening an
 * earlier one shows it in place; system Back returns to the estimate first.
 */
@Composable
fun ReportCardScreen(
    subjects: List<Subject>,
    loadCached: suspend () -> List<ReportCardTerm>,
    load: suspend () -> List<ReportCardTerm>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val scope = rememberCoroutineScope()
    var terms by remember { mutableStateOf<List<ReportCardTerm>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var openTermID by rememberSaveable { mutableStateOf<String?>(null) }
    val refresh: suspend () -> Unit = {
        if (!loading) {
            loading = true
            try { terms = load(); error = null }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message?.takeIf { it.isNotBlank() && it.length <= 300 } ?: "" }
            finally { loading = false }
        }
    }
    LaunchedEffect(Unit) {
        runCatching { loadCached() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { terms = it }
        refresh()
    }
    val period = remember { ReportCardPeriod.containing(LocalDate.now(ReportCardZone)) }
    val current = remember(terms, period) { terms.firstOrNull { it.matches(period) } }
    val past = remember(terms, period) { terms.filter { !it.matches(period) && it.marks.isNotEmpty() } }
    val estimates = remember(subjects, current) { currentEstimates(subjects, current) }
    val openTerm = remember(terms, openTermID) { terms.firstOrNull { it.id == openTermID } }
    BackHandler(enabled = openTerm != null) { openTermID = null }
    if (openTerm != null) {
        ReportCardTermScreen(openTerm, onBack = { openTermID = null }, modifier)
        return
    }
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Column(modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.subjects_report_card_title), scrolled = scrolled,
            onBack = onBack, backDescription = stringResource(R.string.subjects_back),
            onRefresh = { scope.launch { refresh() } }, isRefreshing = loading, refreshDescription = stringResource(R.string.subjects_action_refresh),
        )
        GradeyLazyColumn(Modifier.weight(1f).testTag("reportCard"), state = listState, itemSpacing = 0.dp) {
            item(key = "hero", contentType = "hero") {
                val average = remember(estimates) { ReportCardEstimate.average(estimates) }
                PlainHeader(
                    label = stringResource(R.string.subjects_report_card_hero_estimate), value = GradeMath.formattedAverage(average, locale),
                    valueColor = GradeMath.band(average).color(), summary = termTitle(period.semester.toString(), period.schoolYear),
                )
            }
            item(key = "subjectsTitle", contentType = "title") {
                PlainSectionTitle(stringResource(R.string.subjects_report_card_subjects), Modifier.padding(top = GradeySpacing.xl, bottom = GradeySpacing.sm))
            }
            items(estimates, key = { "estimate-${it.id}" }, contentType = { "estimate" }) { estimate -> EstimateRow(estimate, locale) }
            item(key = "estimateFooter", contentType = "footer") { SectionFooter(stringResource(R.string.subjects_report_card_estimate_footer)) }
            item(key = "pastTitle", contentType = "title") {
                PlainSectionTitle(stringResource(R.string.subjects_report_card_past), Modifier.padding(top = GradeySpacing.xl, bottom = GradeySpacing.sm))
            }
            if (past.isEmpty()) item(key = "pastState", contentType = "state") {
                Column(Modifier.padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    val message = error
                    when {
                        loading -> LoadingState(text = stringResource(R.string.subjects_report_card_past_loading))
                        message != null -> {
                            Text(message.ifEmpty { stringResource(R.string.subjects_error_title) }, style = GradeyType.subheadline, color = secondary)
                            PlainLink(stringResource(R.string.subjects_action_retry), { scope.launch { refresh() } }, showsArrow = false)
                        }
                        else -> Text(stringResource(R.string.subjects_report_card_past_empty), style = GradeyType.subheadline, color = secondary)
                    }
                }
            } else items(past, key = { "term-${it.id}" }, contentType = { "term" }) { term ->
                PlainRow(Modifier.testTag("reportCardTerm-${term.id}"), onClick = { openTermID = term.id }) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                        Text(termTitle(term.semester, term.schoolYear), style = GradeyType.headline.tabular(), color = MaterialTheme.colorScheme.onSurface)
                        term.achievementText?.let { Text(it, style = GradeyType.subheadline, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                    term.average?.let { Text(GradeMath.formattedAverage(it, locale), style = GradeyType.title3.tabular(), color = GradeMath.band(it).color()) }
                    DisclosureIcon()
                }
            }
        }
    }
}

/** Subject, its average, a borderline warning, and the written or estimated mark. */
@Composable
private fun EstimateRow(estimate: ReportCardEstimate, locale: Locale) {
    val markText = estimate.writtenMarkText ?: estimate.estimatedMark?.toString() ?: "–"
    val average = estimate.average?.let { stringResource(R.string.subjects_report_card_row_average, GradeMath.formattedAverage(it, locale)) }
    val detail = if (estimate.writtenMarkText != null) listOfNotNull(stringResource(R.string.subjects_report_card_row_written), average).joinToString(" · ")
        else average ?: stringResource(R.string.subjects_report_card_row_no_marks)
    PlainRow {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(estimate.subjectName, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(detail, style = GradeyType.subheadline.tabular(), color = MaterialTheme.gradeyColors.secondaryLabel)
            estimate.borderline?.let { edge ->
                val distance = GradeMath.formattedAverage(edge.distance, locale)
                Text(
                    stringResource(
                        when (edge.mark) {
                            1 -> R.string.subjects_report_card_borderline_1
                            2 -> R.string.subjects_report_card_borderline_2
                            3 -> R.string.subjects_report_card_borderline_3
                            4 -> R.string.subjects_report_card_borderline_4
                            else -> R.string.subjects_report_card_borderline_5
                        },
                        distance,
                    ),
                    style = GradeyType.captionSemibold.tabular(), color = MaterialTheme.gradeyColors.orange,
                )
            }
        }
        Text(markText, style = GradeyType.title2.tabular(), color = GradeMath.band(GradeMath.parseMarkValue(markText)).color(), maxLines = 1)
    }
}

/** One earlier report card as the school issued it (iOS `ReportCardTermView`). */
@Composable
private fun ReportCardTermScreen(term: ReportCardTerm, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val locale = LocalConfiguration.current.locales[0]
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    val hours = remember(locale) { NumberFormat.getNumberInstance(locale).apply { minimumFractionDigits = 0; maximumFractionDigits = 1 } }
    Column(modifier.fillMaxSize()) {
        GradeyTopBar(
            title = termTitle(term.semester, term.schoolYear), scrolled = scrolled,
            onBack = onBack, backDescription = stringResource(R.string.subjects_back),
        )
        GradeyLazyColumn(Modifier.weight(1f).testTag("reportCardTerm"), state = listState, itemSpacing = 0.dp) {
            item(key = "hero", contentType = "hero") {
                PlainHeader(
                    label = stringResource(R.string.subjects_report_card_hero_written), value = GradeMath.formattedAverage(term.average, locale),
                    valueColor = GradeMath.band(term.average).color(), summary = term.achievementText,
                )
            }
            item(key = "subjectsTitle", contentType = "title") {
                PlainSectionTitle(stringResource(R.string.subjects_report_card_subjects), Modifier.padding(top = GradeySpacing.xl, bottom = GradeySpacing.sm))
            }
            items(term.marks, key = { "mark-${it.id}" }, contentType = { "mark" }) { mark ->
                PlainRow {
                    Text(mark.subjectName, Modifier.weight(1f), style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(mark.markText, style = GradeyType.title3.tabular(), color = GradeMath.band(GradeMath.parseMarkValue(mark.markText)).color(), maxLines = 1)
                }
            }
            val absent = term.absentHours
            if (absent != null) item(key = "absence", contentType = "footer") {
                SectionFooter(stringResource(R.string.subjects_report_card_absence, hours.format(absent), hours.format(term.unexcusedHours ?: 0.0)))
            }
        }
    }
}
