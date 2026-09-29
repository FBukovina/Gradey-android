package com.bukovinafilip.gradey.feature.ai

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import com.google.firebase.functions.StreamResponse
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Uses the established iOS callable contract, including Firebase identity and App Check. */
internal interface GradeyAIClient {
    val isConfigured: Boolean
    suspend fun status(): AIStatus
    suspend fun acceptConsent(termsVersion: String): AIStatus
    suspend fun revokeConsent()
    suspend fun conversations(scope: String): List<AIChat>
    suspend fun create(scope: String, selectionID: String = scope): AIChat
    suspend fun load(id: String): AIDetail
    suspend fun delete(id: String)
    suspend fun deleteAll(scope: String)
    fun reply(request: PendingQuestion): Flow<AIStreamEvent>
    suspend fun recover(request: AIRequestReference): AIRecovery
}

internal class FirebaseGradeyAIClient(context: Context, private val accountID: String?, private val gradeyAuth: GradeyAuthRepository? = null) : GradeyAIClient {
    private val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
    override val isConfigured: Boolean get() = app != null
    private val identityMutex = Mutex()

    init {
        app?.let {
            FirebaseAppCheck.getInstance(it).installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }
    }

    override suspend fun status(): AIStatus = AIContract.status(call("gradeyAIGetStatus"))

    override suspend fun acceptConsent(termsVersion: String): AIStatus {
        require(termsVersion.isNotBlank()) { "The consent terms are unavailable. Please refresh." }
        val result = call("gradeyAIAcceptConsent", mapOf("termsVersion" to termsVersion))
        return AIContract.status(result["status"].objectMap())
    }

    override suspend fun revokeConsent() {
        val result = call("gradeyAIRevokeConsent")
        check(result.boolean("success") == true) { "Consent could not be revoked. Please try again." }
        if (result.boolean("anonymousIdentityDeleted") == true) app?.let { FirebaseAuth.getInstance(it).signOut() }
    }

    override suspend fun conversations(scope: String): List<AIChat> {
        val result = call("gradeyAIListChats", mapOf("schoolScope" to scope))
        return (result["chats"] as? List<*>).orEmpty().map { AIContract.chat(it.objectMap()) }.sortedByDescending { it.updatedAt }
    }

    override suspend fun create(scope: String, selectionID: String): AIChat = AIContract.chat(
        call("gradeyAICreateChat", mapOf("schoolScope" to scope, "contextSelectionID" to selectionID))["chat"].objectMap(),
    )

    override suspend fun load(id: String): AIDetail = AIContract.detail(call("gradeyAILoadChat", mapOf("chatID" to id)))

    override suspend fun delete(id: String) {
        check(call("gradeyAIDeleteChat", mapOf("chatID" to id)).boolean("success") == true) {
            "The conversation could not be deleted. Please try again."
        }
    }

    override suspend fun deleteAll(scope: String) {
        check(call("gradeyAIDeleteAll", mapOf("schoolScope" to scope)).boolean("success") == true) {
            "The conversations could not be deleted. Please try again."
        }
    }

    override suspend fun recover(request: AIRequestReference): AIRecovery {
        val data = call("gradeyAIGetRequest", mapOf("chatID" to request.chatID, "clientMessageID" to request.id,
            "schoolScope" to request.schoolScope, "contextSelectionID" to request.selectionID))
        return AIRecovery(data.string("state") ?: error("The saved request status is unavailable. Please retry recovery."),
            AIContract.message(data["message"].objectMap()), (data["status"] as? Map<*, *>)?.let { AIContract.status(it.objectMap()) })
    }

    override fun reply(request: PendingQuestion): Flow<AIStreamEvent> = flow {
        val schoolContext = request.context
        val text = request.text.trim()
        require(text.isNotBlank() && text.codePointCount(0, text.length) <= 2_000) { "Enter a question of 2,000 characters or fewer." }
        require(AIContext.encodedSize(schoolContext) <= AIContext.MAX_CONTEXT_BYTES) { "The school context is too large." }
        val payload = accountPayload(mapOf(
            "chatID" to request.chatID, "clientMessageID" to request.id, "text" to text,
            "locale" to request.locale, "schoolScope" to schoolContext["schoolScope"],
            "context" to schoolContext, "contextGeneratedAt" to schoolContext["generatedAt"],
            "actionID" to request.selection.action.id, "catalogVersion" to request.catalogVersion,
            "maximumComputeCost" to request.maximumComputeCost, "contextSelectionID" to request.selection.id,
        ))
        require(AIContext.encodedSize(payload) <= AIContext.MAX_REQUEST_BYTES) { "The request is too large." }
        try {
            val firebaseApp = checkNotNull(app) { "Gradey AI is not available in this build." }
            ensureIdentity(firebaseApp)
            val options = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(true).build()
            val callable = FirebaseFunctions.getInstance(firebaseApp, "europe-west1")
                .getHttpsCallable("gradeyAIStreamReply", options).withTimeout(120, TimeUnit.SECONDS)
            var terminal = false
            callable.stream(payload).asFlow().collect { response ->
                // Firebase also supplies the terminal chunk as its final callable result.
                if (terminal) return@collect
                val data = when (response) {
                    is StreamResponse.Message -> response.message.data
                    is StreamResponse.Result -> response.result.data
                    else -> error("Gradey AI returned an unsupported reply.")
                }
                val event = AIContract.event(data.objectMap())
                emit(event)
                terminal = event is AIStreamEvent.Completed || event is AIStreamEvent.Failed
            }
            check(terminal) { "The connection ended before the reply finished. Reload the conversation before retrying." }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw mappedError(error)
        }
    }

