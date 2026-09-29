package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.model.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AbsenceOverrideRepositoryTest {
    private fun entry(scope: String = "owner:school", date: String = "2026-09-25") = AbsenceDayOverride(
        scope, date, Absence(date, ok = 1),
        listOf(AbsenceOverrideAllocation("unit", subjectName = "Math", category = AbsenceOverrideCategory.OK)), setOf("unit"),
    )

    @Test fun scopedCorrectionsSurviveRepositoryRecreationAndResetDoesNotEraseAnotherSchool() = runTest {
        val storage = Storage()
        val first = entry()
        val other = entry("another:school")
        val repository = AndroidAbsenceOverrideRepository(storage)
        repository.save(first)
        repository.save(other)
        val restored = AndroidAbsenceOverrideRepository(storage)
        assertEquals(listOf(first), restored.load(first.scope))
        assertEquals(emptyList<AbsenceDayOverride>(), restored.reset(first.scope))
        assertEquals(listOf(other), restored.load(other.scope))
    }

    @Test fun failedDurableWriteDoesNotAcknowledgeOrReplaceSavedCorrection() = runTest {
        val storage = Storage()
        val repository = AndroidAbsenceOverrideRepository(storage)
        val first = entry()
        repository.save(first)
        val originalFile = storage.encoded
        storage.failWrite = true
        assertTrue(runCatching { repository.restore(first.scope, first.dateKey) }.exceptionOrNull() is IOException)
        assertEquals(listOf(first), repository.load(first.scope))
        assertEquals(originalFile, storage.encoded)
    }

    @Test fun corruptionIsSurfacedAndKeptInsteadOfOverwrittenWithAnEmptyList() = runTest {
        val storage = Storage().apply { encoded = "broken saved correction" }
        val repository = AndroidAbsenceOverrideRepository(storage)
        assertTrue(runCatching { repository.load("owner:school") }.isFailure)
        assertTrue(runCatching { repository.save(entry()) }.isFailure)
        assertEquals("broken saved correction", storage.encoded)
    }

    @Test fun replacingWithAnotherAccountsCorrectionsIsRejected() = runTest {
        val storage = Storage()
        val repository = AndroidAbsenceOverrideRepository(storage)
        assertTrue(runCatching { repository.replace("owner:school", listOf(entry("another:school"))) }.isFailure)
        assertNull(storage.encoded)
    }

    private class Storage : AbsenceOverrideStorage {
        var encoded: String? = null
        var failWrite = false
        override fun read(): String? = encoded
        override fun write(encoded: String) {
            if (failWrite) throw IOException("Cannot save")
            this.encoded = encoded
        }
    }
}
