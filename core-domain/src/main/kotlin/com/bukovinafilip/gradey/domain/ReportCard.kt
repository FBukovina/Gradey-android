package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.CertificateTermDTO
import com.bukovinafilip.gradey.model.Subject
import java.time.LocalDate
import kotlin.math.ceil

/** A school semester: the year it started in and 1 or 2 (iOS `ReportCardPeriod`). */
data class ReportCardPeriod(val startYear: Int, val semester: Int) {
    /** "2026/27". */
    val schoolYear: String get() = "$startYear/" + "%02d".format((startYear + 1) % 100)

    companion object {
        /** September to January is the first semester; February to August the second. */
        fun containing(date: LocalDate): ReportCardPeriod {
            val month = date.monthValue
            return ReportCardPeriod(
                startYear = if (month >= 9) date.year else date.year - 1,
                semester = if (month >= 9 || month == 1) 1 else 2,
            )
        }
    }
}

/** One mark on a report card. */
data class ReportCardMark(val subjectID: String, val subjectName: String, val subjectAbbrev: String?, val markText: String) {
    val id: String get() = subjectID
}

/** One semester's report card as the school issued it (iOS `ReportCardTerm`). */
data class ReportCardTerm(
    val schoolYear: String,
    val semester: String,
    val isClosed: Boolean,
    val achievementText: String?,
    val average: Double?,
    val absentHours: Double?,
    val unexcusedHours: Double?,
    val issuedOn: LocalDate?,
    val marks: List<ReportCardMark>,
) {
    val id: String get() = "$schoolYear-$semester"

    fun matches(period: ReportCardPeriod): Boolean = schoolYear == period.schoolYear && semester == period.semester.toString()

    /** The mark for [subject]: by id, then by abbreviation, then by name. */
    fun mark(subject: Subject): ReportCardMark? {
        val abbrev = subject.subjectInfo.abbrev.trim()
        val name = subject.subjectInfo.name.trim()
        return marks.firstOrNull { it.subjectID == subject.id }
            ?: marks.firstOrNull { abbrev.isNotEmpty() && it.subjectAbbrev.equals(abbrev, ignoreCase = true) }
            ?: marks.firstOrNull { it.subjectName.equals(name, ignoreCase = true) }
    }

    companion object {
        fun from(dto: CertificateTermDTO): ReportCardTerm {
            val marksBySubject = LinkedHashMap<String, String>()
            dto.finalMarks.forEach { marksBySubject.putIfAbsent(it.subjectID, it.markText.trim()) }
            val listed = dto.subjects.mapNotNull { subject ->
                val text = marksBySubject[subject.id]?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ReportCardMark(
                    subjectID = subject.id,
                    subjectName = subject.name?.trim()?.takeIf { it.isNotEmpty() } ?: subject.id,
                    subjectAbbrev = subject.abbrev?.trim()?.takeIf { it.isNotEmpty() },
                    markText = text,
                )
            }
            val listedIDs = listed.mapTo(HashSet()) { it.subjectID }
            val unlisted = marksBySubject.filter { (id, text) -> id !in listedIDs && text.isNotEmpty() }
                .map { (id, text) -> ReportCardMark(id, id, null, text) }
            return ReportCardTerm(
                schoolYear = dto.schoolYear?.trim().orEmpty(),
                semester = dto.semester?.trim(' ', '.').orEmpty(),
                isClosed = dto.closed,
                achievementText = dto.achievementText?.trim()?.takeIf { it.isNotEmpty() },
                average = dto.marksAverage?.takeIf { it > 0 },
                absentHours = dto.absentHours,
                unexcusedHours = dto.notExcusedHours,
                issuedOn = dto.certificateDate?.trim()?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
                marks = listed + unlisted,
            )
        }

        /** Newest first. */
        fun sorted(terms: List<ReportCardTerm>): List<ReportCardTerm> =
            terms.sortedWith(compareByDescending<ReportCardTerm> { it.schoolYear }.thenByDescending { it.semester })
    }
}

/**
 * What a subject is heading for on the next report card (iOS `ReportCardEstimate`): the mark the
 * school already wrote, or the one the current average rounds to, with a warning when the average
 * sits within a tenth of the next mark.
 */
data class ReportCardEstimate(
    val subjectID: String,
    val subjectName: String,
    val average: Double?,
    val writtenMarkText: String?,
    val estimatedMark: Int?,
    val borderline: Borderline?,
) {
    /** The neighbouring mark the average is close to, and how far away it is. */
    data class Borderline(val mark: Int, val distance: Double)

    val id: String get() = subjectID

    val markValue: Double?
        get() = if (writtenMarkText != null) GradeMath.parseMarkValue(writtenMarkText) else estimatedMark?.toDouble()

    companion object {
        const val BorderlineMargin = 0.1

        fun estimates(subjects: List<Subject>, prepared: Map<String, PreparedGradeCalculation>, written: ReportCardTerm?): List<ReportCardEstimate> =
            subjects.map { subject ->
                val average = prepared[subject.id]?.displayAverage ?: GradeMath.subjectAverage(subject)
                val writtenMark = written?.mark(subject)?.markText
                val estimated = average?.let(::mark)
                ReportCardEstimate(
                    subjectID = subject.id,
                    subjectName = subject.subjectInfo.name.trim().ifEmpty { subject.displayName },
                    average = average,
                    writtenMarkText = writtenMark,
                    estimatedMark = estimated,
                    borderline = if (writtenMark == null && average != null && estimated != null) borderline(average, estimated) else null,
                )
            }

        /** Mean of the marks: written ones where they exist, estimates otherwise. */
        fun average(estimates: List<ReportCardEstimate>): Double? =
            estimates.mapNotNull { it.markValue }.takeIf { it.isNotEmpty() }?.average()

        /** An average up to x.50 rounds to x; anything above goes to the next mark. */
        fun mark(average: Double): Int = ceil(average - 0.5).toInt().coerceIn(1, 5)

        fun borderline(average: Double, mark: Int): Borderline? {
            val candidates = buildList {
                if (mark < 5) add(Borderline(mark + 1, mark + 0.5 - average))
                if (mark > 1) add(Borderline(mark - 1, average - (mark - 0.5)))
            }
            return candidates.filter { it.distance >= 0 && it.distance <= BorderlineMargin + 0.0001 }.minByOrNull { it.distance }
        }
    }
}
