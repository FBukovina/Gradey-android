package com.bukovinafilip.gradey.feature.vavruska

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.VavruskaEvent
import com.bukovinafilip.gradey.domain.VavruskaEventKind
import com.bukovinafilip.gradey.domain.VavruskaTodayRows
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InfoRowContent
import com.bukovinafilip.gradey.ui.DisclosureIcon
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.SectionFooter
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.tabular

/** Lessons are the brand colour, special lessons orange, and the prodloužená and the ball purple (iOS `VavruskaEventKind.tint`). */
@Composable
internal fun VavruskaEventKind.tint(): Color = when (this) {
    VavruskaEventKind.LESSON -> MaterialTheme.colorScheme.primary
    VavruskaEventKind.SPECIAL_LESSON -> MaterialTheme.gradeyColors.orange
    VavruskaEventKind.EXTENDED_LESSON, VavruskaEventKind.BALL -> MaterialTheme.gradeyColors.purple
}

/**
 * The dance class on Today (iOS `TodayView.danceCard`): the next lesson, a ticket sale, news on the
 * board, or the request to sign in again. The title opens the dance class screen.
 *
 * @param now the moment the rows were worked out for, which decides "today" and "tomorrow".
 * @param showsSavedDataNote the last refresh failed, so what is shown may be out of date.
 */
@Composable
fun TodayDanceSection(rows: VavruskaTodayRows, now: Long, showsSavedDataNote: Boolean, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val format = rememberVavruskaFormat()
    Column(modifier.fillMaxWidth().testTag("todayDanceCard")) {
        PlainSectionTitle(stringResource(R.string.vavruska_today_title), Modifier.testTag("todayOpenVavruskaButton"), onClick = onOpen)
        Box(Modifier.height(GradeySpacing.sm))
        if (rows.needsReconnect) PlainRow(Modifier.testTag("todayDanceReconnect"), onClick = onOpen) {
            InfoRowContent(HugeIcons.Alert02, stringResource(R.string.vavruska_today_reconnect), null, MaterialTheme.gradeyColors.orange)
            DisclosureIcon()
        }
        rows.nextEvent?.let { event ->
            PlainRow(Modifier.testTag("todayDanceNextEvent")) {
                InfoRowContent(
                    event.kind.icon, event.title,
                    format.relativeDayLine(event, now, stringResource(R.string.vavruska_today), stringResource(R.string.vavruska_tomorrow)), event.kind.tint(),
                )
            }
        }
        rows.sale?.let { sale ->
            PlainRow(Modifier.testTag("todayDanceTicketSale")) {
                InfoRowContent(
                    HugeIcons.Ticket01, sale.eventTitle ?: stringResource(R.string.vavruska_sale_default_title),
                    saleLine(sale.saleStartEpochMillis, now, format), MaterialTheme.gradeyColors.purple,
                )
            }
        }
        if (rows.hasUnreadNotices) PlainRow(Modifier.testTag("todayDanceNotices")) {
            InfoRowContent(HugeIcons.News, stringResource(R.string.vavruska_today_notices), null, MaterialTheme.colorScheme.primary)
        }
        if (showsSavedDataNote) SectionFooter(stringResource(R.string.vavruska_saved_data))
    }
}

/** "Ticket sales open Mon 12. 10. at 18:00", or that they are open (iOS `VavruskaFormatting.saleLine`). */
@Composable
internal fun saleLine(saleStart: Long, now: Long, format: VavruskaFormat): String =
    if (saleStart <= now) stringResource(R.string.vavruska_sale_open)
    else stringResource(R.string.vavruska_sale_opens, format.weekdayShortDate(saleStart), format.time(saleStart))

/** Dance lessons under a weekday's school lessons in the Timetable (iOS `VavruskaAfterSchoolBlock`). Each opens the dance class screen. */
@Composable
fun AfterSchoolBlock(events: List<VavruskaEvent>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (events.isEmpty()) return
    val format = rememberVavruskaFormat()
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Column(modifier.fillMaxWidth().padding(top = GradeySpacing.lg).testTag("timetableAfterSchoolBlock")) {
        SectionHeader(stringResource(R.string.vavruska_after_school), Modifier.padding(start = GradeySpacing.xs, end = GradeySpacing.xs, bottom = GradeySpacing.sm))
        events.forEach { event ->
            key(event.id) {
                Hairline()
                Row(
                    Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onOpen).padding(vertical = GradeySpacing.md)
                        .semantics(mergeDescendants = true) {}.testTag("timetableDanceEvent-${event.id}"),
                    horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
                ) {
                    // The same rail as a school lesson's number and times, so the two line up.
                    Column(Modifier.widthIn(min = GradeyMetrics.minTouch), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Box(Modifier.height(22.dp), contentAlignment = Alignment.Center) { GradeyIcon(event.kind.icon, size = 16.dp, tint = event.kind.tint()) }
                        Text(format.time(event.startEpochMillis), style = GradeyType.caption2.tabular(), color = secondary, maxLines = 1)
                        Text(format.time(event.endEpochMillis), style = GradeyType.caption2.tabular(), color = MaterialTheme.gradeyColors.tertiaryLabel, maxLines = 1)
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                        Text(event.title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        event.venue?.let { Text(it, style = GradeyType.subheadline, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
        }
    }
}

/** Date tile, title and "day · time · venue" line (iOS `VavruskaEventRow`). */
@Composable
internal fun VavruskaEventRow(event: VavruskaEvent, format: VavruskaFormat, isPast: Boolean, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val tint = if (isPast) secondary else event.kind.tint()
    val (day, month) = format.tile(event.startEpochMillis)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        Row(Modifier.weight(1f).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            // 46 dp, growing with large text so the month is never cut off.
            Column(
                Modifier.defaultMinSize(46.dp, 46.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(GradeyRadius.sm)).padding(horizontal = GradeySpacing.xs, vertical = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(day, style = GradeyType.title3.tabular(), color = tint, maxLines = 1)
                Text(month, style = GradeyType.caption2Bold, color = tint, maxLines = 1)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(event.title, style = GradeyType.subheadlineBold, color = if (isPast) secondary else MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(format.dayLine(event), style = GradeyType.caption, color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        trailing?.invoke()
    }
}
