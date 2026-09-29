package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.SchoolDirectoryClient
import com.bukovinafilip.gradey.model.CachedSchoolDirectory
import com.bukovinafilip.gradey.model.SchoolDirectoryMunicipality
import com.bukovinafilip.gradey.model.SchoolDirectorySchool
import com.bukovinafilip.gradey.network.GradeyJson
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class SchoolDirectoryProviderTest {
    private val now = 1_800_000_000_000L
    private fun school(id: String, town: String) = SchoolDirectorySchool(id, "School $id", town, "https://$id.example.cz/")

    @Test fun healthyRefreshSavesDirectoryAndReusesItOfflineForSevenDays() = runBlocking {
        val store = Store()
        val client = Client(listOf("Brno", "Praha"))
        val provider = BakalariSchoolDirectoryProvider(store, client, nowProvider = { now })
        val first = provider.refreshDirectory()
        assertEquals(2, first.size)
        assertEquals(2, client.townCalls)
        assertEquals(2, provider.cachedSchools().size)
        client.unavailable = true
        assertEquals(first, provider.refreshDirectory())
        assertEquals(2, client.townCalls)
    }

    @Test fun partialTownFailureKeepsPriorSchoolsAndDoesNotReplaceCompleteCache() = runBlocking {
        val store = Store()
        val old = CachedSchoolDirectory(listOf(school("original", "Praha")), now - 8L * 24 * 60 * 60 * 1000)
        store.save("school-directory.v2", old, CachedSchoolDirectory.serializer())
        val client = Client(listOf("Brno", "Praha")).apply { failedTown = "Praha" }
        val result = BakalariSchoolDirectoryProvider(store, client, nowProvider = { now }).refreshDirectory()
        assertEquals(setOf("original", "Brno"), result.map { it.id }.toSet())
        assertEquals(old, store.load("school-directory.v2", CachedSchoolDirectory.serializer()))
        assertEquals(3, client.townCalls)
    }

    @Test fun firstIncompleteFetchRemainsUsefulWithoutBeingSavedAsComplete() = runBlocking {
        val store = Store()
        val client = Client(listOf("Brno", "Praha")).apply { failedTown = "Praha" }
        val provider = BakalariSchoolDirectoryProvider(store, client, nowProvider = { now })
        assertEquals(listOf("Brno"), provider.refreshDirectory().map { it.id })
        assertTrue(provider.cachedSchools().isEmpty())
    }

    @Test fun unavailableDirectoryPreservesCachedResults() = runBlocking {
        val store = Store()
        val old = CachedSchoolDirectory(listOf(school("original", "Praha")), 0)
        store.save("school-directory.v2", old, CachedSchoolDirectory.serializer())
        val client = Client(listOf("Brno")).apply { unavailable = true }
        val provider = BakalariSchoolDirectoryProvider(store, client, nowProvider = { now })
        assertTrue(runCatching { provider.refreshDirectory() }.exceptionOrNull() is IOException)
        assertEquals(old.schools, provider.cachedSchools())
    }

    private inner class Client(private val towns: List<String>) : SchoolDirectoryClient {
        var townCalls = 0
        var failedTown: String? = null
        var unavailable = false
        override suspend fun fetchMunicipalities(): List<SchoolDirectoryMunicipality> {
            if (unavailable) throw IOException("offline")
            return towns.map { SchoolDirectoryMunicipality(it, 1) }
        }
        override suspend fun fetchSchools(municipalityName: String): List<SchoolDirectorySchool> {
            townCalls++
            if (failedTown == municipalityName) throw IOException("town temporarily unavailable")
            return listOf(school(municipalityName, municipalityName))
        }
    }

    private class Store : JsonStore {
        private val entries = mutableMapOf<String, String>()
        override fun <T> load(key: String, serializer: KSerializer<T>): T? = entries[key]?.let { GradeyJson.decodeFromString(serializer, it) }
        override fun <T> save(key: String, value: T?, serializer: KSerializer<T>) {
            if (value == null) entries.remove(key) else entries[key] = GradeyJson.encodeToString(serializer, value)
        }
        override fun clear(key: String) { entries.remove(key) }
        override fun clearPrefix(prefix: String) { entries.keys.filter { it.startsWith(prefix) }.forEach { entries.remove(it) } }
    }
}
