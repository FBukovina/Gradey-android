package com.bukovinafilip.gradey.wear

import android.app.Application
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import androidx.wear.input.RemoteInputIntentHelper
import com.bukovinafilip.gradey.domain.WearAIProtocol
import com.bukovinafilip.gradey.domain.WearAIStreamAccumulator
import com.bukovinafilip.gradey.model.*
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import java.util.UUID

class WearAIActivity : ComponentActivity() {
    private lateinit var model: WearAIViewModel
    private val input = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.let { data ->
            RemoteInput.getResultsFromIntent(data)?.getCharSequence("question")?.toString()?.let(model::draft)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[WearAIViewModel::class.java]
        setContent {
            MaterialTheme(colors = Colors(primary = Color(0xFF6BDCC3), onPrimary = Color(0xFF002B23), surface = Color(0xFF172522))) {
                WearAIContent(model, ::editQuestion, ::finish)
            }
        }
    }
    override fun onResume() { super.onResume(); model.refreshIdentity() }
    private fun editQuestion() {
        try {
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("question").setLabel(getString(R.string.wear_ai_question)).build()))
            RemoteInputIntentHelper.putTitleExtra(intent, "Gradey AI")
            RemoteInputIntentHelper.putConfirmLabelExtra(intent, getString(R.string.wear_ai_review))
            input.launch(intent)
        } catch (_: Exception) { model.inputUnavailable() }
    }
}

