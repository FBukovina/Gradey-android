package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.WearAIIdentity
import com.bukovinafilip.gradey.model.WearAIRequest
import java.util.UUID

object WearAIProtocol {
    const val REQUEST_PATH = "/gradey/ai/request"
    const val CANCEL_PATH = "/gradey/ai/cancel"
    const val RESPONSE_PATH = "/gradey/ai/response"
    const val MAX_MESSAGE_BYTES = 48 * 1024
    const val MAX_QUESTION_CODE_POINTS = 2_000
    const val MAX_RESPONSE_CODE_POINTS = 40_000
    const val REQUEST_LIFETIME_MILLIS = 120_000L
    const val RESPONSE_TIMEOUT_MILLIS = 120_000L

    fun valid(request: WearAIRequest, expected: WearAIIdentity, now: Long): Boolean =
        request.schemaVersion == 1 && request.identity == expected && valid(expected) &&
            isUUID(request.requestID) && isUUID(request.clientMessageID) &&
            (request.conversationID?.let { Regex("[A-Za-z0-9_-]{1,160}").matches(it) } != false) &&
            request.createdAtEpochMillis in (now - REQUEST_LIFETIME_MILLIS)..(now + 30_000) &&
            request.text.isNotBlank() && request.text.codePointCount(0, request.text.length) <= MAX_QUESTION_CODE_POINTS &&
            request.locale.length in 2..35 && request.locale.all { it.isLetterOrDigit() || it == '-' }

    fun valid(identity: WearAIIdentity): Boolean =
        identity.ownerID.isNotBlank() && identity.ownerID.length <= 128 &&
            identity.schoolScope.isNotBlank() && identity.schoolScope.length <= 256

    fun isUUID(value: String): Boolean = runCatching { UUID.fromString(value).toString().equals(value, ignoreCase = true) }.getOrDefault(false)
}
