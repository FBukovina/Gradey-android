package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class CompanionSnapshotsTest {
    @Test fun cancelsAreExcludedAndPhoneWatchMatch() {
        val week = DemoData.timetableFor("2026-09-21")
        val day = week.days.first { it.lessons.isNotEmpty() }
        val canceled = day.lessons.first().copy(changeKind = LessonChangeKind.CANCELED)
        val input = week.copy(days = listOf(day.copy(lessons = listOf(canceled) + day.lessons.drop(1))))
        val widget = CompanionSnapshots.widget(input, 42)
        val watch = CompanionSnapshots.wear(input, null, 42)
        assertThat(widget.lessons.map { it.id }).doesNotContain(canceled.id)
        assertThat(watch.timetable!!.days.flatMap { it.lessons }.map { it.id }).containsExactlyElementsIn(widget.lessons.map { it.id })
        assertThat(watch.auth).isNull()
    }

    @Test fun schoolTimesUsePragueTimezone() {
        val week = DemoData.timetableFor("2026-09-21")
        val snapshot = CompanionSnapshots.widget(week, 42)
        val day = week.days.first { it.lessons.isNotEmpty() }
        val expected = LocalDate.parse(day.date!!.take(10)).atStartOfDay(ZoneId.of("Europe/Prague")).toInstant().toEpochMilli()
        assertThat(snapshot.lessons.first().dayStartEpochMillis).isEqualTo(expected)
    }

    @Test fun exactEndChoosesUpcomingAndSkipsCanceled() {
        val first = NextLessonWidgetLesson("first", 0, 100, 200)
        val cancel = first.copy(id = "cancel", startEpochMillis = 201, endEpochMillis = 250, changeKind = NextLessonWidgetChangeKind.CANCELED)
        val second = first.copy(id = "second", startEpochMillis = 300, endEpochMillis = 400)
        val selection = NextLessonSelector.select(NextLessonWidgetSnapshot(0, listOf(first, cancel, second)), 200) as NextLessonWidgetSelection.Lesson
        assertThat(selection.lesson.id).isEqualTo("second")
        assertThat(selection.timing).isEqualTo(NextLessonWidgetTiming.UPCOMING)
    }

    @Test fun accountAndStudentCachesAreIsolatedWithoutEmbeddingCredentials() {
        val session = StoredSession("token", "refresh", "Bearer", Long.MAX_VALUE, "https://school.example/path", bakalari = BakalariCredentials("alice", "secret"))
        assertThat(session.cacheScope).isNotEqualTo(session.copy(bakalari = BakalariCredentials("bob", "secret")).cacheScope)
        assertThat(session.cacheScope).isNotEqualTo(session.copy(baseURL = "https://school.example/other").cacheScope)
        assertThat(session.cacheScope).isEqualTo(session.copy(accessToken = "new").cacheScope)
        assertThat(session.cacheScope).doesNotContain("alice")
        val parent = session.copy(provider = SchoolProvider.EDU_PAGE, linkedAccountID = "parent", eduPage = EduPageSessionData("session", "parent", "secret", "hash", "id", activeStudent = SchoolStudentProfile("one", "Child One")))
        assertThat(parent.cacheScope).isNotEqualTo(parent.copy(eduPage = parent.eduPage!!.copy(activeStudent = SchoolStudentProfile("two", "Child Two"))).cacheScope)
    }
    @Test fun overnightLessonEndsOnFollowingSchoolDayAndUsesInjectedClock() {
        val week = DemoData.timetableFor("2026-09-21")
        val original = week.days.first { it.lessons.isNotEmpty() }
        val lesson = original.lessons.first().copy(hour = TimetableHour("night", "1", "23:30", "00:15"))
        val day = original.copy(date = "2026-09-21", lessons = listOf(lesson))
        val now = LocalDate.parse("2026-09-21").atTime(23, 45).atZone(ZoneId.of("Europe/Prague")).toInstant().toEpochMilli()
        val widget = CompanionSnapshots.widget(week.copy(days = listOf(day)), now)
        val payload = CompanionSnapshots.wear(week.copy(days = listOf(day)), null, now)
        assertThat(widget.lessons.single().endEpochMillis!! - widget.lessons.single().startEpochMillis!!).isEqualTo(45 * 60_000L)
        assertThat((NextLessonSelector.select(widget, now) as NextLessonWidgetSelection.Lesson).timing).isEqualTo(NextLessonWidgetTiming.CURRENT)
        assertThat(payload.timetable!!.days.single().isToday).isTrue()
    }

}
