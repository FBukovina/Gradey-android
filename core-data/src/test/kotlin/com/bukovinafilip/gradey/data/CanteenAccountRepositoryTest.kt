package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.*
import com.bukovinafilip.gradey.model.*
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class CanteenAccountRepositoryTest {
    private class Store : JsonStore {
        val content = mutableMapOf<String, String>()
        override fun <T> load(key: String, serializer: KSerializer<T>): T? = content[key]?.let { Json.decodeFromString(serializer, it) }
        override fun <T> save(key: String, value: T?, serializer: KSerializer<T>) { if (value == null) content.remove(key) else content[key] = Json.encodeToString(serializer, value) }
        override fun clear(key: String) { content.remove(key) }
        override fun clearPrefix(prefix: String) { content.keys.filter { it.startsWith(prefix) }.forEach(content::remove) }
    }
    private class Auth : GradeyAuthRepository {
        var session: GradeyAuthSession? = GradeyAuthSession("token", account = GradeyAccount("owner"))
        override suspend fun bootstrapSession() = session
        override suspend fun cachedSession() = session
        override suspend fun refreshAccount() = session!!.account
        override suspend fun updateFullName(fullName: String) = session!!.account
        override suspend fun signInWithGoogle(idToken: String, accessToken: String?, fullName: String?) = session!!
        override suspend fun signOut() { session = null }
    }
    private class Client : CanteenAccountClient {
        var rows = emptyList<LinkedCanteenAccount>()
        var calls = 0
        var afterLink: (() -> Unit)? = null
        override suspend fun accounts(auth: GradeyAuthSession) = rows
        override suspend fun link(auth: GradeyAuthSession, session: StravaCZStoredSession): LinkedCanteenAccount {
            calls++
            return LinkedCanteenAccount("id-$calls", session.username, session.canteenName, session.username).also { rows = rows + it; afterLink?.invoke() }
        }
        override suspend fun unlink(auth: GradeyAuthSession, accountID: String) { rows = rows.filterNot { it.id == accountID } }
    }
    private val school = StravaCZStoredSession("private-session-token", "1234", "student", serviceURL = "https://app.strava.cz/", canteenName = "School")

    @Test fun provenLocalLinkIsReusedWithoutDuplicatingHostedInsert() = runTest {
        val client = Client(); val auth = Auth(); val store = Store()
        val repo = SupabaseCanteenAccountRepository(client, auth, store)
        val first = repo.linkCurrent(school)
        assertEquals(first, repo.linkCurrent(school.copy(sessionID = "rotated-token")))
        assertEquals(1, client.calls)
        assertEquals(first.id, repo.linkedAccountID(school))
        assertFalse(store.content.values.any { it.contains("private-session-token") || it.contains("rotated-token") })
    }
    @Test fun existingUnknownLinkRequiresReviewInsteadOfGuessingIdentityOrDuplicating() = runTest {
        val client = Client().apply { rows = listOf(LinkedCanteenAccount("other-device", "Name", "School", "student")) }
        val repo = SupabaseCanteenAccountRepository(client, Auth(), Store())
        assertTrue(runCatching { repo.linkCurrent(school) }.exceptionOrNull() is AccountFailureException)
        assertEquals(0, client.calls)
        assertEquals("other-device", repo.cachedAccounts().single().id)
        assertNull(repo.linkedAccountID(school))
    }
    @Test fun ownerCachesAreIsolatedAndUnlinkForgetsDeviceBinding() = runTest {
        val client = Client(); val auth = Auth(); val store = Store(); val repo = SupabaseCanteenAccountRepository(client, auth, store)
        val link = repo.linkCurrent(school)
        val original = auth.session
        auth.session = original!!.copy(account = GradeyAccount("another-owner"))
        assertTrue(repo.cachedAccounts().isEmpty()); assertNull(repo.linkedAccountID(school))
        auth.session = original
        repo.unlinkAccount(link.id)
        assertTrue(repo.cachedAccounts().isEmpty()); assertNull(repo.linkedAccountID(school))
    }
    @Test fun signoutDuringCloudLinkCannotRecreateLocalCache() = runTest {
        val auth = Auth(); val client = Client().apply { afterLink = { auth.session = null } }; val store = Store()
        val repo = SupabaseCanteenAccountRepository(client, auth, store)
        assertTrue(runCatching { repo.linkCurrent(school) }.exceptionOrNull() is GradeySessionExpiredException)
        assertTrue(store.content.isEmpty())
        assertTrue(repo.cachedAccounts().isEmpty())
    }
}
