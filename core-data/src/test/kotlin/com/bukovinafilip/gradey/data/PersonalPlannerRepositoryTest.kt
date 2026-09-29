package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerSubjectReference
import com.bukovinafilip.gradey.model.PlannerLessonReference
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PersonalPlannerRepositoryTest {
    private class Storage : PlannerStorage {
        var encoded: String? = null
        var failWrites = false
        var writes = 0
        override fun read() = encoded
        override fun write(encoded: String) { check(!failWrites) { "disk full" }; this.encoded = encoded; writes++ }
    }

    private fun referenced(scope: String) = PersonalPlannerItem(
        title = "School test",
        subject = PlannerSubjectReference(scope, "subject-id", "Mathematics"),
        lesson = PlannerLessonReference(scope, "lesson-id", "2026-10-01", "1", "subject-id", listOf("A"), hourCaption = "1"),
        calendarID = 7, calendarTitle = "School",
    )

    @Test fun `same school migration rebinds coherent references in one write and preserves personal metadata`() = runTest {
        val storage = Storage()
        val repository = AndroidPersonalPlannerRepository(storage)
        val matching = referenced("guest")
        val unrelated = referenced("another").copy(id = "other")
        val conflict = referenced("guest").copy(id = "conflict", lesson = referenced("another").lesson)
        val personal = PersonalPlannerItem(id = "personal", title = "Shopping")
        listOf(matching, unrelated, conflict, personal).forEach { repository.save(it) }
        val before = repository.load()
        val count = storage.writes
        val rebound = repository.rebindSchoolScope("guest", "linked")
        assertEquals(count + 1, storage.writes)
        assertEquals(before.first().copy(subject = before.first().subject!!.copy(scope = "linked"), lesson = before.first().lesson!!.copy(scope = "linked")), rebound.first())
        assertEquals(before.drop(1), rebound.drop(1))
        assertEquals(rebound, AndroidPersonalPlannerRepository(storage).load())
        repository.rebindSchoolScope("guest", "linked")
        assertEquals(count + 1, storage.writes)
    }

    @Test fun `failed migration preserves both disk and visible original scopes`() = runTest {
        val storage = Storage()
        val repository = AndroidPersonalPlannerRepository(storage)
        repository.save(referenced("guest"))
        val original = repository.load()
        val encoded = storage.encoded
        storage.failWrites = true
        assertTrue(runCatching { repository.rebindSchoolScope("guest", "linked") }.isFailure)
        assertEquals(original, repository.load())
        assertEquals(encoded, storage.encoded)
        assertEquals(original, AndroidPersonalPlannerRepository(storage).load())
    }

    @Test fun `saved items survive repository recreation completion and deletion`() = runTest {
        val storage = Storage()
        val first = AndroidPersonalPlannerRepository(storage)
        val item = PersonalPlannerItem(title = "Prepare presentation", notes = "Chapter 2", dueDate = "2026-10-01")
        first.save(item)
        val reopened = AndroidPersonalPlannerRepository(storage)
        assertEquals(item.title, reopened.load().single().title)
        assertTrue(reopened.setCompleted(item.id, true).single().isCompleted)
        assertTrue(AndroidPersonalPlannerRepository(storage).load().single().isCompleted)
        assertTrue(reopened.delete(item.id).isEmpty())
        assertTrue(AndroidPersonalPlannerRepository(storage).load().isEmpty())
    }

    @Test fun `corrupt storage blocks editing and preserves original payload`() = runTest {
        val storage = Storage().apply { encoded = "corrupt" }
        val repository = AndroidPersonalPlannerRepository(storage)
        try { repository.load(); fail("Expected corruption") } catch (_: Exception) { }
        try { repository.save(PersonalPlannerItem(title = "New")); fail("Must not overwrite unreadable data") } catch (_: Exception) { }
        assertEquals("corrupt", storage.encoded)
    }

    @Test fun `failed durable save does not publish edited state`() = runTest {
        val storage = Storage()
        val repository = AndroidPersonalPlannerRepository(storage)
        val item = PersonalPlannerItem(title = "Original")
        repository.save(item)
        val before = storage.encoded
        storage.failWrites = true
        try { repository.save(item.copy(title = "Unsaved edit")); fail("Expected write failure") } catch (_: IllegalStateException) { }
        assertEquals("Original", repository.load().single().title)
        assertEquals(before, storage.encoded)
    }

    @Test fun `personal entries survive other school entries and updates preserve creation timestamp`() = runTest {
        val repository = AndroidPersonalPlannerRepository(Storage())
        val item = PersonalPlannerItem(title = "Personal note", createdAtEpochMillis = 1000)
        repository.save(item)
        repository.save(PersonalPlannerItem(title = "School homework"))
        val edited = repository.save(item.copy(title = "Edited note", createdAtEpochMillis = 9999)).first { it.id == item.id }
        assertEquals(1000L, edited.createdAtEpochMillis)
        assertEquals(2, repository.load().size)
    }
}
