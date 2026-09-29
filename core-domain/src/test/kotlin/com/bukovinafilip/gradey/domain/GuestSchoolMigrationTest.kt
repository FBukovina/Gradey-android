package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.StoredSession
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class GuestSchoolMigrationTest {
    private val source = StoredSession("access", "refresh", "Bearer", Long.MAX_VALUE, "https://school.example")
    private val target = source.copy(linkedAccountID = "canonical-link")
    private class Journal : GuestSchoolMigrationJournal {
        var saved: GuestSchoolMigration? = null
        var rejectNextWrite = false
        override suspend fun load() = saved
        override suspend fun save(value: GuestSchoolMigration?) {
            if (rejectNextWrite) { rejectNextWrite = false; error("disk full") }
            saved = value
        }
    }
    private inner class Effects(val journal: Journal) : GuestSchoolMigrationEffects {
        var owner: String? = "owner"
        var school: StoredSession? = source
        var links = 0
        var copies = 0
        var failAfterLink = false
        var failCopy = false
        var changeOwnerOnLink = false
        var switchSchoolOnLink = false
        val events = mutableListOf<String>()
        override suspend fun currentOwnerID() = owner
        override suspend fun currentSchool() = school
        override suspend fun link(source: StoredSession): StoredSession {
            check(journal.saved?.source == source)
            links++
            school = target
            if (changeOwnerOnLink) owner = "different-owner"
            if (switchSchoolOnLink) school = target.copy(baseURL = "https://other-school.example")
            if (failAfterLink) { failAfterLink = false; error("process died after remote commit") }
            return target
        }
        override suspend fun copyAbsencePlans(sourceScope: String, targetScope: String) {
            check(journal.saved?.target == target)
            check(sourceScope == source.cacheScope && targetScope == target.cacheScope)
            if (failCopy) { failCopy = false; error("disk full") }
            copies++; events += "plans"
        }
        override suspend fun copyAbsenceOverrides(sourceScope: String, targetScope: String) {
            assertThat(sourceScope).isEqualTo("guest-local:${source.cacheScope}")
            assertThat(targetScope).isEqualTo("owner:${target.cacheScope}")
            events += "overrides"
        }
        override suspend fun rebindPlanner(sourceScope: String, targetScope: String) { events += "planner" }
        override suspend fun activate(target: StoredSession) { school = target; events += "activate" }
    }
    @Test fun journalIsDurableBeforeLinkAndCopiesPrecedeActivation() = runTest {
        val journal = Journal(); val effects = Effects(journal)
        val result = GuestSchoolMigrationCoordinator(journal, effects).migrate("owner", source)
        assertThat(result).isEqualTo(target)
        assertThat(effects.events).containsExactly("plans", "overrides", "planner", "activate").inOrder()
        assertThat(journal.saved?.target).isEqualTo(target) // Caller must first commit app ownership.
    }
    @Test fun cannotLinkWhenInitialRecoveryRecordCannotBeSaved() = runTest {
        val journal = Journal().apply { rejectNextWrite = true }; val effects = Effects(journal)
        try { GuestSchoolMigrationCoordinator(journal, effects).migrate("owner", source); error("expected failure") } catch (_: IllegalStateException) {}
        assertThat(effects.links).isEqualTo(0)
        assertThat(effects.school).isEqualTo(source)
    }
    @Test fun restartAfterRemoteCommitRelinksIdempotentlyBeforeAdoptingAccount() = runTest {
        val journal = Journal(); val effects = Effects(journal).apply { failAfterLink = true }
        try { GuestSchoolMigrationCoordinator(journal, effects).migrate("owner", source) } catch (_: IllegalStateException) {}
        assertThat(journal.saved?.target).isNull()
        assertThat(effects.school).isEqualTo(target)
        GuestSchoolMigrationCoordinator(journal, effects).migrate("owner", source)
        assertThat(effects.links).isEqualTo(2)
        assertThat(effects.copies).isEqualTo(1)
    }
    @Test fun durableTargetResumesLocalMigrationWithoutNetworkLink() = runTest {
        val journal = Journal(); val effects = Effects(journal).apply { failCopy = true }
        try { GuestSchoolMigrationCoordinator(journal, effects).migrate("owner", source) } catch (_: IllegalStateException) {}
        assertThat(journal.saved?.target).isEqualTo(target)
        effects.school = source // App keeps guest available after the failure.
        GuestSchoolMigrationCoordinator(journal, effects).migrate("owner", source)
        assertThat(effects.links).isEqualTo(1)
        assertThat(effects.events.last()).isEqualTo("activate")
    }
    @Test fun anotherGradeyOwnerCannotResumeAnExistingJournal() = runTest {
        val journal = Journal().apply { saved = GuestSchoolMigration("previous-owner", source, target) }
        val effects = Effects(journal)
        try { GuestSchoolMigrationCoordinator(journal, effects).migrate("owner", source) } catch (_: IllegalStateException) {}
        assertThat(effects.links).isEqualTo(0)
        assertThat(effects.copies).isEqualTo(0)
        assertThat(journal.saved?.ownerID).isEqualTo("previous-owner")
    }
    @Test fun ownerChangeDuringRemoteLinkCannotCopyOrActivateLocalData() = runTest {
        val journal = Journal(); val effects = Effects(journal).apply { changeOwnerOnLink = true }
        try { GuestSchoolMigrationCoordinator(journal, effects).migrate("owner", source) } catch (_: IllegalStateException) {}
        assertThat(effects.copies).isEqualTo(0)
        assertThat(effects.events).isEmpty()
        assertThat(journal.saved?.target).isNull()
    }
    @Test fun activeSchoolChangeCannotRebindAnotherSchoolsPlanner() = runTest {
        val journal = Journal(); val effects = Effects(journal).apply { switchSchoolOnLink = true }
        try { GuestSchoolMigrationCoordinator(journal, effects).migrate("owner", source) } catch (_: IllegalStateException) {}
        assertThat(effects.copies).isEqualTo(0)
        assertThat(journal.saved?.target).isNull()
    }
    @Test fun linkedSchoolWithoutGuestJournalCannotBeAdopted() = runTest {
        val journal = Journal(); val effects = Effects(journal).apply { school = target }
        try { GuestSchoolMigrationCoordinator(journal, effects).migrate("owner", target) } catch (_: IllegalArgumentException) {}
        assertThat(effects.links).isEqualTo(0)
        assertThat(journal.saved).isNull()
    }
}
