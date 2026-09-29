package com.bukovinafilip.gradey.model

import kotlinx.serialization.Serializable

@Serializable data class WearSchoolScope(val ownerID: String, val schoolScope: String)
@Serializable data class WearSchoolNodeGrant(val nodeID: String, val grantID: String, val expiresAtEpochMillis: Long)
/** Public revocation metadata only. Never put credentials into the shared DataItem. */
@Serializable data class WearSchoolAccess(val scope: WearSchoolScope, val grants: List<WearSchoolNodeGrant>)
@Serializable data class WearSchoolProvisionRequest(
    val schemaVersion: Int = 1,
    val requestID: String,
    val scope: WearSchoolScope,
    val grantID: String,
    val createdAtEpochMillis: Long,
    val publicKey: String,
)
@Serializable data class WearSchoolProvisionResponse(
    val schemaVersion: Int = 1,
    val requestID: String,
    val scope: WearSchoolScope,
    val grantID: String,
    val issuedAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
    val encryptedKey: String,
    val nonce: String,
    val ciphertext: String,
)
@Serializable enum class WearSchoolProvisionRejectionReason { ACCESS_DISABLED, INVALID_REQUEST, CREDENTIALS_UNAVAILABLE, SCHOOL_IDENTITY_UNAVAILABLE, PHONE_UNAVAILABLE }
@Serializable data class WearSchoolProvisionRejection(
    val schemaVersion: Int = 1,
    val requestID: String,
    val scope: WearSchoolScope,
    val grantID: String,
    val reason: WearSchoolProvisionRejectionReason,
)
/** Transferred only inside a one-time sealed envelope and stored only with a device-bound key. */
@Serializable class WearSchoolCredentials(
    val provider: SchoolProvider,
    val baseURL: String,
    val username: String,
    val password: String,
    val expectedUserID: String,
    val selectedStudentID: String? = null,
) {
    override fun toString() = "WearSchoolCredentials([redacted])"
}
@Serializable class WearSchoolStoredGrant(
    val scope: WearSchoolScope,
    val grantID: String,
    val nodeID: String,
    val phoneNodeID: String,
    val expiresAtEpochMillis: Long,
    val credentials: WearSchoolCredentials,
    val session: StoredSession? = null,
    /** Local provisioning instance; a reconnect under the same phone grant must not reuse it. */
    val connectionID: String = java.util.UUID.randomUUID().toString(),
) {
    override fun toString() = "WearSchoolStoredGrant([redacted])"
    fun withSession(session: StoredSession) = WearSchoolStoredGrant(scope, grantID, nodeID, phoneNodeID, expiresAtEpochMillis, credentials, session, connectionID)
}
