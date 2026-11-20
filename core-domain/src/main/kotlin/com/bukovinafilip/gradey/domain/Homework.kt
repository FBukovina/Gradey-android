package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.HomeworkDTO
import com.bukovinafilip.gradey.model.HomeworkEntity
import com.bukovinafilip.gradey.model.HomeworkResponse
import com.bukovinafilip.gradey.model.PersonalPlannerItem
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.PlannerSubjectReference
import com.bukovinafilip.gradey.model.ScheduledLesson
import com.bukovinafilip.gradey.model.SchoolAttachmentDTO
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/** A file attached to homework or a Komens message. */
data class SchoolAttachment(val id: String, val name: String, val size: Long?)

/** A homework from the school, read-only apart from the student's done tick (iOS `Homework`). */
data class Homework(
    val id: String,
    val subjectID: String?,
    val subjectName: String?,
    val subjectAbbrev: String?,
    val teacherName: String?,
    val content: String,
    val notice: String?,
    /** Civil days in the school's time zone; Bakaláři sends them as midnight. */
    val assignedOn: LocalDate?,
    val dueOn: LocalDate?,
    val isDone: Boolean,
    val isClosed: Boolean,
    val isElectronic: Boolean,
    val attachments: List<SchoolAttachment>,
) {
    /** The first line, for rows; null when the homework has no text and the row names it another way. */
    val firstLine: String? get() = content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()

    val subjectLabel: String? get() = subjectName ?: subjectAbbrev

    /** Still to do: not ticked off and not closed by the teacher. */
    val isOpen: Boolean get() = !isDone && !isClosed

    /**
     * The homework as a Planner item, for lists that mix both. Its id starts with [PlannerIDPrefix],
     * so it is never saved or edited as one.
     */
    fun plannerItem(scope: String, fallbackTitle: String): PersonalPlannerItem = PersonalPlannerItem(
        id = PlannerIDPrefix + id,
        title = firstLine ?: subjectName ?: fallbackTitle,
        type = PlannerItemType.HOMEWORK,
        subject = subjectLabel?.let { PlannerSubjectReference(scope, subjectID.orEmpty(), it, subjectAbbrev) },
        dueDate = dueOn?.toString(),
        isCompleted = isDone,
        timeZoneID = SchoolZone.id,
    )

    companion object {
        /** Bakaláři dates are school days, so they are read in Prague time. */
        val SchoolZone: ZoneId = ZoneId.of("Europe/Prague")

        const val PlannerIDPrefix = "school-homework:"

        /** The homework id behind a Planner item made by [plannerItem], or null for the user's own items. */
        fun idFromPlannerItem(plannerItemID: String): String? = plannerItemID.takeIf { it.startsWith(PlannerIDPrefix) }?.removePrefix(PlannerIDPrefix)

        fun from(dto: HomeworkDTO): Homework = Homework(
            id = dto.id,
            subjectID = dto.subject?.id.trimmedOrNull(),
            subjectName = dto.subject?.name.trimmedOrNull(),
            subjectAbbrev = dto.subject?.abbrev.trimmedOrNull(),
            teacherName = dto.teacher?.name.trimmedOrNull(),
            content = dto.content.orEmpty().trim(),
            notice = dto.notice.trimmedOrNull(),
            assignedOn = schoolDate(dto.dateStart),
            dueOn = schoolDate(dto.dateEnd),
            isDone = dto.done == true,
            isClosed = dto.closed == true,
            isElectronic = dto.electronic == true,
            attachments = dto.attachments.orEmpty().map { SchoolAttachment(it.id, it.name.trimmedOrNull() ?: it.id, it.size?.takeIf { size -> size > 0 }) },
        )

        fun from(response: HomeworkResponse): List<Homework> = HomeworkList.sorted(response.homeworks.orEmpty().map(::from))

        /**
         * "2020-11-10T00:00:00+01:00", with or without fractions, or a bare day. Komens and report
         * cards send their dates the same way.
         */
        fun schoolDate(value: String?): LocalDate? {
            val text = value?.trim().orEmpty()
            if (text.isEmpty()) return null
            runCatching { OffsetDateTime.parse(text).atZoneSameInstant(SchoolZone).toLocalDate() }.getOrNull()?.let { return it }
            return runCatching { LocalDate.parse(text.take(10)) }.getOrNull()
        }

        private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
    }
}

