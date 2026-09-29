package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import java.io.IOException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PlannerCalendarMirrorTest {
    private val item = PersonalPlannerItem(id = "test-item", title = "Math test", dueDate = "2026-10-01", calendarID = 7, calendarTitle = "School")

    @Test fun nothingIsExportedWithoutExplicitItemOptIn() = runTest {
        val fixture = Fixture(item.copy(calendarID = null))
        assertEquals(0, fixture.mirror.synchronize().pending)
        assertEquals(0, fixture.adapter.mutations)
        assertTrue(fixture.storage.state.mappings.isEmpty())
    }

    @Test fun permissionDenialKeepsLocalIntentAndUnattemptedDisableNeedsNoAccess() = runTest {
        val f = Fixture(item)
        f.adapter.access = false
        val report = f.mirror.synchronize()
        assertTrue(report.needsPermission)
        assertEquals(1, report.pending)
        assertFalse(f.storage.state.mappings.single().attempted)
        assertEquals(item, f.repository.items.single())
        f.repository.items = listOf(item.copy(calendarID = null))
        assertEquals(0, f.mirror.synchronize().pending)
        assertTrue(f.storage.state.mappings.isEmpty())
        assertEquals(0, f.adapter.mutations)
    }

    @Test fun editsCompletionAndDeletionAffectOnlyOwnedEvent() = runTest {
        val f = Fixture(item)
        f.adapter.events[100] = FakeEvent("foreign", 7, "Other event")
        f.mirror.synchronize()
        val id = f.storage.state.mappings.single().eventID!!
        f.repository.items = listOf(item.copy(title = "Updated test", isCompleted = true))
        f.mirror.synchronize()
        assertTrue(f.adapter.events[id]!!.title.contains("✓ Updated test"))
        assertEquals(2, f.adapter.events.size)
        f.repository.items = emptyList()
        f.mirror.synchronize()
        assertEquals(setOf(100L), f.adapter.events.keys)
        assertTrue(f.storage.state.mappings.isEmpty())
    }

    @Test fun revokedPermissionRetainsMinimalDeletionTombstoneUntilAuthorizedRetry() = runTest {
        val f = Fixture(item)
        f.mirror.synchronize()
        f.adapter.access = false
        f.repository.items = emptyList()
        assertEquals(1, f.mirror.synchronize().pending)
        assertEquals(1, f.storage.state.mappings.size)
        assertEquals(1, f.adapter.events.size)
        f.adapter.access = true
        assertEquals(0, f.mirror.synchronize().pending)
        assertTrue(f.storage.state.mappings.isEmpty())
        assertTrue(f.adapter.events.isEmpty())
    }

    @Test fun failedOwnershipPersistenceDoesNotTouchCalendar() = runTest {
        val f = Fixture(item)
        f.storage.failAtWrite = 2 // Ownership is saved, attempted flag cannot be committed.
        assertTrue(runCatching { f.mirror.synchronize() }.exceptionOrNull() is IOException)
        assertEquals(0, f.adapter.mutations)
        assertFalse(f.storage.state.mappings.single().attempted)
    }

    @Test fun interruptedIdentifierPersistenceRecoversMarkerWithoutDuplicateInsert() = runTest {
        val f = Fixture(item)
        f.storage.failAtWrite = 3 // Calendar saved; identifier acknowledgment fails.
        assertTrue(runCatching { f.mirror.synchronize() }.isFailure)
        assertEquals(1, f.adapter.events.size)
        assertNull(f.storage.state.mappings.single().eventID)
        assertTrue(f.storage.state.mappings.single().attempted)
        f.storage.failAtWrite = null
        assertEquals(0, f.mirror.synchronize().pending)
        assertEquals(1, f.adapter.events.size)
        assertNotNull(f.storage.state.mappings.single().eventID)
    }

    @Test fun calendarSwitchWaitsForOldEventCleanup() = runTest {
        val f = Fixture(item)
        f.mirror.synchronize()
        f.repository.items = listOf(item.copy(calendarID = 8))
        f.adapter.failRemoval = true
        assertEquals(1, f.mirror.synchronize().pending)
        assertEquals(7L, f.adapter.events.values.single().calendarID)
        f.adapter.failRemoval = false
        assertEquals(0, f.mirror.synchronize().pending)
        assertEquals(8L, f.adapter.events.values.single().calendarID)
        assertEquals(1, f.adapter.events.size)
    }

    @Test fun missingOrRecycledEventIsRecreatedOnEditWithoutOverwritingForeignRecord() = runTest {
        val f = Fixture(item)
        f.mirror.synchronize()
        val id = f.storage.state.mappings.single().eventID!!
        f.adapter.events[id] = FakeEvent("foreign", 7, "Unrelated")
        f.repository.items = listOf(item.copy(title = "Edited"))
        f.mirror.synchronize()
        assertEquals("Unrelated", f.adapter.events[id]!!.title)
        assertEquals(2, f.adapter.events.size)
        assertNotEquals(id, f.storage.state.mappings.single().eventID)
    }

    @Test fun missingDateStaysLocalAndNeverCreatesAnInvalidEvent() = runTest {
        val f = Fixture(item.copy(dueDate = null))
        assertEquals(1, f.mirror.synchronize().pending)
        assertTrue(f.adapter.events.isEmpty())
    }

    @Test fun unchangedItemDoesNotKeepWritingToCalendar() = runTest {
        val f = Fixture(item)
        f.mirror.synchronize()
        val writes = f.adapter.mutations
        f.mirror.synchronize()
        assertEquals(writes, f.adapter.mutations)
    }

    private class Fixture(item: PersonalPlannerItem) {
        val repository = MemoryRepository(listOf(item))
        val storage = MemoryStorage()
        val adapter = FakeAdapter()
        val mirror = PlannerCalendarMirror(repository, storage, adapter)
    }
    private class MemoryRepository(var items: List<PersonalPlannerItem>) : PersonalPlannerRepository {
        override suspend fun load() = items
        override suspend fun save(item: PersonalPlannerItem): List<PersonalPlannerItem> = (items.filterNot { it.id == item.id } + item).also { items = it }
        override suspend fun delete(id: String): List<PersonalPlannerItem> = items.filterNot { it.id == id }.also { items = it }
        override suspend fun setCompleted(id: String, completed: Boolean): List<PersonalPlannerItem> = items.map { if (it.id == id) it.copy(isCompleted = completed) else it }.also { items = it }
    }
    private class MemoryStorage : PlannerCalendarStateStore {
        var state = PlannerCalendarState()
        var writes = 0
        var failAtWrite: Int? = null
        override suspend fun read() = state
        override suspend fun write(state: PlannerCalendarState) {
            if (++writes == failAtWrite) throw IOException("disk full")
            this.state = state
        }
    }
    private data class FakeEvent(val token: String, val calendarID: Long, val title: String)
    private class FakeAdapter : PlannerCalendarAdapter {
        var access = true
        var failRemoval = false
        var mutations = 0
        var nextID = 1L
        val events = mutableMapOf<Long, FakeEvent>()
        override val hasAccess get() = access
        override suspend fun calendars() = listOf(PlannerWritableCalendar(7, "School", "local"), PlannerWritableCalendar(8, "Personal", "local"))
        override suspend fun upsert(mapping: PlannerCalendarMapping, event: PlannerCalendarEvent): Long {
            check(access)
            mutations++
            val existing = events.entries.firstOrNull { it.value.token == mapping.ownershipToken && it.value.calendarID == mapping.calendarID }?.key
            val id = existing ?: nextID++
            events[id] = FakeEvent(mapping.ownershipToken, mapping.calendarID, event.title)
            return id
        }
        override suspend fun remove(mapping: PlannerCalendarMapping) {
            check(access)
            if (failRemoval) throw IOException("calendar unavailable")
            mutations++
            events.entries.removeAll { it.value.token == mapping.ownershipToken && it.value.calendarID == mapping.calendarID }
        }
    }
}
