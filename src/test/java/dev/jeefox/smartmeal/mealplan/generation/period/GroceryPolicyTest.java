package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.snapshot.*;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.pantry.PantryModels.*;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

class GroceryPolicyTest {
    final LocalDate date = LocalDate.of(2040, 1, 1);

    Options options() {
        return new Options(
                date,
                date.plusDays(6),
                null,
                List.of(1L),
                "Тула",
                null,
                null,
                14,
                0,
                true,
                null,
                0L);
    }

    Selection entry(int day, long... products) {
        var n =
                new NutritionValues(
                        BigDecimal.valueOf(500), BigDecimal.TEN, BigDecimal.TEN, BigDecimal.TEN);
        var ingredients =
                Arrays.stream(products)
                        .mapToObj(
                                id ->
                                        new RecipeIngredientSnapshot(
                                                id, "Продукт " + id, BigDecimal.valueOf(100)))
                        .toList();
        var r =
                new RecipeSnapshot(
                        UUID.randomUUID(), "Блюдо", null, "Готовить", 10, 1, ingredients, n, n);
        return new Selection(
                "day" + day,
                date.plusDays(day),
                MealType.LUNCH,
                r,
                RecipeSnapshotFingerprint.calculate(r),
                BigDecimal.ONE,
                false,
                null,
                List.of());
    }

    PlanningContext context(Snapshot stock) {
        return new PlanningContext(
                UUID.randomUUID(),
                2000,
                "test",
                Instant.now(),
                Map.of(),
                List.of(),
                List.of(),
                stock);
    }

    @Test
    void repeatedIngredientsCountOnceIncludingUnpricedProducts() {
        var entries = List.of(entry(0, 1, 2), entry(2, 1, 3), entry(4, 1, 2));
        var o = options();
        var basket = new PurchaseBasketCalculator().calculate(entries, List.of(), o);
        assertEquals(3, GroceryPolicy.count(basket));
        assertEquals(3, GroceryPolicy.count(context(Snapshot.empty()), o, entries));
        assertEquals(3, basket.unknownProducts());
        assertEquals(
                0,
                BigDecimal.valueOf(300)
                        .compareTo(
                                basket.items().stream()
                                        .filter(i -> i.productId() == 1L)
                                        .findFirst()
                                        .orElseThrow()
                                        .requiredG()));
    }

    @Test
    void onlyFullyCoveredProductsAreExcludedAndExpiryIsRespected() {
        var stock =
                new Snapshot(
                        1,
                        date,
                        List.of(
                                new StockLot(
                                        UUID.randomUUID(),
                                        1L,
                                        BigDecimal.valueOf(1000),
                                        date,
                                        null,
                                        Instant.now()),
                                new StockLot(
                                        UUID.randomUUID(),
                                        2L,
                                        BigDecimal.valueOf(50),
                                        date,
                                        null,
                                        Instant.now()),
                                new StockLot(
                                        UUID.randomUUID(),
                                        3L,
                                        BigDecimal.valueOf(1000),
                                        date,
                                        date,
                                        Instant.now())));
        var entries = List.of(entry(2, 1, 2, 3));
        var o = options();
        var b = new PurchaseBasketCalculator().calculate(entries, List.of(), o, stock);
        assertEquals(2, GroceryPolicy.count(b));
        assertEquals(GroceryPolicy.count(b), GroceryPolicy.count(context(stock), o, entries));
    }

    @Test
    void capAcceptsSameIngredientInManyDishesButRejectsANewProduct() {
        var old = options();
        var capped =
                new Options(
                        old.from(),
                        old.to(),
                        null,
                        List.of(1L),
                        "Тула",
                        null,
                        null,
                        14,
                        0,
                        true,
                        null,
                        0L,
                        true,
                        List.of(),
                        false,
                        1);
        var c = context(Snapshot.empty());
        assertTrue(GroceryPolicy.fits(c, capped, List.of(entry(0, 1), entry(2, 1), entry(4, 1))));
        assertFalse(GroceryPolicy.fits(c, capped, List.of(entry(0, 1), entry(2, 2))));
    }
}
