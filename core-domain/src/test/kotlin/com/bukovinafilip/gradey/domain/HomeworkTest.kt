package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.HomeworkDTO
import com.bukovinafilip.gradey.model.HomeworkEntity
import com.bukovinafilip.gradey.model.HomeworkResponse
import com.bukovinafilip.gradey.model.PlannerItemType
import com.bukovinafilip.gradey.model.SchoolAttachmentDTO
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

class HomeworkTest {
    private fun dto(id: String, due: String?, subject: String? = "Matematika", done: Boolean? = false, closed: Boolean? = false, content: String? = "Úkol $id") =
        HomeworkDTO(id = id, dateEnd = due, content = content, done = done, closed = closed, subject = subject?.let { HomeworkEntity(it.lowercase(), it.take(1), it) })

    @Test fun bakalariPayloadBecomesAHomeworkWithSchoolDaysAndTrimmedText() {
        val homework = Homework.from(HomeworkDTO(
            id = "h1", dateStart = "2026-11-10T00:00:00+01:00", dateEnd = "2026-11-17T00:00:00.000+01:00", content = "  \nWorkbook p. 34  \nFull sentences.\n",
            notice = "   ", done = null, closed = null, electronic = true, subject = HomeworkEntity(" 7 ", " AJ ", "  "), teacher = HomeworkEntity(name = " Eva S. "),
            attachments = listOf(SchoolAttachmentDTO("a1", "  ", size = 0), SchoolAttachmentDTO("a2", " zadani.pdf ", size = 2048)),
        ))
        assertThat(homework.assignedOn).isEqualTo(LocalDate.parse("2026-11-10"))
        assertThat(homework.dueOn).isEqualTo(LocalDate.parse("2026-11-17"))
        assertThat(homework.firstLine).isEqualTo("Workbook p. 34")
        assertThat(homework.content).isEqualTo("Workbook p. 34  \nFull sentences.")
        assertThat(homework.notice).isNull()
        assertThat(homework.subjectID).isEqualTo("7")
        // A blank name falls back to the abbreviation.
        assertThat(homework.subjectName).isNull()
        assertThat(homework.subjectLabel).isEqualTo("AJ")
        assertThat(homework.teacherName).isEqualTo("Eva S.")
        assertThat(homework.isDone).isFalse()
        assertThat(homework.isOpen).isTrue()
        assertThat(homework.isElectronic).isTrue()
        // An attachment without a name is called by its id; a size of zero is not shown.
        assertThat(homework.attachments).containsExactly(SchoolAttachment("a1", "a1", null), SchoolAttachment("a2", "zadani.pdf", 2048)).inOrder()
        assertThat(Homework.from(dto("empty", null, content = null)).firstLine).isNull()
    }

    @Test fun schoolDatesAreCivilDaysInPragueWhateverTheOffset() {
        assertThat(Homework.schoolDate("2026-11-10T00:00:00+01:00")).isEqualTo(LocalDate.parse("2026-11-10"))
        // Midnight in Prague written in UTC is still that Prague day.
        assertThat(Homework.schoolDate("2026-11-09T23:00:00Z")).isEqualTo(LocalDate.parse("2026-11-10"))
        assertThat(Homework.schoolDate("2026-06-01T00:00:00+02:00")).isEqualTo(LocalDate.parse("2026-06-01"))
        assertThat(Homework.schoolDate("2026-11-10")).isEqualTo(LocalDate.parse("2026-11-10"))
        assertThat(Homework.schoolDate("2026-11-10T00:00:00")).isEqualTo(LocalDate.parse("2026-11-10"))
        assertThat(Homework.schoolDate("")).isNull()
        assertThat(Homework.schoolDate(null)).isNull()
        assertThat(Homework.schoolDate("soon")).isNull()
    }

