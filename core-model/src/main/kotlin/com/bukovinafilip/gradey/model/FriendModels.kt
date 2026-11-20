package com.bukovinafilip.gradey.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Someone whose timetable you can look at next to yours (iOS `Friend`). Friends live only on this
 * device: their Bakaláři login is in encrypted storage and is never synced.
 */
@Serializable
data class Friend(val id: String, val name: String, val source: FriendTimetableSource, val addedAtEpochMillis: Long)

@Serializable
sealed interface FriendTimetableSource {
    val schoolURL: String
    val schoolName: String?

    /** Their own Bakaláři account; the session is stored under the friend's id. */
    @Serializable
    @SerialName("bakalari")
    data class Bakalari(override val schoolURL: String, override val schoolName: String? = null, val username: String) : FriendTimetableSource

    /** The school's public class timetable. [groups] null means every group. */
    @Serializable
    @SerialName("publicClass")
    data class PublicClass(
        override val schoolURL: String,
        override val schoolName: String? = null,
        val classID: String,
        val className: String,
        val groups: List<String>? = null,
    ) : FriendTimetableSource
}

/** A class in a school's public Bakaláři timetable (`/Timetable/Public`). */
@Serializable
data class PublicTimetableClass(val id: String, val name: String)
