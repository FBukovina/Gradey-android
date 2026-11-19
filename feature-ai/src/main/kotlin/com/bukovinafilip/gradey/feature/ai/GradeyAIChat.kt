package com.bukovinafilip.gradey.feature.ai

import android.content.ClipData
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bukovinafilip.gradey.ui.AIOrb
import com.bukovinafilip.gradey.ui.BrandGradientIcon
import com.bukovinafilip.gradey.ui.GradeyColors
import com.bukovinafilip.gradey.ui.GradeyDialog
import com.bukovinafilip.gradey.ui.GradeyIcon
import com.bukovinafilip.gradey.ui.GradeyMetrics
import com.bukovinafilip.gradey.ui.GradeyRadius
import com.bukovinafilip.gradey.ui.GradeySheet
import com.bukovinafilip.gradey.ui.GradeySpacing
import com.bukovinafilip.gradey.ui.GradeyType
import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.PlainLink
import com.bukovinafilip.gradey.ui.SectionHeader
import com.bukovinafilip.gradey.ui.SettingsToggleRow
import com.bukovinafilip.gradey.ui.brandGradient
import com.bukovinafilip.gradey.ui.gradeyColors
import com.bukovinafilip.gradey.ui.tabular
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date

private val ChatMaxWidth = 760.dp

/**
 * The chat itself (iOS `GradeyAIChatScreen`). A new chat shows the welcome state, an open one its
 * messages. The dock at the bottom keeps what will be shared, its Compute cost and the composer together.
 */
@Composable
internal fun AIChatScreen(state: AIState, controller: GradeyAIController, opening: Boolean, onUpgrade: (() -> Unit)?) {
    val status = state.status
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                opening && state.messages.isEmpty() -> Column(Modifier.fillMaxSize()) {
                    PausedBanner(status, Modifier.padding(horizontal = 20.dp))
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        AIStatusState(stringResource(R.string.gradey_ai_loading), Modifier.testTag("gradeyAIOpeningConversation"))
                    }
                }
                state.messages.isEmpty() -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = GradeySpacing.lg),
                    verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl, Alignment.CenterVertically),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (status?.enabled == false) item(key = "paused") { PausedBanner(status, Modifier.widthIn(max = 720.dp)) }
                    item(key = "welcome") { Welcome(state, controller, Modifier.widthIn(max = 720.dp)) }
                }
                else -> Column(Modifier.fillMaxSize()) {
                    PausedBanner(status, Modifier.padding(horizontal = 20.dp))
                    MessageList(state, controller, Modifier.weight(1f))
                }
            }
        }
        Dock(state, controller, onUpgrade)
    }
}

@Composable
private fun PausedBanner(status: AIStatus?, modifier: Modifier = Modifier) {
    if (status?.enabled == false) AIBanner(
        AIBannerKind.PAUSED, stringResource(R.string.gradey_ai_availability_paused_message),
        modifier.padding(top = GradeySpacing.sm).testTag("gradeyAIServiceUnavailableBanner"),
        title = stringResource(R.string.gradey_ai_availability_paused_title),
    )
}

// MARK: - Welcome

@Composable
private fun Welcome(state: AIState, controller: GradeyAIController, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("gradeyAIWelcome"), verticalArrangement = Arrangement.spacedBy(GradeySpacing.xl)) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            AIOrb(64.dp, Modifier.padding(bottom = GradeySpacing.xs))
            Text(
                stringResource(R.string.gradey_ai_chat_empty_title), Modifier.semantics { heading() },
                style = GradeyType.display(32).copy(brush = MaterialTheme.colorScheme.brandGradient, textAlign = TextAlign.Center),
            )
            Text(
                stringResource(R.string.gradey_ai_chat_empty_message), style = GradeyType.callout,
                color = MaterialTheme.gradeyColors.secondaryLabel, textAlign = TextAlign.Center,
            )
        }
        if (state.selection.action != AIAction.REPLY) FocusedAction(state, controller)
    }
}

