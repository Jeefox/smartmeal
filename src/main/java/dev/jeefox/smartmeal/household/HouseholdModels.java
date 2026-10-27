package dev.jeefox.smartmeal.household;

import dev.jeefox.smartmeal.nutrition.dto.NutritionValues;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.*;

public final class HouseholdModels {
    private HouseholdModels() {}

    public record MemberRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull @Min(500) @Max(10000) Integer dailyCalorieTarget,
            @NotNull @Size(max = 50) List<@NotNull @Positive Long> allergenIds,
            @NotNull @Size(max = 500) List<@NotNull @Positive Long> excludedProductIds) {}

    public record ProductChoice(Long id, String name) {}

    public record Member(
            UUID id,
            String name,
            int dailyCalorieTarget,
            List<Long> allergenIds,
            List<Long> excludedProductIds,
            List<ProductChoice> excludedProducts) {}

    public record Participant(
            UUID id,
            String name,
            int dailyCalorieTarget,
            boolean accountOwner,
            BigDecimal proteinTargetG,
            BigDecimal fiberTargetG) {
        public Participant(UUID id, String name, int dailyCalorieTarget, boolean accountOwner) {
            this(id, name, dailyCalorieTarget, accountOwner, null, null);
        }
    }

    public record PersonPortion(
            UUID participantId, String name, BigDecimal portions, NutritionValues nutrition) {}

    public record PersonNutrition(
            UUID participantId,
            String name,
            int dailyCalorieTarget,
            NutritionValues nutrition,
            BigDecimal proteinTargetG,
            BigDecimal fiberTargetG) {
        public PersonNutrition(
                UUID participantId,
                String name,
                int dailyCalorieTarget,
                NutritionValues nutrition) {
            this(participantId, name, dailyCalorieTarget, nutrition, null, null);
        }
    }
}
