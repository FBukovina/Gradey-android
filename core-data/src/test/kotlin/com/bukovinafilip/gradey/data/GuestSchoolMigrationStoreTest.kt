package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.GuestSchoolMigration
import com.bukovinafilip.gradey.model.StoredSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GuestSchoolMigrationStoreTest {
    private class Storage : PlannerStorage {
        var value: String? = null
        var fail = false
        override fun read() = value
        override fun write(encoded: String) { if (fail) error("disk unavailable"); value = encoded }
    }
    @Test fun restartRestoresExactOwnerAndScopesAndClearIsDurable() = runTest {
        val storage = Storage()
        val source = StoredSession("access", "refresh", "Bearer", 999, "https://school.example")
        val record = GuestSchoolMigration("owner", source, source.copy(linkedAccountID = "link"))
        AndroidGuestSchoolMigrationJournal(storage).save(record)
        assertThat(AndroidGuestSchoolMigrationJournal(storage).load()).isEqualTo(record)
        AndroidGuestSchoolMigrationJournal(storage).save(null)
        assertThat(AndroidGuestSchoolMigrationJournal(storage).load()).isNull()
    }
    @Test fun failedWriteLeavesPreviousRecoveryRecordAvailable() = runTest {
        val storage = Storage()
        val source = StoredSession("access", "refresh", "Bearer", 999, "https://school.example")
        val record = GuestSchoolMigration("owner", source)
        val journal = AndroidGuestSchoolMigrationJournal(storage)
        journal.save(record)
        storage.fail = true
        try { journal.save(record.copy(target = source.copy(linkedAccountID = "link"))) } catch (_: IllegalStateException) {}
        assertThat(AndroidGuestSchoolMigrationJournal(storage).load()).isEqualTo(record)
    }
    @Test fun corruptRecoveryDataIsNeverSilentlyTreatedAsNewMigration() = runTest {
        val storage = Storage().apply { value = "not valid json" }
        var failed = false
        try { AndroidGuestSchoolMigrationJournal(storage).load() } catch (_: Exception) { failed = true }
        assertThat(failed).isTrue()
        assertThat(storage.value).isEqualTo("not valid json")
    }
}