/** The chosen action with its starter prompt (iOS `focusedAction`). */
@Composable
private fun FocusedAction(state: AIState, controller: GradeyAIController) {
    val action = state.selection.action
    val prompt = stringResource(action.promptResource)
    val canStart = !state.busy && state.status?.canAfford(action) == true &&
        (action != AIAction.SUBJECT || state.selection.subjectID != null) && (action != AIAction.TEST || state.selection.eventID != null)
    Column(Modifier.fillMaxWidth().aiSurface(RoundedCornerShape(PanelCorner)).padding(GradeySpacing.lg), verticalArrangement = Arrangement.spacedBy(GradeySpacing.lg)) {
        Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            Box(Modifier.size(38.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
                BrandGradientIcon(action.icon, size = 18.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(action.titleResource), style = GradeyType.headline, color = MaterialTheme.colorScheme.onSurface)
                state.selectedContextName?.let { Text(it, style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel) }
            }
            val resetLabel = stringResource(R.string.gradey_ai_action_reply)
            Box(
                Modifier.size(GradeyMetrics.minTouch).clip(CircleShape)
                    .clickable(enabled = !state.busy, role = Role.Button) { controller.selectAction(AIAction.REPLY) }
                    .semantics { contentDescription = resetLabel }.testTag("gradeyAIResetActionButton"),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(30.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), CircleShape), contentAlignment = Alignment.Center) {
                    GradeyIcon(HugeIcons.Cancel01, size = 13.dp, tint = MaterialTheme.gradeyColors.secondaryLabel)
                }
            }
        }
        Row(
            Modifier.fillMaxWidth().aiSurface(RoundedCornerShape(GradeyRadius.card))
                .clickable(enabled = canStart, role = Role.Button) { controller.send(prompt = prompt) }
                .padding(horizontal = GradeySpacing.lg, vertical = 14.dp).testTag("gradeyAIStarterPrompts"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
        ) {
            BrandGradientIcon(HugeIcons.Sparkles, size = 15.dp)
            Text(prompt, Modifier.weight(1f), style = GradeyType.subheadlineMedium, color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (canStart) 1f else 0.45f))
            GradeyIcon(HugeIcons.ArrowUpRight01, size = 14.dp, tint = MaterialTheme.gradeyColors.secondaryLabel)
        }
    }
}

// MARK: - Messages

@Composable
private fun MessageList(state: AIState, controller: GradeyAIController, modifier: Modifier = Modifier) {
    val list = rememberLazyListState()
    val count = state.messages.size
    val streamedLength = state.messages.lastOrNull()?.takeIf { it.status == "streaming" }?.text?.length ?: 0
    // A new message scrolls into view with an animation; streamed text only keeps the end in view.
    LaunchedEffect(count) { if (count > 0) list.animateScrollToItem(count) }
    LaunchedEffect(streamedLength) { if (streamedLength > 0 && !list.isScrollInProgress) list.scrollToItem(count) }
    val canRetry = state.pending != null && !state.busy && state.status?.enabled == true
    LazyColumn(
        modifier.fillMaxWidth().testTag("gradeyAIMessageList"),
        state = list,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = GradeySpacing.lg),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items(state.messages, key = { it.id }, contentType = { it.role }) { message ->
            Box(Modifier.widthIn(max = ChatMaxWidth).fillMaxWidth()) {
                if (message.role == "user") UserBubble(message.text, Modifier.align(Alignment.CenterEnd))
                else AssistantReply(message, canRetry, onRetry = { controller.send(retry = true) })
            }
        }
        item(key = "bottom", contentType = "bottom") { Spacer(Modifier.height(1.dp)) }
    }
}

