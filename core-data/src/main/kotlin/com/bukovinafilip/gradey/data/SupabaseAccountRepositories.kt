package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.AccountFailureException
import com.bukovinafilip.gradey.domain.AccountFailureReason
import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.domain.GradeyHistoryRepository
import com.bukovinafilip.gradey.domain.LinkedAccountRepository
import com.bukovinafilip.gradey.domain.isEduPageParentSession
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.SchoolProvider
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.network.SupabaseAccountClient
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.bukovinafilip.gradey.model.GradeyAuthSession
import com.bukovinafilip.gradey.model.UserResponse
import com.bukovinafilip.gradey.domain.GradeySessionExpiredException

class SupabaseLinkedAccountRepository(
    private val client: SupabaseAccountClient,
    private val authRepository: GradeyAuthRepository,
    private val store: JsonStore,
    private val sessionStore: SchoolSessionStore,
    private val cache: RoomGradeyCache,
) : LinkedAccountRepository {
    private val serializer = ListSerializer(LinkedSchoolAccount.serializer())
    private val operationMutex = Mutex()
    private val cacheMutex = Mutex()
    private var generation = 0L

    override suspend fun cachedAccounts(): List<LinkedSchoolAccount> = cacheMutex.withLock {
        val auth = authRepository.cachedSession() ?: return@withLock emptyList()
        store.load(key(auth.account.id), serializer).orEmpty()
    }

    override suspend fun localAccounts(): List<LinkedSchoolAccount> = withOwner(
        request = { client.linkedAccounts(it) },
        commit = { owner, accounts -> store.save(key(owner), accounts, serializer) },
    )

    override suspend fun linkSchoolAccount(session: StoredSession, displayName: String, user: UserResponse?): LinkedSchoolAccount = withOwner(
        request = { client.linkSchoolAccount(it, session, displayName, user) },
        commit = { owner, account -> saveLinkedSession(owner, account, session) },
    )

    override suspend fun reconnectSchoolAccount(accountID: String, session: StoredSession, displayName: String, user: UserResponse?): LinkedSchoolAccount = withOwner(
        request = { auth ->
            val previous = store.load(key(auth.account.id), serializer).orEmpty().firstOrNull { it.id == accountID }
                ?: throw AccountFailureException(AccountFailureReason.ACCOUNT_NOT_FOUND)
            if (previous.provider != session.provider) throw AccountFailureException(AccountFailureReason.SCHOOL_PROVIDER_MISMATCH)
            val candidateID = session.eduPage?.activeStudent?.id ?: user?.userUID ?: session.eduPage?.userID ?: session.bakalari?.username
            if (previous.providerUserID.isNullOrBlank() || previous.providerUserID != candidateID)
                throw AccountFailureException(AccountFailureReason.SCHOOL_IDENTITY_MISMATCH)
            client.reconnectSchoolAccount(auth, accountID, session, displayName, user).also {
                if (it.id != accountID) throw AccountFailureException(AccountFailureReason.ACCOUNT_IDENTITY_MISMATCH)
            }
        },
        commit = { owner, account -> saveLinkedSession(owner, account, session) },
    )

    override suspend fun updateNotificationsEnabled(accountID: String, enabled: Boolean): LinkedSchoolAccount = withOwner(
        request = { auth -> client.updateNotificationsEnabled(auth, accountID, enabled).also {
            if (it.id != accountID) throw AccountFailureException(AccountFailureReason.ACCOUNT_IDENTITY_MISMATCH)
        } },
        commit = { owner, account -> upsert(owner, account) },
    )

    override suspend fun storedSession(accountID: String): StoredSession? = cacheMutex.withLock {
        val auth = authRepository.cachedSession() ?: return@withLock null
        val owned = store.load(key(auth.account.id), serializer).orEmpty().any { it.id == accountID }
        if (owned) sessionStore.loadLinked(accountID) else null
    }

    override suspend fun activateAccount(accountID: String): StoredSession? = withOwner(
        request = { auth ->
            val cloud = client.activateSchoolAccount(auth, accountID)
            try { schoolSessionForActivation(cloud, sessionStore.loadLinked(accountID)) }
            catch (_: DeviceSchoolSignInRequiredException) { null }
        },
        commit = { _, activated -> activated?.let(sessionStore::save) },
    )

    override suspend fun unlinkAccount(accountID: String) = withOwner(
        request = { client.unlinkAccount(it, accountID) },
        commit = { owner, _ ->
            sessionStore.loadLinked(accountID)?.let { cache.clearSchool(it.cacheScope) }
            sessionStore.removeLinked(accountID)
            val remaining = store.load(key(owner), serializer).orEmpty().filterNot { it.id == accountID }
            store.save(key(owner), remaining, serializer)
        },
    )

    override suspend fun clearLocalAccounts() = cacheMutex.withLock {
        // Invalidate in-flight responses before clearing, including when auth has already expired.
        generation++
        try { store.clearPrefix("linked.accounts.") }
        finally { sessionStore.clearLinked() }
    }

    private fun saveLinkedSession(owner: String, account: LinkedSchoolAccount, session: StoredSession) {
        sessionStore.save(session.copy(
            linkedAccountID = account.id,
            linkedAccountDisplayName = account.displayName,
            linkedAccountSchoolName = account.schoolName,
        ))
        upsert(owner, account.copy(baseURL = account.baseURL ?: session.baseURL))
    }

    private fun upsert(owner: String, account: LinkedSchoolAccount) {
        val accounts = store.load(key(owner), serializer).orEmpty().filterNot { it.id == account.id } + account
        store.save(key(owner), accounts, serializer)
    }

    private suspend fun <T> withOwner(request: suspend (GradeyAuthSession) -> T, commit: suspend (String, T) -> Unit): T = operationMutex.withLock {
        val auth = authRepository.validSession()
        val expectedGeneration = cacheMutex.withLock { generation }
        val result = request(auth)
        cacheMutex.withLock {
            if (generation != expectedGeneration || authRepository.cachedSession()?.account?.id != auth.account.id) {
                throw GradeySessionExpiredException()
            }
            commit(auth.account.id, result)
        }
        result
    }

    private fun key(ownerID: String) = "linked.accounts.$ownerID"
}

