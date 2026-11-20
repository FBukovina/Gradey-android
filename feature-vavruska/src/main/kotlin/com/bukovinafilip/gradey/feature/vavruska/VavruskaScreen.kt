package com.bukovinafilip.gradey.feature.vavruska

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.VavruskaEvent
import com.bukovinafilip.gradey.domain.VavruskaLinks
import com.bukovinafilip.gradey.domain.VavruskaSchedule
import com.bukovinafilip.gradey.domain.VavruskaSnapshot
import com.bukovinafilip.gradey.domain.VavruskaStoredCourse
import com.bukovinafilip.gradey.domain.VavruskaTextRun
import com.bukovinafilip.gradey.ui.BrandTextField
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyPrimaryButton
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.InlineNotice
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.PlainRow
import com.bukovinafilip.gradey.ui.PlainSectionTitle
import com.bukovinafilip.gradey.ui.SectionFooter
import com.bukovinafilip.gradey.ui.SettingsBackground
import com.bukovinafilip.gradey.ui.SettingsIcon
import com.bukovinafilip.gradey.ui.SettingsToggleRow
import com.bukovinafilip.gradey.ui.StatusChip
import com.bukovinafilip.gradey.ui.ToolbarIconButton
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.rememberSchoolTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * The dance class screen (iOS `VavruskaView`): upcoming lessons, prodloužená and ball tickets, the
 * school's notice board and its useful links. Without a connected course it is the form that
 * connects one. A full-screen surface with its own background; [onClose] leaves it.
 *
 * @param onConnect signs in with the course card password; throws when it does not work.
 * @param errorMessage puts a failure into the app's words.
 * @param onSeen the notice board has been looked at, which clears its "New" badge.
 */
@Composable
fun VavruskaScreen(
    course: VavruskaStoredCourse?,
    snapshot: VavruskaSnapshot,
    isRefreshing: Boolean,
    lastRefreshFailed: Boolean,
    hasUnreadNotices: Boolean,
    ticketAlertsEnabled: Boolean,
    onRefresh: () -> Unit,
    onConnect: suspend (String) -> Unit,
    errorMessage: (Exception) -> String,
    onTicketAlertsChange: (Boolean) -> Unit,
    onSeen: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Signing in again, or connecting a new course, from a connected one.
    var reconnecting by rememberSaveable { mutableStateOf(false) }
    val seen by rememberUpdatedState(onSeen)
    DisposableEffect(Unit) { onDispose { seen() } }
    BackHandler(enabled = reconnecting && course != null) { reconnecting = false }

    SettingsBackground(modifier.fillMaxSize()) {
        if (course == null || reconnecting) {
            Column(Modifier.fillMaxSize()) {
                GradeyTopBar(
                    title = stringResource(R.string.vavruska_title), backDescription = stringResource(R.string.vavruska_back),
                    onBack = { if (reconnecting && course != null) reconnecting = false else onClose() },
                )
                VavruskaConnectForm(onConnect = { password -> onConnect(password); reconnecting = false }, errorMessage = errorMessage, modifier = Modifier.weight(1f))
            }
        } else {
            VavruskaCourseContent(
                course, snapshot, isRefreshing, lastRefreshFailed, hasUnreadNotices, ticketAlertsEnabled,
                onRefresh = onRefresh, onReconnect = { reconnecting = true }, onTicketAlertsChange = onTicketAlertsChange, onClose = onClose,
            )
        }
    }
}

