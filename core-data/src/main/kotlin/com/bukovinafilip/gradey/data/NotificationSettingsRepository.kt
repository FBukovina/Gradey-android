package com.bukovinafilip.gradey.data

import com.bukovinafilip.gradey.domain.DevicePushTokenClient
import com.bukovinafilip.gradey.domain.GradeyAuthRepository
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.network.SupabaseAccountClient
import com.bukovinafilip.gradey.network.notificationTimeToMinutes

/** Settings are scoped to the signed-in Gradey account and encrypted alongside other account state. */
class NotificationSettingsRepository(
    private val store: SecureJsonStore,
    private val authRepository: GradeyAuthRepository,
    private val devicePushTokenClient: DevicePushTokenClient,
    private val accountClient: SupabaseAccountClient? = null,
) {
    suspend fun load(): NotificationPreferences {
        val session = authRepository.cachedSession() ?: return NotificationPreferences.Default
        return store.load(key(session.account.id), NotificationPreferences.serializer()) ?: NotificationPreferences.Default
    }

    suspend fun refresh(): NotificationPreferences {
        val session = authRepository.bootstrapSession() ?: return NotificationPreferences.Default
        val value = accountClient?.notificationPreferences(session) ?: load()
        store.save(key(session.account.id), value, NotificationPreferences.serializer())
        return value
    }

    suspend fun save(preferences: NotificationPreferences) {
        notificationTimeToMinutes(preferences.quietHoursStart)
        notificationTimeToMinutes(preferences.quietHoursEnd)
        val session = authRepository.bootstrapSession() ?: error("Sign in to Gradey to change notifications.")
        // Persist after a confirmed response, so a rejected save does not appear successful after restart.
        devicePushTokenClient.updateNotificationPreferences(preferences, session)
        store.save(key(session.account.id), preferences, NotificationPreferences.serializer())
    }

    private fun key(ownerID: String) = "notifications.v1.$ownerID"
}
