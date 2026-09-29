package com.bukovinafilip.gradey.data

import android.content.Context
import com.bukovinafilip.gradey.domain.SchoolInsightState
import com.bukovinafilip.gradey.domain.SchoolInsightStorage
import com.bukovinafilip.gradey.domain.SchoolInsightStore
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.TimetableWeek
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Encrypted, school-scoped, reconstructible sidecar; no credentials or raw provider payloads. */
class SchoolInsightJsonStorage(private val jsonStore: JsonStore) : SchoolInsightStorage {
    private fun key(scope: String) = "school-insights-v1:" + MessageDigest.getInstance("SHA-256")
        .digest(scope.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    override fun read(scope: String) = jsonStore.load(key(scope), SchoolInsightState.serializer())
    override fun write(state: SchoolInsightState) = jsonStore.save(key(state.scope), state, SchoolInsightState.serializer())
    override fun clear(scope: String) = jsonStore.clear(key(scope))
    override fun clearAll() = jsonStore.clearPrefix("school-insights-v1:")
}

object AndroidSchoolInsightStore {
    // App and workers share the same synchronized sidecar in this process.
    private var store: SchoolInsightStore? = null
    @Synchronized fun create(context: Context): SchoolInsightStore = store ?: SchoolInsightStore(
        SchoolInsightJsonStorage(SecureJsonStore(context.applicationContext, "gradey-school-insights", Json { ignoreUnknownKeys = true; encodeDefaults = true })),
    ).also { store = it }
}

/** Observes provider reads once at the shared repository boundary, including AI and app refreshes. */
class ObservedSchoolRepository(
    private val delegate: SchoolRepository,
    val insightStore: SchoolInsightStore,
    private val cache: RoomGradeyCache? = null,
    private val now: () -> Long = System::currentTimeMillis,
) : SchoolRepository by delegate {
    private val activationMutex = Mutex()
    private val marksMutex = Mutex()
    private val timetableMutex = Mutex()
    private var activeScope: String? = null

    override suspend fun loadDashboard(forceRefresh: Boolean): DashboardData = marksMutex.withLock {
        val scope = scope()
        activate(scope)
        val started = now()
        try {
            val result = delegate.loadDashboard(forceRefresh)
            ensureScope(scope)
            sidecar(scope, "marks") {
                val fetchedAt = if (cache != null) cache.dashboardFetchedAt(scope) else now()
                if (fetchedAt != null) insightStore.observe(scope, result.marksResponse.subjects, fetchedAt)
                else insightStore.sourceUnavailable(scope, "marks")
            }
            result
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            if (isCurrent(scope)) sidecar(scope, "marks") { insightStore.sourceFailed(scope, "marks", started) }
            throw error
        }
    }

    override suspend fun loadCachedDashboard(): DashboardData? {
        val scope = delegate.currentStoredSession()?.cacheScope ?: return null
        val result = delegate.loadCachedDashboard()
        ensureScope(scope)
        activate(scope, result)
        return result
    }

    override suspend fun loadTimetable(weekContaining: String): TimetableWeek = timetableMutex.withLock {
        val scope = scope()
        activate(scope)
        val key = weekKey(weekContaining)
        val started = now()
        try {
            val result = delegate.loadTimetable(weekContaining)
            ensureScope(scope)
            sidecar(scope, "timetable-$key") {
                val fetchedAt = if (cache != null) cache.timetableFetchedAt(scope, key) else now()
                if (fetchedAt != null) insightStore.sourceSucceeded(scope, "timetable-$key", fetchedAt)
                else insightStore.sourceUnavailable(scope, "timetable-$key")
            }
            result
        } catch (error: CancellationException) { throw error
        } catch (error: Exception) {
            if (isCurrent(scope)) sidecar(scope, "timetable-$key") { insightStore.sourceFailed(scope, "timetable-$key", started) }
            throw error
        }
    }

    override suspend fun loadCachedTimetable(weekContaining: String): TimetableWeek? {
        val scope = delegate.currentStoredSession()?.cacheScope ?: return null
        activate(scope)
        val result = delegate.loadCachedTimetable(weekContaining)
        ensureScope(scope)
        val key = weekKey(weekContaining)
        if (result != null) sidecar(scope, "timetable-$key") { cache?.timetableFetchedAt(scope, key)?.let { timestamp ->
            // Cache fallback never clears a failed refresh or makes the data freshly fetched.
            if (insightStore.snapshot(scope, now()).sources["timetable-$key"]?.lastSuccessAt == null)
                insightStore.sourceSucceeded(scope, "timetable-$key", timestamp)
        } }
        return result
    }

    override suspend fun logout() {
        val scope = delegate.currentStoredSession()?.cacheScope
        try { delegate.logout() } finally {
            activeScope = null
            if (scope != null) sidecar(scope, "marks") { insightStore.clear(scope) }
        }
    }

    private suspend fun activate(scope: String, knownCache: DashboardData? = null) = activationMutex.withLock {
        if (activeScope == scope) return@withLock
        sidecar(scope, "marks") {
            val cached = knownCache ?: delegate.loadCachedDashboard()
            val fetchedAt = cache?.dashboardFetchedAt(scope)
            ensureScope(scope)
            insightStore.activate(scope, cached?.marksResponse?.subjects.orEmpty(), if (cached != null) fetchedAt else null, now())
        }
        activeScope = scope
    }

    private suspend fun sidecar(scope: String, source: String, operation: suspend () -> Unit) = withContext(Dispatchers.IO) {
        try { operation() } catch (error: CancellationException) { throw error
        } catch (_: Exception) { insightStore.sourceUnavailable(scope, source) }
    }

    private suspend fun scope() = delegate.currentStoredSession()?.cacheScope ?: error("Connect your school account first.")
    private suspend fun isCurrent(scope: String) = delegate.currentStoredSession()?.cacheScope == scope
    private suspend fun ensureScope(scope: String) { if (!isCurrent(scope)) throw CancellationException("The school account changed during refresh.") }
    private fun weekKey(date: String) = LocalDate.parse(date.take(10)).let { it.minusDays((it.dayOfWeek.value - 1).toLong()) }.toString()
}
