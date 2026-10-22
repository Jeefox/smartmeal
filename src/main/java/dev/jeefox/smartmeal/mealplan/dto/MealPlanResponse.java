package dev.jeefox.smartmeal.mealplan.dto;

import dev.jeefox.smartmeal.mealplan.entity.MealPlan;
import dev.jeefox.smartmeal.mealplan.entity.MealPlanStatus;
import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

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
        Integer dailyCalorieTarget,
        List<dev.jeefox.smartmeal.household.HouseholdModels.Participant> participants,
        List<dev.jeefox.smartmeal.household.HouseholdModels.PersonNutrition> people) {
    public MealPlanResponse(
            UUID id,
            LocalDate planDate,
            MealPlanStatus status,
            Integer dailyCalorieTargetSnapshot,
            Instant confirmedAt,
            Instant createdAt,
            Instant updatedAt,
            List<MealPlanEntryResponse> entries,
            NutritionValues nutritionTotal,
            Integer dailyCalorieTarget) {
        this(
                id,
                planDate,
                status,
                dailyCalorieTargetSnapshot,
                confirmedAt,
                createdAt,
                updatedAt,
                entries,
                nutritionTotal,
                dailyCalorieTarget,
                List.of(),
                List.of());
    }

    public MealPlanResponse withHousehold(
            List<dev.jeefox.smartmeal.household.HouseholdModels.Participant> ps,
            List<MealPlanEntryResponse> items,
            Integer target) {
        return new MealPlanResponse(
                id,
                planDate,
                status,
                dailyCalorieTargetSnapshot,
                confirmedAt,
                createdAt,
                updatedAt,
                items,
                nutritionTotal,
                target,
                ps,
                dev.jeefox.smartmeal.household.HouseholdPortions.totals(
                        ps, items.stream().flatMap(e -> e.personPortions().stream()).toList()));
    }

    public MealPlanResponse {
        entries = List.copyOf(entries);
    }

    public static MealPlanResponse from(
            MealPlan plan,
            List<MealPlanEntryResponse> entries,
            NutritionValues nutritionTotal,
            Integer dailyCalorieTarget) {
        return new MealPlanResponse(
                plan.getId(),
                plan.getPlanDate(),
                plan.getStatus(),
                plan.getDailyCalorieTargetSnapshot(),
                plan.getConfirmedAt(),
                plan.getCreatedAt(),
                plan.getUpdatedAt(),
                entries,
                nutritionTotal,
                dailyCalorieTarget);
    }
}
