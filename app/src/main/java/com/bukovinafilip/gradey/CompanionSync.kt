package com.bukovinafilip.gradey

import android.content.Context
import com.bukovinafilip.gradey.domain.CompanionSnapshots
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.network.GradeyJson
import com.bukovinafilip.gradey.widgets.WidgetSnapshotStore
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

object CompanionSync {
    private val mutex = Mutex()
    suspend fun publish(context: Context, week: TimetableWeek, user: UserResponse?, expectedScope: String? = null) = mutex.withLock {
        val application = context.applicationContext as? GradeyApplication
        if (expectedScope != null && application?.activeGraph?.schoolRepository?.currentStoredSession()?.cacheScope != expectedScope) return@withLock
        WidgetSnapshotStore.save(context, CompanionSnapshots.widget(week))
        send(context, CompanionSnapshots.wear(week, user))
    }
    suspend fun clear(context: Context) = mutex.withLock {
        WidgetSnapshotStore.save(context, null)
        send(context, GradeyWearSyncPayload.signedOut())
    }
    private suspend fun send(context: Context, payload: GradeyWearSyncPayload) {
        try {
            val request = PutDataMapRequest.create("/gradey/snapshot").apply {
                dataMap.putString("payload", GradeyJson.encodeToString(GradeyWearSyncPayload.serializer(), payload))
            }.asPutDataRequest().setUrgent()
            Wearable.getDataClient(context).putDataItem(request).await()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // Phones without a paired Wear OS device still retain their local widget snapshot.
        }
    }
}
