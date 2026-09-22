package com.bukovinafilip.gradey.feature.ai

import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.model.DashboardData
import com.bukovinafilip.gradey.model.LessonChangeKind
import com.bukovinafilip.gradey.model.StoredSession
import com.bukovinafilip.gradey.model.TimetableWeek
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.MessageDigest
import java.time.Instant

internal data class AIStatus(
    val enabled: Boolean,
    val consentRequired: Boolean,
    val termsVersion: String,
    val dailyLimit: Int,
    val remaining: Int,
    val resetAt: Long?,
) {
    val canSend: Boolean get() = enabled && !consentRequired && remaining > 0
}

internal data class AIChat(val id: String, val title: String, val updatedAt: Long)
internal data class AIMessage(val id: String, val role: String, val text: String, val status: String, val createdAt: Long)
internal data class AIDetail(val chat: AIChat, val messages: List<AIMessage>, val status: AIStatus?)

internal object AIContract {
    fun status(data: Map<String, Any?>): AIStatus {
        val limit = data.number("dailyLimit", "daily_limit")?.toInt()?.coerceAtLeast(0) ?: 0
        val used = data.number("dailyUsed", "daily_used")?.toInt()?.coerceAtLeast(0) ?: 0
        return AIStatus(
            enabled = data.boolean("enabled") ?: false,
            consentRequired = data.boolean("consentRequired", "consent_required") ?: true,
            termsVersion = data.string("termsVersion", "terms_version").orEmpty(),
            dailyLimit = limit,
            remaining = (data.number("remaining")?.toInt() ?: (limit - used)).coerceAtLeast(0),
            resetAt = timestamp(data["resetAt"] ?: data["reset_at"]),
        )
    }

    fun chat(data: Map<String, Any?>): AIChat = AIChat(
        id = requireNotNull(data.string("id")) { "The conversation response is missing its ID." },
        title = data.string("title") ?: "New conversation",
        updatedAt = timestamp(data["updatedAt"] ?: data["updated_at"]) ?: 0L,
    )

    fun detail(data: Map<String, Any?>): AIDetail {
        val chat = chat((data["chat"] ?: data["conversation"]).objectMap())
        val raw = data["messages"] ?: data["history"] ?: data["items"]
        val values = when (raw) {
            is List<*> -> raw
            is Map<*, *> -> raw.values.toList()
            else -> emptyList()
        }
        val messages = values.mapNotNull { item ->
            val value = item.objectMap()
            val id = value.string("id") ?: return@mapNotNull null
            val role = value.string("role")?.takeIf { it == "user" || it == "assistant" } ?: return@mapNotNull null
            AIMessage(id, role, value.string("content", "text").orEmpty(), value.string("status", "state") ?: "complete",
                timestamp(value["createdAt"] ?: value["created_at"]) ?: 0L)
        }.sortedBy { it.createdAt }
        return AIDetail(chat, messages, (data["status"] as? Map<*, *>)?.let { status(it.objectMap()) })
    }

    fun timestamp(value: Any?): Long? {
        val numeric = (value as? Number)?.toDouble() ?: (value as? String)?.toDoubleOrNull()
        if (numeric != null) return (if (kotlin.math.abs(numeric) < 10_000_000_000) numeric * 1_000 else numeric).toLong()
        if (value is String) return runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
        if (value is Map<*, *>) {
            val map = value.objectMap()
            map["value"]?.let { return timestamp(it) }
            val seconds = map.number("seconds", "_seconds") ?: return null
            return (seconds * 1_000 + (map.number("nanos", "nanoseconds", "_nanoseconds") ?: 0.0) / 1_000_000).toLong()
        }
        return null
    }
}

/** An explicit allowlist: no provider credentials, user profile, school URL, or raw responses. */
internal object AIContext {
    const val MAX_CONTEXT_BYTES = 96 * 1024
    const val MAX_REQUEST_BYTES = 128 * 1024

    fun schoolScope(session: StoredSession, salt: ByteArray): String {
        require(salt.size >= 16)
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(0.toByte())
        digest.update(session.cacheScope.toByteArray(Charsets.UTF_8))
        return "school_" + digest.digest().joinToString("") { "%02x".format(it) }
    }