@Composable
private fun VavruskaCourseContent(
    course: VavruskaStoredCourse,
    snapshot: VavruskaSnapshot,
    isRefreshing: Boolean,
    lastRefreshFailed: Boolean,
    hasUnreadNotices: Boolean,
    ticketAlertsEnabled: Boolean,
    onRefresh: () -> Unit,
    onReconnect: () -> Unit,
    onTicketAlertsChange: (Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val format = rememberVavruskaFormat()
    val uriHandler = LocalUriHandler.current
    fun open(url: String) { runCatching { uriHandler.openUri(url) } }
    // The clock ticks once a minute, so a lesson moves to "past" and a sale opens without a refresh.
    val clock = rememberSchoolTime()
    val now by remember { derivedStateOf { clock.value.toInstant().toEpochMilli() } }
    val upcoming = remember(snapshot, now) { VavruskaSchedule.upcoming(snapshot, now) }
    val past = remember(snapshot, now) { VavruskaSchedule.past(snapshot, now) }
    val sales = remember(snapshot, now) { snapshot.ticketSales.filter { it.eventStartEpochMillis > now } }
    val ended = remember(snapshot, now) { VavruskaSchedule.isCourseEnded(snapshot, now) }
    var showsPast by rememberSaveable { mutableStateOf(false) }
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val linkColor = MaterialTheme.colorScheme.primary

    Column(Modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.vavruska_title), scrolled = scrolled,
            onBack = onClose, backDescription = stringResource(R.string.vavruska_back),
            onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = stringResource(R.string.vavruska_refresh),
        )
        GradeyLazyColumn(Modifier.weight(1f).testTag("vavruskaScrollView"), state = listState, bottomPadding = GradeySpacing.xl + bottomInset) {
            item(key = "header", contentType = "header") {
                Column(Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                    Text(
                        course.courseCode?.let { stringResource(R.string.vavruska_course_title, it) } ?: stringResource(R.string.vavruska_title),
                        Modifier.semantics { heading() }, style = GradeyType.display(30), color = MaterialTheme.colorScheme.onSurface,
                    )
                    course.scheduleSummary?.let { Text(it, style = GradeyType.callout, color = secondary) }
                    if (lastRefreshFailed) Text(stringResource(R.string.vavruska_saved_data), style = GradeyType.caption, color = secondary)
                }
            }
            if (snapshot.calendarUnavailable) item(key = "status", contentType = "status") {
                StatusNotice(
                    HugeIcons.Alert02, MaterialTheme.gradeyColors.orange, stringResource(R.string.vavruska_reconnect_title),
                    stringResource(R.string.vavruska_reconnect_message), stringResource(R.string.vavruska_reconnect_button), onReconnect,
                )
            } else if (ended) item(key = "status", contentType = "status") {
                StatusNotice(
                    HugeIcons.Flower, MaterialTheme.colorScheme.primary, stringResource(R.string.vavruska_ended_title),
                    stringResource(R.string.vavruska_ended_message), stringResource(R.string.vavruska_ended_button), onReconnect,
                )
            }
            item(key = "upcoming", contentType = "section") {
                Column(Modifier.fillMaxWidth().testTag("vavruskaUpcomingSection")) {
                    PlainSectionTitle(stringResource(R.string.vavruska_section_upcoming))
                    if (upcoming.isEmpty()) Text(
                        stringResource(R.string.vavruska_upcoming_none), Modifier.padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.sm),
                        style = GradeyType.subheadline, color = secondary,
                    )
                    upcoming.take(8).forEach { event -> key(event.id) { EventRow(event, format, isPast = false, onOpenMap = ::open) } }
                }
            }
            if (sales.isNotEmpty()) item(key = "tickets", contentType = "section") {
                Column(Modifier.fillMaxWidth().testTag("vavruskaTicketsSection")) {
                    PlainSectionTitle(stringResource(R.string.vavruska_section_tickets))
                    sales.forEach { sale ->
                        key(sale.id) {
                            val open = sale.saleStartEpochMillis <= now
                            PlainRow(Modifier.testTag("vavruskaSale-${sale.id}"), verticalAlignment = Alignment.Top) {
                                SettingsIcon(HugeIcons.Ticket01, color = MaterialTheme.gradeyColors.purple)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(sale.eventTitle ?: stringResource(R.string.vavruska_sale_default_title), style = GradeyType.subheadlineBold, color = MaterialTheme.colorScheme.onSurface)
                                    Text(format.saleEventLine(sale), style = GradeyType.caption, color = secondary)
                                    Text(saleLine(sale.saleStartEpochMillis, now, format), style = GradeyType.captionSemibold, color = if (open) linkColor else secondary)
                                    // The site lists the event's own page only once its sale is live; until then the listing is the way in.
                                    if (open) PlainLink(stringResource(R.string.vavruska_sale_buy), { open(sale.ticketURL ?: VavruskaLinks.Tickets) }, Modifier.testTag("vavruskaBuyTickets-${sale.id}"))
                                }
                            }
                        }
                    }
                    PlainRow {
                        SettingsToggleRow(
                            stringResource(R.string.vavruska_alerts_toggle), stringResource(R.string.vavruska_alerts_message), ticketAlertsEnabled,
                            enabled = true, onChange = onTicketAlertsChange, modifier = Modifier.weight(1f).testTag("vavruskaTicketAlertsToggle"),
                        )
                    }
                    SectionFooter(stringResource(R.string.vavruska_alerts_footer))
                }
            }
            val board = snapshot.noticeBoard
            if (board != null && board.items.isNotEmpty()) item(key = "notices", contentType = "section") {
                Column(Modifier.fillMaxWidth().testTag("vavruskaNoticeBoard")) {
                    PlainSectionTitle(
                        stringResource(R.string.vavruska_section_notices),
                        trailing = if (hasUnreadNotices) ({ StatusChip(stringResource(R.string.vavruska_notices_new), color = linkColor) }) else null,
                    )
                    board.items.forEach { item ->
                        key(item.id) {
                            PlainRow(verticalAlignment = Alignment.Top) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                                    Text(item.title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
                                    item.paragraphs.forEach { paragraph ->
                                        Text(remember(paragraph, linkColor) { annotated(paragraph, linkColor) }, style = GradeyType.subheadline, color = secondary)
                                    }
                                }
                            }
                        }
                    }
                    board.updatedOn?.let(format::date)?.let { SectionFooter(stringResource(R.string.vavruska_notices_updated, it)) }
                }
            }
            item(key = "links", contentType = "section") {
                Column(Modifier.fillMaxWidth().testTag("vavruskaLinksSection")) {
                    PlainSectionTitle(stringResource(R.string.vavruska_section_links))
                    course.pdfURL?.let { LinkRow(HugeIcons.Pdf01, stringResource(R.string.vavruska_link_pdf)) { open(it) } }
                    LinkRow(HugeIcons.Image01, stringResource(R.string.vavruska_link_gallery)) { open(VavruskaLinks.Gallery) }
                    LinkRow(HugeIcons.Dress01, stringResource(R.string.vavruska_link_clothing)) { open(VavruskaLinks.Clothing) }
                    LinkRow(HugeIcons.Ticket01, stringResource(R.string.vavruska_link_tickets)) { open(VavruskaLinks.Tickets) }
                    LinkRow(HugeIcons.Mail01, stringResource(R.string.vavruska_link_makeup)) { open(VavruskaLinks.Office) }
                    LinkRow(HugeIcons.News, stringResource(R.string.vavruska_link_notice_board)) { open(VavruskaLinks.NoticeBoard) }
                }
            }
            if (past.isNotEmpty()) item(key = "past", contentType = "section") {
                Column(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs).heightIn(min = GradeyMetrics.minTouch)
                            .clickable(role = Role.Button) { showsPast = !showsPast }.semantics { heading() }.testTag("vavruskaPastToggle"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
                    ) {
                        Text(stringResource(R.string.vavruska_section_past, past.size), style = GradeyType.title3, color = MaterialTheme.colorScheme.onSurface)
                        GradeyIcon(if (showsPast) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, size = 15.dp, tint = secondary)
                    }
                    AnimatedVisibility(showsPast) {
                        Column { past.forEach { event -> key(event.id) { EventRow(event, format, isPast = true, onOpenMap = null) } } }
                    }
                }
            }
        }
    }
}

