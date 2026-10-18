package dev.jeefox.smartmeal.user.dto;

import dev.jeefox.smartmeal.user.entity.ActivityLevel;
import dev.jeefox.smartmeal.user.entity.Goal;
import dev.jeefox.smartmeal.user.entity.Sex;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;

public record UpdateUserProfileRequest(

        @NotNull
        @Min(18)
        Short age,
        @NotNull
        Sex sex,
        @NotNull
        @Positive
        Integer heightCm,
        @NotNull
        @Positive
        @Digits(integer = 3, fraction = 2)
        BigDecimal weightKg,
        @NotNull
        ActivityLevel activityLevel,
        @NotNull
        Goal goal
) {
}
