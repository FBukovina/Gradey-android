package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.UserResponse
import com.bukovinafilip.gradey.network.GradeyJson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class RoomGradeyCacheTest {
    @Test fun removingAnAccountCannotDeleteAnotherAccountsDashboard() = runBlocking {
        val dao = MemoryCacheDao()
        val cache = RoomGradeyCache(dao, GradeyJson)
        cache.saveDashboard("account", DashboardData(MarksResponse(), user = UserResponse("First")))
        cache.saveDashboard("account-other", DashboardData(MarksResponse(), user = UserResponse("Second")))
        cache.clearSchool("account")
        assertNull(cache.loadDashboard("account"))
        assertEquals("Second", cache.loadDashboard("account-other")?.user?.fullName)
    }

    @Test fun corruptedCacheDoesNotCrashOfflineLaunch() = runBlocking {
        val dao = MemoryCacheDao()
        dao.save(CacheEntryEntity("dashboard:account", "invalid json", 0))
        assertNull(RoomGradeyCache(dao, GradeyJson).loadDashboard("account"))
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
