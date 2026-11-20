package com.bukovinafilip.gradey.feature.planner

import android.util.Patterns
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.Homework
import com.bukovinafilip.gradey.domain.SchoolAttachment
import com.bukovinafilip.gradey.ui.DetailRow
import com.bukovinafilip.gradey.ui.EmptyState
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySheet
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.ModalCloseButton
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.SchoolAttachmentsSection
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.struck
import com.bukovinafilip.gradey.ui.tabular
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/** The words of a homework row and its detail, read once per language (iOS `HomeworkText`). */
@Immutable
class HomeworkLabels internal constructor(
    val school: String,
    private val dueToday: String,
    private val dueTomorrow: String,
    private val dueDate: String,
    locale: Locale,
) {
    private val weekday: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE", locale)

    /** "Thu 24. 10.", with the year when it is not this one. */
    fun weekdayShortDate(date: LocalDate, today: LocalDate): String = date.format(weekday).trimEnd('.') + " " + PersonalPlannerDates.shortDate(date, today)

    /** "Due today", "Due tomorrow" or "Due Thu 24. 10."; null for homework without a due day. */
    fun due(homework: Homework, today: LocalDate): String? = when (val day = homework.dueOn) {
        null -> null
        today -> dueToday
        today.plusDays(1) -> dueTomorrow
        else -> dueDate.format(weekdayShortDate(day, today))
    }

    /** The first line of the homework; its subject, or "School homework", when it has no text. */
    fun title(homework: Homework): String = homework.firstLine ?: homework.subjectName ?: school
}

@Composable
fun rememberHomeworkLabels(): HomeworkLabels {
    val resources = LocalResources.current
    val locale = LocalConfiguration.current.locales[0]
    return remember(resources, locale) {
        HomeworkLabels(
            school = resources.getString(R.string.homework_school),
            dueToday = resources.getString(R.string.homework_due_today),
            dueTomorrow = resources.getString(R.string.homework_due_tomorrow),
            dueDate = resources.getString(R.string.homework_due_date),
            locale = locale,
        )
    }
}

/**
 * A school homework in a list (iOS `HomeworkRow`): the done tick in a 40×44 target, then the summary
 * that opens it. Laid out like a Planner item, so both line up in the Planner.
 *
 * @param pending the tick is on its way to the school and cannot be changed again yet.
 * @param showsDue off in the Planner, whose day heading already says when it is due.
 */
@Composable
fun HomeworkRow(
    homework: Homework,
    pending: Boolean,
    onToggle: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    showsDue: Boolean = true,
    showDivider: Boolean = true,
    labels: HomeworkLabels = rememberHomeworkLabels(),
) {
    val title = labels.title(homework)
    Column(modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs)) {
        if (showDivider) Hairline()
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Box(
                Modifier.clickable(enabled = !pending, role = Role.Button, onClick = onToggle)
                    .padding(vertical = GradeySpacing.md)
                    .size(width = 40.dp, height = GradeyMetrics.minTouch)
                    .testTag("homeworkDone-${homework.id}"),
                contentAlignment = Alignment.CenterStart,
            ) {
                GradeyIcon(
                    if (homework.isDone) HugeIcons.CheckmarkCircle02 else HugeIcons.Circle,
                    size = 22.dp, tint = MaterialTheme.colorScheme.primary.copy(alpha = if (pending) 0.5f else 1f),
                    contentDescription = stringResource(if (homework.isDone) R.string.planner_mark_1_s_incomplete else R.string.planner_mark_1_s_complete, title),
                )
            }
            Row(
                Modifier.weight(1f).clickable(role = Role.Button, onClick = onOpen).padding(vertical = GradeySpacing.md).testTag("homeworkItem-${homework.id}"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
            ) {
                HomeworkSummary(homework, title, labels, showsDue, Modifier.weight(1f))
                GradeyIcon(HugeIcons.ArrowRight01, size = 14.dp, tint = MaterialTheme.gradeyColors.tertiaryLabel)
            }
        }
    }
}

/** Title, "School homework · subject" and the due day (iOS `HomeworkSummary`). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeworkSummary(homework: Homework, title: String, labels: HomeworkLabels, showsDue: Boolean, modifier: Modifier = Modifier) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val due = remember(homework.dueOn, labels, showsDue) { if (showsDue) labels.due(homework, LocalDate.now()) else null }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        Text(
            title, style = GradeyType.headline.struck(homework.isDone), color = MaterialTheme.colorScheme.onSurface,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm), verticalArrangement = Arrangement.spacedBy(2.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            HomeworkCaption(HugeIcons.Mortarboard02, labels.school)
            homework.subjectLabel?.let { Text(it, style = GradeyType.caption, color = secondary) }
            if (homework.attachments.isNotEmpty()) GradeyIcon(HugeIcons.Attachment01, size = 12.dp, tint = secondary)
        }
        if (due != null) HomeworkCaption(HugeIcons.Clock01, due)
    }
}

@Composable
private fun HomeworkCaption(icon: String, text: String) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        GradeyIcon(icon, size = 12.dp, tint = secondary)
        Text(text, style = GradeyType.caption.tabular(), color = secondary)
    }
}

/** Homework given in a lesson or due in it, for the lesson's detail sheet (iOS `homeworkSection`). */
@Composable
fun LessonHomeworkSection(
    items: List<Homework>,
    pendingIDs: Set<String>,
    onToggle: (Homework) -> Unit,
    onOpen: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = rememberHomeworkLabels()
    Column(modifier.fillMaxWidth().testTag("lessonHomeworkSection"), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        // The small header the sheet's other sections use (iOS `SettingsModalSectionHeader`).
        SectionHeader(stringResource(R.string.planner_homework), Modifier.padding(horizontal = GradeySpacing.xs))
        Column(Modifier.fillMaxWidth()) {
            items.forEach { homework ->
                key(homework.id) {
                    HomeworkRow(homework, pending = homework.id in pendingIDs, onToggle = { onToggle(homework) }, onOpen = { onOpen(homework.id) }, labels = labels)
                }
            }
        }
    }
}

