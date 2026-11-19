package com.bukovinafilip.gradey.feature.recap

import android.content.SharedPreferences
import androidx.compose.runtime.Immutable
import com.bukovinafilip.gradey.domain.GradeSimulation
import com.bukovinafilip.gradey.domain.Recap
import com.bukovinafilip.gradey.domain.RecapArchives
import com.bukovinafilip.gradey.domain.RecapBuilder
import com.bukovinafilip.gradey.domain.RecapCalendar
import com.bukovinafilip.gradey.domain.RecapInput
import com.bukovinafilip.gradey.domain.RecapPeriod
import com.bukovinafilip.gradey.domain.RecapSemester
import com.bukovinafilip.gradey.domain.ReportCardPeriod
import com.bukovinafilip.gradey.domain.ReportCardTerm
import com.bukovinafilip.gradey.domain.SchoolRepository
import com.bukovinafilip.gradey.domain.TimetableDates
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.TimetableWeek
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

/**
 * The live school data a recap starts from: what is on screen has the newest marks and the user's
 * absence corrections.
 *
 * @param supportsReportCards false for schools without report cards (EduPage); their recaps leave the report card out.
 */
@Immutable
data class RecapSource(val subjects: List<Subject>, val absence: AbsenceResponse?, val supportsReportCards: Boolean = true)

/**
 * Which recaps are out and what Today points at.
 *
 * @param listed recaps that are out and have something in them, newest first.
 * @param featured the ones Today may offer right now, biggest first, before hidden ones are left out.
 */
@Immutable
data class RecapOverview(val listed: List<Recap> = emptyList(), val featured: List<Recap> = emptyList()) {
    /** The newest recap, shown on Marks and at the top of the list. */
    val latest: Recap? get() = listed.firstOrNull()

    /** The recap the Today tile offers, until it is opened or hidden. */
    fun featured(hiddenIDs: Set<String>): Recap? = featured.firstOrNull { it.period.id !in hiddenIDs }
}

/**
 * Gradey Recap (iOS `RecapStore`): which recaps are out, what Today points at, and the full recap
 * behind each. The live semester comes from the marks and absence on screen, earlier ones from the
 * repository's archive. Everything is worked out on the device.
 */
