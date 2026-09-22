package com.bukovinafilip.gradey.feature.ai

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.functions.FirebaseFunctions
import com.google.firebase.functions.FirebaseFunctionsException
import com.google.firebase.functions.HttpsCallableOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Uses the established iOS callable contract, including Firebase identity and App Check. */
internal class FirebaseGradeyAIClient(context: Context, private val accountID: String?) {
    private val app = FirebaseApp.getApps(context).firstOrNull() ?: FirebaseApp.initializeApp(context)
    val isConfigured: Boolean get() = app != null
    private val identityMutex = Mutex()

    init {
        app?.let {
            FirebaseAppCheck.getInstance(it).installAppCheckProviderFactory(PlayIntegrityAppCheckProviderFactory.getInstance())
        }
    }

    suspend fun status(): AIStatus = AIContract.status(call("gradeyAIGetStatus"))

    suspend fun acceptConsent(termsVersion: String): AIStatus {
        require(termsVersion.isNotBlank()) { "The consent terms are unavailable. Please refresh." }
        val result = call("gradeyAIAcceptConsent", mapOf("termsVersion" to termsVersion))
        return AIContract.status(result["status"].objectMap())
    }

    suspend fun revokeConsent() {
        val result = call("gradeyAIRevokeConsent")
        check(result.boolean("success") == true) { "Consent could not be revoked. Please try again." }
        if (result.boolean("anonymousIdentityDeleted") == true) app?.let { FirebaseAuth.getInstance(it).signOut() }
    }

    suspend fun conversations(scope: String): List<AIChat> {
        val result = call("gradeyAIListChats", mapOf("schoolScope" to scope))
        return (result["chats"] as? List<*>).orEmpty().map { AIContract.chat(it.objectMap()) }.sortedByDescending { it.updatedAt }
    }

    suspend fun create(scope: String): AIChat = AIContract.chat(
        call("gradeyAICreateChat", mapOf("schoolScope" to scope))["chat"].objectMap(),
    )

    suspend fun load(id: String): AIDetail = AIContract.detail(call("gradeyAILoadChat", mapOf("chatID" to id)))

    suspend fun delete(id: String) {
        check(call("gradeyAIDeleteChat", mapOf("chatID" to id)).boolean("success") == true) {
            "The conversation could not be deleted. Please try again."
        }
    }

    suspend fun deleteAll(scope: String) {
        check(call("gradeyAIDeleteAll", mapOf("schoolScope" to scope)).boolean("success") == true) {
            "The conversations could not be deleted. Please try again."
        }
    }

    suspend fun reply(chatID: String, clientMessageID: String, prompt: String, schoolContext: Map<String, Any?>): AIDetail {
        val text = prompt.trim()
        require(text.isNotBlank() && text.length <= 2_000) { "Enter a question of 2,000 characters or fewer." }
        require(AIContext.encodedSize(schoolContext) <= AIContext.MAX_CONTEXT_BYTES) { "The school context is too large." }
        val payload = mapOf(
            "chatID" to chatID, "clientMessageID" to clientMessageID, "text" to text,
            "locale" to Locale.getDefault().toLanguageTag(), "schoolScope" to schoolContext["schoolScope"],
            "context" to schoolContext, "contextGeneratedAt" to schoolContext["generatedAt"],
        )
        require(AIContext.encodedSize(payload) <= AIContext.MAX_REQUEST_BYTES) { "The request is too large." }
        // The pinned Android SDK receives the final callable result; persisted chat is authoritative.
        val result = call("gradeyAIStreamReply", payload, limitedUseAppCheck = true)
        when (result.string("type")) {
            "completed" -> Unit
            "failed" -> error(result.string("message", "text") ?: "Gradey AI could not finish this reply. Reload the conversation before trying again.")
            else -> error("The reply status could not be confirmed. Reload the conversation before trying again.")
        }
        return load(chatID)
    }

    private suspend fun call(name: String, body: Map<String, Any?> = emptyMap(), limitedUseAppCheck: Boolean = false): Map<String, Any?> {
        val firebaseApp = checkNotNull(app) { "Gradey AI is not available in this build." }
        try {
            identityMutex.withLock {
                val auth = FirebaseAuth.getInstance(firebaseApp)
                if (auth.currentUser == null) auth.signInAnonymously().await()
                auth.currentUser?.getIdToken(false)?.await()
            }
            val payload = body.toMutableMap()
            accountID?.takeIf { it.isNotBlank() }?.let { payload["gradey_account_id"] = it }
            val options = HttpsCallableOptions.Builder().setLimitedUseAppCheckTokens(limitedUseAppCheck).build()
            val callable = FirebaseFunctions.getInstance(firebaseApp, "europe-west1").getHttpsCallable(name, options)
            callable.setTimeout(120, TimeUnit.SECONDS)
            return callable.call(payload).await().getData().objectMap()
        } catch (error: CancellationException) {
            throw error
        } catch (error: FirebaseFunctionsException) {
            val message = when (error.code) {
                FirebaseFunctionsException.Code.UNAUTHENTICATED -> "Gradey AI couldn’t verify your session. Please refresh and try again."
                FirebaseFunctionsException.Code.PERMISSION_DENIED -> "This app could not be verified for Gradey AI. Please use the latest supported app version."
                FirebaseFunctionsException.Code.RESOURCE_EXHAUSTED -> "Your Gradey AI limit has been reached. Refresh to see when it resets."
                FirebaseFunctionsException.Code.NOT_FOUND -> "Gradey AI is not available for this app yet."
                FirebaseFunctionsException.Code.UNAVAILABLE, FirebaseFunctionsException.Code.DEADLINE_EXCEEDED ->
                    "Gradey AI could not be reached. Reload the conversation before retrying your question."
                else -> error.details.objectMap().string("message")?.take(400) ?: "Gradey AI could not complete this request. Please try again."
            }
            throw IllegalStateException(message, error)
        }
    }
}

/** Clear the local AI identity when Gradey signs out; does not delete server conversations. */
fun resetAiIdentity(context: Context) {
    FirebaseApp.getApps(context).firstOrNull()?.let { app ->
        val auth = FirebaseAuth.getInstance(app)
        if (auth.currentUser?.isAnonymous == true) auth.signOut()
    }
}
