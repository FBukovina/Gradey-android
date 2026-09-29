package com.bukovinafilip.gradey.wear

import android.app.Application
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import androidx.wear.input.RemoteInputIntentHelper
import com.bukovinafilip.gradey.domain.*
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.network.WatchSchoolRefresh
import com.google.android.gms.wearable.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class WearSchoolActivity : ComponentActivity() {
    private lateinit var model: WearSchoolViewModel
    private val input = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) result.data?.let { data -> RemoteInput.getResultsFromIntent(data)?.getCharSequence("code")?.toString()?.let(model::completeCode) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        model = ViewModelProvider(this)[WearSchoolViewModel::class.java]
        setContent {
            MaterialTheme(colors = Colors(primary = Color(0xFF6BDCC3), onPrimary = Color(0xFF002B23), surface = Color(0xFF172522))) {
                WearSchoolContent(model, ::enterCode, {
                    runCatching { startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS)) }
                        .onFailure { runCatching { startActivity(Intent(Settings.ACTION_SETTINGS)) } }
                }, ::finish)
            }
        }
    }
    override fun onResume() { super.onResume(); model.refreshState() }
    private fun enterCode() {
        try {
            val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
            RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(RemoteInput.Builder("code").setLabel(getString(R.string.wear_school_code)).build()))
            RemoteInputIntentHelper.putTitleExtra(intent, "EduPage")
            input.launch(intent)
        } catch (_: Exception) { model.inputUnavailable() }
    }
}

class WearSchoolViewModel(application: Application) : AndroidViewModel(application), MessageClient.OnMessageReceivedListener, DataClient.OnDataChangedListener {
    var enabled by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var expiry by mutableStateOf<Long?>(null); private set
    var verification by mutableStateOf(false); private set
    var error by mutableStateOf<WearSchoolFailure?>(null); private set
    var refreshed by mutableStateOf(false); private set
    private val context get() = getApplication<Application>()
    private val json = Json { ignoreUnknownKeys = true }
    private var requestID: String? = null
    private var pendingRequest: WearSchoolProvisionRequest? = null
    private var phoneNodeID: String? = null
    private var operation: Job? = null
    private var lastScope: WearSchoolScope? = null
    private var lastGrantID: String? = null
    private var generation = 0
    private val registrationMutex = Mutex()
    private var registered = false

