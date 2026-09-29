package com.bukovinafilip.gradey.feature.ai

import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.domain.GradeSimulation
import com.bukovinafilip.gradey.domain.GradeHistoryTrend
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.OffsetDateTime
import java.util.Locale

internal data class AIStatus(
    val enabled: Boolean,
    val consentRequired: Boolean,
    val termsVersion: String,
    val dailyLimit: Int,
    val remaining: Int,
    val resetAt: Long?,
    val linked: Boolean = false,
    val compute: AICompute? = null,
) {
    val canSend: Boolean get() = enabled && !consentRequired && remaining > 0
    fun cost(action: AIAction): Int? = if (compute == null) (if (action == AIAction.REPLY) 1 else null)
        else compute.takeIf { it.schemaVersion == 1 && it.catalogVersion.isNotBlank() }?.actions
            ?.firstOrNull { it.id == action.id && it.available && it.cost > 0 }?.cost
    fun canAfford(action: AIAction): Boolean = enabled && !consentRequired && cost(action)?.let { remaining >= it } == true
}

internal data class AICompute(val schemaVersion: Int, val catalogVersion: String, val reserved: Int, val actions: List<AIComputeAction>)
internal data class AIComputeAction(val id: String, val cost: Int, val available: Boolean)
enum class AIAction(val id: String) {
    REPLY("reply"), TOMORROW("tomorrow"), PRIORITIES("study_priorities"),
    WEEK("week_summary"), SUBJECT("subject_help"), TEST("test_preparation"),
}

