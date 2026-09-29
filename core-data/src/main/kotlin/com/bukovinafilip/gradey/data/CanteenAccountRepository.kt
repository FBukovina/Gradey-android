package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.*
import com.bukovinafilip.gradey.model.*
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class SupabaseCanteenAccountRepository(
    private val client: CanteenAccountClient,
    private val auth: GradeyAuthRepository,
    private val store: JsonStore,
) : CanteenAccountRepository {
    private val mutex = Mutex()
    private val generation = AtomicLong()
    override suspend fun cachedAccounts(): List<LinkedCanteenAccount> = mutex.withLock {
        auth.cachedSession()?.let { load(it.account.id).accounts }.orEmpty()
    }
    override suspend fun linkedAccountID(session: StravaCZStoredSession): String? = mutex.withLock {
        auth.cachedSession()?.let { load(it.account.id).linkedIdentities[identity(session)] }
    }
    override suspend fun refreshAccounts(): List<LinkedCanteenAccount> = withOwner { session, epoch ->
        val rows = client.accounts(session)
        save(session, epoch, load(session.account.id).copy(accounts = rows, linkedIdentities = load(session.account.id).linkedIdentities.filterValues { id -> rows.any { it.id == id } }))
        rows
    }
    override suspend fun linkCurrent(session: StravaCZStoredSession): LinkedCanteenAccount = withOwner { owner, epoch ->
        val fresh = client.accounts(owner)
        val cached = load(owner.account.id)
        val identity = identity(session)
        val known = cached.linkedIdentities[identity]?.let { id -> fresh.firstOrNull { it.id == id } }
        if (known != null) {
            save(owner, epoch, cached.copy(accounts = fresh))
            return@withOwner known
        }
        // The hosted function inserts rather than upserts. Never silently create a duplicate
        // after an interrupted request or guess whether a similarly named row is this canteen.
        if (fresh.any { it.providerUserID == session.username && it.canteenName == session.canteenName }) {
            save(owner, epoch, cached.copy(accounts = fresh))
            throw AccountFailureException(AccountFailureReason.REQUEST_REJECTED)
        }
        val linked = client.link(owner, session)
        save(owner, epoch, CanteenAccountCache(fresh + linked, cached.linkedIdentities + (identity to linked.id)))
        linked
    }
    override suspend fun unlinkAccount(accountID: String) = withOwner { owner, epoch ->
        val current = load(owner.account.id)
        if (current.accounts.none { it.id == accountID }) throw AccountFailureException(AccountFailureReason.ACCOUNT_NOT_FOUND)
        client.unlink(owner, accountID)
        save(owner, epoch, current.copy(accounts = current.accounts.filterNot { it.id == accountID }, linkedIdentities = current.linkedIdentities.filterValues { it != accountID }))
    }
    override suspend fun clearLocalAccounts() {
        generation.incrementAndGet()
        mutex.withLock { store.clearPrefix("canteen.accounts.") }
    }
    private suspend fun <T> withOwner(block: suspend (GradeyAuthSession, Long) -> T): T = mutex.withLock {
        val epoch = generation.get()
        val owner = auth.validSession()
        block(owner, epoch)
    }
    private suspend fun save(owner: GradeyAuthSession, epoch: Long, cache: CanteenAccountCache) {
        if (generation.get() != epoch || auth.cachedSession()?.account?.id != owner.account.id) throw GradeySessionExpiredException()
        store.save("canteen.accounts.${owner.account.id}", cache, CanteenAccountCache.serializer())
    }
    private fun load(owner: String) = store.load("canteen.accounts.$owner", CanteenAccountCache.serializer()) ?: CanteenAccountCache()
    private fun identity(session: StravaCZStoredSession): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(session.serviceURL.trimEnd('/'), session.canteenNumber, session.username).joinToString("\u0000").toByteArray())
        .joinToString("") { "%02x".format(it) }
}

class MockCanteenAccountRepository : CanteenAccountRepository {
    override suspend fun cachedAccounts() = emptyList<LinkedCanteenAccount>()
    override suspend fun refreshAccounts() = emptyList<LinkedCanteenAccount>()
    override suspend fun linkedAccountID(session: StravaCZStoredSession): String? = null
    override suspend fun linkCurrent(session: StravaCZStoredSession): LinkedCanteenAccount = throw AccountFailureException(AccountFailureReason.NOT_CONFIGURED)
    override suspend fun unlinkAccount(accountID: String) {}
    override suspend fun clearLocalAccounts() {}
}
