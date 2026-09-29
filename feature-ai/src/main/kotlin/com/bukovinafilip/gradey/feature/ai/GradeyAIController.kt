package com.bukovinafilip.gradey.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bukovinafilip.gradey.domain.GradeyHistoryRepository
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.domain.SchoolInsightStore
import com.bukovinafilip.gradey.domain.isEduPageParentSession
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.util.Locale

internal data class PendingQuestion(val chatID: String, val id: String, val text: String, val context: Map<String, Any?>,
    val selection: AISelection = AISelection(), val catalogVersion: String? = null, val maximumComputeCost: Int = 1,
    val locale: String = Locale.getDefault().toLanguageTag()) {
    val reference get() = AIRequestReference(chatID, id, checkNotNull(context.string("schoolScope")), selection.id)
}

internal data class AIState(
    val status: AIStatus? = null,
    val chats: List<AIChat> = emptyList(),
    val currentChat: AIChat? = null,
    val messages: List<AIMessage> = emptyList(),
    val draft: String = "",
    val busy: Boolean = false,
    val sending: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val shareMarks: Boolean = false,
    val shareTimetable: Boolean = false,
    val context: Map<String, Any?>? = null,
    val pending: PendingQuestion? = null,
    val recovery: AIRequestReference? = null,
    val selection: AISelection = AISelection(),
    val availableSubjects: List<Pair<String, String>> = emptyList(),
    val availableEvents: List<AIPlannerEvent> = emptyList(),
) {
    val draftLength get() = draft.codePointCount(0, draft.length)
    val purposeCompatible get() = currentChat?.let { chat -> chat.contextSelectionID == selection.id ||
        (selection.action == AIAction.REPLY && (chat.contextSelectionID == null || chat.contextSelectionID == chat.schoolScope)) } ?: true
    val canSend get() = !busy && status?.canAfford(selection.action) == true && draft.isNotBlank() && draftLength <= 2_000 && purposeCompatible &&
        (selection.action != AIAction.SUBJECT || selection.subjectID != null) && (selection.action != AIAction.TEST || selection.eventID != null)
}