/** An event in a plain row; upcoming ones can be found on a map. */
@Composable
private fun EventRow(event: VavruskaEvent, format: VavruskaFormat, isPast: Boolean, onOpenMap: ((String) -> Unit)?) {
    val place = remember(event) { listOfNotNull(event.venue, event.address).joinToString(", ") }
    PlainRow(Modifier.testTag("vavruskaEvent-${event.id}")) {
        VavruskaEventRow(
            event, format, isPast, Modifier.weight(1f),
            trailing = if (onOpenMap != null && place.isNotEmpty()) ({
                ToolbarIconButton(
                    HugeIcons.Location04, stringResource(R.string.vavruska_open_in_maps),
                    { onOpenMap("geo:0,0?q=" + URLEncoder.encode(place, "UTF-8")) }, tint = MaterialTheme.gradeyColors.secondaryLabel,
                )
            }) else null,
        )
    }
}

/** What is wrong with the course, or that it is over, with the way on (iOS `VavruskaView.notice`). */
@Composable
private fun StatusNotice(icon: String, tint: androidx.compose.ui.graphics.Color, title: String, message: String, action: String, onAction: () -> Unit) {
    PlainRow(Modifier.testTag("vavruskaStatusNotice"), verticalAlignment = Alignment.Top) {
        SettingsIcon(icon, color = tint)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            Text(title, style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
            Text(message, style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel)
            PlainLink(action, onAction, Modifier.testTag("vavruskaReconnectButton"))
        }
    }
}

