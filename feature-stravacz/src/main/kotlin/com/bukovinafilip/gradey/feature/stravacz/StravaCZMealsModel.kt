package com.bukovinafilip.gradey.feature.stravacz

import androidx.compose.runtime.Immutable
import com.bukovinafilip.gradey.model.StravaCZMeal
import com.bukovinafilip.gradey.model.StravaCZMenu
import java.text.Normalizer
import java.text.NumberFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** iOS `StravaCZMealType`. */
internal enum class MealKind { SOUP, MAIN, OTHER }

/** Localised texts the menu rows are built with, so the mapping stays a pure function. */
@Immutable
internal data class MealLabels(val soup: String, val main: String, val other: String, val included: String)

@Immutable
internal data class MealRowUi(
    val meal: StravaCZMeal,
    val key: String,
    val kind: MealKind,
    /** Upper-cased course title for the pill. */
    val typeTitle: String,
    val name: String,
    val price: String,
    val allergens: String?,
    val forbiddenAllergens: String?,
)

@Immutable
internal data class MealDayUi(val id: String, val weekday: String, val shortDate: String?, val meals: List<MealRowUi>)

private val Diacritics = Regex("\\p{M}+")
private val ShortDayMonth: DateTimeFormatter = DateTimeFormatter.ofPattern("d. M.")
private val ShortDayMonthYear: DateTimeFormatter = DateTimeFormatter.ofPattern("d. M. yyyy")

/**
 * Course of a meal from the canteen's type description (iOS `StravaCZMealType.make`): "Polévka" is
 * a soup, "Oběd …" a main course. Meals the canteen lets you order are always main courses.
 */
internal fun mealKind(meal: StravaCZMeal): MealKind {
    val folded = Normalizer.normalize(meal.title, Normalizer.Form.NFD).replace(Diacritics, "").lowercase(Locale.ROOT)
    return when {
        folded.contains("polevka") -> MealKind.SOUP
        folded.contains("obed") || meal.canModify -> MealKind.MAIN
        else -> MealKind.OTHER
    }
}

/** Builds every day section once per menu, locale and currency (kept out of composition). */
internal fun mealDays(menu: StravaCZMenu?, locale: Locale, currency: String, labels: MealLabels, today: LocalDate): List<MealDayUi> {
    val days = menu?.days.orEmpty()
    if (days.isEmpty()) return emptyList()
    val weekday = DateTimeFormatter.ofPattern("EEEE", locale)
    val numbers = moneyFormat(locale)
    return days.filter { it.meals.isNotEmpty() }.map { day ->
        val date = runCatching { LocalDate.parse(day.date) }.getOrNull()
        MealDayUi(
            id = day.id,
            weekday = date?.format(weekday)?.replaceFirstChar { it.titlecase(locale) } ?: day.title,
            shortDate = date?.format(if (date.year == today.year) ShortDayMonth else ShortDayMonthYear),
            meals = day.meals.map { meal ->
                val kind = mealKind(meal)
                MealRowUi(
                    meal = meal,
                    key = "meal-${day.id}-${meal.id}",
                    kind = kind,
                    typeTitle = when (kind) {
                        MealKind.SOUP -> labels.soup
                        MealKind.MAIN -> labels.main
                        MealKind.OTHER -> labels.other
                    }.uppercase(locale),
                    name = meal.description?.takeIf { it.isNotBlank() } ?: meal.title,
                    price = meal.price?.takeIf { it > 0.0 }?.let { "${numbers.format(it)} $currency" } ?: labels.included,
                    allergens = meal.allergens.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotEmpty() },
                    forbiddenAllergens = meal.forbiddenAllergens?.takeIf { it.isNotBlank() },
                )
            },
        )
    }
}

/** "1 234,50 Kč": two decimals, like iOS `StravaCZFormatters.balance`; unparseable values are shown as sent. */
internal fun formatBalance(value: String, currency: String, locale: Locale): String {
    val numeric = value.trim().replace(',', '.').toBigDecimalOrNull()
    return "${numeric?.let { moneyFormat(locale).format(it) } ?: value.trim()} $currency"
}

private fun moneyFormat(locale: Locale): NumberFormat = NumberFormat.getNumberInstance(locale).apply {
    minimumFractionDigits = 2
    maximumFractionDigits = 2
}
