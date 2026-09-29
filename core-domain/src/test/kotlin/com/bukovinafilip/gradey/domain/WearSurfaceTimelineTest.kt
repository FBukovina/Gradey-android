package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.UUID

class WearSurfaceTimelineTest {
    private val now = 1_000_000L
    private fun payload(cached: Long = now): GradeyWearSyncPayload {
        val lesson = GradeyWearTimetableLesson("lesson", 0, now + 10_000, now + 20_000, subjectAbbrev = "MAT")
        return GradeyWearSyncPayload(generatedAtEpochMillis = now, isSignedIn = true, timetable = GradeyWearTimetable(cachedAtEpochMillis = cached, weekStart = "2026-09-28", days = listOf(GradeyWearTimetableDay(id = "day", dayStartEpochMillis = 0, weekdayTitle = "Monday", isToday = true, isSchoolDay = true, lessons = listOf(lesson)))))
    }
    @Test fun exactLessonBoundariesTransitionWithoutFutureNetworkRequests() {
        val values = WearSurfaceTimeline.build(payload(), now)
        assertThat(values.map { it.startEpochMillis }).containsExactly(now, now + 10_000, now + 20_000).inOrder()
        assertThat((values[0].selection as GradeyWearLessonSelection.Lesson).timing).isEqualTo(NextLessonWidgetTiming.UPCOMING)
        assertThat((values[1].selection as GradeyWearLessonSelection.Lesson).timing).isEqualTo(NextLessonWidgetTiming.CURRENT)
        assertThat(values.last().selection).isEqualTo(GradeyWearLessonSelection.NoLessons)
        assertThat(values.last().endEpochMillis).isEqualTo(now + 24 * 60 * 60_000)
    }
    @Test fun signoutAndExpiredCacheNeverPublishOldLesson() {
        assertThat(WearSurfaceTimeline.build(payload().copy(isSignedIn = false), now).single().selection).isEqualTo(GradeyWearLessonSelection.NoTimetable)
        val expires = now - WearLessonSelector.StaleIntervalMillis + 5_000
        val timeline = WearSurfaceTimeline.build(payload(expires), now)
        assertThat(timeline.any { it.startEpochMillis == now + 5_001 && it.selection == GradeyWearLessonSelection.Stale }).isTrue()
    }
    @Test fun relayBoundsIdentityTimeUnicodeAndOpaqueConversationIDs() {
        val owner = WearAIIdentity("owner", "salted-school")
        val request = WearAIRequest(requestID = UUID.randomUUID().toString(), clientMessageID = UUID.randomUUID().toString(), identity = owner, createdAtEpochMillis = now, text = "🍎".repeat(2_000), conversationID = "Firestore_Auto-id")
        assertThat(WearAIProtocol.valid(request, owner, now)).isTrue()
        assertThat(WearAIProtocol.valid(request.copy(text = request.text + "x"), owner, now)).isFalse()
        assertThat(WearAIProtocol.valid(request, owner.copy(ownerID = "other"), now)).isFalse()
        assertThat(WearAIProtocol.valid(request, owner, now + 120_001)).isFalse()
        assertThat(WearAIProtocol.valid(request.copy(requestID = "short"), owner, now)).isFalse()
        assertThat(WearAIProtocol.valid(request.copy(conversationID = "unsafe/path"), owner, now)).isFalse()
    }
}
