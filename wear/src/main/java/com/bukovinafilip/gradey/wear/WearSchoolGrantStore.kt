package com.bukovinafilip.gradey.wear

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.bukovinafilip.gradey.domain.*
import com.bukovinafilip.gradey.model.*
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.json.Json

/** Credentials never enter gradey-watch snapshot preferences or Android backups. */
object WearSchoolGrantStore {
    private const val FILE = "gradey-watch-school-private"
    private const val AES_ALIAS = "gradey-watch-school-storage-v1"
    private const val REQUEST_PREFIX = "gradey-watch-school-request-"
    private val json = Json { ignoreUnknownKeys = true }
    private fun preferences(context: Context) = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun keys() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    fun requireUnlocked(context: Context) {
        val lock = context.getSystemService(KeyguardManager::class.java)
        if (!lock.isDeviceSecure) throw WearSchoolException(WearSchoolFailure.LOCK_REQUIRED)
        if (lock.isDeviceLocked) throw WearSchoolException(WearSchoolFailure.UNLOCK_REQUIRED)
    }

    @Synchronized
    fun createRequest(context: Context, nodeID: String, phoneNodeID: String): WearSchoolProvisionRequest {
        requireUnlocked(context)
        val snapshot = WearSnapshotStore.load(context)
        val access = snapshot?.takeIf { it.isSignedIn }?.schoolAccess ?: throw WearSchoolException(WearSchoolFailure.DISABLED)
        val grant = WearSchoolProtocol.grant(access, nodeID, System.currentTimeMillis()) ?: throw WearSchoolException(WearSchoolFailure.DISABLED)
        if (WearSnapshotStore.sourceNode(context) != phoneNodeID) throw WearSchoolException(WearSchoolFailure.DISABLED)
        clearPending(context)
        val requestID = UUID.randomUUID().toString()
        val builder = KeyGenParameterSpec.Builder(REQUEST_PREFIX + requestID, KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(2048).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_RSA_OAEP)
            .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA1)
        if (Build.VERSION.SDK_INT >= 28) builder.setUnlockedDeviceRequired(true)
        val pair = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore").run { initialize(builder.build()); generateKeyPair() }
        val request = WearSchoolProvisionRequest(requestID = requestID, scope = access.scope, grantID = grant.grantID,
            createdAtEpochMillis = System.currentTimeMillis(), publicKey = Base64.getEncoder().encodeToString(pair.public.encoded))
        if (!preferences(context).edit().putString("request", json.encodeToString(WearSchoolProvisionRequest.serializer(), request))
                .putString("request-phone", phoneNodeID).putString("request-node", nodeID).commit()) {
            clearPending(context); throw WearSchoolException(WearSchoolFailure.INVALID_RESPONSE)
        }
        return request
    }

    @Synchronized
    fun accept(context: Context, phoneNodeID: String, response: WearSchoolProvisionResponse): Boolean {
        requireUnlocked(context)
        val prefs = preferences(context)
        val encoded = prefs.getString("request", null) ?: return false
        val request = runCatching { json.decodeFromString(WearSchoolProvisionRequest.serializer(), encoded) }.getOrNull() ?: return false
        val nodeID = prefs.getString("request-node", null) ?: return false
        if (prefs.getString("request-phone", null) != phoneNodeID || WearSnapshotStore.sourceNode(context) != phoneNodeID) return false
        val access = WearSnapshotStore.load(context)?.takeIf { it.isSignedIn }?.schoolAccess ?: return false
        val grant = WearSchoolProtocol.grant(access, nodeID, System.currentTimeMillis()) ?: return false
        if (request.scope != access.scope || request.grantID != grant.grantID || request.requestID != response.requestID) return false
        try {
            val privateKey = keys().getKey(REQUEST_PREFIX + request.requestID, null) as? java.security.PrivateKey ?: return false
            val credentials = WearSchoolProtocol.open(response, request, privateKey, grant, System.currentTimeMillis())
            save(context, WearSchoolStoredGrant(access.scope, grant.grantID, nodeID, phoneNodeID, response.expiresAtEpochMillis, credentials))
            return true
        } finally { clearPending(context) }
    }

    @Synchronized
    fun load(context: Context): WearSchoolStoredGrant? {
        reconcile(context, WearSnapshotStore.load(context), WearSnapshotStore.sourceNode(context))
        val prefs = preferences(context)
        val ciphertext = prefs.getString("ciphertext", null) ?: return null
        requireUnlocked(context)
        try {
            val nonce = Base64.getDecoder().decode(prefs.getString("nonce", null) ?: return null)
            val key = keys().getKey(AES_ALIAS, null) as? SecretKey ?: throw WearSchoolException(WearSchoolFailure.RECONNECT_REQUIRED)
            val data = Cipher.getInstance("AES/GCM/NoPadding").run {
                init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, nonce)); updateAAD("gradey-watch-school-storage-v1".toByteArray()); doFinal(Base64.getDecoder().decode(ciphertext))
            }
            val grant = try { json.decodeFromString(WearSchoolStoredGrant.serializer(), data.toString(Charsets.UTF_8)) } finally { data.fill(0) }
            if (!isCurrent(context, grant)) { clear(context); return null }
            return grant
        } catch (e: Exception) {
            if (context.getSystemService(KeyguardManager::class.java).isDeviceLocked) throw WearSchoolException(WearSchoolFailure.UNLOCK_REQUIRED)
            if (e is WearSchoolException && e.reason == WearSchoolFailure.UNLOCK_REQUIRED) throw e
            clear(context)
            throw WearSchoolException(WearSchoolFailure.RECONNECT_REQUIRED)
        }
    }

    @Synchronized
    private fun save(context: Context, grant: WearSchoolStoredGrant) {
        requireUnlocked(context)
        if (!isAuthorized(context, grant)) throw WearSchoolException(WearSchoolFailure.DISABLED)
        val keystore = keys()
        val key = (keystore.getKey(AES_ALIAS, null) as? SecretKey) ?: run {
            val builder = KeyGenParameterSpec.Builder(AES_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            if (Build.VERSION.SDK_INT >= 28) builder.setUnlockedDeviceRequired(true)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run { init(builder.build()); generateKey() }
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key); updateAAD("gradey-watch-school-storage-v1".toByteArray()) }
        val cleartext = json.encodeToString(WearSchoolStoredGrant.serializer(), grant).toByteArray(Charsets.UTF_8)
        val encrypted = try { cipher.doFinal(cleartext) } finally { cleartext.fill(0) }
        if (!preferences(context).edit().putString("ciphertext", Base64.getEncoder().encodeToString(encrypted))
                .putString("nonce", Base64.getEncoder().encodeToString(cipher.iv))
                .putString("scope", json.encodeToString(WearSchoolScope.serializer(), grant.scope)).putString("grant", grant.grantID)
                .putString("connection", grant.connectionID)
                .putString("node", grant.nodeID).putString("phone", grant.phoneNodeID).putLong("expiry", grant.expiresAtEpochMillis).commit()) {
            throw WearSchoolException(WearSchoolFailure.INVALID_RESPONSE)
        }
    }

    @Synchronized
    fun updateSession(context: Context, previous: WearSchoolStoredGrant, session: StoredSession) {
        val prefs = preferences(context)
        if (prefs.getString("ciphertext", null) == null || prefs.getString("grant", null) != previous.grantID ||
            prefs.getString("connection", null) != previous.connectionID ||
            prefs.getString("scope", null) != json.encodeToString(WearSchoolScope.serializer(), previous.scope)) {
            throw WearSchoolException(WearSchoolFailure.DISABLED)
        }
        save(context, previous.withSession(session))
    }

    fun isCurrent(context: Context, grant: WearSchoolStoredGrant): Boolean =
        preferences(context).getString("connection", null) == grant.connectionID && isAuthorized(context, grant)

    private fun isAuthorized(context: Context, grant: WearSchoolStoredGrant): Boolean {
        val snapshot = WearSnapshotStore.load(context)?.takeIf { it.isSignedIn } ?: return false
        val access = snapshot.schoolAccess ?: return false
        val authorized = WearSchoolProtocol.grant(access, grant.nodeID, System.currentTimeMillis()) ?: return false
        return access.scope == grant.scope && authorized.grantID == grant.grantID && grant.expiresAtEpochMillis > System.currentTimeMillis() &&
            grant.expiresAtEpochMillis <= authorized.expiresAtEpochMillis && WearSnapshotStore.sourceNode(context) == grant.phoneNodeID
    }

    @Synchronized
    fun reconcile(context: Context, snapshot: GradeyWearSyncPayload?, phoneNodeID: String?) {
        val prefs = preferences(context)
        val storedScope = prefs.getString("scope", null)
        val pending = prefs.getString("request", null)?.let { runCatching { json.decodeFromString(WearSchoolProvisionRequest.serializer(), it) }.getOrNull() }
        if (storedScope == null && pending == null) return
        val access = snapshot?.takeIf { it.isSignedIn }?.schoolAccess
        val nodeID = prefs.getString("node", null) ?: prefs.getString("request-node", null)
        val grant = nodeID?.let { WearSchoolProtocol.grant(access, it, System.currentTimeMillis()) }
        val scope = storedScope?.let { runCatching { json.decodeFromString(WearSchoolScope.serializer(), it) }.getOrNull() } ?: pending?.scope
        val expectedGrantID = prefs.getString("grant", null) ?: pending?.grantID
        val expectedPhone = prefs.getString("phone", null) ?: prefs.getString("request-phone", null)
        if (access?.scope != scope || grant?.grantID != expectedGrantID || phoneNodeID != expectedPhone ||
            !context.getSystemService(KeyguardManager::class.java).isDeviceSecure ||
            (storedScope != null && (prefs.getLong("expiry", 0) <= System.currentTimeMillis() || prefs.getLong("expiry", 0) > (grant?.expiresAtEpochMillis ?: 0)))) clear(context)
        else if (pending != null && !WearSchoolProtocol.valid(pending, System.currentTimeMillis())) clearPending(context)
    }

    @Synchronized
    fun clear(context: Context) {
        WearSchoolConnections.invalidate()
        preferences(context).edit().clear().commit()
        runCatching { val store = keys(); store.deleteEntry(AES_ALIAS); store.aliases().toList().filter { it.startsWith(REQUEST_PREFIX) }.forEach(store::deleteEntry) }
    }
    @Synchronized
    fun clearPending(context: Context) {
        preferences(context).edit().remove("request").remove("request-phone").remove("request-node").commit()
        runCatching { val store = keys(); store.aliases().toList().filter { it.startsWith(REQUEST_PREFIX) }.forEach(store::deleteEntry) }
    }
}
