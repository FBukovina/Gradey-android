package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.PlannerLessonReference
import com.bukovinafilip.gradey.model.PlannerSubjectReference
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.ScheduledDay
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.TimetableWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

interface PersonalPlannerRepository {
    suspend fun load(): List<PersonalPlannerItem>
    suspend fun save(item: PersonalPlannerItem): List<PersonalPlannerItem>
    suspend fun delete(id: String): List<PersonalPlannerItem>
    suspend fun setCompleted(id: String, completed: Boolean): List<PersonalPlannerItem>
    /** Rebind a verified same-school migration in one durable transaction. */
    suspend fun rebindSchoolScope(from: String, to: String): List<PersonalPlannerItem> =
        throw UnsupportedOperationException("This planner store does not support account migration.")
}

object PersonalPlanner {
    fun validate(item: PersonalPlannerItem): PersonalPlannerItem {
        require(item.id.isNotBlank()) { "This planner item has no identifier." }
        require(item.title.isNotBlank()) { "Give your planner item a title." }
        require(item.title.length <= 300) { "Keep the title under 300 characters." }
        require(item.notes.length <= 20_000) { "Keep notes under 20,000 characters." }
        item.linkedDate?.let { require(parseDate(it) != null) { "Choose a valid linked day." } }
        item.dueDate?.let { require(parseDate(it) != null) { "Choose a valid due date." } }
        val dueTime = item.dueTime
        if (dueTime != null) require(item.dueDate != null && parseTime(dueTime) != null) { "Choose a valid due time and date." }
        require(runCatching { ZoneId.of(item.timeZoneID) }.isSuccess) { "The saved time zone is unavailable." }
        return item.copy(title = item.title.trim(), notes = item.notes.trim(), updatedAtEpochMillis = System.currentTimeMillis())
    }

    fun zone(item: PersonalPlannerItem): ZoneId = runCatching { ZoneId.of(item.timeZoneID) }.getOrDefault(ZoneId.systemDefault())
    fun orderingDate(item: PersonalPlannerItem): LocalDate? = item.dueDate?.let(::parseDate)
        ?: item.lesson?.startEpochMillis?.let { Instant.ofEpochMilli(it).atZone(zone(item)).toLocalDate() }
        ?: item.linkedDate?.let(::parseDate)
    fun orderingInstant(item: PersonalPlannerItem): Instant? = item.dueDate?.let(::parseDate)?.let { date ->
        date.atTime(item.dueTime?.let(::parseTime) ?: LocalTime.MIDNIGHT).atZone(zone(item)).toInstant()
    } ?: item.lesson?.startEpochMillis?.let(Instant::ofEpochMilli)
        ?: item.linkedDate?.let(::parseDate)?.atStartOfDay(zone(item))?.toInstant()

    fun isOverdue(item: PersonalPlannerItem, now: Instant = Instant.now()): Boolean {
        if (item.isCompleted) return false
        val date = orderingDate(item) ?: return false
        return if (item.dueTime == null) date < now.atZone(zone(item)).toLocalDate() else orderingInstant(item)?.isBefore(now) == true
    }

    fun linked(lesson: ScheduledLesson, day: ScheduledDay, scope: String, zone: ZoneId = ZoneId.of("Europe/Prague")): PersonalPlannerItem {
        val date = day.date?.take(10)?.let(::parseDate)
        val subjectID = lesson.subjectID?.takeIf { it.isNotBlank() }
        return PersonalPlannerItem(
            subject = subjectID?.let { PlannerSubjectReference(scope, it, lesson.subjectName ?: lesson.title, lesson.subjectAbbrev) },
            lesson = PlannerLessonReference(scope, lesson.id, date?.toString() ?: day.id, lesson.hour.id, subjectID,
                lesson.groupIDs.sorted(), lessonDate(date, lesson.hour.beginTime, zone)?.toEpochMilli(),
                lessonDate(date, lesson.hour.endTime, zone)?.toEpochMilli(), lesson.hour.caption, lesson.teacherName ?: lesson.teacherAbbrev, lesson.roomTitle),
            linkedDate = date?.toString(), timeZoneID = zone.id,
        )
    }

