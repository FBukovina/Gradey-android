package com.bukovinafilip.gradey.domain

import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SchoolInsightsTest {
    private val day = SchoolInsightStore.DAY
    private val start = 100 * day
    private fun mark(id: String, value: String = "2", weight: Double? = 1.0) = Mark(id = id, subjectID = "math", markText = value, weight = weight)
    private fun subject(vararg marks: Mark, average: String? = null) = Subject(subjectInfo = SubjectInfo("math", "M", "Mathematics"), marks = marks.toList(), averageText = average)

    @Test fun firstSuccessfulReadIsQuietThenRealAddAndEditProduceObservations() {
        val store = SchoolInsightStore()
        assertThat(store.observe("school", listOf(subject(mark("a"))), start).observations).isEmpty()
        val second = store.observe("school", listOf(subject(mark("a"), mark("b", "4"))), start + day)
        assertThat(second.observations.single().addedMarkIDs).containsExactly("b")
        assertThat(second.observations.single().previousAverage).isEqualTo(2.0)
        assertThat(second.observations.single().average).isEqualTo(3.0)
        val third = store.observe("school", listOf(subject(mark("a"), mark("b", "3"))), start + 2 * day)
        assertThat(third.observations.first().editedMarkIDs).containsExactly("b")
        assertThat(third.observations).hasSize(2)
    }

    @Test fun identicalAndNonGradeMetadataChangesNeverCreateHistory() {
        val store = SchoolInsightStore()
        store.observe("school", listOf(subject(mark("a")).copy(temporaryMarkNote = "old")), start)
        store.observe("school", listOf(subject(mark("a")).copy(temporaryMarkNote = "new")), start + day)
        val unchanged = store.observe("school", listOf(subject(mark("a"))), start + 2 * day)
        assertThat(unchanged.observations).isEmpty()
        assertThat(unchanged.baselines.getValue("math").observedAt).isEqualTo(start + 2 * day)
    }

    @Test fun separateAccountsAndEduPageChildrenDoNotShareBaselines() {
        val store = SchoolInsightStore()
        store.observe("school/student-a", listOf(subject(mark("a"))), start)
        store.observe("school/student-b", listOf(subject(mark("a", "5"))), start + day)
        assertThat(store.snapshot("school/student-b", start + day).observations).isEmpty()
        assertThat(store.snapshot("school/student-a", start + day).subjects.single().average).isEqualTo(2.0)
        store.observe("school/student-a", listOf(subject(mark("a"), mark("b", "4"))), start + 2 * day)
        assertThat(store.snapshot("school/student-b", start + 2 * day).observations).isEmpty()
    }

    @Test fun restartPreservesOnlyContinuityProvenByMatchingTimestampedCache() {
        val disk = MemorySchoolInsightStorage()
        val store = SchoolInsightStore(disk)
        val original = subject(mark("a"))
        val changed = subject(mark("a"), mark("b", "4"))
        store.observe("school", listOf(original), start)
        store.observe("school", listOf(changed), start + day)
        val restored = SchoolInsightStore(disk)
        assertThat(restored.activate("school", listOf(changed), start + day, start + 2 * day).observations).hasSize(1)
        assertThat(restored.activate("school", listOf(original), start, start + 2 * day).observations).isEmpty()
        assertThat(restored.activate("school", listOf(changed), null, start + 2 * day).baselines).isEmpty()
        assertThat(restored.observe("school", listOf(changed), start + 3 * day).observations).isEmpty()
    }

    @Test fun removalsAndAmbiguousDuplicateIdsResetObservedHistory() {
        val store = SchoolInsightStore()
        store.observe("school", listOf(subject(mark("a"))), start)
        store.observe("school", listOf(subject(mark("a"), mark("b", "4"))), start + day)
        assertThat(store.observe("school", listOf(subject(mark("a"))), start + 2 * day).observations).isEmpty()
        store.observe("school", listOf(subject(mark("a"), mark("b", "4"))), start + 3 * day)
        val ambiguous = store.observe("school", listOf(subject(mark("a"), mark("a", "3"))), start + 4 * day)
        assertThat(ambiguous.observations).isEmpty()
        assertThat(ambiguous.baselines).isEmpty()
        assertThat(store.observe("school", listOf(subject(mark("a", "3"))), start + 5 * day).observations).isEmpty()
    }

    @Test fun providerAverageAndInferredWeightBasisChangesResetContinuityButExplicitWeightEditsCount() {
        val store = SchoolInsightStore()
        store.observe("school", listOf(subject(mark("a"))), start)
        assertThat(store.observe("school", listOf(subject(mark("a"), average = "2.0")), start + day).observations).isEmpty()
        assertThat(store.observe("school", listOf(subject(mark("a", weight = null), average = "2.0")), start + 2 * day).observations).isEmpty()
        assertThat(store.observe("school", listOf(subject(mark("a", weight = 1.0), average = "2.0")), start + 3 * day).observations).isEmpty()
        val explicit = store.observe("school", listOf(subject(mark("a", weight = 3.0), average = "2.0")), start + 4 * day)
        assertThat(explicit.observations.single().editedMarkIDs).containsExactly("a")
    }

    @Test fun syntheticIdsDoNotClaimAddedGradesAndOlderResponsesCannotRewindBaseline() {
        val store = SchoolInsightStore()
        store.observe("school", listOf(subject(mark("a"))), start)
        val added = store.observe("school", listOf(subject(mark("a"), mark("local-mark-generated", "4"))), start + day)
        assertThat(added.observations.single().addedMarkIDs).isEmpty()
        val late = store.observe("school", listOf(subject(mark("a"))), start)
        assertThat(late).isEqualTo(added)
    }

    @Test fun sourceFreshnessKeepsOriginalTimeOnFailureAndRejectsBackwardClock() {
        val store = SchoolInsightStore()
        store.sourceSucceeded("school", "marks", start)
        assertThat(store.snapshot("school", start).sources.getValue("marks").isStale(start + 299_999, 300_000)).isFalse()
        assertThat(store.snapshot("school", start).sources.getValue("marks").isStale(start + 300_000, 300_000)).isTrue()
        assertThat(store.snapshot("school", start).sources.getValue("marks").isStale(start - 1, 300_000)).isTrue()
        store.sourceFailed("school", "marks", start + 1)
        val stale = store.snapshot("school", start + 1).sources.getValue("marks")
        assertThat(stale.lastSuccessAt).isEqualTo(start)
        assertThat(stale.isStale(start + 1, 300_000)).isTrue()
        store.sourceSucceeded("school", "marks", start + 100)
        store.sourceFailed("school", "marks", start + 1)
        assertThat(store.snapshot("school", start + 100).sources.getValue("marks").failed).isFalse()
    }

    @Test fun trendsRequireThreeComparablePointsAcrossAtLeastAWeek() {
        val store = SchoolInsightStore()
        store.observe("school", listOf(subject(mark("a", "1"))), start)
        val twoPoints = store.observe("school", listOf(subject(mark("a", "1"), mark("b", "3"))), start + 4 * day)
        assertThat(SchoolSubjectInsights.trendDelta(twoPoints.observations, start + 4 * day)).isNull()
        val threePoints = store.observe("school", listOf(subject(mark("a", "1"), mark("b", "3"), mark("c", "5"))), start + 8 * day)
        assertThat(SchoolSubjectInsights.trendDelta(threePoints.observations, start + 8 * day)).isEqualTo(2.0)
        assertThat(SchoolSubjectInsights.trendDelta(threePoints.observations, start + 40 * day)).isNull()
    }

    @Test fun observationsAreBoundedAndSeenStateDoesNotRemoveThem() {
        val store = SchoolInsightStore()
        store.observe("school", listOf(subject(mark("a"))), start)
        repeat(110) { i -> store.observe("school", listOf(subject(mark("a", if (i % 2 == 0) "3" else "2"))), start + i + 1) }
        val observations = store.snapshot("school", start + 110).observations
        assertThat(observations).hasSize(100)
        store.markSeen("school", setOf(observations.first().id), start + 111)
        assertThat(store.snapshot("school", start + 111).observations.first().seenAt).isEqualTo(start + 111)
        assertThat(store.snapshot("school", start + 31 * day).observations).isEmpty()
    }
}
