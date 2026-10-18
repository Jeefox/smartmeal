package dev.jeefox.smartmeal.mealplan.dto;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.util.UUID;

public record AddMealPlanEntryRequest(
        @NotNull UUID recipeId,
        @NotNull MealType mealType,
        @NotNull @Positive @Digits(integer = 4, fraction = 2) BigDecimal portions
) {
}
