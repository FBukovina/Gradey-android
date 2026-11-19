package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.Absence
import com.bukovinafilip.gradey.model.AbsencePerSubject
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.RecapArchive
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import java.time.DayOfWeek
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * A full semester of sample marks and absence for Gradey Recap in demo mode (iOS
 * `PreviewData.recapFixture`). Dates follow the clock, so the current semester always has
 * something in it.
 */
object RecapDemoData {
    /**
     * @param archive the semester before, but only within the same school year, the way a device
     *   that has had the app since September would have it.
     */
    data class Fixture(val marks: MarksResponse, val absence: AbsenceResponse, val archive: RecapArchive)

    fun fixture(now: ZonedDateTime): Fixture {
        val time = now.withZoneSameInstant(RecapZone)
        val current = ReportCardPeriod.containing(time.toLocalDate())
        var archive = RecapArchive()
        if (current.semester == 2) {
            val previous = ReportCardPeriod(current.startYear, 1)
            val (start, end) = bounds(previous)
            archive = RecapArchive(
                marks = mapOf(RecapArchives.key(previous) to marks(start, end, seed = 7)),
                absence = mapOf(RecapArchives.key(previous) to absence(start, end, seed = 7)),
            )
        }
        val (start, semesterEnd) = bounds(current)
        val end = minOf(semesterEnd, time)
        return Fixture(marks(start, end, seed = 3), absence(start, end, seed = 3), archive)
    }

    /** Everything demo mode has for Recap: the current semester and, in spring, the autumn before it. */
    fun archive(now: ZonedDateTime): RecapArchive {
        val fixture = fixture(now)
        val key = RecapArchives.key(ReportCardPeriod.containing(now.withZoneSameInstant(RecapZone).toLocalDate()))
        return RecapArchive(marks = fixture.archive.marks + (key to fixture.marks), absence = fixture.archive.absence + (key to fixture.absence))
    }

    /** @param early,late mark texts the generator cycles through; a later half lets one subject come back. */
    private class DemoSubject(val id: String, val abbrev: String, val name: String, val early: List<String>, val late: List<String>)

    private val subjects = listOf(
        DemoSubject("math", "M", "Matematika", listOf("1", "1-", "2", "1"), listOf("1", "2+", "1")),
        DemoSubject("czech", "ČJ", "Český jazyk", listOf("2", "1-", "2-", "2"), listOf("1", "2", "2+")),
        DemoSubject("eng", "AJ", "Anglický jazyk", listOf("1", "1", "1-", "1"), listOf("1", "1+", "2")),
        DemoSubject("phys", "F", "Fyzika", listOf("3", "4", "3-", "2"), listOf("3", "3+", "4")),
        DemoSubject("hist", "D", "Dějepis", listOf("4", "3", "4-", "3"), listOf("2", "1", "2+", "1-")),
        DemoSubject("bio", "Bi", "Biologie", listOf("2", "1", "2"), listOf("1", "2", "1")),
        DemoSubject("pe", "Tv", "Tělesná výchova", listOf("1", "N"), listOf("1", "1")),
    )

    private fun bounds(semester: ReportCardPeriod): Pair<ZonedDateTime, ZonedDateTime> {
        val period = RecapPeriod.semester(semester.semester, semester.startYear)
        return period.start.atStartOfDay(RecapZone) to period.end.atStartOfDay(RecapZone)
    }

    private fun span(start: ZonedDateTime, end: ZonedDateTime): Double = maxOf(ChronoUnit.SECONDS.between(start, end), 86_400L).toDouble()

    private fun marks(start: ZonedDateTime, end: ZonedDateTime, seed: Int): MarksResponse {
        val span = span(start, end)
        val weights = listOf(1.0, 2.0, 3.0, 1.0, 5.0, 2.0)
        return MarksResponse(subjects = subjects.mapIndexed { index, demo ->
            val texts = demo.early + demo.late
            val marks = texts.mapIndexedNotNull { offset, text ->
                // Spread evenly with a per-subject shift, early texts in the first half.
                val position = (offset + 0.5 + ((index * 7 + seed) % 5) / 10.0) / texts.size
                val date = schoolDay(start.plusSeconds((span * minOf(position, 0.98)).toLong())).takeIf { it < end } ?: return@mapIndexedNotNull null
                val weight = weights[(offset + index + seed) % weights.size]
                val isPoints = demo.id == "bio" && offset == 1
                Mark(
                    markDate = iso(date, hour = 8 + (offset + index) % 6),
                    caption = if (weight >= 3) "Písemná práce" else if (weight == 2.0) "Test" else "Ústní zkoušení",
                    markText = if (isPoints) "18" else text,
                    type = if (isPoints) "points" else if (weight >= 3) "written" else "oral",
                    weight = if (isPoints) null else weight,
                    subjectID = demo.id,
                    isPoints = isPoints,
                    id = "recap-$seed-${demo.id}-$offset",
                    pointsText = if (isPoints) "18" else null,
                    maxPoints = if (isPoints) 20 else null,
                )
            }
            Subject(marks = marks, subjectInfo = SubjectInfo(id = demo.id, abbrev = demo.abbrev, name = demo.name), markPredictionEnabled = true)
        })
    }

    private fun absence(start: ZonedDateTime, end: ZonedDateTime, seed: Int): AbsenceResponse {
        val span = span(start, end)
        // Excused, unresolved and late hours of each absent day.
        val pattern = listOf(Triple(6, 0, 0), Triple(2, 0, 0), Triple(0, 0, 1), Triple(6, 0, 0), Triple(0, 2, 0), Triple(1, 0, 0), Triple(0, 0, 1), Triple(6, 0, 0), Triple(3, 0, 0), Triple(0, 1, 0))
        val days = pattern.mapIndexedNotNull { offset, (ok, unsolved, late) ->
            val position = (offset + 0.3 + (seed % 3) / 10.0) / pattern.size
            val date = schoolDay(start.plusSeconds((span * position).toLong())).takeIf { it < end } ?: return@mapIndexedNotNull null
            Absence(date = iso(date, hour = 0), unsolved = unsolved, ok = ok, late = late)
        }
        return AbsenceResponse(
            percentageThreshold = 25.0,
            absences = days,
            absencesPerSubject = listOf(
                AbsencePerSubject(subjectName = "Fyzika", lessonsCount = 40, base = 6, late = 1),
                AbsencePerSubject(subjectName = "Matematika", lessonsCount = 70, base = 5),
                AbsencePerSubject(subjectName = "Dějepis", lessonsCount = 30, base = 3),
            ),
        )
    }

    /** Moves Saturdays and Sundays to the next Monday. */
    private fun schoolDay(date: ZonedDateTime): ZonedDateTime = when (date.dayOfWeek) {
        DayOfWeek.SATURDAY -> date.plusDays(2)
        DayOfWeek.SUNDAY -> date.plusDays(1)
        else -> date
    }

    private fun iso(date: ZonedDateTime, hour: Int): String =
        date.toLocalDate().atStartOfDay(RecapZone).plusHours(hour.toLong()).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
}
