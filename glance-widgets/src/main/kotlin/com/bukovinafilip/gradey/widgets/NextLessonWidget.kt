package com.bukovinafilip.gradey.widgets

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import kotlinx.serialization.json.Json
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bukovinafilip.gradey.domain.NextLessonSelector
import com.bukovinafilip.gradey.model.NextLessonWidgetLesson
import com.bukovinafilip.gradey.model.NextLessonWidgetSelection
import com.bukovinafilip.gradey.model.NextLessonWidgetSnapshot
import java.time.LocalDate
import java.time.ZoneId

class NextLessonWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = NextLessonWidget()
}

class NextLessonWidget : GlanceAppWidget() {
    override val sizeMode = androidx.glance.appwidget.SizeMode.Responsive(setOf(
        androidx.compose.ui.unit.DpSize(180.dp, 80.dp),
        androidx.compose.ui.unit.DpSize(240.dp, 120.dp),
        androidx.compose.ui.unit.DpSize(300.dp, 160.dp),
    ))
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val localized = localizedWidgetContext(context)
        provideContent { NextLessonWidgetContent(localized, NextLessonSelector.select(WidgetSnapshotStore.load(context))) }
    }
}

private val WidgetBackground = androidx.glance.color.ColorProvider(Color(0xFFEAF8F3), Color(0xFF102721))
private val WidgetHeading = androidx.glance.color.ColorProvider(Color(0xFF031F1B), Color(0xFFE6F6EF))
private val WidgetSecondary = androidx.glance.color.ColorProvider(Color(0xFF24534B), Color(0xFFB3CDC3))
private val WidgetAccent = androidx.glance.color.ColorProvider(Color(0xFF137C68), Color(0xFF6BDCC3))

@Composable
private fun NextLessonWidgetContent(context: Context, selection: NextLessonWidgetSelection) {
    val spacious = androidx.glance.LocalSize.current.height >= 120.dp
    Column(
        modifier = GlanceModifier.fillMaxSize()
            .clickable(actionStartActivity(Intent(Intent.ACTION_VIEW, Uri.parse("gradey://timetable")).setPackage(context.packageName)))
            .background(WidgetBackground).cornerRadius(16.dp).padding(if (spacious) 16.dp else 10.dp),
    ) {
        when (selection) {
            is NextLessonWidgetSelection.Lesson -> {
                val label = context.getString(if (selection.timing == com.bukovinafilip.gradey.model.NextLessonWidgetTiming.CURRENT) R.string.widget_now else R.string.widget_next)
                val change = when (selection.lesson.changeKind) {
                    com.bukovinafilip.gradey.model.NextLessonWidgetChangeKind.NONE -> null
                    com.bukovinafilip.gradey.model.NextLessonWidgetChangeKind.CANCELED -> context.getString(R.string.widget_canceled)
                    com.bukovinafilip.gradey.model.NextLessonWidgetChangeKind.SUBSTITUTION -> context.getString(R.string.widget_substitution)
                    com.bukovinafilip.gradey.model.NextLessonWidgetChangeKind.ROOM_CHANGED -> context.getString(R.string.widget_room_changed)
                    com.bukovinafilip.gradey.model.NextLessonWidgetChangeKind.ADDED -> context.getString(R.string.widget_added)
                }
                Text(listOfNotNull(label, change).joinToString(" · "), maxLines = 1,
                    style = TextStyle(color = WidgetAccent, fontWeight = FontWeight.Bold, fontSize = 12.sp))
                Spacer(GlanceModifier.height(if (spacious) 6.dp else 2.dp))
                Text(selection.lesson.subjectName?.takeIf(String::isNotBlank) ?: selection.lesson.subjectAbbrev?.takeIf(String::isNotBlank) ?: context.getString(R.string.widget_lesson),
                    maxLines = if (spacious) 2 else 1, style = TextStyle(color = WidgetHeading, fontWeight = FontWeight.Bold, fontSize = if (spacious) 19.sp else 16.sp), modifier = GlanceModifier.fillMaxWidth())
                if (spacious) selection.lesson.teacher?.takeIf(String::isNotBlank)?.let {
                    Text(it, maxLines = 1, style = TextStyle(color = WidgetSecondary, fontSize = 12.sp))
                }
                val zone = ZoneId.of("Europe/Prague")
                val date = java.time.Instant.ofEpochMilli(selection.lesson.dayStartEpochMillis).atZone(zone).toLocalDate()
                val datePrefix = date.takeIf { it != LocalDate.now(zone) }?.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", context.resources.configuration.locales[0]))
                Text(listOfNotNull(datePrefix, selection.lesson.timeRange?.takeIf(String::isNotBlank), selection.lesson.room?.takeIf(String::isNotBlank)?.let { context.getString(R.string.widget_room, it) }).joinToString(" · "),
                    maxLines = if (spacious) 2 else 1, style = TextStyle(color = WidgetSecondary, fontSize = 12.sp))
            }
            NextLessonWidgetSelection.NoSnapshot -> EmptyWidget(context.getString(R.string.widget_open_gradey), context.getString(R.string.widget_load_timetable), spacious)
            NextLessonWidgetSelection.NoLessons -> EmptyWidget(context.getString(R.string.widget_no_lessons), context.getString(R.string.widget_saved_week), spacious)
            NextLessonWidgetSelection.Stale -> EmptyWidget(context.getString(R.string.widget_refresh_timetable), context.getString(R.string.widget_stale_detail), spacious)
        }
    }
}

@Composable
private fun EmptyWidget(title: String, subtitle: String, spacious: Boolean) {
    Text(title, maxLines = 1, style = TextStyle(color = WidgetHeading, fontWeight = FontWeight.Bold, fontSize = if (spacious) 19.sp else 16.sp))
    Spacer(GlanceModifier.height(4.dp))
    Text(subtitle, maxLines = if (spacious) 3 else 2, style = TextStyle(color = WidgetSecondary, fontSize = 12.sp))
}

private fun localizedWidgetContext(context: Context): Context {
    if (android.os.Build.VERSION.SDK_INT >= 33) return context
    val tag = com.bukovinafilip.gradey.model.AppLanguage.fromStorage(context.getSharedPreferences("gradey-app-preferences", 0).getString("language", null)).languageTag ?: return context
    return context.createConfigurationContext(android.content.res.Configuration(context.resources.configuration).apply {
        setLocales(android.os.LocaleList.forLanguageTags(tag))
    })
}

object WidgetSnapshotStore {
    private val json = Json { ignoreUnknownKeys = true }
    fun load(context: Context): NextLessonWidgetSnapshot? = context.getSharedPreferences("gradey-widget", 0)
        .getString("snapshot", null)?.let { runCatching { json.decodeFromString(NextLessonWidgetSnapshot.serializer(), it) }.getOrNull() }
    suspend fun save(context: Context, snapshot: NextLessonWidgetSnapshot?) {
        val prefs = context.getSharedPreferences("gradey-widget", 0)
        if (snapshot == null) prefs.edit().clear().commit()
        else prefs.edit().putString("snapshot", json.encodeToString(NextLessonWidgetSnapshot.serializer(), snapshot)).commit()
        NextLessonWidget().updateAll(context)
        scheduleWidgetRefresh(context)
    }
}
