package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.domain.GradeyHistoryRepository
import com.bukovinafilip.gradey.domain.LinkedAccountRepository
import com.bukovinafilip.gradey.domain.isEduPageParentSession
import com.bukovinafilip.gradey.model.LinkedSchoolAccount
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.network.SupabaseAccountClient
import kotlinx.serialization.builtins.ListSerializer

class SupabaseLinkedAccountRepository(
    private val client: SupabaseAccountClient,
    private val authRepository: GradeyAuthRepository,
    private val store: SecureJsonStore,
    private val sessionStore: SchoolSessionStore,
    private val cache: RoomGradeyCache,
) : LinkedAccountRepository {
    private val serializer = ListSerializer(LinkedSchoolAccount.serializer())

    override suspend fun cachedAccounts(): List<LinkedSchoolAccount> {
        val auth = authRepository.cachedSession() ?: return emptyList()
        return store.load(key(auth.account.id), serializer).orEmpty()
    }

    override suspend fun localAccounts(): List<LinkedSchoolAccount> {
        val auth = authRepository.bootstrapSession() ?: return emptyList()
        // Fetch failures are surfaced to the caller so stale state is never reported as a successful refresh.
        return client.linkedAccounts(auth).also { store.save(key(auth.account.id), it, serializer) }
    }

    override suspend fun linkSchoolAccount(session: StoredSession, displayName: String): LinkedSchoolAccount {
        val auth = authRepository.bootstrapSession() ?: error("Sign in to Gradey to link a school account.")
        val account = client.linkSchoolAccount(auth, session, displayName)
        sessionStore.save(session.copy(
            linkedAccountID = account.id,
            linkedAccountDisplayName = account.displayName,
            linkedAccountSchoolName = account.schoolName,
        ))
        val accounts = store.load(key(auth.account.id), serializer).orEmpty().filterNot { it.id == account.id } + account
        store.save(key(auth.account.id), accounts, serializer)
        return account
    }

    override suspend fun storedSession(accountID: String): StoredSession? {
        val auth = authRepository.bootstrapSession() ?: return null
        val owned = store.load(key(auth.account.id), serializer).orEmpty().any { it.id == accountID }
        return if (owned) sessionStore.loadLinked(accountID) else null
    }

    override suspend fun activateAccount(accountID: String): StoredSession? {
        val auth = authRepository.bootstrapSession() ?: error("Sign in to Gradey to switch accounts.")
        // Activation checks server ownership and updates the cloud's active account as well.
        val cloud = client.activateSchoolAccount(auth, accountID)
        val activated = schoolSessionForActivation(cloud, sessionStore.loadLinked(accountID))
        sessionStore.save(activated)
        return activated
    }

    override suspend fun unlinkAccount(accountID: String) {
        val auth = authRepository.bootstrapSession() ?: error("Sign in to Gradey to remove an account.")
        client.unlinkAccount(auth, accountID)
        sessionStore.loadLinked(accountID)?.let { cache.clearSchool(it.cacheScope) }
        sessionStore.removeLinked(accountID)
        val remaining = store.load(key(auth.account.id), serializer).orEmpty().filterNot { it.id == accountID }
        store.save(key(auth.account.id), remaining, serializer)
    }

    override suspend fun clearLocalAccounts() {
        // Clear all local owners at sign-out, even when the cloud session has already expired.
        try { store.clearPrefix("linked.accounts.") }
        finally { sessionStore.clearLinked() }
    }

    private fun key(ownerID: String) = "linked.accounts.$ownerID"
}

internal fun schoolSessionForActivation(cloud: StoredSession, saved: StoredSession?): StoredSession {
    val local = saved?.takeIf {
        it.provider == cloud.provider && it.baseURL.trimEnd('/') == cloud.baseURL.trimEnd('/') &&
            it.linkedAccountID == cloud.linkedAccountID &&
            it.bakalari?.username == cloud.bakalari?.username &&
            it.eduPage?.username == cloud.eduPage?.username && it.eduPage?.userID == cloud.eduPage?.userID &&
            it.eduPage?.activeStudent?.id == cloud.eduPage?.activeStudent?.id
    }
    if (cloud.isEduPageParentSession()) {
        check(local != null) { "For EduPage parent accounts, sign in to the school on this device and select the student. A shared cloud session cannot safely switch students." }
        return local.copy(linkedAccountDisplayName = cloud.linkedAccountDisplayName, linkedAccountSchoolName = cloud.linkedAccountSchoolName)
    }
    return if (local != null && (local.provider == com.bukovinafilip.gradey.model.SchoolProvider.EDU_PAGE || local.expiresAtEpochMillis > cloud.expiresAtEpochMillis)) {
        local.copy(linkedAccountDisplayName = cloud.linkedAccountDisplayName, linkedAccountSchoolName = cloud.linkedAccountSchoolName)
    } else cloud
}

class SupabaseHistoryRepository(
    private val client: SupabaseAccountClient,
    private val authRepository: GradeyAuthRepository,
) : GradeyHistoryRepository {
    override suspend fun gradeHistory(accountID: String?) =
        authRepository.bootstrapSession()?.let { client.gradeHistory(it, accountID) }.orEmpty()
}
