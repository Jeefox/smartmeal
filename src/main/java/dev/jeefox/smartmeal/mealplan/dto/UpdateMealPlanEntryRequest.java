package dev.jeefox.smartmeal.mealplan.dto;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record UpdateMealPlanEntryRequest(
        @NotNull @Positive @Digits(integer = 4, fraction = 2) BigDecimal portions
) {
}