    init {
        viewModelScope.launch {
            try { ensureListeners(); refreshState() }
            catch (e: Exception) { if (e is CancellationException && e !is TimeoutCancellationException) throw e }
        }
    }
    private suspend fun ensureListeners() = registrationMutex.withLock {
        if (!registered) {
            withTimeout(10_000) {
                Wearable.getMessageClient(context).addListener(this@WearSchoolViewModel).await()
                Wearable.getDataClient(context).addListener(this@WearSchoolViewModel).await()
            }
            registered = true
        }
    }
    fun refreshState() {
        viewModelScope.launch {
            try {
                val access = WearSnapshotStore.load(context)?.schoolAccess
                val node = withTimeout(10_000) { Wearable.getNodeClient(context).localNode.await() }
                val grant = WearSchoolProtocol.grant(access, node.id, System.currentTimeMillis())
                if (lastScope != null && (access?.scope != lastScope || grant?.grantID != lastGrantID)) {
                    generation++; operation?.cancel(); busy = false; verification = false; requestID = null
                }
                lastScope = access?.scope; lastGrantID = grant?.grantID
                enabled = grant != null
                expiry = withContext(Dispatchers.IO) { WearSchoolGrantStore.load(context)?.expiresAtEpochMillis }
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                expiry = null
                if (e is WearSchoolException) error = e.reason
            }
        }
    }
    fun connect() = perform {
        ensureListeners()
        val source = WearSnapshotStore.sourceNode(context) ?: throw WearSchoolException(WearSchoolFailure.PHONE_UNREACHABLE)
        val nearby = withTimeout(10_000) { Wearable.getNodeClient(context).connectedNodes.await() }.singleOrNull { it.id == source && it.isNearby }
            ?: throw WearSchoolException(WearSchoolFailure.PHONE_UNREACHABLE)
        val node = withTimeout(10_000) { Wearable.getNodeClient(context).localNode.await() }
        val request = withContext(Dispatchers.IO) { WearSchoolGrantStore.createRequest(context, node.id, nearby.id) }
        requestID = request.requestID
        pendingRequest = request
        phoneNodeID = nearby.id
        val bytes = json.encodeToString(WearSchoolProvisionRequest.serializer(), request).toByteArray(Charsets.UTF_8)
        withTimeout(10_000) { Wearable.getMessageClient(context).sendMessage(nearby.id, WearSchoolProtocol.REQUEST_PATH, bytes).await() }
        delay(WearSchoolProtocol.REQUEST_LIFETIME_MILLIS)
        if (requestID == request.requestID) {
            requestID = null
            withContext(Dispatchers.IO) { WearSchoolGrantStore.clearPending(context) }
            throw WearSchoolException(WearSchoolFailure.PHONE_UNREACHABLE)
        }
    }
    fun refresh() = perform { handle(WearSchoolConnections.refresh(context)) }
    fun completeCode(code: String) = perform { handle(WearSchoolConnections.refresh(context, code = code)) }
    fun checkApproval() = perform { handle(WearSchoolConnections.refresh(context, checkApproval = true)) }
    fun resend() = perform { WearSchoolConnections.resend(context) }
    fun inputUnavailable() { error = WearSchoolFailure.INVALID_CODE }
    fun forget() {
        generation++; operation?.cancel(); busy = false; verification = false; requestID = null
        WearSchoolGrantStore.clear(context)
        expiry = null; refreshed = false; error = null
    }
    private suspend fun handle(result: WatchSchoolRefresh) {
        verification = result is WatchSchoolRefresh.VerificationRequired
        refreshed = result is WatchSchoolRefresh.Loaded
        expiry = withContext(Dispatchers.IO) { WearSchoolGrantStore.load(context)?.expiresAtEpochMillis }
    }
    private fun perform(block: suspend () -> Unit) {
        if (busy) return
        val currentGeneration = ++generation
        busy = true; error = null; refreshed = false
        operation = viewModelScope.launch {
            try { block() }
            catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                error = (e as? WearSchoolException)?.reason ?: WearSchoolFailure.NETWORK
            } finally { if (generation == currentGeneration) busy = false }
        }
    }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path == WearSchoolProtocol.REJECTION_PATH && event.data.size <= WearSchoolProtocol.MAX_MESSAGE_BYTES && event.sourceNodeId == phoneNodeID) {
            val rejection = runCatching { json.decodeFromString(WearSchoolProvisionRejection.serializer(), event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
            val request = pendingRequest ?: return
            if (rejection.schemaVersion != 1 || rejection.requestID != requestID || rejection.scope != request.scope || rejection.grantID != request.grantID) return
            viewModelScope.launch {
                requestID = null; operation?.cancel(); operation?.join(); busy = false
                withContext(Dispatchers.IO) { WearSchoolGrantStore.clearPending(context) }
                error = when (rejection.reason) {
                    WearSchoolProvisionRejectionReason.ACCESS_DISABLED -> WearSchoolFailure.DISABLED
                    WearSchoolProvisionRejectionReason.CREDENTIALS_UNAVAILABLE -> WearSchoolFailure.RECONNECT_REQUIRED
                    WearSchoolProvisionRejectionReason.SCHOOL_IDENTITY_UNAVAILABLE -> WearSchoolFailure.IDENTITY_MISMATCH
                    WearSchoolProvisionRejectionReason.PHONE_UNAVAILABLE -> WearSchoolFailure.PHONE_UNREACHABLE
                    WearSchoolProvisionRejectionReason.INVALID_REQUEST -> WearSchoolFailure.INVALID_RESPONSE
                }
            }
            return
        }
        if (event.path != WearSchoolProtocol.RESPONSE_PATH || event.data.size > WearSchoolProtocol.MAX_MESSAGE_BYTES || event.sourceNodeId != phoneNodeID) return
        val response = runCatching { json.decodeFromString(WearSchoolProvisionResponse.serializer(), event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
        if (response.requestID != requestID) return
        viewModelScope.launch {
            try {
                val accepted = withContext(Dispatchers.IO) { WearSchoolGrantStore.accept(context, event.sourceNodeId, response) }
                if (accepted && response.requestID == requestID) {
                    requestID = null; operation?.cancel(); operation?.join(); busy = false
                    refreshState()
                    refresh()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                requestID = null; operation?.cancel(); busy = false
                error = (e as? WearSchoolException)?.reason ?: WearSchoolFailure.INVALID_RESPONSE
            }
        }
    }
    override fun onDataChanged(events: DataEventBuffer) {
        // Persist revocation before returning; there is no unbound background service job.
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) WearSnapshotStore.accept(context, it.dataItem) }
        refreshState()
    }
    override fun onCleared() {
        operation?.cancel()
        WearSchoolGrantStore.clearPending(context)
        Wearable.getMessageClient(context).removeListener(this)
        Wearable.getDataClient(context).removeListener(this)
        super.onCleared()
    }
}

@Composable
private fun WearSchoolContent(model: WearSchoolViewModel, onCode: () -> Unit, onSettings: () -> Unit, onBack: () -> Unit) {
    val list = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = list) }) {
        ScalingLazyColumn(state = list, modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.wear_school_title), color = MaterialTheme.colors.primary, style = MaterialTheme.typography.title3, textAlign = TextAlign.Center) }
            item { Text(stringResource(R.string.wear_school_detail), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
            if (!model.enabled) item { Text(stringResource(R.string.wear_school_enable_phone), style = MaterialTheme.typography.body2, textAlign = TextAlign.Center) }
            model.expiry?.let { expiry -> item {
                val date = Instant.ofEpochMilli(expiry).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("d MMM HH:mm", androidx.compose.ui.platform.LocalConfiguration.current.locales[0]))
                Text(stringResource(R.string.wear_school_expiry, date), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
            } }
            if (model.refreshed) item { Text(stringResource(R.string.wear_synced), color = MaterialTheme.colors.primary, textAlign = TextAlign.Center) }
            model.error?.let { item { Text(stringResource(wearSchoolErrorResource(it)), color = MaterialTheme.colors.error, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center) } }
            if (model.busy) item { Text(stringResource(R.string.wear_syncing), style = MaterialTheme.typography.caption1) }
            if (model.verification) {
                item { Text(stringResource(R.string.wear_school_verify), style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center) }
                item { Chip(onClick = onCode, enabled = !model.busy, label = { Text(stringResource(R.string.wear_school_code)) }, modifier = Modifier.fillMaxWidth()) }
                item { Chip(onClick = model::checkApproval, enabled = !model.busy, label = { Text(stringResource(R.string.wear_school_check)) }, modifier = Modifier.fillMaxWidth()) }
                item { Chip(onClick = model::resend, enabled = !model.busy, label = { Text(stringResource(R.string.wear_school_resend)) }, modifier = Modifier.fillMaxWidth()) }
            }
            if (model.expiry != null) {
                item { Chip(onClick = model::refresh, enabled = !model.busy, label = { Text(stringResource(R.string.wear_school_refresh)) }, modifier = Modifier.fillMaxWidth()) }
                item { Chip(onClick = model::forget, label = { Text(stringResource(R.string.wear_school_remove)) }, modifier = Modifier.fillMaxWidth()) }
            }
            item { Chip(onClick = model::connect, enabled = model.enabled && !model.busy, label = { Text(stringResource(R.string.wear_school_connect)) }, modifier = Modifier.fillMaxWidth()) }
            if (model.error == WearSchoolFailure.LOCK_REQUIRED || model.error == WearSchoolFailure.UNLOCK_REQUIRED)
                item { Chip(onClick = onSettings, label = { Text(stringResource(R.string.wear_school_settings)) }, modifier = Modifier.fillMaxWidth()) }
            item { Chip(onClick = onBack, label = { Text(stringResource(R.string.wear_ai_back)) }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

internal fun wearSchoolErrorResource(error: WearSchoolFailure) = when (error) {
    WearSchoolFailure.DISABLED -> R.string.wear_school_enable_phone
    WearSchoolFailure.LEASE_EXPIRED -> R.string.wear_school_expired
    WearSchoolFailure.LOCK_REQUIRED -> R.string.wear_school_lock
    WearSchoolFailure.UNLOCK_REQUIRED -> R.string.wear_school_unlock
    WearSchoolFailure.PHONE_UNREACHABLE -> R.string.wear_school_nearby
    WearSchoolFailure.IDENTITY_MISMATCH -> R.string.wear_school_identity
    WearSchoolFailure.RECONNECT_REQUIRED -> R.string.wear_school_reconnect
    WearSchoolFailure.TWO_FACTOR -> R.string.wear_school_verify
    WearSchoolFailure.CAPTCHA -> R.string.wear_school_captcha
    WearSchoolFailure.INVALID_CODE -> R.string.wear_school_bad_code
    WearSchoolFailure.NOT_APPROVED -> R.string.wear_school_not_approved
    WearSchoolFailure.INVALID_RESPONSE, WearSchoolFailure.NETWORK -> R.string.wear_school_failed
}
