package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.net.URI
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.security.spec.MGF1ParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.OAEPParameterSpec
import javax.crypto.spec.PSource
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json

object WearSchoolProtocol {
    const val REQUEST_PATH = "/gradey/school/provision-request"
    const val RESPONSE_PATH = "/gradey/school/provision-response"
    const val REJECTION_PATH = "/gradey/school/provision-rejected"
    const val MAX_MESSAGE_BYTES = 32 * 1024
    const val LEASE_MILLIS = 7L * 24 * 60 * 60_000
    const val REQUEST_LIFETIME_MILLIS = 120_000L
    private val json = Json { ignoreUnknownKeys = true }
    // Android Keystore supports SHA-256 OAEP with SHA-1 MGF1 across our supported API levels.
    private val oaep = OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT)

    fun valid(scope: WearSchoolScope): Boolean = listOf(scope.ownerID, scope.schoolScope).all { value ->
        value.length in 1..256 && value.none { it.isISOControl() }
    }
    fun grant(access: WearSchoolAccess?, nodeID: String, now: Long): WearSchoolNodeGrant? = access
        ?.takeIf { valid(it.scope) && it.grants.size <= 8 }
        ?.grants?.singleOrNull { it.nodeID == nodeID && WearAIProtocol.isUUID(it.grantID) && it.expiresAtEpochMillis > now && it.expiresAtEpochMillis <= now + LEASE_MILLIS + 30_000 }

    fun valid(request: WearSchoolProvisionRequest, now: Long): Boolean = request.schemaVersion == 1 && valid(request.scope) &&
        WearAIProtocol.isUUID(request.requestID) && WearAIProtocol.isUUID(request.grantID) &&
        request.createdAtEpochMillis in (now - REQUEST_LIFETIME_MILLIS)..(now + 30_000) && request.publicKey.length in 256..1024 &&
        runCatching { publicKey(request.publicKey); true }.getOrDefault(false)

    fun valid(credentials: WearSchoolCredentials): Boolean = credentials.username.isNotBlank() && credentials.username.length <= 256 &&
        credentials.password.isNotEmpty() && credentials.password.length <= 4096 && credentials.expectedUserID.isNotBlank() && credentials.expectedUserID.length <= 256 &&
        (credentials.selectedStudentID?.length ?: 0) <= 256 && credentials.baseURL.length <= 1024 && runCatching {
            val uri = URI(credentials.baseURL)
            uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null &&
                (credentials.provider != SchoolProvider.EDU_PAGE || uri.host == "edupage.org" || uri.host.endsWith(".edupage.org"))
        }.getOrDefault(false)

    /** Caller must first verify nearby source node, current opt-in, exact scope and grant ID. */
    fun seal(request: WearSchoolProvisionRequest, credentials: WearSchoolCredentials, expiresAtEpochMillis: Long, now: Long): WearSchoolProvisionResponse {
        require(valid(request, now) && valid(credentials)) { "Invalid watch connection" }
        require(expiresAtEpochMillis > now && expiresAtEpochMillis <= now + LEASE_MILLIS) { "Invalid watch lease" }
        val key = ByteArray(32).also(SecureRandom()::nextBytes)
        val nonce = ByteArray(12).also(SecureRandom()::nextBytes)
        try {
            val rsa = Cipher.getInstance("RSA/ECB/OAEPPadding").apply { init(Cipher.ENCRYPT_MODE, publicKey(request.publicKey), oaep) }
            val envelope = WearSchoolProvisionResponse(requestID = request.requestID, scope = request.scope, grantID = request.grantID,
                issuedAtEpochMillis = now, expiresAtEpochMillis = expiresAtEpochMillis,
                encryptedKey = encode(rsa.doFinal(key)), nonce = encode(nonce), ciphertext = "")
            val aes = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad(envelope))
            }
            return envelope.copy(ciphertext = encode(aes.doFinal(json.encodeToString(WearSchoolCredentials.serializer(), credentials).toByteArray(Charsets.UTF_8))))
        } finally { key.fill(0) }
    }

    /** Exact request correlation and public grant checks happen before decryption. */
    fun open(response: WearSchoolProvisionResponse, request: WearSchoolProvisionRequest, privateKey: PrivateKey, grant: WearSchoolNodeGrant, now: Long): WearSchoolCredentials {
        require(valid(request, now) && response.schemaVersion == 1 && response.requestID == request.requestID && response.scope == request.scope && response.grantID == request.grantID && grant.grantID == request.grantID) { "Invalid watch connection" }
        require(response.issuedAtEpochMillis in (now - REQUEST_LIFETIME_MILLIS)..(now + 30_000) && response.expiresAtEpochMillis > now &&
            response.expiresAtEpochMillis <= response.issuedAtEpochMillis + LEASE_MILLIS && response.expiresAtEpochMillis <= grant.expiresAtEpochMillis) { "Invalid watch lease" }
        require(response.encryptedKey.length in 256..1024 && response.nonce.length <= 32 && response.ciphertext.length in 1..MAX_MESSAGE_BYTES) { "Invalid watch envelope" }
        val key = Cipher.getInstance("RSA/ECB/OAEPPadding").run { init(Cipher.DECRYPT_MODE, privateKey, oaep); doFinal(decode(response.encryptedKey)) }
        try {
            require(key.size == 32) { "Invalid watch key" }
            val nonce = decode(response.nonce)
            require(nonce.size == 12) { "Invalid watch nonce" }
            val data = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); updateAAD(aad(response)); doFinal(decode(response.ciphertext))
            }
            try {
                return json.decodeFromString(WearSchoolCredentials.serializer(), data.toString(Charsets.UTF_8)).also { require(valid(it)) { "Invalid watch credentials" } }
            } finally { data.fill(0) }
        } finally { key.fill(0) }
    }
    private fun publicKey(value: String): RSAPublicKey = (KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decode(value))) as RSAPublicKey)
        .also { require(it.modulus.bitLength() in 2048..4096) { "Invalid watch key" } }
    private fun aad(value: WearSchoolProvisionResponse) = listOf("gradey-watch-school-v1", value.requestID, value.scope.ownerID, value.scope.schoolScope, value.grantID,
        value.issuedAtEpochMillis.toString(), value.expiresAtEpochMillis.toString()).joinToString("\u0000").toByteArray(Charsets.UTF_8)
    private fun encode(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)
    private fun decode(value: String) = Base64.getDecoder().decode(value)
}
