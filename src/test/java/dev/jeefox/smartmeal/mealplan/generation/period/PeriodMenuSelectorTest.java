package dev.jeefox.smartmeal.mealplan.generation.period;

import static dev.jeefox.smartmeal.mealplan.generation.period.PlanningModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.snapshot.*;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

class PeriodMenuSelectorTest {
    final PurchaseBasketCalculator baskets = new PurchaseBasketCalculator();
    final PeriodMenuEvaluator evaluator = new PeriodMenuEvaluator(baskets);
    final PeriodMenuSelector selector = new PeriodMenuSelector(baskets, evaluator, 24, 100000);
    final LocalDate from = LocalDate.of(2035, 1, 1);

    Options options(int days) {
        return new Options(
                from,
                from.plusDays(days - 1),
                new BigDecimal("2000"),
                List.of(1L),
                "Тула",
                null,
                null,
                null,
                null,
                true,
                null,
                0L);
    }

    PlanningContext context(int count) {
        Map<MealType, List<Candidate>> candidates = new EnumMap<>(MealType.class);
        for (MealType meal : PeriodMenuEvaluator.MEALS) {
            List<Candidate> list = new ArrayList<>();
            int calories = meal == MealType.BREAKFAST ? 500 : meal == MealType.LUNCH ? 800 : 700;
            for (int i = 0; i < count; i++) {
                var n =
                        new NutritionValues(
                                BigDecimal.valueOf(calories),
                                BigDecimal.TEN,
                                BigDecimal.TEN,
                                BigDecimal.TEN);
                UUID id =
                        UUID.nameUUIDFromBytes(
                                (meal + ":" + i).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                var snapshot =
                        new RecipeSnapshot(
                                id,
                                meal + " " + i,
                                null,
                                "Готовить",
                                10,
                                1,
                                List.of(
                                        new RecipeIngredientSnapshot(
                                                1L, "Крупа", BigDecimal.valueOf(calories))),
                                n,
                                n);
                list.add(
                        new Candidate(
                                snapshot,
                                Set.of(meal),
                                List.of(BigDecimal.ONE, new BigDecimal("2")),
                                null,
                                null,
                                null));
            }
            candidates.put(meal, list);
        }
        return new PlanningContext(
                UUID.randomUUID(),
                2000,
                "fingerprint",
                Instant.now(),
                candidates,
                List.of(
                        PurchaseBasketCalculatorTest.offer(
                                1, 1, "1000", "100", false, "STORE_OBSERVATION")),
                List.of());
    }

    @Test
    void generatesWholeWeekWithSharedPackagesAndNoExcessiveRepeats() {
        var context = context(4);
        var options = options(7);
        var result = selector.select(context, options, List.of());
        assertEquals(21, result.entries().size());
        assertTrue(result.days().stream().allMatch(Day::withinTargetTolerance));
        assertEquals(new BigDecimal("1400.00"), result.shopping().knownPurchaseCost());
        assertTrue(
                result.entries().stream()
                        .collect(
                                Collectors.groupingBy(
                                        e -> e.recipe().recipeId(), Collectors.counting()))
                        .values()
                        .stream()
                        .allMatch(n -> n <= 2));
        assertEquals(result.entries(), selector.select(context, options, List.of()).entries());
    }

    @Test
    void fixedMealsSurviveRegeneration() {
        var context = context(4);
        var options = options(3);
        var first = selector.select(context, options, List.of());
        var entry = first.entries().getFirst();
        var fixed =
                evaluator.selection(
                        context.candidate(entry.recipe().recipeId()),
                        entry.date(),
                        entry.mealType(),
                        entry.portions(),
                        true);
        var next = selector.select(context, options.withSeed(3), List.of(fixed));
        assertTrue(
                next.entries().stream()
                        .anyMatch(
                                e ->
                                        e.id().equals(fixed.id())
                                                && e.recipe()
                                                        .recipeId()
                                                        .equals(fixed.recipe().recipeId())
                                                && e.locked()));
    }

    @Test
    void lackOfRecipesProducesDiagnosticsRatherThanBreakingRepeatLimits() {
        var result = selector.select(context(1), options(7), List.of());
        assertEquals("NO_FEASIBLE_FOUND", result.status());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("NO_FEASIBLE_FOUND")));
    }

    @Test
    void finalValidatorRejectsIneligibleSlotAndUnpracticalPortion() {
        var context = context(4);
        var candidate = context.candidates().get(MealType.LUNCH).getFirst();
        assertThrows(
                PlanningException.class,
                () ->
                        evaluator.evaluate(
                                context,
                                options(1),
                                List.of(
                                        evaluator.selection(
                                                candidate,
                                                from,
                                                MealType.BREAKFAST,
                                                BigDecimal.ONE,
                                                false)),
                                "FOUND"));
        assertThrows(
                PlanningException.class,
                () ->
                        evaluator.evaluate(
                                context,
                                options(1),
                                List.of(
                                        evaluator.selection(
                                                candidate,
                                                from,
                                                MealType.LUNCH,
                                                new BigDecimal(".25"),
                                                false)),
                                "FOUND"));
    }

    @Test
    void boundedSearchReportsItsLimit() {
        var limited =
                new PeriodMenuSelector(baskets, evaluator, 4, 1)
                        .select(context(4), options(7), List.of());
        assertEquals("LIMIT_REACHED", limited.searchStatus());
        assertNotEquals("READY", limited.status());
    }

    @Test
    void priceSearchLimitDoesNotLeaveTheRemainingThreeDaysEmpty() {
        var context = context(4);
        var options = options(5);
        var result =
                new PeriodMenuSelector(baskets, evaluator, 4, 1)
                        .select(context, options, List.of());
        assertEquals("LIMIT_REACHED", result.searchStatus());
        assertEquals(15, result.entries().size());
        assertTrue(result.days().stream().allMatch(day -> day.entries().size() == 3));
        assertNotEquals("NO_FEASIBLE_FOUND", result.status());
        assertTrue(
                result.issues().stream()
                        .noneMatch(issue -> issue.code().equals("NO_FEASIBLE_FOUND")));
        assertEquals(
                result.entries(),
                evaluator.evaluate(context, options, result.entries(), "LIMIT_REACHED").entries());
        assertEquals(options, result.options());
    }

    @Test
    void completeFallbackReservesFuturePinsAndHandlesSeveralPinsOnTheSameDay() {
        var context = context(4);
        var options = options(5);
        var locks = new ArrayList<Selection>();
        for (var meal : PeriodMenuEvaluator.MEALS) {
            var candidate = context.candidates().get(meal).getFirst();
            locks.add(evaluator.selection(candidate, from.plusDays(4), meal, BigDecimal.ONE, true));
        }
        var result =
                new PeriodMenuSelector(baskets, evaluator, 4, 1).select(context, options, locks);
        assertEquals(15, result.entries().size());
        assertTrue(result.entries().containsAll(locks));
        evaluator.evaluate(context, options, result.entries(), "LIMIT_REACHED");
    }

    @Test
    void completeFallbackDoesNotBreakRepeatLimitsToPretendSuccess() {
        var result =
                new PeriodMenuSelector(baskets, evaluator, 4, 1)
                        .select(context(1), options(5), List.of());
        assertEquals("NO_FEASIBLE_FOUND", result.status());
        assertTrue(result.entries().size() < 15);
    }

    @Test
    void tinyCatalogueMatchesExhaustiveFeasiblePurchaseMinimum() {
        var context = context(2);
        var options = options(1);
        BigDecimal exhaustive = null;
        for (var breakfast : context.candidates().get(MealType.BREAKFAST))
            for (var lunch : context.candidates().get(MealType.LUNCH))
                for (var dinner : context.candidates().get(MealType.DINNER))
                    for (var b : breakfast.portions())
                        for (var l : lunch.portions())
                            for (var d : dinner.portions()) {
                                var selected =
                                        List.of(
                                                evaluator.selection(
                                                        breakfast,
                                                        from,
                                                        MealType.BREAKFAST,
                                                        b,
                                                        false),
                                                evaluator.selection(
                                                        lunch, from, MealType.LUNCH, l, false),
                                                evaluator.selection(
                                                        dinner, from, MealType.DINNER, d, false));
                                var calories = evaluator.nutrition(selected).calories();
                                if (calories.compareTo(new BigDecimal("1800")) < 0
                                        || calories.compareTo(new BigDecimal("2200")) > 0) continue;
                                var cost =
                                        baskets.calculate(selected, context.offers(), options)
                                                .knownPurchaseCost();
                                if (exhaustive == null || cost.compareTo(exhaustive) < 0)
                                    exhaustive = cost;
                            }
        assertNotNull(exhaustive);
        assertEquals(
                exhaustive,
                selector.select(context, options, List.of()).shopping().knownPurchaseCost());
    }

    @Test
    void knownExpiryIsASoftTieBreakAndUnusedUnknownExpiryDoesNotWarn() {
        var original = context(1);
        var breakfast = original.candidates().get(MealType.BREAKFAST).getFirst();
        var r = breakfast.snapshot();
        var alternativeSnapshot =
                new RecipeSnapshot(
                        UUID.randomUUID(),
                        "Другой завтрак",
                        null,
                        r.instructions(),
                        r.preparationTimeMin(),
                        r.servings(),
                        List.of(
                                new RecipeIngredientSnapshot(
                                        2L, "Другая крупа", new BigDecimal("500"))),
                        r.nutritionTotal(),
                        r.nutritionPerServing());
        var alternatives = new EnumMap<MealType, List<Candidate>>(MealType.class);
        alternatives.putAll(original.candidates());
        alternatives.put(
                MealType.BREAKFAST,
                List.of(
                        breakfast,
                        new Candidate(
                                alternativeSnapshot,
                                Set.of(MealType.BREAKFAST),
                                List.of(BigDecimal.ONE),
                                null,
                                null,
                                null)));
        var stock =
                new dev.jeefox.smartmeal.pantry.PantryModels.Snapshot(
                        1,
                        from,
                        List.of(
                                new dev.jeefox.smartmeal.pantry.PantryModels.StockLot(
                                        UUID.randomUUID(),
                                        1L,
                                        new BigDecimal("2500"),
                                        from,
                                        from,
                                        Instant.EPOCH),
                                new dev.jeefox.smartmeal.pantry.PantryModels.StockLot(
                                        UUID.randomUUID(),
                                        2L,
                                        new BigDecimal("500"),
                                        from,
                                        null,
                                        Instant.EPOCH)));
        var context =
                new PlanningContext(
                        original.ownerId(),
                        original.target(),
                        original.fingerprint(),
                        original.asOf(),
                        alternatives,
                        original.offers(),
                        List.of(),
                        stock);
        var result = selector.select(context, options(1), List.of());
        assertEquals(
                breakfast.snapshot().recipeId(), result.entries().getFirst().recipe().recipeId());
        assertTrue(
                result.issues().stream()
                        .noneMatch(issue -> issue.code().equals("PANTRY_UNKNOWN_EXPIRY")));
        assertTrue(result.days().getFirst().withinTargetTolerance());
    }
}