@Composable
private fun LinkRow(icon: String, title: String, onClick: () -> Unit) {
    PlainRow(onClick = onClick) {
        SettingsIcon(icon)
        Text(title, Modifier.weight(1f), style = GradeyType.subheadlineSemibold, color = MaterialTheme.colorScheme.onSurface)
        GradeyIcon(HugeIcons.LinkSquare02, size = 14.dp, tint = MaterialTheme.gradeyColors.secondaryLabel)
    }
}

/** A notice board paragraph with its links tappable and its bold words bold (iOS `VavruskaFormatting.attributed`). */
private fun annotated(runs: List<VavruskaTextRun>, linkColor: androidx.compose.ui.graphics.Color): AnnotatedString = buildAnnotatedString {
    for (run in runs) {
        val bold = if (run.isBold) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle()
        val link = run.link
        if (link != null) withLink(LinkAnnotation.Url(link, TextLinkStyles(SpanStyle(color = linkColor)))) { withStyle(bold) { append(run.text) } }
        else withStyle(bold) { append(run.text) }
    }
}

/**
 * The password from the course card (iOS `VavruskaConnectView`). The password goes to
 * vavruska.info once and is not kept, so it is never saved with the screen's state either.
 */
@Composable
private fun VavruskaConnectForm(onConnect: suspend (String) -> Unit, errorMessage: (Exception) -> String, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    fun connect() {
        if (connecting || password.isBlank()) return
        connecting = true; error = null
        scope.launch {
            try { onConnect(password); password = "" }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = errorMessage(failure) }
            finally { connecting = false }
        }
    }
    Box(modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).testTag("vavruskaConnectView"), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = GradeyMetrics.narrowContentMaxWidth).fillMaxWidth().padding(horizontal = GradeySpacing.lg, vertical = GradeySpacing.xl),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                Text(stringResource(R.string.vavruska_connect_title), Modifier.semantics { heading() }, style = GradeyType.title1, color = MaterialTheme.colorScheme.onSurface)
                Text(stringResource(R.string.vavruska_connect_message), style = GradeyType.subheadline, color = secondary)
            }
            // The code is printed on a card, so nothing offers to save it.
            BrandTextField(
                password, { password = it; error = null }, Modifier.fillMaxWidth().testTag("vavruskaPasswordField"),
                placeholder = stringResource(R.string.vavruska_connect_password), enabled = !connecting,
                keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, onImeAction = ::connect,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                trailing = {
                    ToolbarIconButton(
                        if (visible) HugeIcons.ViewOff else HugeIcons.View,
                        stringResource(if (visible) R.string.vavruska_hide_password else R.string.vavruska_show_password), { visible = !visible }, tint = secondary,
                    )
                },
            )
            Text(stringResource(R.string.vavruska_connect_hint), style = GradeyType.caption, color = secondary)
            error?.let { InlineNotice(it, Modifier.testTag("vavruskaConnectError")) }
            GradeyPrimaryButton(
                stringResource(if (connecting) R.string.vavruska_connect_loading else R.string.vavruska_connect_button), ::connect,
                Modifier.fillMaxWidth().testTag("vavruskaConnectButton"), enabled = !connecting && password.isNotBlank(), loading = connecting,
            )
            Text(stringResource(R.string.vavruska_connect_privacy), style = GradeyType.caption, color = secondary)
        }
    }
}
