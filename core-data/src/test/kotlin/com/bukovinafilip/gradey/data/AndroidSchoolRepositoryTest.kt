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

    @Test fun permanentCacheIsSeparateFromWeeklyAndBoundToSchoolAccount() = runBlocking {
        val store = SchoolSessionStore(MemoryJsonStore())
        val first = session("first")
        store.save(first)
        val cache = RoomGradeyCache(MemoryCacheDao(), GradeyJson)
        val actual = com.bukovinafilip.gradey.domain.DemoData.timetableFor("2026-09-28")
        cache.saveTimetable(first.cacheScope, "2026-09-28", actual)
        val repository = AndroidSchoolRepository(FakeBakalariClient(), OkHttpEduPageClient(), store, cache)
        assertNull(repository.loadCachedPermanentTimetable())
        val permanent = repository.loadPermanentTimetable()
        assertEquals(TimetableKind.PERMANENT, permanent.kind)
        assertEquals(permanent, repository.loadCachedPermanentTimetable())
        assertEquals(actual, repository.loadCachedTimetable("2026-09-28"))
        store.save(session("second"))
        assertNull(repository.loadCachedPermanentTimetable())
        store.save(first.copy(provider = SchoolProvider.EDU_PAGE))
        assertNull(repository.loadCachedPermanentTimetable())
    }

    @Test fun reportCardsAreDecodedLenientlyCachedPerAccountAndClearedWithTheSchool() = runBlocking {
        // Bakaláři versions disagree on numbers versus strings; one odd field must not lose the report card.
        val response = GradeyJson.decodeFromString(FinalMarksResponse.serializer(), """
            {"CertificateTerms":[
              {"SchoolYear":"2024/25","Semester":2,"Closed":"yes","MarksAverage":"1,45","AbsentHours":18,"NotExcusedHours":"0",
               "AchievementText":7,"CertificateDate":"2025-06-27T00:00:00+02:00",
               "Subjects":[{"Id":12,"Abbrev":"M","Name":"Matematika"}],
               "FinalMarks":[{"Id":1,"SubjectId":12,"MarkText":"1"}]},
              {"SchoolYear":"2025/26","Semester":"1","Closed":false,"Subjects":[],"FinalMarks":[],"Unknown":{"nested":true}}
            ]}
        """.trimIndent())
        val store = SchoolSessionStore(MemoryJsonStore())
        val first = session("first")
        store.save(first)
        val cache = RoomGradeyCache(MemoryCacheDao(), GradeyJson)
        val client = FakeBakalariClient().apply { finalMarks = { response } }
        val repository = AndroidSchoolRepository(client, OkHttpEduPageClient(), store, cache)
        assertTrue(repository.loadCachedReportCards().isEmpty())

        val terms = repository.loadReportCards()
        assertEquals(listOf("2025/26-1", "2024/25-2"), terms.map { it.id })
        val issued = terms.last()
        assertEquals(1.45, issued.average!!, 0.0001)
        assertEquals(18.0, issued.absentHours!!, 0.0)
        assertEquals(0.0, issued.unexcusedHours!!, 0.0)
        assertTrue(issued.isClosed)
        assertNull(issued.achievementText)
        assertEquals(listOf("12" to "1"), issued.marks.map { it.subjectID to it.markText })
        assertFalse(terms.first().isClosed)
        assertEquals(terms, repository.loadCachedReportCards())

        // Another account, and EduPage, never see this account's report cards.
        store.save(session("second"))
        assertTrue(repository.loadCachedReportCards().isEmpty())
        store.save(first.copy(provider = SchoolProvider.EDU_PAGE))
        assertTrue(repository.loadCachedReportCards().isEmpty())
        store.save(first)
        cache.clearSchool(first.cacheScope)
        assertTrue(repository.loadCachedReportCards().isEmpty())
    }

    @Test fun everySemesterSeenIsArchivedForRecapPerAccountAndClearedWithTheSchool() = runBlocking {
        fun marks(id: String, date: String) = MarksResponse(subjects = listOf(Subject(
            marks = listOf(Mark(markDate = date, markText = "1", subjectID = "m", id = id)), subjectInfo = SubjectInfo("m", "M", "Matematika"),
        )))
        val store = SchoolSessionStore(MemoryJsonStore())
        val first = session("first")
        store.save(first)
        val cache = RoomGradeyCache(MemoryCacheDao(), GradeyJson)
        var today = java.time.LocalDate.parse("2026-11-12")
        val client = FakeBakalariClient().apply {
            marks = { marks("autumn", "2026-10-05T08:00:00+02:00") }
            absences = { AbsenceResponse(absences = listOf(Absence(date = "2026-10-06T00:00:00+02:00", ok = 2))) }
        }
        val repository = AndroidSchoolRepository(client, OkHttpEduPageClient(), store, cache) { today }
        assertEquals(RecapArchive(), repository.loadRecapArchive())

        repository.loadDashboard(true)
        assertEquals(setOf("2026/27-1"), repository.loadRecapArchive().marks.keys)
        assertEquals(setOf("2026/27-1"), repository.loadRecapArchive().absence.keys)

        // Bakaláři moves on to the next semester; the autumn stays on the device.
        today = java.time.LocalDate.parse("2027-03-01")
        client.marks = { marks("spring", "2027-02-20T08:00:00+01:00") }
        client.absences = { AbsenceResponse(absences = listOf(Absence(date = "2027-02-22T00:00:00+01:00", late = 1))) }
        repository.loadDashboard(true)
        repository.loadAbsence(true)
        val archive = repository.loadRecapArchive()
        assertEquals(setOf("2026/27-1", "2026/27-2"), archive.marks.keys)
        assertEquals("autumn", archive.marks.getValue("2026/27-1").subjects.single().marks.single().id)
        assertEquals(setOf("2026/27-1", "2026/27-2"), archive.absence.keys)

        // A semester with no marks yet replaces nothing, and a failed load changes nothing.
        client.marks = { MarksResponse() }
        repository.loadDashboard(true)
        client.marks = { throw IOException("offline") }
        try { repository.loadDashboard(true); fail("Expected offline failure") } catch (_: IOException) { }
        assertEquals(archive, repository.loadRecapArchive())

        store.save(session("second"))
        assertEquals(RecapArchive(), repository.loadRecapArchive())
        store.save(first)
        cache.clearSchool(first.cacheScope)
        assertEquals(RecapArchive(), repository.loadRecapArchive())
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
        var absences: () -> AbsenceResponse = { AbsenceResponse() }
        override suspend fun fetchAbsences(baseURL: String, accessToken: String) = absences()
        override suspend fun fetchUser(baseURL: String, accessToken: String) = UserResponse("Student")
        override suspend fun fetchTimetable(baseURL: String, accessToken: String, date: String) = TimetableResponse()
        override suspend fun fetchPermanentTimetable(baseURL: String, accessToken: String) = com.bukovinafilip.gradey.domain.DemoData.permanentTimetableResponse()
        var finalMarks: () -> FinalMarksResponse = { FinalMarksResponse() }
        override suspend fun fetchFinalMarks(baseURL: String, accessToken: String) = finalMarks()
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
