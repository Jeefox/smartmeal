package dev.jeefox.smartmeal.mealplan.balanced;

import static dev.jeefox.smartmeal.mealplan.balanced.BalancedModels.*;

import static org.junit.jupiter.api.Assertions.*;

import dev.jeefox.smartmeal.mealplan.snapshot.*;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import org.junit.jupiter.api.Test;

import java.math.*;
import java.util.*;

class CompositeMealsTest {
    BigDecimal n(String v) {
        return new BigDecimal(v);
    }

    RecipeSnapshot source(BigDecimal fiber, boolean complete) {
        var nutrition = new NutritionValues(n("400"), n("30"), n("10"), n("45"), fiber, complete);
        return new RecipeSnapshot(
                UUID.randomUUID(),
                "Тест компонента",
                null,
                "Приготовить",
                10,
                2,
                List.of(new RecipeIngredientSnapshot(1L, "Продукт", n("200"))),
                nutrition,
                nutrition.scaled(n(".5")));
    }

    Component component(RecipeSnapshot s, UUID a, UUID b) {
        return new Component(
                "main",
                Role.PROTEIN_MAIN,
                s,
                new Profile(
                        Role.PROTEIN_MAIN,
                        n("300"),
                        YieldQuality.MEASURED,
                        "Тест измерения",
                        n("100"),
                        n("180"),
                        n("25"),
                        false,
                        false),
                List.of(new Amount(a, n(".5"), n("150")), new Amount(b, n(".4"), n("120"))),
                false);
    }

    @Test
    void aggregateKeepsEdibleRawDemandSeparateFromCookedWeight() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var source = source(n("8"), true);
        var result = CompositeMeals.aggregate(List.of(component(source, a, b)));
        assertEquals(1, result.servings());
        assertEquals(0, result.ingredients().getFirst().quantityG().compareTo(n("180")));
        assertEquals(0, result.nutritionTotal().calories().compareTo(n("360")));
        assertEquals(0, CompositeMeals.forPerson(result, a).calories().compareTo(n("200")));
        assertEquals(0, CompositeMeals.forPerson(result, b).calories().compareTo(n("160")));
    }

    @Test
    void unknownFiberKeepsKnownLowerBound() {
        var a = new NutritionValues(n("100"), n("1"), n("1"), n("1"), n("3"), true);
        var b = new NutritionValues(n("100"), n("1"), n("1"), n("1"));
        var sum = NutritionValues.sum(List.of(a, b));
        assertFalse(sum.fiberComplete());
        assertEquals(n("3"), sum.knownFiber());
        assertEquals(n("6"), sum.scaled(n("2")).knownFiber());
    }

    @Test
    void additionalWaterWeightDoesNotChangeNutrition() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var source = source(n("8"), true);
        var c = component(source, a, b);
        var largerYield =
                new Profile(
                        c.role(),
                        n("600"),
                        YieldQuality.ESTIMATED,
                        "Больше воды",
                        n("200"),
                        n("360"),
                        n("25"),
                        false,
                        false);
        var doubled =
                new Component(
                        c.id(),
                        c.role(),
                        source,
                        largerYield,
                        List.of(new Amount(a, n(".5"), n("300")), new Amount(b, n(".4"), n("240"))),
                        false);
        assertEquals(
                CompositeMeals.aggregate(List.of(c)).nutritionTotal(),
                CompositeMeals.aggregate(List.of(doubled)).nutritionTotal());
    }

    @Test
    void fingerprintDetectsCompositionAndIndividualAmountChanges() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        var c = component(source(n("8"), true), a, b);
        var locked = new Component(c.id(), c.role(), c.recipe(), c.profile(), c.amounts(), true);
        assertNotEquals(
                RecipeSnapshotFingerprint.calculate(CompositeMeals.aggregate(List.of(c))),
                RecipeSnapshotFingerprint.calculate(CompositeMeals.aggregate(List.of(locked))));
    }
}
