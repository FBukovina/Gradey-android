package com.bukovinafilip.gradey.feature.komens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.domain.KomensBox
import com.bukovinafilip.gradey.domain.KomensList
import com.bukovinafilip.gradey.domain.KomensMessage
import com.bukovinafilip.gradey.domain.SchoolAttachment
import com.bukovinafilip.gradey.model.KomensSnapshot
import com.bukovinafilip.gradey.ui.EmptyState
import com.bukovinafilip.gradey.ui.ErrorState
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyLazyColumn
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyTopBar
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.Hairline
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.LoadingState
import com.bukovinafilip.gradey.ui.SchoolAttachmentsSection
import com.bukovinafilip.gradey.ui.SegmentedControl
import com.bukovinafilip.gradey.ui.SettingsBackground
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.isScrolled
import com.bukovinafilip.gradey.ui.linkified
import com.bukovinafilip.gradey.ui.tabular
import java.time.Instant
import java.time.ZoneId

/**
 * Komens (iOS `KomensView`): messages from the school and its noticeboard, opened from the envelope
 * on Today. A full-screen surface with its own background. Opening a message shows it in place of
 * the list and marks it read at the school too; Back returns to the list, then leaves.
 *
 * @param snapshot both lists as the school sent them; null until the first load.
 * @param readIDs messages opened here, read even if [snapshot] still says otherwise.
 * @param errorMessage why the last load failed; shown only while there is nothing to list.
 */
@Composable
fun KomensScreen(
    snapshot: KomensSnapshot?,
    readIDs: Set<String>,
    isRefreshing: Boolean,
    errorMessage: String?,
    downloadingAttachmentID: String?,
    onRefresh: () -> Unit,
    onMarkRead: (KomensMessage) -> Unit,
    onOpenAttachment: (SchoolAttachment) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var box by rememberSaveable { mutableStateOf(KomensBox.RECEIVED) }
    var openMessageID by rememberSaveable { mutableStateOf<String?>(null) }
    val received = remember(snapshot, readIDs) { KomensList.messages(snapshot, KomensBox.RECEIVED, readIDs) }
    val noticeboard = remember(snapshot, readIDs) { KomensList.messages(snapshot, KomensBox.NOTICEBOARD, readIDs) }
    val dates = rememberKomensDateFormat()
    BackHandler(enabled = openMessageID != null) { openMessageID = null }

    SettingsBackground(modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = openMessageID,
            transitionSpec = {
                // Opening a message pushes it in from the end; Back slides it out again.
                if (targetState != null) slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { it } togetherWith
                    (slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 4 } + fadeOut(tween(300)))
                else (slideInHorizontally(tween(300, easing = FastOutSlowInEasing)) { -it / 4 } + fadeIn(tween(300))) togetherWith
                    slideOutHorizontally(tween(300, easing = FastOutSlowInEasing)) { it }
            },
            label = "komens",
        ) { messageID ->
            if (messageID == null) KomensListPage(
                messages = if (box == KomensBox.RECEIVED) received else noticeboard, box = box, onBox = { box = it },
                loaded = snapshot != null, isRefreshing = isRefreshing, errorMessage = errorMessage, dates = dates,
                onRefresh = onRefresh, onOpen = { openMessageID = it.id }, onClose = onClose,
            ) else KomensMessagePage(
                message = remember(messageID, received, noticeboard) { received.firstOrNull { it.id == messageID } ?: noticeboard.firstOrNull { it.id == messageID } },
                dates = dates, downloadingAttachmentID = downloadingAttachmentID,
                onMarkRead = onMarkRead, onOpenAttachment = onOpenAttachment, onBack = { openMessageID = null },
            )
        }
    }
}

