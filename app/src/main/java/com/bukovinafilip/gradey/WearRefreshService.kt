package com.bukovinafilip.gradey

import com.bukovinafilip.gradey.data.AndroidGradeyGraph
import com.bukovinafilip.gradey.domain.WearAIProtocol
import com.bukovinafilip.gradey.feature.ai.WearAIRelay
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.network.GradeyJson
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import java.time.LocalDate

class WearRefreshService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestLock = Any()
    private data class Running(val request: WearAIRequest, val job: Job)
    private val running = mutableMapOf<String, Running>()
    private var relayGraph: AndroidGradeyGraph? = null
    private var relay: WearAIRelay? = null

    // The SDK releases its own binding when a callback returns. Keep a bounded binding
    // until asynchronous work finishes so onDestroy cannot cancel a valid watch request.
    private fun retainService(): java.io.Closeable? {
        val context = applicationContext
        val connection = object : android.content.ServiceConnection {
            override fun onServiceConnected(name: android.content.ComponentName?, binder: android.os.IBinder?) = Unit
            override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
        }
        val intent = android.content.Intent(context, WearRefreshService::class.java)
            .setAction("com.google.android.gms.wearable.MESSAGE_RECEIVED")
        val bound = runCatching { context.bindService(intent, connection, android.content.Context.BIND_AUTO_CREATE) }.getOrDefault(false)
        if (!bound) return null
        val closed = java.util.concurrent.atomic.AtomicBoolean()
        return java.io.Closeable { if (closed.compareAndSet(false, true)) runCatching { context.unbindService(connection) } }
    }

    private fun launchBound(timeoutMillis: Long, block: suspend CoroutineScope.() -> Unit) {
        val binding = retainService() ?: return
        scope.launch {
            try { withTimeout(timeoutMillis, block) }
            catch (cancelled: CancellationException) { if (cancelled !is TimeoutCancellationException) throw cancelled }
            catch (_: Exception) { /* The watch exposes a bounded retry state. */ }
            finally { binding.close() }
        }.invokeOnCompletion { binding.close() }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.data.size > WearAIProtocol.MAX_MESSAGE_BYTES) return
        when (event.path) {
            com.bukovinafilip.gradey.domain.WearSchoolProtocol.REQUEST_PATH -> launchBound(15_000) {
                WearSchoolAccessManager.get(this@WearRefreshService).handleProvision(event.sourceNodeId, event.data)
            }
            "/gradey/refresh" -> launchBound(25_000) {
                try {
                    val app = application as GradeyApplication
                    val graph = app.activeGraph
                    val token = CompanionSync.capture(this@WearRefreshService)
                    val session = graph.schoolRepository.bootstrapSession() ?: run { CompanionSync.clearIfStillSignedOut(this@WearRefreshService, token); return@launchBound }
                    val week = graph.schoolRepository.loadTimetable(LocalDate.now().toString())
                    if (app.activeGraph === graph) CompanionSync.publish(this@WearRefreshService, week, graph.schoolRepository.loadCachedDashboard()?.user, session.cacheScope, token)
                } catch (e: Exception) { if (e is CancellationException) throw e }
            }
            WearAIProtocol.REQUEST_PATH -> {
                val request = runCatching { GradeyJson.decodeFromString(WearAIRequest.serializer(), event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
                if (!WearAIProtocol.valid(request, request.identity, System.currentTimeMillis())) return
                begin(event.sourceNodeId, request)
            }
            WearAIProtocol.CANCEL_PATH -> {
                val cancel = runCatching { GradeyJson.decodeFromString(WearAICancel.serializer(), event.data.toString(Charsets.UTF_8)) }.getOrNull() ?: return
                if (cancel.schemaVersion != 1 || !WearAIProtocol.isUUID(cancel.requestID)) return
                synchronized(requestLock) {
                    running[event.sourceNodeId]?.takeIf { it.request.requestID == cancel.requestID && it.request.identity == cancel.identity }?.job?.cancel()
                }
            }
        }
    }

    private fun begin(node: String, request: WearAIRequest) {
        synchronized(requestLock) {
            val existing = running[node]
            if (existing?.request?.requestID == request.requestID) return
            if (existing != null || running.size >= 2) {
                launchBound(5_000) { sendFailure(node, request, WearAIError.BUSY) }
                return
            }
            val graph = (application as GradeyApplication).activeGraph
            val activeRelay = relayFor(graph)
            val binding = retainService() ?: return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                var nextSequence = 0
                try {
                    withTimeout(110_000) { activeRelay.reply(request).collect { nextSequence = it.sequence + 1; send(node, it) } }
                } catch (_: TimeoutCancellationException) {
                    sendFailure(node, request, WearAIError.TIMEOUT, nextSequence)
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) { withTimeoutOrNull(3_000) { sendFailure(node, request, WearAIError.CANCELLED, nextSequence) } }
                    throw cancelled
                } catch (_: Exception) {
                    sendFailure(node, request, WearAIError.REQUEST_FAILED, nextSequence)
                } finally {
                    synchronized(requestLock) { if (running[node]?.request?.requestID == request.requestID) running.remove(node) }
                    binding.close()
                }
            }
            job.invokeOnCompletion { binding.close() }
            running[node] = Running(request, job)
            job.start()
        }
    }

    private fun relayFor(graph: AndroidGradeyGraph): WearAIRelay {
        if (relayGraph === graph) return checkNotNull(relay)
        return WearAIRelay(this, graph.gradeyAuthRepository, graph.schoolRepository,
            isAllowed = {
                val owner = graph.gradeyAuthRepository.cachedSession()?.account?.id
                val preferences = getSharedPreferences("gradey-app-preferences", 0)
                !graph.isDemo && (application as GradeyApplication).activeGraph === graph && owner != null &&
                    AccountNotifications.acceptsOwner(this, owner) &&
                    preferences.getInt("privacyRevision", 0) >= PrivacyRevision && isValidAgeAttestation(preferences.getString("ageAttestation", null))
            },
            isRecurringSupporter = {
                val owner = graph.gradeyAuthRepository.cachedSession()?.account?.id
                owner != null && AccountNotifications.acceptsOwner(this, owner) && hasRecurringSupport(owner)
            },
        ).also { relayGraph = graph; relay = it }
    }

    private suspend fun send(node: String, response: WearAIResponse) {
        val bytes = GradeyJson.encodeToString(WearAIResponse.serializer(), response).toByteArray(Charsets.UTF_8)
        check(bytes.size <= WearAIProtocol.MAX_MESSAGE_BYTES)
        withTimeout(10_000) { Wearable.getMessageClient(this@WearRefreshService).sendMessage(node, WearAIProtocol.RESPONSE_PATH, bytes).await() }
    }
    private suspend fun sendFailure(node: String, request: WearAIRequest, error: WearAIError, sequence: Int = 0) {
        try { send(node, WearAIResponse(requestID = request.requestID, identity = request.identity, sequence = sequence, kind = WearAIResponseKind.FAILED, error = error)) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* A disconnected watch recovers the same request on an explicit retry. */ }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
