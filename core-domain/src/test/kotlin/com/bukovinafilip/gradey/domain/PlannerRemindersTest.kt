package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.time.*
import org.junit.Assert.*
import org.junit.Test

class PlannerRemindersTest {
    private val zone = ZoneId.of("Europe/Prague")
    private val now = Instant.parse("2026-09-29T15:00:00Z")
    private fun item(id: String = "one") = PersonalPlannerItem(id = id, title = "Prepare", dueDate = "2026-09-30", timeZoneID = zone.id)

    @Test fun schedulesOneDailySummaryAndExcludesNotesCompletedAndOtherAccounts() {
        val other = item("other").copy(subject = PlannerSubjectReference("other-school", "m", "Math"))
        val reminders = PlannerReminders.make(listOf(item(), item("done").copy(isCompleted = true), item("note").copy(type = PlannerItemType.NOTE), other), "school", NotificationPreferences(), now, zone)
        assertEquals(1, reminders.size)
        assertEquals(listOf("one"), reminders.single().items.map { it.id })
        assertEquals(Instant.parse("2026-09-29T16:00:00Z"), reminders.single().fireAt)
    }

    @Test fun openingAfterEighteenDoesNotTriggerLateCatchUp() {
        assertTrue(PlannerReminders.make(listOf(item()), null, NotificationPreferences(), Instant.parse("2026-09-29T16:01:00Z"), zone).isEmpty())
    }

    @Test fun quietDeferralDropsDateOnlyEventsThatWouldAlreadyBeDue() {
        val prefs = NotificationPreferences(quietHoursEnabled = true, quietHoursStart = "17:00", quietHoursEnd = "07:00")
        val timed = item("timed").copy(dueTime = "08:00")
        val result = PlannerReminders.make(listOf(item(), timed), null, prefs, now, zone).single()
        assertEquals(listOf("timed"), result.items.map { it.id })
        assertEquals(Instant.parse("2026-09-30T05:00:00Z"), result.fireAt)
    }

    @Test fun dateOnlyItemsKeepTheirAuthoredDateAcrossTimeZones() {
        val travelItem = item().copy(timeZoneID = "America/Los_Angeles")
        assertEquals("2026-09-30", PlannerReminders.make(listOf(travelItem), null, NotificationPreferences(), now, zone).single().day)
    }
}
