package com.bukovinafilip.gradey

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.bukovinafilip.gradey.domain.EduPageURLNormalizer
import com.bukovinafilip.gradey.domain.SchoolURLNormalizer
import com.bukovinafilip.gradey.domain.WearSchoolProtocol
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.network.GradeyJson
import com.google.android.gms.wearable.Wearable
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*

/** Settings receives public metadata only. Credentials never enter a DataItem or this view state. */
data class WatchSchoolNode(val id: String, val displayName: String)
data class WatchSchoolAccessState(
    val scope: WearSchoolScope?,
    val nearbyNodes: List<WatchSchoolNode>,
    val grants: List<WearSchoolNodeGrant>,
    val available: Boolean,
    val nodeNames: Map<String, String> = emptyMap(),
)
interface WatchSchoolAccessApi {
    suspend fun load(): WatchSchoolAccessState
    suspend fun enable(nodeID: String, expectedScope: WearSchoolScope): WatchSchoolAccessState
    suspend fun revoke(nodeID: String, expectedScope: WearSchoolScope): WatchSchoolAccessState
    /** Invalidates in-flight requests synchronously, before any suspending cleanup. */
    fun revokeAll()
}

internal data class WearSchoolPhoneIdentity(
    val scope: WearSchoolScope,
    val provider: SchoolProvider,
    val baseURL: String,
    val expectedUserID: String,
    val selectedStudentID: String? = null,
    val source: Any,
)
internal data class WearSchoolSavedAccess(val access: WearSchoolAccess, val names: Map<String, String> = emptyMap())
internal interface WearSchoolAccessStorage {
    fun load(): WearSchoolSavedAccess?
    fun save(value: WearSchoolSavedAccess)
    fun invalidate()
}
internal interface WearSchoolAccessEnvironment {
    suspend fun identity(): WearSchoolPhoneIdentity?
    fun stillAllowed(identity: WearSchoolPhoneIdentity): Boolean
    suspend fun credentials(identity: WearSchoolPhoneIdentity): WearSchoolCredentials?
    suspend fun nearbyNodes(): List<WatchSchoolNode>
    /** Enqueue synchronously under the revocation lock; await the already-started transfer outside it. */
    fun send(nodeID: String, bytes: ByteArray, path: String = WearSchoolProtocol.RESPONSE_PATH): suspend () -> Unit
}

