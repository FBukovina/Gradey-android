package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.GradeyWearLessonSelection
import com.bukovinafilip.gradey.model.GradeyWearSyncPayload

data class WearSurfaceInterval(val startEpochMillis: Long, val endEpochMillis: Long, val selection: GradeyWearLessonSelection)

/** Predictable transitions without waking the watch at every lesson boundary. */
object WearSurfaceTimeline {
    private const val HORIZON_MILLIS = 24 * 60 * 60_000L
    fun build(payload: GradeyWearSyncPayload?, now: Long): List<WearSurfaceInterval> {
        val timetable = payload?.takeIf { it.isSignedIn }?.timetable
        val end = now + HORIZON_MILLIS
        val boundaries = sortedSetOf(now, end)
        timetable?.days.orEmpty().flatMap { it.lessons }.forEach { lesson ->
            listOfNotNull(lesson.startEpochMillis, lesson.endEpochMillis).forEach { if (it in (now + 1) until end) boundaries += it }
        }
        timetable?.let { cache ->
            val expires = cache.cachedAtEpochMillis + WearLessonSelector.StaleIntervalMillis + 1
            if (expires in (now + 1) until end) boundaries += expires
        }
        return boundaries.toList().zipWithNext().map { (start, finish) ->
            WearSurfaceInterval(start, finish, WearLessonSelector.select(timetable, start))
        }
    }
}