/**
 * The whole homework in a sheet (iOS `HomeworkDetailView`): text with its links, teacher, dates,
 * attachments and the done tick. [homework] is null when the school no longer lists it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeworkDetailSheet(
    homework: Homework?,
    pending: Boolean,
    downloadingAttachmentID: String?,
    onToggle: (Homework) -> Unit,
    onOpenAttachment: (SchoolAttachment) -> Unit,
    onDismiss: () -> Unit,
) {
    val labels = rememberHomeworkLabels()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val linkColor = MaterialTheme.colorScheme.primary
    GradeySheet(onDismiss = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).testTag("homeworkDetail"),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                Text(
                    homework?.subjectLabel ?: stringResource(R.string.planner_homework),
                    Modifier.weight(1f).semantics { heading() }, style = GradeyType.title2, color = MaterialTheme.colorScheme.onSurface,
                )
                ModalCloseButton(
                    onClick = { scope.launch { sheetState.hide() }.invokeOnCompletion { if (!sheetState.isVisible) onDismiss() } },
                    contentDescription = stringResource(R.string.homework_done),
                )
            }
            if (homework == null) {
                EmptyState(title = stringResource(R.string.homework_unavailable), icon = HugeIcons.CheckList)
                return@Column
            }
            val text = remember(homework.content, labels, linkColor) { linkified(homework.content.ifEmpty { labels.title(homework) }, linkColor) }
            SelectionContainer {
                Text(text, Modifier.fillMaxWidth().testTag("homeworkDetailContent"), style = GradeyType.body, color = MaterialTheme.colorScheme.onSurface)
            }
            HomeworkDetails(homework, labels)
            if (homework.attachments.isNotEmpty()) SchoolAttachmentsSection(
                title = stringResource(R.string.homework_attachments), attachments = homework.attachments,
                downloadingID = downloadingAttachmentID, onOpen = onOpenAttachment, tagPrefix = "homeworkAttachment",
            )
            GradeyPrimaryButton(
                text = stringResource(if (homework.isDone) R.string.homework_mark_not_done else R.string.homework_mark_done),
                onClick = { onToggle(homework) }, modifier = Modifier.fillMaxWidth().testTag("homeworkDetailDoneButton"),
                enabled = !pending, icon = if (homework.isDone) HugeIcons.Undo02 else HugeIcons.Tick02,
            )
        }
    }
}

@Composable
private fun HomeworkDetails(homework: Homework, labels: HomeworkLabels) {
    val today = remember(homework) { LocalDate.now() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        homework.dueOn?.let { DetailRow(HugeIcons.Clock01, stringResource(R.string.homework_detail_due), labels.weekdayShortDate(it, today)) }
        homework.assignedOn?.let { DetailRow(HugeIcons.Calendar03, stringResource(R.string.homework_detail_assigned), labels.weekdayShortDate(it, today)) }
        homework.teacherName?.let { DetailRow(HugeIcons.User, stringResource(R.string.homework_detail_teacher), it) }
        homework.notice?.let { DetailRow(HugeIcons.Book02, stringResource(R.string.homework_detail_notice), it) }
        if (homework.isElectronic) DetailRow(HugeIcons.CheckList, stringResource(R.string.homework_detail_hand_in), stringResource(R.string.homework_detail_electronic))
        if (homework.isClosed) DetailRow(HugeIcons.SecurityLock, stringResource(R.string.homework_detail_status), stringResource(R.string.homework_closed))
    }
}

/** Homework text with its web links made tappable (iOS `HomeworkText.linkified`). */
private fun linkified(text: String, color: Color): AnnotatedString = buildAnnotatedString {
    append(text)
    val styles = TextLinkStyles(SpanStyle(color = color, textDecoration = TextDecoration.Underline))
    val matcher = Patterns.WEB_URL.matcher(text)
    while (matcher.find()) {
        val shown = matcher.group()
        // A link written without a scheme opens over https.
        val url = if (shown.contains("://")) shown else "https://$shown"
        addLink(LinkAnnotation.Url(url, styles), matcher.start(), matcher.end())
    }
}
