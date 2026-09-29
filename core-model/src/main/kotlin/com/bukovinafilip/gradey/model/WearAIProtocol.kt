package com.bukovinafilip.gradey.model

import kotlinx.serialization.Serializable

/** Non-secret identity binding. The scope is the phone's salted AI scope. */
@Serializable
data class WearAIIdentity(val ownerID: String, val schoolScope: String)

@Serializable
data class WearAIRequest(
    val schemaVersion: Int = 1,
    val requestID: String,
    val clientMessageID: String,
    val identity: WearAIIdentity,
    val createdAtEpochMillis: Long,
    val text: String,
    val conversationID: String? = null,
    val locale: String = "en",
)

@Serializable
data class WearAICancel(val schemaVersion: Int = 1, val requestID: String, val identity: WearAIIdentity)

@Serializable
data class WearAIResponse(
    val schemaVersion: Int = 1,
    val requestID: String,
    val identity: WearAIIdentity,
    val kind: WearAIResponseKind,
    val conversationID: String? = null,
    val text: String? = null,
    val error: WearAIError? = null,
    val remaining: Int? = null,
    val sequence: Int = 0,
)

@Serializable
enum class WearAIResponseKind { STARTED, DELTA, DONE, FAILED }

@Serializable
enum class WearAIError {
    CONSENT_REQUIRED, SUPPORTER_REQUIRED, NOT_CONFIGURED, NO_SCHOOL_ACCOUNT,
    QUOTA_EXCEEDED, CANCELLED, PHONE_UNREACHABLE, INVALID_REQUEST, STALE_IDENTITY,
    BUSY, TIMEOUT, REQUEST_FAILED,
}