/** What the lists of homework need (iOS `HomeworkStore`'s read side). */
object HomeworkList {
    /** By due day, undated last; then by subject. */
    fun sorted(items: List<Homework>): List<Homework> = items.sortedWith(
        compareBy<Homework> { it.dueOn == null }.thenBy { it.dueOn }.thenBy { it.subjectLabel.orEmpty() }.thenBy { it.id },
    )

    /** The Planner's Upcoming or Completed list, narrowed to one due day when a day is in focus. */
    fun visible(items: List<Homework>, completed: Boolean, focusedDay: LocalDate? = null): List<Homework> = items.filter { item ->
        (if (completed) item.isDone else item.isOpen) && (focusedDay == null || item.dueOn == focusedDay)
    }

    /** Open homework that is due today or later. */
    fun upcoming(items: List<Homework>, today: LocalDate): List<Homework> = items.filter { it.isOpen && it.dueOn != null && it.dueOn >= today }

    /** Homework given in this lesson, then homework due in it. */
    fun forLesson(items: List<Homework>, lesson: ScheduledLesson, date: LocalDate?): List<Homework> {
        val assigned = lesson.homeworkIDs.mapNotNull { id -> items.firstOrNull { it.id == id } }
        val subjectID = lesson.subjectID
        val due = if (subjectID == null || date == null) emptyList() else items.filter {
            it.subjectID == subjectID && it.id !in lesson.homeworkIDs && it.dueOn == date
        }
        return assigned + due
    }

    /** The list after a refresh: a tick still on its way to the school keeps the state the user just chose. */
    fun merged(fresh: List<Homework>, shown: List<Homework>, pendingIDs: Set<String>): List<Homework> {
        if (pendingIDs.isEmpty()) return fresh
        val local = shown.associateBy { it.id }
        return fresh.map { item -> if (item.id in pendingIDs) local[item.id]?.let { item.copy(isDone = it.isDone) } ?: item else item }
    }

    fun withDone(items: List<Homework>, id: String, isDone: Boolean): List<Homework> = items.map { if (it.id == id) it.copy(isDone = isDone) else it }

    /** The request range: the last month covers the Completed list; three months ahead covers long projects. */
    fun requestRange(today: LocalDate): Pair<LocalDate, LocalDate> = today.minusDays(30) to today.plusDays(90)
}

/** Sample homework for demo mode: one per lesson that has some this week, one overdue, one done. */
object HomeworkDemoData {
    fun response(today: LocalDate = LocalDate.now(Homework.SchoolZone)): HomeworkResponse {
        val monday = TimetableDates.monday(today)
        fun day(date: LocalDate) = "${date}T00:00:00+01:00"
        fun homework(id: String, subject: HomeworkEntity, teacher: String, given: LocalDate, due: LocalDate, content: String, done: Boolean = false,
                     notice: String? = null, electronic: Boolean = false, attachments: List<SchoolAttachmentDTO> = emptyList()) = HomeworkDTO(
            id = id, dateStart = day(given), dateEnd = day(due), content = content, notice = notice, done = done, closed = false, electronic = electronic,
            subject = subject, teacher = HomeworkEntity(name = teacher), attachments = attachments,
        )
        val english = HomeworkEntity("english", "AJ", "Angličtina")
        val czech = HomeworkEntity("czech", "ČJ", "Český jazyk")
        val math = HomeworkEntity("math", "M", "Matematika")
        val biology = HomeworkEntity("biology", "Bi", "Biologie")
        return HomeworkResponse(listOf(
            homework("homework-english", english, "Eva Svobodová", monday.plusDays(1), monday.plusDays(8), "Workbook p. 34, exercises 2–5\nWrite the answers in full sentences."),
            homework("homework-czech", czech, "Eva Svobodová", monday.plusDays(2), monday.plusDays(9), "Slohová práce: vypravování (250 slov)",
                notice = "Odevzdat na papíře", attachments = listOf(SchoolAttachmentDTO("demo-attachment-zadani", "Zadání slohové práce.pdf", "application/pdf", 184_320))),
            homework("homework-math", math, "Jan Novák", monday.plusDays(3), monday.plusDays(7), "Sbírka str. 58, příklady 4 až 9", electronic = true),
            homework("homework-biology", biology, "Petr Dvořák", monday.plusDays(4), monday.plusDays(11), "Protokol z laboratorní práce"),
            homework("homework-math-done", math, "Jan Novák", monday.minusDays(6), monday.minusDays(1), "Pracovní list: lineární rovnice", done = true),
        ))
    }
}
