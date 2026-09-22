package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.domain.GradeMath
import com.bukovinafilip.gradey.model.Absence
import com.bukovinafilip.gradey.model.AbsenceResponse
import com.bukovinafilip.gradey.model.EduPageSessionData
import com.bukovinafilip.gradey.model.EduPageSubjectProfile
import com.bukovinafilip.gradey.model.Mark
import com.bukovinafilip.gradey.model.MarksResponse
import com.bukovinafilip.gradey.model.SchoolStudentProfile
import com.bukovinafilip.gradey.model.Subject
import com.bukovinafilip.gradey.model.SubjectInfo
import com.bukovinafilip.gradey.model.TimetableAtom
import com.bukovinafilip.gradey.model.TimetableChange
import com.bukovinafilip.gradey.model.TimetableDayDTO
import com.bukovinafilip.gradey.model.TimetableEntity
import com.bukovinafilip.gradey.model.TimetableGroup
import com.bukovinafilip.gradey.model.TimetableHour
import com.bukovinafilip.gradey.model.TimetableResponse
import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup

enum class EduPageAuthFailure {
    MISSING_FIELDS, BAD_CREDENTIALS, CAPTCHA_REQUIRED, TWO_FACTOR_REQUIRED,
    INVALID_TWO_FACTOR_CODE, TWO_FACTOR_NOT_CONFIRMED, INVALID_STUDENT,
    NO_LINKED_STUDENTS, SESSION_EXPIRED, MALFORMED_RESPONSE,
}

class EduPageAuthenticationException(val reason: EduPageAuthFailure, message: String) : RuntimeException(message)

internal fun eduPageFailure(reason: EduPageAuthFailure, message: String): Nothing =
    throw EduPageAuthenticationException(reason, message)

/** Parses the same embedded JSON contract used by Gradey's iOS EduPage client. */
internal object EduPageResponseParser {
    fun session(html: String, username: String, password: String, sessionID: String): EduPageSessionData {
        val root = EduPageHtmlParser.firstJSONObject(html, "userid")
        val userID = root?.get("userid").text()
        if (root == null || userID.isNullOrBlank()) {
            if (looksLikeLoginPage(html)) eduPageFailure(EduPageAuthFailure.SESSION_EXPIRED, "Your EduPage session has expired. Sign in again.")
            malformed("Missing logged-in user data.")
        }
        val dbi = root["dbi"].obj()
        val classes = dbi["classes"].obj()
        val parent = isParent(userID)
        val students = dbi["students"].obj().mapNotNull { (id, raw) ->
            val student = raw as? JsonObject ?: return@mapNotNull null
            val linked = if (parent) student.any { (key, value) ->
                key.contains("parent", ignoreCase = true) && numericID(value.text().orEmpty()) == numericID(userID)
            } else numericID(id) == numericID(userID)
            if (!linked) return@mapNotNull null
            val name = listOfNotNull(student["firstname"].text(), student["lastname"].text()).joinToString(" ").trim()
            val classID = student["classid"].text()
            val schoolClass = classID?.let { classes[it] }.obj()
            SchoolStudentProfile(
                id = if (id.startsWith("Student")) id else "Student$id",
                fullName = name.ifBlank { username },
                classID = classID,
                className = (schoolClass["short"] ?: schoolClass["name"]).text(),
            )
        }.sortedBy { it.fullName.lowercase(Locale.ROOT) }
        val subjects = dbi["subjects"].obj().mapNotNull { (id, raw) ->
            val subject = raw as? JsonObject ?: return@mapNotNull null
            val name = subject["name"].text() ?: subject["short"].text() ?: id
            EduPageSubjectProfile(id, name, subject["short"].text() ?: name)
        }
        return EduPageSessionData(
            sessionID = sessionID, username = username, password = password,
            gsecHash = EduPageHtmlParser.firstCapture(html, listOf("ASC.gsechash=\"", "\"gsechash\":\""))
                ?: root["ASC"].obj()["gsechash"].text().orEmpty(),
            userID = userID,
            schoolName = recursiveString(root, setOf("schoolName", "school_name", "schoolname")),
            activeStudent = if (parent) null else students.firstOrNull { numericID(it.id) == numericID(userID) } ?: students.firstOrNull(),
            linkedStudents = students,
            subjects = subjects,
        )
    }

