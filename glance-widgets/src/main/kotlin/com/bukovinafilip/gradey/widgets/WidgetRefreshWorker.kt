package com.bukovinafilip.gradey.widgets

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bukovinafilip.gradey.domain.NextLessonSelector
import java.util.concurrent.TimeUnit

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        NextLessonWidget().updateAll(applicationContext)
        scheduleWidgetRefresh(applicationContext)
        return Result.success()
    }
}

internal fun scheduleWidgetRefresh(context: Context) {
    val snapshot = WidgetSnapshotStore.load(context)
    val now = System.currentTimeMillis()
    val next = NextLessonSelector.timelineDates(snapshot, now).firstOrNull()
    val manager = WorkManager.getInstance(context)
    if (next == null) manager.cancelUniqueWork("gradey-widget-boundary")
    else manager.enqueueUniqueWork("gradey-widget-boundary", ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<WidgetRefreshWorker>()
            .setInitialDelay((next - now).coerceAtLeast(1_000), TimeUnit.MILLISECONDS).build())
}
