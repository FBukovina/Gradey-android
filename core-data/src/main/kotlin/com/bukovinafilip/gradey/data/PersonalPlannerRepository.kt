package com.bukovinafilip.gradey.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.bukovinafilip.gradey.domain.PersonalPlanner
import com.bukovinafilip.gradey.domain.PersonalPlannerRepository
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PersonalPlannerPayload
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface PlannerStorage {
    /** Null means no saved file. Corruption must throw, never silently become an empty planner. */
    fun read(): String?
    fun write(encoded: String)
}

/** Independent from school session stores, intentionally retained across sign-out and cache clearing. */
class AndroidPersonalPlannerRepository(private val storage: PlannerStorage) : PersonalPlannerRepository {
    constructor(context: Context) : this(EncryptedPlannerStorage(context.applicationContext))
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded: List<PersonalPlannerItem>? = null

    override suspend fun load(): List<PersonalPlannerItem> = withContext(Dispatchers.IO) {
        mutex.withLock { loadLocked() }
    }

    override suspend fun save(item: PersonalPlannerItem): List<PersonalPlannerItem> = mutate { current ->
        val validated = PersonalPlanner.validate(item)
        val existing = current.firstOrNull { it.id == item.id }
        val saved = validated.copy(createdAtEpochMillis = existing?.createdAtEpochMillis ?: validated.createdAtEpochMillis)
        (current.filterNot { it.id == saved.id } + saved)
    }

    override suspend fun delete(id: String): List<PersonalPlannerItem> = mutate { rows -> rows.filterNot { it.id == id } }

    override suspend fun setCompleted(id: String, completed: Boolean): List<PersonalPlannerItem> = mutate { rows ->
        check(rows.any { it.id == id }) { "This planner item is no longer available." }
        rows.map { if (it.id == id) it.copy(isCompleted = completed, updatedAtEpochMillis = System.currentTimeMillis()) else it }
    }

    override suspend fun rebindSchoolScope(from: String, to: String): List<PersonalPlannerItem> = mutate { rows ->
        require(from.isNotBlank() && to.isNotBlank()) { "School account scopes must not be empty." }
        if (from == to) rows else rows.map { item ->
            val subject = item.subject
            val lesson = item.lesson
            // Do not cross account boundaries in an already inconsistent reference pair.
            if (subject != null && lesson != null && subject.scope != lesson.scope) item
            else item.copy(
                subject = subject?.let { if (it.scope == from) it.copy(scope = to) else it },
                lesson = lesson?.let { if (it.scope == from) it.copy(scope = to) else it },
            )
        }
    }

    private suspend fun mutate(update: (List<PersonalPlannerItem>) -> List<PersonalPlannerItem>): List<PersonalPlannerItem> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = loadLocked()
            val next = update(current).toList()
            if (next == current) return@withLock current
            // Publish only after durable atomic storage succeeds.
            storage.write(json.encodeToString(PersonalPlannerPayload(items = next)))
            loaded = next
            next
        }
    }

    private fun loadLocked(): List<PersonalPlannerItem> {
        loaded?.let { return it }
        val encoded = storage.read() ?: return emptyList<PersonalPlannerItem>().also { loaded = it }
        val payload = json.decodeFromString<PersonalPlannerPayload>(encoded)
        check(payload.version == 1 && payload.items.map { it.id }.toSet().size == payload.items.size) {
            "Planner storage could not be read. Your saved file has been kept intact."
        }
        payload.items.forEach { PersonalPlanner.validate(it) }
        return payload.items.toList().also { loaded = it }
    }
}


class InMemoryPersonalPlannerRepository(initialItems: List<PersonalPlannerItem> = emptyList()) : PersonalPlannerRepository {
    private val delegate = AndroidPersonalPlannerRepository(object : PlannerStorage {
        var data: String? = Json.encodeToString(PersonalPlannerPayload(items = initialItems))
        override fun read(): String? = data
        override fun write(encoded: String) { data = encoded }
    })
    override suspend fun load() = delegate.load()
    override suspend fun save(item: PersonalPlannerItem) = delegate.save(item)
    override suspend fun delete(id: String) = delegate.delete(id)
    override suspend fun setCompleted(id: String, completed: Boolean) = delegate.setCompleted(id, completed)
    override suspend fun rebindSchoolScope(from: String, to: String) = delegate.rebindSchoolScope(from, to)
}

/** AtomicFile preserves the last good version; AES-GCM detects tampering and partial/corrupt data. */
internal class EncryptedPlannerStorage(context: Context, fileName: String = "personal-planner.enc", private val alias: String = "gradey-personal-planner-v1") : PlannerStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, fileName))

    override fun read(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val data = file.readFully()
        check(data.size >= 1 + 12 + 16 && data[0] == 1.toByte()) { "The saved planner could not be read. Its file has been kept intact." }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(1, 13)))
        return cipher.doFinal(data.copyOfRange(13, data.size)).toString(Charsets.UTF_8)
    }

    override fun write(encoded: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        check(cipher.iv.size == 12)
        val ciphertext = cipher.doFinal(encoded.toByteArray(Charsets.UTF_8))
        val bytes = ByteBuffer.allocate(1 + 12 + ciphertext.size).put(1.toByte()).put(cipher.iv).put(ciphertext).array()
        val output = file.startWrite()
        try {
            output.write(bytes)
            output.fd.sync()
            file.finishWrite(output)
        } catch (error: Exception) {
            file.failWrite(output)
            throw error
        }
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
}