    fun marks(html: String, profiles: List<EduPageSubjectProfile>): MarksResponse {
        val root = EduPageHtmlParser.firstJSONObject(html, "vsetkyZnamky") ?: malformed("Missing grade data.")
        val rawGrades = root["vsetkyZnamky"] as? JsonArray ?: malformed("Invalid grade data.")
        val events = root["vsetkyUdalosti"].obj()["edupage"].obj()
        val subjects = profiles.associateBy { it.id }
        val grades = rawGrades.mapNotNull { raw ->
            val grade = raw as? JsonObject ?: return@mapNotNull null
            val eventID = grade["udalostid"].text() ?: return@mapNotNull null
            val event = events[eventID] as? JsonObject ?: return@mapNotNull null
            val subjectID = event["PredmetID"].text()?.takeUnless { it == "vsetky" } ?: return@mapNotNull null
            val rawValue = grade["data"].text().orEmpty()
            val markText = rawValue.substringBefore(" (").trim()
            val comment = rawValue.substringAfter(" (", "").trimEnd(')').takeIf { it.isNotBlank() }
            val gradeType = event["p_typ_udalosti"].text() ?: "1"
            val isPoints = gradeType in listOf("2", "3")
            val supported = gradeType == "1" && GradeMath.parseMarkValue(markText)?.let { it in 1.0..5.0 } == true
            val maxPoints = when (gradeType) {
                "2" -> event["p_vaha"].number()
                "3" -> event["p_vaha_body"].number()
                else -> null
            }
            Mark(
                id = eventID, markDate = grade["datum"].text(), caption = event["p_meno"].text(),
                theme = comment, markText = markText, teacherID = event["UcitelID"].text(),
                type = if (isPoints) "points" else if (supported) "grade" else "unsupported",
                typeNote = if (gradeType == "3") "%" else null,
                weight = if (isPoints) null else event["p_vaha"].number()?.div(20.0),
                subjectID = subjectID, isPoints = isPoints,
                pointsText = if (isPoints) markText else null, maxPoints = maxPoints?.roundToInt(),
            )
        }
        return MarksResponse(grades.groupBy { it.subjectID }.map { (id, marks) ->
            val profile = subjects[id]
            val name = profile?.name?.ifBlank { null } ?: profile?.shortName ?: id
            Subject(
                marks = marks,
                subjectInfo = SubjectInfo(id, profile?.shortName ?: name, name),
                pointsOnly = marks.isNotEmpty() && marks.all { it.isPoints },
                markPredictionEnabled = false,
            )
        }.sortedBy { it.displayName.lowercase(Locale.ROOT) })
    }

    fun absences(html: String, studentID: String): AbsenceResponse {
        val root = EduPageHtmlParser.firstJSONObject(html, "dateStats") ?: malformed("Missing attendance statistics.")
        val all = root["dateStats"].obj()
        val byDate = (all[numericID(studentID)] ?: all[studentID]) as? JsonObject
            ?: malformed("Attendance is unavailable for this student.")
        val days = byDate.mapNotNull { (date, raw) ->
            val stats = raw as? JsonObject ?: return@mapNotNull null
            val absent = stats["absent"].count()
            val excused = stats["excused"].count()
            val unexcused = stats["unexcused"].count()
            val late = stats["late"].count()
            val early = stats["early"].count()
            val distant = stats["distant"].count()
            if (absent + late + early + distant <= 0) return@mapNotNull null
            Absence(date = date, ok = excused, missed = unexcused,
                unsolved = (absent - excused - unexcused).coerceAtLeast(0), late = late, soon = early, distanceTeaching = distant)
        }.sortedBy { it.date }
        return AbsenceResponse(absences = days)
    }

