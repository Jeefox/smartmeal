package dev.jeefox.smartmeal.user.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record UpdateDailyCalorieTargetRequest(
        @NotNull
        @Positive
        Integer dailyCalorieTarget
) {
}
