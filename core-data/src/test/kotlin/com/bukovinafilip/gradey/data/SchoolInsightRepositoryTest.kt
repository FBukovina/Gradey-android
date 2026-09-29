package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.SchoolInsightStore
import com.bukovinafilip.gradey.domain.SchoolInsightStorage
import com.bukovinafilip.gradey.domain.SchoolInsightState
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.model.BakalariCredentials
import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.lang.reflect.Proxy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import org.junit.Test

class SchoolInsightRepositoryTest {
    private fun dashboard(value: String) = DashboardData(MarksResponse(listOf(Subject(subjectInfo = SubjectInfo("math", "M", "Mathematics"),
        marks = listOf(Mark(id = "grade", subjectID = "math", markText = value, weight = 1.0))))))
    private fun session(username: String) = StoredSession("secret", "refresh", "Bearer", Long.MAX_VALUE, "https://school.example", bakalari = BakalariCredentials(username, "password"))

    @Test fun refreshFailureAndCachedFallbackDoNotInventObservationsOrAdvanceFreshness() = runBlocking {
        var time = 1_000_000L
        val account = session("first")
        var network = dashboard("2")
        var cached: DashboardData? = null
        var offline = false
        val base = repository { method -> when (method) {
            "currentStoredSession" -> account
            "loadCachedDashboard" -> cached
            "loadDashboard" -> { if (offline) throw IllegalStateException("offline"); cached = network; network }
            else -> error(method)
        } }
        val store = SchoolInsightStore()
        val observed = ObservedSchoolRepository(base, store, now = { time })
        observed.loadDashboard(true)
        time += 1_000
        network = dashboard("4")
        observed.loadDashboard(true)
        val successful = store.snapshot(account.cacheScope, time)
        assertThat(successful.observations).hasSize(1)
        time += 1_000
        offline = true
        try { observed.loadDashboard(true); error("Expected offline") } catch (_: IllegalStateException) { }
        assertThat(observed.loadCachedDashboard()).isEqualTo(network)
        val stale = store.snapshot(account.cacheScope, time)
        assertThat(stale.observations).isEqualTo(successful.observations)
        assertThat(stale.sources.getValue("marks").lastSuccessAt).isEqualTo(successful.sources.getValue("marks").lastSuccessAt)
        assertThat(stale.sources.getValue("marks").failed).isTrue()
    }

    @Test fun switchingAccountDuringNetworkReadRejectsTheResultAndCreatesNoObservation() = runBlocking {
        var current = session("first")
        val original = current
        val base = repository { method -> when (method) {
            "currentStoredSession" -> current
            "loadCachedDashboard" -> null
            "loadDashboard" -> { current = session("second"); dashboard("4") }
            else -> error(method)
        } }
        val store = SchoolInsightStore()
        val observed = ObservedSchoolRepository(base, store)
        try { observed.loadDashboard(true); error("Expected account switch cancellation") } catch (_: CancellationException) { }
        assertThat(store.snapshot(original.cacheScope).observations).isEmpty()
        assertThat(store.snapshot(current.cacheScope).subjects).isEmpty()
    }

    @Test fun encryptedStorageAdapterRoundTripsScopedStateWithoutPlainScopeKeys() {
        val disk = MemoryJsonStore()
        val store = SchoolInsightStore(SchoolInsightJsonStorage(disk))
        store.observe("school/account-a", dashboard("2").marksResponse.subjects, 1000)
        store.observe("school/account-a", dashboard("4").marksResponse.subjects, 2000)
        val restored = SchoolInsightStore(SchoolInsightJsonStorage(disk))
        assertThat(restored.snapshot("school/account-a", 2000).observations).hasSize(1)
        assertThat(restored.snapshot("school/account-b", 2000).observations).isEmpty()
        assertThat(disk.values.keys.single()).doesNotContain("account-a")
        restored.clear("school/account-a")
        assertThat(disk.values).isEmpty()
    }

    @Test fun sidecarReadAndWriteFailuresCannotHideSuccessfullyLoadedSchoolData() = runBlocking {
        val account = session("first")
        val expected = dashboard("2")
        val base = repository { method -> when (method) {
            "currentStoredSession" -> account
            "loadCachedDashboard" -> null
            "loadDashboard" -> expected
            else -> error(method)
        } }
        val brokenDisk = object : SchoolInsightStorage {
            override fun read(scope: String): SchoolInsightState? = throw IOException("Unavailable key")
            override fun write(state: SchoolInsightState): Unit = throw IOException("Full disk")
            override fun clear(scope: String): Unit = throw IOException("Full disk")
            override fun clearAll(): Unit = throw IOException("Full disk")
        }
        val store = SchoolInsightStore(brokenDisk)
        assertThat(ObservedSchoolRepository(base, store).loadDashboard(true)).isEqualTo(expected)
        assertThat(store.persistenceFailed).isTrue()
        assertThat(store.snapshot(account.cacheScope).observations).isEmpty()
    }

    @Test fun successfulRepositoryCacheReuseKeepsItsRealTimestamp() = runBlocking {
        val account = session("first")
        val expected = dashboard("2")
        val cacheTime = 1000L
        val clock = cacheTime + 600_000
        val dao = MemoryCacheDao()
        dao.entries["dashboard:${account.cacheScope}"] = CacheEntryEntity("dashboard:${account.cacheScope}", Json.encodeToString(DashboardData.serializer(), expected), cacheTime)
        val cache = RoomGradeyCache(dao, Json)
        val base = repository { method -> when (method) {
            "currentStoredSession" -> account
            "loadCachedDashboard", "loadDashboard" -> expected
            else -> error(method)
        } }
        val store = SchoolInsightStore()
        assertThat(ObservedSchoolRepository(base, store, cache, now = { clock }).loadDashboard(false)).isEqualTo(expected)
        val snapshot = store.snapshot(account.cacheScope, clock)
        assertThat(snapshot.observations).isEmpty()
        assertThat(snapshot.sources.getValue("marks").lastSuccessAt).isEqualTo(cacheTime)
        assertThat(snapshot.sources.getValue("marks").isStale(clock, 300_000)).isTrue()
    }

    private fun repository(answer: (String) -> Any?): SchoolRepository = Proxy.newProxyInstance(
        SchoolRepository::class.java.classLoader, arrayOf(SchoolRepository::class.java),
    ) { _, method, _ -> answer(method.name) } as SchoolRepository

    private class MemoryJsonStore : JsonStore {
        val values = mutableMapOf<String, String>()
        override fun <T> load(key: String, serializer: KSerializer<T>): T? = values[key]?.let { Json.decodeFromString(serializer, it) }
        override fun <T> save(key: String, value: T?, serializer: KSerializer<T>) { if (value == null) values.remove(key) else values[key] = Json.encodeToString(serializer, value) }
        override fun clear(key: String) { values.remove(key) }
        override fun clearPrefix(prefix: String) { values.keys.filter { it.startsWith(prefix) }.forEach(values::remove) }
    }

    private class MemoryCacheDao : CacheEntryDao {
        val entries = mutableMapOf<String, CacheEntryEntity>()
        override suspend fun load(key: String) = entries[key]
        override suspend fun save(entity: CacheEntryEntity) { entries[entity.key] = entity }
        override suspend fun clear(key: String) { entries.remove(key) }
        override suspend fun clearPrefix(prefix: String) { entries.keys.filter { it.startsWith(prefix) }.forEach(entries::remove) }
        override suspend fun clearAll() = entries.clear()
    }
}
