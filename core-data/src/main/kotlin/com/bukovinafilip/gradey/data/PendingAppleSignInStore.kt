package com.bukovinafilip.gradey.data

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.bukovinafilip.gradey.domain.AccountFailureException
import com.bukovinafilip.gradey.domain.AccountFailureReason
import com.bukovinafilip.gradey.model.PendingAppleSignIn
import com.bukovinafilip.gradey.network.GradeyJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException

/** commit() makes the verifier durable before leaving for the browser or consuming a callback. */
internal class PendingAppleSignInStore(context: Context) {
    private val preferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "gradey-pending-apple-auth", key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    }

    suspend fun load(): PendingAppleSignIn? = withContext(Dispatchers.IO) {
        preferences.getString("pending", null)?.let { encoded ->
            try { GradeyJson.decodeFromString(PendingAppleSignIn.serializer(), encoded) }
            catch (_: SerializationException) { null } // Never include the verifier in a parser error.
        }
    }

    suspend fun save(pending: PendingAppleSignIn?) = withContext(Dispatchers.IO) {
        val editor = preferences.edit()
        if (pending == null) editor.remove("pending")
        else editor.putString("pending", GradeyJson.encodeToString(PendingAppleSignIn.serializer(), pending))
        if (!editor.commit()) throw AccountFailureException(AccountFailureReason.SERVICE_UNAVAILABLE)
    }
}