    private suspend fun ensureIdentity(firebaseApp: FirebaseApp) = identityMutex.withLock {
        val auth = FirebaseAuth.getInstance(firebaseApp)
        if (auth.currentUser == null) auth.signInAnonymously().await()
        try {
            auth.currentUser?.getIdToken(false)?.await()
        } catch (error: FirebaseAuthException) {
            // Recreate revoked/deleted anonymous identities, but keep sessions on network failures.
            if (error.errorCode !in setOf("ERROR_USER_TOKEN_EXPIRED", "ERROR_INVALID_USER_TOKEN", "ERROR_USER_NOT_FOUND", "ERROR_USER_DISABLED")) throw error
            auth.signOut()
            auth.signInAnonymously().await()
        }
    }

    private suspend fun accountPayload(body: Map<String, Any?>): Map<String, Any?> {
        val id = accountID?.trim()?.takeIf(String::isNotBlank)
        val session = gradeyAuth?.bootstrapSession()
        if (id != null) check(session?.account?.id == id && session.accessToken.isNotBlank()) {
            "Sign in to Gradey ID again to verify your AI account."
        }
        return body.toMutableMap().apply {
            id?.let { put("gradey_account_id", it) }
            if (id != null) put("gradeyAccessToken", session?.accessToken)
        }
    }

    private suspend fun call(name: String, body: Map<String, Any?> = emptyMap(), limitedUseAppCheck: Boolean = false): Map<String, Any?> {
        val firebaseApp = checkNotNull(app) { "Gradey AI is not available in this build." }
        try {
            ensureIdentity(firebaseApp)
            val payload = accountPayload(body)
            val options = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(limitedUseAppCheck).build()
            val callable = FirebaseFunctions.getInstance(firebaseApp, "europe-west1").getHttpsCallable(name, options)
            callable.setTimeout(120, TimeUnit.SECONDS)
            return callable.call(payload).await().getData().objectMap()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            throw mappedError(error)
        }
    }

    private fun mappedError(error: Exception): Exception {
        if (error is FirebaseAuthException) return IllegalStateException("Gradey AI couldn’t start a secure session. Please try again.", error)
        if (error is FirebaseFunctionsException) {
            error.details.objectMap().string("code")?.let { return IllegalStateException(AIContract.failureMessage(it), error) }
            val message = when (error.code) {
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Gradey AI couldn’t verify your session. Please refresh and try again."
                FirebaseFunctionsException.Code.PERMISSION_DENIED -> "This app could not be verified for Gradey AI. Please use the latest supported app version."
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Your Gradey Compute limit has been reached. Refresh to see when it resets."
                FirebaseFunctionsException.Code.NOT_FOUND -> "This conversation or AI service is no longer available. Refresh your conversations."
                FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "Gradey AI could not be reached. Reload the conversation before retrying your question."
                else -> error.details.objectMap().string("message")?.take(400) ?: "Gradey AI could not complete this request. Please try again."
            }
            return IllegalStateException(message, error)
        }
        if (error is java.io.IOException) return IllegalStateException("Check your connection and reload the conversation before retrying.", error)
        return error
    }
}

internal object AIIdentitySession { var generation by mutableIntStateOf(0) }

/** Clear the local AI identity when Gradey signs out; does not delete server conversations. */
fun resetAiIdentity(context: Context) {
    AIIdentitySession.generation++
    context.getSharedPreferences("gradey-ai-requests", Context.MODE_PRIVATE).edit().clear().apply()
    context.getSharedPreferences("gradey-wear-ai-requests", Context.MODE_PRIVATE).edit().clear().apply()
    FirebaseApp.getApps(context).firstOrNull()?.let { app ->
        val auth = FirebaseAuth.getInstance(app)
        if (auth.currentUser?.isAnonymous == true) auth.signOut()
    }
}
