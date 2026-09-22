package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.BakalariClient
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.network.GradeyApiException
import com.bukovinafilip.gradey.network.GradeyJson
import com.bukovinafilip.gradey.network.OkHttpEduPageClient
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AndroidSchoolRepositoryTest {
    private fun session(username: String) = StoredSession(
        "token-$username", "refresh-$username", "Bearer", Long.MAX_VALUE,
        "https://school.example/", bakalari = BakalariCredentials(username, "password-$username"),
    )

    @Test fun failedForcedRefreshPreservesLastSuccessfulOfflineDashboard() = runBlocking {
        val store = SchoolSessionStore(MemoryJsonStore())
        store.save(session("first"))
        val cache = RoomGradeyCache(MemoryCacheDao(), GradeyJson)
        val dashboard = DashboardData(MarksResponse(), user = UserResponse("First student"))
        cache.saveDashboard(store.load()!!.cacheScope, dashboard)
        val client = FakeBakalariClient().apply { marks = { throw IOException("offline") } }
        val repository = AndroidSchoolRepository(client, OkHttpEduPageClient(), store, cache)
        try { repository.loadDashboard(true); fail("Expected offline failure") } catch (_: IOException) { }
        assertEquals(dashboard, repository.loadCachedDashboard())
        assertEquals(0, client.refreshCount)
    }

    @Test fun unauthorizedRetryNeverRefreshesOrReactivatesAnAccountSelectedLater() = runBlocking {
        val store = SchoolSessionStore(MemoryJsonStore())
        val original = session("first")
        val second = session("second")
        store.save(original)
        val client = FakeBakalariClient()
        var firstRequest = true
        client.marks = { token ->
            if (firstRequest) {
                firstRequest = false
                store.save(second)
                throw GradeyApiException(401, "expired")
            }
            assertEquals("renewed-refresh-first", token)
            MarksResponse()
        }
        val repository = AndroidSchoolRepository(client, OkHttpEduPageClient(), store, RoomGradeyCache(MemoryCacheDao(), GradeyJson))
        repository.loadDashboard()
        assertEquals(listOf("refresh-first"), client.refreshedTokens)
        assertEquals("token-second", store.load()?.accessToken)
        assertNull(repository.loadCachedDashboard())
    }

    @Test fun eachLinkedAccountRetainsItsOwnRefreshedSession() {
        val store = SchoolSessionStore(MemoryJsonStore())
        val first = session("first").copy(linkedAccountID = "linked-first")
        val second = session("second").copy(linkedAccountID = "linked-second")
        store.save(first)
        store.save(second)
        store.updateExisting(first.copy(accessToken = "new-first"))
        assertEquals("token-second", store.load()?.accessToken)
        assertEquals("new-first", store.loadLinked("linked-first")?.accessToken)
        store.clearLinked()
        assertNull(store.loadLinked("linked-first"))
        assertNull(store.loadLinked("linked-second"))
    }

    private class FakeBakalariClient : BakalariClient {
        var marks: (String) -> MarksResponse = { MarksResponse() }
        var refreshCount = 0
        val refreshedTokens = mutableListOf<String>()
        override suspend fun login(baseURL: String, username: String, password: String) = error("Unexpected password login")
        override suspend fun refreshToken(baseURL: String, refreshToken: String): LoginResponse {
            refreshCount++
            refreshedTokens += refreshToken
            return LoginResponse("renewed-$refreshToken", "renewed-refresh", "Bearer", 3600)
        }
        override suspend fun fetchMarks(baseURL: String, accessToken: String) = marks(accessToken)
        override suspend fun fetchAbsences(baseURL: String, accessToken: String) = AbsenceResponse()
        override suspend fun fetchUser(baseURL: String, accessToken: String) = UserResponse("Student")
        override suspend fun fetchTimetable(baseURL: String, accessToken: String, date: String) = TimetableResponse()
        override suspend fun predictSubject(baseURL: String, accessToken: String, subject: Subject, markText: String, weight: Int) = subject
    }

    private class MemoryJsonStore : JsonStore {
        private val entries = mutableMapOf<String, String>()
        override fun <T> load(key: String, serializer: KSerializer<T>): T? = entries[key]?.let { GradeyJson.decodeFromString(serializer, it) }
        override fun <T> save(key: String, value: T?, serializer: KSerializer<T>) {
            if (value == null) entries.remove(key) else entries[key] = GradeyJson.encodeToString(serializer, value)
        }
        override fun clear(key: String) { entries.remove(key) }
        override fun clearPrefix(prefix: String) { entries.keys.filter { it.startsWith(prefix) }.forEach { entries.remove(it) } }
    }

    private class MemoryCacheDao : CacheEntryDao {
        private val entries = mutableMapOf<String, CacheEntryEntity>()
        override suspend fun load(key: String) = entries[key]
        override suspend fun save(entity: CacheEntryEntity) { entries[entity.key] = entity }
        override suspend fun clear(key: String) { entries.remove(key) }
        override suspend fun clearPrefix(prefix: String) { entries.keys.filter { it.startsWith(prefix) }.forEach { entries.remove(it) } }
        override suspend fun clearAll() { entries.clear() }
    }
}