    fun build(
        scope: String,
        dashboard: DashboardData?,
        weeks: List<TimetableWeek>,
        shareMarks: Boolean,
        shareTimetable: Boolean,
        now: Long = System.currentTimeMillis(),
        stale: Boolean = false,
    ): Map<String, Any?> {
        var markBudget = 80
        val subjects = if (!shareMarks) emptyList() else dashboard?.marksResponse?.subjects.orEmpty().take(80).map { subject ->
            val marks = subject.marks.sortedByDescending { it.markDate.orEmpty() }.take(minOf(5, markBudget))
            markBudget -= marks.size
            mapOf(
                "id" to subject.id.take(128), "name" to subject.displayName.take(120),
                "abbreviation" to subject.subjectInfo.abbrev.take(32), "average" to GradeMath.subjectAverage(subject),
                "pointsOnly" to subject.pointsOnly, "totalMarkCount" to subject.marks.size,
                "recentMarks" to marks.map { mark -> mapOf(
                    "value" to mark.markText.take(64), "date" to mark.markDate.orEmpty().substringBefore('T').take(32),
                    "weight" to mark.weight, "title" to (mark.caption ?: mark.theme)?.take(200),
                    "isPoints" to mark.isPoints, "pointsText" to mark.pointsText?.take(64), "maxPoints" to mark.maxPoints,
                ) },
            )
        }
        val lessons = if (!shareTimetable) emptyList() else weeks.sortedBy { it.weekStart }.flatMap { week ->
            week.days.sortedBy { it.date }.flatMap { day ->
                val date = day.date ?: return@flatMap emptyList()
                day.lessons.map { lesson -> mapOf(
                    "id" to "$date#${lesson.id}".take(180), "date" to date.take(32),
                    "subject" to (lesson.subjectName ?: lesson.subjectAbbrev ?: "Lesson").take(120),
                    "subjectAbbreviation" to lesson.subjectAbbrev?.take(32),
                    "beginsAt" to lesson.hour.beginTime.take(16), "endsAt" to lesson.hour.endTime.take(16),
                    // Teacher and group identities are unnecessary for a study plan.
                    "room" to lesson.roomTitle?.take(120), "groups" to emptyList<String>(),
                    "changeKind" to when (lesson.changeKind) {
                        LessonChangeKind.NONE -> "none"
                        LessonChangeKind.CANCELED -> "cancelled"
                        LessonChangeKind.SUBSTITUTION -> "substitution"
                        LessonChangeKind.ROOM_CHANGED -> "room_changed"
                        LessonChangeKind.ADDED -> "added"
                    },
                ) }
            }
        }.distinctBy { it["id"] }.take(120)
        val context = mutableMapOf<String, Any?>(
            "schoolScope" to scope, "generatedAt" to now, "isStale" to stale,
            "unavailableSections" to buildList {
                if (!shareMarks || dashboard == null) add("marks")
                add("trends")
                if (!shareTimetable || weeks.isEmpty()) add("timetable")
            },
            "subjects" to subjects, "trends" to emptyList<Any>(), "timetable" to lessons,
        )
        // Bound request bytes as well as item counts when schools supply unusually long text.
        while (encodedSize(context) > MAX_CONTEXT_BYTES) {
            val timetable = context["timetable"] as List<*>
            val currentSubjects = context["subjects"] as List<*>
            when {
                timetable.isNotEmpty() -> context["timetable"] = timetable.dropLast(1)
                currentSubjects.isNotEmpty() -> context["subjects"] = currentSubjects.dropLast(1)
                else -> error("The school context is too large to send.")
            }
        }
        return context
    }

    fun encodedSize(data: Map<String, Any?>): Int = jsonValue(data).toString().toByteArray(Charsets.UTF_8).size

    private fun jsonValue(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is Map<*, *> -> JsonObject(value.entries.associate { it.key.toString() to jsonValue(it.value) })
        is List<*> -> JsonArray(value.map(::jsonValue))
        else -> error("Unsupported context value")
    }
}

internal fun Any?.objectMap(): Map<String, Any?> = (this as? Map<*, *>)?.entries?.mapNotNull { (key, value) ->
    (key as? String)?.let { it to value }
}?.toMap().orEmpty()
internal fun Map<String, Any?>.string(vararg names: String): String? = names.firstNotNullOfOrNull {
    (this[it] as? String)?.takeIf(String::isNotBlank)
}
internal fun Map<String, Any?>.number(vararg names: String): Double? = names.firstNotNullOfOrNull {
    (this[it] as? Number)?.toDouble() ?: (this[it] as? String)?.toDoubleOrNull()
}
internal fun Map<String, Any?>.boolean(vararg names: String): Boolean? = names.firstNotNullOfOrNull {
    when (val value = this[it]) {
        is Boolean -> value
        1, 1L, 1.0, "true", "1" -> true
        0, 0L, 0.0, "false", "0" -> false
        else -> null
    }
}
