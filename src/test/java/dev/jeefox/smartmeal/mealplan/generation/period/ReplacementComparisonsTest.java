package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class ReplacementComparisonsTest {
    private final LocalDate date = LocalDate.of(2035, 1, 1);
    private final NutritionValues nutrition =
            new NutritionValues(BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE);
    private final Options options =
            new Options(
                    date,
                    date,
                    new BigDecimal("500"),
                    List.of(1L),
                    "Тула",
                    null,
                    null,
                    null,
                    null,
                    true,
                    null,
                    0L);

    private Selection meal(int id, int minutes) {
        var recipe =
                new RecipeSnapshot(
                        new UUID(0, id),
                        "Блюдо " + id,
                        null,
                        "Приготовить",
                        minutes,
                        1,
                        List.of(),
                        nutrition,
                        nutrition);
        return new Selection(
                "slot",
                date,
                MealType.DINNER,
                recipe,
                "key-" + id,
                BigDecimal.ONE,
                false,
                null,
                List.of());
    }

    private BasketItem product(long id, boolean needsBuying) {
        return new BasketItem(
                id,
                "Продукт " + id,
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.ZERO,
                1,
                BigDecimal.TEN,
                BigDecimal.TEN,
                BigDecimal.TEN,
                1L,
                "Сеть",
                null,
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                "CHAIN",
                needsBuying ? BigDecimal.ZERO : BigDecimal.TEN,
                needsBuying ? BigDecimal.TEN : BigDecimal.ZERO);
    }

    private Basket basket(BigDecimal total, BasketItem... items) {
        return new Basket(
                List.of(items),
                BigDecimal.TEN,
                total,
                null,
                items.length,
                0,
                total == null ? 1 : 0,
                PriceQuality.COMPLETE_OBSERVED,
                BudgetStatus.WITHIN,
                List.of(),
                null);
    }

    private Document document(Selection e, Basket b) {
        return new Document(
                options,
                2000,
                "context",
                Instant.EPOCH,
                List.of(e),
                List.of(new Day(date, nutrition, BigDecimal.ZERO, true, List.of(e))),
                b,
                List.of(),
                "FOUND",
                "FOUND",
                "test");
    }

    private Alternative compare(
            Selection oldMeal, Selection next, Basket before, Basket after, long revision) {
        var oldDoc = document(oldMeal, before);
        var view = new View(new UUID(0, 99), 3, Instant.MAX, oldDoc, List.of());
        var context =
                new PlanningContext(
                        new UUID(0, 1),
                        2000,
                        "context",
                        Instant.EPOCH,
                        Map.of(),
                        List.of(),
                        List.of(),
                        new dev.jeefox.smartmeal.pantry.PantryModels.Snapshot(
                                revision, date, List.of()));
        return ReplacementComparisons.compare(
                view, context, oldMeal, next, oldDoc, document(next, after));
    }

    @Test
    void anUnpricedBasketNeverClaimsSavings() {
        var a =
                compare(
                        meal(1, 20),
                        meal(2, 10),
                        basket(null, product(1, true)),
                        basket(BigDecimal.TEN, product(2, true)),
                        0);
        assertNull(a.deltaPurchaseCost());
        assertFalse(a.goals().contains(ReplacementGoal.CHEAPER));
        assertTrue(a.goals().contains(ReplacementGoal.FASTER));
        assertFalse(a.goals().contains(ReplacementGoal.SAME_PRODUCTS));
    }

    @Test
    void availableStockIsNotANewShoppingPositionAndQuoteTracksItsRevision() {
        var before = basket(new BigDecimal("100"), product(1, true));
        var after = basket(new BigDecimal("100"), product(1, true), product(2, false));
        var a = compare(meal(1, 20), meal(2, 20), before, after, 3);
        assertTrue(a.impact().addedProducts().isEmpty());
        assertEquals(1, a.impact().purchaseProductsAfter());
        assertTrue(a.goals().contains(ReplacementGoal.SAME_PRODUCTS));
        assertNotEquals(
                a.comparisonKey(),
                compare(meal(1, 20), meal(2, 20), before, after, 4).comparisonKey());
    }

    @Test
    void wholePackageCostAndRemovedProductsAreComparedRatherThanIngredientUnitPrices() {
        var a =
                compare(
                        meal(1, 20),
                        meal(2, 10),
                        basket(new BigDecimal("100"), product(1, true)),
                        basket(new BigDecimal("130"), product(2, true)),
                        0);
        assertEquals(new BigDecimal("30"), a.deltaPurchaseCost());
        assertFalse(a.goals().contains(ReplacementGoal.CHEAPER));
        assertEquals(1L, a.impact().removedProducts().getFirst().productId());
        assertEquals(2L, a.impact().addedProducts().getFirst().productId());
    }

    @Test
    void rankingIsDeterministicAndDifferentPortionsOfOneRecipeDoNotFillTheShortlist() {
        var before = basket(new BigDecimal("100"), product(1, true));
        var a =
                compare(
                        meal(1, 30),
                        meal(2, 15),
                        before,
                        basket(new BigDecimal("80"), product(1, true)),
                        0);
        var b =
                compare(
                        meal(1, 30),
                        meal(3, 5),
                        before,
                        basket(new BigDecimal("90"), product(1, true)),
                        0);
        assertEquals(
                a,
                List.of(a, b).stream()
                        .sorted(ReplacementComparisons.order(ReplacementGoal.CHEAPER))
                        .findFirst()
                        .orElseThrow());
        assertEquals(
                b,
                List.of(a, b).stream()
                        .sorted(ReplacementComparisons.order(ReplacementGoal.FASTER))
                        .findFirst()
                        .orElseThrow());
        assertEquals(2, ReplacementComparisons.shortlist(List.of(a, a, b)).size());
    }

    @Test
    void unchangedNumericValuesHaveTheSameQuoteAfterJsonScaleChanges() {
        var oldMeal = meal(1, 20);
        var next = meal(2, 10);
        var rescaled =
                new Selection(
                        next.id(),
                        next.date(),
                        next.mealType(),
                        next.recipe(),
                        next.recipeFingerprint(),
                        new BigDecimal("1.00"),
                        next.locked(),
                        next.coverImageUrl(),
                        next.explanations());
        var a =
                compare(
                        oldMeal,
                        next,
                        basket(new BigDecimal("100"), product(1, true)),
                        basket(new BigDecimal("80"), product(1, true)),
                        0);
        var b =
                compare(
                        oldMeal,
                        rescaled,
                        basket(new BigDecimal("100.00"), product(1, true)),
                        basket(new BigDecimal("80.00"), product(1, true)),
                        0);
        assertEquals(a.comparisonKey(), b.comparisonKey());
    }
}
