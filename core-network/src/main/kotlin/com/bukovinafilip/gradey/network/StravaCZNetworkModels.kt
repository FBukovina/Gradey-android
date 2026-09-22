package com.bukovinafilip.gradey.network

import com.bukovinafilip.gradey.model.StravaCZMeal
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.StravaCZMenuDay
import com.bukovinafilip.gradey.model.StravaCZStoredSession
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.ResolverStyle
import java.util.Locale

/** Maps the same provider response used by the iOS StravaCZ client. */
object StravaCZMenuMapper {
    fun demoMenu(): StravaCZMenu = com.bukovinafilip.gradey.domain.DemoData.stravaMenu

    fun normalize(mealsByDay: Map<String, List<StravaCZMeal>>): StravaCZMenu =
        StravaCZMenu(mealsByDay.toSortedMap().map { (date, meals) ->
            StravaCZMenuDay(
                id = date,
                title = runCatching {
                    LocalDate.parse(date).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL))
                }.getOrDefault(date),
                date = date,
                meals = meals,
            )
        })

    fun parse(response: JsonObject): StravaCZMenu {
        val tables = response.filterKeys { it.startsWith("table") }
        if (tables.isEmpty()) throw StravaCZApiException("The canteen returned an unreadable menu. Please try again.")
        val byDay = linkedMapOf<String, MutableList<StravaCZMeal>>()
        tables.values.forEach { table ->
            val rows = table as? JsonArray ?: return@forEach
            rows.forEach row@{ item ->
                val row = item as? JsonObject ?: return@row
                val name = row.text("nazev").orEmpty().trim()
                val type = row.text("druh_popis").orEmpty().trim()
                val details = row.text("delsiPopis").orEmpty().trim()
                val restrictions = (row["omezeniObj"] as? JsonObject)?.text("den").orEmpty()
                val allergens = (row["alergeny"] as? JsonArray).orEmpty().mapNotNull { allergen ->
                    (allergen as? JsonArray)?.mapNotNull { it.scalarText() }?.joinToString(" ")?.takeIf { it.isNotBlank() }
                }
                val date = normalizedDate(row.text("datum")) ?: return@row
                val id = row.text("veta")?.trim()?.toIntOrNull() ?: return@row
                if (name.isBlank() || name.equals(type, ignoreCase = true) ||
                    (details.isBlank() && allergens.isEmpty()) || restrictions.contains("VP")) return@row
                val foldedType = Normalizer.normalize(type, Normalizer.Form.NFD)
                    .replace(Regex("\\p{M}"), "").lowercase(Locale.ROOT)
                byDay.getOrPut(date) { mutableListOf() }.add(StravaCZMeal(
                    id = id,
                    title = type.ifBlank { name },
                    description = details.ifBlank { name },
                    ordered = (row.text("pocet")?.trim()?.toIntOrNull() ?: 0) > 0,
                    canModify = foldedType.contains("obed") && !restrictions.contains("CO"),
                    price = row.decimal("cena"),
                    allergens = allergens,
                    forbiddenAllergens = row.text("zakazaneAlergeny")?.takeIf { it.isNotBlank() },
                ))
            }
        }
        return normalize(byDay.mapValues { (_, meals) -> meals.distinctBy { it.id }.sortedBy { it.id } })
    }

    internal fun normalizedDate(raw: String?): String? {
        val trimmed = raw?.trim() ?: return null
        return listOf("dd.MM.uuuu", "dd-MM.uuuu", "uuuu-MM-dd").firstNotNullOfOrNull { pattern ->
            runCatching {
                LocalDate.parse(trimmed, DateTimeFormatter.ofPattern(pattern).withResolverStyle(ResolverStyle.STRICT)).toString()
            }.getOrNull()
        }
    }
}

internal fun JsonObject.text(key: String): String? = this[key]?.scalarText()
internal fun JsonElement.scalarText(): String? = (this as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.decimal(key: String): Double? = text(key)?.trim()?.replace(',', '.')?.toDoubleOrNull()?.takeIf { it.isFinite() }

internal fun parseStravaSession(response: JsonObject): StravaCZStoredSession {
    val sessionID = response.text("sid")?.takeIf { it.isNotBlank() }
        ?: throw StravaCZApiException("Strava.cz did not return a session. Check your sign-in details.")
    val serviceURL = response.text("s5url")?.takeIf { it.isNotBlank() }
        ?: throw StravaCZApiException("Strava.cz did not return a canteen service address.")
    val canteenNumber = response.text("cislo")?.takeIf { it.isNotBlank() }
        ?: throw StravaCZApiException("Strava.cz did not return a canteen number.")
    val username = response.text("jmeno")?.takeIf { it.isNotBlank() }
        ?: throw StravaCZApiException("Strava.cz did not return an account name.")
    val user = response["uzivatel"] as? JsonObject ?: JsonObject(emptyMap())
    return StravaCZStoredSession(
        sessionID = sessionID,
        canteenNumber = canteenNumber,
        username = username,
        balance = user.decimal("konto")?.toString(),
        serviceURL = serviceURL,
        currency = user.text("mena")?.takeIf { it.isNotBlank() } ?: "Kč",
        canteenName = user.text("nazevJidelny"),
        fullName = user.text("jmeno"),
    )
}
