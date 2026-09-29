package com.bukovinafilip.gradey.feature.ai

import android.content.Context
import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.domain.WearAIProtocol
import com.bukovinafilip.gradey.model.*
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Non-secret binding only; never transfers school tokens or records to the watch. */
fun wearAIIdentity(context: Context, ownerID: String?, school: StoredSession?): WearAIIdentity? =
    if (ownerID.isNullOrBlank() || school == null) null else WearAIIdentity(ownerID, AIContext.schoolScope(school, schoolScopeSalt(context)))

/** The containing phone service owns cancellation and must retain one relay instance for all nodes. */
class WearAIRelay internal constructor(
    private val currentIdentity: suspend () -> WearAIIdentity?,
    private val isAllowed: suspend () -> Boolean,
    private val isRecurringSupporter: suspend () -> Boolean,
    private val clientFor: (String) -> GradeyAIClient?,
    private val requests: WearAIRequestJournal,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context, authRepository: GradeyAuthRepository, schoolRepository: SchoolRepository,
        isAllowed: suspend () -> Boolean, isRecurringSupporter: suspend () -> Boolean) : this(
        currentIdentity = { wearAIIdentity(context.applicationContext, authRepository.cachedSession()?.account?.id, schoolRepository.currentStoredSession()) },
        isAllowed = isAllowed, isRecurringSupporter = isRecurringSupporter,
        clientFor = { owner -> FirebaseGradeyAIClient(context.applicationContext, owner, authRepository) },
        requests = PreferencesWearAIRequestJournal(context.applicationContext),
    )
    private val mutex = Mutex()
    private class Rejected(val reason: WearAIError) : Exception()
    private class DeliveryFailed(cause: Throwable) : RuntimeException(cause)

    fun reply(request: WearAIRequest): Flow<WearAIResponse> = flow {
        var sequence = 0
        fun response(kind: WearAIResponseKind, chatID: String? = null, text: String? = null, error: WearAIError? = null, remaining: Int? = null) =
            WearAIResponse(requestID = request.requestID, identity = request.identity, kind = kind, sequence = sequence++,
                conversationID = chatID, text = text, error = error, remaining = remaining)
        suspend fun deliver(value: WearAIResponse) {
            try { emit(value) } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Throwable) { throw DeliveryFailed(error) }
        }
        if (!mutex.tryLock()) { deliver(response(WearAIResponseKind.FAILED, error = WearAIError.BUSY)); return@flow }
        var chatID: String? = null
        suspend fun guard() {
            if (!isAllowed() || currentIdentity() != request.identity) throw Rejected(WearAIError.STALE_IDENTITY)
        }
        try {
            val identity = currentIdentity() ?: throw Rejected(WearAIError.NO_SCHOOL_ACCOUNT)
            if (identity != request.identity) throw Rejected(WearAIError.STALE_IDENTITY)
            if (!WearAIProtocol.valid(request, identity, now())) throw Rejected(WearAIError.INVALID_REQUEST)
            guard()
            if (!isRecurringSupporter()) throw Rejected(WearAIError.SUPPORTER_REQUIRED)
            guard()
            val client = clientFor(identity.ownerID)?.takeIf { it.isConfigured } ?: throw Rejected(WearAIError.NOT_CONFIGURED)
            val status = client.status()
            guard()
            if (!status.enabled) throw Rejected(WearAIError.NOT_CONFIGURED)
            if (status.consentRequired) throw Rejected(WearAIError.CONSENT_REQUIRED)
            val selection = AISelection(AIAction.REPLY)
            val fingerprint = digest(request.text.trim() + "\u0000" + request.locale)
            val previous = requests.load(identity, request.clientMessageID)
            if (previous != null) {
                if (previous.questionHash != fingerprint || previous.reference.schoolScope != identity.schoolScope || previous.reference.selectionID != selection.id ||
                    (request.conversationID != null && request.conversationID != previous.reference.chatID)) throw Rejected(WearAIError.INVALID_REQUEST)
                chatID = previous.reference.chatID
                // Recovery is read-only and precedes balance checking; an old answer spends no Compute.
                val recovered = client.recover(previous.reference)
                guard()
                when (recovered.state) {
                    "complete" -> {
                        val text = recovered.message?.text ?: throw Rejected(WearAIError.REQUEST_FAILED)
                        deliver(response(WearAIResponseKind.STARTED, chatID, remaining = recovered.status?.remaining))
                        for (chunk in safeChunks(text)) { guard(); deliver(response(WearAIResponseKind.DELTA, chatID, chunk)) }
                        guard(); deliver(response(WearAIResponseKind.DONE, chatID, remaining = recovered.status?.remaining))
                    }
                    "pending" -> throw Rejected(WearAIError.BUSY)
                    else -> throw Rejected(WearAIError.REQUEST_FAILED)
                }
                return@flow
            }
            if (!status.canAfford(AIAction.REPLY)) throw Rejected(WearAIError.QUOTA_EXCEEDED)
            val conversation = request.conversationID?.let { client.load(it).chat }
                ?: client.create(identity.schoolScope, selection.id)
            guard()
            // Legacy general chats could contain broad school context: require an exact current purpose.
            if (conversation.schoolScope != identity.schoolScope || conversation.contextSelectionID != selection.id) throw Rejected(WearAIError.INVALID_REQUEST)
            chatID = conversation.id
            val context = AIContext.select(AIContext.build(identity.schoolScope, null, emptyList(), false, false, now()), selection)
            val pending = PendingQuestion(conversation.id, request.clientMessageID, request.text.trim(), context, selection,
                status.compute?.catalogVersion, checkNotNull(status.cost(AIAction.REPLY)), request.locale)
            // Fail closed before sending if recovery metadata cannot be durably saved.
            requests.save(identity, WearAIStoredRequest(pending.reference, fingerprint, now()))
            guard()
            deliver(response(WearAIResponseKind.STARTED, chatID, remaining = status.remaining))
            var terminal = false
            var started = false
            var emittedText = ""
            guard()
            client.reply(pending).collect { event ->
                guard()
                if (terminal) return@collect
                when (event) {
                    is AIStreamEvent.Started -> started = true
                    is AIStreamEvent.Delta -> {
                        if (!started) throw Rejected(WearAIError.REQUEST_FAILED)
                        emittedText += event.text
                        if (emittedText.codePointCount(0, emittedText.length) > WearAIProtocol.MAX_RESPONSE_CODE_POINTS) throw Rejected(WearAIError.REQUEST_FAILED)
                        for (chunk in safeChunks(event.text)) { guard(); deliver(response(WearAIResponseKind.DELTA, chatID, chunk)) }
                    }
                    is AIStreamEvent.Completed -> {
                        val final = event.message?.text
                        if (final != null && final.codePointCount(0, final.length) > WearAIProtocol.MAX_RESPONSE_CODE_POINTS) throw Rejected(WearAIError.REQUEST_FAILED)
                        if (final != null && final != emittedText) {
                            if (emittedText.isEmpty()) {
                                for (chunk in safeChunks(final)) { guard(); deliver(response(WearAIResponseKind.DELTA, chatID, chunk)) }
                            } else if (final.startsWith(emittedText)) {
                                for (chunk in safeChunks(final.removePrefix(emittedText))) { guard(); deliver(response(WearAIResponseKind.DELTA, chatID, chunk)) }
                            } else throw Rejected(WearAIError.REQUEST_FAILED) // Recover the authoritative answer on explicit retry.
                        }
                        terminal = true
                        guard(); deliver(response(WearAIResponseKind.DONE, chatID, remaining = event.status?.remaining))
                    }
                    is AIStreamEvent.Failed -> throw Rejected(WearAIError.REQUEST_FAILED)
                }
            }
            if (!terminal) throw Rejected(WearAIError.REQUEST_FAILED)
        } catch (cancelled: CancellationException) {
            throw cancelled // Keep the request reference; stopping transport does not prove server settlement.
        } catch (delivery: DeliveryFailed) {
            throw checkNotNull(delivery.cause)
        } catch (rejected: Rejected) {
            deliver(response(WearAIResponseKind.FAILED, chatID, error = rejected.reason))
        } catch (_: Exception) {
            deliver(response(WearAIResponseKind.FAILED, chatID, error = WearAIError.REQUEST_FAILED))
        } finally { mutex.unlock() }
    }

    private fun safeChunks(text: String): List<String> {
        if (text.codePointCount(0, text.length) > WearAIProtocol.MAX_RESPONSE_CODE_POINTS) throw Rejected(WearAIError.REQUEST_FAILED)
        val result = mutableListOf<String>()
        var offset = 0
        while (offset < text.length) {
            var end = (offset + 4_000).coerceAtMost(text.length)
            if (end < text.length && Character.isHighSurrogate(text[end - 1])) end--
            result += text.substring(offset, end)
            offset = end
        }
        return result
    }
}

