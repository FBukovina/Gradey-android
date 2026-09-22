package com.bukovinafilip.gradey.feature.ai

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.model.TimetableWeek
import com.bukovinafilip.gradey.ui.GradeyHero
import com.bukovinafilip.gradey.ui.GradeySectionCard
import com.bukovinafilip.gradey.ui.GradeySpacing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Base64
import java.util.UUID

private data class PendingQuestion(val chatID: String, val id: String, val text: String, val context: Map<String, Any?>)

@Composable
fun GradeyAIRoute(
    schoolRepository: SchoolRepository,
    modifier: Modifier = Modifier,
    gradeyAccountID: String? = null,
    isDemo: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val client = remember(context, gradeyAccountID, isDemo) {
        if (isDemo) null else FirebaseGradeyAIClient(context, gradeyAccountID)
    }
    var schoolScope by remember(schoolRepository, gradeyAccountID) { mutableStateOf<String?>(null) }
    var status by remember(client) { mutableStateOf<AIStatus?>(null) }
    var chats by remember(client) { mutableStateOf<List<AIChat>>(emptyList()) }
    var currentChat by remember(client) { mutableStateOf<AIChat?>(null) }
    var messages by remember(client) { mutableStateOf<List<AIMessage>>(emptyList()) }
    var draft by remember(client) { mutableStateOf("") }
    var busy by remember(client) { mutableStateOf(false) }
    var error by remember(client) { mutableStateOf<String?>(null) }
    var shareMarks by remember(client) { mutableStateOf(false) }
    var shareTimetable by remember(client) { mutableStateOf(false) }
    var pending by remember(client) { mutableStateOf<PendingQuestion?>(null) }
    var confirmAction by remember { mutableStateOf<String?>(null) }
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    suspend fun refreshStatusAndChats() {
        val ai = checkNotNull(client)
        val session = schoolRepository.currentStoredSession()
            ?: kotlin.error("Connect a school account to use Gradey AI.")
        val nextScope = AIContext.schoolScope(session, schoolScopeSalt(context))
        if (schoolScope != null && schoolScope != nextScope) {
            currentChat = null
            messages = emptyList()
            draft = ""
            pending = null
        }
        schoolScope = nextScope
        status = ai.status()
        chats = if (status?.consentRequired == false) ai.conversations(nextScope) else emptyList()
    }

    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        coroutineScope.launch {
            try {
                block()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message?.takeIf { it.isNotBlank() && it.length < 500 }
                    ?: "Gradey AI could not complete your request. Please try again."
            } finally {
                busy = false
            }
        }
    }

    fun openChat(chat: AIChat) = action {
        val detail = checkNotNull(client).load(chat.id)
        currentChat = detail.chat
        messages = detail.messages
        detail.status?.let { status = it }
        draft = ""
        pending = null
    }

    fun send() = action {
        val ai = checkNotNull(client)
        val chat = checkNotNull(currentChat)
        check(status?.canSend == true) { "Refresh your AI status before sending another question." }
        val text = draft.trim()
        require(text.isNotBlank() && text.length <= 2_000) { "Enter a question of 2,000 characters or fewer." }
        val session = schoolRepository.currentStoredSession() ?: kotlin.error("Connect your school account again.")
        val activeScope = AIContext.schoolScope(session, schoolScopeSalt(context))
        check(activeScope == schoolScope) { "Your school account changed. Return to conversations and refresh first." }
        val request = pending?.takeIf { it.chatID == chat.id && it.text == text } ?: run {
            // Fetch only the sections selected by the user. No school context is sent during status/list calls.
            val dashboard = if (shareMarks) schoolRepository.loadDashboard(forceRefresh = true) else null
            val weeks = if (shareTimetable) {
                val today = LocalDate.now()
                listOf(schoolRepository.loadTimetable(today.toString()), schoolRepository.loadTimetable(today.plusWeeks(1).toString()))
            } else emptyList<TimetableWeek>()
            PendingQuestion(chat.id, UUID.randomUUID().toString(), text,
                AIContext.build(activeScope, dashboard, weeks, shareMarks, shareTimetable)).also { pending = it }
        }
        val detail = ai.reply(request.chatID, request.id, request.text, request.context)
        currentChat = detail.chat
        messages = detail.messages
        pending = null
        draft = ""
        detail.status?.let { status = it }
        refreshStatusAndChats()
    }

    LaunchedEffect(client, schoolRepository) {
        if (client?.isConfigured == true) {
            busy = true
            try {
                refreshStatusAndChats()
            } catch (failure: CancellationException) {
                throw failure
            } catch (failure: Exception) {
                error = failure.message ?: "Gradey AI could not be reached."
            } finally {
                busy = false
            }
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem((listState.layoutInfo.totalItemsCount - 1).coerceAtLeast(0))
    }

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(modifier = Modifier.fillMaxSize().imePadding()) {
            if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(GradeySpacing.lg),
                verticalArrangement = Arrangement.spacedBy(GradeySpacing.md),
            ) {
                item { GradeyHero("Gradey AI", "Make a plan for your next school day.") }
                if (isDemo || client?.isConfigured != true) {
                    item {
                        GradeySectionCard {
                            Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Gradey AI is unavailable here", style = MaterialTheme.typography.titleMedium)
                            Text(if (isDemo) "Connect your school account in the live app to use Gradey AI. Demo mode doesn’t send questions or school data."
                                else "Gradey AI is not available in this build yet. You can continue using your school features.")
                        }
                    }
                } else {
                    error?.let { message -> item {
                        GradeySectionCard {
                            Text(message, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = { if (currentChat != null) openChat(checkNotNull(currentChat)) else action { refreshStatusAndChats() } }, enabled = !busy) {
                                Text(if (currentChat == null) "Retry" else "Reload conversation")
                            }
                        }
                    } }
                    val state = status
                    if (state != null && state.consentRequired) {
                        item {
                            GradeySectionCard(title = "Use Gradey AI?") {
                                Text("You are talking to an artificial intelligence system. Replies are generated automatically and can contain mistakes.")
                                Text("Your questions, recent chat history, and the school context you choose are sent through Firebase to Microsoft Azure AI. Your school password and raw school responses are never shared.")
                                Text("Guest chats are deleted after 30 days of inactivity. You can delete conversations or withdraw consent at any time.")
                                Text("Terms version: ${state.termsVersion}", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = { action { status = checkNotNull(client).acceptConsent(state.termsVersion); refreshStatusAndChats() } },
                                    enabled = !busy && state.termsVersion.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Agree and continue") }
                            }
                        }
                    } else if (state != null) {
                        item {
                            GradeySectionCard {
                                Text(if (state.enabled) "${state.remaining} of ${state.dailyLimit} questions remaining today" else "Gradey AI is temporarily unavailable",
                                    fontWeight = FontWeight.SemiBold)
                                state.resetAt?.let { Text("Resets ${formatReset(it)}", style = MaterialTheme.typography.bodySmall) }
                                Row(horizontalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                                    TextButton(onClick = { action { refreshStatusAndChats() } }, enabled = !busy) { Text("Refresh status") }
                                    TextButton(onClick = { confirmAction = "revoke" }, enabled = !busy) { Text("Withdraw consent") }
                                }
                            }
                        }
                        if (currentChat == null) {
                            item {
                                Button(onClick = { action { currentChat = checkNotNull(client).create(checkNotNull(schoolScope)); messages = emptyList(); draft = ""; refreshStatusAndChats() } },
                                    enabled = !busy && state.canSend, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Text("New conversation")
                                }
                            }
                            if (chats.isEmpty() && !busy) item {
                                GradeySectionCard(title = "What would you like to work on?") {
                                    Text("Start a conversation to make a study plan, understand a topic, or review your marks.")
                                }
                            }
                            items(chats, key = { it.id }) { chat ->
                                GradeySectionCard(modifier = Modifier.clickable(enabled = !busy) { openChat(chat) }) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(chat.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                                        IconButton(onClick = { confirmAction = chat.id }, enabled = !busy) {
                                            Icon(Icons.Default.DeleteOutline, contentDescription = "Delete ${chat.title}")
                                        }
                                    }
                                }
                            }
                            if (chats.isNotEmpty()) item {
                                TextButton(onClick = { confirmAction = "all" }, enabled = !busy) { Text("Delete all conversations for this school") }
                            }
                        } else {
                            item {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconButton(onClick = { currentChat = null; messages = emptyList(); draft = ""; pending = null }, enabled = !busy) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to conversations")
                                    }
                                    Text(currentChat?.title.orEmpty(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                                    IconButton(onClick = { openChat(checkNotNull(currentChat)) }, enabled = !busy) {
                                        Icon(Icons.Default.Refresh, contentDescription = "Reload conversation")
                                    }
                                }
                            }
                            item {
                                GradeySectionCard(title = "Context for your next question") {
                                    ContextCheckbox("Recent marks and subject averages", shareMarks, !busy && pending == null) { shareMarks = it }
                                    ContextCheckbox("This week’s and next week’s timetable", shareTimetable, !busy && pending == null) { shareTimetable = it }
                                    Text(if (!shareMarks && !shareTimetable) "Only your question and chat history will be shared."
                                        else "Only the selected school details will be included. Your profile, login details, and attendance are excluded.",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (messages.isEmpty()) item {
                                Text("Try: Help me build a revision plan for this week.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            items(messages, key = { it.id }) { message ->
                                GradeySectionCard {
                                    Text(if (message.role == "user") "You" else "Gradey AI", style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.primary)
                                    SelectionContainer { Text(message.text.ifBlank { "No reply text is available yet." }) }
                                    if (message.status !in listOf("complete", "completed")) Text("Status: ${message.status}", style = MaterialTheme.typography.bodySmall)
                                }
                            }
                            if (busy) item { Text("Working on your request…", color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                }
            }
            if (currentChat != null && status?.consentRequired == false) {
                Column(modifier = Modifier.padding(GradeySpacing.lg), verticalArrangement = Arrangement.spacedBy(GradeySpacing.sm)) {
                    OutlinedTextField(value = draft, onValueChange = { draft = it }, label = { Text("Ask Gradey AI") },
                        modifier = Modifier.fillMaxWidth(), enabled = !busy, maxLines = 5,
                        supportingText = { Text("${draft.length} / 2,000") }, isError = draft.length > 2_000,
                        trailingIcon = {
                            IconButton(onClick = ::send, enabled = !busy && status?.canSend == true && draft.isNotBlank() && draft.trim().length <= 2_000) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = if (pending?.text == draft.trim()) "Retry question" else "Send question")
                            }
                        })
                    Text("AI can make mistakes. Check important answers.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    confirmAction?.let { operation ->
        AlertDialog(onDismissRequest = { confirmAction = null },
            title = { Text(if (operation == "revoke") "Withdraw AI consent?" else "Delete conversations?") },
            text = { Text(when (operation) {
                "revoke" -> "Gradey AI will be disabled and your AI data will be removed according to the service’s consent policy."
                "all" -> "All AI conversations for this school will be deleted. This cannot be undone."
                else -> "This conversation will be permanently deleted."
            }) },
            confirmButton = { TextButton(onClick = {
                confirmAction = null
                action {
                    when (operation) {
                        "revoke" -> {
                            checkNotNull(client).revokeConsent()
                            currentChat = null; messages = emptyList(); chats = emptyList(); draft = ""; pending = null
                            status = status?.copy(consentRequired = true)
                        }
                        "all" -> { checkNotNull(client).deleteAll(checkNotNull(schoolScope)); chats = emptyList(); refreshStatusAndChats() }
                        else -> { checkNotNull(client).delete(operation); chats = chats.filterNot { it.id == operation }; refreshStatusAndChats() }
                    }
                }
            }, enabled = !busy) { Text(if (operation == "revoke") "Withdraw consent" else "Delete") } },
            dismissButton = { TextButton(onClick = { confirmAction = null }) { Text("Cancel") } })
    }
}

@Composable
private fun ContextCheckbox(label: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }) {
        Checkbox(checked = checked, onCheckedChange = onChange, enabled = enabled)
        Text(label)
    }
}

private fun schoolScopeSalt(context: Context): ByteArray {
    val store = context.getSharedPreferences("gradey-ai-context", Context.MODE_PRIVATE)
    val stored = store.getString("scope-salt-v1", null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
    if (stored != null && stored.size >= 16) return stored
    return ByteArray(32).also { bytes ->
        SecureRandom().nextBytes(bytes)
        store.edit().putString("scope-salt-v1", Base64.getEncoder().encodeToString(bytes)).apply()
    }
}

private fun formatReset(millis: Long): String = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    .format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
