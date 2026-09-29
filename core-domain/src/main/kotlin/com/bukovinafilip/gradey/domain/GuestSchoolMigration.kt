package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.StoredSession
import kotlinx.serialization.Serializable

/** Persisted before cloud linking; credentials belong only in encrypted, non-backed-up storage. */
@Serializable
data class GuestSchoolMigration(
    val ownerID: String,
    val source: StoredSession,
    val target: StoredSession? = null,
)

interface GuestSchoolMigrationJournal {
    suspend fun load(): GuestSchoolMigration?
    suspend fun save(value: GuestSchoolMigration?)
}

interface GuestSchoolMigrationEffects {
    suspend fun currentOwnerID(): String?
    suspend fun currentSchool(): StoredSession?
    /** Must use a freshly verified provider user. The server link is idempotent for this owner and school. */
    suspend fun link(source: StoredSession): StoredSession
    /** Each copy must be durable and idempotent, preserving existing destination entries. */
    suspend fun copyAbsencePlans(sourceScope: String, targetScope: String)
    suspend fun copyAbsenceOverrides(sourceScope: String, targetScope: String)
    suspend fun rebindPlanner(sourceScope: String, targetScope: String)
    suspend fun activate(target: StoredSession)
}

/** The caller commits app ownership before clearing the journal. Any interrupted step can be retried. */
class GuestSchoolMigrationCoordinator(
    private val journal: GuestSchoolMigrationJournal,
    private val effects: GuestSchoolMigrationEffects,
) {
    suspend fun migrate(ownerID: String, source: StoredSession): StoredSession {
        require(ownerID.isNotBlank())
        var migration = journal.load() ?: GuestSchoolMigration(ownerID, source).also {
            require(source.linkedAccountID == null) { "Only an unlinked guest school can be migrated." }
            verify(it)
            journal.save(it)
        }
        check(migration.ownerID == ownerID) { "The pending school upgrade belongs to another Gradey ID." }
        verify(migration, allowRelinkRecovery = migration.target == null)
        val target = migration.target ?: effects.link(migration.source).also {
            check(it.linkedAccountID != null && sameSchool(it, migration.source)) { "The linked school identity changed." }
            migration = migration.copy(target = it)
            verify(migration)
            journal.save(migration)
        }
        verify(migration)
        effects.copyAbsencePlans(migration.source.cacheScope, target.cacheScope)
        verify(migration)
        effects.copyAbsenceOverrides("guest-local:${migration.source.cacheScope}", "$ownerID:${target.cacheScope}")
        verify(migration)
        effects.rebindPlanner(migration.source.cacheScope, target.cacheScope)
        verify(migration)
        effects.activate(target)
        return target
    }

    private suspend fun verify(migration: GuestSchoolMigration, allowRelinkRecovery: Boolean = false) {
        check(effects.currentOwnerID() == migration.ownerID) { "The Gradey ID changed during the school upgrade." }
        val current = effects.currentSchool() ?: error("The guest school is no longer available.")
        check(sameSchool(current, migration.source)) { "The active school changed during the upgrade." }
        check(allowRelinkRecovery || current.linkedAccountID == migration.source.linkedAccountID ||
            current.linkedAccountID == migration.target?.linkedAccountID) { "An unrelated school link cannot be adopted." }
    }

    private fun sameSchool(a: StoredSession, b: StoredSession) =
        a.copy(linkedAccountID = null).cacheScope == b.copy(linkedAccountID = null).cacheScope
}