/** One process-wide instance serializes grants, expiry and final targeted-send admission. */
class WearSchoolAccessManager internal constructor(
    private val storage: WearSchoolAccessStorage,
    private val environment: WearSchoolAccessEnvironment,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val now: () -> Long = System::currentTimeMillis,
) : WatchSchoolAccessApi {
    private val lock = Any()
    private var generation = 0L
    private var disabledAfterStorageFailure = false
    private val recentRequests = LinkedHashMap<String, Long>()

    override suspend fun load(): WatchSchoolAccessState = withContext(ioDispatcher) {
        val epoch = synchronized(lock) { generation }
        val identity = environment.identity() ?: return@withContext unavailable()
        val nodes = environment.nearbyNodes().filter { validNode(it.id) }.distinctBy { it.id }.take(32)
        synchronized(lock) {
            if (epoch != generation || !environment.stillAllowed(identity)) return@synchronized unavailable()
            val saved = savedFor(identity.scope)
            WatchSchoolAccessState(identity.scope, nodes, saved?.access?.grants.orEmpty(), true, saved?.names.orEmpty())
        }
    }

    /** Absence of a grant is the default. A repeated enable deliberately creates a new seven-day lease. */
    override suspend fun enable(nodeID: String, expectedScope: WearSchoolScope): WatchSchoolAccessState {
        // Capture before dispatching: a queued enable cannot revive a grant revoked while waiting for IO.
        val epoch = synchronized(lock) { generation }
        return withContext(ioDispatcher) {
            require(validNode(nodeID)) { "Invalid watch connection." }
            val identity = environment.identity()
            check(identity != null && identity.scope == expectedScope) { "The school account changed. Open watch settings again." }
            val node = environment.nearbyNodes().singleOrNull { it.id == nodeID }
            check(node != null) { "Bring this watch near your phone and try again." }
            val finalIdentity = environment.identity()
            synchronized(lock) {
                check(epoch == generation && finalIdentity == identity && environment.stillAllowed(identity)) { "The school account changed. Open watch settings again." }
                val old = savedFor(expectedScope)
                val retained = old?.access?.grants.orEmpty().filterNot { it.nodeID == nodeID }
                check(retained.size < 8) { "Disconnect another watch before adding this one." }
                val grant = WearSchoolNodeGrant(nodeID, UUID.randomUUID().toString(), now() + WearSchoolProtocol.LEASE_MILLIS)
                val names = old?.names.orEmpty().filterKeys { id -> retained.any { it.nodeID == id } } + (nodeID to node.displayName.take(128))
                // Do not publish the grant unless its atomic encrypted write has finished.
                storage.save(WearSchoolSavedAccess(WearSchoolAccess(expectedScope, retained + grant), names))
                disabledAfterStorageFailure = false
                generation++
            }
            load()
        }
    }

    override suspend fun revoke(nodeID: String, expectedScope: WearSchoolScope): WatchSchoolAccessState {
        // Invalidate before the first suspension: a pending credential fetch cannot outlive this action.
        synchronized(lock) {
            generation++
            val old = savedFor(expectedScope)
            if (old != null) {
                val remaining = old.access.grants.filterNot { it.nodeID == nodeID }
                try {
                    // A durable revocation marker precedes replacement; a failed save revokes all watches.
                    storage.invalidate()
                    if (remaining.isNotEmpty()) storage.save(old.copy(access = old.access.copy(grants = remaining), names = old.names - nodeID))
                } catch (failure: Exception) {
                    disabledAfterStorageFailure = true
                    throw failure
                }
            }
        }
        return load()
    }

    override fun revokeAll() = synchronized(lock) {
        generation++
        disabledAfterStorageFailure = true
        recentRequests.clear()
        storage.invalidate()
        disabledAfterStorageFailure = false
    }

    /** Always derive the public snapshot from the same current identity used for provisioning. */
    suspend fun snapshot(): WearSchoolAccess? = withContext(ioDispatcher) {
        val epoch = synchronized(lock) { generation }
        val identity = environment.identity() ?: return@withContext null
        synchronized(lock) {
            if (epoch != generation || !environment.stillAllowed(identity)) null
            else savedFor(identity.scope)?.access ?: WearSchoolAccess(identity.scope, emptyList())
        }
    }

    /** Does not log, broadcast or persist credentials. Only an explicitly granted nearby node can receive them. */
    suspend fun handleProvision(sourceNodeID: String, bytes: ByteArray): Boolean = withContext(ioDispatcher) {
        if (!validNode(sourceNodeID) || bytes.size !in 1..WearSchoolProtocol.MAX_MESSAGE_BYTES) return@withContext false
        val epoch = synchronized(lock) { generation }
        try {
            val request = GradeyJson.decodeFromString(WearSchoolProvisionRequest.serializer(), bytes.toString(Charsets.UTF_8))
            if (!WearSchoolProtocol.valid(request, now())) return@withContext false
            if (!nearby(sourceNodeID)) return@withContext false
            val identity = environment.identity()
            if (identity == null) {
                reject(sourceNodeID, request, epoch, WearSchoolProvisionRejectionReason.SCHOOL_IDENTITY_UNAVAILABLE)
                return@withContext false
            }
            if (request.scope != identity.scope) return@withContext false
            val grant = synchronized(lock) { admittedGrant(identity, request, sourceNodeID, epoch) } ?: return@withContext false
            val credentials = environment.credentials(identity)
            if (credentials == null) {
                if (environment.stillAllowed(identity)) reject(sourceNodeID, request, epoch, WearSchoolProvisionRejectionReason.CREDENTIALS_UNAVAILABLE)
                return@withContext false
            }
            if (credentials.provider != identity.provider || credentials.baseURL != identity.baseURL ||
                credentials.expectedUserID != identity.expectedUserID || credentials.selectedStudentID != identity.selectedStudentID ||
                !WearSchoolProtocol.valid(credentials)) return@withContext false
            val issued = now()
            val response = WearSchoolProtocol.seal(request, credentials, minOf(grant.expiresAtEpochMillis, issued + WearSchoolProtocol.LEASE_MILLIS), issued)
            val encoded = GradeyJson.encodeToString(WearSchoolProvisionResponse.serializer(), response).toByteArray(Charsets.UTF_8)
            try {
                if (encoded.size > WearSchoolProtocol.MAX_MESSAGE_BYTES || !nearby(sourceNodeID) || environment.identity() != identity) return@withContext false
                val transfer = synchronized(lock) {
                    if (admittedGrant(identity, request, sourceNodeID, epoch) != grant) return@synchronized null
                    val timestamp = now()
                    recentRequests.entries.removeAll { it.value < timestamp - WearSchoolProtocol.REQUEST_LIFETIME_MILLIS }
                    val replayKey = "$sourceNodeID:${request.requestID}"
                    if (recentRequests.containsKey(replayKey) || recentRequests.size >= 128) return@synchronized null
                    recentRequests[replayKey] = timestamp
                    environment.send(sourceNodeID, encoded)
                } ?: return@withContext false
                transfer()
                true
            } finally { encoded.fill(0) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { false }
    }

    private suspend fun reject(node: String, request: WearSchoolProvisionRequest, epoch: Long, reason: WearSchoolProvisionRejectionReason) {
        // Echo only a valid challenge from the still-granted nearby source; no provider diagnostics.
        val response = WearSchoolProvisionRejection(requestID = request.requestID, scope = request.scope, grantID = request.grantID, reason = reason)
        val bytes = GradeyJson.encodeToString(WearSchoolProvisionRejection.serializer(), response).toByteArray(Charsets.UTF_8)
        val transfer = synchronized(lock) {
            if (epoch != generation || !WearSchoolProtocol.valid(request, now()) ||
                WearSchoolProtocol.grant(savedFor(request.scope)?.access, node, now())?.grantID != request.grantID) null
            else environment.send(node, bytes, WearSchoolProtocol.REJECTION_PATH)
        } ?: return
        transfer()
    }

    private suspend fun nearby(node: String) = environment.nearbyNodes().count { it.id == node } == 1
    private fun admittedGrant(identity: WearSchoolPhoneIdentity, request: WearSchoolProvisionRequest, node: String, epoch: Long): WearSchoolNodeGrant? {
        if (epoch != generation || !environment.stillAllowed(identity) || !WearSchoolProtocol.valid(request, now())) return null
        return WearSchoolProtocol.grant(savedFor(identity.scope)?.access, node, now())?.takeIf { it.grantID == request.grantID }
    }
    private fun savedFor(scope: WearSchoolScope): WearSchoolSavedAccess? {
        if (disabledAfterStorageFailure) return null
        val saved = try { storage.load() } catch (_: Exception) { return null } ?: return null
        if (saved.access.scope != scope || !WearSchoolProtocol.valid(scope) || saved.access.grants.size > 8 ||
            saved.access.grants.map { it.nodeID }.distinct().size != saved.access.grants.size) return null
        val active = saved.access.grants.filter { validNode(it.nodeID) && WearSchoolProtocol.grant(saved.access, it.nodeID, now()) != null }
        return saved.copy(access = saved.access.copy(grants = active), names = saved.names.filterKeys { id -> active.any { it.nodeID == id } })
    }
    private fun unavailable() = WatchSchoolAccessState(null, emptyList(), emptyList(), false)
    private fun validNode(value: String) = value.length in 1..256 && value.none { it.isISOControl() }

    companion object {
        @Volatile private var instance: WearSchoolAccessManager? = null
        fun get(context: Context): WearSchoolAccessManager = instance ?: synchronized(this) {
            instance ?: WearSchoolAccessManager(AndroidWearSchoolAccessStorage(context.applicationContext),
                AndroidWearSchoolAccessEnvironment(context.applicationContext)).also { instance = it }
        }
    }
}

internal fun wearSchoolCredentials(session: StoredSession, identity: WearSchoolPhoneIdentity): WearSchoolCredentials? {
    if (session.cacheScope != identity.scope.schoolScope || session.provider != identity.provider || normalizedWearSchoolURL(session.provider, session.baseURL) != identity.baseURL) return null
    if (session.provider == SchoolProvider.EDU_PAGE) {
        val selected = session.eduPage?.activeStudent?.id
        if (selected != identity.selectedStudentID || (selected ?: session.eduPage?.userID) != identity.expectedUserID) return null
    }
    val credentials = when (session.provider) {
        SchoolProvider.BAKALARI -> session.bakalari?.let { WearSchoolCredentials(session.provider, identity.baseURL, it.username, it.password, identity.expectedUserID) }
        SchoolProvider.EDU_PAGE -> session.eduPage?.let { WearSchoolCredentials(session.provider, identity.baseURL, it.username, it.password, identity.expectedUserID, identity.selectedStudentID) }
    }
    return credentials?.takeIf(WearSchoolProtocol::valid)
}

private fun normalizedWearSchoolURL(provider: SchoolProvider, url: String): String? = runCatching {
    if (provider == SchoolProvider.EDU_PAGE) EduPageURLNormalizer.normalizedBaseURL(url) else SchoolURLNormalizer.normalizedBaseURL(url)
}.getOrNull()

private class AndroidWearSchoolAccessEnvironment(private val context: Context) : WearSchoolAccessEnvironment {
    private val application get() = context as GradeyApplication
    private val mode get() = context.getSharedPreferences("gradey-mode", Context.MODE_PRIVATE)
    private val metadata get() = context.getSharedPreferences("gradey-watch-school-metadata", Context.MODE_PRIVATE)

    override suspend fun identity(): WearSchoolPhoneIdentity? {
        val graph = application.activeGraph
        if (graph.isDemo || mode.getBoolean("demo", false)) return null
        val guest = mode.getBoolean("guest", false)
        val owner = if (guest) {
            if (!AccountNotifications.acceptsOwner(context, "guest-local")) return null
            synchronized(guestLock) {
                metadata.getString("guestOwner", null) ?: UUID.randomUUID().toString().let {
                    check(metadata.edit().putString("guestOwner", it).commit()) { "Watch settings could not be saved." }; it
                }
            }.let { "guest-$it" }
        } else graph.gradeyAuthRepository.cachedSession()?.account?.id?.takeIf { AccountNotifications.acceptsOwner(context, it) } ?: return null
        val session = graph.schoolRepository.currentStoredSession() ?: return null
        val scope = session.cacheScope
        val userID = graph.schoolRepository.loadCachedDashboard()?.user?.userUID?.takeIf { it.isNotBlank() }
        val student = session.eduPage?.activeStudent?.id?.takeIf { it.isNotBlank() }
        val canonical = if (session.provider == SchoolProvider.EDU_PAGE) student ?: userID else userID
        if (canonical.isNullOrBlank() || canonical.length > 256 || (student != null && userID != null && student != userID)) return null
        val baseURL = normalized(session) ?: return null
        if (guest) {
            if (!mode.getBoolean("guest", false)) return null
        } else {
            val account = graph.linkedAccountRepository.cachedAccounts().singleOrNull { it.id == session.linkedAccountID } ?: return null
            if (account.provider != session.provider || account.providerUserID != canonical || account.status != "active") return null
            if (account.baseURL != null && normalizedWearSchoolURL(account.provider, account.baseURL!!) != baseURL) return null
            if (graph.gradeyAuthRepository.cachedSession()?.account?.id != owner) return null
        }
        val identity = WearSchoolPhoneIdentity(WearSchoolScope(owner, scope), session.provider, baseURL, canonical, student, graph)
        if (!WearSchoolProtocol.valid(identity.scope) || !stillAllowed(identity) || graph.schoolRepository.currentStoredSession()?.cacheScope != scope) return null
        // A remote token-only activation cannot provision a separate watch login.
        if (wearSchoolCredentials(session, identity) == null) return null
        return identity
    }
    override fun stillAllowed(identity: WearSchoolPhoneIdentity): Boolean = application.activeGraph === identity.source &&
        !application.activeGraph.isDemo && if (identity.scope.ownerID.startsWith("guest-")) {
            mode.getBoolean("guest", false) && identity.scope.ownerID == "guest-${metadata.getString("guestOwner", null)}" && AccountNotifications.acceptsOwner(context, "guest-local")
        } else !mode.getBoolean("guest", false) && AccountNotifications.acceptsOwner(context, identity.scope.ownerID)

    override suspend fun credentials(identity: WearSchoolPhoneIdentity): WearSchoolCredentials? {
        if (!stillAllowed(identity)) return null
        val session = application.activeGraph.schoolRepository.currentStoredSession() ?: return null
        return wearSchoolCredentials(session, identity)
    }
    override suspend fun nearbyNodes(): List<WatchSchoolNode> = Wearable.getNodeClient(context).connectedNodes.await()
        .filter { it.isNearby }.map { WatchSchoolNode(it.id, it.displayName) }
    override fun send(nodeID: String, bytes: ByteArray, path: String): suspend () -> Unit {
        val task = Wearable.getMessageClient(context).sendMessage(nodeID, path, bytes)
        return { task.await(); Unit }
    }
    private fun normalized(session: StoredSession) = normalizedWearSchoolURL(session.provider, session.baseURL)
    companion object { private val guestLock = Any() }
}

/** The encrypted grant file is device-bound and excluded from backup. A separate committed epoch is
 * changed before revocation, so an interrupted replacement cannot resurrect a prior grant file. */
private class AndroidWearSchoolAccessStorage(context: Context) : WearSchoolAccessStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "watch-school-access.enc"))
    private val metadata = context.getSharedPreferences("gradey-watch-school-metadata", Context.MODE_PRIVATE)
    private val alias = "gradey-watch-school-access-v1"
    override fun load(): WearSchoolSavedAccess? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val bytes = file.readFully()
        require(bytes.size in 29..32_768 && bytes[0] == 1.toByte())
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(1, 13)))
            updateAAD(epoch().toByteArray(Charsets.UTF_8))
        }
        val plaintext = cipher.doFinal(bytes.copyOfRange(13, bytes.size))
        try {
            val json = GradeyJson.parseToJsonElement(plaintext.toString(Charsets.UTF_8)).jsonObject
            val access = GradeyJson.decodeFromJsonElement(WearSchoolAccess.serializer(), json.getValue("access"))
            val names = json["names"]?.jsonObject.orEmpty().mapValues { it.value.jsonPrimitive.content.take(128) }
            return WearSchoolSavedAccess(access, names)
        } finally { plaintext.fill(0) }
    }
    override fun save(value: WearSchoolSavedAccess) {
        val json = buildJsonObject {
            put("access", GradeyJson.encodeToJsonElement(WearSchoolAccess.serializer(), value.access))
            put("names", buildJsonObject { value.names.forEach { (id, name) -> put(id, name.take(128)) } })
        }.toString().toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(epoch().toByteArray(Charsets.UTF_8)) }
        val ciphertext = try { cipher.doFinal(json) } finally { json.fill(0) }
        val output = file.startWrite()
        try {
            output.write(byteArrayOf(1) + cipher.iv + ciphertext)
            output.fd.sync()
            file.finishWrite(output)
        } catch (failure: Exception) { file.failWrite(output); throw failure }
    }
    override fun invalidate() {
        val epochSaved = runCatching { metadata.edit().putString("epoch", UUID.randomUUID().toString()).commit() }.getOrDefault(false)
        try { file.delete() } finally {
            if (!epochSaved) {
                // If preferences cannot commit (for example a full disk), destroying the device key
                // also makes any surviving old file unreadable after a process restart.
                val keys = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
                keys.deleteEntry(alias)
                check(!keys.containsAlias(alias)) { "Watch access could not be revoked. Try again." }
            }
        }
    }
    private fun epoch() = metadata.getString("epoch", null) ?: "initial-default-off"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
