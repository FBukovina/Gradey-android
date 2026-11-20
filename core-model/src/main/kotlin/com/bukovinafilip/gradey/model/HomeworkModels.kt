package com.bukovinafilip.gradey.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** `GET api/3/homeworks`: the homework the school gave in a range of days (iOS `HomeworkResponse`). */
@Serializable
data class HomeworkResponse(
    @SerialName("Homeworks")
    val homeworks: List<HomeworkDTO>? = null,
) {
    companion object {
        /** Cache key prefix, shared with iOS. */
        const val CachePrefix = "homework-cache"
    }
}

/** One homework as Bakaláři sends it. Everything but the id may be missing or null. */
@Serializable
data class HomeworkDTO(
    @SerialName("ID")
    val id: String,
    /** The day it was given, as midnight with an offset ("2020-11-10T00:00:00+01:00"). */
    @SerialName("DateStart")
    val dateStart: String? = null,
    /** The day it is due. */
    @SerialName("DateEnd")
    val dateEnd: String? = null,
    @SerialName("Content")
    val content: String? = null,
    @SerialName("Notice")
    val notice: String? = null,
    /** The student's own tick. */
    @SerialName("Done")
    val done: Boolean? = null,
    /** Closed by the teacher. */
    @SerialName("Closed")
    val closed: Boolean? = null,
    /** Handed in online, through Bakaláři. */
    @SerialName("Electronic")
    val electronic: Boolean? = null,
    @SerialName("Subject")
    val subject: HomeworkEntity? = null,
    @SerialName("Teacher")
    val teacher: HomeworkEntity? = null,
    @SerialName("Attachments")
    val attachments: List<SchoolAttachmentDTO>? = null,
)

@Serializable
data class HomeworkEntity(
    @SerialName("Id")
    val id: String? = null,
    @SerialName("Abbrev")
    val abbrev: String? = null,
    @SerialName("Name")
    val name: String? = null,
)

/** A file attached to homework or a Komens message. */
@Serializable
data class SchoolAttachmentDTO(
    @SerialName("Id")
    val id: String,
    @SerialName("Name")
    val name: String? = null,
    @SerialName("Type")
    val type: String? = null,
    @SerialName("Size")
    val size: Long? = null,
)