internal class DeviceSchoolSignInRequiredException(message: String) : IllegalStateException(message)

internal fun schoolSessionForActivation(cloud: StoredSession, saved: StoredSession?): StoredSession {
    val local = saved?.takeIf {
        it.provider == cloud.provider && it.baseURL.trimEnd('/') == cloud.baseURL.trimEnd('/') &&
            !cloud.linkedAccountID.isNullOrBlank() && it.linkedAccountID == cloud.linkedAccountID &&
            (cloud.bakalari == null || it.bakalari?.username == cloud.bakalari?.username) &&
            it.eduPage?.username == cloud.eduPage?.username && it.eduPage?.userID == cloud.eduPage?.userID &&
            it.eduPage?.activeStudent?.id == cloud.eduPage?.activeStudent?.id
    }
    if (cloud.provider == SchoolProvider.BAKALARI && cloud.refreshToken.isBlank()) {
        // Current servers deliberately withhold the cloud poller's refresh token.
        if (local == null || (local.refreshToken.isBlank() && local.bakalari?.password?.isNotEmpty() != true)) {
            throw DeviceSchoolSignInRequiredException("Sign in to Bakaláři on this device to reconnect this school account.")
        }
        return local.copy(linkedAccountDisplayName = cloud.linkedAccountDisplayName, linkedAccountSchoolName = cloud.linkedAccountSchoolName)
    }
    if (cloud.isEduPageParentSession()) {
        if (local == null) throw DeviceSchoolSignInRequiredException("For EduPage parent accounts, sign in to the school on this device and select the student. A shared cloud session cannot safely switch students.")
        return local.copy(linkedAccountDisplayName = cloud.linkedAccountDisplayName, linkedAccountSchoolName = cloud.linkedAccountSchoolName)
    }
    return if (local != null && (local.provider == SchoolProvider.EDU_PAGE || local.expiresAtEpochMillis > cloud.expiresAtEpochMillis)) {
        local.copy(linkedAccountDisplayName = cloud.linkedAccountDisplayName, linkedAccountSchoolName = cloud.linkedAccountSchoolName)
    } else if (local != null && cloud.provider == SchoolProvider.BAKALARI) {
        // The cloud deliberately omits school passwords. Keep this account's device-only
        // credentials even when its cloud tokens are newer than the saved tokens.
        cloud.copy(bakalari = local.bakalari ?: cloud.bakalari)
    } else cloud
}

class SupabaseHistoryRepository(
    private val client: SupabaseAccountClient,
    private val authRepository: GradeyAuthRepository,
) : GradeyHistoryRepository {
    override suspend fun gradeHistory(accountID: String?) =
        authRepository.bootstrapSession()?.let { client.gradeHistory(it, accountID) }.orEmpty()
}
