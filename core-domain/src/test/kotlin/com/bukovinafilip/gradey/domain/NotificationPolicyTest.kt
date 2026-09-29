package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.NotificationLockScreenDetail
import com.bukovinafilip.gradey.model.NotificationPreferences
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun overnightQuietHoursUseSelectedZoneAndExclusiveEnd() {
        val prefs = NotificationPreferences(quietHoursEnabled = true, quietHoursStart = "22:00", quietHoursEnd = "06:00", quietHoursTimeZoneIdentifier = "Europe/Prague")
        assertTrue(NotificationPolicy.isQuiet(prefs, Instant.parse("2026-09-29T20:00:00Z")))
        assertTrue(NotificationPolicy.isQuiet(prefs, Instant.parse("2026-09-30T03:59:00Z")))
        assertFalse(NotificationPolicy.isQuiet(prefs, Instant.parse("2026-09-30T04:00:00Z")))
        assertFalse(NotificationPolicy.isQuiet(prefs.copy(quietHoursEnabled = false), Instant.parse("2026-09-29T20:00:00Z")))
    }

    @Test fun privateLockScreenDoesNotExposePayloadEvenIfServerPreferenceIsStale() {
        val privateBody = NotificationPolicy.lockScreenBody(NotificationLockScreenDetail.PRIVATE_SUMMARY, "Math 5 · Failed algebra test", "Math", "5")
        assertFalse(privateBody.contains("Math"))
        assertFalse(privateBody.contains("Failed"))
        assertEquals("Math · 5", NotificationPolicy.lockScreenBody(NotificationLockScreenDetail.MARK_AND_SUBJECT, "Failed algebra test", "Math", "5"))
        assertFalse(NotificationPolicy.lockScreenBody(NotificationLockScreenDetail.MARK_AND_SUBJECT, "Failed algebra test", null, null).contains("Failed"))
    }
}