    fun matches(reference: PlannerLessonReference, lesson: ScheduledLesson, day: ScheduledDay, scope: String): Boolean =
        reference.scope == scope && reference.dayKey == (day.date?.take(10) ?: day.id) && reference.hourID == lesson.hour.id &&
            reference.subjectID == lesson.subjectID && reference.groupIDs.sorted() == lesson.groupIDs.sorted()

    fun withDueInstant(item: PersonalPlannerItem, instant: Instant): PersonalPlannerItem {
        val local = instant.atZone(zone(item))
        return item.copy(dueDate = local.toLocalDate().toString(), dueTime = local.toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm")))
    }

    internal fun parseDate(raw: String): LocalDate? = runCatching { LocalDate.parse(raw) }.getOrNull()
    internal fun parseTime(raw: String): LocalTime? {
        val parts = Regex("^([01]?[0-9]|2[0-3]):([0-5][0-9])$").matchEntire(raw.trim()) ?: return null
        return LocalTime.of(parts.groupValues[1].toInt(), parts.groupValues[2].toInt())
    }
    private fun lessonDate(date: LocalDate?, raw: String, zone: ZoneId): Instant? = date?.let { day -> parseTime(raw)?.let { day.atTime(it).atZone(zone).toInstant() } }
}

object PlannerNextLessonSelector {
    fun nextDate(reference: PlannerLessonReference, weeks: List<TimetableWeek>, zone: ZoneId): Instant? {
        val subjectID = reference.subjectID?.takeIf { it.isNotBlank() } ?: return null
        val start = reference.startEpochMillis?.let(Instant::ofEpochMilli) ?: return null
        return weeks.flatMap { it.days }.filter { it.dayType.lowercase() in setOf("workday", "schoolday", "school", "normal", "") }
            .flatMap { day ->
                val date = day.date?.take(10)?.let(PersonalPlanner::parseDate) ?: return@flatMap emptyList()
                day.lessons.mapNotNull { lesson ->
                    if (lesson.changeKind == LessonChangeKind.CANCELED || lesson.subjectID != subjectID ||
                        (reference.groupIDs.isNotEmpty() && lesson.groupIDs.isNotEmpty() && reference.groupIDs.none { it in lesson.groupIDs })) return@mapNotNull null
                    val time = PersonalPlanner.parseTime(lesson.hour.beginTime) ?: return@mapNotNull null
                    date.atTime(time).atZone(zone).toInstant().takeIf { it > start }
                }
            }.minOrNull()
    }
}

class PlannerLessonResolver(
    private val loadWeek: suspend (String) -> TimetableWeek,
    private val cachedWeek: suspend (String) -> TimetableWeek? = { null },
    private val currentScope: () -> String?,
) {
    suspend fun nextDate(item: PersonalPlannerItem, displayedWeek: TimetableWeek? = null): Instant? {
        val reference = item.lesson ?: return null
        val start = reference.startEpochMillis?.let(Instant::ofEpochMilli) ?: return null
        if (reference.subjectID.isNullOrBlank()) return null
        val zone = PersonalPlanner.zone(item)
        val monday = TimetableDates.monday(start.atZone(zone).toLocalDate())
        for (offset in 0L..4L) {
            currentCoroutineContext().ensureActive()
            check(currentScope() == reference.scope) { "The school account changed. Reopen this item from the matching account." }
            val anchor = monday.plusWeeks(offset).toString()
            val week = if (offset == 0L && displayedWeek?.weekStart?.take(10) == anchor && displayedWeek.days.any { it.date != null }) displayedWeek
            else try { loadWeek(anchor) } catch (error: CancellationException) { throw error } catch (_: Exception) {
                cachedWeek(anchor) ?: throw IllegalStateException("The timetable is unavailable for $anchor. Reconnect and try again.")
            }
            currentCoroutineContext().ensureActive()
            check(currentScope() == reference.scope) { "The school account changed while looking up the next lesson." }
            check(week.weekStart.take(10) == anchor) { "The school returned a different timetable week. Try again later." }
            PlannerNextLessonSelector.nextDate(reference, listOf(week), zone)?.let { return it }
        }
        return null
    }
}

data class PlannerCalendarEvent(val title: String, val description: String, val start: Instant, val end: Instant, val allDay: Boolean, val date: LocalDate?, val timeZoneID: String)

object PlannerCalendarExport {
    fun event(item: PersonalPlannerItem): PlannerCalendarEvent? {
        val zone = PersonalPlanner.zone(item)
        val date = PersonalPlanner.orderingDate(item) ?: return null
        val allDay = item.dueDate?.let { item.dueTime == null } ?: (item.lesson?.startEpochMillis == null)
        val start = if (allDay) date.atStartOfDay(zone).toInstant() else PersonalPlanner.orderingInstant(item) ?: return null
        val lessonEnd = item.lesson?.endEpochMillis?.let(Instant::ofEpochMilli)
        val end = if (allDay) date.plusDays(1).atStartOfDay(zone).toInstant()
        else if (item.dueDate == null && lessonEnd != null && lessonEnd > start) lessonEnd else start.plusSeconds(30 * 60)
        val title = "${if (item.isCompleted) "✓ " else ""}${item.subject?.name?.let { "$it · " }.orEmpty()}${item.title}"
        val description = listOf(typeTitle(item.type), item.notes, "Created in Gradey.").filter { it.isNotBlank() }.joinToString("\n\n")
        return PlannerCalendarEvent(title, description, start, end, allDay, date, zone.id)
    }

