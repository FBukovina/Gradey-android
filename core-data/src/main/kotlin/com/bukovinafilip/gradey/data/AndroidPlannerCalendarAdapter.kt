package com.bukovinafilip.gradey.data

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import com.bukovinafilip.gradey.domain.PlannerCalendarAdapter
import com.bukovinafilip.gradey.domain.PlannerCalendarEvent
import com.bukovinafilip.gradey.model.PlannerCalendarMapping
import com.bukovinafilip.gradey.model.PlannerWritableCalendar
import java.time.ZoneOffset
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reads only writable-calendar metadata and exact Gradey ownership tokens. Never imports calendar events. */
class AndroidPlannerCalendarAdapter(context: Context) : PlannerCalendarAdapter {
    private val context = context.applicationContext
    private val resolver get() = context.contentResolver
    override val hasAccess: Boolean get() = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        .all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override suspend fun calendars(): List<PlannerWritableCalendar> = withContext(Dispatchers.IO) {
        requireAccess()
        val result = mutableListOf<PlannerWritableCalendar>()
        resolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.CALENDAR_DISPLAY_NAME, Calendars.ACCOUNT_NAME),
            "${Calendars.CALENDAR_ACCESS_LEVEL} >= ?", arrayOf(Calendars.CAL_ACCESS_CONTRIBUTOR.toString()), "${Calendars.CALENDAR_DISPLAY_NAME} ASC")?.use { rows ->
            while (rows.moveToNext()) result += PlannerWritableCalendar(rows.getLong(0), rows.getString(1).orEmpty(), rows.getString(2).orEmpty())
        } ?: error("Calendar metadata could not be read.")
        result
    }

    override suspend fun upsert(mapping: PlannerCalendarMapping, event: PlannerCalendarEvent): Long = withContext(Dispatchers.IO) {
        requireAccess()
        check(calendars().any { it.id == mapping.calendarID }) { "The selected calendar is not writable." }
        val marker = marker(mapping)
        val values = ContentValues().apply {
            put(Events.CALENDAR_ID, mapping.calendarID)
            put(Events.TITLE, event.title)
            put(Events.DESCRIPTION, event.description + "\n\n" + marker)
            // Android Calendar Provider requires UTC-midnight for all-day events.
            put(Events.DTSTART, if (event.allDay) requireNotNull(event.date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else event.start.toEpochMilli())
            put(Events.DTEND, if (event.allDay) requireNotNull(event.date).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else event.end.toEpochMilli())
            put(Events.ALL_DAY, if (event.allDay) 1 else 0)
            put(Events.EVENT_TIMEZONE, if (event.allDay) "UTC" else event.timeZoneID)
            put(Events.CUSTOM_APP_PACKAGE, context.packageName)
            put(Events.CUSTOM_APP_URI, marker)
        }
        val existing = findOwned(mapping)
        if (existing != null) {
            // The provider rejects a selection on an individual event URI. Use the collection
            // with an exact ID plus ownership predicates to retain an atomic conditional update.
            val count = resolver.update(Events.CONTENT_URI, values,
                "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND ${Events.CUSTOM_APP_URI} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.DESCRIPTION} = ?",
                arrayOf(existing.id.toString(), mapping.calendarID.toString(), marker, context.packageName, existing.description))
            check(count == 1) { "The calendar event changed before it could be updated." }
            existing.id
        } else {
            val inserted = resolver.insert(Events.CONTENT_URI, values) ?: error("The calendar event could not be saved.")
            ContentUris.parseId(inserted)
        }
    }

    override suspend fun remove(mapping: PlannerCalendarMapping) = withContext(Dispatchers.IO) {
        requireAccess()
        val owned = findOwned(mapping) ?: return@withContext
        check(calendars().any { it.id == mapping.calendarID }) { "The selected calendar is not writable." }
        val deleted = resolver.delete(Events.CONTENT_URI,
            "${Events._ID} = ? AND ${Events.CALENDAR_ID} = ? AND ${Events.CUSTOM_APP_URI} = ? AND ${Events.CUSTOM_APP_PACKAGE} = ? AND ${Events.DESCRIPTION} = ?",
            arrayOf(owned.id.toString(), mapping.calendarID.toString(), marker(mapping), context.packageName, owned.description))
        check(deleted == 1 || findOwned(mapping) == null) { "The owned event changed before it could be removed." }
        Unit
    }

    private data class OwnedEvent(val id: Long, val description: String)

    private fun findOwned(mapping: PlannerCalendarMapping): OwnedEvent? {
        val marker = marker(mapping)
        val events = mutableListOf<OwnedEvent>()
        resolver.query(Events.CONTENT_URI, arrayOf(Events._ID, Events.DESCRIPTION, Events.CUSTOM_APP_PACKAGE),
            "${Events.CALENDAR_ID} = ? AND ${Events.CUSTOM_APP_URI} = ? AND ${Events.DELETED} = 0", arrayOf(mapping.calendarID.toString(), marker), null)?.use { rows ->
            while (rows.moveToNext()) {
                val description = rows.getString(1).orEmpty()
                if (rows.getString(2) == context.packageName && description.lineSequence().any { it == marker }) events += OwnedEvent(rows.getLong(0), description)
            }
        } ?: error("The owned calendar event could not be read.")
        // Never guess between duplicate ownership markers or mutate a foreign event under a recycled ID.
        events.firstOrNull { it.id == mapping.eventID }?.let { return it }
        check(events.size <= 1) { "More than one calendar event has this ownership marker." }
        return events.singleOrNull()
    }

    private fun marker(mapping: PlannerCalendarMapping) = "https://gradey.app/planner/${mapping.ownershipToken}"
    private fun requireAccess() { check(hasAccess) { "Calendar access is required to update exported events." } }
}
