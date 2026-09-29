package com.bukovinafilip.gradey.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.*
import com.bukovinafilip.gradey.domain.WearLessonSelector
import com.bukovinafilip.gradey.domain.WearSchoolException
import com.bukovinafilip.gradey.domain.WearSchoolFailure
import com.bukovinafilip.gradey.model.*
import com.bukovinafilip.gradey.network.WatchSchoolRefresh
import com.google.android.gms.wearable.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json

class WearMainActivity : ComponentActivity(), DataClient.OnDataChangedListener {
    private var payload by mutableStateOf<GradeyWearSyncPayload?>(null)
    private var refreshing by mutableStateOf(false)
    private var statusResource by mutableStateOf<Int?>(null)
    private var requestedAfter = 0L
    private var refreshJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        payload = WearSnapshotStore.load(this)
        setContent {
            MaterialTheme(colors = Colors(primary = Color(0xFF6BDCC3), onPrimary = Color(0xFF002B23), surface = Color(0xFF172522))) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(1_000) } }
                WearGradeyScreen(payload, refreshing, statusResource, now, ::refresh)
            }
        }
    }
    override fun onResume() {
        super.onResume()
        Wearable.getDataClient(this).addListener(this)
        lifecycleScope.launch {
            readData().join()
            if (payload?.schoolAccess != null && System.currentTimeMillis() - (payload?.timetable?.cachedAtEpochMillis ?: 0) > 5 * 60_000) {
                val connected = withContext(Dispatchers.IO) { runCatching { WearSchoolGrantStore.load(this@WearMainActivity) != null }.getOrDefault(false) }
                if (connected) refresh()
            }
        }
    }
    override fun onPause() {
        Wearable.getDataClient(this).removeListener(this)
        super.onPause()
    }
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) WearSnapshotStore.accept(this, it.dataItem) }
        lifecycleScope.launch { receiveSnapshot() }
    }
    private fun receiveSnapshot() {
        payload = WearSnapshotStore.load(this)
        if (refreshing && (payload?.generatedAtEpochMillis ?: 0) > requestedAfter) {
            refreshing = false
            statusResource = R.string.wear_synced
            refreshJob?.cancel()
        }
    }
    private fun readData() = lifecycleScope.launch {
        try {
            withTimeout(10_000) { Wearable.getDataClient(this@WearMainActivity).dataItems.await() }.use { items ->
                items.forEach { WearSnapshotStore.accept(this@WearMainActivity, it) }
            }
            receiveSnapshot()
        } catch (error: Exception) {
            if (error is CancellationException && error !is TimeoutCancellationException) throw error
            statusResource = R.string.wear_phone_offline
        }
    }
    private fun refresh() {
        if (refreshing) return
        refreshing = true
        requestedAfter = payload?.generatedAtEpochMillis ?: 0L
        statusResource = R.string.wear_refresh_requested
        refreshJob = lifecycleScope.launch {
            var direct = false
            try {
                val grant = withContext(Dispatchers.IO) { WearSchoolGrantStore.load(this@WearMainActivity) }
                if (grant != null) {
                    direct = true
                    when (WearSchoolConnections.refresh(this@WearMainActivity)) {
                        WatchSchoolRefresh.VerificationRequired -> statusResource = R.string.wear_school_verify
                        is WatchSchoolRefresh.Loaded -> { payload = WearSnapshotStore.load(this@WearMainActivity); statusResource = R.string.wear_synced }
                    }
                    return@launch
                }
                val nodes = withTimeout(10_000) { Wearable.getNodeClient(this@WearMainActivity).connectedNodes.await() }
                check(nodes.isNotEmpty())
                withTimeout(10_000) { nodes.forEach { Wearable.getMessageClient(this@WearMainActivity).sendMessage(it.id, "/gradey/refresh", byteArrayOf()).await() } }
                readData().join()
                if (refreshing) {
                    delay(15_000)
                    if (refreshing) statusResource = R.string.wear_refresh_timeout
                }
            } catch (error: Exception) {
                if (error is CancellationException && error !is TimeoutCancellationException) throw error
                statusResource = if (error is WearSchoolException) wearSchoolErrorResource(error.reason)
                    else if (direct) R.string.wear_school_failed else R.string.wear_phone_offline
            } finally { refreshing = false }
        }
    }
}

