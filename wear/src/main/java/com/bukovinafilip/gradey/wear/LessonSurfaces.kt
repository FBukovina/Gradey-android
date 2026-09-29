package com.bukovinafilip.gradey.wear

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.DimensionBuilders.expand
import androidx.wear.protolayout.ModifiersBuilders
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.material3.*
import androidx.wear.protolayout.types.argb
import androidx.wear.protolayout.types.layoutString
import androidx.wear.tiles.Material3TileService
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders
import androidx.wear.tiles.TileService
import androidx.wear.tiles.tile
import androidx.wear.tiles.timeline
import androidx.wear.tiles.timelineEntry
import androidx.wear.tiles.timeInterval
import androidx.wear.watchface.complications.data.*
import androidx.wear.watchface.complications.datasource.*
import com.bukovinafilip.gradey.domain.WearSurfaceInterval
import com.bukovinafilip.gradey.domain.WearSurfaceTimeline
import com.bukovinafilip.gradey.model.*
import java.time.Instant
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

class GradeyLessonTileService : Material3TileService(
    defaultColorScheme = ColorScheme(primary = 0xFF6BDCC3.toInt().argb, onPrimary = 0xFF002B23.toInt().argb),
) {
    override suspend fun MaterialScope.tileResponse(requestParams: RequestBuilders.TileRequest): TileBuilders.Tile {
        val payload = WearSnapshotStore.load(this@GradeyLessonTileService)
        val intervals = WearSurfaceTimeline.build(payload, System.currentTimeMillis())
        val entries = intervals.map { interval ->
            timelineEntry(lessonLayout(interval.selection, payload?.isSignedIn == true), timeInterval(interval.startEpochMillis.milliseconds, interval.endEpochMillis.milliseconds))
        }
        // Untimed fallback never retains an expired lesson after the 24h timeline.
        return tile(timeline(*(entries + timelineEntry(lessonLayout(GradeyWearLessonSelection.Stale, payload?.isSignedIn == true))).toTypedArray()), freshness = 1.hours)
    }

    private fun MaterialScope.lessonLayout(selection: GradeyWearLessonSelection, signedIn: Boolean): androidx.wear.protolayout.LayoutElementBuilders.LayoutElement {
        val click = ModifiersBuilders.Clickable.Builder().setId("open-gradey")
            .setOnClick(ActionBuilders.LaunchAction.Builder().setAndroidActivity(ActionBuilders.AndroidActivity.Builder()
                .setPackageName(packageName).setClassName(WearMainActivity::class.java.name).build()).build()).build()
        val current = selection as? GradeyWearLessonSelection.Lesson
        val lesson = current?.lesson
        val status = when {
            !signedIn -> getString(R.string.wear_sign_in)
            selection == GradeyWearLessonSelection.Stale -> getString(R.string.wear_refresh_timetable)
            selection == GradeyWearLessonSelection.NoTimetable -> getString(R.string.wear_load_timetable)
            else -> getString(R.string.wear_no_lessons)
        }
        return primaryLayout(
            titleSlot = { text("Gradey".layoutString) },
            mainSlot = {
                textDataCard(
                    onClick = click, width = expand(),
                    title = { text((current?.let { getString(if (it.timing == NextLessonWidgetTiming.CURRENT) R.string.wear_now else R.string.wear_next) } ?: getString(R.string.wear_lesson)).layoutString) },
                    content = { text((lesson?.subjectAbbrev?.takeIf(String::isNotBlank) ?: lesson?.subjectName?.takeIf(String::isNotBlank) ?: status).layoutString, maxLines = 2) },
                    secondaryText = { text(listOfNotNull(lesson?.timeRange, lesson?.room).joinToString(" · ").ifBlank { getString(R.string.wear_open_gradey) }.layoutString, maxLines = 2) },
                )
            },
            bottomSlot = { textEdgeButton(onClick = click) { text(getString(R.string.wear_open_gradey).layoutString) } },
        )
    }
}

