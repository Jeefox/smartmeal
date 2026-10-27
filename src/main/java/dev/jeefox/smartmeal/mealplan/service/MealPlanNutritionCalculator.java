package dev.jeefox.smartmeal.mealplan.service;

import dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.function.Function;

public final class MealPlanNutritionCalculator {
    private MealPlanNutritionCalculator() {}

    public static NutritionValues calculate(List<MealPlanEntry> entries) {
        // A common denominator avoids rounding recurring fractions before summing the day.
        BigInteger denominator = BigInteger.ONE;
        for (MealPlanEntry entry : entries) {
            BigInteger servings = BigInteger.valueOf(entry.getRecipeSnapshot().servings());
            denominator = denominator.divide(denominator.gcd(servings)).multiply(servings);
        }
        return new NutritionValues(
                sum(entries, denominator, NutritionValues::calories),
                sum(entries, denominator, NutritionValues::protein),
                sum(entries, denominator, NutritionValues::fat),
                sum(entries, denominator, NutritionValues::carbs),
                sum(entries, denominator, NutritionValues::knownFiber),
                entries.stream()
                        .allMatch(e -> e.getRecipeSnapshot().nutritionTotal().fiberComplete()));
    }

    private static BigDecimal sum(
            List<MealPlanEntry> entries,
            BigInteger denominator,
            Function<NutritionValues, BigDecimal> nutrient) {
        BigDecimal numerator = BigDecimal.ZERO;
        for (MealPlanEntry entry : entries) {
            BigInteger servings = BigInteger.valueOf(entry.getRecipeSnapshot().servings());
            BigDecimal multiplier = new BigDecimal(denominator.divide(servings));
            numerator =
                    numerator.add(
                            nutrient.apply(entry.getRecipeSnapshot().nutritionTotal())
                                    .multiply(entry.getPortions())
                                    .multiply(multiplier));
        }
        return numerator.divide(new BigDecimal(denominator), 2, RoundingMode.HALF_UP);
    }
}
