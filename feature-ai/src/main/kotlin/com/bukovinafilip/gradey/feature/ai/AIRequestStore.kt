package com.bukovinafilip.gradey.feature.ai

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** Recovery metadata only: prompts, school records, and account tokens are never persisted here. */
internal data class AIRequestReference(val chatID: String, val id: String, val schoolScope: String, val selectionID: String)
internal interface AIRequestStore {
    fun load(scope: String): AIRequestReference?
    fun save(request: AIRequestReference)
    fun clear(scope: String)
}
internal class PreferencesAIRequestStore(context: Context, private val accountID: String?) : AIRequestStore {
    private val preferences = context.getSharedPreferences("gradey-ai-requests", Context.MODE_PRIVATE)
    private fun key(scope: String) = "${accountID.orEmpty()}:$scope"
    override fun load(scope: String): AIRequestReference? {
        val raw = preferences.getString(key(scope), null) ?: return null
        return runCatching {
            val values = Json.parseToJsonElement(raw) as JsonObject
            fun field(name: String) = requireNotNull(values[name]?.jsonPrimitive?.contentOrNull)
            AIRequestReference(field("chatID"), field("id"), scope, field("selectionID"))
        }.getOrNull()
    }
    override fun save(request: AIRequestReference) {
        val value = JsonObject(mapOf("chatID" to JsonPrimitive(request.chatID), "id" to JsonPrimitive(request.id), "selectionID" to JsonPrimitive(request.selectionID)))
        check(preferences.edit().putString(key(request.schoolScope), value.toString()).commit()) {
            "The request could not be saved for recovery. Free some device storage and try again."
        }
    }
    override fun clear(scope: String) {
        // A failed removal can only trigger read-only recovery on the next launch, never a new reply.
        preferences.edit().remove(key(scope)).commit()
    }
}
internal class MemoryAIRequestStore : AIRequestStore {
    private val requests = mutableMapOf<String, AIRequestReference>()
    override fun load(scope: String) = requests[scope]
    override fun save(request: AIRequestReference) { requests[request.schoolScope] = request }
    override fun clear(scope: String) { requests.remove(scope) }
}
