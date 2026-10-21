package dev.jeefox.smartmeal.mealplan.dto;

import dev.jeefox.smartmeal.mealplan.entity.MealType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record SaveMealPlanPreviewRequest(
        @NotNull @Valid PreviewMealPlanRequest options,
        @NotNull @Positive Integer dailyCalorieTarget,
        @NotNull @Size(min = 3, max = 3) List<@NotNull @Valid Entry> entries
) {
    public record Entry(
            @NotNull MealType mealType,
            @NotNull UUID recipeId,
            @NotNull @DecimalMin("0.5") @DecimalMax("2") @Digits(integer = 1, fraction = 2) BigDecimal portions,
            @NotNull @Pattern(regexp = "[a-f0-9]{64}") String recipeFingerprint
    ) {
    }
}
