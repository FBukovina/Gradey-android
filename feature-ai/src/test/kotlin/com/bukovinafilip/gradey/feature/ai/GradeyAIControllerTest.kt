package com.bukovinafilip.gradey.feature.ai

import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.StoredSession
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GradeyAIControllerTest {
    private val dispatcher = StandardTestDispatcher()
    private val salt = ByteArray(32) { 1 }
    private var session = StoredSession("school-secret", "refresh", "Bearer", 1, "https://school.example")
    private var schoolReads = 0
    private val school = Proxy.newProxyInstance(SchoolRepository::class.java.classLoader, arrayOf(SchoolRepository::class.java)) { _, method, _ ->
        when (method.name) {
            "currentStoredSession" -> session
            "loadDashboard", "loadCachedDashboard" -> { schoolReads++; DashboardData(MarksResponse()) }
            "toString" -> "Test school repository"
            else -> error("Unexpected school request: ${method.name}")
        }
    } as SchoolRepository

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun streamingShowsDeltasAndStopPreservesRecoveryReference() = runTest(dispatcher) {
        val client = FakeAIClient()
        client.replyFlow = { request -> flow {
            emit(AIStreamEvent.Started("answer", client.currentStatus))
            emit(AIStreamEvent.Delta("Let’s make a plan"))
            awaitCancellation()
        } }
        val store = MemoryAIRequestStore()
        val controller = GradeyAIController(client, school, salt, requestStore = store)
        runCurrent(); controller.newChat(); runCurrent(); controller.draft("Help me revise"); controller.send(); runCurrent()
        assertThat(controller.state.value.sending).isTrue()
        assertThat(controller.state.value.messages.last().text).isEqualTo("Let’s make a plan")
        controller.stop(); runCurrent()
        assertThat(controller.state.value.sending).isFalse()
        assertThat(controller.state.value.messages.last().status).isEqualTo("cancelled")
        assertThat(store.load(AIContext.schoolScope(session, salt))).isNotNull()
    }

    @Test fun generalReplyNeverFetchesOrSendsSchoolRecords() = runTest(dispatcher) {
        val client = FakeAIClient()
        val controller = GradeyAIController(client, school, salt)
        runCurrent(); controller.newChat(); runCurrent(); controller.draft("Explain fractions"); controller.send(); runCurrent()
        val request = client.requests.single()
        assertThat(schoolReads).isEqualTo(0)
        assertThat(request.context["subjects"] as List<*>).isEmpty()
        assertThat(request.context["timetable"] as List<*>).isEmpty()
        assertThat(request.context["unavailableSections"] as List<*>).isEmpty()
        assertThat(request.maximumComputeCost).isEqualTo(2)
        assertThat(request.catalogVersion).isEqualTo("current-prices")
        assertThat(request.context.toString()).doesNotContain("school-secret")
    }

    @Test fun failedRecoveryWriteKeepsDraftAndDoesNotStartGeneration() = runTest(dispatcher) {
        val client = FakeAIClient()
        val store = object : AIRequestStore {
            override fun load(scope: String): AIRequestReference? = null
            override fun save(request: AIRequestReference) { error("Recovery metadata could not be saved.") }
            override fun clear(scope: String) = Unit
        }
        val controller = GradeyAIController(client, school, salt, requestStore = store)
        runCurrent(); controller.newChat(); runCurrent(); controller.draft("Explain fractions"); controller.send(); runCurrent()
        assertThat(client.requests).isEmpty()
        assertThat(controller.state.value.draft).isEqualTo("Explain fractions")
        assertThat(controller.state.value.pending).isNull()
        assertThat(controller.state.value.recovery).isNull()
        assertThat(controller.state.value.sending).isFalse()
        assertThat(controller.state.value.error).isNotNull()
    }

    @Test fun retryQueriesRecoveryAndRestoresCompletedReplyEvenWithZeroCompute() = runTest(dispatcher) {
        val client = FakeAIClient()
        client.replyFlow = { flow { throw java.io.IOException("Connection lost") } }
        val controller = GradeyAIController(client, school, salt)
        runCurrent(); controller.newChat(); runCurrent(); controller.draft("Explain fractions"); controller.send(); runCurrent()
        client.currentStatus = client.currentStatus.copy(remaining = 0)
        client.recoveryState = "complete"
        controller.send(retry = true); runCurrent()
        assertThat(client.recoveryCalls).isEqualTo(1)
        assertThat(client.requests).hasSize(1)
        assertThat(controller.state.value.pending).isNull()
        assertThat(controller.state.value.recovery).isNull()
        assertThat(controller.state.value.messages.last().text).isEqualTo("Saved answer")
        assertThat(controller.state.value.status?.remaining).isEqualTo(0)
    }

    @Test fun confirmedFailedAttemptGetsNewIdOnlyForExplicitRetry() = runTest(dispatcher) {
        val client = FakeAIClient()
        client.replyFlow = { flowOf(AIStreamEvent.Failed("Interrupted", client.currentStatus, true)) }
        val controller = GradeyAIController(client, school, salt)
        runCurrent(); controller.newChat(); runCurrent(); controller.draft("Explain fractions"); controller.send(); runCurrent()
        client.recoveryState = "failed"
        controller.send(retry = true); runCurrent()
        assertThat(client.requests).hasSize(2)
        assertThat(client.requests[1].id).isNotEqualTo(client.requests[0].id)
        assertThat(client.requests[1].text).isEqualTo(client.requests[0].text)
        assertThat(client.requests[1].context).isEqualTo(client.requests[0].context)
    }

    @Test fun pendingRecoveryBlocksNewQuestionsWithoutSpendingCompute() = runTest(dispatcher) {
        val client = FakeAIClient()
        client.replyFlow = { flow { throw java.io.IOException("Connection lost") } }
        val controller = GradeyAIController(client, school, salt)
        runCurrent(); controller.newChat(); runCurrent(); controller.draft("First question"); controller.send(); runCurrent()
        client.recoveryState = "pending"
        controller.draft("A different question"); controller.send(); runCurrent()
        assertThat(client.requests).hasSize(1)
        assertThat(controller.state.value.draft).isEqualTo("A different question")
        assertThat(controller.state.value.recovery).isNotNull()
    }

    @Test fun accountSwitchClearsOldMessagesAndPreventsSend() = runTest(dispatcher) {
        val client = FakeAIClient()
        val controller = GradeyAIController(client, school, salt)
        runCurrent(); controller.newChat(); runCurrent(); controller.draft("Private question")
        session = session.copy(baseURL = "https://other.example")
        controller.send(); runCurrent()
        assertThat(client.requests).isEmpty()
        assertThat(controller.state.value.currentChat).isNull()
        assertThat(controller.state.value.draft).isEmpty()
    }

    @Test fun changedTermsMustBeShownBeforeTheyCanBeAccepted() = runTest(dispatcher) {
        val client = FakeAIClient()
        client.currentStatus = client.currentStatus.copy(consentRequired = true)
        val controller = GradeyAIController(client, school, salt)
        runCurrent()
        client.currentStatus = client.currentStatus.copy(termsVersion = "v2")
        controller.acceptConsent(); runCurrent()
        assertThat(client.acceptedTerms).isEmpty()
        assertThat(controller.state.value.status?.termsVersion).isEqualTo("v2")
        controller.acceptConsent(); runCurrent()
        assertThat(client.acceptedTerms).containsExactly("v2")
    }

    @Test fun initialActionAppliesOnceAndDoesNotReplaceManualSelection() = runTest(dispatcher) {
        val client = FakeAIClient()
        val controller = GradeyAIController(client, school, salt)
        // The navigation request arrives while bootstrap is still in progress.
        controller.initialEntry(AISelection(), "tap-1", "Explain fractions")
        runCurrent()
        assertThat(client.creationCalls).isEqualTo(1)
        assertThat(controller.state.value.draft).isEqualTo("Explain fractions")
        controller.close(); controller.selectAction(AIAction.SUBJECT); runCurrent()
        controller.initialEntry(AISelection(), "tap-1", "Explain fractions")
        runCurrent()
        assertThat(controller.state.value.selection.action).isEqualTo(AIAction.SUBJECT)
        assertThat(client.creationCalls).isEqualTo(1)
        controller.initialEntry(AISelection(), "tap-2", "Explain fractions")
        runCurrent()
        assertThat(controller.state.value.selection.action).isEqualTo(AIAction.REPLY)
        assertThat(client.creationCalls).isEqualTo(2)
    }

    @Test fun requestLocaleUsesTheSelectedAppLanguage() = runTest(dispatcher) {
        val client = FakeAIClient()
        val controller = GradeyAIController(client, school, salt)
        controller.locale("cs-US")
        runCurrent(); controller.newChat(); runCurrent(); controller.draft("Jak se mám učit?"); controller.send(); runCurrent()
        assertThat(client.requests.single().locale).isEqualTo("cs-US")
    }

    private class FakeAIClient : GradeyAIClient {
        override val isConfigured = true
        var currentStatus = AIStatus(true, false, "v1", 20, 20, null, compute = AICompute(1, "current-prices", 0,
            listOf(AIComputeAction("reply", 2, true))))
        val requests = mutableListOf<PendingQuestion>()
        val acceptedTerms = mutableListOf<String>()
        var recoveryState = "missing"
        var recoveryCalls = 0
        var creationCalls = 0
        var chat = AIChat("chat", "Revision", 1)
        var replyFlow: (PendingQuestion) -> Flow<AIStreamEvent> = { flowOf(AIStreamEvent.Completed(savedAnswer, currentStatus)) }
        override suspend fun status() = currentStatus
        override suspend fun acceptConsent(termsVersion: String): AIStatus { acceptedTerms += termsVersion; currentStatus = currentStatus.copy(consentRequired = false); return currentStatus }
        override suspend fun revokeConsent() = Unit
        override suspend fun conversations(scope: String) = listOf(chat)
        override suspend fun create(scope: String, selectionID: String): AIChat { creationCalls++; chat = chat.copy(contextSelectionID = selectionID, schoolScope = scope); return chat }
        override suspend fun load(id: String) = AIDetail(chat, listOf(savedAnswer), currentStatus)
        override suspend fun delete(id: String) = Unit
        override suspend fun deleteAll(scope: String) = Unit
        override suspend fun recover(request: AIRequestReference): AIRecovery { recoveryCalls++; return AIRecovery(recoveryState, savedAnswer, currentStatus) }
        override fun reply(request: PendingQuestion): Flow<AIStreamEvent> { requests += request; return replyFlow(request) }
        companion object { val savedAnswer = AIMessage("answer", "assistant", "Saved answer", "complete", 10) }
    }
}