class WearAIViewModel(application: Application) : AndroidViewModel(application), MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    var draft by mutableStateOf("")
        private set
    var question by mutableStateOf("")
        private set
    var answer by mutableStateOf("")
        private set
    var busy by mutableStateOf(false)
        private set
    var identity by mutableStateOf<WearAIIdentity?>(null)
        private set
    var error by mutableStateOf<WearAIError?>(null)
        private set
    var remaining by mutableStateOf<Int?>(null)
        private set
    private val json = Json { ignoreUnknownKeys = true }
    private var conversationID: String? = null
    private var request: WearAIRequest? = null
    private var recoverableRequest by mutableStateOf<WearAIRequest?>(null)
    val canRetry: Boolean get() = recoverableRequest != null
    private var nodeID: String? = null
    private var stream: WearAIStreamAccumulator? = null
    private var timeout: Job? = null
    private val listenerMutex = Mutex()
    private var listenersRegistered = false
    private val context get() = getApplication<Application>()

    init {
        refreshIdentity()
        viewModelScope.launch {
            try {
                ensureListeners()
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                error = WearAIError.PHONE_UNREACHABLE
            }
        }
    }
    private suspend fun ensureListeners() = listenerMutex.withLock {
        if (!listenersRegistered) {
            withTimeout(10_000) {
                Wearable.getMessageClient(context).addListener(this@WearAIViewModel).await()
                Wearable.getDataClient(context).addListener(this@WearAIViewModel).await()
            }
            listenersRegistered = true
        }
    }
    fun draft(text: String) {
        if (text.codePointCount(0, text.length) > WearAIProtocol.MAX_QUESTION_CODE_POINTS) { error = WearAIError.INVALID_REQUEST; return }
        draft = text
        error = null
    }
    fun inputUnavailable() { error = WearAIError.NOT_CONFIGURED }
    fun refreshIdentity() {
        val next = WearSnapshotStore.load(context)?.takeIf { it.isSignedIn }?.aiIdentity
        if (identity != next) {
            cancel()
            identity = next
            question = ""
            answer = ""
            draft = ""
            remaining = null
            conversationID = null
            error = null
        }
    }
    fun newConversation() {
        cancel()
        conversationID = null
        question = ""
        answer = ""
        error = null
    }
    fun send() {
        if (busy) return
        refreshIdentity()
        val expected = identity ?: run { error = WearAIError.NO_SCHOOL_ACCOUNT; return }
        val pending = WearAIRequest(requestID = UUID.randomUUID().toString(), clientMessageID = UUID.randomUUID().toString(),
            identity = expected, createdAtEpochMillis = System.currentTimeMillis(), text = draft.trim(), conversationID = conversationID,
            locale = context.resources.configuration.locales[0].toLanguageTag())
        start(pending)
    }
    fun retry() {
        if (busy) return
        refreshIdentity()
        val previous = recoverableRequest ?: return
        if (previous.identity != identity) { recoverableRequest = null; error = WearAIError.STALE_IDENTITY; return }
        // Reuse the billing/recovery key and original question; only transport correlation expires.
        start(previous.copy(requestID = UUID.randomUUID().toString(), createdAtEpochMillis = System.currentTimeMillis()))
    }
    private fun start(pending: WearAIRequest) {
        val expected = identity ?: run { error = WearAIError.NO_SCHOOL_ACCOUNT; return }
        if (!WearAIProtocol.valid(pending, expected, System.currentTimeMillis())) { error = WearAIError.INVALID_REQUEST; return }
        val sourceNode = WearSnapshotStore.sourceNode(context) ?: run { error = WearAIError.PHONE_UNREACHABLE; return }
        request = pending
        recoverableRequest = null
        nodeID = sourceNode
        stream = WearAIStreamAccumulator(pending.requestID, expected, sourceNode)
        question = pending.text
        answer = ""
        draft = ""
        error = null
        busy = true
        timeout = viewModelScope.launch {
            try {
                val nodes = withTimeout(10_000) {
                    ensureListeners()
                    Wearable.getNodeClient(context).connectedNodes.await()
                }
                if (nodes.none { it.id == sourceNode }) { end(WearAIError.PHONE_UNREACHABLE); return@launch }
                if (request != pending || WearSnapshotStore.load(context)?.aiIdentity != expected) { refreshIdentity(); return@launch }
                val bytes = json.encodeToString(WearAIRequest.serializer(), pending).toByteArray(Charsets.UTF_8)
                if (bytes.size > WearAIProtocol.MAX_MESSAGE_BYTES) { end(WearAIError.INVALID_REQUEST); return@launch }
                withTimeout(10_000) { Wearable.getMessageClient(context).sendMessage(sourceNode, WearAIProtocol.REQUEST_PATH, bytes).await() }
                delay(WearAIProtocol.RESPONSE_TIMEOUT_MILLIS)
                if (request == pending) { sendCancel(); end(WearAIError.TIMEOUT) }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                if (request == pending) end(WearAIError.PHONE_UNREACHABLE)
            }
        }
    }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearAIProtocol.RESPONSE_PATH || event.data.size > WearAIProtocol.MAX_MESSAGE_BYTES) return
        val response = runCatching { json.decodeFromString(WearAIResponse.serializer(), event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        viewModelScope.launch {
            refreshIdentity()
            val state = stream?.accept(event.sourceNodeId, response) ?: return@launch
            answer = state.answer
            conversationID = state.conversationID ?: conversationID
            remaining = state.remaining
            if (state.complete) {
                if (state.error == WearAIError.INVALID_REQUEST) sendCancel()
                end(state.error)
            }
        }
    }
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) WearSnapshotStore.accept(context, it.dataItem) }
        viewModelScope.launch { refreshIdentity() }
    }
    fun cancel() {
        if (busy) sendCancel()
        end(null)
    }
    private fun sendCancel() {
        val pending = request ?: return
        val node = nodeID ?: return
        val payload = json.encodeToString(WearAICancel.serializer(), WearAICancel(requestID = pending.requestID, identity = pending.identity)).toByteArray(Charsets.UTF_8)
        runCatching { Wearable.getMessageClient(context).sendMessage(node, WearAIProtocol.CANCEL_PATH, payload) }
    }
    private fun end(reason: WearAIError?) {
        busy = false
        recoverableRequest = request?.takeIf { reason in setOf(WearAIError.TIMEOUT, WearAIError.PHONE_UNREACHABLE, WearAIError.REQUEST_FAILED, WearAIError.BUSY) }
        request = null
        stream = null
        timeout?.cancel()
        timeout = null
        error = reason
    }
    override fun onCleared() {
        cancel()
        Wearable.getMessageClient(context).removeListener(this)
        Wearable.getDataClient(context).removeListener(this)
        super.onCleared()
    }
}

