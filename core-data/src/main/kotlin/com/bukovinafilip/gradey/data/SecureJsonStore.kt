package com.bukovinafilip.gradey.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

interface JsonStore {
    fun <T> load(key: String, serializer: KSerializer<T>): T?
    fun <T> save(key: String, value: T?, serializer: KSerializer<T>)
    fun clear(key: String)
    fun clearPrefix(prefix: String)
}

class SecureJsonStore(
    context: Context,
    fileName: String,
    private val json: Json,
) : JsonStore {
    private val preferences: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            fileName,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override fun <T> load(key: String, serializer: KSerializer<T>): T? {
        val encoded = preferences.getString(key, null) ?: return null
        return runCatching { json.decodeFromString(serializer, encoded) }.getOrNull()
    }

    override fun <T> save(key: String, value: T?, serializer: KSerializer<T>) {
        preferences.edit().apply {
            if (value == null) remove(key) else putString(key, json.encodeToString(serializer, value))
        }.apply()
    }

    override fun clear(key: String) {
        preferences.edit().remove(key).apply()
    }

    override fun clearPrefix(prefix: String) {
        preferences.edit().apply {
            preferences.all.keys.filter { it.startsWith(prefix) }.forEach { remove(it) }
        }.apply()
    }
}