/** Compact tinted bubble with a small tail corner (iOS `GradeyAIUserBubble`). */
@Composable
private fun UserBubble(text: String, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.gradeyColors.isDark
    val strength = if (dark) 1f else 0.72f
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(topStart = BubbleCorner, topEnd = BubbleCorner, bottomEnd = BubbleTail, bottomStart = BubbleCorner)
    val fill = remember(colors.primary, colors.secondary, strength) {
        Brush.linearGradient(listOf(colors.primary.copy(alpha = 0.28f * strength), colors.secondary.copy(alpha = 0.18f * strength)))
    }
    SelectionContainer(modifier.padding(start = 48.dp).widthIn(max = 560.dp)) {
        Text(
            text,
            Modifier.background(fill, shape).border(1.dp, colors.primary.copy(alpha = if (dark) 0.32f else 0.22f), shape)
                .padding(horizontal = 16.dp, vertical = 11.dp),
            style = GradeyType.body, color = colors.onSurface,
        )
    }
}

/** Unboxed reply next to the orb, then the AI disclosure and message actions (iOS `GradeyAIAssistantReply`). */
@Composable
private fun AssistantReply(message: AIMessage, canRetry: Boolean, onRetry: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        AIOrb(28.dp, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f, fill = false).widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            if (message.text.isEmpty()) when (message.status) {
                "streaming" -> ThinkingIndicator(Modifier.padding(top = 3.dp))
                "failed" -> Text(
                    stringResource(R.string.gradey_ai_error_could_not_complete), Modifier.padding(top = GradeySpacing.xs),
                    style = GradeyType.subheadline, color = MaterialTheme.gradeyColors.secondaryLabel,
                )
            } else SelectionContainer { AIMarkdownText(message.text) }
            ReplyFooter(message, canRetry, onRetry)
        }
    }
}

@Composable
private fun ReplyFooter(message: AIMessage, canRetry: Boolean, onRetry: () -> Unit) {
    val hasText = message.text.isNotEmpty()
    val showsCopy = message.status == "complete" && hasText
    val showsRetry = message.status == "failed" && canRetry
    val showsCancelled = message.status == "cancelled"
    if (!hasText && !showsRetry && !showsCancelled) return
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
        if (hasText) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs)) {
            BrandGradientIcon(HugeIcons.Sparkles, size = 11.dp)
            Text(stringResource(R.string.gradey_ai_act_generated), style = GradeyType.caption2Semibold, color = secondary)
        }
        if (showsCancelled) Text(stringResource(R.string.gradey_ai_response_cancelled), style = GradeyType.caption, color = secondary)
        Spacer(Modifier.weight(1f))
        if (showsRetry) Row(
            Modifier.clip(CircleShape).background(GradeyColors.Poor.copy(alpha = 0.12f)).clickable(role = Role.Button, onClick = onRetry)
                .padding(horizontal = 10.dp, vertical = 6.dp).testTag("gradeyAIRetryMessageButton"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.xs),
        ) {
            GradeyIcon(HugeIcons.Refresh04, size = 13.dp, tint = GradeyColors.Poor)
            Text(stringResource(R.string.ai_retry), style = GradeyType.captionSemibold, color = GradeyColors.Poor)
        }
        if (showsCopy) CopyButton(message.text)
    }
}

@Composable
private fun CopyButton(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) { if (copied) { delay(1_500); copied = false } }
    val label = stringResource(if (copied) R.string.gradey_ai_message_copied else R.string.gradey_ai_message_copy)
    Box(
        Modifier.size(width = GradeyMetrics.minTouch, height = 30.dp).clip(RoundedCornerShape(GradeyRadius.xs))
            .clickable(role = Role.Button) {
                scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText("Gradey AI", text))); copied = true }
            }.semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        GradeyIcon(
            if (copied) HugeIcons.Tick02 else HugeIcons.Copy01, size = 15.dp,
            tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.gradeyColors.secondaryLabel,
        )
    }
}

// MARK: - Dock