@Composable
private fun WearAIContent(model: WearAIViewModel, onEdit: () -> Unit, onBack: () -> Unit) {
    val list = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
        ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Gradey AI", color = MaterialTheme.colors.primary, style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold) }
            item { Text(stringResource(if (model.identity == null) R.string.wear_ai_setup else R.string.wear_ai_privacy), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
            if (model.question.isNotBlank()) item { Text(model.question, style = MaterialTheme.typography.body2, modifier = Modifier.fillMaxWidth().background(MaterialTheme.colors.surface, RoundedCornerShape(16.dp)).padding(12.dp)) }
            if (model.answer.isNotBlank()) {
                // Paragraphs remain readable and scroll naturally on small round displays.
                model.answer.codePoints().toArray().toList().chunked(1200).forEach { codePoints ->
                    val paragraph = String(codePoints.toIntArray(), 0, codePoints.size)
                    item { Text(paragraph, style = MaterialTheme.typography.body2, modifier = Modifier.fillMaxWidth()) }
                }
                item { Text(stringResource(R.string.wear_ai_disclaimer), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center, color = MaterialTheme.colors.onSurfaceVariant) }
            }
            if (model.busy) item { Text(stringResource(R.string.wear_ai_thinking), style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.primary) }
            model.error?.let { reason -> item { Text(stringResource(errorResource(reason)), style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center, color = MaterialTheme.colors.error) } }
            model.remaining?.let { value -> item { Text(stringResource(R.string.wear_ai_remaining, value), style = MaterialTheme.typography.caption2) } }
            if (model.draft.isNotBlank()) item { Text(model.draft, style = MaterialTheme.typography.body2, textAlign = TextAlign.Center) }
            if (model.busy) item { Chip(onClick = model::cancel, label = { Text(stringResource(R.string.wear_ai_stop)) }, modifier = Modifier.fillMaxWidth()) }
            else {
                if (model.canRetry && model.draft.isBlank()) {
                    item { Text(stringResource(R.string.wear_ai_retry_detail), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
                    item { Chip(onClick = model::retry, enabled = model.identity != null, label = { Text(stringResource(R.string.wear_ai_retry)) }, modifier = Modifier.fillMaxWidth()) }
                }
                item { Chip(onClick = onEdit, enabled = model.identity != null, label = { Text(stringResource(if (model.draft.isBlank()) R.string.wear_ai_question else R.string.wear_ai_edit)) }, modifier = Modifier.fillMaxWidth()) }
                if (model.draft.isNotBlank()) item { Chip(onClick = model::send, enabled = model.identity != null, label = { Text(stringResource(R.string.wear_ai_send)) }, modifier = Modifier.fillMaxWidth()) }
                if (model.question.isNotBlank()) item { Chip(onClick = model::newConversation, label = { Text(stringResource(R.string.wear_ai_new_chat)) }, modifier = Modifier.fillMaxWidth()) }
            }
            item { Chip(onClick = onBack, label = { Text(stringResource(R.string.wear_ai_back)) }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

private fun errorResource(error: WearAIError) = when (error) {
    WearAIError.CONSENT_REQUIRED -> R.string.wear_ai_consent
    WearAIError.SUPPORTER_REQUIRED -> R.string.wear_ai_supporter
    WearAIError.NOT_CONFIGURED -> R.string.wear_ai_unavailable
    WearAIError.NO_SCHOOL_ACCOUNT, WearAIError.STALE_IDENTITY -> R.string.wear_ai_setup
    WearAIError.QUOTA_EXCEEDED -> R.string.wear_ai_quota
    WearAIError.CANCELLED -> R.string.wear_ai_cancelled
    WearAIError.PHONE_UNREACHABLE -> R.string.wear_phone_offline
    WearAIError.INVALID_REQUEST -> R.string.wear_ai_invalid
    WearAIError.BUSY -> R.string.wear_ai_busy
    WearAIError.TIMEOUT -> R.string.wear_ai_timeout
    WearAIError.REQUEST_FAILED -> R.string.wear_ai_failed
}