    fun timetable(html: String, profiles: List<EduPageSubjectProfile>): TimetableResponse {
        val root = EduPageHtmlParser.firstJSONObject(html, "dates") ?: malformed("Missing timetable dates.")
        val rawDates = root["dates"] as? JsonObject ?: malformed("Invalid timetable dates.")
        val subjectProfiles = profiles.associateBy { it.id }
        val hours = linkedMapOf<String, TimetableHour>()
        val subjects = linkedMapOf<String, TimetableEntity>()
        val teachers = linkedMapOf<String, TimetableEntity>()
        val rooms = linkedMapOf<String, TimetableEntity>()
        val groups = linkedMapOf<String, TimetableGroup>()
        val days = rawDates.toSortedMap().mapNotNull { (date, rawDay) ->
            val day = rawDay as? JsonObject ?: return@mapNotNull null
            val plan = day["plan"] as? JsonArray ?: return@mapNotNull null
            val parsedDate = runCatching { LocalDate.parse(date) }.getOrNull() ?: return@mapNotNull null
            val atoms = plan.mapNotNull lesson@ { rawLesson ->
                val lesson = rawLesson as? JsonObject ?: return@lesson null
                if (lesson["header"] != null) return@lesson null
                val type = lesson["type"].text() ?: "lesson"
                if (type !in listOf("lesson", "event", "out", "absent", "")) return@lesson null
                val period = lesson["uniperiod"].count()
                if (period <= 0) return@lesson null
                val begin = lesson["starttime"].text().orEmpty().replace("24:00", "23:59")
                val end = lesson["endtime"].text().orEmpty().replace("24:00", "23:59")
                // A school can use different times for the same period on different days.
                val hourID = "$period@$begin-$end"
                hours[hourID] = TimetableHour(hourID, period.toString(), begin, end)
                val flags = lesson["flags"].obj()
                val dp0 = flags["dp0"].obj()
                val event = flags["event"].obj()
                val subjectID = (lesson["subjectid"] ?: dp0["subjectid"]).text()
                if (subjectID != null) {
                    val profile = subjectProfiles[subjectID]
                    val name = profile?.name ?: (lesson["subjectname"] ?: dp0["subjectname"]).text() ?: subjectID
                    val short = profile?.shortName ?: (lesson["subjectshort"] ?: dp0["subjectshort"]).text() ?: name
                    subjects[subjectID] = TimetableEntity(subjectID, short, name)
                }
                val teacherName = lesson["teachernames"].strings().firstOrNull()
                val teacherID = lesson["teacherids"].strings().firstOrNull() ?: teacherName
                if (teacherID != null) teachers[teacherID] = TimetableEntity(teacherID, teacherName ?: teacherID, teacherName ?: teacherID)
                val roomName = lesson["classroomnames"].strings().firstOrNull()
                val roomID = lesson["classroomids"].strings().firstOrNull() ?: roomName
                if (roomID != null) rooms[roomID] = TimetableEntity(roomID, roomName ?: roomID, roomName ?: roomID)
                val groupNames = lesson["groupnames"].strings()
                groupNames.forEach { groups[it] = TimetableGroup(it, it, it) }
                val canceled = lesson["removed"].text() in listOf("true", "1") || type == "absent" || type.isEmpty()
                TimetableAtom(
                    hourID = hourID, subjectID = subjectID, teacherID = teacherID, roomID = roomID, groupIDs = groupNames,
                    theme = (dp0["note_wd"] ?: event["name"] ?: lesson["curriculum"]).text(),
                    change = if (canceled) TimetableChange("Canceled", (lesson["changeinfo"] ?: lesson["note"]).text()) else null,
                )
            }
            TimetableDayDTO(atoms, parsedDate.dayOfWeek.value, date, dayType = if (parsedDate.dayOfWeek.value <= 5) "WorkDay" else "Weekend")
        }
        return TimetableResponse(
            hours = hours.values.sortedWith(compareBy({ it.caption.toIntOrNull() ?: 0 }, { it.beginTime })),
            days = days, subjects = subjects.values.toList(), teachers = teachers.values.toList(),
            rooms = rooms.values.toList(), groups = groups.values.toList(),
        )
    }

    fun looksLikeLoginPage(html: String): Boolean {
        val doc = Jsoup.parse(html)
        return html.contains("cmd=MainLogin") ||
            (doc.selectFirst("input[name=username]") != null && doc.selectFirst("input[name=password]") != null)
    }

    fun numericID(value: String): String = value.filter(Char::isDigit).ifBlank { value }
    fun isParent(value: String): Boolean = value.contains("rodic", true) || value.contains("parent", true)
    private fun malformed(message: String): Nothing = eduPageFailure(EduPageAuthFailure.MALFORMED_RESPONSE, message)

    private fun recursiveString(value: JsonElement, keys: Set<String>): String? = when (value) {
        is JsonObject -> value.entries.firstNotNullOfOrNull { (key, child) -> child.text()?.takeIf { key in keys && it.isNotBlank() } }
            ?: value.values.firstNotNullOfOrNull { recursiveString(it, keys) }
        is JsonArray -> value.firstNotNullOfOrNull { recursiveString(it, keys) }
        else -> null
    }
}

internal fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.contentOrNull
internal fun JsonElement?.obj(): JsonObject = this as? JsonObject ?: JsonObject(emptyMap())
private fun JsonElement?.number(): Double? = text()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }
private fun JsonElement?.count(): Int = number()?.roundToInt()?.coerceAtLeast(0) ?: 0
private fun JsonElement?.strings(): List<String> = (this as? JsonArray)?.mapNotNull { it.text() }.orEmpty()