@Composable
private fun Dock(state: AIState, controller: GradeyAIController, onUpgrade: (() -> Unit)?) {
    Box(
        Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom)),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            Modifier.widthIn(max = ChatMaxWidth).fillMaxWidth().padding(horizontal = 16.dp, vertical = GradeySpacing.sm),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
        ) {
            if (onUpgrade != null && state.status?.remaining == 0) UpgradeRow(onUpgrade)
            ContextBar(state, controller)
            Composer(state, controller)
            QuotaLine(state)
        }
    }
}

@Composable
private fun UpgradeRow(onUpgrade: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().aiSurface(RoundedCornerShape(GradeyRadius.card)).clickable(role = Role.Button, onClick = onUpgrade)
            .padding(GradeySpacing.md).testTag("gradeyAISupportUpgradeButton"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        Box(Modifier.size(32.dp).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            BrandGradientIcon(HugeIcons.Favourite, size = 16.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.gradey_ai_compute_upgrade), style = GradeyType.footnoteBold, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.gradey_ai_limit_upgrade_action), style = GradeyType.captionSemibold, color = MaterialTheme.colorScheme.primary)
        }
        GradeyIcon(HugeIcons.ArrowRight01, size = 14.dp, tint = MaterialTheme.gradeyColors.secondaryLabel)
    }
}

/** What will be shared, what it costs, and a refresh for the selected school information. */
@Composable
private fun ContextBar(state: AIState, controller: GradeyAIController) {
    val context = LocalContext.current
    val secondary = MaterialTheme.gradeyColors.secondaryLabel
    val cost = state.status?.cost(state.selection.action)
    Column(Modifier.fillMaxWidth().testTag("gradeyAIContextStatus"), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
            ContextMenuChip(state, controller, Modifier.weight(1f, fill = false))
            Spacer(Modifier.weight(1f))
            Text(
                if (cost == null) stringResource(R.string.gradey_ai_compute_action_unavailable) else stringResource(R.string.gradey_ai_compute_action_cost, cost),
                style = GradeyType.caption.tabular(), color = secondary, maxLines = 2, textAlign = TextAlign.End,
            )
            val refreshLabel = stringResource(R.string.gradey_ai_context_refresh)
            val refreshing = state.busy && !state.sending
            Box(
                Modifier.size(GradeyMetrics.minTouch).clip(CircleShape)
                    .clickable(enabled = !state.busy && state.selection.action != AIAction.REPLY, role = Role.Button, onClick = controller::refreshContext)
                    .semantics { contentDescription = refreshLabel }.testTag("gradeyAIContextRefreshButton"),
                contentAlignment = Alignment.Center,
            ) {
                if (refreshing) CircularProgressIndicator(Modifier.size(15.dp), color = secondary, strokeWidth = 2.dp)
                else GradeyIcon(HugeIcons.Refresh04, size = 15.dp, tint = secondary.copy(alpha = if (state.selection.action == AIAction.REPLY) 0.4f else 1f))
            }
        }
        val unavailable = remember(state.context) { (state.context?.get("unavailableSections") as? List<*>)?.filterIsInstance<String>().orEmpty() }
        if (unavailable.isNotEmpty()) Row(Modifier.padding(horizontal = GradeySpacing.xs), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GradeyIcon(HugeIcons.Alert02, size = 12.dp, tint = MaterialTheme.gradeyColors.orange)
            val sections = unavailable.map { stringResource(aiSectionResource(it)) }.joinToString()
            Text(stringResource(R.string.gradey_ai_context_stale, sections), style = GradeyType.caption, color = secondary)
        }
        if (state.selection.action == AIAction.TEST && state.selection.eventID != null) SettingsToggleRow(
            title = stringResource(R.string.gradey_ai_context_include_notes), subtitle = null,
            checked = state.selection.includeNotes, enabled = !state.busy && state.currentChat == null,
            onChange = controller::includeEventNotes,
        )
        if (!state.purposeCompatible) Text(
            stringResource(R.string.gradey_ai_context_new_purpose), Modifier.padding(horizontal = GradeySpacing.xs),
            style = GradeyType.caption, color = secondary,
        )
        if (state.recovery != null && !state.sending) Row(
            Modifier.padding(horizontal = GradeySpacing.xs), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
        ) {
            Text(stringResource(R.string.gradey_ai_compute_pending), Modifier.weight(1f), style = GradeyType.caption, color = secondary)
            PlainLink(stringResource(R.string.gradey_ai_compute_check_request), controller::recover, enabled = !state.busy, showsArrow = false)
        }
        state.notice?.let { notice ->
            Text(localizedAIMessage(context, notice), Modifier.padding(horizontal = GradeySpacing.xs), style = GradeyType.caption, color = secondary)
        }
    }
}

