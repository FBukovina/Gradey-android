package com.bukovinafilip.gradey.feature.ai

import com.bukovinafilip.gradey.ui.HugeIcons
import com.bukovinafilip.gradey.ui.HugeIcon

import android.content.Context
import android.app.Activity
import android.content.ContextWrapper
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bukovinafilip.gradey.domain.GradeyHistoryRepository
import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.domain.SchoolInsightStore
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.ui.GradeyHero
import com.bukovinafilip.gradey.ui.GradeySectionCard
import com.bukovinafilip.gradey.ui.GradeySpacing
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64

@Composable
fun GradeyAIRoute(
    schoolRepository: SchoolRepository,
    modifier: Modifier = Modifier,
    gradeyAccountID: String? = null,
    isDemo: Boolean = false,
    historyRepository: GradeyHistoryRepository? = null,
    schoolAccountKey: String? = null,
    gradeyAuthRepository: GradeyAuthRepository? = null,
    plannerItems: List<PersonalPlannerItem>? = null,
    initialAction: AIAction? = null,
    initialSubjectID: String? = null,
    initialEventID: String? = null,
    initialWeek: String? = null,
    initialRequestKey: String? = null,
    insightStore: SchoolInsightStore? = null,
) {
    val activityContext = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val localeTag = androidx.compose.ui.platform.LocalConfiguration.current.locales[0].toLanguageTag()
    val context = activityContext.applicationContext
    val client = remember(context, gradeyAccountID, isDemo, gradeyAuthRepository) {
        if (isDemo) null else FirebaseGradeyAIClient(context, gradeyAccountID, gradeyAuthRepository)
    }
    val controller: GradeyAIController = viewModel(
        key = "gradey-ai:${AIIdentitySession.generation}:${System.identityHashCode(schoolRepository)}:$gradeyAccountID:$schoolAccountKey:$isDemo",
        factory = remember(client, schoolRepository, historyRepository, insightStore) { object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                GradeyAIController(client, schoolRepository, schoolScopeSalt(context), historyRepository, PreferencesAIRequestStore(context, gradeyAccountID), insightStore) as T
        } },
    )
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(controller, plannerItems, localeTag) { controller.plannerItems(plannerItems); controller.locale(localeTag) }
    LaunchedEffect(controller, initialAction, initialSubjectID, initialEventID, initialWeek, initialRequestKey) {
        val action = initialAction ?: when {
            initialEventID != null -> AIAction.TEST
            initialSubjectID != null -> AIAction.SUBJECT
            initialWeek != null -> AIAction.WEEK
            else -> null
        }
        if (action != null) {
            val week = if (action == AIAction.WEEK) runCatching { LocalDate.parse(initialWeek?.take(10) ?: LocalDate.now().toString()) }
                .getOrDefault(LocalDate.now()).let { it.minusDays((it.dayOfWeek.value - 1).toLong()).toString() } else null
            controller.initialEntry(AISelection(action, subjectID = initialSubjectID, eventID = initialEventID, weekStart = week),
                initialRequestKey, resources.getString(action.promptResource))
        }
    }
    DisposableEffect(controller, activityContext) {
        onDispose { if (activityContext.findActivity()?.isChangingConfigurations != true) controller.stop() }
    }
    GradeyAIScreen(state, controller, isDemo, client?.isConfigured == true, modifier)
}

