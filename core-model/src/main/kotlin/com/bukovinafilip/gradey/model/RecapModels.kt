package com.bukovinafilip.gradey.model

import kotlinx.serialization.Serializable

/**
 * Bakaláři only returns the current semester's marks and absence, so every semester the app sees
 * is kept here for Gradey Recap of earlier periods (iOS `RecapArchive`).
 */
@Serializable
data class RecapArchive(
    /** By semester key, e.g. "2026/27-1". */
    val marks: Map<String, MarksResponse> = emptyMap(),
    val absence: Map<String, AbsenceResponse> = emptyMap(),
) {
    companion object {
        /** Cache key prefix, shared with iOS. */
        const val CachePrefix = "recap-archive"

        /** Older school years are dropped. */
        const val KeptSchoolYears = 3
    }
}