    @Test fun listsAreSortedByDueDayAndSplitIntoUpcomingAndCompleted() {
        val items = Homework.from(HomeworkResponse(listOf(
            dto("undated", null), dto("late", "2026-11-20"), dto("b", "2026-11-12", subject = "Fyzika"), dto("a", "2026-11-12", subject = "Biologie"),
            dto("done", "2026-11-11", done = true), dto("closed", "2026-11-13", closed = true), dto("overdue", "2026-11-05"),
        )))
        assertThat(items.map { it.id }).containsExactly("overdue", "done", "a", "b", "closed", "late", "undated").inOrder()
        assertThat(HomeworkList.visible(items, completed = false).map { it.id }).containsExactly("overdue", "a", "b", "late", "undated").inOrder()
        // Closed by the teacher but not ticked: in neither list, like on iOS.
        assertThat(HomeworkList.visible(items, completed = true).map { it.id }).containsExactly("done")
        assertThat(HomeworkList.visible(items, completed = false, focusedDay = LocalDate.parse("2026-11-12")).map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(HomeworkList.upcoming(items, LocalDate.parse("2026-11-12")).map { it.id }).containsExactly("a", "b", "late").inOrder()
        assertThat(Homework.from(HomeworkResponse(null))).isEmpty()
        assertThat(HomeworkList.requestRange(LocalDate.parse("2026-11-12"))).isEqualTo(LocalDate.parse("2026-10-13") to LocalDate.parse("2027-02-10"))
    }

    @Test fun aLessonShowsTheHomeworkGivenInItThenTheHomeworkDueInIt() {
        val today = LocalDate.parse("2026-11-19")
        val week = DemoData.timetableFor(today.toString())
        val items = Homework.from(HomeworkDemoData.response(today))
        val tuesday = week.days[1]
        val english = tuesday.lessons.first { it.subjectID == "english" }
        assertThat(english.homeworkIDs).containsExactly("homework-english")
        assertThat(HomeworkList.forLesson(items, english, LocalDate.parse(tuesday.date!!.take(10))).map { it.id }).containsExactly("homework-english")
        // Next Monday's maths lesson is where Thursday's homework is due.
        val nextMonday = TimetableDates.monday(today).plusDays(7)
        val maths = DemoData.timetableFor(nextMonday.toString()).days[0].lessons.first { it.subjectID == "math" }
        assertThat(HomeworkList.forLesson(items, maths, nextMonday).map { it.id }).containsExactly("homework-math")
        assertThat(HomeworkList.forLesson(items, maths, null)).isEmpty()
        assertThat(HomeworkList.forLesson(items, maths.copy(subjectID = null), nextMonday)).isEmpty()
        // The permanent timetable carries no homework.
        assertThat(DemoData.permanentTimetableResponse().days.flatMap { it.atoms }.flatMap { it.homeworkIDs }).isEmpty()
    }

    @Test fun aTickOnItsWayToTheSchoolSurvivesARefresh() {
        val shown = HomeworkList.withDone(Homework.from(HomeworkResponse(listOf(dto("a", "2026-11-12"), dto("b", "2026-11-13")))), "a", true)
        assertThat(shown.map { it.isDone }).containsExactly(true, false).inOrder()
        val fresh = Homework.from(HomeworkResponse(listOf(dto("a", "2026-11-12"), dto("b", "2026-11-13", done = true), dto("c", "2026-11-14"))))
        assertThat(HomeworkList.merged(fresh, shown, pendingIDs = setOf("a")).map { it.id to it.isDone }).containsExactly("a" to true, "b" to true, "c" to false).inOrder()
        assertThat(HomeworkList.merged(fresh, shown, pendingIDs = emptySet())).isEqualTo(fresh)
        // A pending tick for homework the school no longer lists changes nothing.
        assertThat(HomeworkList.merged(fresh, shown, pendingIDs = setOf("gone"))).isEqualTo(fresh)
    }

    @Test fun todayPreviewsHomeworkNextToTheUsersOwnPlannerItems() {
        val homework = Homework.from(dto("h1", "2026-11-17T00:00:00+01:00", content = "Workbook p. 34\nmore"))
        val item = homework.plannerItem("scope-a", "School homework")
        assertThat(item.id).isEqualTo("school-homework:h1")
        assertThat(Homework.idFromPlannerItem(item.id)).isEqualTo("h1")
        assertThat(Homework.idFromPlannerItem("3f2c-own-item")).isNull()
        assertThat(item.title).isEqualTo("Workbook p. 34")
        assertThat(item.type).isEqualTo(PlannerItemType.HOMEWORK)
        assertThat(item.dueDate).isEqualTo("2026-11-17")
        assertThat(item.subject!!.name).isEqualTo("Matematika")
        assertThat(Homework.from(dto("h2", null, content = "")).plannerItem("scope-a", "School homework").title).isEqualTo("Matematika")
        assertThat(Homework.from(dto("h3", null, subject = null, content = "")).plannerItem("scope-a", "School homework").title).isEqualTo("School homework")

        // Due today still counts; the day after it is gone. Another account's scope never shows.
        val noon = Instant.parse("2026-11-17T11:00:00Z")
        assertThat(TodayOverview.planner(listOf(item), "scope-a", noon).map { it.id }).containsExactly("school-homework:h1")
        assertThat(TodayOverview.planner(listOf(item), "scope-a", Instant.parse("2026-11-18T11:00:00Z"))).isEmpty()
        assertThat(TodayOverview.planner(listOf(item), "scope-b", noon)).isEmpty()
        assertThat(TodayOverview.planner(listOf(item.copy(isCompleted = true)), "scope-a", noon)).isEmpty()
    }

    @Test fun demoHomeworkFollowsTheClockAndItsAttachmentIsAPdf() {
        val items = Homework.from(HomeworkDemoData.response(LocalDate.parse("2026-11-19")))
        assertThat(items).hasSize(5)
        assertThat(items.count { it.isDone }).isEqualTo(1)
        assertThat(items.first { it.id == "homework-czech" }.attachments.single().name).isEqualTo("Zadání slohové práce.pdf")
        assertThat(HomeworkList.upcoming(items, LocalDate.parse("2026-11-19")).map { it.id })
            .containsExactly("homework-math", "homework-english", "homework-czech", "homework-biology").inOrder()
        val pdf = DemoData.attachment("demo-attachment-zadani").toString(Charsets.US_ASCII)
        assertThat(pdf).startsWith("%PDF-1.4")
        assertThat(pdf.trimEnd()).endsWith("%%EOF")
        // The cross-reference table points at the first object.
        val firstOffset = Regex("""xref\n0 6\n0000000000 65535 f \n(\d{10}) 00000 n """).find(pdf)!!.groupValues[1].toInt()
        assertThat(pdf.substring(firstOffset)).startsWith("1 0 obj")
    }
}
