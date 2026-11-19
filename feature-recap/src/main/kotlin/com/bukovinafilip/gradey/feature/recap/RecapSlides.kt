package com.bukovinafilip.gradey.feature.recap

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.Recap
import com.bukovinafilip.gradey.domain.RecapKind
import com.bukovinafilip.gradey.domain.RecapMoment
import com.bukovinafilip.gradey.domain.RecapReportCard
import com.bukovinafilip.gradey.domain.RecapSlide
import com.bukovinafilip.gradey.domain.RecapSubjectStat
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.RecapColors
import com.bukovinafilip.gradey.ui.RecapLogo
import com.bukovinafilip.gradey.ui.tabular
import kotlin.math.roundToInt

private val Ink = RecapColors.Ink
private val Soft = Ink.copy(alpha = 0.8f)
private val Softer = Ink.copy(alpha = 0.75f)
private val Faint = Ink.copy(alpha = 0.7f)
private val Rule = Ink.copy(alpha = 0.18f)

private val Title2Semibold = GradeyType.title2.copy(fontWeight = FontWeight.SemiBold)
private val Title3Semibold = GradeyType.title3.copy(fontWeight = FontWeight.SemiBold)
private val Title3Medium = GradeyType.title3.copy(fontWeight = FontWeight.Medium)

/** The word under a big number sits closer to it than the slide's regular spacing (iOS `padding(.top, -Spacing.md)`). */
private val NounLift = -GradeySpacing.md

/** A display line that shrinks to stay within its lines (iOS `minimumScaleFactor`). */
private fun shrinking(style: TextStyle, minimumScale: Float): TextAutoSize = TextAutoSize.StepBased(style.fontSize * minimumScale, style.fontSize)

/** True when the system's animations are switched off (iOS Reduce Motion). */
@Composable
internal fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * One slide's content, without the story chrome (iOS `RecapSlideView`). The story and the share
 * image both draw it; [animated] counts the big numbers up.
 */
@Composable
internal fun RecapSlideContent(recap: Recap, slide: RecapSlide, texts: RecapTexts, modifier: Modifier = Modifier, animated: Boolean = true) {
    CompositionLocalProvider(LocalContentColor provides Ink) {
        Column(modifier.fillMaxSize().semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg)) {
            when (slide) {
                RecapSlide.INTRO -> Intro(recap, texts)
                RecapSlide.MARKS -> Marks(recap, texts, animated)
                RecapSlide.AVERAGE -> Average(recap, texts, animated)
                RecapSlide.TOP_SUBJECT -> SubjectSlide(texts.string(R.string.recap_top_kicker), recap.topSubject, null, texts)
                RecapSlide.TOUGHEST_SUBJECT -> SubjectSlide(texts.string(R.string.recap_toughest_kicker), recap.toughestSubject, texts.string(R.string.recap_toughest_note), texts)
                RecapSlide.MOMENTS -> Moments(recap, texts)
                RecapSlide.ABSENCE -> Absence(recap, texts, animated)
                RecapSlide.TIMETABLE -> Timetable(recap, texts, animated)
                RecapSlide.PERSONALITY -> Personality(recap, texts)
                RecapSlide.REPORT_CARD -> ReportCard(recap, texts, animated)
                RecapSlide.LAST_YEAR -> LastYear(recap, texts)
                RecapSlide.SUMMARY -> Summary(recap, texts)
            }
        }
    }
}

// Slides

