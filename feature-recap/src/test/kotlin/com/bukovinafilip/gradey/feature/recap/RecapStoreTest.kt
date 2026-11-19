package com.bukovinafilip.gradey.feature.recap

import android.content.SharedPreferences
import com.bukovinafilip.gradey.domain.DemoData
import com.bukovinafilip.gradey.domain.RecapDemoData
import com.bukovinafilip.gradey.domain.RecapPeriod
import com.bukovinafilip.gradey.domain.RecapSlide
import com.bukovinafilip.gradey.domain.ReportCardTerm
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.model.RecapArchive
import com.bukovinafilip.gradey.model.TimetableWeek
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class RecapStoreTest {
    private val today: LocalDate = LocalDate.parse("2026-11-12")
    private val now: ZonedDateTime = today.atTime(16, 0).atZone(ZoneId.of("Europe/Prague"))

    /** The sample student's live data is last spring's; the archive holds this autumn. */
    private val live = RecapSource(DemoData.marksResponse.subjects, DemoData.absenceResponse)

    @Test fun overviewListsWhatIsOutAndTodayOffersTheNewestUntilItIsHidden() = runBlocking {
        val preferences = MemoryPreferences()
        val store = RecapStore(FakeRepository(RecapDemoData.archive(now)), preferences) { today }
        val overview = store.overview(live)

        assertThat(overview.listed.map { it.period.id }).containsExactly(
            "quarter-2026-1", "month-2026-10", "month-2026-9", "month-2026-6", "semester-2025-2", "month-2026-5", "month-2026-4", "quarter-2025-3",
            "month-2026-3", "month-2026-2",
        ).inOrder()
        assertThat(overview.latest!!.markCount).isAtLeast(30)
        // Last spring comes from the live marks, which replace nothing in this autumn's archive.
        assertThat(overview.listed.first { it.period.id == "semester-2025-2" }.markCount).isEqualTo(4)
        assertThat(overview.featured.map { it.period.id }).containsExactly("quarter-2026-1")
        assertThat(overview.featured(store.hiddenIDs.value)!!.period.id).isEqualTo("quarter-2026-1")

        store.hide("quarter-2026-1")
        store.hide("quarter-2026-1")
        assertThat(overview.featured(store.hiddenIDs.value)).isNull()
        // Hiding is remembered across launches, under the same key as on iOS.
        assertThat(preferences.getStringSet("gradey.recap.hidden.v1", null)).containsExactly("quarter-2026-1")
        assertThat(RecapStore(FakeRepository(), preferences) { today }.hiddenIDs.value).containsExactly("quarter-2026-1")
        assertThat(store.period("quarter-2026-1")).isEqualTo(RecapPeriod.quarter(1, 2026))
        assertThat(store.period("month-2019-3")).isNull()
        assertThat(store.period(null)).isNull()
    }

    @Test fun liveDataReplacesTheArchivedCopyOfItsSemesterAndAFailedArchiveIsNotFatal() = runBlocking {
        val archived = RecapDemoData.archive(now)
        val autumn = archived.marks.getValue("2026/27-1").subjects
        val trimmed = RecapSource(autumn.take(1), null)
        val store = RecapStore(FakeRepository(archived), MemoryPreferences()) { today }
        val quarter = store.summary(RecapPeriod.quarter(1, 2026), trimmed)
        assertThat(quarter.markCount).isEqualTo(autumn.first().marks.size)
        // Absence still comes from the archive when nothing live replaces it.
        assertThat(quarter.absence).isNotNull()

        val broken = RecapStore(FakeRepository(archiveFailure = IOException("disk")), MemoryPreferences()) { today }
        assertThat(broken.overview(live).listed.map { it.period.id }).contains("semester-2025-2")
        assertThat(broken.overview(RecapSource(emptyList(), null)).listed).isEmpty()
    }

    @Test fun fullRecapLoadsThisSchoolYearsTimetableWeeksSavedOnesFirst() = runBlocking {
        val repository = FakeRepository(RecapDemoData.archive(now), cachedWeeks = setOf("2026-09-07", "2026-09-14"), failingWeeks = setOf("2026-10-05"))
        val store = RecapStore(repository, MemoryPreferences()) { today }
        val recap = store.recap(RecapPeriod.quarter(1, 2026), live)

        // 31 August (the Monday of 1 September's week) to 9 November: eleven weeks, two saved, one failing.
        assertThat(repository.cachedRequests).hasSize(11)
        assertThat(repository.cachedRequests).contains("2026-08-31")
        assertThat(repository.cachedRequests).doesNotContain("2026-11-16")
        assertThat(repository.fetchedWeeks).hasSize(9)
        assertThat(repository.fetchedWeeks).containsNoneOf("2026-09-07", "2026-09-14")
        // Four lessons a day in the sample timetable: 1–4 September, eight whole weeks (one failed), and 9–12 November.
        assertThat(recap.timetable!!.lessons).isEqualTo((4 + 8 * 5 + 4) * 4)
        assertThat(recap.slides).contains(RecapSlide.TIMETABLE)
        assertThat(repository.reportCardLoads).isEqualTo(1)

        // Last school year's timetable is gone from the school's server, so it is not asked for.
        val before = repository.cachedRequests.size
        assertThat(store.recap(RecapPeriod.semester(2, 2025), live).timetable).isNull()
        assertThat(repository.cachedRequests).hasSize(before)
    }

    @Test fun schoolsWithoutReportCardsGetNoReportCardSlideAndAreNeverAskedForOne() = runBlocking {
        val repository = FakeRepository(RecapDemoData.archive(now))
        val store = RecapStore(repository, MemoryPreferences()) { LocalDate.parse("2027-01-25") }
        val semester = RecapPeriod.semester(1, 2026)
        assertThat(store.recap(semester, live).reportCard).isNotNull()

        val eduPage = live.copy(supportsReportCards = false)
        val loads = repository.reportCardLoads
        val recap = store.recap(semester, eduPage)
        assertThat(recap.reportCard).isNull()
        assertThat(recap.slides).doesNotContain(RecapSlide.REPORT_CARD)
        assertThat(store.overview(eduPage).listed.first { it.period.id == "semester-2026-1" }.reportCard).isNull()
        assertThat(repository.reportCardLoads).isEqualTo(loads)
        assertThat(repository.cachedReportCardLoads).isEqualTo(0)
    }

    /** Only what Recap reads; every other repository call fails the test. */
    private class FakeRepository(
        private val archive: RecapArchive = RecapArchive(),
        private val archiveFailure: Exception? = null,
        private val cachedWeeks: Set<String> = emptySet(),
        private val failingWeeks: Set<String> = emptySet(),
    ) : SchoolRepository by unsupported() {
        val cachedRequests = mutableListOf<String>()
        val fetchedWeeks = mutableListOf<String>()
        var reportCardLoads = 0
        var cachedReportCardLoads = 0

        override suspend fun loadRecapArchive(): RecapArchive = archiveFailure?.let { throw it } ?: archive
        override suspend fun loadCachedReportCards(): List<ReportCardTerm> { cachedReportCardLoads++; return emptyList() }
        override suspend fun loadReportCards(): List<ReportCardTerm> { reportCardLoads++; return emptyList() }
        override suspend fun loadCachedTimetable(weekContaining: String): TimetableWeek? {
            synchronized(this) { cachedRequests += weekContaining }
            return if (weekContaining in cachedWeeks) DemoData.timetableFor(weekContaining) else null
        }
        override suspend fun loadTimetable(weekContaining: String): TimetableWeek {
            synchronized(this) { fetchedWeeks += weekContaining }
            if (weekContaining in failingWeeks) throw IOException("offline")
            return DemoData.timetableFor(weekContaining)
        }
    }

    private class MemoryPreferences : SharedPreferences {
        private val values = HashMap<String, Any?>()
        override fun getAll(): MutableMap<String, *> = HashMap(values)
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = (values[key] as? Set<String>)?.toMutableSet() ?: defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            private val pending = HashMap<String, Any?>()
            private fun put(key: String?, value: Any?) = apply { pending[key.orEmpty()] = value }
            override fun putString(key: String?, value: String?) = put(key, value)
            override fun putStringSet(key: String?, values: MutableSet<String>?) = put(key, values?.toSet())
            override fun putInt(key: String?, value: Int) = put(key, value)
            override fun putLong(key: String?, value: Long) = put(key, value)
            override fun putFloat(key: String?, value: Float) = put(key, value)
            override fun putBoolean(key: String?, value: Boolean) = put(key, value)
            override fun remove(key: String?) = put(key, null)
            override fun clear() = apply { values.clear() }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() { values.putAll(pending) }
        }
    }

    private companion object {
        fun unsupported(): SchoolRepository = Proxy.newProxyInstance(SchoolRepository::class.java.classLoader, arrayOf(SchoolRepository::class.java)) { _, method, _ ->
            throw UnsupportedOperationException("Recap must not call ${method.name}")
        } as SchoolRepository
    }
}
