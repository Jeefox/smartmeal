package dev.jeefox.smartmeal.pantry;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

public final class CookingModels {
    private CookingModels() {}

    public record Ref(@NotNull UUID entryId, @Size(max = 120) String componentId) {}

    public record Create(
            @Min(0) long expectedRevision,
            @NotNull LocalDate cookOn,
            @NotNull LocalDate useBy,
            @NotEmpty @Size(max = 42) List<@Valid Ref> entries) {}

    public record Prepare(
            @Min(0) long expectedRevision,
            @NotNull @Positive @Digits(integer = 6, fraction = 2) BigDecimal actualYieldG) {}

    public record Assign(
            @Min(0) long expectedRevision, @NotEmpty @Size(max = 42) List<@Valid Ref> entries) {}

    public record Confirm(
            @Min(0) long expectedRevision, @NotNull LocalDate from, @NotNull LocalDate to) {}

    public record Ingredient(String name, BigDecimal quantityG) {}

    public record Draft(UUID id, LocalDate date) {}

    public record Candidate(
            Ref ref,
            LocalDate date,
            String mealType,
            String recipeName,
            String fingerprint,
            BigDecimal cookedG) {}

    public record Allocation(
            UUID id,
            UUID entryId,
            String componentId,
            LocalDate date,
            String mealType,
            BigDecimal cookedG,
            String status,
            boolean mealReady) {}

    public record Batch(
            UUID id,
            String name,
            LocalDate cookOn,
            LocalDate useBy,
            String status,
            BigDecimal estimatedYieldG,
            BigDecimal actualYieldG,
            BigDecimal remainingG,
            BigDecimal freeG,
            boolean expired,
            String fingerprint,
            List<Allocation> allocations,
            UUID recipeId,
            String instructions,
            List<Ingredient> ingredients) {}

    public record View(
            long revision,
            LocalDate today,
            List<Candidate> candidates,
            List<Batch> batches,
            List<Draft> drafts) {}
}
