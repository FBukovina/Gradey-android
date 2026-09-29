package com.bukovinafilip.gradey

import android.app.NotificationManager
import android.content.Context

/** Serializes delivery with local identity revocation so an old account cannot post after cleanup. */
internal object AccountNotifications {
    val lock = Any()
    private var marksPolicyEpoch = 0L

    /** An in-flight callback never survives a policy change, even if values change back. */
    fun captureMarksPolicyEpoch(): Long = synchronized(lock) { marksPolicyEpoch }

    fun deliverMarksIfCurrent(epoch: Long, deliver: () -> Boolean): Boolean = synchronized(lock) {
        epoch == marksPolicyEpoch && deliver()
    }

    fun acceptsOwner(context: Context, owner: String): Boolean {
        val preferences = context.getSharedPreferences("gradey-app-preferences", Context.MODE_PRIVATE)
        if (preferences.getInt("privacyRevision", 0) < PrivacyRevision || !isValidAgeAttestation(preferences.getString("ageAttestation", null))) return false
        val mode = context.getSharedPreferences("gradey-mode", Context.MODE_PRIVATE)
        return !mode.getBoolean("demo", false) && if (mode.getBoolean("guest", false)) {
            owner == "guest-local"
        } else mode.getString("owner", null) == owner
    }

    fun clearChannel(context: Context, channel: String) = synchronized(lock) {
        // Advance before shade cleanup, including when no notification is currently visible.
        if (channel == MARKS_CHANNEL) marksPolicyEpoch++
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { it.notification.channelId == channel }.forEach {
            manager.cancel(it.tag, it.id)
        }
    }

    fun endSession(context: Context) = synchronized(lock) {
        val persisted = context.getSharedPreferences("gradey-mode", Context.MODE_PRIVATE).edit()
            .remove("demo").remove("guest").remove("owner").commit()
        clearChannel(context, MARKS_CHANNEL)
        clearChannel(context, PlannerReminderScheduler.channel)
        check(persisted) { "Could not persist the signed-out notification state." }
    }
}