class RecapStore(
    private val repository: SchoolRepository,
    private val preferences: SharedPreferences,
    private val today: () -> LocalDate = RecapCalendar::today,
) {
    private val hidden = MutableStateFlow(preferences.getStringSet(HiddenKey, null).orEmpty().toSet())

    /** Periods whose Today tile was opened or dismissed. */
    val hiddenIDs: StateFlow<Set<String>> = hidden.asStateFlow()

    private val semesterMutex = Mutex()
    private var semesterKey: Pair<List<Subject>, AbsenceResponse?>? = null
    private var semesterCache: List<RecapSemester> = emptyList()

    fun hide(periodID: String) {
        if (periodID in hidden.value) return
        hidden.update { it + periodID }
        preferences.edit().putStringSet(HiddenKey, hidden.value).apply()
    }

    /** The period with this id among the ones that can be out right now. */
    fun period(id: String?): RecapPeriod? = RecapCalendar.candidates(today()).firstOrNull { it.id == id }

    /** Marks and absence only; cheap enough for the tiles and the list. */
    suspend fun overview(source: RecapSource): RecapOverview = withContext(Dispatchers.Default) {
        val day = today()
        val input = RecapInput(semesters(source), cachedReportCards(source), today = day)
        val built = HashMap<String, Recap>()
        val summary: (RecapPeriod) -> Recap = { period -> built.getOrPut(period.id) { build(period, input, source) } }
        RecapOverview(
            listed = RecapCalendar.listed(day).map(summary).filter { it.hasContent },
            featured = RecapCalendar.featured(day).map(summary).filter { it.hasContent },
        )
    }

    /** One period from marks and absence, for the story's first frame. */
    suspend fun summary(period: RecapPeriod, source: RecapSource): Recap = withContext(Dispatchers.Default) {
        build(period, RecapInput(semesters(source), cachedReportCards(source), today = today()), source)
    }

    /** Everything, including the period's timetable weeks and the newest report cards. */
    suspend fun recap(period: RecapPeriod, source: RecapSource): Recap = coroutineScope {
        val day = today()
        // Earlier school years' timetables are usually gone from the school's server.
        val schoolYearStart = LocalDate.of(ReportCardPeriod.containing(day).startYear, 9, 1)
        val start = maxOf(period.start, schoolYearStart)
        val end = minOf(period.end, day.plusDays(1))
        val weeks = async { if (start < end) withTimeoutOrNull(TimetableTimeoutMillis) { timetableWeeks(start, end) } else null }
        val reportCards = async { loadReportCards(source) }
        val input = RecapInput(semesters(source), reportCards.await(), weeks.await(), day)
        withContext(Dispatchers.Default) { build(period, input, source) }
    }

    /** A school without report cards gets no estimate of one either. */
    private fun build(period: RecapPeriod, input: RecapInput, source: RecapSource): Recap =
        RecapBuilder.build(period, input).let { if (source.supportsReportCards) it else it.copy(reportCard = null) }

    /** The archive plus the live data, each semester's averages prepared once. */
    private suspend fun semesters(source: RecapSource): List<RecapSemester> = semesterMutex.withLock {
        val (subjects, absence) = source
        val key = subjects to absence
        if (semesterKey == key) return@withLock semesterCache
        val archive = attempt { repository.loadRecapArchive() }
        val marksBySemester = HashMap<ReportCardPeriod, List<Subject>>()
        val absenceBySemester = HashMap<ReportCardPeriod, AbsenceResponse>()
        archive?.marks?.forEach { (name, response) -> RecapArchives.period(name)?.let { marksBySemester[it] = response.subjects } }
        archive?.absence?.forEach { (name, response) -> RecapArchives.period(name)?.let { absenceBySemester[it] = response } }
        // What is on screen has the newest marks and the user's absence corrections.
        RecapArchives.semesterOfMarks(subjects)?.let { marksBySemester[it] = subjects }
        if (absence != null) RecapArchives.semesterOfAbsence(absence)?.let { absenceBySemester[it] = absence }
        val result = withContext(Dispatchers.Default) {
            (marksBySemester.keys + absenceBySemester.keys).map { period ->
                val semesterSubjects = marksBySemester[period].orEmpty()
                RecapSemester(period, semesterSubjects, absenceBySemester[period], semesterSubjects.associate { it.id to GradeSimulation.prepare(it) })
            }
        }
        // A failed archive read is tried again next time.
        if (archive != null) { semesterKey = key; semesterCache = result }
        result
    }

    private suspend fun cachedReportCards(source: RecapSource): List<ReportCardTerm> =
        if (source.supportsReportCards) attempt { repository.loadCachedReportCards() }.orEmpty() else emptyList()

    private suspend fun loadReportCards(source: RecapSource): List<ReportCardTerm> =
        if (source.supportsReportCards) attempt { repository.loadReportCards() } ?: cachedReportCards(source) else emptyList()

    /** The weeks from [start] up to [end]: saved ones first, the rest from the school, four at a time. */
    private suspend fun timetableWeeks(start: LocalDate, end: LocalDate): List<TimetableWeek> = coroutineScope {
        val mondays = generateSequence(TimetableDates.monday(start)) { it.plusWeeks(1) }.takeWhile { it < end }.toList()
        val limit = Semaphore(4)
        mondays.map { monday ->
            async {
                val week = monday.toString()
                attempt { repository.loadCachedTimetable(week) } ?: limit.withPermit { attempt { repository.loadTimetable(week) } }
            }
        }.awaitAll().filterNotNull()
    }

    /** Null when a load fails; a recap is then built from what there is. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    companion object {
        /** Preference key, the same as on iOS. */
        const val HiddenKey = "gradey.recap.hidden.v1"

        /** Opening the story waits at most this long for timetable weeks; the slide is left out after that. */
        const val TimetableTimeoutMillis = 10_000L
    }
}
