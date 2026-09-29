package com.bukovinafilip.gradey.model

import kotlinx.serialization.Serializable

@Serializable
data class SchoolDirectoryMunicipality(val name: String, val schoolCount: Int)

@Serializable
data class SchoolDirectorySchool(val id: String, val name: String, val town: String, val schoolURL: String) {
    val trimmedName: String get() = name.trim()
    val trimmedTown: String get() = town.trim()
    val trimmedSchoolURL: String get() = schoolURL.trim()
}

@Serializable
data class CachedSchoolDirectory(
    val schools: List<SchoolDirectorySchool>,
    val cachedAtEpochMillis: Long,
    val formatVersion: Int = 2,
) {
    fun isStale(now: Long = System.currentTimeMillis()): Boolean =
        formatVersion != 2 || now - cachedAtEpochMillis >= 7L * 24 * 60 * 60 * 1000
}
