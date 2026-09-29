package com.bukovinafilip.gradey.push

import android.content.Context
import java.security.MessageDigest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A retry of the same server dispatch must not alert twice, including after process restart. */
internal object PushDeliveryHistory {
    private val mutex = Mutex()
    private const val fileName = "gradey-push-receipts"
    private const val retentionMillis = 7L * 24 * 60 * 60 * 1000
    private const val maximumReceipts = 256

    fun notificationID(owner: String, dispatch: String): Int = receiptKey(owner, dispatch).hashCode()

    suspend fun deliverOnce(context: Context, owner: String, dispatch: String, notify: suspend () -> Boolean): Boolean = mutex.withLock {
        val preferences = context.getSharedPreferences(fileName, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val key = receiptKey(owner, dispatch)
        val receipts = preferences.all.mapNotNull { (id, value) ->
            (value as? Long)?.takeIf { it >= now - retentionMillis }?.let { id to it }
        }.toMap()
        if (key in receipts) return@withLock false
        if (!notify()) return@withLock false
        // Persist after Android accepts notify; if the process dies in this narrow gap, the stable
        // notification ID and onlyAlertOnce keep the retried delivery in one notification card.
        val retained = (receipts + (key to now)).entries.sortedByDescending { it.value }.take(maximumReceipts)
        preferences.edit().clear().apply { retained.forEach { putLong(it.key, it.value) } }.commit()
        true
    }

    suspend fun clear(context: Context) = mutex.withLock {
        context.getSharedPreferences(fileName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun receiptKey(owner: String, dispatch: String) = MessageDigest.getInstance("SHA-256")
        .digest("$owner\u0000$dispatch".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}
