package com.bukovinafilip.gradey.feature.ai

import com.bukovinafilip.gradey.model.*
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class WearAIRelayTest {
    private val timestamp = 1_000_000L
    private val identity = WearAIIdentity("owner", "school_opaque")
    private fun request() = WearAIRequest(requestID = UUID.randomUUID().toString(), clientMessageID = UUID.randomUUID().toString(),
        identity = identity, createdAtEpochMillis = timestamp, text = "Explain fractions", locale = "cs-US")
    private class Journal : WearAIRequestJournal {
        val values = mutableMapOf<Pair<WearAIIdentity, String>, WearAIStoredRequest>()
        var failWrite = false
        override suspend fun load(identity: WearAIIdentity, messageID: String) = values[identity to messageID]
        override suspend fun save(identity: WearAIIdentity, request: WearAIStoredRequest) {
            if (failWrite) throw IOException("disk unavailable")
            values[identity to request.reference.id] = request
        }
    }
    private class Client : GradeyAIClient {
        override val isConfigured = true
        var currentStatus = AIStatus(true, false, "v2", 20, 20, null, true,
            AICompute(1, "catalog", 0, listOf(AIComputeAction("reply", 2, true))))
        var currentChat = AIChat("opaqueFirestoreDoc", "General chat", 0, AISelection().id, "school_opaque")
        var afterStatus: () -> Unit = {}
        var creates = 0
        var consentWrites = 0
        var recoveries = 0
        val sent = mutableListOf<PendingQuestion>()
        val answer = AIMessage("answer", "assistant", "Saved answer", "complete", 0)
        var recovery = "complete"
        var stream: () -> Flow<AIStreamEvent> = { flowOf(AIStreamEvent.Started("answer", currentStatus),
            AIStreamEvent.Delta("Saved answer"), AIStreamEvent.Completed(answer, currentStatus)) }
        override suspend fun status(): AIStatus { afterStatus(); return currentStatus }
        override suspend fun acceptConsent(termsVersion: String): AIStatus { consentWrites++; return currentStatus }
        override suspend fun revokeConsent() { consentWrites++ }
        override suspend fun conversations(scope: String) = listOf(currentChat)
        override suspend fun create(scope: String, selectionID: String): AIChat { creates++; return currentChat }
        override suspend fun load(id: String) = AIDetail(currentChat, emptyList(), currentStatus)
        override suspend fun delete(id: String) = Unit
        override suspend fun deleteAll(scope: String) = Unit
        override suspend fun recover(request: AIRequestReference): AIRecovery { recoveries++; return AIRecovery(recovery, answer, currentStatus) }
        override fun reply(request: PendingQuestion): Flow<AIStreamEvent> { sent += request; return stream() }
    }
    private fun relay(client: Client, journal: Journal = Journal(), current: suspend () -> WearAIIdentity? = { identity }, allowed: Boolean = true, supporter: Boolean = true) =
        WearAIRelay(current, { allowed }, { supporter }, { client }, journal, { timestamp })

    @Test fun generalReplyUsesCatalogCostLanguageAndZeroSchoolRecords() = runTest {
        val client = Client(); val responses = relay(client).reply(request()).toList()
        assertThat(responses.map { it.kind }).containsExactly(WearAIResponseKind.STARTED, WearAIResponseKind.DELTA, WearAIResponseKind.DONE).inOrder()
        assertThat(responses.map { it.sequence }).containsExactly(0, 1, 2).inOrder()
        val sent = client.sent.single()
        assertThat(sent.selection).isEqualTo(AISelection())
        assertThat(sent.maximumComputeCost).isEqualTo(2)
        assertThat(sent.catalogVersion).isEqualTo("catalog")
        assertThat(sent.locale).isEqualTo("cs-US")
        listOf("subjects", "trends", "timetable", "events", "insights").forEach { assertThat((sent.context[it] as? List<*>).orEmpty()).isEmpty() }
        assertThat(client.consentWrites).isEqualTo(0)
    }
    @Test fun consentSupportAndBalanceGatesNeverCreateOrGenerate() = runTest {
        for ((consent, supporter, balance, reason) in listOf(
            Gate(true, true, 20, WearAIError.CONSENT_REQUIRED), Gate(false, false, 20, WearAIError.SUPPORTER_REQUIRED), Gate(false, true, 1, WearAIError.QUOTA_EXCEEDED))) {
            val client = Client().apply { currentStatus = currentStatus.copy(consentRequired = consent, remaining = balance) }
            assertThat(relay(client, supporter = supporter).reply(request()).toList().single().error).isEqualTo(reason)
            assertThat(client.creates).isEqualTo(0); assertThat(client.sent).isEmpty(); assertThat(client.consentWrites).isEqualTo(0)
        }
    }
    private data class Gate(val consent: Boolean, val supporter: Boolean, val balance: Int, val reason: WearAIError)
    @Test fun malformedExpiredAndOtherOwnerRequestsNeverReachTheService() = runTest {
        val client = Client(); val relay = relay(client)
        for (request in listOf(request().copy(requestID = "bad"), request().copy(createdAtEpochMillis = timestamp - 120_001),
            request().copy(text = " "), request().copy(text = "a".repeat(2001)))) {
            assertThat(relay.reply(request).toList().single().error).isEqualTo(WearAIError.INVALID_REQUEST)
        }
        assertThat(relay.reply(request().copy(identity = identity.copy(ownerID = "other"))).toList().single().error).isEqualTo(WearAIError.STALE_IDENTITY)
        assertThat(client.creates).isEqualTo(0)
    }
    @Test fun ownerChangeDuringAuthenticationCannotCreateAConversation() = runTest {
        var current = identity
        val client = Client().apply { afterStatus = { current = identity.copy(ownerID = "other") } }
        assertThat(relay(client, current = { current }).reply(request()).toList().single().error).isEqualTo(WearAIError.STALE_IDENTITY)
        assertThat(client.creates).isEqualTo(0)
    }
    @Test fun legacyOrOtherPurposeChatsCannotLeakOldSchoolContext() = runTest {
        for (selection in listOf(null, "school_opaque", AISelection(AIAction.SUBJECT, subjectID = "math").id)) {
            val client = Client().apply { currentChat = currentChat.copy(contextSelectionID = selection) }
            assertThat(relay(client).reply(request().copy(conversationID = "opaqueFirestoreDoc")).toList().single().error).isEqualTo(WearAIError.INVALID_REQUEST)
            assertThat(client.sent).isEmpty()
        }
    }
    @Test fun interruptedReplyRecoversWithoutNewGenerationEvenWithNoCompute() = runTest {
        val client = Client().apply { stream = { flow { throw IOException("offline") } } }
        val journal = Journal(); val first = request()
        assertThat(relay(client, journal).reply(first).toList().last().error).isEqualTo(WearAIError.REQUEST_FAILED)
        client.currentStatus = client.currentStatus.copy(remaining = 0)
        val restored = relay(client, journal).reply(first.copy(requestID = UUID.randomUUID().toString())).toList()
        assertThat(restored.last().kind).isEqualTo(WearAIResponseKind.DONE)
        assertThat(restored.filter { it.kind == WearAIResponseKind.DELTA }.joinToString("") { it.text.orEmpty() }).isEqualTo("Saved answer")
        assertThat(client.creates).isEqualTo(1); assertThat(client.sent).hasSize(1); assertThat(client.recoveries).isEqualTo(1)
    }
    @Test fun repeatedIdCannotChangeQuestionOrLanguageAndPendingDoesNotRegenerate() = runTest {
        val client = Client(); val journal = Journal(); val first = request(); val relay = relay(client, journal)
        relay.reply(first).toList()
        assertThat(relay.reply(first.copy(text = "different")).toList().last().error).isEqualTo(WearAIError.INVALID_REQUEST)
        assertThat(relay.reply(first.copy(locale = "en")).toList().last().error).isEqualTo(WearAIError.INVALID_REQUEST)
        client.recovery = "pending"
        assertThat(relay.reply(first).toList().last().error).isEqualTo(WearAIError.BUSY)
        assertThat(client.sent).hasSize(1)
    }
    @Test fun identityChangeDuringStreamSuppressesFollowingText() = runTest {
        var current = identity
        val client = Client().apply { stream = { flow {
            emit(AIStreamEvent.Started("answer", currentStatus)); emit(AIStreamEvent.Delta("allowed"))
            current = identity.copy(schoolScope = "different"); emit(AIStreamEvent.Delta("must not deliver"))
        } } }
        val responses = relay(client, current = { current }).reply(request()).toList()
        assertThat(responses.last().error).isEqualTo(WearAIError.STALE_IDENTITY)
        assertThat(responses.mapNotNull { it.text }).containsExactly("allowed")
    }
    @Test fun failedRecoveryPersistenceBlocksGeneration() = runTest {
        val client = Client(); val journal = Journal().apply { failWrite = true }
        assertThat(relay(client, journal).reply(request()).toList().last().error).isEqualTo(WearAIError.REQUEST_FAILED)
        assertThat(client.sent).isEmpty()
    }
    @Test fun chunksPreserveUnicodeAndFitTransportBounds() = runTest {
        val text = "😀".repeat(4001)
        val client = Client().apply { stream = { flowOf(AIStreamEvent.Completed(answer.copy(text = text), currentStatus)) } }
        val responses = relay(client).reply(request()).toList()
        val chunks = responses.filter { it.kind == WearAIResponseKind.DELTA }.map { it.text!! }
        assertThat(chunks.joinToString("")).isEqualTo(text)
        assertThat(chunks.all { it.length <= 4000 && !Character.isHighSurrogate(it.last()) }).isTrue()
        assertThat(responses.last().kind).isEqualTo(WearAIResponseKind.DONE)
    }
    @Test fun parallelRequestIsBusyAndCancellationKeepsRecoveryRecord() = runTest {
        val client = Client().apply { stream = { flow { awaitCancellation() } } }
        val journal = Journal(); val relay = relay(client, journal); val first = request()
        val running = launch { relay.reply(first).collect() }
        runCurrent()
        assertThat(relay.reply(request()).toList().single().error).isEqualTo(WearAIError.BUSY)
        running.cancel(); running.join()
        assertThat(journal.load(identity, first.clientMessageID)).isNotNull()
    }
    @Test fun brokenWatchDeliveryPropagatesWithoutAttemptingAnotherEmission() = runTest {
        val client = Client(); var count = 0; var failed = false
        try { relay(client).reply(request()).collect { count++; throw IOException("disconnected") } } catch (_: IOException) { failed = true }
        assertThat(failed).isTrue(); assertThat(count).isEqualTo(1); assertThat(client.sent).isEmpty()
    }
}