class GradeyLessonComplicationService : SuspendingTimelineComplicationDataSourceService() {
    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationDataTimeline {
        val now = System.currentTimeMillis()
        val payload = WearSnapshotStore.load(this)
        val intervals = WearSurfaceTimeline.build(payload, now)
        return ComplicationDataTimeline(
            defaultComplicationData = NoDataComplicationData(),
            timelineEntries = intervals.map { interval ->
                TimelineEntry(TimeInterval(Instant.ofEpochMilli(interval.startEpochMillis), Instant.ofEpochMilli(interval.endEpochMillis)),
                    complication(request.complicationType, interval))
            },
        )
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData = when (type) {
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain(getString(R.string.wear_complication_preview)), plain(getString(R.string.wear_complication_label))).setTitle(plain("Gradey")).build()
        ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(50f, 0f, 100f, plain(getString(R.string.wear_complication_label))).setText(plain("MAT")).build()
        else -> ShortTextComplicationData.Builder(plain("MAT"), plain(getString(R.string.wear_complication_label))).setTitle(plain("09:00")).build()
    }

    private fun complication(type: ComplicationType, interval: WearSurfaceInterval): ComplicationData {
        val selection = interval.selection as? GradeyWearLessonSelection.Lesson ?: return NoDataComplicationData()
        val lesson = selection.lesson
        val current = selection.timing == NextLessonWidgetTiming.CURRENT
        val label = getString(if (current) R.string.wear_now else R.string.wear_next)
        val subject = lesson.subjectAbbrev?.takeIf(String::isNotBlank) ?: lesson.subjectName?.takeIf(String::isNotBlank) ?: getString(R.string.wear_lesson)
        val description = plain(listOfNotNull(label, lesson.subjectName ?: subject, lesson.timeRange, lesson.room).joinToString(" · "))
        val action = PendingIntent.getActivity(this, 0, Intent(this, WearMainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val range = TimeRange.between(Instant.ofEpochMilli(interval.startEpochMillis), Instant.ofEpochMilli(interval.endEpochMillis - 1))
        val target = if (current) lesson.endEpochMillis else lesson.startEpochMillis
        val countdown = target?.let { TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(Instant.ofEpochMilli(it))).setDisplayAsNow(false).build() }
        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(subject.take(7)), description)
                .setTitle(countdown ?: plain(label)).setTapAction(action).setValidTimeRange(range)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain(listOfNotNull(lesson.subjectName ?: subject, lesson.timeRange, lesson.room).joinToString(" · ")), description)
                .setTitle(plain(label)).setTapAction(action).setValidTimeRange(range)
                .build()
            ComplicationType.RANGED_VALUE -> {
                val start = lesson.startEpochMillis
                val end = lesson.endEpochMillis
                val elapsed = if (current && start != null && end != null && end > start) ((interval.startEpochMillis - start).toFloat() / (end - start) * 100).coerceIn(0f, 100f) else 0f
                val builder = if (Build.VERSION.SDK_INT >= 33 && current && start != null && end != null && end > start) {
                    val dynamic = DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(start))
                        .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision()).toIntSeconds().asFloat().times(100f / ((end - start) / 1000f))
                    RangedValueComplicationData.Builder(dynamic, elapsed, 0f, 100f, description)
                } else RangedValueComplicationData.Builder(elapsed, 0f, 100f, description)
                builder.setText(plain(subject.take(7))).setTitle(countdown ?: plain(label)).setTapAction(action).setValidTimeRange(range)
                    .build()
            }
            else -> NoDataComplicationData()
        }
    }
    private fun plain(text: String) = PlainComplicationText.Builder(text).build()
}

object WearSurfaceUpdates {
    fun request(context: Context) {
        runCatching { TileService.getUpdater(context).requestUpdate(GradeyLessonTileService::class.java) }
        runCatching { ComplicationDataSourceUpdateRequester.create(context, ComponentName(context, GradeyLessonComplicationService::class.java)).requestUpdateAll() }
    }
}