/** Keeps an in-flight reply and the composer intact across Activity recreation. */
internal class GradeyAIController(
    private val client: GradeyAIClient?,
    private val school: SchoolRepository,
    private val scopeSalt: ByteArray,
    private val history: GradeyHistoryRepository? = null,
    private val requestStore: AIRequestStore = MemoryAIRequestStore(),
    private val insightStore: SchoolInsightStore? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(AIState())
    val state = mutableState.asStateFlow()
    private var schoolScope: String? = null
    private var operation: Job? = null
    private var plannerItems: List<PersonalPlannerItem>? = null
    private var requestLocale: String = Locale.getDefault().toLanguageTag()
    private var lastInitialEntry: String? = null
    private var entryOperation: Job? = null

    init { if (client?.isConfigured == true) refresh() }

    fun locale(value: String) { requestLocale = value }
    fun draft(value: String) = mutableState.update { it.copy(draft = value) }
    fun dismissError() = mutableState.update { it.copy(error = null) }
    fun plannerItems(items: List<PersonalPlannerItem>?) { plannerItems = items }
    fun selectEvent(id: String) {
        if (!state.value.busy && state.value.currentChat == null) mutableState.update {
            it.copy(selection = it.selection.copy(eventID = id, includeNotes = false), context = null)
        }
    }
    fun includeEventNotes(include: Boolean) {
        if (!state.value.busy && state.value.currentChat == null) mutableState.update {
            it.copy(selection = it.selection.copy(includeNotes = include), context = null)
        }
    }
    fun selectAction(action: AIAction) = action {
        val monday = LocalDate.now(ZoneId.of("Europe/Prague")).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }
        prepareSelection(AISelection(action, weekStart = if (action == AIAction.WEEK) monday.toString() else null))
    }

    /** Entry arguments are a one-time navigation request, never a replacement for the user's next selection. */
    fun initialEntry(selection: AISelection, requestKey: String?, prompt: String) {
        if (client?.isConfigured != true) return
        val key = "${requestKey.orEmpty()}:${selection.id}"
        if (lastInitialEntry == key) return
        lastInitialEntry = key
        entryOperation?.cancel()
        stop()
        entryOperation = viewModelScope.launch {
            state.first { !it.busy }
            if (lastInitialEntry != key) return@launch
            action {
                prepareSelection(selection)
                val value = state.value
                if (value.status?.canAfford(selection.action) == true &&
                    (selection.action != AIAction.SUBJECT || selection.subjectID in value.availableSubjects.map { it.first }) &&
                    (selection.action != AIAction.TEST || selection.eventID in value.availableEvents.map { it.id })) {
                    val chat = ai().create(checkNotNull(schoolScope), selection.id)
                    mutableState.update { it.copy(currentChat = chat, chats = listOf(chat) + it.chats.filterNot { previous -> previous.id == chat.id }, draft = prompt) }
                }
            }
        }
    }

    private suspend fun prepareSelection(selection: AISelection) {
        ensureSchool()
        clearChat()
        mutableState.update { it.copy(selection = selection, context = null,
            shareMarks = selection.action in setOf(AIAction.PRIORITIES, AIAction.SUBJECT, AIAction.TEST),
            shareTimetable = selection.action in setOf(AIAction.TOMORROW, AIAction.WEEK)) }
        loadContext()
    }

    fun selectSubject(id: String) {
        if (!state.value.busy && state.value.currentChat == null) mutableState.update {
            it.copy(selection = it.selection.copy(subjectID = id), context = null)
        }
    }

    fun refresh() = action {
        ensureSchool()
        val status = ai().status()
        val chats = if (!status.consentRequired) ai().conversations(checkNotNull(schoolScope)) else emptyList()
        mutableState.update { it.copy(status = status, chats = chats, recovery = requestStore.load(checkNotNull(schoolScope))) }
        if (!status.consentRequired && state.value.recovery != null) recoverPending()
        if (status.consentRequired) clearChat()
        // A remotely deleted chat must not remain an editable ghost conversation.
        else state.value.currentChat?.takeIf { current -> chats.none { it.id == current.id } }?.let { clearChat() }
    }

    fun acceptConsent() = action {
        val displayedTermsVersion = state.value.status?.termsVersion
        val latest = ai().status()
        mutableState.update { it.copy(status = latest) }
        // Do not accept changed terms that were never displayed to the user.
        check(latest.termsVersion == displayedTermsVersion) { "The privacy notice changed. Review it and tap Agree again." }
        val status = ai().acceptConsent(latest.termsVersion)
        ensureSchool()
        val chats = ai().conversations(checkNotNull(schoolScope))
        mutableState.update { it.copy(status = status, chats = chats) }
    }

    fun newChat(prompt: String = "") = action {
        ensureSchool()
        check(state.value.status?.canAfford(state.value.selection.action) == true) { "Refresh your AI status before starting a conversation." }
        check(state.value.selection.action != AIAction.SUBJECT || state.value.selection.subjectID != null) { "Choose a subject before starting subject help." }
        check(state.value.selection.action != AIAction.TEST || state.value.selection.eventID != null) { "Choose a test from your Planner first." }
        val chat = ai().create(checkNotNull(schoolScope), state.value.selection.id)
        mutableState.update { it.copy(currentChat = chat, chats = listOf(chat) + it.chats.filterNot { value -> value.id == chat.id },
            messages = emptyList(), draft = prompt, pending = null, notice = null) }
    }

    fun open(chat: AIChat) = action {
        ensureSchool()
        applyDetail(ai().load(chat.id))
        val candidates = AIAction.entries.filter { it !in setOf(AIAction.SUBJECT, AIAction.TEST) }.map { AISelection(it,
            weekStart = if (it == AIAction.WEEK) LocalDate.now(ZoneId.of("Europe/Prague")).let { date -> date.minusDays((date.dayOfWeek.value - 1).toLong()).toString() } else null) } +
            state.value.availableSubjects.map { AISelection(AIAction.SUBJECT, subjectID = it.first) } +
            state.value.availableEvents.flatMap { event -> listOf(AISelection(AIAction.TEST, eventID = event.id), AISelection(AIAction.TEST, eventID = event.id, includeNotes = true)) }
        val selection = candidates.firstOrNull { it.id == state.value.currentChat?.contextSelectionID } ?: state.value.selection
        mutableState.update { it.copy(draft = "", pending = null, notice = null, selection = selection, context = null) }
    }

    fun reload() = action {
        ensureSchool()
        val chat = state.value.currentChat ?: return@action
        applyDetail(ai().load(chat.id))
        mutableState.update { it.copy(notice = null) }
        if (state.value.recovery != null) recoverPending()
        val status = ai().status()
        mutableState.update { it.copy(status = status) }
    }

    fun close() {
        if (state.value.busy) return
        clearChat()
    }

    fun refreshContext() = action { ensureSchool(); loadContext() }

    fun send(retry: Boolean = false) = action(sending = true) {
        ensureSchool()
        val frozen = state.value.pending
        val recovery = state.value.recovery
        var recovered: String? = null
        if (recovery != null) {
            recovered = recoverPending()
            if (recovered == "pending" || recovered == "complete") return@action
        }
        val value = state.value
        check(value.status?.canAfford(value.selection.action) == true) { "You don’t have enough Gradey Compute for this action. Refresh to check your balance." }
        check(value.purposeCompatible) { "Start a new conversation to use this school context." }
        val chat = checkNotNull(value.currentChat)
        val text = if (retry) checkNotNull(frozen).text else value.draft.trim()
        require(text.isNotBlank() && text.codePointCount(0, text.length) <= 2_000) { "Enter a question of 2,000 characters or fewer." }
        check(value.selection.action != AIAction.SUBJECT || value.selection.subjectID != null) { "Choose a subject first." }
        check(value.selection.action != AIAction.TEST || value.selection.eventID != null) { "Choose a test first." }
        val request = if (retry && frozen != null) {
            // Failed/cancelled attempts are immutable. Only an explicit retry gets a new ID.
            frozen.copy(id = if (recovered in setOf("failed", "cancelled")) UUID.randomUUID().toString() else frozen.id, catalogVersion = value.status?.compute?.catalogVersion)
        } else {
            val context = loadContext()
            ensureSchool()
            PendingQuestion(chat.id, UUID.randomUUID().toString(), text, context, value.selection, value.status?.compute?.catalogVersion,
                checkNotNull(value.status?.cost(value.selection.action)), locale = requestLocale)
        }
        requestStore.save(request.reference)
        mutableState.update { current ->
            val previous = current.messages.filterNot { it.status in setOf("failed", "cancelled") && it.role == "assistant" && it.clientMessageID == request.id }
            val messages = if (previous.any { it.clientMessageID == request.id && it.role == "user" }) previous else previous +
                AIMessage(request.id, "user", text, "complete", System.currentTimeMillis(), request.id)
            current.copy(pending = request, recovery = request.reference, draft = "", messages = messages, notice = null)
        }
        var assistantID: String? = null
        ai().reply(request).collect { event ->
            when (event) {
                is AIStreamEvent.Started -> {
                    assistantID = event.assistantID
                    mutableState.update { current -> current.copy(status = event.status ?: current.status,
                        messages = current.messages.filterNot { it.id == event.assistantID } +
                            AIMessage(event.assistantID, "assistant", "", "streaming", System.currentTimeMillis(), request.id)) }
                }
                is AIStreamEvent.Delta -> {
                    val id = checkNotNull(assistantID) { "Gradey AI returned text before starting the reply. Please reload." }
                    mutableState.update { current -> current.copy(messages = current.messages.map {
                        if (it.id == id) it.copy(text = it.text + event.text) else it
                    }) }
                }
                is AIStreamEvent.Completed -> {
                    requestStore.clear(request.reference.schoolScope)
                    mutableState.update { current ->
                    val finalMessage = event.message?.copy(clientMessageID = event.message.clientMessageID ?: request.id)
                    val messages = if (finalMessage != null) current.messages.filterNot { it.id == assistantID || it.id == finalMessage.id } + finalMessage
                        else current.messages.map { if (it.id == assistantID) it.copy(status = "complete") else it }
                    current.copy(messages = messages, status = event.status ?: current.status, pending = null, recovery = null)
                    }
                }
                is AIStreamEvent.Failed -> {
                    mutableState.update { it.copy(status = event.status ?: it.status, pending = if (event.retryable) it.pending else null) }
                    error(event.message)
                }
            }
        }
        // Persisted history is authoritative. A refresh failure must not hide a completed answer.
        try {
            applyDetail(ai().load(chat.id))
            val status = ai().status()
            val chats = ai().conversations(checkNotNull(schoolScope))
            mutableState.update { it.copy(status = status, chats = chats) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            mutableState.update { it.copy(notice = "Your reply is ready. Refresh later to sync conversation history and your remaining questions.") }
        }
    }

    fun stop() {
        if (!state.value.sending) return
        operation?.cancel()
        mutableState.update { it.copy(messages = it.messages.map { message ->
            if (message.status == "streaming") message.copy(status = "cancelled") else message
        }, notice = "Reply stopped. Reload to check the saved reply before trying again.") }
    }

    fun delete(id: String) = action {
        ensureSchool()
        ai().delete(id)
        if (state.value.recovery?.chatID == id) { requestStore.clear(checkNotNull(schoolScope)); mutableState.update { it.copy(recovery = null) } }
        if (state.value.currentChat?.id == id) clearChat()
        mutableState.update { it.copy(chats = it.chats.filterNot { chat -> chat.id == id }) }
    }

    fun deleteAll() = action {
        ensureSchool()
        ai().deleteAll(checkNotNull(schoolScope))
        requestStore.clear(checkNotNull(schoolScope))
        mutableState.update { it.copy(recovery = null) }
        clearChat()
        mutableState.update { it.copy(chats = emptyList()) }
    }

    fun revoke() = action {
        ai().revokeConsent()
        schoolScope?.let(requestStore::clear)
        mutableState.value = AIState(status = state.value.status?.copy(consentRequired = true), busy = true)
    }

    private suspend fun ensureSchool() {
        val session = school.currentStoredSession() ?: error("Connect your school account to use Gradey AI.")
        val current = AIContext.schoolScope(session, scopeSalt)
        if (schoolScope != null && schoolScope != current) {
            schoolScope = current
            mutableState.value = AIState(busy = state.value.busy)
            error("Your school account changed. Refresh Gradey AI to continue with this school.")
        }
        schoolScope = current
    }

    private suspend fun loadContext(): Map<String, Any?> {
        val value = state.value
        val needsMarks = value.selection.action in setOf(AIAction.PRIORITIES, AIAction.SUBJECT, AIAction.TEST)
        val needsTimetable = value.selection.action in setOf(AIAction.TOMORROW, AIAction.WEEK)
        var stale = false
        val dashboard = if (needsMarks) try { school.loadDashboard(forceRefresh = true) } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) { stale = true; optional { school.loadCachedDashboard() } } else null
        val weeks = if (needsTimetable) {
            val today = LocalDate.now(ZoneId.of("Europe/Prague"))
            val dates = if (value.selection.action == AIAction.WEEK) listOf(value.selection.weekStart?.let { LocalDate.parse(it.take(10)) } ?: today)
                else listOf(today.plusDays(1))
            dates.mapNotNull { date ->
                try { school.loadTimetable(date.toString()) } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) { stale = true; optional { school.loadCachedTimetable(date.toString()) } }
            }
        } else emptyList()
        val session = school.currentStoredSession() ?: error("Connect your school account again.")
        val trends = if (needsMarks && history != null && session.linkedAccountID != null && !session.isEduPageParentSession()) {
            val started = System.currentTimeMillis()
            try {
                history.gradeHistory(session.linkedAccountID).also {
                    insightStore?.sourceSucceeded(session.cacheScope, "history", System.currentTimeMillis())
                }
            } catch (error: CancellationException) { throw error
            } catch (_: Exception) { insightStore?.sourceFailed(session.cacheScope, "history", started); null }
        } else null
        ensureSchool()
        val events = plannerItems?.let { AIPlannerContext.events(it, session.cacheScope) }
        val source = AIContext.build(checkNotNull(schoolScope), dashboard, weeks, needsMarks, needsTimetable, stale = stale, history = trends).toMutableMap()
        events?.let { source["events"] = it.map { event -> event.payload(value.selection.includeNotes && event.id == value.selection.eventID) } }
        insightStore?.let { store ->
            val now = System.currentTimeMillis()
            val snapshot = store.snapshot(session.cacheScope, now)
            val selectedDate = if (value.selection.action == AIAction.WEEK) value.selection.weekStart?.let(LocalDate::parse)
                ?: LocalDate.now(ZoneId.of("Europe/Prague")) else LocalDate.now(ZoneId.of("Europe/Prague")).plusDays(1)
            val week = selectedDate.minusDays((selectedDate.dayOfWeek.value - 1).toLong()).toString()
            source["insights"] = AIInsightsContext.insights(snapshot, now)
            val freshness = AIInsightsContext.freshness(snapshot, week, events != null, now)
            source["sourceFreshness"] = freshness
            source["unavailableSections"] = ((source["unavailableSections"] as? List<*>).orEmpty() +
                freshness.filter { it["fetchedAt"] == null }.mapNotNull { it["section"] as? String }).distinct()
        }
        return AIContext.select(source, value.selection).also { snapshot ->
            check(AIContext.encodedSize(snapshot) <= AIContext.MAX_CONTEXT_BYTES) { "The selected school context is too large. Try a different action." }
            mutableState.update { it.copy(context = snapshot,
                availableSubjects = dashboard?.marksResponse?.subjects?.map { subject -> subject.id to subject.displayName } ?: it.availableSubjects,
                availableEvents = events.orEmpty().filter { event -> event.isAssessment && event.expiresAt > System.currentTimeMillis() }.take(30)) }
        }
    }

    fun recover() = action { ensureSchool(); recoverPending() }

    private suspend fun recoverPending(): String {
        val reference = state.value.recovery ?: return "missing"
        val recovery = ai().recover(reference)
        mutableState.update { it.copy(status = recovery.status ?: it.status) }
        if (recovery.state == "pending") {
            mutableState.update { it.copy(notice = "Your previous answer is still being saved. Check again shortly; no new Compute will be spent.") }
            return "pending"
        }
        check(recovery.state in setOf("complete", "failed", "cancelled", "missing")) { "The previous request status could not be confirmed. Try recovery again." }
        if (recovery.state == "complete") {
            checkNotNull(recovery.message) { "The saved answer is not available yet. Try recovery again." }
            if (state.value.currentChat?.id == reference.chatID) applyDetail(ai().load(reference.chatID))
            mutableState.update { it.copy(pending = null, notice = "Your saved answer was recovered without using more Compute.") }
        }
        requestStore.clear(reference.schoolScope)
        mutableState.update { it.copy(recovery = null) }
        return recovery.state
    }

    private suspend fun <T> optional(block: suspend () -> T): T? = try { block() } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) { null }

    private fun applyDetail(detail: AIDetail) = mutableState.update { current ->
        // Keep the request ID until a persisted assistant answer confirms it completed.
        val pending = current.pending?.takeUnless { request -> detail.messages.any {
            it.role == "assistant" && it.status == "complete" && it.clientMessageID == request.id
        } }
        current.copy(currentChat = detail.chat, messages = detail.messages, status = detail.status ?: current.status, pending = pending)
    }

    private fun clearChat() = mutableState.update { it.copy(currentChat = null, messages = emptyList(), draft = "", pending = null, notice = null) }
    private fun ai() = checkNotNull(client) { "Gradey AI is not available in this build." }

    private fun action(sending: Boolean = false, block: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, sending = sending, error = null) }
        operation = viewModelScope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                mutableState.update { value -> value.copy(error = error.message?.takeIf { it.isNotBlank() && it.length < 500 }
                    ?: "Gradey AI could not complete your request. Please try again.",
                    messages = value.messages.map { if (it.status == "streaming") it.copy(status = "failed") else it }) }
            } finally {
                mutableState.update { it.copy(busy = false, sending = false) }
            }
        }
    }
}
