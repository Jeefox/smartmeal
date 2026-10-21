package dev.jeefox.smartmeal.mealplan.dto;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;
import dev.jeefox.smartmeal.shopping.dto.ShoppingEstimateResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record MealPlanPreviewResponse(
        LocalDate planDate,
        int dailyCalorieTarget,
        NutritionValues nutritionTotal,
        BigDecimal calorieDeviation,
        boolean withinTargetTolerance,
        List<Entry> entries,
        List<String> warnings,
        BudgetEstimate budgetEstimate
) {
    public record BudgetEstimate(BigDecimal budget, String status, BigDecimal excess,
                                 ShoppingEstimateResponse shoppingEstimate) {
    }

    public record Entry(MealType mealType, UUID recipeId, String recipeName, BigDecimal portions,
                        int preparationTimeMin, NutritionValues nutrition, LocalDate lastPlannedDate, String recipeFingerprint) {
    }
}
