package com.bukovinafilip.gradey.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bukovinafilip.gradey.domain.GuestSchoolMigration
import com.bukovinafilip.gradey.domain.GuestSchoolMigrationJournal
import com.bukovinafilip.gradey.model.PlannedAbsence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.json.Json

class AndroidGuestSchoolMigrationJournal(private val storage: PlannerStorage) : GuestSchoolMigrationJournal {
    constructor(context: Context) : this(EncryptedPlannerStorage(context.applicationContext,
        fileName = "guest-school-upgrade.enc", alias = "gradey-guest-school-upgrade-v1"))
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    override suspend fun load(): GuestSchoolMigration? = withContext(Dispatchers.IO) {
        mutex.withLock { storage.read()?.let { json.decodeFromString(GuestSchoolMigration.serializer().nullable, it) } }
    }
    override suspend fun save(value: GuestSchoolMigration?) = withContext(Dispatchers.IO) {
        mutex.withLock { storage.write(json.encodeToString(GuestSchoolMigration.serializer().nullable, value)) }
    }
}

/** Shares the existing plans file, but migration uses commit so the recovery journal cannot outrun disk. */
class GuestAbsencePlanMigration(context: Context) {
    private val preferences = EncryptedSharedPreferences.create(context.applicationContext, "gradey-planned-absences",
        MasterKey.Builder(context.applicationContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    suspend fun copy(source: String, target: String) = withContext(Dispatchers.IO) {
        if (source == target) return@withContext
        val serializer = ListSerializer(PlannedAbsence.serializer())
        fun read(scope: String) = preferences.getString("plans.$scope", null)?.let { json.decodeFromString(serializer, it) }.orEmpty()
        val destination = read(target)
        val merged = (destination + read(source)).distinctBy { it.date }.sortedBy { it.date }
        if (merged != destination) check(preferences.edit().putString("plans.$target", json.encodeToString(serializer, merged)).commit()) {
            "The school upgrade could not save absence plans."
        }
        // Retain the source until the complete upgrade is committed; retries always preserve target edits.
    }
}
