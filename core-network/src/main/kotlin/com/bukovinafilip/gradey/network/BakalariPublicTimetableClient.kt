package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.BakalariPublicTimetableClient
import com.bukovinafilip.gradey.domain.FriendTimetableException
import com.bukovinafilip.gradey.domain.FriendTimetableException.Reason
import com.bukovinafilip.gradey.domain.KomensText
import com.bukovinafilip.gradey.domain.PublicTimetableTerm
import com.bukovinafilip.gradey.domain.SchoolDirectorySearch
import com.bukovinafilip.gradey.model.PublicTimetableClass
import com.bukovinafilip.gradey.model.TimetableAtom
import com.bukovinafilip.gradey.model.TimetableChange
import com.bukovinafilip.gradey.model.TimetableDayDTO
import com.bukovinafilip.gradey.model.TimetableEntity
import com.bukovinafilip.gradey.model.TimetableGroup
import com.bukovinafilip.gradey.model.TimetableHour
import com.bukovinafilip.gradey.model.TimetableResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads the school's public timetable pages, no sign-in (iOS `URLSessionBakalariPublicTimetableClient`).
 * Schools can turn this off, in which case Bakaláři redirects to its login page.
 */
class BakalariPublicTimetableNetworkClient(okHttpClient: OkHttpClient = OkHttpClient()) : BakalariPublicTimetableClient {
    private val client = okHttpClient.newBuilder().callTimeout(20, TimeUnit.SECONDS).build()

    override suspend fun fetchClasses(schoolURL: String): List<PublicTimetableClass> {
        val classes = BakalariPublicTimetableParser.classes(page(schoolURL, "Timetable", "Public"))
        if (classes.isEmpty()) throw FriendTimetableException(Reason.NOT_PUBLISHED)
        return classes
    }

    override suspend fun fetchTimetable(schoolURL: String, classID: String, term: PublicTimetableTerm, weekStart: LocalDate) =
        BakalariPublicTimetableParser.timetable(page(schoolURL, "Timetable", "Public", term.path, "Class", classID), term, weekStart)
            ?: throw FriendTimetableException(Reason.UNEXPECTED_PAGE)

    private suspend fun page(schoolURL: String, vararg segments: String): String {
        val base = schoolURL.trimEnd('/').toHttpUrlOrNull() ?: throw FriendTimetableException(Reason.UNEXPECTED_PAGE)
        val url = segments.fold(base.newBuilder()) { builder, segment -> builder.addPathSegment(segment) }.build()
        val request = Request.Builder().url(url).get().header("Accept-Language", "cs-CZ,cs;q=0.9").header("Accept", "text/html").build()
        return suspendCancellableCoroutine { continuation ->
            val call = client.newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val html = response.use {
                            // A school that doesn't publish its timetable sends visitors to sign in.
                            if (it.request.url.encodedPath.contains("login", ignoreCase = true)) throw FriendTimetableException(Reason.NOT_PUBLISHED)
                            if (it.code == 404 || it.code == 401 || it.code == 403) throw FriendTimetableException(Reason.NOT_PUBLISHED)
                            if (!it.isSuccessful) throw FriendTimetableException(Reason.HTTP_STATUS, it.code)
                            val source = it.body?.source() ?: throw FriendTimetableException(Reason.UNEXPECTED_PAGE)
                            source.request(MaxResponseBytes + 1)
                            if (source.buffer.size > MaxResponseBytes) throw FriendTimetableException(Reason.UNEXPECTED_PAGE)
                            source.readUtf8()
                        }
                        if (continuation.isActive) continuation.resume(html)
                    } catch (error: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
        }
    }

    private companion object {
        const val MaxResponseBytes = 3L * 1024 * 1024
    }
}

/**
 * Turns the public timetable page into the same payload the Bakaláři API returns, so friends'
 * timetables go through `TimetableMapper` like yours (iOS `BakalariPublicTimetableParser`). The
 * page embeds its data as `const timetableData = {…};`.
 */
