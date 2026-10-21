package dev.jeefox.smartmeal.mealplan.generation;

import dev.jeefox.smartmeal.exception.MenuGenerationUnavailableException;
import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DayMenuSelectorTest {
    private final DayMenuSelector selector = new DayMenuSelector();

    @Test
    void selectsExactTargetWithNormalPortionsAndStableResults() {
        var input = Map.of(MealType.BREAKFAST, List.of(candidate("500", "0")),
                MealType.LUNCH, List.of(candidate("800", "0")), MealType.DINNER, List.of(candidate("700", "0")));
        var result = selector.select(2000, input);
        assertTrue(result.stream().allMatch(c -> c.portions().compareTo(BigDecimal.ONE) == 0));
        assertEquals(new BigDecimal("2000.00"), selector.nutrition(result).calories());
        assertEquals(result, selector.select(2000, input));
    }

    @Test
    void prefersUnusedRecipeWithOtherwiseEqualNutrition() {
        var old = candidate("500", "0.2");
        var fresh = candidate("500", "0");
        var result = selector.select(2000, Map.of(MealType.BREAKFAST, List.of(old, fresh),
                MealType.LUNCH, List.of(candidate("800", "0")), MealType.DINNER, List.of(candidate("700", "0"))));
        assertEquals(fresh.recipeId(), result.getFirst().recipe().recipeId());
    }

    @Test
    void neverRepeatsRecipeAcrossMealsAndFailsWhenDistinctRecipesUnavailable() {
        var a = candidate("500", "0");
        var b = candidate("700", "0");
        var c = candidate("800", "0");
        var result = selector.select(2000, Map.of(MealType.BREAKFAST, List.of(a, b, c),
                MealType.LUNCH, List.of(a, b, c), MealType.DINNER, List.of(a, b, c)));
        assertEquals(3, result.stream().map(x -> x.recipe().recipeId()).distinct().count());
        assertThrows(MenuGenerationUnavailableException.class, () -> selector.select(2000,
                Map.of(MealType.BREAKFAST, List.of(a), MealType.LUNCH, List.of(a), MealType.DINNER, List.of(a))));
    }

    @Test
    void portionsStayOnQuarterGridAndWithinBoundsEvenForImpossibleTarget() {
        var result = selector.select(10000, Map.of(MealType.BREAKFAST, List.of(candidate("100", "0")),
                MealType.LUNCH, List.of(candidate("100", "0")), MealType.DINNER, List.of(candidate("100", "0"))));
        for (var choice : result) {
            assertTrue(choice.portions().compareTo(new BigDecimal("0.5")) >= 0);
            assertTrue(choice.portions().compareTo(new BigDecimal("2")) <= 0);
            assertEquals(0, choice.portions().remainder(new BigDecimal("0.25")).signum());
        }
    }

    @Test
    void sumsRecurringFractionsBeforeRounding() {
        var c = new DayMenuSelector.Candidate(UUID.randomUUID(), "Тест", 10, 3,
                new NutritionValues(new BigDecimal("1.005"), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO),
                BigDecimal.ZERO, null);
        var choice = new DayMenuSelector.Choice(MealType.BREAKFAST, c, BigDecimal.ONE);
        assertEquals(new BigDecimal("1.01"), selector.nutrition(List.of(choice, choice, choice)).calories());
    }

    @Test
    void budgetCanSelectAffordableAlternativeWithoutSacrificingCalories() {
        var expensive = candidate("500", "0");
        var cheap = candidate("500", "0.01");
        var lunch = candidate("800", "0");
        var dinner = candidate("700", "0");
        var input = Map.of(MealType.BREAKFAST, List.of(expensive, cheap),
                MealType.LUNCH, List.of(lunch), MealType.DINNER, List.of(dinner));
        var result = selector.select(2000, input, new BigDecimal("100"), choices ->
                new DayMenuSelector.PurchaseCost(true, choices.stream().anyMatch(c -> c.recipe().equals(expensive))
                        ? new BigDecimal("200") : new BigDecimal("90")));
        assertEquals(cheap.recipeId(), result.getFirst().recipe().recipeId());
        assertEquals(new BigDecimal("2000.00"), selector.nutrition(result).calories());
    }

    @Test
    void missingPricesAreNotTreatedAsFreeAlternative() {
        var known = candidate("500", "0.01");
        var unknown = candidate("500", "0");
        var result = selector.select(2000, Map.of(MealType.BREAKFAST, List.of(known, unknown),
                MealType.LUNCH, List.of(candidate("800", "0")), MealType.DINNER, List.of(candidate("700", "0"))),
                new BigDecimal("100"), choices -> choices.stream().anyMatch(c -> c.recipe().equals(unknown))
                        ? new DayMenuSelector.PurchaseCost(false, BigDecimal.ZERO)
                        : new DayMenuSelector.PurchaseCost(true, new BigDecimal("90")));
        assertEquals(known.recipeId(), result.getFirst().recipe().recipeId());
    }

    private DayMenuSelector.Candidate candidate(String calories, String penalty) {
        return new DayMenuSelector.Candidate(UUID.randomUUID(), "Рецепт", 10, 1,
                new NutritionValues(new BigDecimal(calories), BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE),
                new BigDecimal(penalty), penalty.equals("0") ? null : LocalDate.of(2033, 1, 1));
    }
}