internal data class AISelection(val action: AIAction = AIAction.REPLY, val subjectID: String? = null, val weekStart: String? = null,
    val eventID: String? = null, val includeNotes: Boolean = false) {
    val id: String get() {
        val input = listOf(action.id, subjectID.orEmpty(), eventID.orEmpty(), if (includeNotes) "notes" else "no-notes", weekStart.orEmpty())
            .joinToString("|") { "${it.toByteArray(Charsets.UTF_8).size}:$it" }
        return "selection_" + MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
internal data class AIChat(val id: String, val title: String, val updatedAt: Long, val contextSelectionID: String? = null, val schoolScope: String? = null)
internal data class AIMessage(val id: String, val role: String, val text: String, val status: String, val createdAt: Long,
    val clientMessageID: String? = null)
internal data class AIDetail(val chat: AIChat, val messages: List<AIMessage>, val status: AIStatus?)
internal data class AIRecovery(val state: String, val message: AIMessage?, val status: AIStatus?)

internal sealed interface AIStreamEvent {
    data class Started(val assistantID: String, val status: AIStatus?) : AIStreamEvent
    data class Delta(val text: String) : AIStreamEvent
    data class Completed(val message: AIMessage?, val status: AIStatus?) : AIStreamEvent
    data class Failed(val message: String, val status: AIStatus?, val retryable: Boolean) : AIStreamEvent
}

internal object AIContract {
    fun status(data: Map<String, Any?>): AIStatus {
        val compute = (data["compute"] as? Map<*, *>)?.objectMap()
        val limit = data.number("dailyLimit", "daily_limit")?.toInt()?.coerceAtLeast(0) ?: 0
        val used = data.number("dailyUsed", "daily_used")?.toInt()?.coerceAtLeast(0) ?: 0
        return AIStatus(
            enabled = data.boolean("enabled") ?: false,
            consentRequired = data.boolean("consentRequired", "consent_required") ?: true,
            termsVersion = data.string("termsVersion", "terms_version").orEmpty(),
            dailyLimit = limit,
            remaining = (data.number("remaining")?.toInt() ?: (limit - used)).coerceAtLeast(0),
            resetAt = timestamp(data["resetAt"] ?: data["reset_at"]),
            linked = data.string("tier") == "linked",
            compute = compute?.let { value -> AICompute(value.number("schemaVersion")?.toInt() ?: 0,
                value.string("catalogVersion").orEmpty(), value.number("reserved")?.toInt()?.coerceAtLeast(0) ?: 0,
                (value["actions"] as? List<*>).orEmpty().mapNotNull { item ->
                    val action = item.objectMap()
                    val id = action.string("id") ?: return@mapNotNull null
                    AIComputeAction(id, action.number("cost")?.toInt() ?: 0, action.boolean("available") ?: false)
                }) },
        )
    }

    fun chat(data: Map<String, Any?>): AIChat = AIChat(
        id = requireNotNull(data.string("id")) { "The conversation response is missing its ID." },
        title = data.string("title") ?: "New conversation",
        updatedAt = timestamp(data["updatedAt"] ?: data["updated_at"]) ?: 0L,
        contextSelectionID = data.string("contextSelectionID", "context_selection_id"),
        schoolScope = data.string("schoolScope", "school_scope"),
    )

    fun detail(data: Map<String, Any?>): AIDetail {
        val chat = chat((data["chat"] ?: data["conversation"]).objectMap())
        val raw = data["messages"] ?: data["history"] ?: data["items"]
        val values = when (raw) {
            is List<*> -> raw
            is Map<*, *> -> raw.values.toList()
            else -> emptyList()
        }
        val messages = values.mapNotNull { message(it.objectMap()) }.distinctBy { it.id }.sortedBy { it.createdAt }
        return AIDetail(chat, messages, (data["status"] as? Map<*, *>)?.let { status(it.objectMap()) })
    }

    fun message(value: Map<String, Any?>): AIMessage? {
        val id = value.string("id") ?: return null
        val role = value.string("role")?.takeIf { it == "user" || it == "assistant" } ?: return null
        val state = when (value.string("status", "state")) {
            "pending", "streaming" -> "streaming"
            "cancelled", "canceled" -> "cancelled"
            "failed" -> "failed"
            else -> "complete"
        }
        return AIMessage(id, role, value.string("content", "text").orEmpty(), state,
            timestamp(value["createdAt"] ?: value["created_at"]) ?: 0L,
            value.string("clientMessageID", "client_message_id"))
    }

    fun event(data: Map<String, Any?>): AIStreamEvent {
        val status = (data["status"] as? Map<*, *>)?.let { status(it.objectMap()) }
        return when (data.string("type")) {
            "started" -> AIStreamEvent.Started(requireNotNull(data.string("assistantMessageID", "assistant_message_id")), status)
            "delta" -> AIStreamEvent.Delta(requireNotNull(data["text"] as? String))
            "completed" -> AIStreamEvent.Completed(message(data["message"].objectMap()), status)
            "failed" -> AIStreamEvent.Failed(failureMessage(data.string("code")), status, data.boolean("retryable") ?: false)
            else -> error("Gradey AI returned an unrecognized reply. Reload the conversation before retrying.")
        }
    }

    fun failureMessage(code: String?): String = when (code?.lowercase(Locale.ROOT)?.replace('_', '-')) {
        "quota-exceeded" -> "You don’t have enough Gradey Compute for this action. Refresh to check your balance."
        "catalog-changed", "price-changed" -> "The selected action or its Compute cost has changed. Check the updated cost before sending again."
        "request-pending" -> "Your previous answer is still being saved. Check again shortly; no new Compute will be spent."
        "timeout" -> "The reply took too long. Try again."
        "content-filter" -> "AI couldn’t answer this request. Try rephrasing your question."
        "provider-rate-limit" -> "Gradey AI is busy. Try again later."
        "idempotency-conflict" -> "This reply can’t be retried with changed content. Send a new message."
        "unauthenticated" -> "Gradey AI couldn’t start a secure session. Please try again."
        "accounting-blocked" -> "Gradey AI is not available in this build."
        else -> "Gradey AI could not finish this reply."
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
        history: List<GradeHistoryTrend>? = null,
    ): Map<String, Any?> {
        var markBudget = 80
        val subjects = if (!shareMarks) emptyList() else dashboard?.marksResponse?.subjects.orEmpty().take(80).map { subject ->
            val marks = subject.marks.sortedByDescending { it.markDate.orEmpty() }.take(minOf(5, markBudget))
            markBudget -= marks.size
            val calculation = GradeSimulation.prepare(subject)
            fun bounded(value: Double?) = value?.takeIf { it.isFinite() && kotlin.math.abs(it) <= 1e12 }
            mapOf(
                "id" to subject.id.take(128), "name" to subject.displayName.take(120),
                "abbreviation" to subject.subjectInfo.abbrev.take(32), "average" to GradeMath.subjectAverage(subject),
                "pointsOnly" to subject.pointsOnly, "totalMarkCount" to subject.marks.size,
                "calculation" to mapOf("localAverage" to bounded(calculation.calculatedAverage), "providerAverage" to bounded(calculation.officialAverage),
                    "weightedSum" to bounded(calculation.weightedSum), "totalWeight" to bounded(calculation.totalWeight),
                    "includedCount" to calculation.marks.size, "excludedCount" to calculation.excludedMarkCount,
                    "confidence" to calculation.confidence.name.lowercase(Locale.ROOT), "issues" to calculation.issues.map { issue ->
                        issue.name.lowercase(Locale.ROOT).split('_').mapIndexed { index, part -> if (index == 0) part else part.replaceFirstChar(Char::uppercase) }.joinToString("").replace("Ids", "IDs")
                    }.sorted().take(10)),
                "recentMarks" to marks.map { mark -> mapOf(
                    "value" to mark.markText.take(64), "date" to mark.markDate.orEmpty().substringBefore('T').take(32),
                    "weight" to bounded(mark.weight), "title" to (mark.caption ?: mark.theme)?.take(200),
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
        val trends = if (!shareMarks || dashboard == null || history == null) emptyList() else history.take(80).mapNotNull { trend ->
            val subject = dashboard.marksResponse.subjects.firstOrNull { it.id == trend.subjectID } ?: return@mapNotNull null
            val firstCount = trend.firstMarkCount ?: return@mapNotNull null
            val latestCount = trend.latestMarkCount ?: return@mapNotNull null
            mapOf("subjectID" to subject.id.take(128), "subjectName" to subject.displayName.take(120),
                "subjectAbbreviation" to subject.subjectInfo.abbrev.take(32), "latestAverage" to trend.latestAverage,
                "firstAverage" to trend.firstAverage, "averageDelta" to trend.averageDelta,
                "latestMarkCount" to latestCount.coerceAtLeast(0), "firstMarkCount" to firstCount.coerceAtLeast(0))
        }
        val context = mutableMapOf<String, Any?>(
            "schoolScope" to scope, "generatedAt" to now, "isStale" to stale,
            "unavailableSections" to buildList {
                if (!shareMarks || dashboard == null) add("marks")
                if (!shareMarks || dashboard == null || history == null || history.any { it.firstMarkCount == null || it.latestMarkCount == null }) add("trends")
                if (!shareTimetable || weeks.isEmpty()) add("timetable")
            },
            "subjects" to subjects, "trends" to trends, "timetable" to lessons,
        )
        // Bound request bytes as well as item counts when schools supply unusually long text.
        while (encodedSize(context) > MAX_CONTEXT_BYTES) {
            val timetable = context["timetable"] as List<*>
            val currentSubjects = context["subjects"] as List<*>
            val currentTrends = context["trends"] as List<*>
            when {
                timetable.isNotEmpty() -> context["timetable"] = timetable.dropLast(1)
                currentTrends.isNotEmpty() -> context["trends"] = currentTrends.dropLast(1)
                currentSubjects.isNotEmpty() -> context["subjects"] = currentSubjects.dropLast(1)
                else -> error("The school context is too large to send.")
            }
        }
        return context
    }

    fun encodedSize(data: Map<String, Any?>): Int = jsonValue(data).toString().toByteArray(Charsets.UTF_8).size

    /** Match iOS purpose selection; a general reply never includes school records. */
    fun select(source: Map<String, Any?>, selection: AISelection, now: LocalDate = LocalDate.now(ZoneId.of("Europe/Prague"))): Map<String, Any?> {
        val allSubjects = (source["subjects"] as? List<*>).orEmpty().map { it.objectMap() }
        val allEvents = (source["events"] as? List<*>).orEmpty().map { it.objectMap() }
        val allInsights = (source["insights"] as? List<*>).orEmpty().map { it.objectMap() }
        fun eventDate(event: Map<String, Any?>): LocalDate? = event.string("date")?.let { raw ->
            runCatching { OffsetDateTime.parse(raw).atZoneSameInstant(ZoneId.of("Europe/Prague")).toLocalDate() }.getOrNull()
                ?: runCatching { LocalDate.parse(raw.take(10)) }.getOrNull()
        }
        val selectedEvent = allEvents.firstOrNull { it.string("id") == selection.eventID }
        val selectedIDs = when (selection.action) {
            AIAction.PRIORITIES -> {
                val knownIDs = allSubjects.mapNotNull { it.string("id") }.toSet()
                val upcoming = allEvents.filter { event -> eventDate(event)?.let { !it.isBefore(now) && it.isBefore(now.plusWeeks(1)) } == true }
                (allInsights.mapNotNull { it.string("subjectID") } + upcoming.mapNotNull { it.string("subjectID") } +
                    allSubjects.sortedByDescending { it.number("average") ?: 0.0 }.mapNotNull { it.string("id") })
                    .filter { it in knownIDs }.distinct().take(3).toSet()
            }
            AIAction.SUBJECT -> setOfNotNull(selection.subjectID)
            AIAction.TEST -> setOfNotNull(selectedEvent?.string("subjectID"))
            else -> emptySet()
        }
        val subjects = allSubjects.filter { it.string("id") in selectedIDs }.map {
            if (selection.action == AIAction.PRIORITIES) it + ("recentMarks" to emptyList<Any>()) else it
        }
        val required = when (selection.action) {
            AIAction.REPLY -> emptySet()
            AIAction.TOMORROW -> setOf("timetable", "events")
            AIAction.WEEK -> setOf("timetable", "events", "insights")
            AIAction.PRIORITIES, AIAction.SUBJECT -> setOf("marks", "trends", "events", "insights")
            AIAction.TEST -> setOf("marks", "events")
        }
        val monday = selection.weekStart?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: now.minusDays((now.dayOfWeek.value - 1).toLong())
        val lessons = (source["timetable"] as? List<*>).orEmpty().map { it.objectMap() }.filter { lesson ->
            val date = lesson.string("date")?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            when (selection.action) {
                AIAction.TOMORROW -> date == now.plusDays(1)
                AIAction.WEEK -> date != null && !date.isBefore(monday) && date.isBefore(monday.plusWeeks(1))
                else -> false
            }
        }.take(60)
        val events = allEvents.filter { event ->
            val date = eventDate(event)
            when (selection.action) {
                AIAction.REPLY -> false
                AIAction.TOMORROW -> date == now.plusDays(1)
                AIAction.WEEK -> date != null && !date.isBefore(monday) && date.isBefore(monday.plusWeeks(1))
                AIAction.PRIORITIES -> date != null && !date.isBefore(now) && date.isBefore(now.plusWeeks(1))
                AIAction.SUBJECT -> event.string("subjectID") == selection.subjectID && selection.subjectID != null && date != null && !date.isBefore(now) && date.isBefore(now.plusWeeks(1))
                AIAction.TEST -> event.string("id") == selection.eventID
            }
        }.take(30).map { event -> event + ("notes" to event.string("notes")?.takeIf {
            selection.includeNotes && selection.action == AIAction.TEST && event.string("id") == selection.eventID
        }?.take(1_000)) }
        val unavailable = buildList {
            addAll((source["unavailableSections"] as? List<*>).orEmpty().filterIsInstance<String>())
            if (source["events"] !is List<*>) add("events")
            if (source["insights"] !is List<*>) add("insights")
            if (selection.action in setOf(AIAction.SUBJECT, AIAction.TEST) && subjects.isEmpty()) add("marks")
            if (selection.action == AIAction.TEST && selectedEvent == null) add("events")
        }.filter { it in required }.distinct()
        val insights = if ("insights" !in required) emptyList() else allInsights.filter { insight ->
            if (selection.action == AIAction.WEEK) {
                val observed = insight.number("observedAt")?.toLong()?.let { Instant.ofEpochMilli(it).atZone(ZoneId.of("Europe/Prague")).toLocalDate() }
                insight.string("kind") == "observed_grade_change" && observed != null && !observed.isBefore(monday) && observed.isBefore(monday.plusWeeks(1))
            } else insight.string("subjectID") in selectedIDs
        }.take(5)
        val freshness = (source["sourceFreshness"] as? List<*>)?.map { it.objectMap() }?.filter { it.string("section") in required }
        return source + mapOf("subjects" to subjects,
            "trends" to if ("trends" in required) (source["trends"] as? List<*>).orEmpty().filter { it.objectMap().string("subjectID") in selectedIDs } else emptyList<Any>(),
            "timetable" to lessons, "events" to events, "insights" to insights, "sourceFreshness" to freshness,
            "unavailableSections" to unavailable, "isStale" to (selection.action != AIAction.REPLY &&
                (unavailable.isNotEmpty() || (freshness?.any { it.boolean("isStale") == true } ?: (source.boolean("isStale") == true)))))
    }

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