object BakalariPublicTimetableParser {
    private val Option = Regex("<option\\s+value=\"([^\"]*)\"[^>]*>(.*?)</option>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val Space = Regex("\\s+")

    fun classes(html: String): List<PublicTimetableClass> {
        val start = html.indexOf("id=\"selectedClass\"").takeIf { it >= 0 } ?: return emptyList()
        val end = html.indexOf("</select>", start).takeIf { it >= 0 } ?: return emptyList()
        return Option.findAll(html.substring(start, end)).mapNotNull { match ->
            val id = KomensText.decodeEntities(match.groupValues[1]).trim()
            val name = KomensText.decodeEntities(match.groupValues[2]).replace(Space, " ").trim()
            if (id.isEmpty() || name.isEmpty()) null else PublicTimetableClass(id, name)
        }.toList()
    }

    fun timetable(html: String, term: PublicTimetableTerm, weekStart: LocalDate): TimetableResponse? {
        val json = embeddedJSON(html, "const timetableData") ?: return null
        val payload = runCatching { GradeyJson.decodeFromString(Payload.serializer(), json) }.getOrNull() ?: return null
        return response(payload, term, weekStart)
    }

    internal fun response(payload: Payload, term: PublicTimetableTerm, weekStart: LocalDate): TimetableResponse {
        val subjects = LinkedHashMap<String, TimetableEntity>()
        val teachers = LinkedHashMap<String, TimetableEntity>()
        val rooms = LinkedHashMap<String, TimetableEntity>()
        val groups = LinkedHashMap<String, TimetableGroup>()
        val cycles = LinkedHashMap<String, TimetableEntity>()
        val hours = payload.hours.orEmpty().mapIndexedTo(ArrayList()) { index, header ->
            TimetableHour((index + 1).toString(), header.caption ?: "${index + 1}", header.beginTime.orEmpty(), header.endTime.orEmpty())
        }
        val hourByMinute = HashMap<Int, String>()
        hours.forEach { hour -> minutes(hour.beginTime)?.let { hourByMinute.putIfAbsent(it, hour.id) } }

        val days = payload.days.orEmpty().mapIndexed { index, day ->
            val dayOfWeek = day.dayAbbrev?.let(::weekday) ?: (index + 1)
            val date = if (term == PublicTimetableTerm.PERMANENT) "" else weekStart.plusDays((dayOfWeek - 1).toLong()).toString()
            if (day.dayOff == true) return@mapIndexed TimetableDayDTO(dayOfWeek = dayOfWeek, date = date, dayDescription = day.dayOffName.orEmpty(), dayType = "Holiday")
            val atoms = ArrayList<TimetableAtom>()
            for (hour in day.hours.orEmpty()) {
                val beginMinute = hour.beginTimeValue ?: minutes(hour.begin.orEmpty())
                val hourID = beginMinute?.let(hourByMinute::get) ?: run {
                    // An hour the header doesn't list (a zero lesson, say).
                    val id = ((hours.mapNotNull { it.id.toIntOrNull() }.maxOrNull() ?: 0) + 1).toString()
                    hours += TimetableHour(id, "", clock(hour.begin), clock(hour.end))
                    if (beginMinute != null) hourByMinute[beginMinute] = id
                    id
                }
                val text = hour.hourText?.trim().orEmpty()
                if (hour.atoms.isNullOrEmpty() && text.isNotEmpty()) {
                    // A class-wide event in place of lessons (a trip, an exam day).
                    val subjectID = "event-$text"
                    subjects[subjectID] = TimetableEntity(subjectID, text, text)
                    atoms += TimetableAtom(hourID = hourID, subjectID = subjectID, change = TimetableChange("Event", text))
                    continue
                }
                for (atom in hour.atoms.orEmpty()) {
                    val removed = atom.type.equals("removed", ignoreCase = true)
                    var subjectAbbrev = atom.subjectAbbrev
                    var subjectName = atom.subjectText
                    var teacherName = atom.teacherFullname
                    val info = atom.changeInfo
                    if (removed && info != null && info.indexOf('(') in 0 until info.lastIndexOf(')')) {
                        // "Vyjmuto z rozvrhu (ČJL, Grausgruberová Romana)"
                        val parts = info.substring(info.indexOf('(') + 1, info.lastIndexOf(')')).split(",", limit = 2).map { it.trim() }
                        subjectAbbrev = parts.firstOrNull()
                        subjectName = parts.firstOrNull()
                        teacherName = parts.getOrNull(1)
                    }
                    val subjectID = entityID(subjectAbbrev, subjectName)?.also { subjects[it] = TimetableEntity(it, subjectAbbrev ?: subjectName, subjectName ?: subjectAbbrev) }
                    val teacherID = entityID(atom.teacher, teacherName)?.also { teachers[it] = TimetableEntity(it, atom.teacher ?: teacherName, teacherName ?: atom.teacher) }
                    val roomID = entityID(atom.room, atom.roomFullName)?.also { rooms[it] = TimetableEntity(it, atom.room ?: atom.roomFullName, atom.roomFullName ?: atom.room) }
                    val groupIDs = groupNames(atom.groupsNames).map { name -> "g-$name".also { groups[it] = TimetableGroup(it, name, name) } }
                    val cycleIDs = atom.cycleText?.trim()?.takeIf { it.isNotEmpty() }?.let { cycle -> listOf("c-$cycle".also { cycles[it] = TimetableEntity(it, cycle, cycle) }) }.orEmpty()
                    atoms += TimetableAtom(
                        hourID = hourID, subjectID = subjectID, teacherID = teacherID, roomID = roomID, groupIDs = groupIDs,
                        theme = atom.theme, change = change(atom, removed), cycleIDs = cycleIDs,
                    )
                }
            }
            TimetableDayDTO(atoms = atoms, dayOfWeek = dayOfWeek, date = date)
        }
        return TimetableResponse(
            hours = hours, days = days, groups = groups.values.sortedBy { it.id }, subjects = subjects.values.sortedBy { it.id },
            teachers = teachers.values.sortedBy { it.id }, rooms = rooms.values.sortedBy { it.id }, cycles = cycles.values.sortedBy { it.id },
        )
    }

    /** Group names on an atom, "AJ1" or "AJ1, NJ". */
    fun groupNames(value: String?): List<String> = value.orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private fun change(atom: Payload.Atom, removed: Boolean): TimetableChange? {
        val description = atom.changeInfo?.trim()
        if (removed) return TimetableChange("Removed", description)
        if (atom.hasChanged != true) return null
        val tooltipCode = atom.tooltipDetails?.let { details ->
            runCatching { ((GradeyJson.parseToJsonElement(details) as? JsonObject)?.get("infoChangeCode") as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
        }
        val code = tooltipCode ?: when (atom.infoChangeCode) {
            0 -> "Removed"
            3 -> "Added"
            4 -> "RoomChanged"
            else -> "Substitution"
        }
        return TimetableChange(code, description)
    }

    private fun entityID(abbrev: String?, name: String?): String? =
        listOfNotNull(abbrev?.trim(), name?.trim()).filter { it.isNotEmpty() }.joinToString("|").ifEmpty { null }

    /** "8:00" or "08:00:00" to minutes after midnight. */
    private fun minutes(value: String): Int? {
        val parts = value.split(':').mapNotNull { it.trim().toIntOrNull() }
        return if (parts.size >= 2) parts[0] * 60 + parts[1] else null
    }

    private fun clock(value: String?): String = value?.let(::minutes)?.let { "%d:%02d".format(it / 60, it % 60) }.orEmpty()

    /** Monday is 1. Czech, Slovak and English names and abbreviations, without diacritics. */
    internal fun weekday(text: String): Int? {
        val token = SchoolDirectorySearch.normalize(text.trim()).split(Regex("[\\s.,:]+")).firstOrNull { it.isNotEmpty() } ?: return null
        return WeekdayNames[token]
    }

    private val WeekdayNames: Map<String, Int> = mapOf(
        1 to listOf("po", "pondeli", "pondelok", "mon", "monday"),
        2 to listOf("ut", "utery", "utorok", "tue", "tues", "tuesday"),
        3 to listOf("st", "streda", "wed", "wednesday"),
        4 to listOf("ct", "ctvrtek", "stv", "stvrtok", "thu", "thur", "thurs", "thursday"),
        5 to listOf("pa", "patek", "pi", "piatok", "fri", "friday"),
        6 to listOf("so", "sobota", "sat", "saturday"),
        7 to listOf("ne", "nedele", "nedela", "sun", "sunday"),
    ).flatMap { (day, names) -> names.map { it to day } }.toMap()

    /**
     * The JSON object literal assigned after [marker], found by matching braces outside strings
     * (the data itself contains `;` and `}` in text).
     */
    fun embeddedJSON(html: String, marker: String): String? {
        val markerIndex = html.indexOf(marker).takeIf { it >= 0 } ?: return null
        val start = html.indexOf('{', markerIndex + marker.length).takeIf { it >= 0 } ?: return null
        var depth = 0
        var inString = false
        var escaped = false
        for (index in start until html.length) {
            val character = html[index]
            when {
                inString -> when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> inString = false
                }
                character == '"' -> inString = true
                character == '{' -> depth++
                character == '}' -> if (--depth == 0) return html.substring(start, index + 1)
            }
        }
        return null
    }

    @Serializable
    internal data class Payload(@SerialName("Days") val days: List<Day>? = null, @SerialName("Hours") val hours: List<HeaderHour>? = null) {
        @Serializable
        data class HeaderHour(@SerialName("Caption") val caption: String? = null, @SerialName("BeginTime") val beginTime: String? = null, @SerialName("EndTime") val endTime: String? = null)

        @Serializable
        data class Day(
            @SerialName("DayAbbrev") val dayAbbrev: String? = null, @SerialName("DayOff") val dayOff: Boolean? = null,
            @SerialName("DayOffName") val dayOffName: String? = null, @SerialName("Hours") val hours: List<Hour>? = null,
        )

        @Serializable
        data class Hour(
            @SerialName("Atoms") val atoms: List<Atom>? = null, @SerialName("HourText") val hourText: String? = null,
            @SerialName("Begin") val begin: String? = null, @SerialName("End") val end: String? = null, @SerialName("BeginTimeValue") val beginTimeValue: Int? = null,
        )

        @Serializable
        data class Atom(
            @SerialName("Type") val type: String? = null, @SerialName("SubjectText") val subjectText: String? = null,
            @SerialName("SubjectAbbrev") val subjectAbbrev: String? = null, @SerialName("Teacher") val teacher: String? = null,
            @SerialName("TeacherFullname") val teacherFullname: String? = null, @SerialName("Room") val room: String? = null,
            @SerialName("RoomFullName") val roomFullName: String? = null, @SerialName("GroupsNames") val groupsNames: String? = null,
            @SerialName("Theme") val theme: String? = null, @SerialName("ChangeInfo") val changeInfo: String? = null,
            @SerialName("HasChanged") val hasChanged: Boolean? = null, @SerialName("InfoChangeCode") val infoChangeCode: Int? = null,
            @SerialName("CycleText") val cycleText: String? = null, @SerialName("TooltipDetails") val tooltipDetails: String? = null,
        )
    }
}

/** Demo classes: the sample timetable under three class names (iOS `MockBakalariPublicTimetableClient`). */
class DemoBakalariPublicTimetableClient : BakalariPublicTimetableClient {
    override suspend fun fetchClasses(schoolURL: String) = listOf(PublicTimetableClass("A1", "1.A"), PublicTimetableClass("B2", "2.B"), PublicTimetableClass("C3", "3.C"))

    override suspend fun fetchTimetable(schoolURL: String, classID: String, term: PublicTimetableTerm, weekStart: LocalDate): TimetableResponse =
        if (term == PublicTimetableTerm.PERMANENT) com.bukovinafilip.gradey.domain.DemoData.permanentTimetableResponse()
        else com.bukovinafilip.gradey.domain.DemoData.timetableResponse(weekStart.toString())
}
