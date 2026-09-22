package com.bukovinafilip.gradey.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.bukovinafilip.gradey.domain.WearLessonSelector
import com.bukovinafilip.gradey.model.*
import com.google.android.gms.wearable.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

class WearMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private var payload by mutableStateOf<GradeyWearSyncPayload?>(null)
    private var refreshing by mutableStateOf(false)
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payload = WearSnapshotStore.load(this)
        setContent {
            MaterialTheme {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000) } }
                WearGradeyScreen(payload, refreshing, error, now, ::refresh)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        readData()
    }
    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        super.onPause()
    }
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) WearSnapshotStore.accept(this, it.dataItem) }
        lifecycleScope.launch { payload = WearSnapshotStore.load(this@WearMainActivity); refreshing = false }
    }
    private fun readData() = lifecycleScope.launch {
        try {
            Wearable.getDataClient(this@WearMainActivity).dataItems.await().use { items ->
                items.forEach { WearSnapshotStore.accept(this@WearMainActivity, it) }
            }
            payload = WearSnapshotStore.load(this@WearMainActivity)
        } catch (e: Exception) { if (e is CancellationException) throw e; error = "Connect your phone to sync." }
    }
    private fun refresh() {
        if (refreshing) return
        refreshing = true
        error = null
        lifecycleScope.launch {
            try {
                val nodes = Wearable.getNodeClient(this@WearMainActivity).connectedNodes.await()
                check(nodes.isNotEmpty())
                nodes.forEach { Wearable.getMessageClient(this@WearMainActivity).sendMessage(it.id, "/gradey/refresh", byteArrayOf()).await() }
                readData().join()
                error = "Refresh requested on phone."
            } catch (e: Exception) { if (e is CancellationException) throw e; error = "Open Gradey on your connected phone." }
            finally { refreshing = false }
        }
    }
}

@Composable
private fun WearGradeyScreen(payload: GradeyWearSyncPayload?, refreshing: Boolean, error: String?, now: Long, onRefresh: () -> Unit) {
    val selection = WearLessonSelector.select(payload?.timetable, now)
    ScalingLazyColumn {
        item { Text("Gradey", style = MaterialTheme.typography.title1) }
        item {
            Column {
                if (payload?.isSignedIn != true) Text("Sign in and load your timetable on the phone.")
                else when (selection) {
                    is GradeyWearLessonSelection.Lesson -> {
                        Text(if (selection.timing == NextLessonWidgetTiming.CURRENT) "Now" else "Next")
                        Text(selection.lesson.detailTitle, style = MaterialTheme.typography.title2)
                        Text(selection.lesson.timeRange.orEmpty())
                        selection.lesson.room?.let { Text("Room $it") }
                        selection.lesson.teacher?.let { Text(it) }
                    }
                    GradeyWearLessonSelection.NoTimetable -> Text("Sync your timetable from the phone.")
                    GradeyWearLessonSelection.NoLessons -> Text("No upcoming lessons in the saved week.")
                    GradeyWearLessonSelection.Stale -> Text("Open the phone to refresh your timetable.")
                }
            }
        }
        payload?.timetable?.days?.flatMap { it.lessons }?.filter { (it.startEpochMillis ?: 0) > now }?.take(5)?.forEach { lesson ->
            item { Column { Text(lesson.detailTitle); Text(lesson.timeRange.orEmpty()); lesson.room?.let { Text("Room $it") } } }
        }
        error?.let { item { Text(it) } }
        item { Button(onClick = onRefresh, enabled = !refreshing) { Text(if (refreshing) "Syncing…" else "Refresh") } }
    }
}

object WearSnapshotStore {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(context: android.content.Context): GradeyWearSyncPayload? =
        context.getSharedPreferences("gradey-watch", 0).getString("snapshot", null)?.let {
            runCatching { json.decodeFromString(GradeyWearSyncPayload.serializer(), it) }.getOrNull()
        }
    fun accept(context: android.content.Context, item: DataItem) {
        if (item.uri.path != "/gradey/snapshot") return
        val encoded = runCatching { DataMapItem.fromDataItem(item).dataMap.getString("payload") }.getOrNull() ?: return
        val next = runCatching { json.decodeFromString(GradeyWearSyncPayload.serializer(), encoded) }.getOrNull() ?: return
        if (next.schemaVersion != GradeyWearSyncPayload.CURRENT_SCHEMA_VERSION) return
        if (next.generatedAtEpochMillis < (load(context)?.generatedAtEpochMillis ?: 0)) return
        context.getSharedPreferences("gradey-watch", 0).edit().putString("snapshot", encoded).apply()
    }
}

class WearSyncService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) WearSnapshotStore.accept(this, it.dataItem) }
    }
}
