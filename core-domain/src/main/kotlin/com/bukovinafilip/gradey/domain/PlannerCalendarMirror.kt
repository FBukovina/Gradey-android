package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface PlannerCalendarStateStore {
    suspend fun read(): PlannerCalendarState
    /** Must commit atomically or throw; never turn unreadable state into an empty mapping. */
    suspend fun write(state: PlannerCalendarState)
}

interface PlannerCalendarAdapter {
    val hasAccess: Boolean
    suspend fun calendars(): List<PlannerWritableCalendar>
    /** Only update/recover an event after checking the exact ownership marker. */
    suspend fun upsert(mapping: PlannerCalendarMapping, event: PlannerCalendarEvent): Long
    /** Missing events are harmless. Never remove an event with a different marker. */
    suspend fun remove(mapping: PlannerCalendarMapping)
}

data class PlannerCalendarReport(val synced: Int, val pending: Int, val needsPermission: Boolean)

/** One-way, explicit opt-in mirror. Every external mutation is preceded by durable ownership state. */
class PlannerCalendarMirror(
    private val repository: PersonalPlannerRepository,
    private val storage: PlannerCalendarStateStore,
    private val adapter: PlannerCalendarAdapter,
) {
    private val mutex = Mutex()
    val hasAccess: Boolean get() = adapter.hasAccess
    suspend fun calendars(): List<PlannerWritableCalendar> = adapter.calendars()

    suspend fun synchronize(): PlannerCalendarReport = mutex.withLock {
        var state = storage.read()
        check(state.version == 1 && state.mappings.map { it.itemID }.distinct().size == state.mappings.size) { "Calendar export state could not be read safely." }
        check(state.mappings.all { it.itemID.isNotBlank() && it.ownershipToken.isNotBlank() && it.calendarID > 0 }) { "Calendar export state could not be read safely." }
        var pending = 0
        var synced = 0
        suspend fun save(rows: List<PlannerCalendarMapping>) {
            val next = state.copy(mappings = rows)
            storage.write(next)
            state = next
        }
        suspend fun removeMapping(row: PlannerCalendarMapping) = save(state.mappings.filterNot { it.itemID == row.itemID })
        // A removed item leaves just this mapping as a cleanup tombstone.
        for (row in state.mappings.toList()) {
            val item = repository.load().firstOrNull { it.id == row.itemID }
            if (item?.calendarID == row.calendarID) continue
            if (!row.attempted) { removeMapping(row); continue }
            if (!adapter.hasAccess) { pending++; continue }
            try {
                adapter.remove(row)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { pending++; continue }
            // A failed acknowledgment keeps the tombstone so a retry can confirm removal.
            removeMapping(row)
        }
        for (candidate in repository.load().filter { it.calendarID != null }) {
            val item = repository.load().firstOrNull { it.id == candidate.id && it.calendarID == candidate.calendarID } ?: continue
            val calendarID = requireNotNull(item.calendarID)
            val event = PlannerCalendarExport.event(item)
            if (event == null) { pending++; continue }
            val existing = state.mappings.firstOrNull { it.itemID == item.id }
            // A previous calendar must finish cleanup before exporting to another one.
            if (existing != null && existing.calendarID != calendarID) continue
            var row = existing ?: PlannerCalendarMapping(item.id, calendarID, UUID.randomUUID().toString()).also {
                save(state.mappings + it)
            }
            val revision = revision(event)
            if (row.syncedRevision == revision) { synced++; continue }
            if (!adapter.hasAccess) { pending++; continue }
            if (!row.attempted) {
                val attempted = row.copy(attempted = true)
                save(state.mappings.map { if (it.itemID == attempted.itemID) attempted else it })
                row = attempted
            }
            val eventID = try { adapter.upsert(row, event) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { pending++; continue }
            // If this commit fails, the marker recovers the already-created event on retry.
            val saved = row.copy(eventID = eventID, syncedRevision = revision)
            save(state.mappings.map { if (it.itemID == saved.itemID) saved else it })
            synced++
        }
        PlannerCalendarReport(synced, pending, pending > 0 && !adapter.hasAccess)
    }

    private fun revision(event: PlannerCalendarEvent): String {
        val fields = listOf(event.title, event.description, event.start.toString(), event.end.toString(), event.allDay.toString(), event.timeZoneID)
        val canonical = fields.joinToString("") { "${it.length}:$it" }
        return MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
