package com.bukovinafilip.gradey.feature.stravacz

import com.bukovinafilip.gradey.model.StravaCZMeal
import com.bukovinafilip.gradey.model.StravaCZMenu
import com.bukovinafilip.gradey.model.StravaCZMenuDay
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StravaCZMealsModelTest {
    private val czech = Locale.forLanguageTag("cs-CZ")
    private val labels = MealLabels(soup = "Polévka", main = "Oběd", other = "Jídlo", included = "V ceně")

    @Test fun courseFollowsTheCanteenTypeLikeIOS() {
        assertEquals(MealKind.SOUP, mealKind(StravaCZMeal(1, "Polévka", canModify = false)))
        assertEquals(MealKind.MAIN, mealKind(StravaCZMeal(2, "Oběd 1", canModify = false)))
        assertEquals(MealKind.OTHER, mealKind(StravaCZMeal(3, "Svačina", canModify = false)))
        // Only main courses can be ordered, so an orderable meal is a main course whatever its label.
        assertEquals(MealKind.MAIN, mealKind(StravaCZMeal(4, "Meal 1", canModify = true)))
    }

    @Test fun daysCarryWeekdayShortDateAndFormattedRows() {
        val menu = StravaCZMenu(
            listOf(
                StravaCZMenuDay(
                    "2026-07-06", "Monday, 6 July 2026", "2026-07-06",
                    listOf(
                        StravaCZMeal(10, "Polévka", "Kulajda", canModify = false, price = 0.0),
                        StravaCZMeal(11, "Oběd 1", "Svíčková", ordered = true, price = 45.0, allergens = listOf("1 Lepek", "7 Mléko")),
                    ),
                ),
                StravaCZMenuDay("2026-07-07", "Tuesday", "2026-07-07", emptyList()),
            ),
        )
        val days = mealDays(menu, czech, "Kč", labels, LocalDate.of(2026, 7, 1))
        assertEquals(1, days.size)
        val day = days.single()
        assertEquals("Pondělí", day.weekday)
        assertEquals("6. 7.", day.shortDate)
        val (soup, main) = day.meals
        assertEquals(MealKind.SOUP, soup.kind)
        assertEquals("POLÉVKA", soup.typeTitle)
        assertEquals("Kulajda", soup.name)
        assertEquals("V ceně", soup.price)
        assertNull(soup.allergens)
        assertEquals("OBĚD", main.typeTitle)
        assertEquals("45,00 Kč", main.price)
        assertEquals("1 Lepek, 7 Mléko", main.allergens)
        assertTrue(day.meals.map { it.key }.toSet().size == 2)
    }

    @Test fun shortDateShowsTheYearOutsideTheCurrentOne() {
        val menu = StravaCZMenu(listOf(StravaCZMenuDay("2026-07-06", "Monday", "2026-07-06", listOf(StravaCZMeal(1, "Oběd")))))
        assertEquals("6. 7. 2026", mealDays(menu, Locale.ENGLISH, "Kč", labels, LocalDate.of(2027, 1, 4)).single().shortDate)
        assertEquals("Monday", mealDays(menu, Locale.ENGLISH, "Kč", labels, LocalDate.of(2026, 7, 6)).single().weekday)
    }

    @Test fun unparseableDayFallsBackToTheCanteenTitle() {
        val menu = StravaCZMenu(listOf(StravaCZMenuDay("x", "Pondělí 6. 7.", "not-a-date", listOf(StravaCZMeal(1, "Oběd")))))
        val day = mealDays(menu, czech, "Kč", labels, LocalDate.of(2026, 7, 6)).single()
        assertEquals("Pondělí 6. 7.", day.weekday)
        assertNull(day.shortDate)
    }

    @Test fun balanceUsesTwoDecimals() {
        assertEquals("123,50 Kč", formatBalance("123.5", "Kč", czech))
        assertEquals("12.00 EUR", formatBalance("12", "EUR", Locale.ENGLISH))
        assertEquals("n/a Kč", formatBalance(" n/a ", "Kč", czech))
    }
}
