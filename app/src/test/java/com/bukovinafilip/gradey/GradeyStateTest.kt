package com.bukovinafilip.gradey

import com.bukovinafilip.gradey.domain.GradeHistoryTrend
import com.bukovinafilip.gradey.model.*
import org.junit.Assert.*
import org.junit.Test

class GradeyStateTest {
    @Test fun accountSwitchRemovesAllPriorSchoolDataBeforeNewDataLoads() {
        val prior = GradeyState(
            phase = AppPhase.SIGNED_IN,
            account = GradeyAccount("gradey-owner"),
            dashboard = DashboardData(MarksResponse(), user = UserResponse("Other child")),
            absence = AbsenceResponse(percentageThreshold = 30.0),
            timetable = TimetableWeek("2026-09-21", emptyList(), emptyList()),
            currentTimetable = TimetableWeek("2026-09-21", emptyList(), emptyList()),
            history = listOf(GradeHistoryTrend("math", "Other child's maths", 0.5, 1)),
            plans = listOf(PlannedAbsence("2026-09-22", emptyList())),
            error = "Old account failure",
            preferences = NotificationPreferences(newMarksEnabled = false),
        )
        val session = StoredSession("new-token", "refresh", "Bearer", 99_999, "https://new-school.example/", linkedAccountID = "new-account")
        val next = prior.withSchool(session)
        assertEquals("new-account", next.school?.linkedAccountID)
        assertEquals("gradey-owner", next.account?.id)
        assertNull(next.dashboard.user)
        assertNull(next.timetable)
        assertNull(next.currentTimetable)
        assertTrue(next.absence.absences.isEmpty())
        assertNull(next.absence.percentageThreshold)
        assertTrue(next.history.isEmpty())
        assertTrue(next.plans.isEmpty())
        assertNull(next.error)
        assertFalse(next.preferences.newMarksEnabled)
        assertTrue(next.loading)
    }
}