@Composable
private fun KomensListPage(
    messages: List<KomensMessage>,
    box: KomensBox,
    onBox: (KomensBox) -> Unit,
    loaded: Boolean,
    isRefreshing: Boolean,
    errorMessage: String?,
    dates: KomensDateFormat,
    onRefresh: () -> Unit,
    onOpen: (KomensMessage) -> Unit,
    onClose: () -> Unit,
) {
    val listState = rememberLazyListState()
    val scrolled by remember { derivedStateOf { listState.isScrolled } }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // One clock reading per list, so every row agrees on what "today" is.
    val now = remember(messages) { Instant.now() }
    val zone = remember { ZoneId.systemDefault() }
    Column(Modifier.fillMaxSize()) {
        GradeyTopBar(
            title = stringResource(R.string.komens_title), scrolled = scrolled,
            onBack = onClose, backDescription = stringResource(R.string.komens_back),
            onRefresh = onRefresh, isRefreshing = isRefreshing, refreshDescription = stringResource(R.string.komens_refresh),
        )
        GradeyLazyColumn(
            Modifier.weight(1f).testTag("komensList"), state = listState,
            maxWidth = GradeyMetrics.narrowContentMaxWidth, itemSpacing = 0.dp, bottomPadding = GradeySpacing.xl + bottomInset,
        ) {
            item(key = "boxes", contentType = "picker") {
                SegmentedControl(
                    options = listOf(stringResource(R.string.komens_box_received), stringResource(R.string.komens_box_noticeboard)),
                    selectedIndex = box.ordinal, onSelect = { onBox(KomensBox.entries[it]) },
                    modifier = Modifier.fillMaxWidth().padding(bottom = GradeySpacing.md).testTag("komensBoxPicker"),
                )
            }
            when {
                messages.isNotEmpty() -> items(messages, key = { it.id }, contentType = { "message" }) { message ->
                    KomensMessageRow(message, sent = message.sentAt?.let { dates.short(it, now, zone) }, onClick = { onOpen(message) })
                }
                !loaded && isRefreshing -> item(key = "loading", contentType = "state") { LoadingState(Modifier.padding(top = GradeySpacing.xl)) }
                !loaded && errorMessage != null -> item(key = "error", contentType = "state") {
                    ErrorState(errorMessage, Modifier.padding(top = GradeySpacing.xl), retryLabel = stringResource(R.string.komens_retry), onRetry = onRefresh)
                }
                else -> item(key = "empty", contentType = "state") {
                    Text(
                        stringResource(if (box == KomensBox.RECEIVED) R.string.komens_empty_received else R.string.komens_empty_noticeboard),
                        Modifier.fillMaxWidth().padding(top = GradeySpacing.xl).testTag("komensEmpty"),
                        style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel, textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/** A message in the list: an unread dot, the heading with the day, the sender and two lines of the text. */
@Composable
private fun KomensMessageRow(message: KomensMessage, sent: String?, onClick: () -> Unit) {
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val unread = stringResource(R.string.komens_unread)
    val heading = message.heading ?: stringResource(R.string.komens_untitled)
    Column(Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.xs)) {
        Hairline()
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(vertical = GradeySpacing.md)
                .semantics(mergeDescendants = true) { if (!message.isRead) stateDescription = unread }
                .testTag("komensRow-${message.messageID}"),
            horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
        ) {
            // The dot keeps its place when read, so headings line up down the list.
            Box(Modifier.padding(top = 7.dp).size(8.dp).background(if (message.isRead) androidx.compose.ui.graphics.Color.Transparent else MaterialTheme.colorScheme.primary, CircleShape))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    Text(
                        heading, Modifier.weight(1f).alignByBaseline(),
                        style = GradeyType.headline.copy(fontWeight = if (message.isRead) FontWeight.SemiBold else FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    if (sent != null) Text(sent, Modifier.alignByBaseline(), style = GradeyType.caption.tabular(), color = secondary, maxLines = 1)
                }
                message.senderName?.let { sender ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                        Text(sender, Modifier.weight(1f, fill = false), style = GradeyType.subheadline, color = secondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (message.attachments.isNotEmpty()) GradeyIcon(HugeIcons.Attachment01, size = 13.dp, tint = secondary)
                    }
                }
                if (message.preview.isNotEmpty()) Text(message.preview, style = GradeyType.subheadline, color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** One message (iOS `KomensMessageView`): heading, sender, text and attachments. Showing it marks it read. */
@Composable
private fun KomensMessagePage(
    message: KomensMessage?,
    dates: KomensDateFormat,
    downloadingAttachmentID: String?,
    onMarkRead: (KomensMessage) -> Unit,
    onOpenAttachment: (SchoolAttachment) -> Unit,
    onBack: () -> Unit,
) {
    val scroll = rememberScrollState()
    val scrolled by remember { derivedStateOf { scroll.value > 0 } }
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val linkColor = MaterialTheme.colorScheme.primary
    if (message != null) LaunchedEffect(message.id) { if (!message.isRead) onMarkRead(message) }
    Column(Modifier.fillMaxSize().testTag("komensMessage")) {
        GradeyTopBar(
            title = stringResource(if (message?.box == KomensBox.NOTICEBOARD) R.string.komens_box_noticeboard else R.string.komens_title),
            scrolled = scrolled, onBack = onBack, backDescription = stringResource(R.string.komens_back),
        )
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll), contentAlignment = Alignment.TopCenter) {
            Column(
                Modifier.widthIn(max = GradeyMetrics.narrowContentMaxWidth).fillMaxWidth()
                    .padding(start = GradeySpacing.lg, end = GradeySpacing.lg, top = GradeySpacing.sm, bottom = GradeySpacing.xl + bottomInset),
                verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg),
            ) {
                if (message == null) {
                    EmptyState(title = stringResource(R.string.komens_unavailable), icon = HugeIcons.Mail01)
                    return@Column
                }
                val byline = remember(message, dates) {
                    listOfNotNull(message.senderName, message.sentAt?.let { dates.full(it, Instant.now(), ZoneId.systemDefault()) }).joinToString(" · ")
                }
                Column(verticalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
                    Text(
                        message.heading ?: stringResource(R.string.komens_untitled), Modifier.semantics { heading() },
                        style = GradeyType.title2, color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (byline.isNotEmpty()) Text(byline, style = GradeyType.subheadline.tabular(), color = MaterialTheme.gradeyColors.secondaryLabel)
                }
                if (message.body.isNotEmpty()) {
                    val text = remember(message.body, linkColor) { linkified(message.body, linkColor) }
                    SelectionContainer {
                        Text(text, Modifier.fillMaxWidth().testTag("komensMessageBody"), style = GradeyType.body, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
                if (message.attachments.isNotEmpty()) SchoolAttachmentsSection(
                    title = stringResource(R.string.komens_attachments), attachments = message.attachments,
                    downloadingID = downloadingAttachmentID, onOpen = onOpenAttachment, tagPrefix = "komensAttachment",
                )
            }
        }
    }
}