private enum class ContextMenuLevel { ROOT, SUBJECTS, TESTS }

/** Capsule that names the current action and opens the action menu (iOS `contextMenu`). */
@Composable
private fun ContextMenuChip(state: AIState, controller: GradeyAIController, modifier: Modifier = Modifier) {
    var level by remember { mutableStateOf<ContextMenuLevel?>(null) }
    val action = state.selection.action
    val title = stringResource(action.titleResource)
    val name = state.selectedContextName
    val menuLabel = listOfNotNull(stringResource(R.string.gradey_ai_context_choose_action), title, name).joinToString()
    val tokens = MaterialTheme.gradeyColors
    Box(modifier) {
        Row(
            Modifier.heightIn(min = 36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                .clickable(enabled = !state.busy, role = Role.Button) { level = ContextMenuLevel.ROOT }
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .semantics { contentDescription = menuLabel }.testTag("gradeyAIContextSelectionMenu"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BrandGradientIcon(action.icon, size = 14.dp)
            Text(title, style = GradeyType.footnoteBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (name != null) Text(name, Modifier.weight(1f, fill = false), style = GradeyType.footnote, color = tokens.secondaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
            GradeyIcon(HugeIcons.ArrowDown01, size = 12.dp, tint = tokens.secondaryLabel)
        }
        DropdownMenu(expanded = level != null, onDismissRequest = { level = null }, containerColor = tokens.secondaryGroupedBackground) {
            @Composable
            fun entry(text: String, enabled: Boolean = true, submenu: Boolean = false, onClick: () -> Unit) = DropdownMenuItem(
                text = { Text(text, style = GradeyType.body, maxLines = 2, overflow = TextOverflow.Ellipsis) }, onClick = onClick, enabled = enabled,
                trailingIcon = if (submenu) ({ GradeyIcon(HugeIcons.ArrowRight01, size = 13.dp, tint = tokens.secondaryLabel) }) else null,
            )
            when (level) {
                ContextMenuLevel.SUBJECTS -> state.availableSubjects.forEach { (id, subject) ->
                    entry(subject) { level = null; controller.selectAction(AIAction.SUBJECT, subjectID = id) }
                }
                ContextMenuLevel.TESTS -> state.availableEvents.forEach { event ->
                    entry(event.title) { level = null; controller.selectAction(AIAction.TEST, subjectID = event.subjectID, eventID = event.id) }
                }
                else -> {
                    listOf(AIAction.REPLY, AIAction.TOMORROW, AIAction.PRIORITIES, AIAction.WEEK).forEach { item ->
                        entry(stringResource(item.titleResource)) { level = null; controller.selectAction(item) }
                    }
                    entry(stringResource(AIAction.SUBJECT.titleResource), enabled = state.availableSubjects.isNotEmpty(), submenu = true) { level = ContextMenuLevel.SUBJECTS }
                    entry(stringResource(AIAction.TEST.titleResource), enabled = state.availableEvents.isNotEmpty(), submenu = true) { level = ContextMenuLevel.TESTS }
                }
            }
        }
    }
}

/** Pill composer with the round send / stop button; the ring turns primary while it has focus. */
@Composable
private fun Composer(state: AIState, controller: GradeyAIController) {
    val keyboard = LocalSoftwareKeyboardController.current
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(PanelCorner)
    val primary = MaterialTheme.colorScheme.primary
    val send = { if (state.canSend) { keyboard?.hide(); controller.send() } }
    Row(
        Modifier.fillMaxWidth().aiSurface(shape)
            .then(if (focused) Modifier.border(1.dp, primary.copy(alpha = 0.6f), shape) else Modifier)
            .padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
    ) {
        BasicTextField(
            value = state.draft, onValueChange = controller::draft,
            modifier = Modifier.weight(1f).padding(vertical = 9.dp).onFocusChanged { focused = it.isFocused }.testTag("gradeyAIComposer"),
            enabled = !state.sending, minLines = 1, maxLines = 6,
            textStyle = GradeyType.body.copy(color = MaterialTheme.colorScheme.onSurface),
            cursorBrush = SolidColor(primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { send() }),
            decorationBox = { inner ->
                Box {
                    if (state.draft.isEmpty()) Text(
                        stringResource(R.string.gradey_ai_composer_placeholder), style = GradeyType.body,
                        color = MaterialTheme.gradeyColors.tertiaryLabel, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    inner()
                }
            },
        )
        if (state.sending) ComposerButton(HugeIcons.Stop, stringResource(R.string.gradey_ai_stop), enabled = true, onClick = controller::stop, tag = "gradeyAIStopButton")
        else ComposerButton(HugeIcons.ArrowUp01, stringResource(R.string.gradey_ai_send), enabled = state.canSend, onClick = send, tag = "gradeyAISendButton")
    }
}

/** 38 dp gradient circle; a disabled button loses its colour and fades to 35 % (iOS `GradeyAIComposerButton`). */
@Composable
private fun ComposerButton(icon: String, label: String, enabled: Boolean, onClick: () -> Unit, tag: String) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.35f, tween(150), label = "composerButton")
    val disabledFill = MaterialTheme.gradeyColors.secondaryLabel
    Box(
        Modifier.size(GradeyMetrics.minTouch).clip(CircleShape).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = label }.testTag(tag),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(38.dp).graphicsLayer { this.alpha = alpha }
                .then(if (enabled) Modifier.background(MaterialTheme.colorScheme.brandGradient, CircleShape) else Modifier.background(disabledFill, CircleShape)),
            contentAlignment = Alignment.Center,
        ) { GradeyIcon(icon, size = 16.dp, tint = GradeyColors.OnAccent) }
    }
}

@Composable
private fun QuotaLine(state: AIState) {
    val context = LocalContext.current
    val status = state.status
    val empty = (status?.remaining ?: 0) == 0
    val text = listOfNotNull(
        status?.let { stringResource(R.string.gradey_ai_compute_remaining, it.remaining, it.dailyLimit) },
        status?.resetAt?.let { stringResource(R.string.gradey_ai_limit_resets, DateFormat.getTimeFormat(context).format(Date(it))) },
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().padding(horizontal = GradeySpacing.sm), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
        Text(
            text, Modifier.weight(1f).testTag("gradeyAIRemainingMessages"), style = GradeyType.caption2Medium.tabular(),
            color = if (empty) GradeyColors.Poor else MaterialTheme.gradeyColors.secondaryLabel, textAlign = TextAlign.Center,
        )
        if (state.draftLength > 1_800) Text(
            "${state.draftLength}/2000", style = GradeyType.caption2.tabular(),
            color = if (state.draftLength > 2_000) GradeyColors.Poor else MaterialTheme.gradeyColors.secondaryLabel,
        )
    }
}

// MARK: - History sheet

private enum class DangerousAction { DELETE_ALL, REVOKE }

/**
 * Past chats and the data controls (iOS `GradeyAIHistorySheet`). A chat is deleted from its long-press
 * action; delete-all and revoke-consent each ask first.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun AIHistorySheet(
    chats: List<AIChat>,
    currentChatID: String?,
    busy: Boolean,
    sending: Boolean,
    onOpen: (AIChat) -> Unit,
    onDelete: (String) -> Unit,
    onDeleteAll: () -> Unit,
    onRevoke: () -> Unit,
    onDismiss: () -> Unit,
) {
    var pendingDeletion by remember { mutableStateOf<AIChat?>(null) }
    var dangerous by remember { mutableStateOf<DangerousAction?>(null) }
    val deleteLabel = stringResource(R.string.gradey_ai_conversation_delete_action)
    GradeySheet(onDismiss) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md)) {
            Text(
                stringResource(R.string.gradey_ai_conversations_title), Modifier.weight(1f).semantics { heading() },
                style = GradeyType.display(30), color = MaterialTheme.colorScheme.onSurface,
            )
            AICircleButton(HugeIcons.Cancel01, stringResource(R.string.gradey_ai_done), onDismiss, Modifier.testTag("gradeyAIHistoryCloseButton"))
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f, fill = false).testTag("gradeyAIConversationList"),
            verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm),
        ) {
            if (chats.isEmpty()) item(key = "empty", contentType = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = GradeySpacing.xl).testTag("gradeyAIEmptyConversations"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(GradeySpacing.md),
                ) {
                    AIOrb(40.dp)
                    Text(stringResource(R.string.gradey_ai_empty_title), style = GradeyType.headline, color = MaterialTheme.gradeyColors.secondaryLabel)
                }
            } else items(chats, key = { it.id }, contentType = { "chat" }) { chat ->
                ConversationRow(
                    chat, current = chat.id == currentChatID,
                    Modifier.combinedClickable(
                        enabled = !busy, role = Role.Button, onLongClickLabel = deleteLabel,
                        onLongClick = { pendingDeletion = chat }, onClick = { onOpen(chat) },
                    ).semantics {
                        customActions = listOf(CustomAccessibilityAction(deleteLabel) { pendingDeletion = chat; true })
                    }.testTag("gradeyAIConversation-${chat.id}"),
                )
            }
            item(key = "manageHeader", contentType = "header") {
                SectionHeader(stringResource(R.string.gradey_ai_history_manage), Modifier.padding(top = GradeySpacing.md, start = GradeySpacing.xs))
            }
            item(key = "deleteAll", contentType = "manage") {
                ManageRow(HugeIcons.Delete02, stringResource(R.string.gradey_ai_delete_all_action), enabled = chats.isNotEmpty() && !sending && !busy, tag = "gradeyAIDeleteAllButton") {
                    dangerous = DangerousAction.DELETE_ALL
                }
            }
            item(key = "revoke", contentType = "manage") {
                ManageRow(HugeIcons.SecurityBlock, stringResource(R.string.gradey_ai_privacy_revoke_action), enabled = !sending && !busy, tag = "gradeyAIRevokeConsentButton") {
                    dangerous = DangerousAction.REVOKE
                }
            }
            item(key = "privacy", contentType = "footnote") {
                Row(Modifier.padding(horizontal = GradeySpacing.xs, vertical = GradeySpacing.sm), horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    GradeyIcon(HugeIcons.SecurityLock, size = 14.dp, tint = MaterialTheme.colorScheme.primary)
                    Text(stringResource(R.string.gradey_ai_privacy_footnote), Modifier.weight(1f), style = GradeyType.footnote, color = MaterialTheme.gradeyColors.secondaryLabel)
                }
            }
        }
    }
    pendingDeletion?.let { chat ->
        GradeyDialog(
            title = stringResource(R.string.gradey_ai_conversation_delete_title), text = stringResource(R.string.gradey_ai_conversation_delete_message),
            onDismiss = { pendingDeletion = null }, confirmLabel = deleteLabel, destructive = true,
            onConfirm = { pendingDeletion = null; onDelete(chat.id) }, dismissLabel = stringResource(R.string.ai_cancel),
        )
    }
    dangerous?.let { action ->
        val revoke = action == DangerousAction.REVOKE
        GradeyDialog(
            title = stringResource(if (revoke) R.string.gradey_ai_privacy_revoke_title else R.string.gradey_ai_delete_all_title),
            text = stringResource(if (revoke) R.string.gradey_ai_privacy_revoke_message else R.string.gradey_ai_delete_all_message),
            onDismiss = { dangerous = null },
            confirmLabel = stringResource(if (revoke) R.string.gradey_ai_privacy_revoke_action else R.string.gradey_ai_delete_all_action),
            destructive = true, dismissLabel = stringResource(R.string.ai_cancel),
            onConfirm = { dangerous = null; if (revoke) { onRevoke(); onDismiss() } else onDeleteAll() },
        )
    }
}

@Composable
private fun ConversationRow(chat: AIChat, current: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val tokens = MaterialTheme.gradeyColors
    val shape = RoundedCornerShape(TileCorner)
    val fill = colors.primary.copy(alpha = if (tokens.isDark) (if (current) 0.16f else 0.08f) else (if (current) 0.12f else 0.05f))
    val updated = remember(chat.updatedAt) {
        if (chat.updatedAt <= 0) null
        else DateUtils.getRelativeTimeSpanString(chat.updatedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    }
    Row(
        Modifier.fillMaxWidth().clip(shape).background(fill).border(1.dp, colors.primary.copy(alpha = if (current) 0.6f else 0.12f), shape)
            .then(modifier).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        Box(Modifier.size(32.dp).background(colors.primary.copy(alpha = if (current) 0.16f else 0.08f), CircleShape), contentAlignment = Alignment.Center) {
            if (current) BrandGradientIcon(HugeIcons.MessageMultiple01, size = 16.dp)
            else GradeyIcon(HugeIcons.MessageMultiple01, size = 16.dp, tint = tokens.secondaryLabel)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(chat.title, style = GradeyType.subheadlineSemibold, color = colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (updated != null) Text(updated, style = GradeyType.caption, color = tokens.secondaryLabel)
        }
        GradeyIcon(HugeIcons.ArrowRight01, size = 13.dp, tint = tokens.secondaryLabel)
    }
}

/** Destructive data control: red icon and text on red @6 % (iOS `GradeyAIManageRowLabel`). */
@Composable
private fun ManageRow(icon: String, title: String, enabled: Boolean, tag: String, onClick: () -> Unit) {
    val red = GradeyColors.Poor
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.4f }.clip(RoundedCornerShape(TileCorner)).background(red.copy(alpha = 0.06f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(GradeySpacing.md),
    ) {
        Box(Modifier.size(32.dp).background(red.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
            GradeyIcon(icon, size = 16.dp, tint = red)
        }
        Text(title, Modifier.weight(1f), style = GradeyType.subheadlineSemibold, color = red)
    }
}

// MARK: - Helpers

/** Glyph of each action, the same as iOS `GradeyAIChatScreen.icon(for:)`. */
private val AIAction.icon: String
    get() = when (this) {
        AIAction.REPLY -> HugeIcons.Sparkles
        AIAction.TOMORROW -> HugeIcons.Sun01
        AIAction.PRIORITIES -> HugeIcons.Target02
        AIAction.WEEK -> HugeIcons.CalendarCheckIn02
        AIAction.SUBJECT -> HugeIcons.Book02
        AIAction.TEST -> HugeIcons.CheckList
    }

/** Name of the subject or test the selection points at, shown next to the action title. */
private val AIState.selectedContextName: String?
    get() = when (selection.action) {
        AIAction.SUBJECT -> availableSubjects.firstOrNull { it.first == selection.subjectID }?.second
        AIAction.TEST -> availableEvents.firstOrNull { it.id == selection.eventID }?.title
        else -> null
    }