@Composable
private fun ColumnScope.Intro(recap: Recap, texts: RecapTexts) {
    RecapLogo(Modifier.semantics { heading() }, size = 30.dp, showsGradey = true)
    Spacer(Modifier.weight(1f))
    val headline = GradeyType.display(54)
    Text(texts.headline(recap.period), style = headline, autoSize = shrinking(headline, 0.5f), maxLines = 3)
    Text(texts.title(recap.period), style = Title2Semibold.tabular(), color = Softer)
    if (recap.isOngoing) Note(texts.string(R.string.recap_intro_ongoing))
    if (recap.springFromReportCard) Note(texts.string(R.string.recap_intro_spring_from_report_card))
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.Marks(recap: Recap, texts: RecapTexts, animated: Boolean) {
    Kicker(texts.string(R.string.recap_marks_kicker))
    Text(texts.string(R.string.recap_marks_headline), style = Title2Semibold)
    RecapNumber(recap.markCount.toDouble(), animated) { texts.integer(it.roundToInt()) }
    Text(texts.noun(R.plurals.recap_marks_noun, recap.markCount), Modifier.offset(y = NounLift), style = Title2Semibold)
    Spacer(Modifier.weight(1f))
    Distribution(recap.distribution, recap.otherMarks, texts)
}

@Composable
private fun ColumnScope.Average(recap: Recap, texts: RecapTexts, animated: Boolean) {
    Kicker(texts.string(R.string.recap_average_kicker))
    Spacer(Modifier.weight(1f))
    recap.average?.let { average ->
        RecapNumber(average, animated, from = 5.0) { texts.average(it) }
        Text(texts.bandLine(average), style = Title2Semibold)
        recap.comparison?.let { Note(texts.comparison(it, average)) }
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.SubjectSlide(kicker: String, stat: RecapSubjectStat?, note: String?, texts: RecapTexts) {
    Kicker(kicker)
    Spacer(Modifier.weight(1f))
    if (stat != null) {
        val name = GradeyType.display(52)
        Text(stat.name, style = name, autoSize = shrinking(name, 0.5f), maxLines = 3)
        Text(texts.string(R.string.recap_subject_detail, texts.average(stat.average), texts.marks(stat.markCount)), style = Title3Semibold.tabular(), color = Soft)
    }
    if (note != null) Note(note)
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.Moments(recap: Recap, texts: RecapTexts) {
    Kicker(texts.string(R.string.recap_moments_kicker))
    Spacer(Modifier.weight(1f))
    Column {
        recap.moments.forEachIndexed { index, moment ->
            if (index > 0) Divider()
            val (title, value) = when (moment) {
                is RecapMoment.OnesStreak -> texts.string(R.string.recap_moment_streak_title) to texts.plural(R.plurals.recap_moment_streak_value, moment.count)
                is RecapMoment.Comeback -> texts.string(R.string.recap_moment_comeback_title) to "${moment.subject} · ${texts.average(moment.from)} → ${texts.average(moment.to)}"
                is RecapMoment.HeaviestMark -> texts.string(R.string.recap_moment_heaviest_title) to
                    "${moment.markText} · ${moment.subject} · " + texts.string(R.string.recap_moment_heaviest_weight, texts.weight(moment.weight))
                is RecapMoment.BusiestDay -> texts.string(R.string.recap_moment_busiest_title) to "${texts.weekdayShortDate(moment.date)} · ${texts.marks(moment.count)}"
            }
            Column(Modifier.padding(vertical = GradeySpacing.lg), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                Text(title, style = GradeyType.subheadlineSemibold, color = Faint)
                Text(value, style = GradeyType.title2.tabular())
            }
        }
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.Absence(recap: Recap, texts: RecapTexts, animated: Boolean) {
    Kicker(texts.string(R.string.recap_absence_kicker))
    val absence = recap.absence ?: return
    val counts = absence.counts
    Spacer(Modifier.weight(1f))
    RecapNumber(counts.total.toDouble(), animated) { texts.integer(it.roundToInt()) }
    if (counts.total == 0) {
        Text(texts.string(R.string.recap_absence_zero_title), style = Title2Semibold)
        Note(texts.string(R.string.recap_absence_zero_detail))
    } else {
        Text(texts.noun(R.plurals.recap_absence_noun, counts.total), Modifier.offset(y = NounLift), style = Title2Semibold)
        val parts = listOf(
            R.string.recap_absence_short_ok to counts.ok, R.string.recap_absence_short_unsolved to counts.unsolved,
            R.string.recap_absence_short_missed to counts.missed, R.string.recap_absence_short_late to counts.late,
        ).filter { it.second > 0 }.joinToString(" · ") { (label, count) -> "${texts.string(label)} ${texts.integer(count)}" }
        if (parts.isNotEmpty()) Note(parts)
        absence.topSubject?.let { Note(texts.string(R.string.recap_absence_top_subject, it, texts.hours(absence.topSubjectHours))) }
    }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.Timetable(recap: Recap, texts: RecapTexts, animated: Boolean) {
    Kicker(texts.string(R.string.recap_timetable_kicker))
    val timetable = recap.timetable ?: return
    Spacer(Modifier.weight(1f))
    RecapNumber(timetable.canceled.toDouble(), animated) { texts.integer(it.roundToInt()) }
    Text(
        if (timetable.canceled == 0) texts.string(R.string.recap_timetable_zero) else texts.noun(R.plurals.recap_timetable_canceled_noun, timetable.canceled),
        Modifier.offset(y = NounLift), style = Title2Semibold,
    )
    Note(
        texts.string(R.string.recap_timetable_substitutions, texts.integer(timetable.substitutions)) + " · " +
            texts.string(R.string.recap_timetable_lessons, texts.integer(timetable.lessons)),
    )
    timetable.topCanceledSubject?.let { Note(texts.string(R.string.recap_timetable_top_canceled, it)) }
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.Personality(recap: Recap, texts: RecapTexts) {
    Kicker(texts.string(R.string.recap_personality_kicker))
    val personality = recap.personality ?: return
    Spacer(Modifier.weight(1f))
    GradeyIcon(personality.icon(), size = 64.dp, tint = Ink)
    val title = GradeyType.display(48)
    Text(texts.personalityTitle(personality), style = title, autoSize = shrinking(title, 0.5f), maxLines = 3)
    Text(texts.personalityDetail(personality), style = Title3Semibold, color = Soft)
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ColumnScope.ReportCard(recap: Recap, texts: RecapTexts, animated: Boolean) {
    Kicker(texts.string(R.string.recap_report_card_title))
    val card = recap.reportCard ?: return
    Spacer(Modifier.weight(1f))
    RecapNumber(card.average, animated, from = 5.0) { texts.average(it) }
    Text(texts.string(if (card.isEstimate) R.string.recap_report_card_estimate else R.string.recap_report_card_final), style = Title2Semibold)
    reportCardDetail(card, texts).takeIf { it.isNotEmpty() }?.let { Note(it) }
    Spacer(Modifier.weight(1f))
}

private fun reportCardDetail(card: RecapReportCard, texts: RecapTexts): String = buildList {
    if (card.ones > 0) add(texts.plural(R.plurals.recap_report_card_ones, card.ones))
    if (card.borderline > 0) add(texts.plural(R.plurals.recap_report_card_borderline_count, card.borderline))
}.joinToString(" · ")

@Composable
private fun ColumnScope.LastYear(recap: Recap, texts: RecapTexts) {
    Kicker(texts.string(R.string.recap_last_year_kicker))
    val lastYear = recap.lastYear ?: return
    Spacer(Modifier.weight(1f))
    ComparisonBlock(
        texts.string(R.string.recap_term, lastYear.spring.semester.toString(), lastYear.spring.schoolYear), lastYear.springAverage,
        lastYear.springAbsentHours?.roundToInt(), texts,
    )
    Divider()
    ComparisonBlock(texts.string(R.string.recap_last_year_now), lastYear.autumnAverage, lastYear.autumnAbsentHours, texts)
    Spacer(Modifier.weight(1f))
}

@Composable
private fun ComparisonBlock(title: String, average: Double?, hours: Int?, texts: RecapTexts) {
    Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
        Text(title, style = GradeyType.subheadlineSemibold.tabular(), color = Faint)
        Text(average?.let(texts::average) ?: "–", style = GradeyType.display(56).tabular(), maxLines = 1)
        if (hours != null) Text(texts.string(R.string.recap_last_year_absence, texts.hours(hours)), style = Title3Semibold.tabular(), color = Soft)
    }
}

@Composable
private fun ColumnScope.Summary(recap: Recap, texts: RecapTexts) {
    RecapLogo(Modifier.semantics { heading() }, size = 22.dp, showsGradey = true)
    val title = GradeyType.display(36)
    Text(texts.title(recap.period), style = title.tabular(), autoSize = shrinking(title, 0.6f), maxLines = 2)
    Spacer(Modifier.weight(1f))
    val tiles = buildList {
        recap.average?.let { add(texts.string(R.string.recap_average_kicker) to texts.average(it)) }
        add(texts.string(R.string.recap_marks_kicker) to texts.integer(recap.markCount))
        recap.topSubject?.let { add(texts.string(R.string.recap_top_kicker) to it.name) }
        recap.absence?.let { add(texts.string(R.string.recap_absence_kicker) to texts.hours(it.counts.total)) }
        recap.timetable?.let { add(texts.string(R.string.recap_summary_canceled) to texts.integer(it.canceled)) }
        val personality = recap.personality
        if (personality != null && recap.period.kind != RecapKind.MONTH) add(texts.string(R.string.recap_summary_personality) to texts.personalityTitle(personality))
    }
    Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                row.forEach { (label, value) ->
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                        Text(label, style = GradeyType.subheadlineSemibold, color = Faint)
                        Text(value, style = GradeyType.title2.tabular(), autoSize = shrinking(GradeyType.title2, 0.7f), maxLines = 2)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
    Spacer(Modifier.weight(1f))
}

// Pieces

@Composable
private fun Kicker(text: String) {
    Text(text, Modifier.semantics { heading() }, style = GradeyType.headline, color = Softer)
}

@Composable
private fun Note(text: String) {
    Text(text, style = Title3Medium.tabular(), color = Soft)
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).drawBehind { drawRect(Rule) })
}

/** A big number that counts up (or down, for averages) when the slide appears (iOS `RecapNumber`). */
@Composable
private fun RecapNumber(value: Double, animated: Boolean, from: Double = 0.0, format: (Double) -> String) {
    val counts = animated && !rememberReduceMotion()
    val shown = remember(value, from, counts) { Animatable(if (counts) from.toFloat() else value.toFloat()) }
    LaunchedEffect(shown) {
        if (counts) shown.animateTo(value.toFloat(), tween(durationMillis = 900, delayMillis = 150, easing = FastOutSlowInEasing))
    }
    val spoken = format(value)
    val style = GradeyType.display(120).tabular()
    // At rest the exact value is shown, never a float that went through the animation.
    Text(
        if (shown.value == value.toFloat()) spoken else format(shown.value.toDouble()),
        Modifier.clearAndSetSemantics { contentDescription = spoken }, style = style, autoSize = shrinking(style, 0.4f), maxLines = 1,
    )
}

/** Bars for 1 to 5, plus the marks that are not graded 1–5. */
@Composable
private fun Distribution(counts: List<Int>, other: Int, texts: RecapTexts) {
    val largest = maxOf(counts.maxOrNull() ?: 0, 1)
    Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        counts.forEachIndexed { index, count ->
            Row(Modifier.semantics(mergeDescendants = true) { }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
                Text((index + 1).toString(), Modifier.width(22.dp), style = GradeyType.title3.tabular())
                Box(
                    Modifier.weight(1f).height(18.dp).drawBehind {
                        if (count > 0) {
                            val width = maxOf(6.dp.toPx(), size.width * count / largest)
                            drawRoundRect(Ink.copy(alpha = 0.85f), size = Size(width, size.height), cornerRadius = CornerRadius(size.height / 2f))
                        }
                    },
                )
                Text(texts.integer(count), Modifier.widthIn(min = 32.dp), style = Title3Semibold.tabular(), textAlign = TextAlign.End)
            }
        }
        if (other > 0) Text(texts.string(R.string.recap_marks_other, texts.integer(other)), style = GradeyType.subheadlineSemibold.tabular(), color = Faint)
    }
}
