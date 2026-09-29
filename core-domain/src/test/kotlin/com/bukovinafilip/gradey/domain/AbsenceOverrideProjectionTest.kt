package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.*
import org.junit.Assert.*
import org.junit.Test

class AbsenceOverrideProjectionTest {
    private val scope = "owner:school"
    private val day = Absence("2026-09-25T00:00:00+02:00", ok = 2, late = 1)
    private val raw = AbsenceResponse(25.0, listOf(day), listOf(
        AbsencePerSubject("Mathematics", lessonsCount = 20, base = 5, late = 1),
        AbsencePerSubject("Czech", lessonsCount = 18, base = 3),
    ))
    private val allocations = listOf(
        AbsenceOverrideAllocation("math", subjectName = "Mathematics", category = AbsenceOverrideCategory.OK),
        AbsenceOverrideAllocation("czech", subjectName = "Czech", category = AbsenceOverrideCategory.OK),
        AbsenceOverrideAllocation("late", subjectName = "Mathematics", category = AbsenceOverrideCategory.LATE),
    )
    private fun entry(hidden: Set<String> = setOf("math")) = AbsenceDayOverride(scope, "2026-09-25", day, allocations, hidden)

    @Test fun correctionSubtractsOnlySelectedCategoryAndNeverChangesDenominatorOrRawResponse() {
        val result = AbsenceOverrideProjection.project(raw, listOf(entry(setOf("math", "late"))), scope)
        assertEquals(1, result.response.absences.single().ok)
        assertEquals(0, result.response.absences.single().late)
        val math = result.response.absencesPerSubject.first()
        assertEquals(4, math.base)
        assertEquals(0, math.late)
        assertEquals(20, math.lessonsCount)
        assertEquals(2, raw.absences.single().ok)
        assertEquals(5, raw.absencesPerSubject.first().base)
        assertEquals(2, result.hiddenCount)
    }

    @Test fun hidingWholeDayRemovesDayWhileUnknownSubjectTotalsStayUnknown() {
        val unknownTotals = raw.copy(absencesPerSubject = emptyList())
        val result = AbsenceOverrideProjection.project(unknownTotals, listOf(entry(allocations.map { it.id }.toSet())), scope)
        assertTrue(result.response.absences.isEmpty())
        assertTrue(result.response.absencesPerSubject.isEmpty())
        assertEquals(3, result.hiddenCount)
    }

    @Test fun providerChangePausesCorrectionAndConflictStaysUntilExplicitReview() {
        val changed = raw.copy(absences = listOf(day.copy(ok = 1)))
        val paused = AbsenceOverrideProjection.project(changed, listOf(entry()), scope)
        assertEquals(changed, paused.response)
        assertEquals(AbsenceOverridePauseReason.DAY_CHANGED, paused.reviewOverrides.single().pauseReason)
        val returnedToOldValues = AbsenceOverrideProjection.project(raw, paused.reconciledOverrides, scope)
        assertEquals(raw, returnedToOldValues.response)
        assertEquals(1, returnedToOldValues.reviewOverrides.size)
    }

    @Test fun wrongScopeAndAmbiguousDaysCannotChangeSchoolData() {
        assertEquals(raw, AbsenceOverrideProjection.project(raw, listOf(entry().copy(scope = "another:school")), scope).response)
        val duplicate = raw.copy(absences = listOf(day, day))
        val result = AbsenceOverrideProjection.project(duplicate, listOf(entry()), scope)
        assertEquals(duplicate, result.response)
        assertEquals(AbsenceOverridePauseReason.AMBIGUOUS_DAY, result.reviewOverrides.single().pauseReason)
    }

    @Test fun partialOriginalAllocationCannotTurnAFullDayIntoAnInferredPartialDay() {
        val invalid = entry().copy(allocations = allocations.take(1))
        val result = AbsenceOverrideProjection.project(raw, listOf(invalid), scope)
        assertEquals(raw, result.response)
        assertEquals(AbsenceOverridePauseReason.INVALID_ALLOCATION, result.reviewOverrides.single().pauseReason)
    }

    @Test fun mismatchedSubjectAndInsufficientTotalsPauseEntireCorrectionAtomically() {
        val wrongSubject = entry().copy(allocations = allocations.map { if (it.id == "math") it.copy(subjectName = "Unknown subject") else it })
        assertEquals(AbsenceOverridePauseReason.SUBJECT_MAPPING_CHANGED,
            AbsenceOverrideProjection.project(raw, listOf(wrongSubject), scope).reviewOverrides.single().pauseReason)
        val insufficient = raw.copy(absencesPerSubject = listOf(raw.absencesPerSubject.first().copy(base = 0), raw.absencesPerSubject.last()))
        val result = AbsenceOverrideProjection.project(insufficient, listOf(entry(setOf("czech", "math"))), scope)
        assertEquals(insufficient, result.response)
        assertEquals(AbsenceOverridePauseReason.INSUFFICIENT_SUBJECT_COUNT, result.reviewOverrides.single().pauseReason)
    }

    @Test fun linkedLessonMismatchPausesRatherThanChangingAnotherLesson() {
        val linked = entry().copy(allocations = allocations.map { if (it.id == "math") it.copy(lessonID = "old-lesson", subjectID = "math-id") else it })
        val result = AbsenceOverrideProjection.project(raw, listOf(linked), scope, lessonsByDate = mapOf("2026-09-25" to emptyList()))
        assertEquals(raw, result.response)
        assertEquals(AbsenceOverridePauseReason.LESSON_MAPPING_CHANGED, result.reviewOverrides.single().pauseReason)
    }

    @Test fun providerDatePrefixDoesNotShiftWhenDeviceTimezoneChanges() {
        assertEquals("2026-09-25", AbsenceOverrideProjection.dateKey(day.date))
    }
}
