package dev.jeefox.smartmeal.user.dto;

import java.util.UUID;

public record UserProfileResponse(
        UUID userId,
        Integer dailyCalorieTarget
) {
}
