package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.SchoolDirectoryClient
import com.bukovinafilip.gradey.domain.SchoolDirectoryProvider
import com.bukovinafilip.gradey.domain.SchoolDirectorySearch
import com.bukovinafilip.gradey.model.CachedSchoolDirectory
import com.bukovinafilip.gradey.model.SchoolDirectorySchool
import com.bukovinafilip.gradey.network.BakalariSchoolDirectoryClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull

/** Public Bakaláři directory; partial outages never replace a previously complete offline cache. */
class BakalariSchoolDirectoryProvider(
    private val store: JsonStore,
    private val client: SchoolDirectoryClient = BakalariSchoolDirectoryClient(),
    private val nowProvider: () -> Long = System::currentTimeMillis,
    private val batchTimeoutMillis: Long = 25_000,
) : SchoolDirectoryProvider {
    private val refreshMutex = Mutex()

    override suspend fun cachedSchools(): List<SchoolDirectorySchool> = cached()?.schools.orEmpty()

    override suspend fun refreshDirectory(): List<SchoolDirectorySchool> = refreshMutex.withLock {
        val previous = cached()
        if (previous != null && !previous.isStale(nowProvider()) && previous.schools.isNotEmpty()) return@withLock previous.schools
        val towns = client.fetchMunicipalities().filter { it.name.isNotBlank() && it.schoolCount > 0 }
        check(towns.isNotEmpty()) { "The school directory is unavailable. Enter your school address instead." }
        val permits = Semaphore(8)
        val resultMutex = Mutex()
        val fresh = mutableListOf<SchoolDirectorySchool>()
        var successfulExpected = 0
        withTimeoutOrNull(batchTimeoutMillis) {
            coroutineScope {
                towns.forEach { town -> launch {
                    permits.withPermit {
                        val schools = fetchTown(town.name) ?: return@withPermit
                        resultMutex.withLock {
                            fresh += schools
                            successfulExpected += town.schoolCount
                        }
                    }
                } }
            }
        }
        val merged = (previous?.schools.orEmpty() + fresh)
            .associateBy { it.trimmedSchoolURL.trimEnd('/').lowercase(java.util.Locale.ROOT) }
            .values.sortedWith(compareBy({ SchoolDirectorySearch.normalize(it.town) }, { SchoolDirectorySearch.normalize(it.name) }))
        check(merged.isNotEmpty()) { "The school directory is unavailable. Enter your school address instead." }
        val expected = towns.sumOf { it.schoolCount }.toDouble()
        val requestCoverage = successfulExpected / expected
        val responseCoverage = fresh.size / expected
        val trustedCache = previous?.formatVersion == 2
        val healthy = if (trustedCache) requestCoverage >= 0.90 && responseCoverage >= 0.70
            else requestCoverage >= 0.98 && responseCoverage >= 0.90
        if (healthy) store.save(KEY, CachedSchoolDirectory(merged, nowProvider()), CachedSchoolDirectory.serializer())
        merged
    }

    private suspend fun fetchTown(town: String): List<SchoolDirectorySchool>? {
        repeat(2) { attempt ->
            try { return client.fetchSchools(town) }
            catch (error: CancellationException) { throw error }
            catch (_: Exception) { if (attempt == 0) delay(250) }
        }
        return null
    }

    private fun cached(): CachedSchoolDirectory? = store.load(KEY, CachedSchoolDirectory.serializer())

    private companion object { const val KEY = "school-directory.v2" }
}