@Composable
private fun WearGradeyScreen(payload: GradeyWearSyncPayload?, refreshing: Boolean, statusResource: Int?, now: Long, onRefresh: () -> Unit) {
    val context = LocalContext.current
    val listState = rememberScalingLazyListState()
    val timetable = payload?.timetable
    val selection = WearLessonSelector.select(timetable, now)
    val signedIn = payload?.isSignedIn == true
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.of("Europe/Prague") }
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(scalingLazyListState = listState) }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().background(MaterialTheme.colors.background),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Text("Gradey", color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption1, fontWeight = FontWeight.Bold) }
            item {
                if (!signedIn) StatusCard(stringResource(R.string.wear_sign_in), stringResource(R.string.wear_sign_in_detail))
                else when (selection) {
                    is GradeyWearLessonSelection.Lesson -> LessonHero(selection, now)
                    GradeyWearLessonSelection.NoTimetable -> StatusCard(stringResource(R.string.wear_open_gradey), stringResource(R.string.wear_load_timetable))
                    GradeyWearLessonSelection.NoLessons -> StatusCard(stringResource(R.string.wear_no_lessons), stringResource(R.string.wear_saved_week))
                    GradeyWearLessonSelection.Stale -> StatusCard(stringResource(R.string.wear_refresh_timetable), stringResource(R.string.wear_stale_detail))
                }
            }
            if (signedIn && selection != GradeyWearLessonSelection.Stale) {
                val upcoming = timetable?.days.orEmpty().flatMap { it.lessons }
                    .filter { (it.startEpochMillis ?: 0) > now && it.changeKind != NextLessonWidgetChangeKind.CANCELED }
                    .sortedBy { it.sortEpochMillis }.take(12).groupBy { Instant.ofEpochMilli(it.dayStartEpochMillis).atZone(zone).toLocalDate() }
                upcoming.forEach { (date, lessons) ->
                    item {
                        Text(when (date) {
                            today -> stringResource(R.string.wear_today)
                            today.plusDays(1) -> stringResource(R.string.wear_tomorrow)
                            else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM", locale))
                        }, color = MaterialTheme.colors.primary, style = MaterialTheme.typography.caption1, fontWeight = FontWeight.Bold)
                    }
                    lessons.forEach { lesson -> item { UpcomingLessonCard(lesson) } }
                }
                if (upcoming.isNotEmpty()) item { Text(stringResource(R.string.wear_see_phone), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) }
            }
            if (signedIn && timetable != null) item {
                val saved = Instant.ofEpochMilli(timetable.cachedAtEpochMillis).atZone(zone).format(DateTimeFormatter.ofPattern("EEE HH:mm", locale))
                Text(stringResource(R.string.wear_saved_at, saved), style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            statusResource?.let { message -> item { Text(stringResource(message), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center) } }
            item { Chip(onClick = onRefresh, enabled = !refreshing, label = { Text(stringResource(if (refreshing) R.string.wear_syncing else R.string.wear_refresh)) }, modifier = Modifier.fillMaxWidth()) }
            item { Chip(onClick = { context.startActivity(android.content.Intent(context, WearAIActivity::class.java)) }, label = { Text("Gradey AI") }, modifier = Modifier.fillMaxWidth()) }
            item { Chip(onClick = { context.startActivity(android.content.Intent(context, WearSchoolActivity::class.java)) }, label = { Text(stringResource(R.string.wear_school_title)) }, modifier = Modifier.fillMaxWidth()) }
        }
    }
}

@Composable
private fun LessonHero(selection: GradeyWearLessonSelection.Lesson, now: Long) {
    val lesson = selection.lesson
    val current = selection.timing == NextLessonWidgetTiming.CURRENT
    val start = lesson.startEpochMillis
    val end = lesson.endEpochMillis
    val progress = if (current && start != null && end != null && end > start) ((now - start).toFloat() / (end - start)).coerceIn(0f, 1f) else 0f
    val target = if (current) end else start
    val primary = MaterialTheme.colors.primary
    val progressLabel = stringResource(R.string.wear_progress, (progress * 100).toInt())
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(138.dp)) {
            Canvas(Modifier.fillMaxSize().semantics { if (current) contentDescription = progressLabel }) {
                val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
                drawArc(primary.copy(alpha = 0.16f), -90f, 360f, false, style = stroke)
                if (progress > 0f) drawArc(primary, -90f, 360f * progress, false, style = stroke)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 13.dp)) {
                Text(stringResource(if (current) R.string.wear_now else R.string.wear_next), color = primary, style = MaterialTheme.typography.caption1)
                Text(lesson.subjectAbbrev?.takeIf(String::isNotBlank) ?: lesson.subjectName?.takeIf(String::isNotBlank) ?: stringResource(R.string.wear_lesson), style = MaterialTheme.typography.title2, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
                if (target != null && target - now in 0..(120 * 60_000)) Text(stringResource(if (current) R.string.wear_ends_in else R.string.wear_starts_in, ceil((target - now) / 60_000.0).toInt()), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
            }
        }
        lesson.timeRange?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.body2) }
        lesson.room?.takeIf(String::isNotBlank)?.let { Text(stringResource(R.string.wear_room, it), style = MaterialTheme.typography.caption1, color = MaterialTheme.colors.primary) }
        lesson.teacher?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.caption2, maxLines = 2, textAlign = TextAlign.Center) }
        ChangeLabel(lesson.changeKind)
    }
}