internal data class WearAIStoredRequest(val reference: AIRequestReference, val questionHash: String, val createdAt: Long)
internal interface WearAIRequestJournal {
    suspend fun load(identity: WearAIIdentity, messageID: String): WearAIStoredRequest?
    suspend fun save(identity: WearAIIdentity, request: WearAIStoredRequest)
}

/** Identifiers and a hash only; no prompt, reply, school records or authentication tokens are stored. */
private class PreferencesWearAIRequestJournal(context: Context) : WearAIRequestJournal {
    private val preferences = context.getSharedPreferences("gradey-wear-ai-requests", Context.MODE_PRIVATE)
    private fun key(identity: WearAIIdentity, id: String) = digest("${identity.ownerID}\u0000${identity.schoolScope}\u0000$id")
    override suspend fun load(identity: WearAIIdentity, messageID: String): WearAIStoredRequest? = withContext(Dispatchers.IO) {
        preferences.getString(key(identity, messageID), null)?.let { raw ->
            val value = Json.parseToJsonElement(raw).jsonObject
            fun field(name: String) = checkNotNull(value[name]?.jsonPrimitive?.contentOrNull)
            WearAIStoredRequest(AIRequestReference(field("chat"), messageID, identity.schoolScope, field("selection")), field("hash"), field("createdAt").toLong())
        }
    }
    override suspend fun save(identity: WearAIIdentity, request: WearAIStoredRequest) = withContext(Dispatchers.IO) {
        val expired = preferences.all.filterValues { value -> runCatching {
            Json.parseToJsonElement(value as String).jsonObject["createdAt"]!!.jsonPrimitive.long < System.currentTimeMillis() - 7 * 86_400_000L
        }.getOrDefault(false) }.keys
        check(preferences.all.size - expired.size < 2048) { "Too many retained requests." }
        val encoded = JsonObject(mapOf("chat" to JsonPrimitive(request.reference.chatID), "selection" to JsonPrimitive(request.reference.selectionID),
            "hash" to JsonPrimitive(request.questionHash), "createdAt" to JsonPrimitive(request.createdAt))).toString()
        check(preferences.edit().apply { expired.forEach(::remove); putString(key(identity, request.reference.id), encoded) }.commit()) {
            "Recovery metadata could not be saved."
        }
    }
}

private fun digest(value: String): String = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
