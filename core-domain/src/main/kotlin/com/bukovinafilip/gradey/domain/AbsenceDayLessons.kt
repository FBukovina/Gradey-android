package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.Absence
import com.bukovinafilip.gradey.model.AbsenceDayOverride
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.TimetableWeek
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.LocalDate

/** One lesson behind a day of absence. [isHidden] marks a lesson a local correction removed from the totals. */
data class AbsenceDayLesson(val id: String, val hourCaption: String, val timeRange: String, val subjectName: String, val isHidden: Boolean)

/** The subjects behind one day of absence, so the Days list can show what was missed (iOS `AbsenceDayLessons`). */
data class AbsenceDayLessons(val dateKey: String, val status: Status, val lessons: List<AbsenceDayLesson>) {
    sealed interface Status {
        data object Resolved : Status

        /** Fewer lessons were missed than the day had, and which ones is not known yet. */
        data class NeedsSelection(val required: Int) : Status
    }
}

/** Works out which lessons a day of absence covers (iOS `AbsenceDayLessonResolver`). */
object AbsenceDayLessonResolver {
    /**
     * @param timetableLessons the day's countable lessons, or null when its timetable is not available.
     * @param selectedLessonIDs lessons the user picked for a partly missed day.
     * @return null when nothing can be said about the day.
     */
    fun resolve(
        day: Absence,
        timetableLessons: List<ScheduledLesson>?,
        selectedLessonIDs: Set<String> = emptySet(),
        activeOverride: AbsenceDayOverride? = null,
    ): AbsenceDayLessons? {
        val dateKey = AbsenceOverrideProjection.dateKey(day.date)
        // A confirmed correction replaces inference, even without a timetable.
        if (activeOverride != null) {
            val lessonsByID = timetableLessons.orEmpty().associateBy { it.id }
            val rows = activeOverride.allocations.mapIndexed { index, allocation ->
                val lesson = allocation.lessonID?.let(lessonsByID::get)
                Triple(index, lesson?.hour?.let(::hourOrder), AbsenceDayLesson(
                    id = allocation.id, hourCaption = lesson?.let(::hourCaption).orEmpty(), timeRange = lesson?.let(::timeRange).orEmpty(),
                    subjectName = allocation.subjectName, isHidden = allocation.id in activeOverride.hiddenAllocationIDs,
                ))
            }
            // Lessons with a known hour first, in order; the rest as they were saved.
            val sorted = rows.sortedWith(compareBy<Triple<Int, Int?, AbsenceDayLesson>> { it.second == null }.thenBy { it.second ?: 0 }.thenBy { it.first })
            return AbsenceDayLessons(dateKey, AbsenceDayLessons.Status.Resolved, sorted.map { it.third })
        }
        if (timetableLessons == null) return null
        val base = day.unsolved + day.ok + day.missed
        if (base <= 0) return AbsenceDayLessons(dateKey, AbsenceDayLessons.Status.Resolved, emptyList())
        if (timetableLessons.isEmpty()) return null
        if (base >= timetableLessons.size) return AbsenceDayLessons(dateKey, AbsenceDayLessons.Status.Resolved, timetableLessons.map(::row))
        val selected = timetableLessons.filter { it.id in selectedLessonIDs }
        if (selected.size == base) return AbsenceDayLessons(dateKey, AbsenceDayLessons.Status.Resolved, selected.map(::row))
        return AbsenceDayLessons(dateKey, AbsenceDayLessons.Status.NeedsSelection(base), emptyList())
    }

    /** The lessons of [dateKey] that count towards absence: not cancelled, with a subject, one per hour and subject. */
    fun countableLessons(week: TimetableWeek, dateKey: String): List<ScheduledLesson>? {
        val day = week.days.firstOrNull { it.date?.let(AbsenceOverrideProjection::dateKey) == dateKey } ?: return null
        val seen = HashSet<String>()
        return day.lessons
            .filter { it.changeKind != LessonChangeKind.CANCELED && !it.subjectName.isNullOrBlank() }
            .filter { seen.add("${it.hour.id}#${it.subjectID ?: AbsenceOverrideProjection.normalizedSubjectName(it.subjectName.orEmpty())}") }
            .sortedWith(compareBy<ScheduledLesson> { hourOrder(it.hour) }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.subjectName.orEmpty() })
    }

    /**
     * Every recorded day of [rawAbsence] that can be resolved, by date key. Paused corrections are ignored,
     * like on the totals.
     */
    fun resolveAll(rawAbsence: AbsenceResponse, weeks: List<TimetableWeek>, overrides: List<AbsenceDayOverride>): Map<String, AbsenceDayLessons> {
        val active = overrides.filter { it.pauseReason == null }.associateBy { it.dateKey }
        val result = LinkedHashMap<String, AbsenceDayLessons>()
        for (day in rawAbsence.absences) {
            val dateKey = AbsenceOverrideProjection.dateKey(day.date)
            if (dateKey in result) continue
            val lessons = weeks.firstNotNullOfOrNull { countableLessons(it, dateKey) }
            resolve(day, lessons, activeOverride = active[dateKey])?.let { result[dateKey] = it }
        }
        return result
    }

    /** The Mondays of the weeks [rawAbsence] has days in, oldest first. */
    fun weekStarts(rawAbsence: AbsenceResponse): List<LocalDate> = rawAbsence.absences
        .mapNotNull { runCatching { LocalDate.parse(AbsenceOverrideProjection.dateKey(it.date)) }.getOrNull() }
        .map(TimetableDates::monday).distinct().sorted()

    private fun row(lesson: ScheduledLesson) = AbsenceDayLesson(lesson.id, hourCaption(lesson), timeRange(lesson), lesson.subjectName.orEmpty(), isHidden = false)
    private fun hourOrder(hour: com.bukovinafilip.gradey.model.TimetableHour): Int = hour.id.toIntOrNull() ?: Int.MAX_VALUE
    private fun hourCaption(lesson: ScheduledLesson): String = lesson.hour.caption.ifBlank { lesson.hour.id }
    private fun timeRange(lesson: ScheduledLesson): String {
        val begin = lesson.hour.beginTime.trim()
        val end = lesson.hour.endTime.trim()
        return if (begin.isEmpty() || end.isEmpty()) "" else "$begin-$end"
    }
}

/** Loads timetable weeks for features that read many of them: saved ones first, the rest from the school, four at a time. */
object TimetableWeeksLoader {
    suspend fun load(repository: SchoolRepository, mondays: List<LocalDate>): List<TimetableWeek> = coroutineScope {
        val limit = Semaphore(4)
        mondays.map { monday ->
            async {
                val week = monday.toString()
                attempt { repository.loadCachedTimetable(week) } ?: limit.withPermit { attempt { repository.loadTimetable(week) } }
            }
        }.awaitAll().filterNotNull()
    }

    /** Null when a week cannot be loaded; the caller works with the weeks there are. */
    private suspend fun <T> attempt(block: suspend () -> T): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
}
