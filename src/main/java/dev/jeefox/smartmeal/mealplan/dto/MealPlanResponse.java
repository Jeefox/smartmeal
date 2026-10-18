package dev.jeefox.smartmeal.mealplan.dto;

import dev.jeefox.smartmeal.mealplan.entity.MealPlan;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

public record MealPlanResponse(
        UUID id,
        LocalDate planDate,
        MealPlanStatus status,
        Integer dailyCalorieTargetSnapshot,
        Instant confirmedAt,
        Instant createdAt,
        Instant updatedAt,
        List<MealPlanEntryResponse> entries,
        NutritionValues nutritionTotal,
        Integer dailyCalorieTarget
) {
    public MealPlanResponse {
        entries = List.copyOf(entries);
    }

    public static MealPlanResponse from(MealPlan plan, List<MealPlanEntryResponse> entries,
                                        NutritionValues nutritionTotal, Integer dailyCalorieTarget) {
        return new MealPlanResponse(plan.getId(), plan.getPlanDate(), plan.getStatus(),
                plan.getDailyCalorieTargetSnapshot(), plan.getConfirmedAt(), plan.getCreatedAt(),
                plan.getUpdatedAt(), entries, nutritionTotal, dailyCalorieTarget);
    }
}
