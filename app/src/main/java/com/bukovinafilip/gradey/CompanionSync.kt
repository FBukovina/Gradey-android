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
    private val revision = java.util.concurrent.atomic.AtomicLong()
    class PublicationToken internal constructor(internal val revision: Long, internal val graph: com.bukovinafilip.gradey.data.AndroidGradeyGraph?)
    fun capture(context: Context) = PublicationToken(revision.get(), (context.applicationContext as? GradeyApplication)?.activeGraph)
    private fun isCurrent(context: Context, token: PublicationToken): Boolean = token.revision == revision.get() &&
        token.graph != null && (context.applicationContext as? GradeyApplication)?.activeGraph === token.graph

    suspend fun publish(context: Context, week: TimetableWeek?, user: UserResponse?, expectedScope: String? = null, token: PublicationToken = capture(context)) = mutex.withLock {
        if (!isCurrent(context, token)) return@withLock
        val application = context.applicationContext as? GradeyApplication
        if (expectedScope != null && application?.activeGraph?.schoolRepository?.currentStoredSession()?.cacheScope != expectedScope) return@withLock
        val graph = application?.activeGraph
        val owner = graph?.gradeyAuthRepository?.cachedSession()?.account?.id
        val mode = context.getSharedPreferences("gradey-mode", 0)
        fun canPublish() = isCurrent(context, token) && when {
            graph?.isDemo == true -> mode.getBoolean("demo", false)
            mode.getBoolean("guest", false) -> AccountNotifications.acceptsOwner(context, "guest-local")
            owner != null -> AccountNotifications.acceptsOwner(context, owner)
            else -> false
        }
        if (!canPublish()) return@withLock
        val aiIdentity = if (graph != null && !graph.isDemo && owner != null && AccountNotifications.acceptsOwner(context, owner)) {
            com.bukovinafilip.gradey.feature.ai.wearAIIdentity(context, owner, graph.schoolRepository.currentStoredSession())
        } else null
        val schoolAccess = if (graph != null && !graph.isDemo) WearSchoolAccessManager.get(context).snapshot() else null
        if (expectedScope != null && application?.activeGraph?.schoolRepository?.currentStoredSession()?.cacheScope != expectedScope) return@withLock
        if (!canPublish()) return@withLock
        if (week != null) WidgetSnapshotStore.save(context, CompanionSnapshots.widget(week))
        if (!canPublish()) return@withLock // A queued clear owns removal of any already-written widget.
        val snapshot = if (week != null) CompanionSnapshots.wear(week, user)
            else GradeyWearSyncPayload(generatedAtEpochMillis = System.currentTimeMillis(), isSignedIn = true)
        send(context, snapshot.copy(aiIdentity = aiIdentity, schoolAccess = schoolAccess))
    }

    suspend fun clear(context: Context) {
        revision.incrementAndGet() // Revoke in-flight loads before waiting for an older publication.
        mutex.withLock { clearLocked(context) }
    }
    suspend fun clearIfStillSignedOut(context: Context, token: PublicationToken) = mutex.withLock {
        if (!isCurrent(context, token)) return@withLock
        val session = token.graph?.schoolRepository?.currentStoredSession()
        if (!isCurrent(context, token) || session != null) return@withLock
        revision.incrementAndGet()
        clearLocked(context)
    }
    private suspend fun clearLocked(context: Context) {
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
