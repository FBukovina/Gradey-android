package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.util.TreeMap

data class WearAIStreamState(
    val answer: String = "",
    val conversationID: String? = null,
    val remaining: Int? = null,
    val complete: Boolean = false,
    val error: WearAIError? = null,
)

/** Correlates the paired node, owner, school, request and bounded stream order. */
class WearAIStreamAccumulator(
    private val requestID: String,
    private val identity: WearAIIdentity,
    private val sourceNodeID: String,
) {
    private var nextSequence = 0
    private val buffered = TreeMap<Int, WearAIResponse>()
    var state = WearAIStreamState()
        private set

    fun accept(nodeID: String, response: WearAIResponse): WearAIStreamState? {
        if (state.complete || nodeID != sourceNodeID || response.schemaVersion != 1 || response.requestID != requestID || response.identity != identity) return null
        if (response.sequence < nextSequence || buffered.containsKey(response.sequence)) return null
        if (response.sequence > nextSequence + 32 || response.sequence > 10_000) return fail()
        buffered[response.sequence] = response
        while (true) {
            val event = buffered.remove(nextSequence) ?: break
            nextSequence++
            val nextText = when (event.kind) {
                WearAIResponseKind.DELTA -> state.answer + event.text.orEmpty()
                WearAIResponseKind.DONE -> event.text?.takeIf(String::isNotBlank) ?: state.answer
                else -> state.answer
            }
            if (nextText.codePointCount(0, nextText.length) > WearAIProtocol.MAX_RESPONSE_CODE_POINTS) return fail()
            val conversationID = event.conversationID ?: state.conversationID
            if (state.conversationID != null && conversationID != state.conversationID) return fail()
            state = state.copy(
                answer = nextText,
                conversationID = conversationID,
                remaining = event.remaining?.coerceAtLeast(0) ?: state.remaining,
                complete = event.kind == WearAIResponseKind.DONE || event.kind == WearAIResponseKind.FAILED,
                error = if (event.kind == WearAIResponseKind.FAILED) event.error ?: WearAIError.REQUEST_FAILED else null,
            )
            if (state.complete) { buffered.clear(); break }
        }
        return state
    }

    private fun fail(): WearAIStreamState {
        buffered.clear()
        state = state.copy(complete = true, error = WearAIError.INVALID_REQUEST)
        return state
    }
}
