package com.bukovinafilip.gradey.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import java.io.File
import java.nio.ByteBuffer
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import com.bukovinafilip.gradey.domain.AbsenceOverrideProjection
import com.bukovinafilip.gradey.domain.AbsenceOverrideRepository
import com.bukovinafilip.gradey.model.AbsenceDayOverride
import com.bukovinafilip.gradey.model.AbsenceOverridePayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

interface AbsenceOverrideStorage {
    fun read(): String?
    fun write(encoded: String)
}

/** User corrections are durable and separate from replaceable school-response caches. */
class AndroidAbsenceOverrideRepository(private val storage: AbsenceOverrideStorage) : AbsenceOverrideRepository {
    constructor(context: Context) : this(EncryptedAbsenceOverrideStorage(context.applicationContext))
    private val mutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var loaded: Map<String, List<AbsenceDayOverride>>? = null

    override suspend fun load(scope: String): List<AbsenceDayOverride> = withContext(Dispatchers.IO) {
        mutex.withLock { require(scope.isNotBlank()); loadLocked()[scope].orEmpty().toList() }
    }

    override suspend fun save(override: AbsenceDayOverride): List<AbsenceDayOverride> = mutate(override.scope) { current ->
        current.filterNot { it.dateKey == override.dateKey } + override
    }

    override suspend fun replace(scope: String, overrides: List<AbsenceDayOverride>) = mutate(scope) { overrides }
    override suspend fun restore(scope: String, dateKey: String) = mutate(scope) { rows -> rows.filterNot { it.dateKey == dateKey } }
    override suspend fun reset(scope: String) = mutate(scope) { emptyList() }

    private suspend fun mutate(scope: String, update: (List<AbsenceDayOverride>) -> List<AbsenceDayOverride>): List<AbsenceDayOverride> = withContext(Dispatchers.IO) {
        mutex.withLock {
            val all = loadLocked()
            val next = update(all[scope].orEmpty()).sortedBy { it.dateKey }
            AbsenceOverrideProjection.validateStored(scope, next)
            val updated = all + (scope to next)
            storage.write(json.encodeToString(AbsenceOverridePayload(scopes = updated)))
            loaded = updated
            next
        }
    }

    private fun loadLocked(): Map<String, List<AbsenceDayOverride>> {
        loaded?.let { return it }
        val encoded = storage.read() ?: return emptyMap<String, List<AbsenceDayOverride>>().also { loaded = it }
        val payload = json.decodeFromString<AbsenceOverridePayload>(encoded)
        check(payload.version == 1) { "Saved attendance corrections use an unsupported format. The saved file has been kept intact." }
        payload.scopes.forEach { (scope, entries) -> AbsenceOverrideProjection.validateStored(scope, entries) }
        return payload.scopes.also { loaded = it }
    }
}


/** AtomicFile preserves the last good version; AES-GCM detects tampering and partial/corrupt data. */
private class EncryptedAbsenceOverrideStorage(context: Context) : AbsenceOverrideStorage {
    private val file = AtomicFile(File(context.noBackupFilesDir, "absence-overrides.enc"))
    private val alias = "gradey-absence-overrides-v1"

    override fun read(): String? {
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        val data = file.readFully()
        check(data.size >= 1 + 12 + 16 && data[0] == 1.toByte()) { "The saved attendance corrections could not be read. Its file has been kept intact." }
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

class InMemoryAbsenceOverrideRepository : AbsenceOverrideRepository {
    private val delegate = AndroidAbsenceOverrideRepository(object : AbsenceOverrideStorage {
        private var data: String? = null
        override fun read(): String? = data
        override fun write(encoded: String) { data = encoded }
    })
    override suspend fun load(scope: String) = delegate.load(scope)
    override suspend fun save(override: AbsenceDayOverride) = delegate.save(override)
    override suspend fun replace(scope: String, overrides: List<AbsenceDayOverride>) = delegate.replace(scope, overrides)
    override suspend fun restore(scope: String, dateKey: String) = delegate.restore(scope, dateKey)
    override suspend fun reset(scope: String) = delegate.reset(scope)
}
