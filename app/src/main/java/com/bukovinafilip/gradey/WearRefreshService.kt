package com.bukovinafilip.gradey

import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.*
import java.time.LocalDate

class WearRefreshService : WearableListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != "/gradey/refresh") return
        scope.launch {
            try {
                val graph = (application as GradeyApplication).activeGraph
                val session = graph.schoolRepository.bootstrapSession() ?: run { CompanionSync.clear(this@WearRefreshService); return@launch }
                val week = graph.schoolRepository.loadTimetable(LocalDate.now().toString())
                CompanionSync.publish(this@WearRefreshService, week, graph.schoolRepository.loadCachedDashboard()?.user, session.cacheScope)
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
    }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