@Composable
internal fun GradeyAIScreen(state: AIState, controller: GradeyAIController, isDemo: Boolean, configured: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var confirmation by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var showContext by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val chat = state.currentChat
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(state.messages.lastOrNull()?.text, state.messages.size) {
        val lastVisible = list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
        if (state.messages.isNotEmpty() && !list.isScrollInProgress && list.layoutInfo.totalItemsCount - lastVisible <= 3) {
            list.animateScrollToItem((list.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
        }
    }
    Surface(modifier.fillMaxSize(), color = androidx.compose.ui.graphics.Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) {
        Column(Modifier.fillMaxSize().imePadding()) {
            if (chat != null) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = controller::close, enabled = !state.busy) { HugeIcon(HugeIcons.ArrowLeft01, resources.getString(R.string.gradey_ai_conversations_back)) }
                    Column(Modifier.weight(1f)) {
                        Text(chat.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(resources.getString(R.string.ai_companion), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = controller::reload, enabled = !state.busy) { HugeIcon(HugeIcons.Refresh04, resources.getString(R.string.ai_reload)) }
                    Box {
                        IconButton(onClick = { menu = true }, enabled = !state.busy) { HugeIcon(HugeIcons.MoreVertical, resources.getString(R.string.gradey_ai_options)) }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(resources.getString(R.string.gradey_ai_conversation_delete_action)) }, onClick = { menu = false; confirmation = chat.id },
                                leadingIcon = { HugeIcon(HugeIcons.Delete02, null) })
                            DropdownMenuItem(text = { Text(resources.getString(R.string.gradey_ai_privacy_revoke_action)) }, onClick = { menu = false; confirmation = "revoke" })
                        }
                    }
                }
            }
            if (state.busy && !state.sending) LinearProgressIndicator(Modifier.fillMaxWidth())
            LazyColumn(state = list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (chat == null) item { GradeyHero(resources.getString(R.string.gradey_ai_welcome_title), resources.getString(R.string.gradey_ai_welcome_message)) }
                if (isDemo || !configured) item {
                    GradeySectionCard {
                        AIEmblem()
                        Text(if (isDemo) resources.getString(R.string.ai_demo_title) else resources.getString(R.string.ai_setup_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(if (isDemo) resources.getString(R.string.ai_demo_message)
                            else resources.getString(R.string.ai_setup_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        PromptExamples(enabled = false, onPrompt = {})
                    }
                } else {
                    state.error?.let { error -> item {
                        Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(16.dp)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(localizedAIMessage(context, error), color = MaterialTheme.colorScheme.onErrorContainer)
                                Row {
                                    TextButton(onClick = { if (chat == null) controller.refresh() else controller.reload() }, enabled = !state.busy) {
                                        Text(if (chat == null) resources.getString(R.string.ai_retry) else resources.getString(R.string.ai_reload))
                                    }
                                    TextButton(onClick = controller::dismissError) { Text(resources.getString(R.string.ai_dismiss)) }
                                }
                            }
                        }
                    } }
                    val status = state.status
                    if (state.recovery != null && !state.sending) item {
                        GradeySectionCard(title = resources.getString(R.string.gradey_ai_compute_check_request)) {
                            Text(resources.getString(R.string.gradey_ai_compute_pending), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = controller::recover, enabled = !state.busy) { Text(resources.getString(R.string.gradey_ai_compute_check_request)) }
                        }
                    }
                    when {
                        status == null -> if (!state.busy && state.error == null) item {
                            GradeySectionCard(title = resources.getString(R.string.ai_connect_title)) { Text(resources.getString(R.string.ai_connect_message)); Button(onClick = controller::refresh) { Text(resources.getString(R.string.ai_refresh)) } }
                        }
                        status.consentRequired -> item { ConsentCard(status, state.busy, controller::acceptConsent) }
                        chat == null -> {
                            item { UsageCard(status, state.busy, controller::refresh) { confirmation = "revoke" } }
                            item { ActionPicker(state, controller::selectAction, controller::selectSubject, controller::selectEvent, controller::includeEventNotes) }
                            item {
                                Button(onClick = { controller.newChat(if (state.selection.action == AIAction.REPLY) "" else resources.getString(state.selection.action.promptResource)) },
                                    enabled = !state.busy && status.canAfford(state.selection.action) && (state.selection.action != AIAction.SUBJECT || state.selection.subjectID != null) && (state.selection.action != AIAction.TEST || state.selection.eventID != null), modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
                                    HugeIcon(HugeIcons.Add01, null, Modifier.size(20.dp)); Spacer(Modifier.size(8.dp)); Text(resources.getString(R.string.gradey_ai_new_chat))
                                }
                            }
                            if (state.chats.isEmpty()) item {
                                GradeySectionCard {
                                    AIEmblem()
                                    Text(resources.getString(R.string.gradey_ai_empty_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                                    Text(resources.getString(R.string.gradey_ai_empty_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    PromptExamples(!state.busy && status.canAfford(state.selection.action), controller::newChat)
                                }
                            } else {
                                item { Text(resources.getString(R.string.gradey_ai_conversations_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                                items(state.chats, key = { "chat-${it.id}" }) { conversation ->
                                    GradeySectionCard(Modifier.clickable(enabled = !state.busy) { controller.open(conversation) }) {
                                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            HugeIcon(HugeIcons.Message01, null, tint = MaterialTheme.colorScheme.primary)
                                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(conversation.title, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                if (conversation.updatedAt > 0) Text(formatDate(conversation.updatedAt, context), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            IconButton(onClick = { confirmation = conversation.id }, enabled = !state.busy) { HugeIcon(HugeIcons.Delete02, resources.getString(R.string.ai_delete_named, conversation.title)) }
                                        }
                                    }
                                }
                                item { TextButton(onClick = { confirmation = "all" }, enabled = !state.busy) { Text(resources.getString(R.string.gradey_ai_delete_all_action)) } }
                            }
                        }
                        else -> {
                            item {
                                ContextCard(state, showContext, { showContext = !showContext }, controller::refreshContext)
                            }
                            if (!state.purposeCompatible) item {
                                Text(resources.getString(R.string.gradey_ai_context_new_purpose), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            if (state.messages.isEmpty() && !state.sending) item {
                                Column(Modifier.fillMaxWidth().padding(vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    AIEmblem()
                                    Text(resources.getString(R.string.gradey_ai_chat_empty_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                                    Text(resources.getString(R.string.gradey_ai_chat_empty_message), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (state.selection.action == AIAction.REPLY) PromptExamples(!state.busy, controller::draft)
                                    else OutlinedButton(onClick = { controller.draft(resources.getString(state.selection.action.promptResource)) }, enabled = !state.busy) { Text(resources.getString(state.selection.action.promptResource)) }
                                }
                            }
                            items(state.messages, key = { "message-${it.id}" }) { message -> MessageBubble(message) }
                            if (state.sending && state.messages.none { it.status == "streaming" }) item {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Text(resources.getString(R.string.gradey_ai_responding), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            state.notice?.let { notice -> item { Text(localizedAIMessage(context, notice), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) } }
                            if (state.pending != null && !state.busy) item {
                                OutlinedButton(onClick = { controller.send(retry = true) }, enabled = status.enabled && !status.consentRequired) {
                                    HugeIcon(HugeIcons.Refresh04, null, Modifier.size(18.dp)); Spacer(Modifier.size(8.dp)); Text(resources.getString(R.string.ai_retry_question))
                                }
                            }
                        }
                    }
                }
            }
            if (chat != null && state.status?.consentRequired == false) {
                Surface(color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(state.draft, controller::draft, Modifier.fillMaxWidth(), enabled = !state.sending,
                            placeholder = { Text(resources.getString(R.string.gradey_ai_composer_placeholder)) }, minLines = 1, maxLines = 5,
                            shape = RoundedCornerShape(20.dp), isError = state.draftLength > 2_000,
                            trailingIcon = {
                                FilledIconButton(onClick = { if (state.sending) controller.stop() else { keyboard?.hide(); controller.send() } },
                                    enabled = state.sending || state.canSend, modifier = Modifier.padding(end = 8.dp)) {
                                    HugeIcon(if (state.sending) HugeIcons.Stop else HugeIcons.Sent,
                                        if (state.sending) resources.getString(R.string.gradey_ai_stop) else resources.getString(R.string.gradey_ai_send), Modifier.size(20.dp))
                                }
                            })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(if (state.status?.enabled == false) resources.getString(R.string.gradey_ai_availability_paused_title) else resources.getString(R.string.ai_compute_composer, state.status?.remaining ?: 0, state.status?.cost(state.selection.action)?.toString() ?: "—"),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(resources.getString(R.string.ai_counter, state.draftLength), style = MaterialTheme.typography.labelSmall,
                                color = if (state.draftLength > 2_000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (state.status?.remaining == 0) state.status.resetAt?.let { Text(resources.getString(R.string.gradey_ai_limit_resets, formatDate(it, context)), style = MaterialTheme.typography.bodySmall) }
                        Text(resources.getString(R.string.gradey_ai_disclaimer), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
    confirmation?.let { operation ->
        AlertDialog(onDismissRequest = { confirmation = null },
            title = { Text(if (operation == "revoke") resources.getString(R.string.gradey_ai_privacy_revoke_title) else resources.getString(R.string.gradey_ai_conversation_delete_title)) },
            text = { Text(when (operation) {
                "revoke" -> resources.getString(R.string.gradey_ai_privacy_revoke_message)
                "all" -> resources.getString(R.string.gradey_ai_delete_all_message)
                else -> resources.getString(R.string.gradey_ai_conversation_delete_message)
            }) },
            confirmButton = { TextButton(onClick = {
                confirmation = null
                when (operation) { "revoke" -> controller.revoke(); "all" -> controller.deleteAll(); else -> controller.delete(operation) }
            }, enabled = !state.busy) { Text(if (operation == "revoke") resources.getString(R.string.gradey_ai_privacy_revoke_action) else resources.getString(R.string.ai_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmation = null }) { Text(resources.getString(R.string.ai_cancel)) } })
    }
}

@Composable
private fun AIEmblem() {
    Box(Modifier.size(48.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        HugeIcon(HugeIcons.Sparkles, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(25.dp))
    }
}

@Composable
private fun ConsentCard(status: AIStatus, busy: Boolean, onAccept: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    GradeySectionCard {
        AIEmblem()
        Text(resources.getString(R.string.gradey_ai_consent_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(resources.getString(R.string.gradey_ai_consent_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HugeIcon(HugeIcons.University, null, tint = MaterialTheme.colorScheme.primary)
            Text(resources.getString(R.string.gradey_ai_consent_azure_message))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            HugeIcon(HugeIcons.SecurityCheck, null, tint = MaterialTheme.colorScheme.primary)
            Text(resources.getString(R.string.gradey_ai_consent_retention_message))
        }
        Text(resources.getString(R.string.ai_notice_version, status.termsVersion), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick = onAccept, enabled = !busy && status.termsVersion.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text(resources.getString(R.string.gradey_ai_consent_action)) }
    }
}

@Composable
private fun UsageCard(status: AIStatus, busy: Boolean, refresh: () -> Unit, revoke: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    GradeySectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(if (status.enabled) resources.getString(R.string.ai_compute_available, status.remaining) else resources.getString(R.string.gradey_ai_availability_paused_title), fontWeight = FontWeight.SemiBold)
                Text(if (status.linked) resources.getString(R.string.ai_allowance_linked, status.dailyLimit) else resources.getString(R.string.ai_allowance_guest, status.dailyLimit), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = refresh, enabled = !busy) { HugeIcon(HugeIcons.Refresh04, resources.getString(R.string.ai_refresh_status)) }
        }
        if (status.dailyLimit > 0) LinearProgressIndicator(progress = { (status.remaining.toFloat() / status.dailyLimit).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
        if ((status.compute?.reserved ?: 0) > 0) Text(resources.getString(R.string.ai_reserved, status.compute?.reserved ?: 0), style = MaterialTheme.typography.bodySmall)
        status.resetAt?.let { Text(resources.getString(R.string.gradey_ai_limit_resets, formatDate(it, context)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        TextButton(onClick = revoke, enabled = !busy) { Text(resources.getString(R.string.ai_privacy)) }
    }
}

@Composable
private fun ActionPicker(state: AIState, select: (AIAction) -> Unit, subject: (String) -> Unit, event: (String) -> Unit, notes: (Boolean) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    var subjectMenu by remember { mutableStateOf(false) }
    var actionMenu by remember { mutableStateOf(false) }
    var eventMenu by remember { mutableStateOf(false) }
    GradeySectionCard(title = resources.getString(R.string.gradey_ai_context_choose_action)) {
        Box {
            OutlinedButton(onClick = { actionMenu = true }, enabled = !state.busy, modifier = Modifier.fillMaxWidth()) {
                Text(resources.getString(state.selection.action.titleResource), Modifier.weight(1f))
                Text(state.status?.cost(state.selection.action)?.let { resources.getString(R.string.ai_price, it) }
                    ?: resources.getString(R.string.gradey_ai_compute_action_unavailable), style = MaterialTheme.typography.labelMedium)
                HugeIcon(HugeIcons.ArrowDown01, null, Modifier.padding(start = 8.dp).size(18.dp))
            }
            DropdownMenu(actionMenu, { actionMenu = false }) {
                AIAction.entries.forEach { action ->
                    val cost = state.status?.cost(action)
                    DropdownMenuItem(enabled = cost != null, text = {
                        Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(resources.getString(action.titleResource), Modifier.weight(1f))
                            Text(cost?.let { resources.getString(R.string.ai_price, it) } ?: resources.getString(R.string.gradey_ai_compute_action_unavailable),
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }, onClick = { actionMenu = false; select(action) }, leadingIcon = {
                        HugeIcon(if (state.selection.action == action) HugeIcons.CheckmarkCircle02 else HugeIcons.Sparkles, null, Modifier.size(18.dp))
                    })
                }
            }
        }
        Text(when (state.selection.action) {
            AIAction.REPLY -> resources.getString(R.string.gradey_ai_context_none)
            AIAction.TOMORROW -> resources.getString(R.string.ai_share_tomorrow)
            AIAction.WEEK -> resources.getString(R.string.ai_share_week)
            AIAction.PRIORITIES -> resources.getString(R.string.ai_share_priorities)
            AIAction.SUBJECT -> resources.getString(R.string.ai_share_subject)
            AIAction.TEST -> resources.getString(R.string.ai_share_test)
        }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (state.selection.action == AIAction.TEST) {
            Box {
                OutlinedButton(onClick = { eventMenu = true }, enabled = !state.busy && state.availableEvents.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(state.availableEvents.firstOrNull { it.id == state.selection.eventID }?.title ?: resources.getString(R.string.ai_choose_test))
                }
                DropdownMenu(eventMenu, { eventMenu = false }) {
                    state.availableEvents.forEach { item -> DropdownMenuItem(text = { Text(item.title) }, onClick = { eventMenu = false; event(item.id) }) }
                }
            }
            if (state.availableEvents.isEmpty()) Text(resources.getString(R.string.ai_add_test), style = MaterialTheme.typography.bodySmall)
            if (state.selection.eventID != null) Row(verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(enabled = !state.busy) { notes(!state.selection.includeNotes) }) {
                Checkbox(state.selection.includeNotes, notes, enabled = !state.busy)
                Text(resources.getString(R.string.ai_include_notes), style = MaterialTheme.typography.bodySmall)
            }
        }
        if (state.selection.action == AIAction.SUBJECT) Box {
            OutlinedButton(onClick = { subjectMenu = true }, enabled = !state.busy && state.availableSubjects.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                Text(state.availableSubjects.firstOrNull { it.first == state.selection.subjectID }?.second ?: resources.getString(R.string.ai_choose_subject))
            }
            DropdownMenu(subjectMenu, { subjectMenu = false }) {
                state.availableSubjects.forEach { (id, title) -> DropdownMenuItem(text = { Text(title) }, onClick = { subjectMenu = false; subject(id) }) }
            }
        }
    }
}

@Composable
private fun ContextCard(state: AIState, expanded: Boolean, toggle: () -> Unit, refresh: () -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val selected = state.selection.action != AIAction.REPLY
    val stale = state.context?.boolean("isStale") == true
    GradeySectionCard {
        Row(Modifier.fillMaxWidth().clickable(onClick = toggle), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            HugeIcon(if (selected) HugeIcons.University else HugeIcons.SecurityCheck, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f)) {
                Text(resources.getString(state.selection.action.titleResource), style = MaterialTheme.typography.labelLarge)
                Text(when { !selected -> resources.getString(R.string.gradey_ai_context_none); stale -> resources.getString(R.string.gradey_ai_context_partial); state.context == null -> resources.getString(R.string.ai_refresh_before_send); else -> resources.getString(R.string.gradey_ai_context_ready) },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HugeIcon(if (expanded) HugeIcons.ArrowUp01 else HugeIcons.ArrowDown01, if (expanded) resources.getString(R.string.ai_hide_context) else resources.getString(R.string.ai_show_context))
        }
        if (expanded) {
            Text(if (state.selection.includeNotes) resources.getString(R.string.ai_notes_shared) else resources.getString(R.string.ai_context_privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.context?.let { snapshot ->
                val subjects = (snapshot["subjects"] as? List<*>)?.size ?: 0
                val lessons = (snapshot["timetable"] as? List<*>)?.size ?: 0
                val trends = (snapshot["trends"] as? List<*>)?.size ?: 0
                val events = (snapshot["events"] as? List<*>)?.size ?: 0
                val insights = (snapshot["insights"] as? List<*>)?.size ?: 0
                Text(resources.getString(R.string.ai_context_counts, subjects, trends, lessons, events, insights), style = MaterialTheme.typography.labelMedium)
                val unavailable = (snapshot["unavailableSections"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                if (unavailable.isNotEmpty()) Text(resources.getString(R.string.ai_unavailable_sections, unavailable.joinToString { resources.getString(aiSectionResource(it)) }), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                (snapshot["generatedAt"] as? Long)?.let { Text(resources.getString(R.string.ai_updated, formatDate(it, context)), style = MaterialTheme.typography.labelSmall) }
                if (state.selection.action == AIAction.TOMORROW) {
                    (snapshot["timetable"] as? List<*>).orEmpty().forEach { lesson ->
                        val item = lesson.objectMap()
                        Text("${item.string("beginsAt").orEmpty()}  ${item.string("subject").orEmpty()}${if (item["changeKind"] == "cancelled") resources.getString(R.string.ai_cancelled_suffix) else ""}", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            TextButton(onClick = refresh, enabled = !state.busy && selected) { HugeIcon(HugeIcons.Refresh04, null, Modifier.size(18.dp)); Spacer(Modifier.size(6.dp)); Text(resources.getString(R.string.gradey_ai_context_refresh)) }
        }
    }
}

@Composable
private fun PromptExamples(enabled: Boolean, onPrompt: (String) -> Unit) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    listOf(resources.getString(R.string.gradey_ai_prompt_general1), resources.getString(R.string.gradey_ai_prompt_general2)).forEach { prompt ->
        OutlinedButton(onClick = { onPrompt(prompt) }, enabled = enabled, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp)) {
            Text(prompt, modifier = Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun MessageBubble(message: AIMessage) {
    val context = LocalContext.current
    val resources = androidx.compose.ui.platform.LocalResources.current
    val user = message.role == "user"
    val clipboard = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Column(Modifier.widthIn(max = 640.dp).then(if (user) Modifier.padding(start = 28.dp) else Modifier.fillMaxWidth()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (!user) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HugeIcon(HugeIcons.Sparkles, null, Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Gradey AI", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            Surface(color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = if (user) 4.dp else 18.dp, bottomStart = if (user) 18.dp else 4.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (message.text.isNotBlank()) SelectionContainer {
                        if (user) Text(message.text, style = MaterialTheme.typography.bodyLarge) else AIMarkdownText(message.text)
                    } else Text(if (message.status == "streaming") resources.getString(R.string.gradey_ai_responding) else resources.getString(R.string.ai_no_answer), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    when (message.status) {
                        "streaming" -> LinearProgressIndicator(Modifier.fillMaxWidth())
                        "failed" -> Text(resources.getString(R.string.ai_interrupted), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                        "cancelled" -> Text(resources.getString(R.string.gradey_ai_response_cancelled), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (!user && message.text.isNotBlank() && message.status != "streaming") {
                TextButton(onClick = { clipboard.setText(AnnotatedString(message.text)) }, contentPadding = PaddingValues(horizontal = 4.dp)) {
                    HugeIcon(HugeIcons.Copy01, null, Modifier.size(15.dp)); Spacer(Modifier.size(6.dp)); Text(resources.getString(R.string.ai_copy), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Synchronized
internal fun schoolScopeSalt(context: Context): ByteArray {
    val store = context.getSharedPreferences("gradey-ai-context", Context.MODE_PRIVATE)
    val stored = store.getString("scope-salt-v1", null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    if (stored != null && stored.size >= 16) return stored
    return ByteArray(32).also { bytes ->
        SecureRandom().nextBytes(bytes)
        check(store.edit().putString("scope-salt-v1", Base64.getEncoder().encodeToString(bytes)).commit()) { "The private AI scope could not be saved." }
    }
}

private fun formatDate(millis: Long, context: Context): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(context.resources.configuration.locales[0]))

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
