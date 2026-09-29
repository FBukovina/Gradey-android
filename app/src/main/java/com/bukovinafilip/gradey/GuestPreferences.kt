package com.bukovinafilip.gradey

import android.content.Context
import com.bukovinafilip.gradey.data.SecureJsonStore
import com.bukovinafilip.gradey.model.NotificationPreferences
import com.bukovinafilip.gradey.network.GradeyJson
import java.time.LocalTime
import java.time.ZoneId

/** Guest settings stay on this device and never call the cloud preference endpoint. */
internal class GuestPreferences(context: Context) {
    private val store = SecureJsonStore(context, "gradey-guest-preferences", GradeyJson)
    fun load(): NotificationPreferences = (store.load("preferences", NotificationPreferences.serializer())
        ?: NotificationPreferences.Default).copy(newMarksEnabled = false)
    fun save(value: NotificationPreferences) {
        LocalTime.parse(value.quietHoursStart)
        LocalTime.parse(value.quietHoursEnd)
        ZoneId.of(value.quietHoursTimeZoneIdentifier)
        store.save("preferences", value.copy(newMarksEnabled = false), NotificationPreferences.serializer())
    }
    fun clear() = store.clear("preferences")
}