@Composable
private fun UpcomingLessonCard(lesson: GradeyWearTimetableLesson) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colors.surface, RoundedCornerShape(16.dp)).padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(lesson.subjectName?.takeIf(String::isNotBlank) ?: lesson.subjectAbbrev?.takeIf(String::isNotBlank) ?: stringResource(R.string.wear_lesson), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.body2, textAlign = TextAlign.Center, maxLines = 2)
        lesson.timeRange?.let { Text(it, style = MaterialTheme.typography.caption1) }
        lesson.room?.let { Text(stringResource(R.string.wear_room, it), style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.primary) }
        ChangeLabel(lesson.changeKind)
    }
}

@Composable
private fun ChangeLabel(kind: NextLessonWidgetChangeKind) {
    val label = when (kind) {
        NextLessonWidgetChangeKind.NONE -> return
        NextLessonWidgetChangeKind.CANCELED -> R.string.wear_canceled
        NextLessonWidgetChangeKind.SUBSTITUTION -> R.string.wear_substitution
        NextLessonWidgetChangeKind.ROOM_CHANGED -> R.string.wear_room_changed
        NextLessonWidgetChangeKind.ADDED -> R.string.wear_added
    }
    Text(stringResource(label), color = Color(0xFFFFC17B), style = MaterialTheme.typography.caption2, textAlign = TextAlign.Center)
}

@Composable
private fun StatusCard(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.title3, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Text(detail, style = MaterialTheme.typography.caption1, textAlign = TextAlign.Center, color = MaterialTheme.colors.onSurfaceVariant)
    }
}

object WearSnapshotStore {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(context: android.content.Context): GradeyWearSyncPayload? =
        context.getSharedPreferences("gradey-watch", 0).getString("snapshot", null)?.let {
            runCatching { json.decodeFromString(GradeyWearSyncPayload.serializer(), it).takeIf { payload -> payload.schemaVersion == GradeyWearSyncPayload.CURRENT_SCHEMA_VERSION }?.withoutSecrets() }.getOrNull()
        }
    @Synchronized
    fun accept(context: android.content.Context, item: DataItem) {
        if (item.uri.path != "/gradey/snapshot") return
        val encoded = runCatching { DataMapItem.fromDataItem(item).dataMap.getString("payload") }.getOrNull() ?: return
        val next = runCatching { json.decodeFromString(GradeyWearSyncPayload.serializer(), encoded).withoutSecrets() }.getOrNull() ?: return
        if (next.schemaVersion != GradeyWearSyncPayload.CURRENT_SCHEMA_VERSION) return
        val previous = load(context)
        if (previous != null && (next.generatedAtEpochMillis < previous.generatedAtEpochMillis ||
                (next.generatedAtEpochMillis == previous.generatedAtEpochMillis && (next.isSignedIn || !previous.isSignedIn)))) return
        if (next == previous) return
        context.getSharedPreferences("gradey-watch", 0).edit()
            .putString("snapshot", json.encodeToString(GradeyWearSyncPayload.serializer(), next))
            .putString("source-node", item.uri.host).apply()
        WearSchoolGrantStore.reconcile(context, next, item.uri.host)
        WearSurfaceUpdates.request(context)
    }
    @Synchronized
    fun saveIndependent(context: android.content.Context, grant: WearSchoolStoredGrant, snapshot: GradeyWearSyncPayload) {
        if (!WearSchoolGrantStore.isCurrent(context, grant)) throw WearSchoolException(WearSchoolFailure.DISABLED)
        val current = load(context) ?: throw WearSchoolException(WearSchoolFailure.DISABLED)
        // Keep the phone publication timestamp: an older DataItem must not replace a newer direct cache.
        val next = current.copy(user = snapshot.user, timetable = snapshot.timetable)
        if (!context.getSharedPreferences("gradey-watch", 0).edit().putString("snapshot", json.encodeToString(GradeyWearSyncPayload.serializer(), next)).commit()) {
            throw WearSchoolException(WearSchoolFailure.INVALID_RESPONSE)
        }
        WearSurfaceUpdates.request(context)
    }
    fun sourceNode(context: android.content.Context): String? = context.getSharedPreferences("gradey-watch", 0).getString("source-node", null)
    private fun GradeyWearSyncPayload.withoutSecrets() = if (isSignedIn) copy(auth = null) else copy(auth = null, user = null, timetable = null, aiIdentity = null, schoolAccess = null)
}

class WearSyncService : WearableListenerService() {
    override fun onDataChanged(events: DataEventBuffer) {
        events.forEach { if (it.type == DataEvent.TYPE_CHANGED) WearSnapshotStore.accept(this, it.dataItem) }
    }
}
