package com.bukovinafilip.gradey

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.os.Build
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import androidx.test.platform.app.InstrumentationRegistry
import com.bukovinafilip.gradey.data.AndroidPlannerCalendarAdapter
import com.bukovinafilip.gradey.domain.PlannerCalendarExport
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerCalendarMapping
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Uses an isolated LOCAL calendar on an emulator, never an existing calendar or cloud account. */
class PlannerCalendarProviderTest {
    @Test fun nativeProviderSupportsOwnedRecoveryUpdateAndRemovalWithoutTouchingForeignEvents() = runBlocking {
        assumeTrue(Build.HARDWARE in setOf("ranchu", "goldfish"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val resolver = context.contentResolver
        val account = "gradey-test-${UUID.randomUUID()}"
        val permissions = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        val grantedHere = permissions.filter { context.checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        grantedHere.forEach { instrumentation.uiAutomation.grantRuntimePermission(context.packageName, it) }
        val syncCalendars = Calendars.CONTENT_URI.buildUpon()
            .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
            .appendQueryParameter(Calendars.ACCOUNT_NAME, account)
            .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL).build()
        var calendarID: Long? = null
        try {
            calendarID = ContentUris.parseId(requireNotNull(resolver.insert(syncCalendars, ContentValues().apply {
                put(Calendars.ACCOUNT_NAME, account)
                put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                put(Calendars.NAME, account)
                put(Calendars.CALENDAR_DISPLAY_NAME, "Gradey test calendar")
                put(Calendars.CALENDAR_COLOR, 0xFF159B80.toInt())
                put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                put(Calendars.OWNER_ACCOUNT, account)
                put(Calendars.CALENDAR_TIME_ZONE, "Europe/Prague")
                put(Calendars.VISIBLE, 0)
                put(Calendars.SYNC_EVENTS, 1)
            })))
            val adapter = AndroidPlannerCalendarAdapter(context)
            assertTrue(adapter.calendars().any { it.id == calendarID })
            val item = PersonalPlannerItem(title = "Ownership fixture", dueDate = "2026-10-06", calendarID = calendarID)
            val event = requireNotNull(PlannerCalendarExport.event(item))
            val mapping = PlannerCalendarMapping(item.id, requireNotNull(calendarID), UUID.randomUUID().toString())
            val eventID = adapter.upsert(mapping, event)
            // Lost acknowledgment/process recreation must recover by marker, without inserting a duplicate.
            assertEquals(eventID, AndroidPlannerCalendarAdapter(context).upsert(mapping, event))
            val changed = requireNotNull(PlannerCalendarExport.event(item.copy(title = "Updated fixture")))
            assertEquals(eventID, adapter.upsert(mapping.copy(eventID = eventID), changed))
            resolver.query(ContentUris.withAppendedId(Events.CONTENT_URI, eventID), arrayOf(Events.TITLE, Events.ALL_DAY, Events.EVENT_TIMEZONE), null, null, null)!!.use {
                assertTrue(it.moveToFirst()); assertEquals("Updated fixture", it.getString(0)); assertEquals(1, it.getInt(1)); assertEquals("UTC", it.getString(2))
            }
            val foreignMapping = mapping.copy(itemID = UUID.randomUUID().toString(), ownershipToken = UUID.randomUUID().toString())
            val foreignID = adapter.upsert(foreignMapping, event)
            // A stale/recycled eventID cannot override the independent ownership token.
            adapter.remove(mapping.copy(eventID = foreignID))
            resolver.query(Events.CONTENT_URI, arrayOf(Events._ID), "${Events.CALENDAR_ID} = ? AND ${Events.DELETED} = 0", arrayOf(calendarID.toString()), null)!!.use {
                assertEquals(1, it.count); assertTrue(it.moveToFirst()); assertEquals(foreignID, it.getLong(0))
            }
            adapter.remove(foreignMapping.copy(eventID = foreignID))
            adapter.remove(foreignMapping.copy(eventID = foreignID))
        } finally {
            calendarID?.let { resolver.delete(ContentUris.withAppendedId(syncCalendars, it), null, null) }
            // Permission revocation kills the instrumented process; the emulator runner resets it after the suite.
        }
    }
}
