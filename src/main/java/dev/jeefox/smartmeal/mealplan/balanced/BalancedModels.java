package dev.jeefox.smartmeal.mealplan.balanced;

import dev.jeefox.smartmeal.mealplan.snapshot.RecipeSnapshot;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.*;

public final class BalancedModels {
    private BalancedModels() {}

    public enum Role {
        COMPLETE_MAIN,
        PROTEIN_MAIN,
        SIDE,
        VEGETABLE,
        SOUP,
        BREAKFAST
    }

    public enum YieldQuality {
        MEASURED,
        AUTHOR_REPORTED,
        ESTIMATED,
        UNKNOWN
    }

    public record Profile(
            @NotNull Role role,
            @Positive @Digits(integer = 8, fraction = 2) BigDecimal cookedYieldG,
            @NotNull YieldQuality yieldQuality,
            @Size(max = 1000) String source,
            @NotNull @Positive BigDecimal minG,
            @NotNull @Positive BigDecimal maxG,
            @NotNull @Positive BigDecimal stepG,
            boolean containsStarch,
            boolean containsVegetables) {}

    public record Fiber(
            @DecimalMin("0") @DecimalMax("100") BigDecimal fiberPer100g,
            @Size(max = 1000) String source,
            @NotNull String quality) {}

    public record Targets(
            @Min(0) long expectedRevision,
            boolean adultConfirmed,
            @DecimalMin("20") @DecimalMax("400") BigDecimal weightKg,
            @DecimalMin("0") @DecimalMax("400") BigDecimal proteinTargetG,
            @DecimalMin("0") @DecimalMax("100") BigDecimal fiberTargetG,
            @NotNull @DecimalMin("250") @DecimalMax("1200") BigDecimal maxMealG,
            @NotNull String targetSource) {}

    public record TargetView(UUID participantId, String name, long revision, Targets settings) {}

    public record Amount(UUID participantId, BigDecimal multiplier, BigDecimal cookedG) {}

    public record Component(
            String id,
            Role role,
            RecipeSnapshot recipe,
            Profile profile,
            List<Amount> amounts,
            boolean locked) {
        public Component {
            amounts = List.copyOf(amounts);
        }
    }

    public record Context(
            Map<UUID, Profile> profiles, Map<Long, Fiber> fiber, Map<UUID, Targets> targets) {
        public Context {
            profiles = Map.copyOf(profiles);
            fiber = Map.copyOf(fiber);
            targets = Map.copyOf(targets);
        }

        public static Context empty() {
            return new Context(Map.of(), Map.of(), Map.of());
        }
    }

    public record FeedbackRequest(@Min(0) long expectedRevision, @NotNull String rating) {}

    public record Feedback(UUID participantId, String rating, long revision) {}
}
