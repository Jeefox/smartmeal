package dev.jeefox.smartmeal.user.dto;

import dev.jeefox.smartmeal.user.entity.ActivityLevel;
import dev.jeefox.smartmeal.user.entity.Goal;
import dev.jeefox.smartmeal.user.entity.Sex;
import dev.jeefox.smartmeal.user.entity.UserProfile;

import java.math.BigDecimal;
import java.util.UUID;

public record UserProfileResponse(
        UUID userId,
        Integer dailyCalorieTarget,
        Short age,
        Sex sex,
        Integer heightCm,
        BigDecimal weightKg,
        ActivityLevel activityLevel,
        Goal goal,
        boolean canEditCatalogue,
        boolean equipmentConfigured) {
    public UserProfileResponse withCataloguePermission(boolean allowed) {
        return new UserProfileResponse(
                userId,
                dailyCalorieTarget,
                age,
                sex,
                heightCm,
                weightKg,
                activityLevel,
                goal,
                allowed,
                equipmentConfigured);
    }

    public UserProfileResponse withKitchenConfigured(boolean configured) {
        return new UserProfileResponse(
                userId,
                dailyCalorieTarget,
                age,
                sex,
                heightCm,
                weightKg,
                activityLevel,
                goal,
                canEditCatalogue,
                configured);
    }

    public static UserProfileResponse from(UserProfile profile) {
        return new UserProfileResponse(
                profile.getUserId(),
                profile.getDailyCalorieTarget(),
                profile.getAge(),
                profile.getSex(),
                profile.getHeightCm(),
                profile.getWeightKg(),
                profile.getActivityLevel(),
                profile.getGoal(),
                false,
                false);
    }
}