    fun ics(item: PersonalPlannerItem): String? {
        val event = event(item) ?: return null
        val utc = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
        val day = DateTimeFormatter.BASIC_ISO_DATE
        val lines = mutableListOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Gradey//Personal Planner//EN", "CALSCALE:GREGORIAN", "BEGIN:VEVENT",
            "UID:${escape(item.id)}@gradey.local", "DTSTAMP:${utc.format(Instant.ofEpochMilli(item.updatedAtEpochMillis))}")
        if (event.allDay) {
            lines += "DTSTART;VALUE=DATE:${day.format(event.date)}"
            lines += "DTEND;VALUE=DATE:${day.format(event.date!!.plusDays(1))}"
        } else {
            lines += "DTSTART:${utc.format(event.start)}"
            lines += "DTEND:${utc.format(event.end)}"
        }
        lines += listOf("SUMMARY:${escape(event.title)}", "DESCRIPTION:${escape(event.description)}", "END:VEVENT", "END:VCALENDAR")
        return lines.joinToString("\r\n") { fold(it) } + "\r\n"
    }

    fun typeTitle(type: PlannerItemType): String = when (type) {
        PlannerItemType.HOMEWORK -> "Homework"
        PlannerItemType.TEST -> "Test"
        PlannerItemType.PRESENTATION -> "Presentation"
        PlannerItemType.NOTE -> "Note"
        PlannerItemType.TASK -> "Task"
    }
    private fun escape(value: String) = value.replace("\\", "\\\\").replace("\r\n", "\n").replace("\r", "\n").replace("\n", "\\n").replace(";", "\\;").replace(",", "\\,")
    /** RFC 5545 folds at 75 UTF-8 octets without splitting a code point. */
    private fun fold(value: String): String {
        val result = StringBuilder()
        var octets = 0
        value.codePoints().forEach { point ->
            val text = String(Character.toChars(point))
            val bytes = text.toByteArray(Charsets.UTF_8).size
            if (octets + bytes > 75) { result.append("\r\n "); octets = 1 }
            result.append(text); octets += bytes
        }
        return result.toString()
    }
}
