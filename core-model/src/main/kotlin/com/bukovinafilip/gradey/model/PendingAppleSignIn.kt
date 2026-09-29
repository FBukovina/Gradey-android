package com.bukovinafilip.gradey.model

import kotlinx.serialization.Serializable

/** Short-lived secret stored encrypted on the device, never in saved UI state or logs. */
@Serializable
class PendingAppleSignIn(
    val state: String,
    val codeVerifier: String,
    val createdAtEpochMillis: Long,
) {
    override fun toString(): String = "PendingAppleSignIn(redacted)"
}
