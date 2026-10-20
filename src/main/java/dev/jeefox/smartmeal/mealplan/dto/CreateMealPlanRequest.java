package dev.jeefox.smartmeal.mealplan.dto;

import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;

public record CreateMealPlanRequest(
        @NotNull
        LocalDate planDate
) {
}
