package dev.jeefox.smartmeal.mealplan.dto;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanEntry;
import dev.jeefox.smartmeal.mealplan.service.MealPlanNutritionCalculator;
import java.util.List;
import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import java.math.BigDecimal;
import java.util.UUID;

public record MealPlanEntryResponse(
        UUID id,
        UUID mealPlanId,
        MealType mealType,
        BigDecimal portions,
        Integer position,
        RecipeSnapshot recipeSnapshot,
        NutritionValues nutrition
) {
    public static MealPlanEntryResponse from(MealPlanEntry entry) {
        return new MealPlanEntryResponse(entry.getId(), entry.getMealPlan().getId(), entry.getMealType(),
                entry.getPortions(), entry.getPosition(), entry.getRecipeSnapshot(),
                MealPlanNutritionCalculator.calculate(List.of(entry)));
    }
}
