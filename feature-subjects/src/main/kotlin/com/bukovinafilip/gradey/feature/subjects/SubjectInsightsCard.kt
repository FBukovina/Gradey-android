package com.bukovinafilip.gradey.feature.subjects

import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.HugeIcon

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.SchoolInsightState
import com.bukovinafilip.gradey.domain.SubjectDetailInsights
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.ui.GradeySectionCard
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.delay

@Composable
internal fun SubjectInsightsCard(subject: Subject, schoolScope: String?, state: SchoolInsightState?,
    plannerItems: List<PersonalPlannerItem>, onAskAI: ((String) -> Unit)?, onAskTestAI: ((String, String) -> Unit)?) {
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(subject.id, schoolScope) {
        while (true) { now = Instant.now(); delay(30_000) }
    }
    val summary = remember(subject, schoolScope, state, plannerItems, now) { SubjectDetailInsights.make(subject, schoolScope, state, plannerItems, now) }
    var contributionExpanded by rememberSaveable(subject.id, summary.recentContribution?.markID) { mutableStateOf(false) }
    val locale = LocalConfiguration.current.locales[0]
    GradeySectionCard(title = stringResource(R.string.subject_insight_heading), modifier = Modifier.semantics { testTag = "subjectInsightSummary" }) {
        val delta = summary.trendDelta
        if (delta != null) {
            Text(stringResource(if (delta > 0) R.string.subject_insight_worsening else R.string.subject_insight_improving, kotlin.math.abs(delta)), fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.subject_insight_observed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(stringResource(if (summary.marksFresh) R.string.subject_insight_insufficient else R.string.subject_insight_refresh),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        summary.recentContribution?.let { contribution ->
            TextButton(onClick = { contributionExpanded = !contributionExpanded }, contentPadding = PaddingValues(vertical = 8.dp)) {
                Text(stringResource(R.string.subject_insight_contribution))
            }
            if (contributionExpanded) Text(stringResource(R.string.subject_insight_contribution_detail, contribution.markText, contribution.reconstructedDelta),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        onAskAI?.let { ask ->
            OutlinedButton(onClick = { ask(subject.id) }, modifier = Modifier.fillMaxWidth()) {
                HugeIcon(HugeIcons.Sparkles, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.subjects_review_this_subject_with_ai))
            }
        }
        summary.upcomingEvents.forEach { event ->
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                HugeIcon(HugeIcons.Calendar03, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(event.title, fontWeight = FontWeight.Medium)
                    val formatter = if (event.hasTime) DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                        else DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                    Text(formatter.withLocale(locale).format(event.date.atZone(ZoneId.of(event.zoneID))),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (event.type == PlannerItemType.TEST && onAskTestAI != null) {
                        TextButton(onClick = { onAskTestAI(subject.id, event.id) }, contentPadding = PaddingValues(vertical = 8.dp)) {
                            Text(stringResource(R.string.subject_insight_test_preparation))
                        }
                    }
                }
            }
        }
    }
}
